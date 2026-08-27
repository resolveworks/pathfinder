package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.data.settings.RetrySettings
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-retry wiring of [Agent], porting pi's agent-session auto-retry tests'
 * scenarios (agent-session.ts `_handlePostAgentRun` / `_prepareRetry` /
 * success-reset at ~684): transient errors are retried as continuations with
 * exponential backoff, non-retryable and overflow errors are not, the budget
 * is capped, abort cancels the backoff, and the error message leaves agent
 * state while the session layer keeps it (asserted in ChatViewModelTest).
 */
class AgentAutoRetryTest {

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
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = stopReason,
        errorMessage = errorMessage,
        timestamp = 42L,
    )

    private fun okStream(text: String = "hello"): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(text = "")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant(text = text)),
    )

    private fun errorStream(message: String): Flow<AssistantMessageEvent> =
        flowOf(AssistantMessageEvent.Error(StopReason.ERROR, assistant("", StopReason.ERROR, message)))

    private class ScriptedStreams {
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val seenContexts = CopyOnWriteArrayList<List<works.resolve.pathfinder.ai.core.Message>>()
        val streamFn = StreamFn { _, context, _ ->
            seenContexts.add(context.messages)
            streams.removeFirstOrNull() ?: flow { awaitCancellation() }
        }
    }

    private fun agent(
        streams: ScriptedStreams,
        retrySettings: RetrySettings = RetrySettings(),
        sleep: suspend (Long) -> Unit = { },
    ) = Agent(
        model = model,
        systemPrompt = "be brief",
        streamOptions = SimpleStreamOptions(),
        retrySettings = retrySettings,
        sleep = sleep,
        streamFn = streams.streamFn,
    )

    private suspend fun collectEvents(agent: Agent): MutableList<AgentEvent> = coroutineScope {
        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        agent.prompt("hi")
        collector.cancelAndJoin()
        events
    }

    @Test
    fun `retryable error is retried as a continue run and success ends the sequence`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("terminated"))
            streams.add(okStream("recovered"))
        }
        val delays = mutableListOf<Long>()
        val agent = agent(streams, sleep = { delays.add(it) })

        val events = collectEvents(agent)

        assertEquals(listOf(2000L), delays)
        assertEquals(
            listOf(
                AgentEvent.AutoRetryStart(attempt = 1, maxAttempts = 3, delayMs = 2000, errorMessage = "terminated"),
                AgentEvent.AutoRetryEnd(success = true, attempt = 1),
            ),
            events.filterIsInstance<AgentEvent.AutoRetryStart>() + events.filterIsInstance<AgentEvent.AutoRetryEnd>(),
        )

        // The retried run is a continue: no new user message, and the error
        // assistant message was removed from the agent-transcript snapshot,
        // so both runs see exactly the same single-message context.
        assertEquals(1, streams.seenContexts[0].size)
        assertEquals(listOf(streams.seenContexts[0].single()), streams.seenContexts[1])

        val state = agent.state.value
        assertEquals(2, state.messages.size)
        val reply = state.messages.last() as AssistantMessage
        assertEquals("recovered", (reply.content.single() as TextContent).text)
        assertNull(state.errorMessage)
    }

    @Test
    fun `non-retryable error is never retried`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("insufficient_quota: billing"))
        }
        val agent = agent(streams)
        val events = collectEvents(agent)

        assertTrue(events.filterIsInstance<AgentEvent.AutoRetryStart>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.AutoRetryEnd>().isEmpty())
        assertEquals(1, streams.seenContexts.size)
        assertEquals("insufficient_quota: billing", (agent.state.value.messages.last() as AssistantMessage).errorMessage)
    }

    @Test
    fun `context overflow error is not retried`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("prompt is too long: 300000 tokens > 200000 maximum"))
        }
        val agent = agent(streams)
        val events = collectEvents(agent)

        assertTrue(events.filterIsInstance<AgentEvent.AutoRetryStart>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.AutoRetryEnd>().isEmpty())
        assertEquals(1, streams.seenContexts.size)
    }

    @Test
    fun `disabled retry settings produce no retries`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("terminated"))
        }
        val agent = agent(streams, retrySettings = RetrySettings(enabled = false))
        val events = collectEvents(agent)

        assertTrue(events.filterIsInstance<AgentEvent.AutoRetryStart>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.AutoRetryEnd>().isEmpty())
        assertEquals(1, streams.seenContexts.size)
    }

    @Test
    fun `budget exhaustion emits final failure and keeps the error message`() = runTest {
        val streams = ScriptedStreams().apply {
            repeat(4) { streams.add(errorStream("terminated")) }
        }
        val delays = mutableListOf<Long>()
        val agent = agent(streams, sleep = { delays.add(it) })

        val events = collectEvents(agent)

        // Initial run plus 3 retries; backoff 2s/4s/8s.
        assertEquals(4, streams.seenContexts.size)
        assertEquals(listOf(2000L, 4000L, 8000L), delays)
        val starts = events.filterIsInstance<AgentEvent.AutoRetryStart>()
        assertEquals(listOf(1, 2, 3), starts.map { it.attempt })
        assertEquals(
            listOf(AgentEvent.AutoRetryEnd(success = false, attempt = 3, finalError = "terminated")),
            events.filterIsInstance<AgentEvent.AutoRetryEnd>(),
        )
        // The final error assistant message stays in agent state.
        assertEquals("terminated", (agent.state.value.messages.last() as AssistantMessage).errorMessage)
    }

    @Test
    fun `abort during backoff cancels the retry and reports it`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("terminated"))
            streams.add(okStream("never reached"))
        }
        val started = CompletableDeferred<Unit>()
        val agent = agent(streams, sleep = {
            started.complete(Unit)
            awaitCancellation()
        })

        val events = mutableListOf<AgentEvent>()
        val collector = launch { agent.events.toList(events) }
        yield()
        val run = launch { agent.prompt("hi") }
        started.await()
        agent.abort()
        run.join()
        // pi resolves the prompt; this port's existing abort contract surfaces
        // abort as cancellation (see Agent.prompt KDoc).
        assertTrue(run.isCancelled)
        collector.cancelAndJoin()

        assertEquals(
            listOf(
                AgentEvent.AutoRetryStart(attempt = 1, maxAttempts = 3, delayMs = 2000, errorMessage = "terminated"),
                AgentEvent.AutoRetryEnd(success = false, attempt = 1, finalError = "Retry cancelled"),
            ),
            events.filterIsInstance<AgentEvent.AutoRetryStart>() + events.filterIsInstance<AgentEvent.AutoRetryEnd>(),
        )
        // The continue run never started, and the error message was removed
        // from agent state before the backoff (it stays in the session layer).
        assertEquals(1, streams.seenContexts.size)
        assertEquals(1, agent.state.value.messages.size)
        assertFalse(agent.state.value.isStreaming)
    }
}
