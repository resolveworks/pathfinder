package works.resolve.pathfinder.agent

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.MessageRole
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.UserMessage

/**
 * Steering and follow-up queue semantics against pi's Agent
 * (agent.test.ts): queued messages stay out of the transcript until
 * delivered, continue() from an assistant tail drains steering first with
 * one-at-a-time semantics (skipInitialSteeringPoll), and follow-ups run as
 * the continuation prompt.
 */
class AgentQueuesTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid"
    )

    private fun assistant(text: String, stopReason: StopReason = StopReason.STOP) =
        AssistantMessage(
            content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = stopReason,
            timestamp = 42L
        )

    private fun doneStream(message: AssistantMessage): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(text = "")),
        AssistantMessageEvent.Done(StopReason.STOP, message)
    )

    private fun userText(message: Message): String? = (message as? UserMessage)
        ?.content
        ?.filterIsInstance<TextContent>()
        ?.joinToString("") { it.text }

    @Test
    fun `steer and followUp queue messages without entering the transcript`() {
        val agent = Agent(model, streamFn = StreamFn { _, _, _ -> error("unused") })

        agent.steer(UserMessage.ofText("Steering message", 1L))
        agent.followUp(UserMessage.ofText("Follow-up message", 2L))

        assertTrue(agent.hasQueuedMessages())
        assertTrue(agent.state.value.messages.none { it is UserMessage })

        agent.clearAllQueues()
        assertFalse(agent.hasQueuedMessages())
    }

    @Test
    fun `continueRun processes queued follow-ups after an assistant turn`() = runTest {
        val agent = Agent(
            model,
            streamFn = StreamFn { _, _, _ ->
                doneStream(assistant("Processed"))
            }
        )
        agent.replaceTranscript(
            listOf(UserMessage.ofText("Initial", 10L), assistant("Initial response"))
        )

        agent.followUp(UserMessage.ofText("Queued follow-up", 20L))
        agent.continueRun()

        val messages = agent.state.value.messages
        assertEquals(
            listOf("Initial", "Queued follow-up"),
            messages.filterIsInstance<UserMessage>().map { userText(it) }
        )
        assertTrue(messages.last() is AssistantMessage)
    }

    @Test
    fun `continueRun keeps one-at-a-time steering semantics from assistant tail`() = runTest {
        val contexts = CopyOnWriteArrayList<List<Message>>()
        var responseCount = 0
        val agent = Agent(
            model,
            streamFn = StreamFn { _, context, _ ->
                contexts.add(context.messages)
                responseCount++
                doneStream(assistant("Processed $responseCount"))
            }
        )
        agent.replaceTranscript(
            listOf(UserMessage.ofText("Initial", 10L), assistant("Initial response"))
        )

        agent.steer(UserMessage.ofText("Steering 1", 20L))
        agent.steer(UserMessage.ofText("Steering 2", 21L))
        agent.continueRun()

        val tail = agent.state.value.messages.takeLast(4)
        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.ASSISTANT
            ),
            tail.map { it.role }
        )
        assertEquals(2, responseCount)
        // skipInitialSteeringPoll: the drained Steering 1 runs as the
        // continuation prompt, and the loop's initial poll does not also
        // deliver Steering 2 into that first turn.
        assertTrue(contexts[0].any { userText(it) == "Steering 1" })
        assertFalse(contexts[0].any { userText(it) == "Steering 2" })
        assertTrue(contexts[1].any { userText(it) == "Steering 2" })
    }

    @Test
    fun `continueRun prefers steering over follow-ups from an assistant tail`() = runTest {
        val contexts = CopyOnWriteArrayList<List<Message>>()
        var responseCount = 0
        val agent = Agent(
            model,
            streamFn = StreamFn { _, context, _ ->
                contexts.add(context.messages)
                responseCount++
                doneStream(assistant("Processed $responseCount"))
            }
        )
        agent.replaceTranscript(
            listOf(UserMessage.ofText("Initial", 10L), assistant("Initial response"))
        )

        agent.followUp(UserMessage.ofText("Queued follow-up", 20L))
        agent.steer(UserMessage.ofText("Steering 1", 21L))
        agent.continueRun()

        assertEquals(2, responseCount)
        // Steering wins the continuation prompt; the follow-up is delivered
        // by the same run's end-of-run follow-up poll, after the steering
        // turn completed.
        assertTrue(contexts[0].any { userText(it) == "Steering 1" })
        assertFalse(contexts[0].any { userText(it) == "Queued follow-up" })
        assertTrue(contexts[1].any { userText(it) == "Queued follow-up" })
        assertFalse(agent.hasQueuedMessages())
    }

    @Test
    fun `an exception escaping after an abort request is classified aborted`() = runTest {
        // pi's handleRunFailure consults the run's abort signal when an
        // ordinary exception escapes: a listener throwing during the
        // post-abort tail after abort() was requested yields stopReason
        // "aborted", not "error".
        val toolStart = CompletableDeferred<Unit>()
        val blockingTool = object : AgentTool {
            override val definition = Tool(
                "block",
                "Blocks until cancelled",
                buildJsonObject { put("type", "object") }
            )
            override val label = "Block"

            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback
            ): AgentToolResult {
                toolStart.complete(Unit)
                awaitCancellation()
            }
        }
        var listenerThrown = false
        val agent = Agent(
            model,
            tools = listOf(blockingTool),
            streamFn = StreamFn { _, _, _ ->
                doneStream(
                    assistant("").copy(
                        content = listOf(ToolCall("call-1", "block", JsonObject(emptyMap()))),
                        stopReason = StopReason.TOOL_USE
                    )
                )
            }
        )
        agent.attachEventSink { event ->
            val message = (event as? AgentEvent.MessageEnd)?.message
            if (!listenerThrown && message is AssistantMessage &&
                message.stopReason == StopReason.ABORTED
            ) {
                listenerThrown = true
                throw RuntimeException("listener exploded")
            }
        }

        val run = async {
            agent.prompt(listOf(UserMessage.ofText("hello", 1L)))
        }
        toolStart.await()
        agent.abort()
        run.await()

        val failure = agent.state.value.messages.last() as AssistantMessage
        assertEquals(StopReason.ABORTED, failure.stopReason)
        assertEquals("Run aborted", failure.errorMessage)
    }
}
