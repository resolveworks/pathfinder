package works.resolve.pathfinder.codingagent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.codingagent.core.RetrySettings
import works.resolve.pathfinder.codingagent.core.session.MessageEntry

class AgentAutoRetryTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid"
    )

    private fun assistant(
        text: String = "hello",
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null
    ) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = stopReason,
        errorMessage = errorMessage,
        timestamp = 42L
    )

    private fun okStream(text: String = "hello"): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(text = "")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant(text = text))
    )

    private fun errorStream(message: String): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Error(StopReason.ERROR, assistant("", StopReason.ERROR, message))
    )

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
        retrySettings: RetrySettings = RetrySettings(),
        sleep: suspend (Long) -> Unit = { }
    ) = AgentSession(
        agent = Agent(
            model = model,
            systemPrompt = "be brief",
            streamOptions = SimpleStreamOptions(),
            streamFn = streams.streamFn
        ),
        retrySettings = retrySettings,
        sleep = sleep
    )

    private suspend fun collectEvents(agent: AgentSession): MutableList<AgentEvent> =
        coroutineScope {
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
        val agent = session(streams, sleep = { delays.add(it) })

        val events = collectEvents(agent)

        assertEquals(listOf(2000L), delays)
        assertEquals(
            listOf(
                AgentEvent.AutoRetryStart(
                    attempt = 1,
                    maxAttempts = 3,
                    delayMs = 2000,
                    errorMessage = "terminated"
                ),
                AgentEvent.AutoRetryEnd(success = true, attempt = 1)
            ),
            events.filterIsInstance<AgentEvent.AutoRetryStart>() +
                events.filterIsInstance<AgentEvent.AutoRetryEnd>()
        )

        // pi drops the errored assistant message from agent state before the
        // retry, so both runs see the same single-message context.
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
        val agent = session(streams)
        val events = collectEvents(agent)

        assertTrue(events.filterIsInstance<AgentEvent.AutoRetryStart>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.AutoRetryEnd>().isEmpty())
        assertEquals(1, streams.seenContexts.size)
        assertEquals(
            "insufficient_quota: billing",
            (agent.state.value.messages.last() as AssistantMessage).errorMessage
        )
    }

    @Test
    fun `context overflow error is not retried`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("prompt is too long: 300000 tokens > 200000 maximum"))
        }
        val agent = session(streams)
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
        val agent = session(streams, retrySettings = RetrySettings(enabled = false))
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
        val agent = session(streams, sleep = { delays.add(it) })

        val events = collectEvents(agent)

        assertEquals(4, streams.seenContexts.size)
        assertEquals(listOf(2000L, 4000L, 8000L), delays)
        val starts = events.filterIsInstance<AgentEvent.AutoRetryStart>()
        assertEquals(listOf(1, 2, 3), starts.map { it.attempt })
        assertEquals(
            listOf(
                AgentEvent.AutoRetryEnd(success = false, attempt = 3, finalError = "terminated")
            ),
            events.filterIsInstance<AgentEvent.AutoRetryEnd>()
        )
        assertEquals(
            "terminated",
            (agent.state.value.messages.last() as AssistantMessage).errorMessage
        )
    }

    @Test
    fun `retry after tool results continues from the toolResult without replaying the partial tool turn`() =
        runTest {
            // pi drops only the trailing assistant error from agent state and
            // continues from the trailing toolResult: the partial tool turn is
            // neither replayed nor duplicated, and the session tree keeps
            // everything (append-only).
            val toolUse = assistant(text = "", stopReason = StopReason.TOOL_USE).copy(
                content = listOf(ToolCall("call-1", "get_weather", "{}"))
            )
            val fakeTool = object : AgentTool {
                override val definition =
                    Tool("get_weather", "fake weather", JsonPrimitive("object"))
                override val label = "get_weather"
                override fun validateArguments(arguments: JsonObject) = arguments
                override suspend fun execute(
                    toolCallId: String,
                    arguments: JsonObject,
                    onUpdate: AgentToolUpdateCallback
                ) = AgentToolResult(content = listOf(TextContent("sunny")))
            }
            val streams = ScriptedStreams().apply {
                streams.add(flowOf(AssistantMessageEvent.Done(StopReason.TOOL_USE, toolUse)))
                streams.add(errorStream("terminated"))
                streams.add(okStream("recovered"))
            }
            val agent = AgentSession(
                agent = Agent(
                    model = model,
                    systemPrompt = "be brief",
                    streamOptions = SimpleStreamOptions(),
                    tools = listOf(fakeTool),
                    streamFn = streams.streamFn
                ),
                retrySettings = RetrySettings(),
                sleep = { }
            )

            val events = collectEvents(agent)

            assertEquals(
                listOf(
                    AgentEvent.AutoRetryStart(
                        attempt = 1,
                        maxAttempts = 3,
                        delayMs = 2000,
                        errorMessage = "terminated"
                    ),
                    AgentEvent.AutoRetryEnd(success = true, attempt = 1)
                ),
                events.filterIsInstance<AgentEvent.AutoRetryStart>() +
                    events.filterIsInstance<AgentEvent.AutoRetryEnd>()
            )

            assertEquals(3, streams.seenContexts.size)
            assertEquals(streams.seenContexts[1], streams.seenContexts[2])
            assertTrue(
                streams.seenContexts[1].last() is works.resolve.pathfinder.ai.ToolResultMessage
            )

            val state = agent.state.value.messages
            assertEquals(4, state.size)
            assertTrue(state[1] is AssistantMessage)
            assertTrue(state[2] is works.resolve.pathfinder.ai.ToolResultMessage)
            val recovered = state[3] as AssistantMessage
            assertEquals("recovered", (recovered.content.single() as TextContent).text)
            assertNull(agent.state.value.errorMessage)

            val tree = agent.conversation.activeMessages()
            assertEquals(5, tree.size)
            val errored = tree[3] as AssistantMessage
            assertEquals(StopReason.ERROR, errored.stopReason)
        }

    @Test
    fun `prompt creates the user message and the session tree keeps retried errors`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("terminated"))
            streams.add(okStream("recovered"))
        }
        val agent = session(streams, sleep = { })
        agent.prompt("hi")

        // Every message_end lands in the session tree, including the error
        // removed from agent state by the retry.
        val entries = agent.conversation.activeEntries()
        assertEquals(3, entries.size)
        val user = entries[0] as MessageEntry
        val text = ((user.message as UserMessage).content.single() as TextContent).text
        assertEquals("hi", text)
        val failed = (entries[1] as MessageEntry).message as AssistantMessage
        assertEquals(StopReason.ERROR, failed.stopReason)
        assertEquals(
            StopReason.STOP,
            (entries[2] as MessageEntry).message.let {
                (it as AssistantMessage).stopReason
            }
        )
        assertEquals(2, agent.state.value.messages.size)
    }

    @Test
    fun `abort during backoff cancels the retry and reports it`() = runTest {
        val streams = ScriptedStreams().apply {
            streams.add(errorStream("terminated"))
            streams.add(okStream("never reached"))
        }
        val started = CompletableDeferred<Unit>()
        val agent = session(streams, sleep = {
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
        // The error message was removed from agent state before the backoff;
        // it stays in the session layer.
        assertEquals(1, streams.seenContexts.size)
        assertEquals(1, agent.state.value.messages.size)
        assertFalse(agent.state.value.isStreaming)
    }
}
