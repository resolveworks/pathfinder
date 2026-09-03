package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.*

import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings
import works.resolve.pathfinder.codingagent.core.compaction.createCompactionSummaryMessage
import works.resolve.pathfinder.codingagent.core.RetrySettings
import java.util.concurrent.CopyOnWriteArrayList
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

class AgentCompactionTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid",
        contextWindow = 200_000,
        maxTokens = 8_192,
    )

    private fun assistant(
        text: String = "hello",
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null,
        usage: Usage = Usage(),
        timestamp: Long = System.currentTimeMillis(),
        provider: String = model.provider,
        modelId: String = model.id,
    ) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = model.api,
        provider = provider,
        model = modelId,
        stopReason = stopReason,
        errorMessage = errorMessage,
        usage = usage,
        timestamp = timestamp,
    )

    private class FauxApi : ChatApi {
        val responses = ArrayDeque<AssistantMessage>()
        var gate: CompletableDeferred<Unit>? = null

        override fun streamSimple(
            model: Model,
            context: Context,
            options: SimpleStreamOptions,
        ): Flow<AssistantMessageEvent> = flow {
            gate?.await()
            val response = responses.removeFirstOrNull()
                ?: error("No faux summary response queued")
            if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
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
                    apis = mapOf(model.api to api),
                ),
            ),
        )
        return api to models
    }

    private class ScriptedStreams {
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val seenContexts = CopyOnWriteArrayList<List<works.resolve.pathfinder.ai.Message>>()
        val streamFn = StreamFn { _, context, _ ->
            seenContexts.add(context.messages)
            streams.removeFirstOrNull() ?: flow { awaitCancellation() }
        }
    }

    private fun session(
        streams: ScriptedStreams,
        models: Models?,
        compactionSettings: CompactionSettings = CompactionSettings(enabled = true, reserveTokens = 16_384, keepRecentTokens = 20_000),
        retrySettings: RetrySettings = RetrySettings(enabled = false),
        sleep: suspend (Long) -> Unit = { },
    ) = AgentSession(
        agent = Agent(
            model = model,
            streamFn = streams.streamFn,
        ),
        retrySettings = retrySettings,
        compactionSettings = compactionSettings,
        models = models,
        sleep = sleep,
    )

    private suspend fun collectEvents(agent: AgentSession): MutableList<AgentEvent> = kotlinx.coroutines.coroutineScope {
        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        agent.prompt("hi")
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
                        assistant("long", usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)),
                    ),
                ),
            )
        }
        api.responses.add(assistant("SUMMARY", usage = Usage(input = 100, output = 50, totalTokens = 150)))
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
        val entries = agent.conversation.activeEntries()
        val compaction = entries.last() as works.resolve.pathfinder.codingagent.core.session.CompactionEntry
        assertEquals("SUMMARY", compaction.summary)
        val rebuilt = agent.state.value.messages
        assertEquals(compaction.retainedTail.size + 1, rebuilt.size)
        val summaryMessage = rebuilt.first() as works.resolve.pathfinder.ai.UserMessage
        assertEquals(createCompactionSummaryMessage("SUMMARY", compaction.tokensBefore, summaryMessage.timestamp), summaryMessage)
    }

    @Test
    fun `threshold estimate path compacts on zero-usage error responses`() = runTest {
        val (api, models) = fauxModels()
        val bigTail = "x".repeat(900_000) // estimate ≫ threshold without usage (chars/4 heuristic)
        val streams = ScriptedStreams().apply {
            streams.add(flowOf(AssistantMessageEvent.Error(StopReason.ERROR, assistant("", StopReason.ERROR, "boom"))))
        }
        // Previous assistant with huge text gives a pure-size estimate.
        val seed = works.resolve.pathfinder.codingagent.core.session.Conversation(
            listOf(
                works.resolve.pathfinder.codingagent.core.session.MessageEntry(
                    id = "u1", parentId = null, timestamp = 1L,
                    message = works.resolve.pathfinder.ai.UserMessage.ofText("hi", 1L),
                ),
                works.resolve.pathfinder.codingagent.core.session.MessageEntry(
                    id = "a1", parentId = "u1", timestamp = 2L,
                    message = assistant(bigTail, timestamp = 2L),
                ),
            ),
            "a1",
        )
        api.responses.add(assistant("SUMMARY"))
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = streams.streamFn),
            conversation = seed,
            retrySettings = RetrySettings(enabled = false),
            models = models,
        )

        val events = collectEvents(agent)

        assertEquals(
            AgentEvent.CompactionReason.THRESHOLD,
            events.filterIsInstance<AgentEvent.CompactionStart>().single().reason,
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
                        assistant("long", usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)),
                    ),
                ),
            )
        }
        val agent = session(streams, null, CompactionSettings(enabled = false, reserveTokens = 16_384, keepRecentTokens = 20_000))

        val events = collectEvents(agent)

        assertTrue(events.filterIsInstance<AgentEvent.CompactionStart>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.CompactionEnd>().isEmpty())
    }

    private fun overflowError() =
        assistant("", StopReason.ERROR, "prompt is too long: 300000 tokens > 200000 maximum")

    @Test
    fun `overflow error compacts once and retries the turn, second overflow fails without looping`() = runTest {
        val (api, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(flowOf(AssistantMessageEvent.Error(StopReason.ERROR, overflowError())))
            // The retried turn overflows again: recovery must give up. The
            // message is created lazily with an explicitly post-compaction
            // timestamp so the pre-compaction-boundary guard cannot swallow
            // it when wall-clock and the appended entry share a millisecond.
            streams.add(flow {
                emit(
                    AssistantMessageEvent.Error(
                        StopReason.ERROR,
                        overflowError().copy(timestamp = System.currentTimeMillis() + 10_000),
                    ),
                )
            })
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
            failure.errorMessage,
        )
        assertEquals(2, streams.seenContexts.size)
        // The first overflow error is removed before the retry; the second stays as the final message.
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
                    AssistantMessageEvent.Done(StopReason.STOP, assistant("recovered")),
                ),
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
            retryContext.any { it is AssistantMessage && it.stopReason == StopReason.ERROR },
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
                        assistant("partial", stopReason = StopReason.LENGTH, usage = Usage(input = 100, output = 16)),
                    ),
                ),
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
                        overflowError().copy(provider = "other", model = "opus"),
                    ),
                ),
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
                flowOf(AssistantMessageEvent.Error(StopReason.ABORTED, assistant("", StopReason.ABORTED))),
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
                        assistant("long", usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)),
                    ),
                ),
            )
            // The zero-usage error estimate falls back to the retained
            // (pre-compaction) assistant's huge usage, which must not compact again.
            streams.add(flowOf(AssistantMessageEvent.Error(StopReason.ERROR, assistant("", StopReason.ERROR, "boom"))))
        }
        api.responses.add(assistant("SUMMARY"))
        val agent = session(streams, models)

        val events = kotlinx.coroutines.coroutineScope {
            val collected = mutableListOf<AgentEvent>()
            val collector = launch { agent.events.toList(collected) }
            yield()
            agent.prompt("one")
            agent.prompt("two")
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
                        assistant("long", usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)),
                    ),
                ),
            )
        }
        api.responses.add(assistant("", StopReason.ERROR, "boom"))
        val agent = session(streams, models)

        val events = collectEvents(agent)

        val end = events.filterIsInstance<AgentEvent.CompactionEnd>().single()
        assertFalse(end.aborted)
        assertNull(end.result)
        assertEquals("Auto-compaction failed: Summarization failed: boom", end.errorMessage)
        assertTrue(agent.conversation.activeEntries().none { it is works.resolve.pathfinder.codingagent.core.session.CompactionEntry })
    }

    @Test
    fun `prompt is rejected while compaction is in progress`() = runTest {
        val (api, models) = fauxModels()
        val streams = ScriptedStreams().apply {
            streams.add(
                flowOf(
                    AssistantMessageEvent.Done(
                        StopReason.STOP,
                        assistant("long", usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)),
                    ),
                ),
            )
        }
        api.responses.add(assistant("SUMMARY"))
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val agent = session(streams, models)

        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        val run = launch {
            started.complete(Unit)
            agent.prompt("hi")
        }
        started.await()
        while (!events.any { it is AgentEvent.CompactionStart }) yield()
        try {
            agent.prompt("second")
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry.",
                e.message,
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
                        assistant("long", usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)),
                    ),
                ),
            )
        }
        api.responses.add(assistant("", StopReason.ERROR, "terminated"))
        api.responses.add(assistant("SUMMARY"))
        val delays = mutableListOf<Long>()
        val agent = session(
            streams,
            models,
            retrySettings = RetrySettings(enabled = true, maxRetries = 3, baseDelayMs = 2000),
            sleep = { delays.add(it) },
        )

        val events = collectEvents(agent)

        val scheduled = events.filterIsInstance<AgentEvent.SummarizationRetryScheduled>().single()
        assertEquals(1, scheduled.attempt)
        assertEquals(3, scheduled.maxAttempts)
        assertEquals(2000L, scheduled.delayMs)
        assertEquals("terminated", scheduled.errorMessage)
        assertEquals(
            listOf(AgentEvent.SummarizationSource.Compaction(AgentEvent.CompactionReason.THRESHOLD)),
            events.filterIsInstance<AgentEvent.SummarizationRetryAttemptStart>().map { it.source },
        )
        assertEquals(1, events.filterIsInstance<AgentEvent.SummarizationRetryFinished>().size)
        assertTrue(events.filterIsInstance<AgentEvent.CompactionEnd>().single().result != null)
    }
}
