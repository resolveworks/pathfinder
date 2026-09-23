package works.resolve.pathfinder.codingagent.core

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock

/**
 * Event-contract and orchestration fidelity against pi's agent-session:
 * event ordering around persistence, agent_end willRetry, run-active state
 * across the prompt cycle, abort reach into navigation and compaction, and
 * pi's prompt-guard breadth.
 */
class AgentSessionFidelityTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid"
    )

    private val clock = FakeClock()
    private var entryCounter = 0

    private fun assistant(
        text: String,
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null
    ) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = stopReason,
        errorMessage = errorMessage,
        usage = Usage(),
        timestamp = clock.now().toEpochMilliseconds()
    )

    private fun okStream(text: String): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(text = "")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant(text = text))
    )

    private fun errorStream(message: String): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Error(StopReason.ERROR, assistant("", StopReason.ERROR, message))
    )

    private class ScriptedStreams {
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val streamFn = StreamFn { _, _, _ ->
            streams.removeFirstOrNull() ?: flow { awaitCancellation() }
        }
    }

    private suspend fun newManager(dir: File = createTempDirectory("fidelity-test").toFile()) =
        SessionManager.create(
            dir = dir,
            clock = clock,
            ioDispatcher = Dispatchers.Unconfined,
            idFactory = { "sess-fidelity" },
            entryIdFactory = { "e${entryCounter++}" }
        )

    private suspend fun session(
        streams: ScriptedStreams,
        manager: SessionManager? = null,
        settings: Settings = Settings(retry = RetrySettings(enabled = false)),
        sleep: suspend (Long) -> Unit = { }
    ): AgentSession = AgentSession(
        agent = Agent(model = model, streamFn = streams.streamFn),
        manager = manager ?: newManager(),
        settingsManager = SettingsManager.inMemory(settings),
        sleep = sleep
    )

    private suspend fun collectEvents(
        agent: AgentSession,
        block: suspend () -> Unit
    ): List<AgentEvent> = coroutineScope {
        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        block()
        collector.cancelAndJoin()
        events
    }

    private fun recoveredText(message: works.resolve.pathfinder.ai.Message): String? =
        (message as? AssistantMessage)
            ?.content
            ?.singleOrNull()
            ?.let { it as? TextContent }
            ?.text

    // ---- finding 1: pi re-emits message_end before persistence and before
    // the success auto_retry_end ----

    @Test
    fun `success auto_retry_end follows the triggering message_end`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("terminated"))
            streams.add(okStream("recovered"))
        }
        val agent = session(streams, settings = Settings(retry = RetrySettings()))

        val events = collectEvents(agent) { agent.prompt("hi") }

        val recoveredEnd = events.indexOfFirst {
            it is AgentEvent.MessageEnd && recoveredText(it.message) == "recovered"
        }
        val retryEnd = events.indexOfFirst { it is AgentEvent.AutoRetryEnd && it.success }
        assertTrue(recoveredEnd >= 0)
        assertTrue(retryEnd >= 0)
        assertTrue(
            "message_end must precede the success auto_retry_end (pi's order)",
            recoveredEnd < retryEnd
        )
    }

    // ---- finding 2: agent_end willRetry ----

    @Test
    fun `agent_end willRetry tracks the retry decision`() = runTest {
        val streams = ScriptedStreams().apply {
            repeat(4) { streams.add(errorStream("terminated")) }
        }
        val agent = session(streams, settings = Settings(retry = RetrySettings()))

        val events = collectEvents(agent) { agent.prompt("hi") }

        // Retryable errors with budget left predict a continuation; the
        // budget-exhausted final run does not.
        assertEquals(
            listOf(true, true, true, false),
            events.filterIsInstance<AgentEvent.AgentEnd>().map { it.willRetry }
        )
    }

    @Test
    fun `agent_end willRetry is false for a successful run`() = runTest {
        val streams = ScriptedStreams().apply { streams.add(okStream("hello")) }
        val agent = session(streams)

        val events = collectEvents(agent) { agent.prompt("hi") }

        assertEquals(
            listOf(false),
            events.filterIsInstance<AgentEvent.AgentEnd>().map { it.willRetry }
        )
    }

    // ---- finding 3: run-active spans the cycle; the settle event closes it ----

    @Test
    fun `run-active state spans retry backoff and the cycle settles once`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("terminated"))
            streams.add(okStream("recovered"))
        }
        val backoffStarted = CompletableDeferred<Unit>()
        val releaseBackoff = CompletableDeferred<Unit>()
        val agent = session(
            streams,
            settings = Settings(retry = RetrySettings()),
            sleep = {
                backoffStarted.complete(Unit)
                releaseBackoff.await()
            }
        )

        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        val run = launch { agent.prompt("hi") }
        backoffStarted.await()

        // Mid-backoff the cycle is still active and has not settled.
        assertTrue(agent.isStreaming.value)
        assertTrue(events.none { it is AgentEvent.AgentSettled })

        releaseBackoff.complete(Unit)
        run.join()
        collector.cancelAndJoin()

        assertFalse(agent.isStreaming.value)
        assertEquals(1, events.filterIsInstance<AgentEvent.AgentSettled>().size)
        // The settle event trails the cycle's final agent_end.
        val lastAgentEnd = events.indexOfLast { it is AgentEvent.AgentEnd }
        val settled = events.indexOfFirst { it is AgentEvent.AgentSettled }
        assertTrue(lastAgentEnd in 0 until settled)
    }

    // ---- finding 6: an abort landing in the post-run window skips the phases ----

    @Test
    fun `abort during backoff ends the cycle without post-run phases`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("terminated"))
            streams.add(okStream("never reached"))
        }
        val started = CompletableDeferred<Unit>()
        val agent = session(
            streams,
            settings = Settings(retry = RetrySettings()),
            sleep = {
                started.complete(Unit)
                awaitCancellation()
            }
        )

        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        val run = launch { agent.prompt("hi") }
        started.await()
        agent.abort()
        run.join()
        collector.cancelAndJoin()

        assertTrue(run.isCancelled)
        assertEquals(
            listOf(
                AgentEvent.AutoRetryStart(
                    attempt = 1,
                    maxAttempts = 3,
                    delayMs = 2000,
                    errorMessage = "terminated"
                ),
                AgentEvent.AutoRetryEnd(
                    success = false,
                    attempt = 1,
                    finalError = "Retry cancelled"
                )
            ),
            events.filterIsInstance<AgentEvent.AutoRetryStart>() +
                events.filterIsInstance<AgentEvent.AutoRetryEnd>()
        )
        assertTrue(events.none { it is AgentEvent.CompactionStart })
        assertEquals(1, events.filterIsInstance<AgentEvent.AgentSettled>().size)
        assertFalse(agent.isStreaming.value)
    }

    /**
     * pi's #9340 regression, ported under real dispatchers: an abort landing
     * while the run's final error message is still being processed (here:
     * parked in its persistence append — pi fires the same abort from a
     * synchronous message_end listener) must skip every post-run phase — no
     * second retry start, no compaction — and finalize the outstanding retry
     * as cancelled. Single-threaded test schedulers serialize this window
     * away: the abort comes from a foreign thread while the loop thread is
     * parked mid final-message_end, so the abort flag and the prompt-job
     * Divergence from pi's exact event tail, inherent to the port's eager
     * cancellation: pi's advisory signal lets the error message finish and
     * the post-run checkpoint finalize the attempt ("Retry cancelled"); the
     * port's prompt-job cancellation preempts the run at the append resume,
     * so the interrupted run finalizes through the agent's synthesized
     * aborted lifecycle — which closes the outstanding attempt exactly like
     * pi's abort during a retried run's streaming (success=true on a
     * non-error stopReason).
     */
    @Test
    fun `abort at the final message window skips the post-run phases under real dispatchers`() =
        runBlocking {
            val streams = ScriptedStreams().apply {
                streams.add(errorStream("terminated"))
                streams.add(errorStream("terminated"))
            }
            val streamCalls = AtomicInteger()
            val parked = AtomicBoolean(false)
            val windowEntered = CountDownLatch(1)
            val releaseWindow = CountDownLatch(1)
            val gatedManager = SessionManager.create(
                createTempDirectory("fidelity-abort-window").toFile(),
                clock = clock,
                ioDispatcher = Dispatchers.Unconfined,
                idFactory = { "sess-abort-window" },
                entryIdFactory = {
                    val id = "e${entryCounter++}"
                    // Park once inside the second (final) error message's
                    // append: the last suspension before agent_end. A raw
                    // latch — coroutine cancellation cannot preempt it.
                    if (streamCalls.get() >= 2 && parked.compareAndSet(false, true)) {
                        windowEntered.countDown()
                        check(releaseWindow.await(10, TimeUnit.SECONDS)) {
                            "abort window was never released"
                        }
                    }
                    id
                }
            )
            val agent = AgentSession(
                agent = Agent(
                    model,
                    streamFn = StreamFn { requestedModel, context, options ->
                        streamCalls.incrementAndGet()
                        streams.streamFn.stream(requestedModel, context, options)
                    }
                ),
                manager = gatedManager,
                settingsManager = SettingsManager.inMemory(
                    Settings(retry = RetrySettings(enabled = true, maxRetries = 3, baseDelayMs = 0))
                ),
                sleep = { }
            )

            val events = CopyOnWriteArrayList<AgentEvent>()
            val settled = CountDownLatch(1)
            val collector = launch(Dispatchers.Default) {
                agent.events.collect { event ->
                    events.add(event)
                    if (event is AgentEvent.AgentSettled) settled.countDown()
                }
            }
            val run = launch(Dispatchers.Default) { agent.prompt("test") }
            check(windowEntered.await(10, TimeUnit.SECONDS)) { "abort window never entered" }
            // UNDISPATCHED runs abort()'s synchronous prefix — the abort
            // flag write and the prompt-job cancellation — on this thread
            // before the parked loop thread is released.
            val aborter = launch(start = CoroutineStart.UNDISPATCHED) { agent.abort() }
            releaseWindow.countDown()
            aborter.join()
            run.join()
            check(settled.await(10, TimeUnit.SECONDS)) { "session never settled" }
            collector.cancelAndJoin()

            assertTrue(run.isCancelled)
            // No second retry start; the outstanding attempt is closed by the
            // aborted message's own message_end handling.
            assertEquals(
                listOf(
                    AgentEvent.AutoRetryStart(
                        attempt = 1,
                        maxAttempts = 3,
                        delayMs = 0,
                        errorMessage = "terminated"
                    )
                ),
                events.filterIsInstance<AgentEvent.AutoRetryStart>()
            )
            val retryEnd = events.filterIsInstance<AgentEvent.AutoRetryEnd>().single()
            assertEquals(1, retryEnd.attempt)
            // The interrupted run classified ABORTED (the agent's abort
            // signal state), not ERROR — pi's handleRunFailure semantics.
            assertTrue(retryEnd.success)
            assertEquals(
                StopReason.ABORTED,
                (agent.state.value.messages.last() as AssistantMessage).stopReason
            )
            // The abort was already requested when the final agent_end was
            // processed, so it predicts no continuation.
            assertEquals(false, events.filterIsInstance<AgentEvent.AgentEnd>().last().willRetry)
            assertTrue(events.none { it is AgentEvent.CompactionStart })
            assertFalse(agent.isStreaming.value)
        }

    // ---- finding 10: listeners hear message_end even when the append fails ----

    @Test
    fun `message_end is emitted before the failing persistence append`() = runTest {
        val dir = createTempDirectory("fidelity-storage").toFile()
        val manager = newManager(dir)
        // A read-only sessions directory: the first append (the user
        // message) fails like a full disk would.
        dir.setWritable(false)

        val streams = ScriptedStreams().apply { streams.add(okStream("world")) }
        val agent = session(streams, manager = manager)

        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        val error = runCatching { agent.prompt("hi") }.exceptionOrNull()
        collector.cancelAndJoin()

        assertNotNull(error)
        assertTrue(error !is CancellationException)
        assertTrue(
            "the user message_end must reach listeners before the append fails",
            events.any {
                it is AgentEvent.MessageEnd &&
                    (it.message as? UserMessage)
                        ?.content
                        ?.singleOrNull()
                        ?.let { part -> (part as? TextContent)?.text == "hi" } == true
            }
        )
        dir.setWritable(true)
    }

    // ---- findings 5, 7, 11: navigation, compaction, and the prompt guard ----

    /**
     * A summarization API whose single response is held behind a gate; the
     * prompt text routes requests: the branch-summary prompt carries
     * "conversation branch", the compaction prompt "context checkpoint".
     */
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

    /** user("hello") ← assistant("branch A") ← assistant("branch B"), leaf on B. */
    private suspend fun forkedSession(): Pair<SessionManager, String> {
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello"))
        clock.advanceMillis(1)
        manager.appendMessage(assistant("branch A:" + "a".repeat(900_000)))
        val branchA = manager.getLeafId()!!
        clock.advanceMillis(1)
        manager.appendMessage(assistant("branch B"))
        return manager to branchA
    }

    @Test
    fun `abort cancels an in-flight navigation`() = runTest {
        val faux = GatedSummaryApi()
        val (manager, branchA) = forkedSession()
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            manager = manager,
            models = summaryModels(faux.api),
            settingsManager = SettingsManager.inMemory(
                Settings(retry = RetrySettings(enabled = false))
            )
        )

        val navigation = async(start = CoroutineStart.DEFAULT) {
            agent.navigateTree(branchA, AgentSession.NavigateTreeOptions(summarize = true))
        }
        faux.entered.await()
        agent.abort()
        val result = navigation.await()

        assertTrue(result.cancelled)
        assertTrue(result.aborted)
        assertNull(result.summaryEntry)
        assertTrue(manager.getBranch().none { it is BranchSummaryEntry })
    }

    @Test
    fun `compact aborts an in-flight navigation before proceeding`() = runTest {
        val navigationApi = GatedSummaryApi()
        val compactionApi = object : ChatApi {
            val responses = ArrayDeque<AssistantMessage>()
            val entered = CompletableDeferred<Unit>()

            override fun streamSimple(
                model: Model,
                context: TranscriptContext,
                options: SimpleStreamOptions
            ): Flow<AssistantMessageEvent> = flow {
                entered.complete(Unit)
                val response = responses.removeFirstOrNull()
                    ?: error("No faux compaction response queued")
                emit(AssistantMessageEvent.Done(response.stopReason, response))
            }
        }
        // Route by prompt content: the branch-summary prompt is the gated
        // one; the compaction prompt goes to the plain api.
        val models = Models(
            listOf(
                Provider(
                    model.provider,
                    model.provider,
                    "https://faux.test",
                    authResolver = { _, _ -> ResolvedAuth(apiKey = "faux-key") },
                    models = listOf(model),
                    apis = mapOf(
                        model.api to object : ChatApi {
                            override fun streamSimple(
                                model: Model,
                                context: TranscriptContext,
                                options: SimpleStreamOptions
                            ): Flow<AssistantMessageEvent> = flow {
                                val prompt = context.messages
                                    .filterIsInstance<UserMessage>()
                                    .singleOrNull()
                                    ?.content
                                    ?.singleOrNull()
                                    ?.let { it as? TextContent }?.text.orEmpty()
                                val target = if ("conversation branch" in prompt) {
                                    navigationApi.api
                                } else {
                                    compactionApi
                                }
                                target.streamSimple(model, context, options)
                                    .collect { emit(it) }
                            }
                        }
                    )
                )
            )
        )
        val (manager, branchA) = forkedSession()
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            manager = manager,
            models = models,
            settingsManager = SettingsManager.inMemory(
                Settings(retry = RetrySettings(enabled = false))
            )
        )

        val navigation = async(start = CoroutineStart.DEFAULT) {
            agent.navigateTree(branchA, AgentSession.NavigateTreeOptions(summarize = true))
        }
        navigationApi.entered.await()

        compactionApi.responses.add(assistant("## Progress\ncompacted"))
        agent.compact()
        val result = navigation.await()

        // The navigation was aborted by compact's leading abort; the
        // compaction itself then completed.
        assertTrue(result.cancelled)
        assertTrue(result.aborted)
        assertTrue(manager.getBranch().none { it is BranchSummaryEntry })
        assertTrue(manager.getBranch().any { it is CompactionEntry })
    }

    @Test
    fun `prompt during navigation is accepted like pi's unguarded race`() = runTest {
        val faux = GatedSummaryApi()
        val (manager, branchA) = forkedSession()
        val streams = ScriptedStreams().apply { streams.add(okStream("raced reply")) }
        val agent = AgentSession(
            agent = Agent(model = model, streamFn = streams.streamFn),
            manager = manager,
            models = summaryModels(faux.api),
            settingsManager = SettingsManager.inMemory(
                Settings(
                    retry = RetrySettings(enabled = false),
                    compaction = CompactionSettings(enabled = false)
                )
            )
        )

        val navigation = async(start = CoroutineStart.DEFAULT) {
            agent.navigateTree(branchA, AgentSession.NavigateTreeOptions(summarize = true))
        }
        faux.entered.await()

        // pi's prompt does not guard against navigation: the prompt runs and
        // the tree serializes both under the manager's mutex. The navigation
        // never mutates the tree (still gated at its summarization).
        agent.prompt("raced")
        navigation.cancelAndJoin()

        val racedUserMessage = manager.getBranch()
            .filterIsInstance<MessageEntry>()
            .map { it.message }
            .filterIsInstance<UserMessage>()
            .map { user -> (user.content.single() as TextContent).text }
        assertTrue("raced" in racedUserMessage)
        // The raced prompt's reply committed after the branch's own messages.
        val racedReply = manager.getBranch()
            .filterIsInstance<MessageEntry>()
            .map { it.message }
            .filterIsInstance<AssistantMessage>()
            .map { assistant -> (assistant.content.single() as TextContent).text }
        assertTrue("raced reply" in racedReply)
    }
}
