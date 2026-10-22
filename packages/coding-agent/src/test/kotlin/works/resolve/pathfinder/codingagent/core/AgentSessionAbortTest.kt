package works.resolve.pathfinder.codingagent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.agent.AgentToolUpdateCallback
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FauxProvider

/**
 * pi's abort windows in the session layer: manual compaction, the pre-prompt
 * preflight compaction (abort cancels and awaits it, then pi's prompt
 * proceeds), the post-abort threshold compaction inside the loop's
 * NonCancellable tail, the lazy prompt-job publication, and the
 * "Prompt aborted" rethrow contract.
 */
class AgentSessionAbortTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid",
        contextWindow = 2_600,
        maxTokens = 100
    )

    private val clock = FakeClock()

    private fun assistant(
        text: String,
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null,
        usage: Usage = Usage()
    ) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = stopReason,
        errorMessage = errorMessage,
        usage = usage,
        timestamp = clock.now().toEpochMilliseconds()
    )

    private fun okStream(text: String): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(text = "")),
        AssistantMessageEvent.Done(
            StopReason.STOP,
            assistant(text = text, usage = Usage(totalTokens = 100))
        )
    )

    private class ScriptedStreams {
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val streamFn = StreamFn { _, _, _ ->
            streams.removeFirstOrNull() ?: flow { awaitCancellation() }
        }
    }

    /** Summarization API whose single response is held behind a gate. */
    private class GatedSummaryApi {
        val responses = ArrayDeque<AssistantMessage>()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val api = object : ChatApi {
            override fun streamSimple(
                model: Model,
                context: TranscriptContext,
                options: SimpleStreamOptions
            ): Flow<AssistantMessageEvent> = flow {
                entered.complete(Unit)
                gate.await()
                val response = responses.removeFirstOrNull()
                    ?: error("No faux summary response queued")
                emit(AssistantMessageEvent.Done(response.stopReason, response))
            }
        }
    }

    private fun summaryModels(api: ChatApi): Models = Models(
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

    private suspend fun newManager() = SessionManager.create(
        dir = createTempDirectory("abort-test").toFile(),
        clock = clock,
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun smallHistoryCompaction() = Settings(
        retry = RetrySettings(enabled = false),
        compaction = CompactionSettings(enabled = true, reserveTokens = 400, keepRecentTokens = 1)
    )

    private fun branchTexts(session: AgentSession): List<String> =
        session.sessionManager.getBranch()
            .filterIsInstance<MessageEntry>()
            .mapNotNull { entry -> messageText(entry.message) }

    private fun messageText(message: Message): String? = when (message) {
        is UserMessage -> (message.content.singleOrNull() as? TextContent)?.text
        is AssistantMessage -> (message.content.singleOrNull() as? TextContent)?.text
        else -> null
    }

    /**
     * abort during manual compaction: the summarization is cancelled, the
     * aborted compaction_end is emitted, abort() awaits it, and the
     * cancellation rethrows out of compact() (pi's compact rethrows too).
     */
    @Test
    fun `abort during manual compaction emits the aborted compaction_end and rethrows`() = runTest {
        val faux = GatedSummaryApi().apply { responses.add(assistant("## Summary")) }
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello"))
        clock.advanceMillis(1)
        manager.appendMessage(assistant("history: " + "a".repeat(40_000)))
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            manager = manager,
            models = summaryModels(faux.api),
            settingsManager = SettingsManager.inMemory(smallHistoryCompaction())
        )

        val sawEnd = CompletableDeferred<AgentEvent.CompactionEnd>()
        val collector = launch {
            session.events.collect { event ->
                if (event is AgentEvent.CompactionEnd && !sawEnd.isCompleted) {
                    sawEnd.complete(event)
                }
            }
        }
        yield()
        val failure = CompletableDeferred<Throwable>()
        val run = launch {
            try {
                session.compact()
            } catch (e: Throwable) {
                failure.complete(e)
            }
        }
        faux.entered.await()
        session.abort()
        val error = failure.await()
        run.join()
        collector.cancelAndJoin()

        assertTrue(error is CancellationException)
        // abort() awaited the compaction before returning.
        assertFalse(session.isCompacting)
        val end = sawEnd.await()
        assertTrue(end.aborted)
        assertEquals(AgentEvent.CompactionReason.MANUAL, end.reason)
        assertTrue(manager.getBranch().none { it is CompactionEntry })
    }

    /**
     * abort during the pre-prompt preflight compaction: pi's abortCompaction
     * cancels it, waitForIdle awaits it, and — because _runAutoCompaction
     * swallows the abort into compaction_end{aborted} — the prompt itself
     * still runs. The tracked pre-prompt job gives abort() that reach.
     */
    @Test
    fun `abort cancels and awaits pre-prompt compaction and the prompt proceeds`() = runTest {
        val faux = GatedSummaryApi().apply { responses.add(assistant("## Summary")) }
        val streams = ScriptedStreams().apply { streams.add(okStream("proceeded")) }
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello"))
        clock.advanceMillis(1)
        manager.appendMessage(assistant("history: " + "a".repeat(40_000)))
        clock.advanceMillis(1)
        manager.appendMessage(assistant("", stopReason = StopReason.ABORTED))
        val agent = Agent(model = model, streamFn = streams.streamFn).also {
            it.replaceTranscript(manager.buildSessionContext().messages)
        }
        val session = AgentSession(
            agent = agent,
            manager = manager,
            models = summaryModels(faux.api),
            settingsManager = SettingsManager.inMemory(smallHistoryCompaction())
        )

        val sawEnd = CompletableDeferred<AgentEvent.CompactionEnd>()
        val collector = launch {
            session.events.collect { event ->
                if (event is AgentEvent.CompactionEnd && !sawEnd.isCompleted) {
                    sawEnd.complete(event)
                }
            }
        }
        yield()
        val run = launch { session.prompt("next") }
        faux.entered.await()
        assertTrue(session.isCompacting)
        session.abort()
        // pi's waitForIdle: abort() returns only once the compaction settled.
        assertFalse(session.isCompacting)
        run.join()
        collector.cancelAndJoin()

        // pi's prompt proceeds past the aborted checkpoint.
        assertFalse(run.isCancelled)
        val end = sawEnd.await()
        assertTrue(end.aborted)
        assertEquals(AgentEvent.CompactionReason.THRESHOLD, end.reason)
        assertTrue(manager.getBranch().none { it is CompactionEntry })
        val texts = branchTexts(session)
        assertTrue("next" in texts)
        assertTrue("proceeded" in texts)
    }

    /**
     * pi's post-abort turn: the loop's extra iteration invokes the
     * prepareNextTurn hook, whose threshold checkpoint does not consult the
     * run's abort signal and runs under a fresh (un-aborted) controller — so
     * an aborted run performs the summarization between the aborted batch's
     * turn_end and its final aborted turn, and abort() awaits it all.
     */
    @Test
    fun `post-abort threshold compaction runs before the aborted final turn`() = runTest {
        val faux = FauxProvider(model = model)
        val largeResult = "large-tool-result:" + "x".repeat(6_800)
        val largeTool = object : AgentTool {
            override val definition = Tool(
                "large_result",
                "Returns enough content to cross the compaction threshold",
                buildJsonObject {}
            )
            override val label = "Large result"

            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback
            ) = AgentToolResult(content = listOf(TextContent(largeResult)))
        }
        val slowStarted = CompletableDeferred<Unit>()
        val slowTool = object : AgentTool {
            override val definition = Tool("slow_tool", "Parks until aborted", buildJsonObject {})
            override val label = "slow_tool"

            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback
            ): AgentToolResult {
                slowStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val manager = newManager()
        val session = AgentSession(
            agent = Agent(
                model = model,
                systemPrompt = "You are a test assistant.",
                tools = listOf(largeTool, slowTool),
                streamFn = modelsStreamFn(faux.models)
            ),
            manager = manager,
            models = faux.models,
            tools = listOf(largeTool, slowTool),
            settingsManager = SettingsManager.inMemory(
                Settings(
                    retry = RetrySettings(enabled = false),
                    compaction = CompactionSettings(
                        enabled = true,
                        reserveTokens = 400,
                        keepRecentTokens = 1_750
                    )
                )
            )
        )
        faux.setResponses(
            assistant("old-history:" + "a".repeat(800)),
            assistant("recent-history:" + "b".repeat(800)),
            assistant("", stopReason = StopReason.TOOL_USE).copy(
                content = listOf(
                    ToolCall("call-large", "large_result", JsonObject(emptyMap())),
                    ToolCall("call-slow", "slow_tool", JsonObject(emptyMap()))
                )
            ),
            assistant("compacted history"),
            assistant("compacted turn")
        )

        val events = CopyOnWriteArrayList<AgentEvent>()
        val settled = CompletableDeferred<Unit>()
        val largeSettled = CompletableDeferred<Unit>()
        val collector = launch {
            session.events.collect { event ->
                events.add(event)
                if (event is AgentEvent.ToolExecutionEnd &&
                    event.toolCallId == "call-large" &&
                    !largeSettled.isCompleted
                ) {
                    largeSettled.complete(Unit)
                }
                if (event is AgentEvent.AgentSettled && !settled.isCompleted) {
                    settled.complete(Unit)
                }
            }
        }
        yield()
        // Seed history below the threshold so only the aborted run crosses it.
        session.prompt("seed old history")
        session.prompt("seed recent history")
        val failure = CompletableDeferred<Throwable>()
        val run = launch {
            try {
                session.prompt("run the tools")
            } catch (e: Throwable) {
                failure.complete(e)
            }
        }
        // The tool batch: the large call's outcome settles (the signal fires
        // after the outcome is recorded), the slow call parks.
        largeSettled.await()
        slowStarted.await()
        session.abort()
        val error = failure.await()
        run.join()
        settled.await() // buffered delivery: drain before cancelling
        collector.cancelAndJoin()

        assertTrue(error is CancellationException)
        assertEquals("Prompt aborted", error.message)
        // abort() awaited the tail's compaction before returning.
        assertFalse(session.isCompacting)
        assertFalse(session.isStreaming.value)

        val abortedBatchTurnEnd = events.indexOfLast {
            it is AgentEvent.TurnEnd && it.toolResults.isNotEmpty()
        }
        val compactionStart = events.indexOfFirst { it is AgentEvent.CompactionStart }
        val compactionEnd = events.indexOfFirst {
            it is AgentEvent.CompactionEnd && it.result != null
        }
        val abortedMessageEnd = events.indexOfLast {
            it is AgentEvent.MessageEnd &&
                (it.message as? AssistantMessage)?.stopReason == StopReason.ABORTED
        }
        val agentEnd = events.indexOfLast { it is AgentEvent.AgentEnd }
        val settledIndex = events.indexOfLast { it is AgentEvent.AgentSettled }
        assertTrue(abortedBatchTurnEnd >= 0)
        assertTrue(
            "threshold compaction runs after the aborted batch's turn_end " +
                "(start=$compactionStart, turnEnd=$abortedBatchTurnEnd)",
            compactionStart > abortedBatchTurnEnd
        )
        assertTrue(compactionEnd > compactionStart)
        assertTrue(
            "the summarization completes before the final aborted turn " +
                "(end=$compactionEnd, abortedMessage=$abortedMessageEnd)",
            compactionEnd < abortedMessageEnd
        )
        assertTrue(abortedMessageEnd < agentEnd)
        assertTrue(agentEnd < settledIndex)

        // The summarization really ran: the tree recorded the compaction
        // ahead of the aborted final assistant message.
        val branch = manager.getBranch()
        val compactionEntry = branch.filterIsInstance<CompactionEntry>().single()
        val lastEntry = branch.filterIsInstance<MessageEntry>().last()
        assertEquals(StopReason.ABORTED, (lastEntry.message as AssistantMessage).stopReason)
        assertTrue(branch.indexOf(compactionEntry) < branch.indexOf(lastEntry))
        // The transcript was rebuilt from the compaction, and the aborted
        // turn materialized on top of it.
        val transcript = session.state.value.messages
        assertTrue(transcript.toString().contains("compacted history"))
        assertEquals(StopReason.ABORTED, (transcript.last() as AssistantMessage).stopReason)
    }

    /**
     * The prompt job is published before anything in the cycle can run, and
     * isStreaming only turns true inside it — so an abort fired at the first
     * observable isStreaming reaches the published job and the cycle dies
     * with the port's "Prompt aborted" cancellation.
     */
    @Test
    fun `abort at the first observable isStreaming reaches the published prompt job`() = runTest {
        val streams = ScriptedStreams()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = streams.streamFn),
            manager = newManager(),
            settingsManager = SettingsManager.inMemory(
                Settings(retry = RetrySettings(enabled = false))
            )
        )

        val failure = CompletableDeferred<Throwable>()
        val run = launch {
            try {
                session.prompt("hi")
            } catch (e: Throwable) {
                failure.complete(e)
            }
        }
        session.isStreaming.first { it }
        session.abort()
        val error = failure.await()
        run.join()

        assertTrue(error is CancellationException)
        assertEquals("Prompt aborted", error.message)
        assertFalse(session.isStreaming.value)
    }
}
