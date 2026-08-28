package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentLoopTest {

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
    )

    private fun typeLabels(events: List<AgentEvent>) = events.map { it::class.simpleName }

    @Test
    fun `success path emits full lifecycle in order and returns new messages`() = runTest {
        val final = assistant("hi there")
        var capturedContext: Context? = null
        var capturedOptions: SimpleStreamOptions? = null
        val streamFn = StreamFn { m, ctx, opts ->
            assertEquals(model, m)
            capturedContext = ctx
            capturedOptions = opts
            flowOf(
                AssistantMessageEvent.Start(assistant("")),
                AssistantMessageEvent.TextDelta(0, "hi ", assistant("hi ")),
                AssistantMessageEvent.TextEnd(0, "hi there", final),
                AssistantMessageEvent.Done(StopReason.STOP, final),
            )
        }
        val prompt = UserMessage.ofText("q")
        val context = AgentContext(systemPrompt = "sys", messages = listOf(UserMessage.ofText("earlier")))

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(listOf(prompt), context, AgentLoopConfig(model, streamFn = streamFn)) {
            events.add(it)
        }

        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd",
                "MessageStart", "MessageUpdate", "MessageUpdate", "MessageEnd",
                "TurnEnd", "AgentEnd",
            ),
            typeLabels(events),
        )
        // Prompt message pair
        assertEquals(prompt, (events[2] as AgentEvent.MessageStart).message)
        // Assistant message start carries the Start partial
        assertEquals(assistant(""), (events[4] as AgentEvent.MessageStart).message)
        // Updates carry the provider partial snapshots
        assertEquals(assistant("hi "), (events[5] as AgentEvent.MessageUpdate).message)
        val updateEvent = events[5] as AgentEvent.MessageUpdate
        assertTrue(updateEvent.assistantMessageEvent is AssistantMessageEvent.TextDelta)
        // Message end and turn end carry the final message
        assertEquals(final, (events[7] as AgentEvent.MessageEnd).message)
        assertEquals(final, (events[8] as AgentEvent.TurnEnd).message)
        // New messages: prompt + assistant, source order
        assertEquals(listOf<Message>(prompt, final), result)
        assertEquals(listOf<Message>(prompt, final), (events[9] as AgentEvent.AgentEnd).messages)

        // Provider context: existing messages + prompt, system prompt preserved, no tools
        assertEquals("sys", capturedContext!!.systemPrompt)
        assertEquals(listOf<Message>(UserMessage.ofText("earlier"), prompt), capturedContext.messages)
        assertTrue(capturedContext.tools.isEmpty())
        assertEquals(SimpleStreamOptions(), capturedOptions)

        // Immutable snapshot: the caller's context list is untouched
        assertEquals(1, context.messages.size)
    }

    @Test
    fun `multiple prompts each emit message pairs and reach the provider`() = runTest {
        val final = assistant()
        var capturedContext: Context? = null
        val streamFn = StreamFn { _, ctx, _ ->
            capturedContext = ctx
            flowOf(AssistantMessageEvent.Done(StopReason.STOP, final))
        }
        val p1 = UserMessage.ofText("a")
        val p2 = UserMessage.ofText("b")

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(listOf(p1, p2), AgentContext(messages = emptyList()), AgentLoopConfig(model, streamFn = streamFn)) {
            events.add(it)
        }

        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd", "MessageStart", "MessageEnd",
                "MessageStart", "MessageEnd", "TurnEnd", "AgentEnd",
            ),
            typeLabels(events),
        )
        assertEquals(listOf<Message>(p1, p2, final), result)
        assertEquals(listOf<Message>(p1, p2), capturedContext!!.messages)
    }

    @Test
    fun `pre-start error synthesizes message_start before message_end`() = runTest {
        val error = assistant(stopReason = StopReason.ERROR, errorMessage = "401 unauthorized")
        val streamFn = StreamFn { _, _, _ ->
            flowOf(AssistantMessageEvent.Error(StopReason.ERROR, error))
        }

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList()),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd",
                "MessageStart", "MessageEnd", "TurnEnd", "AgentEnd",
            ),
            typeLabels(events),
        )
        assertEquals(error, (events[4] as AgentEvent.MessageStart).message)
        assertEquals(error, (events[5] as AgentEvent.MessageEnd).message)
        assertEquals(error, (events[6] as AgentEvent.TurnEnd).message)
        val newMessages = listOf<UserMessage>(UserMessage.ofText("q")) + listOf(error)
        assertEquals(newMessages, result)
        assertEquals(newMessages, (events[7] as AgentEvent.AgentEnd).messages)
    }

    @Test
    fun `stream completing without terminal event produces error message and normal lifecycle`() = runTest {
        val partial = assistant("partial")
        val streamFn = StreamFn { _, _, _ ->
            flowOf(
                AssistantMessageEvent.Start(assistant("")),
                AssistantMessageEvent.TextDelta(0, "partial", partial),
            )
        }

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList()),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd",
                "MessageStart", "MessageUpdate", "MessageEnd", "TurnEnd", "AgentEnd",
            ),
            typeLabels(events),
        )
        val endMessage = (events[6] as AgentEvent.MessageEnd).message as AssistantMessage
        assertEquals(StopReason.ERROR, endMessage.stopReason)
        assertEquals("Provider stream completed without a terminal event", endMessage.errorMessage)
        // The synthesized error preserves the latest partial's content and metadata.
        assertEquals(listOf(TextContent("partial")), endMessage.content)
        assertEquals(model.provider, endMessage.provider)
        assertEquals(model.id, endMessage.model)
        assertEquals(2, result.size)
        assertEquals(endMessage, result[1])
    }

    @Test
    fun `malformed stream with no events at all gets fresh error message with timestamp`() = runTest {
        val before = System.currentTimeMillis()
        val streamFn = StreamFn { _, _, _ -> flowOf() }

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList()),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val endMessage = (events[5] as AgentEvent.MessageEnd).message as AssistantMessage
        assertEquals(StopReason.ERROR, endMessage.stopReason)
        assertEquals("Provider stream completed without a terminal event", endMessage.errorMessage)
        assertTrue(endMessage.content.isEmpty())
        assertTrue("timestamp should be current", endMessage.timestamp >= before)
        assertEquals(endMessage, result[1])
    }

    @Test
    fun `terminal event cancels upstream and first terminal wins`() = runTest {
        val final = assistant("done")
        val secondTerminal = assistant("second")
        var upstreamCancelled = false
        var eventsAfterTerminal = 0
        val streamFn = StreamFn { _, _, _ ->
            flow {
                emit(AssistantMessageEvent.Start(assistant("")))
                emit(AssistantMessageEvent.TextDelta(0, "done", final))
                emit(AssistantMessageEvent.Done(StopReason.STOP, final))
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    upstreamCancelled = true
                    throw e
                }
                eventsAfterTerminal++
                emit(AssistantMessageEvent.Done(StopReason.STOP, secondTerminal))
            }
        }

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList()),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        // The run completes normally despite upstream hanging after the terminal event.
        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd",
                "MessageStart", "MessageUpdate", "MessageEnd", "TurnEnd", "AgentEnd",
            ),
            typeLabels(events),
        )
        assertTrue("upstream must be cancelled after the first terminal event", upstreamCancelled)
        assertEquals(0, eventsAfterTerminal)
        // The first terminal message wins.
        assertEquals(final, (events[6] as AgentEvent.MessageEnd).message)
        assertEquals(final, result[1])
    }

    @Test
    fun `unexpected tool call is finalized as error without another turn`() = runTest {
        val toolCall = works.resolve.pathfinder.ai.core.ToolCall("t1", "web_search", "{}")
        val final = AssistantMessage(
            content = listOf(toolCall),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = StopReason.TOOL_USE,
        )
        val streamFn = StreamFn { _, _, _ ->
            flowOf(AssistantMessageEvent.Done(StopReason.TOOL_USE, final))
        }

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList()),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        // Exactly one turn: no second TurnStart/assistant pair.
        assertEquals(1, typeLabels(events).count { it == "TurnStart" })
        val turnEnd = events.filterIsInstance<AgentEvent.TurnEnd>().single()
        val finalEnd = result.last() as AssistantMessage
        assertEquals(StopReason.ERROR, finalEnd.stopReason)
        assertTrue(finalEnd.errorMessage!!.contains("tools are not supported"))
        assertEquals(StopReason.ERROR, turnEnd.message.stopReason)
        assertEquals(finalEnd, (events.last() as AgentEvent.AgentEnd).messages.last())
    }

    @Test
    fun `non-empty tool list in context is rejected up front`() = runTest {
        val streamFn = StreamFn { _, _, _ -> throw IllegalStateException("stream must not be called") }
        val tools = listOf(
            object : AgentTool {
                override val definition = Tool("t", "d", kotlinx.serialization.json.Json.parseToJsonElement("{}"))
                override val label = "t"
                override fun validateArguments(arguments: kotlinx.serialization.json.JsonObject) = arguments
                override suspend fun execute(
                    toolCallId: String,
                    arguments: kotlinx.serialization.json.JsonObject,
                    onUpdate: AgentToolUpdateCallback,
                ): AgentToolResult = error("must not execute")
            },
        )
        try {
            runAgentLoop(
                listOf(UserMessage.ofText("q")),
                AgentContext(messages = emptyList(), tools = tools),
                AgentLoopConfig(model, streamFn = streamFn),
            ) { error("emit must not be called") }
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Tools are not supported"))
        }
    }

    @Test
    fun `cancellation propagates without synthetic error or agent_end`() = runTest {
        val started = CompletableDeferred<Unit>()
        var collectedAfterCancel = false
        val streamFn = StreamFn { _, _, _ ->
            flow {
                emit(AssistantMessageEvent.Start(assistant("")))
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    collectedAfterCancel = true
                    throw e
                }
            }
        }

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch {
            runAgentLoop(
                listOf(UserMessage.ofText("q")),
                AgentContext(messages = emptyList()),
                AgentLoopConfig(model, streamFn = streamFn),
            ) { events.add(it) }
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue("launched job must end cancelled", job.isCancelled)
        assertTrue(collectedAfterCancel)
        // Lifecycle stops after the cancellation point: no MessageEnd, TurnEnd, AgentEnd, or synthetic Error.
        assertEquals(listOf("AgentStart", "TurnStart", "MessageStart", "MessageEnd", "MessageStart"), typeLabels(events))
        assertTrue(events.none { it is AgentEvent.MessageEnd && it.message is AssistantMessage && it.message.stopReason == StopReason.ERROR })
        assertTrue(events.none { it is AgentEvent.AgentEnd })
    }
}
