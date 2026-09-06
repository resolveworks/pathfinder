package works.resolve.pathfinder.codingagent.core

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.codingagent.core.CompactionEntry
import works.resolve.pathfinder.codingagent.core.RetrySettings
import works.resolve.pathfinder.codingagent.core.SessionManager
import works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings
import works.resolve.pathfinder.codingagent.core.createCompactionSummaryMessage

class AgentCompactionTest {

    private val longPrompt = "x".repeat(60_000)

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid",
        contextWindow = 200_000,
        maxTokens = 8_192
    )

    private fun assistant(
        text: String = "hello",
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null,
        usage: Usage = Usage(),
        timestamp: Long = System.currentTimeMillis(),
        provider: String = model.provider,
        modelId: String = model.id
    ) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = model.api,
        provider = provider,
        model = modelId,
        stopReason = stopReason,
        errorMessage = errorMessage,
        usage = usage,
        timestamp = timestamp
    )

    private class FauxApi : ChatApi {
        val responses = ArrayDeque<AssistantMessage>()
        var gate: CompletableDeferred<Unit>? = null

        override fun streamSimple(
            model: Model,
            context: Context,
            options: SimpleStreamOptions
        ): Flow<AssistantMessageEvent> = flow {
            gate?.await()
            val response = responses.removeFirstOrNull()
                ?: error("No faux summary response queued")
            if (response.stopReason == StopReason.ERROR ||
                response.stopReason == StopReason.ABORTED
            ) {
                emit(AssistantMessageEvent.Error(response.stopReason, response))
            } else {
                emit(AssistantMessageEvent.Done(response.stopReason, response))
            }
        }
    }

    private fun fauxModels(): Pair<FauxApi, Models> {
        val api = FauxApi()
        val models = Models(
            listOf(
                Provider(
                    model.provider,
                    model.provider,
                    "https://faux.test",
                    authResolver = { _, _ -> ResolvedAuth(apiKey = "faux-key") },
                    models = listOf(model),
                    apis = mapOf(model.api to api)
                )
            )
        )
        return api to models
    }

    /**
     * A session pre-seeded with one large prior exchange so the cut lands
     * on each prompt's user message with a non-empty summarize range.
     */
    private suspend fun seededManager(): SessionManager {
        val manager = SessionManager.create(
            createTempDirectory("compaction-test").toFile(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        manager.appendMessage(UserMessage.ofText(longPrompt))
        manager.appendMessage(assistant("prior turn"))
        return manager
    }

    private class ScriptedStreams {
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val seenContexts = CopyOnWriteArrayList<List<works.resolve.pathfinder.ai.Message>>()
        val streamFn = StreamFn { _, context, _ ->
            seenContexts.add(context.messages)
            streams.removeFirstOrNull() ?: flow { awaitCancellation() }
        }
    }

    private suspend fun session(
        streams: ScriptedStreams,
        models: Models?,
        // The synthetic prompts are large so the cut lands on the user
        // message with a non-empty summarize range; pi returns no
        // preparation when nothing would be summarized.
        compactionSettings: CompactionSettings =
            CompactionSettings(enabled = true, reserveTokens = 16_384, keepRecentTokens = 10_000),
        retrySettings: RetrySettings = RetrySettings(enabled = false),
        sleep: suspend (Long) -> Unit = { }
    ) = AgentSession(
        agent = Agent(
            model = model,
            streamFn = streams.streamFn
        ),
        manager = seededManager(),
        retrySettings = retrySettings,
        compactionSettings = compactionSettings,
        models = models,
        sleep = sleep
    )

    private suspend fun collectEvents(agent: AgentSession): MutableList<AgentEvent> =
        kotlinx.coroutines.coroutineScope {
            val events = mutableListOf<AgentEvent>()
            val collector = launch { agent.events.toList(events) }
            yield()
            agent.prompt(longPrompt)
            collector.cancelAndJoin()
            events
        }

    @Test
    fun `threshold usage triggers compaction, rebuild, and a full end event`() = runTest {
        val (api, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Start(assistant("")),
                    AssistantMessageEvent.Done(
                        StopReason.STOP,
                        assistant(
                            "long",
                            usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)
                        )
                    )
                )
            )
        }
        api.responses.add(
            assistant("SUMMARY", usage = Usage(input = 100, output = 50, totalTokens = 150))
        )
        val agent = session(streams, models)

        val events = collectEvents(agent)

        val start = events.filterIsInstance<AgentEvent.CompactionStart>().single()
        assertEquals(AgentEvent.CompactionReason.THRESHOLD, start.reason)
        val end = events.filterIsInstance<AgentEvent.CompactionEnd>().single()
        assertEquals(AgentEvent.CompactionReason.THRESHOLD, end.reason)
        assertFalse(end.aborted)
        assertFalse(end.willRetry)
        assertNull(end.errorMessage)
        val result = end.result!!
        assertEquals("SUMMARY", result.summary)
        assertEquals(190_010, result.tokensBefore)
        assertTrue(result.estimatedTokensAfter < result.tokensBefore)
        assertEquals(150, result.usage!!.totalTokens)

        assertEquals(1, streams.seenContexts.size)
        val entries = agent.sessionManager.getBranch()
        val compaction = entries.last() as CompactionEntry
        assertEquals("SUMMARY", compaction.summary)
        assertEquals(
            agent.sessionManager.getEntries()[2].id,
            compaction.firstKeptEntryId
        )
        val rebuilt = agent.state.value.messages
        // Summary message + the kept entries (prompt user + assistant).
        assertEquals(3, rebuilt.size)
        val summaryMessage = rebuilt.first() as UserMessage
        assertEquals(
            createCompactionSummaryMessage(
                "SUMMARY",
                compaction.tokensBefore,
                summaryMessage.timestamp
            ),
            summaryMessage
        )
    }

    @Test
    fun `threshold estimate path compacts on zero-usage error responses`() = runTest {
        val (api, models) = fauxModels()
        val bigTail = "x".repeat(900_000) // estimate ≫ threshold without usage (chars/4 heuristic)
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Error(
                        StopReason.ERROR,
                        assistant("", StopReason.ERROR, "boom")
                    )
                )
            )
        }
        // Previous assistant with huge text gives a pure-size estimate.
        val seed = SessionManager.create(
            createTempDirectory("compaction-test").toFile(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        seed.appendMessage(UserMessage.ofText("hi", 1L))
        seed.appendMessage(assistant(bigTail, timestamp = 2L))
        api.responses.add(assistant("SUMMARY"))
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = streams.streamFn),
            manager = seed,
            retrySettings = RetrySettings(enabled = false),
            models = models
        )

        val events = collectEvents(agent)

        assertEquals(
            AgentEvent.CompactionReason.THRESHOLD,
            events.filterIsInstance<AgentEvent.CompactionStart>().single().reason
        )
        assertTrue(events.filterIsInstance<AgentEvent.CompactionEnd>().single().result != null)
    }

    @Test
    fun `disabled compaction settings never compact`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Done(
                        StopReason.STOP,
                        assistant(
                            "long",
                            usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)
                        )
                    )
                )
            )
        }
        val agent =
            session(
                streams,
                null,
                CompactionSettings(
                    enabled = false,
                    reserveTokens = 16_384,
                    keepRecentTokens = 20_000
                )
            )

        val events = collectEvents(agent)

        assertTrue(events.filterIsInstance<AgentEvent.CompactionStart>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.CompactionEnd>().isEmpty())
    }

    private fun overflowError() =
        assistant("", StopReason.ERROR, "prompt is too long: 300000 tokens > 200000 maximum")

    @Test
    fun `overflow error compacts once and retries the turn, second overflow fails without looping`() =
        runTest {
            val (api, models) = fauxModels()
            val streams = ScriptedStreams().apply {
                streams.add(flowOf(AssistantMessageEvent.Error(StopReason.ERROR, overflowError())))
                // The retried turn overflows again: recovery must give up. The
                // message is created lazily with an explicitly post-compaction
                // timestamp so the pre-compaction-boundary guard cannot swallow
                // it when wall-clock and the appended entry share a millisecond.
                streams.add(
                    flow {
                        emit(
                            AssistantMessageEvent.Error(
                                StopReason.ERROR,
                                overflowError().copy(
                                    timestamp = System.currentTimeMillis() + 10_000
                                )
                            )
                        )
                    }
                )
            }
            api.responses.add(assistant("SUMMARY"))
            val agent = session(streams, models)

            val events = collectEvents(agent)
            val ends = events.filterIsInstance<AgentEvent.CompactionEnd>()
            assertEquals(2, ends.size)
            val recovery = ends[0]
            assertEquals(AgentEvent.CompactionReason.OVERFLOW, recovery.reason)
            assertTrue(recovery.willRetry)
            assertFalse(recovery.aborted)
            assertNotNull(recovery.result)
            val failure = ends[1]
            assertEquals(AgentEvent.CompactionReason.OVERFLOW, failure.reason)
            assertFalse(failure.willRetry)
            assertFalse(failure.aborted)
            assertNull(failure.result)
            assertEquals(
                "Context overflow recovery failed after one compact-and-retry attempt. " +
                    "Try reducing context or switching to a larger-context model.",
                failure.errorMessage
            )
            assertEquals(2, streams.seenContexts.size)
            // The first overflow error is removed before the retry; the second stays as
            // the final message.
            val last = agent.state.value.messages.last() as AssistantMessage
            assertEquals(overflowError().errorMessage, last.errorMessage)
        }

    @Test
    fun `overflow retry strips the restored trailing error after the rebuild`() = runTest {
        val (api, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(flowOf(AssistantMessageEvent.Error(StopReason.ERROR, overflowError())))
            streams.add(
                flowOf(
                    AssistantMessageEvent.Start(assistant("")),
                    AssistantMessageEvent.Done(StopReason.STOP, assistant("recovered"))
                )
            )
        }
        api.responses.add(assistant("SUMMARY"))
        val agent = session(streams, models)

        val events = collectEvents(agent)

        val end = events.filterIsInstance<AgentEvent.CompactionEnd>().single()
        assertTrue(end.willRetry)
        val last = agent.state.value.messages.last() as AssistantMessage
        assertEquals("recovered", (last.content.single() as TextContent).text)
        // The overflow error was removed twice: pre-compaction and post-rebuild.
        val retryContext = streams.seenContexts[1]
        assertFalse(
            retryContext.any { it is AssistantMessage && it.stopReason == StopReason.ERROR }
        )
    }

    @Test
    fun `recoverable length stop triggers overflow recovery with willRetry`() = runTest {
        val (api, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Done(
                        StopReason.LENGTH,
                        assistant(
                            "partial",
                            stopReason = StopReason.LENGTH,
                            usage = Usage(input = 100, output = 16)
                        )
                    )
                )
            )
            streams.add(flowOf(AssistantMessageEvent.Done(StopReason.STOP, assistant("full"))))
        }
        api.responses.add(assistant("SUMMARY"))
        val agent = session(streams, models)

        val events = collectEvents(agent)

        val end = events.filterIsInstance<AgentEvent.CompactionEnd>().single()
        assertEquals(AgentEvent.CompactionReason.OVERFLOW, end.reason)
        assertTrue(end.willRetry)
        assertEquals(2, streams.seenContexts.size)
    }

    @Test
    fun `overflow error from a different model never compacts`() = runTest {
        val (_, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Error(
                        StopReason.ERROR,
                        overflowError().copy(provider = "other", model = "opus")
                    )
                )
            )
        }
        val agent = session(streams, models)

        val events = collectEvents(agent)

        assertTrue(events.filterIsInstance<AgentEvent.CompactionStart>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.CompactionEnd>().isEmpty())
    }

    @Test
    fun `aborted messages skip compaction`() = runTest {
        val (_, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Error(
                        StopReason.ABORTED,
                        assistant("", StopReason.ABORTED)
                    )
                )
            )
        }
        val agent = session(streams, models)

        val events = collectEvents(agent)

        assertTrue(events.filterIsInstance<AgentEvent.CompactionStart>().isEmpty())
    }

    @Test
    fun `stale pre-compaction usage does not retrigger after a compaction`() = runTest {
        val (api, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Done(
                        StopReason.STOP,
                        assistant(
                            "long",
                            usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)
                        )
                    )
                )
            )
            // The zero-usage error estimate falls back to the retained
            // (pre-compaction) assistant's huge usage, which must not compact again.
            streams.add(
                flowOf(
                    AssistantMessageEvent.Error(
                        StopReason.ERROR,
                        assistant("", StopReason.ERROR, "boom")
                    )
                )
            )
        }
        api.responses.add(assistant("SUMMARY"))
        val agent = session(streams, models)

        val events = kotlinx.coroutines.coroutineScope {
            val collected = mutableListOf<AgentEvent>()
            val collector = launch { agent.events.toList(collected) }
            yield()
            agent.prompt(longPrompt)
            agent.prompt(longPrompt)
            collector.cancelAndJoin()
            collected
        }

        assertEquals(1, events.filterIsInstance<AgentEvent.CompactionStart>().size)
        assertEquals(1, events.filterIsInstance<AgentEvent.CompactionEnd>().size)
    }

    @Test
    fun `summarization failure emits the formatted threshold failure event`() = runTest {
        val (api, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Done(
                        StopReason.STOP,
                        assistant(
                            "long",
                            usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)
                        )
                    )
                )
            )
        }
        api.responses.add(assistant("", StopReason.ERROR, "boom"))
        val agent = session(streams, models)

        val events = collectEvents(agent)

        val end = events.filterIsInstance<AgentEvent.CompactionEnd>().single()
        assertFalse(end.aborted)
        assertNull(end.result)
        assertEquals("Auto-compaction failed: Summarization failed: boom", end.errorMessage)
        assertTrue(
            agent.sessionManager.getBranch().none {
                it is works.resolve.pathfinder.codingagent.core.CompactionEntry
            }
        )
    }

    @Test
    fun `prompt is rejected while compaction is in progress`() = runTest {
        // pi's prompt() guard covers only manual compaction: compact() sets
        // its abort controller before emitting compaction_start
        // (agent-session.ts 1948-49), so observing the event means the guard
        // is armed. Auto compaction emits before setting its controller
        // (2267-68) and is not guarded by prompt() at all.
        val (api, models) = fauxModels()
        val seed = SessionManager.create(
            createTempDirectory("compaction-test").toFile(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        seed.appendMessage(UserMessage.ofText("hi", 1L))
        seed.appendMessage(assistant("x".repeat(900_000), timestamp = 2L))
        api.responses.add(assistant("SUMMARY"))
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = ScriptedStreams().streamFn),
            manager = seed,
            retrySettings = RetrySettings(enabled = false),
            models = models
        )

        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        val run = launch { agent.compact() }
        while (!events.any { it is AgentEvent.CompactionStart }) yield()
        try {
            agent.prompt("second")
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Cannot submit a prompt while compaction is in progress. Wait for compaction " +
                    "to finish and retry.",
                e.message
            )
        }
        gate.complete(Unit)
        run.join()
        collector.cancelAndJoin()
        assertTrue(events.any { it is AgentEvent.CompactionEnd })
    }

    @Test
    fun `summarization retries map to the retry events`() = runTest {
        val (api, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Done(
                        StopReason.STOP,
                        assistant(
                            "long",
                            usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)
                        )
                    )
                )
            )
        }
        api.responses.add(assistant("", StopReason.ERROR, "terminated"))
        api.responses.add(assistant("SUMMARY"))
        val delays = mutableListOf<Long>()
        val agent = session(
            streams,
            models,
            retrySettings = RetrySettings(enabled = true, maxRetries = 3, baseDelayMs = 2000),
            sleep = { delays.add(it) }
        )

        val events = collectEvents(agent)

        val scheduled = events.filterIsInstance<AgentEvent.SummarizationRetryScheduled>().single()
        assertEquals(1, scheduled.attempt)
        assertEquals(3, scheduled.maxAttempts)
        assertEquals(2000L, scheduled.delayMs)
        assertEquals("terminated", scheduled.errorMessage)
        assertEquals(
            listOf(
                AgentEvent.SummarizationSource.Compaction(AgentEvent.CompactionReason.THRESHOLD)
            ),
            events.filterIsInstance<AgentEvent.SummarizationRetryAttemptStart>().map { it.source }
        )
        assertEquals(1, events.filterIsInstance<AgentEvent.SummarizationRetryFinished>().size)
        assertTrue(events.filterIsInstance<AgentEvent.CompactionEnd>().single().result != null)
    }

    @Test
    fun `pre-prompt check compacts an aborted response the post-run check skipped`() = runTest {
        val (api, models) = fauxModels()
        val bigTail = "x".repeat(900_000)
        val seed = SessionManager.create(
            createTempDirectory("compaction-test").toFile(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        seed.appendMessage(UserMessage.ofText("hi", 1L))
        seed.appendMessage(assistant(bigTail, timestamp = 2L))
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Error(
                        StopReason.ABORTED,
                        assistant("", StopReason.ABORTED)
                    )
                )
            )
            streams.add(flowOf(AssistantMessageEvent.Done(StopReason.STOP, assistant("ok"))))
        }
        api.responses.add(assistant("SUMMARY"))
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = streams.streamFn),
            manager = seed,
            retrySettings = RetrySettings(enabled = false),
            models = models
        )

        val events = kotlinx.coroutines.coroutineScope {
            val collected = mutableListOf<AgentEvent>()
            val collector = launch { agent.events.toList(collected) }
            yield()
            agent.prompt("first")
            agent.prompt("second")
            collector.cancelAndJoin()
            collected
        }

        // No compaction after the aborted run; the pre-prompt check on the
        // second prompt compacts before the new user message is sent.
        val start = events.filterIsInstance<AgentEvent.CompactionStart>().single()
        assertEquals(AgentEvent.CompactionReason.THRESHOLD, start.reason)
        val compactionIndex = events.indexOf(start)
        val userStarts = events.indexOfLast {
            it is AgentEvent.MessageStart && it.message is UserMessage
        }
        assertTrue(compactionIndex < userStarts)
        // The second provider request runs on the rebuilt context.
        val secondContext = streams.seenContexts[1]
        val rebuilt = secondContext.first() as UserMessage
        assertTrue((rebuilt.content.single() as TextContent).text.contains("SUMMARY"))
    }

    @Test
    fun `mid-run checkpoint compacts between turns before the next assistant response`() = runTest {
        val (api, models) = fauxModels()
        // The seeded exchange stays below the threshold so the pre-prompt
        // check does not fire; turn one's huge response grows the context
        // past it mid-run.
        val seed = SessionManager.create(
            createTempDirectory("compaction-test").toFile(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        seed.appendMessage(UserMessage.ofText("hi", 1L))
        seed.appendMessage(assistant("prior", timestamp = 2L))
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Done(
                        StopReason.TOOL_USE,
                        assistant("", stopReason = StopReason.TOOL_USE)
                            .copy(
                                content = listOf(
                                    TextContent("x".repeat(900_000)),
                                    works.resolve.pathfinder.ai.ToolCall("c1", "missing", "{}")
                                )
                            )
                    )
                )
            )
            streams.add(flowOf(AssistantMessageEvent.Done(StopReason.STOP, assistant("turn two"))))
        }
        api.responses.add(assistant("SUMMARY"))
        api.responses.add(assistant("SUMMARY"))
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = streams.streamFn),
            manager = seed,
            retrySettings = RetrySettings(enabled = false),
            compactionSettings = CompactionSettings(
                enabled = true,
                reserveTokens = 16_384,
                keepRecentTokens = 10_000
            ),
            models = models
        )

        val events = collectEvents(agent)

        // The kept-tail cut lands inside turn one's huge response, so the
        // compaction splits the turn and issues two summarization calls
        // (pi compaction.ts: history + turn prefix); both are queued above.
        // It is the only compaction: the post-run check sees the rebuilt
        // context (summary + kept tail) far below the threshold.
        val starts = events.filterIsInstance<AgentEvent.CompactionStart>()
        assertEquals(1, starts.size)
        val start = starts.single()
        assertEquals(AgentEvent.CompactionReason.THRESHOLD, start.reason)
        // The checkpoint runs after the first turn_end and before the second
        // turn_start.
        val turnStarts = events.filterIsInstance<AgentEvent.TurnStart>()
        assertEquals(2, turnStarts.size)
        val turnEnd1 = events.indexOf(events.first { it is AgentEvent.TurnEnd })
        val turnStart2 = events.lastIndexOf(turnStarts[1])
        val compactionIndex = events.indexOf(start)
        assertTrue(turnEnd1 < compactionIndex && compactionIndex < turnStart2)
        // The second provider request runs on the rebuilt context.
        val secondContext = streams.seenContexts[1]
        val first = secondContext.first() as UserMessage
        assertTrue((first.content.single() as TextContent).text.contains("SUMMARY"))
    }

    @Test
    fun `manual compact runs the machinery with reason manual and rebuilds context`() = runTest {
        val (api, models) = fauxModels()
        val seed = SessionManager.create(
            createTempDirectory("compaction-test").toFile(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        seed.appendMessage(UserMessage.ofText("hi", 1L))
        seed.appendMessage(assistant("x".repeat(900_000), timestamp = 2L))
        api.responses.add(assistant("SUMMARY"))
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = ScriptedStreams().streamFn),
            manager = seed,
            retrySettings = RetrySettings(enabled = false),
            models = models
        )

        val events = kotlinx.coroutines.coroutineScope {
            val collected = mutableListOf<AgentEvent>()
            val collector = launch { agent.events.toList(collected) }
            yield()
            val result = agent.compact()
            collector.cancelAndJoin()
            collected to result
        }
        val (collected, result) = events

        assertTrue(result.summary.contains("SUMMARY"))
        assertEquals(
            AgentEvent.CompactionReason.MANUAL,
            collected.filterIsInstance<AgentEvent.CompactionStart>().single().reason
        )
        val end = collected.filterIsInstance<AgentEvent.CompactionEnd>().single()
        assertEquals(AgentEvent.CompactionReason.MANUAL, end.reason)
        assertFalse(end.aborted)
        assertEquals(result, end.result)
        // The transcript is rebuilt from the compaction boundary.
        assertTrue(
            agent.state.value.messages.first() is UserMessage &&
                (
                    (agent.state.value.messages.first() as UserMessage).content.single()
                        as TextContent
                    ).text.contains("SUMMARY")
        )

        // Compacting again hits the already-compacted leaf.
        val second = runCatching { agent.compact() }.exceptionOrNull()
        assertTrue(second is IllegalStateException)
        assertEquals("Already compacted", second!!.message)
    }

    @Test
    fun `manual compact on a too-small session reports nothing to compact`() = runTest {
        val (api, models) = fauxModels()
        val manager = SessionManager.create(
            createTempDirectory("compaction-test").toFile(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        manager.appendMessage(UserMessage.ofText("hi"))
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = ScriptedStreams().streamFn),
            manager = manager,
            models = models
        )

        val error = runCatching { agent.compact() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("Nothing to compact (session too small)", error!!.message)
    }
}
