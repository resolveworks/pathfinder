package works.resolve.pathfinder.codingagent.core

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
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
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FauxProvider
import works.resolve.pathfinder.ai.testing.FauxResponseFactory
import works.resolve.pathfinder.ai.utils.contentText

/**
 * Steering and follow-up queue characterization against pi's
 * agent-session-queue.test.ts: prompt() during a run queues per
 * streamingBehavior, steering is delivered before the next provider request,
 * follow-ups only once the current turn finished, one-at-a-time delivery
 * order, queue_update events, and queued messages surviving auto-compaction.
 */
class AgentSessionQueueTest {

    private val clock = FakeClock()

    private fun assistant(
        text: String,
        stopReason: StopReason = StopReason.STOP,
        usage: Usage = Usage()
    ) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = "faux",
        provider = "faux",
        model = "faux-1",
        stopReason = stopReason,
        usage = usage,
        timestamp = clock.now().toEpochMilliseconds()
    )

    private fun waitToolCall() = assistant("").copy(
        content = listOf(ToolCall("call-1", "wait", JsonObject(emptyMap()))),
        stopReason = StopReason.TOOL_USE
    )

    private class WaitingSession(
        val session: AgentSession,
        val releaseTool: () -> Unit,
        val toolStart: CompletableDeferred<Unit>
    )

    /** pi's createWaitingHarness: a gated wait tool holds the run open mid-batch. */
    private suspend fun waitingSession(
        faux: FauxProvider,
        settings: Settings = Settings(retry = RetrySettings(enabled = false))
    ): WaitingSession {
        val toolStart = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waitTool = object : AgentTool {
            override val definition = Tool("wait", "Wait for release", buildJsonObject {})
            override val label = "Wait"

            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback
            ): AgentToolResult {
                toolStart.complete(Unit)
                release.await()
                return AgentToolResult(content = listOf(TextContent("released")))
            }
        }
        val session = AgentSession(
            agent = Agent(faux.model, streamFn = modelsStreamFn(faux.models)),
            manager = SessionManager.create(
                createTempDirectory("queue-test").toFile(),
                clock = clock,
                ioDispatcher = Dispatchers.Unconfined,
                idFactory = { "sess-queue" },
                entryIdFactory = { "e${entryCounter++}" }
            ),
            settingsManager = SettingsManager.inMemory(settings),
            tools = listOf(waitTool)
        )
        return WaitingSession(session, { release.complete(Unit) }, toolStart)
    }

    private var entryCounter = 0

    private fun userTexts(session: AgentSession): List<String> = session.state.value.messages
        .filterIsInstance<UserMessage>()
        .map { contentText(it.content) }

    private fun assistantTexts(session: AgentSession): List<String> = session.state.value.messages
        .filterIsInstance<AssistantMessage>()
        .map { message ->
            message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        }

    private fun userMessageText(message: Message): String? =
        (message as? UserMessage)?.let { contentText(it.content) }

    /** Collects session events; [block] runs to completion and the collector sees AgentSettled. */
    private suspend fun collectDuringRun(
        session: AgentSession,
        block: suspend () -> Unit
    ): List<AgentEvent> = kotlinx.coroutines.coroutineScope {
        val events = mutableListOf<AgentEvent>()
        val settled = CompletableDeferred<Unit>()
        val collector = launch {
            session.events.collect { event ->
                events.add(event)
                if (event is AgentEvent.AgentSettled) settled.complete(Unit)
            }
        }
        yield()
        try {
            block()
            settled.await()
        } finally {
            collector.cancelAndJoin()
        }
        events
    }

    @Test
    fun `prompt during the run enqueues steering delivered before the next llm call`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(waitToolCall())
        faux.appendResponse(
            FauxResponseFactory { context, _, _, _ ->
                val sawSteer = context.messages.any { userMessageText(it) == "steer now" }
                assistant(if (sawSteer) "saw steer" else "missing steer")
            }
        )
        val waiting = waitingSession(faux)
        val session = waiting.session

        val events = collectDuringRun(session) {
            val run = launch { session.prompt("start") }
            waiting.toolStart.await()
            // The submit that used to throw now queues as steering.
            session.prompt("steer now", StreamingBehavior.STEER)
            assertEquals(listOf("steer now"), session.getSteeringMessages())
            waiting.releaseTool()
            run.join()
        }

        assertEquals(listOf("start", "steer now"), userTexts(session))
        assertTrue(assistantTexts(session).contains("saw steer"))
        // Delivered as a declared user message, not just provider context.
        val startedUserTexts = events
            .filterIsInstance<AgentEvent.MessageStart>()
            .map { userMessageText(it.message) }
        assertTrue(startedUserTexts.contains("steer now"))
        assertEquals(0, session.pendingMessageCount)
    }

    @Test
    fun `prompt during the run without streaming behavior still throws`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(waitToolCall(), assistant("done"))
        val waiting = waitingSession(faux)

        collectDuringRun(waiting.session) {
            val run = launch { waiting.session.prompt("start") }
            waiting.toolStart.await()
            val error = runCatching { waiting.session.prompt("nope") }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(
                "Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message.",
                error?.message
            )
            waiting.releaseTool()
            run.join()
        }
    }

    @Test
    fun `follow-ups are delivered only after the current turn finishes`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(waitToolCall())
        val assistantsSeenBeforeFollowUp = mutableListOf<String>()
        faux.appendResponse(
            FauxResponseFactory { context, _, _, _ ->
                assistantsSeenBeforeFollowUp +=
                    context.messages.filterIsInstance<AssistantMessage>().map { message ->
                        message.content.filterIsInstance<TextContent>()
                            .joinToString("") { it.text }
                    }
                assistant("follow-up response")
            }
        )
        val waiting = waitingSession(faux)
        val session = waiting.session

        collectDuringRun(session) {
            val run = launch { session.prompt("start") }
            waiting.toolStart.await()
            session.prompt("after current run", StreamingBehavior.FOLLOW_UP)
            assertEquals(listOf("after current run"), session.getFollowUpMessages())
            waiting.releaseTool()
            run.join()
        }

        assertEquals(listOf("start", "after current run"), userTexts(session))
        // The tool-call turn's assistant (empty text) completed before the
        // follow-up was delivered.
        assertTrue(assistantsSeenBeforeFollowUp.contains(""))
        assertTrue(assistantTexts(session).contains("follow-up response"))
    }

    @Test
    fun `delivers multiple steering messages in order in one-at-a-time mode`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(
            waitToolCall(),
            assistant("handled steer 1"),
            assistant("handled steer 2")
        )
        val waiting = waitingSession(faux)
        val session = waiting.session

        collectDuringRun(session) {
            val run = launch { session.prompt("start") }
            waiting.toolStart.await()
            session.prompt("steer 1", StreamingBehavior.STEER)
            session.prompt("steer 2", StreamingBehavior.STEER)
            waiting.releaseTool()
            run.join()
        }

        assertEquals(listOf("start", "steer 1", "steer 2"), userTexts(session))
        assertEquals(
            listOf("", "handled steer 1", "handled steer 2"),
            assistantTexts(session)
        )
    }

    @Test
    fun `delivers multiple follow-ups in order in one-at-a-time mode`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(
            waitToolCall(),
            assistant("original turn complete"),
            assistant("handled follow-up 1"),
            assistant("handled follow-up 2")
        )
        val waiting = waitingSession(faux)
        val session = waiting.session

        collectDuringRun(session) {
            val run = launch { session.prompt("start") }
            waiting.toolStart.await()
            session.prompt("follow-up 1", StreamingBehavior.FOLLOW_UP)
            session.prompt("follow-up 2", StreamingBehavior.FOLLOW_UP)
            waiting.releaseTool()
            run.join()
        }

        assertEquals(listOf("start", "follow-up 1", "follow-up 2"), userTexts(session))
        assertEquals(
            listOf("", "original turn complete", "handled follow-up 1", "handled follow-up 2"),
            assistantTexts(session)
        )
    }

    @Test
    fun `queue updates announce queueing and delivery before message_start`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(waitToolCall(), assistant("done"))
        val waiting = waitingSession(faux)
        val session = waiting.session
        val countsAtQueuedMessageStart = mutableListOf<Int>()

        val events = collectDuringRun(session) {
            val run = launch { session.prompt("start") }
            waiting.toolStart.await()
            session.prompt("queued", StreamingBehavior.STEER)
            assertEquals(1, session.pendingMessageCount)
            waiting.releaseTool()
            run.join()
        }
        events.forEach { event ->
            if (event is AgentEvent.MessageStart && userMessageText(event.message) == "queued") {
                countsAtQueuedMessageStart.add(session.pendingMessageCount)
            }
        }

        assertEquals(0, session.pendingMessageCount)
        val updates = events.filterIsInstance<AgentEvent.QueueUpdate>()
        assertEquals(2, updates.size)
        assertEquals(listOf("queued"), updates[0].steering)
        assertTrue(updates[0].followUp.isEmpty())
        assertTrue(updates[1].steering.isEmpty())
        assertTrue(updates[1].followUp.isEmpty())
        // The emptied-queue announcement precedes the queued message_start.
        val emptiedAt = events.indexOfFirst {
            it is AgentEvent.QueueUpdate && it.steering.isEmpty() && it.followUp.isEmpty()
        }
        val messageStartAt = events.indexOfFirst {
            it is AgentEvent.MessageStart && userMessageText(it.message) == "queued"
        }
        assertTrue(emptiedAt in 0 until messageStartAt)
        assertEquals(listOf(0), countsAtQueuedMessageStart)
    }

    @Test
    fun `queued follow-ups survive post-run compaction and continue the run`() = runTest {
        val model = Model(
            id = "faux-1",
            name = "Faux Model",
            api = "faux",
            provider = "faux",
            baseUrl = "http://localhost:0",
            contextWindow = 1_000,
            maxTokens = 100
        )
        val faux = FauxProvider(model)
        // The compaction's summary LLM call is the rendezvous: the follow-up
        // must be queued after the loop's end-of-run follow-up poll and
        // during the post-run compaction — pi's "auto-compaction can
        // complete while follow-up/steering/custom messages are waiting"
        // window.
        val compactionSummaryStarted = CompletableDeferred<Unit>()
        val followUpQueued = CompletableDeferred<Unit>()
        faux.appendResponse(
            FauxResponseFactory { _, _, _, _ ->
                compactionSummaryStarted.complete(Unit)
                followUpQueued.await()
                assistant("SUMMARY")
            }
        )

        val streamStarted = CompletableDeferred<Unit>()
        val releaseStream = CompletableDeferred<Unit>()
        fun done(text: String, usage: Usage): Flow<AssistantMessageEvent> = flowOf(
            AssistantMessageEvent.Start(assistant("")),
            AssistantMessageEvent.Done(StopReason.STOP, assistant(text, usage = usage))
        )
        val streams = ArrayDeque(
            listOf(
                done("history:" + "a".repeat(8_000), Usage(input = 10, output = 100)),
                flow {
                    streamStarted.complete(Unit)
                    releaseStream.await()
                    emit(AssistantMessageEvent.Start(assistant("")))
                    emit(
                        AssistantMessageEvent.Done(
                            StopReason.STOP,
                            assistant("done", usage = Usage(input = 2_000))
                        )
                    )
                },
                done("follow-up reply", Usage(input = 10))
            )
        )
        val session = AgentSession(
            agent = Agent(
                model,
                streamFn = StreamFn { _, _, _ ->
                    streams.removeFirstOrNull() ?: flow { kotlinx.coroutines.awaitCancellation() }
                }
            ),
            manager = SessionManager.create(
                createTempDirectory("queue-compaction-test").toFile(),
                clock = clock,
                ioDispatcher = Dispatchers.Unconfined,
                idFactory = { "sess-queue-compaction" },
                entryIdFactory = { "e${entryCounter++}" }
            ),
            settingsManager = SettingsManager.inMemory(
                Settings(
                    retry = RetrySettings(enabled = false),
                    compaction = CompactionSettings(
                        enabled = true,
                        reserveTokens = 400,
                        keepRecentTokens = 500
                    )
                )
            ),
            models = faux.models
        )

        session.prompt("seed")
        val events = collectDuringRun(session) {
            val run = launch { session.prompt("run") }
            streamStarted.await()
            releaseStream.complete(Unit)
            compactionSummaryStarted.await()
            session.prompt("after compact", StreamingBehavior.FOLLOW_UP)
            followUpQueued.complete(Unit)
            run.join()
        }

        // The compacted context projects the summary as its leading user
        // message (pi's session-context projection); the run prompt and the
        // queued follow-up follow it.
        val users = userTexts(session)
        assertEquals(3, users.size)
        assertTrue(users[0].contains("compacted into the following summary"))
        assertEquals("run", users[1])
        assertEquals("after compact", users[2])
        assertTrue(assistantTexts(session).contains("done"))
        assertTrue(assistantTexts(session).contains("follow-up reply"))
        // Overflow compaction (no retry) ran between the completed response
        // and the follow-up delivery, and its hasQueuedMessages tail
        // continued the cycle with a second agent run.
        val compactionEnd = events.filterIsInstance<AgentEvent.CompactionEnd>().single()
        assertEquals(AgentEvent.CompactionReason.OVERFLOW, compactionEnd.reason)
        val followUpStartAt = events.indexOfFirst {
            it is AgentEvent.MessageStart && userMessageText(it.message) == "after compact"
        }
        assertTrue(events.indexOf(compactionEnd) < followUpStartAt)
        assertEquals(2, events.count { it is AgentEvent.AgentStart })
        assertEquals(0, session.pendingMessageCount)
    }
}
