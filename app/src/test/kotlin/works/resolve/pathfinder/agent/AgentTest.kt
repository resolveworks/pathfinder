package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.data.settings.RetrySettings
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid",
    )

    private fun assistant(
        text: String = "hello",
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null,
    ) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = stopReason,
        errorMessage = errorMessage,
        timestamp = 42L,
    )

    /** Successful stream: Start, one TextDelta, Done. */
    private fun okStream(): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(text = "")),
        AssistantMessageEvent.TextDelta(0, "he", assistant(text = "he")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant(text = "hello")),
    )

    /** Stream that starts but never terminates; only cancellation ends it. */
    private fun hangingStream(): Flow<AssistantMessageEvent> = flow {
        emit(AssistantMessageEvent.Start(assistant(text = "")))
        awaitCancellation()
    }

    private fun agent(
        streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
        // Retry is disabled by default so these tests exercise one run per
        // prompt; auto-retry has its own suite (AgentAutoRetryTest).
        retrySettings: RetrySettings = RetrySettings(enabled = false),
        streamFn: StreamFn,
    ) = Agent(
        model = model,
        systemPrompt = "be brief",
        streamOptions = streamOptions,
        retrySettings = retrySettings,
        streamFn = streamFn,
    )

    @Test
    fun `successful prompt reduces state and emits events in order`() = runTest {
        val contexts = CopyOnWriteArrayList<List<Message>>()
        val streamFn = StreamFn { _, context, _ ->
            contexts.add(context.messages)
            okStream()
        }
        val agent = agent(streamFn = streamFn)

        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield() // let the collector subscribe before the run starts

        agent.prompt("hi")
        collector.cancelAndJoin()

        val types = events.map { it::class.simpleName }
        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd", // user
                "MessageStart", "MessageUpdate", "MessageEnd", // assistant
                "TurnEnd", "AgentEnd",
            ),
            types,
        )

        val final = agent.state.value
        assertEquals(2, final.messages.size)
        assertEquals("hi", ((final.messages[0] as UserMessage).content.single() as TextContent).text)
        val reply = final.messages[1] as AssistantMessage
        assertEquals("hello", (reply.content.single() as TextContent).text)
        assertEquals(StopReason.STOP, reply.stopReason)
        assertNull(final.streamingMessage)
        assertFalse(final.isStreaming)
        assertNull(final.errorMessage)

        // A second prompt is sent against the committed transcript snapshot;
        // the context may contain AssistantMessages, so text is read generically.
        fun textOf(msg: Message) = when (msg) {
            is UserMessage -> (msg.content.single() as TextContent).text
            is AssistantMessage -> (msg.content.single() as TextContent).text
            else -> "tool-result"
        }
        agent.prompt("again")
        val texts = contexts.map { ctx -> ctx.map(::textOf) }
        assertEquals(listOf(listOf("hi"), listOf("hi", "hello", "again")), texts)
    }

    @Test
    fun `observer sees already-reduced state`() = runTest {
        val agent = agent { _, _, _ -> okStream() }
        val sawAssistantEnd = CompletableDeferred<AgentState>()
        val collector = launch {
            agent.events.collect { event ->
                if (event is AgentEvent.MessageEnd && event.message is AssistantMessage) {
                    sawAssistantEnd.complete(agent.state.value)
                }
            }
        }
        yield() // subscribe before the run starts

        agent.prompt("hi")
        val observed = sawAssistantEnd.await()
        // The assistant message is committed and streaming cleared before the
        // event reaches observers; the run itself is still streaming.
        assertTrue(observed.messages.any { it is AssistantMessage })
        assertNull(observed.streamingMessage)
        assertTrue(observed.isStreaming)
        collector.cancelAndJoin()
    }

    @Test
    fun `replace and reset transcript copy caller lists while idle`() = runTest {
        // Mutation while active is rejected. Gate on provider start, not
        // merely isStreaming, so the run is deterministically established.
        val providerStarted = CompletableDeferred<Unit>()
        val agent = Agent(model, null, SimpleStreamOptions()) { _, _, _ ->
            providerStarted.complete(Unit)
            hangingStream()
        }
        val callerList = mutableListOf<Message>(UserMessage.ofText("seed"))
        agent.replaceTranscript(callerList)

        callerList.clear()
        assertEquals(1, agent.state.value.messages.size)

        agent.resetTranscript()
        assertTrue(agent.state.value.messages.isEmpty())
        assertNull(agent.state.value.errorMessage)

        // Mutation while active is rejected.
        val job = launch { agent.prompt("hi") }
        agent.state.first { it.isStreaming }
        try {
            agent.replaceTranscript(emptyList())
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("while a prompt is running"))
        }
        try {
            agent.resetTranscript()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
        providerStarted.await()
        job.cancelAndJoin()
        // Caller cancellation still synthesizes the ABORTED terminal message
        // (transcript was reset above, so: user prompt + synthesized message).
        assertEquals(2, agent.state.value.messages.size)
        assertEquals(
            StopReason.ABORTED,
            (agent.state.value.messages[1] as AssistantMessage).stopReason,
        )
    }

    @Test
    fun `concurrent prompt is rejected`() = runTest {
        val providerStarted = CompletableDeferred<Unit>()
        val agent = Agent(model, null, SimpleStreamOptions()) { _, _, _ ->
            providerStarted.complete(Unit)
            hangingStream()
        }
        val job = launch { agent.prompt("first") }
        providerStarted.await()
        agent.state.first { it.isStreaming }

        try {
            agent.prompt("second")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already processing"))
        }

        agent.abort()
        job.join()
        assertFalse(agent.state.value.isStreaming)
    }

    @Test
    fun `abort synthesizes ABORTED message and cancels the caller`() = runTest {
        val events = mutableListOf<AgentEvent>()
        val providerStarted = CompletableDeferred<Unit>()
        val agent = Agent(model, null, SimpleStreamOptions()) { _, _, _ ->
            providerStarted.complete(Unit)
            hangingStream()
        }
        val collector = launch { agent.events.toList(events) }
        yield() // subscribe before the run starts

        val deferred = async { agent.prompt("hi") }
        providerStarted.await()

        agent.abort()

        try {
            withTimeout(1_000) { deferred.await() }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        withTimeout(1_000) { agent.state.first { !it.isStreaming } }
        collector.cancelAndJoin()

        val final = agent.state.value
        assertEquals("Run aborted", final.errorMessage)
        assertNull(final.streamingMessage)
        assertEquals(2, final.messages.size)
        val synthesized = final.messages[1] as AssistantMessage
        assertEquals(StopReason.ABORTED, synthesized.stopReason)
        assertEquals("Run aborted", synthesized.errorMessage)

        val tail = events.takeLast(4).map { it::class.simpleName }
        assertEquals(listOf("MessageStart", "MessageEnd", "TurnEnd", "AgentEnd"), tail)
    }

    @Test
    fun `caller cancellation synthesizes ABORTED message`() = runTest {
        val providerStarted = CompletableDeferred<Unit>()
        val agent = Agent(model, null, SimpleStreamOptions()) { _, _, _ ->
            providerStarted.complete(Unit)
            hangingStream()
        }
        val job = launch { agent.prompt("hi") }
        providerStarted.await()

        job.cancelAndJoin()

        val final = agent.state.value
        assertEquals(2, final.messages.size)
        val synthesized = final.messages[1] as AssistantMessage
        assertEquals(StopReason.ABORTED, synthesized.stopReason)
        assertFalse(final.isStreaming)
    }

    @Test
    fun `unexpected throw synthesizes ERROR message with safe message`() = runTest {
        val agent = agent(
            streamFn = StreamFn { _, _, _ ->
                throw RuntimeException("boom with key sk-supersecret")
            },
            streamOptions = SimpleStreamOptions(apiKey = "sk-supersecret"),
        )

        val failureTypes = mutableListOf<String>()
        val collector = launch { agent.events.collect { failureTypes.add(it::class.simpleName!!) } }
        yield() // subscribe before the run starts

        // Ordinary failures resolve normally rather than throwing.
        agent.prompt("hi")
        collector.cancelAndJoin()

        // Full lifecycle, mirroring pi: agent_start/turn_start, user prompt pair,
        // then the synthesized failure message through message_start/end, turn_end, agent_end.
        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd",
                "MessageStart", "MessageEnd",
                "TurnEnd", "AgentEnd",
            ),
            failureTypes,
        )

        val final = agent.state.value
        assertFalse(final.isStreaming)
        assertEquals(2, final.messages.size)
        val error = final.messages[1] as AssistantMessage
        assertEquals(StopReason.ERROR, error.stopReason)
        assertEquals("Unexpected error (RuntimeException)", error.errorMessage)
        assertFalse(error.errorMessage!!.contains("sk-supersecret"))
        assertEquals("Unexpected error (RuntimeException)", final.errorMessage)
    }

    @Test
    fun `provider error errorMessage is cleared by the next successful run`() = runTest {
        var call = 0
        val error = assistant(text = "", stopReason = StopReason.ERROR, errorMessage = "500 upstream")
        val agent = agent(streamFn = StreamFn { _, _, _ ->
            call++
            if (call == 1) flowOf(AssistantMessageEvent.Error(StopReason.ERROR, error)) else okStream()
        })

        agent.prompt("hi")
        assertEquals("500 upstream", agent.state.value.errorMessage)

        agent.prompt("again")
        assertNull(agent.state.value.errorMessage)
        assertEquals(4, agent.state.value.messages.size)
    }

    @Test
    fun `abort while idle is a no-op`() = runTest {
        val agent = agent(streamFn = StreamFn { _, _, _ -> okStream() })
        agent.abort() // must not throw
        assertTrue(agent.state.value.messages.isEmpty())
    }
}
