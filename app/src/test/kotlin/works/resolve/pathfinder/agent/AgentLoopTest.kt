package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun toolCallAssistant(vararg calls: ToolCall, stopReason: StopReason = StopReason.TOOL_USE) =
        AssistantMessage(
            content = calls.toList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = stopReason,
        )

    private fun scriptedStream(
        vararg messages: AssistantMessage,
        contexts: MutableList<Context> = mutableListOf(),
    ): StreamFn {
        var call = 0
        return StreamFn { _, ctx, _ ->
            contexts.add(ctx)
            val message = messages.getOrElse(call++) { error("unexpected provider call #${call - 1}") }
            flowOf(AssistantMessageEvent.Done(message.stopReason, message))
        }
    }

    private class FakeTool(
        override val definition: Tool = Tool("my_tool", "test tool", buildJsonObject {}),
        override val executionMode: ToolExecutionMode? = null,
        val validate: (JsonObject) -> JsonObject = { it },
        val executeImpl: suspend (String, JsonObject, AgentToolUpdateCallback) -> AgentToolResult =
            { _, _, _ -> AgentToolResult(listOf(TextContent("ok"))) },
    ) : AgentTool {
        override val label = definition.name

        val executedCalls = mutableListOf<Pair<String, JsonObject>>()

        override fun validateArguments(arguments: JsonObject): JsonObject = validate(arguments)

        override suspend fun execute(
            toolCallId: String,
            arguments: JsonObject,
            onUpdate: AgentToolUpdateCallback,
        ): AgentToolResult {
            executedCalls.add(toolCallId to arguments)
            return executeImpl(toolCallId, arguments, onUpdate)
        }
    }

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
        assertEquals(prompt, (events[2] as AgentEvent.MessageStart).message)
        assertEquals(assistant(""), (events[4] as AgentEvent.MessageStart).message)
        assertEquals(assistant("hi "), (events[5] as AgentEvent.MessageUpdate).message)
        val updateEvent = events[5] as AgentEvent.MessageUpdate
        assertTrue(updateEvent.assistantMessageEvent is AssistantMessageEvent.TextDelta)
        assertEquals(final, (events[7] as AgentEvent.MessageEnd).message)
        assertEquals(final, (events[8] as AgentEvent.TurnEnd).message)
        assertEquals(listOf<Message>(prompt, final), result)
        assertEquals(listOf<Message>(prompt, final), (events[9] as AgentEvent.AgentEnd).messages)

        assertEquals("sys", capturedContext!!.systemPrompt)
        assertEquals(listOf<Message>(UserMessage.ofText("earlier"), prompt), capturedContext.messages)
        assertTrue(capturedContext.tools.isEmpty())
        assertEquals(SimpleStreamOptions(), capturedOptions)

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
        assertEquals(final, (events[6] as AgentEvent.MessageEnd).message)
        assertEquals(final, result[1])
    }

    @Test
    fun `cancellation during streaming propagates without synthetic error or agent_end`() = runTest {
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
        assertEquals(listOf("AgentStart", "TurnStart", "MessageStart", "MessageEnd", "MessageStart"), typeLabels(events))
        assertTrue(events.none { it is AgentEvent.MessageEnd && it.message is AssistantMessage && it.message.stopReason == StopReason.ERROR })
        assertTrue(events.none { it is AgentEvent.AgentEnd })
    }

    @Test
    fun `tool definitions reach the provider in first and follow-up contexts`() = runTest {
        val tool1 = FakeTool(Tool("t1", "one", buildJsonObject {}))
        val tool2 = FakeTool(Tool("t2", "two", buildJsonObject {}))
        val contexts = mutableListOf<Context>()
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "t1", """{"a":1}""")),
            assistant("done"),
            contexts = contexts,
        )
        val prompt = UserMessage.ofText("q")

        runAgentLoop(
            listOf(prompt),
            AgentContext(messages = emptyList(), tools = listOf(tool1, tool2)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { }

        assertEquals(2, contexts.size)
        assertEquals(listOf(tool1.definition, tool2.definition), contexts[0].tools)
        assertEquals(listOf(tool1.definition, tool2.definition), contexts[1].tools)
        assertEquals("t1", ((contexts[1].messages[2]) as ToolResultMessage).toolName)
    }

    @Test
    fun `single successful tool call emits full lifecycle and returns messages in order`() = runTest {
        val tool = FakeTool(
            Tool("my_tool", "d", buildJsonObject {}),
            validate = { args -> if (args.containsKey("x")) args else JsonObject(args + ("x" to kotlinx.serialization.json.JsonPrimitive(0))) },
        )
        val assistant1 = toolCallAssistant(ToolCall("c1", "my_tool", """{"a":1}"""))
        val assistant2 = assistant("done")
        val streamFn = scriptedStream(assistant1, assistant2)
        val prompt = UserMessage.ofText("q")

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(prompt),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd", // prompt
                "MessageStart", "MessageEnd", // assistant 1
                "ToolExecutionStart", "ToolExecutionEnd",
                "MessageStart", "MessageEnd", // tool result message
                "TurnEnd", "TurnStart",
                "MessageStart", "MessageEnd", // assistant 2
                "TurnEnd", "AgentEnd",
            ),
            typeLabels(events),
        )

        val start = events[6] as AgentEvent.ToolExecutionStart
        assertEquals("c1", start.toolCallId)
        assertEquals("my_tool", start.toolName)
        assertEquals(buildJsonObject { put("a", 1) }, start.arguments)

        val end = events[7] as AgentEvent.ToolExecutionEnd
        assertEquals("c1", end.toolCallId)
        assertFalse(end.isError)
        assertEquals(listOf(TextContent("ok")), end.result.content)

        val toolResult = events[8] as AgentEvent.MessageStart
        val trm = toolResult.message as ToolResultMessage
        assertEquals("c1", trm.toolCallId)
        assertEquals("my_tool", trm.toolName)
        assertFalse(trm.isError)
        assertEquals(trm, (events[9] as AgentEvent.MessageEnd).message)

        val turnEnd1 = events[10] as AgentEvent.TurnEnd
        assertEquals(assistant1, turnEnd1.message)
        assertEquals(listOf(trm), turnEnd1.toolResults)

        val turnEnd2 = events[14] as AgentEvent.TurnEnd
        assertEquals(assistant2, turnEnd2.message)
        assertTrue(turnEnd2.toolResults.isEmpty())

        assertEquals(listOf("c1" to buildJsonObject { put("a", 1); put("x", 0) }), tool.executedCalls)

        assertEquals(listOf<Message>(prompt, assistant1, trm, assistant2), result)
        assertEquals(result, (events[15] as AgentEvent.AgentEnd).messages)
    }

    @Test
    fun `multiple tool turns continue until a response has no tool calls`() = runTest {
        val tool = FakeTool(Tool("t", "d", buildJsonObject {}))
        val a1 = toolCallAssistant(ToolCall("c1", "t", "{}"), ToolCall("c2", "t", "{}"))
        val a2 = toolCallAssistant(ToolCall("c3", "t", "{}"))
        val a3 = assistant("done")
        val streamFn = scriptedStream(a1, a2, a3)

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        assertEquals(3, typeLabels(events).count { it == "TurnStart" })
        val turnEnds = events.filterIsInstance<AgentEvent.TurnEnd>()
        assertEquals(listOf(2, 1, 0), turnEnds.map { it.toolResults.size })
        assertEquals(listOf("c1", "c2", "c3"), tool.executedCalls.map { it.first })
        val roles = result.map { it.role }
        assertEquals(7, result.size)
        assertEquals("c1", (result[2] as ToolResultMessage).toolCallId)
        assertEquals("c2", (result[3] as ToolResultMessage).toolCallId)
        assertEquals("c3", (result[5] as ToolResultMessage).toolCallId)
        assertTrue(roles[0].name == "USER")
    }

    @Test
    fun `assistant error stop reason never executes embedded tool calls`() = runTest {
        val tool = FakeTool()
        val message = toolCallAssistant(ToolCall("c1", "my_tool", "{}"), stopReason = StopReason.ERROR)
            .copy(errorMessage = "boom")
        val streamFn = scriptedStream(message)

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        assertTrue(tool.executedCalls.isEmpty())
        assertTrue(events.none { it is AgentEvent.ToolExecutionStart })
        val turnEnd = events.filterIsInstance<AgentEvent.TurnEnd>().single()
        assertTrue(turnEnd.toolResults.isEmpty())
        assertEquals(listOf<Message>(UserMessage.ofText("q"), message), result)
        assertTrue(events.last() is AgentEvent.AgentEnd)
    }

    @Test
    fun `assistant aborted stop reason never executes embedded tool calls`() = runTest {
        val tool = FakeTool()
        val message = toolCallAssistant(ToolCall("c1", "my_tool", "{}"), stopReason = StopReason.ABORTED)
        val streamFn = scriptedStream(message)

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        assertTrue(tool.executedCalls.isEmpty())
        assertTrue(events.none { it is AgentEvent.ToolExecutionStart })
        assertTrue(events.filterIsInstance<AgentEvent.TurnEnd>().single().toolResults.isEmpty())
        assertEquals(listOf<Message>(UserMessage.ofText("q"), message), result)
    }

    @Test
    fun `length stop reason fails every call without invoking tools`() = runTest {
        val tool = FakeTool()
        val message = toolCallAssistant(
            ToolCall("c1", "my_tool", """{"a":1}"""),
            ToolCall("c2", "my_tool", "not json"),
            stopReason = StopReason.LENGTH,
        )
        val streamFn = scriptedStream(message, assistant("redone"))

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        assertTrue(tool.executedCalls.isEmpty())
        val expectedText = "Tool call \"my_tool\" was not executed: the response hit the output token limit, " +
            "so its arguments may be truncated. Re-issue the tool call with complete arguments."

        val starts = events.filterIsInstance<AgentEvent.ToolExecutionStart>()
        assertEquals(listOf("c1", "c2"), starts.map { it.toolCallId })
        assertEquals(buildJsonObject { put("a", 1) }, starts[0].arguments)
        // Unparseable raw arguments degrade to an empty object for the start event.
        assertEquals(JsonObject(emptyMap()), starts[1].arguments)

        val ends = events.filterIsInstance<AgentEvent.ToolExecutionEnd>()
        assertEquals(listOf("c1", "c2"), ends.map { it.toolCallId })
        assertTrue(ends.all { it.isError })
        assertTrue(ends.all { (it.result.content.single() as TextContent).text == expectedText })
        assertTrue(ends.all { it.result.details == JsonObject(emptyMap()) })

        val turnEnds = events.filterIsInstance<AgentEvent.TurnEnd>()
        assertEquals(2, turnEnds.size)
        assertEquals(2, turnEnds[0].toolResults.size)
        assertTrue(turnEnds[0].toolResults.all { it.isError })
        assertEquals(2, typeLabels(events).count { it == "TurnStart" })
        // The failed calls count as tool results, so the model gets a follow-up turn.
        assertEquals(5, result.size) // prompt, assistant, two tool results, follow-up assistant
        assertEquals("redone", ((result[4] as AssistantMessage).content.single() as TextContent).text)
    }

    @Test
    fun `malformed json arguments fail validation with stable message`() = runTest {
        val tool = FakeTool()
        val events = mutableListOf<AgentEvent>()
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "my_tool", """{"a": """)),
            assistant("done"),
        )
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val end = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single()
        assertTrue(end.isError)
        assertEquals(
            "Validation failed for tool \"my_tool\": arguments are not a JSON object",
            (end.result.content.single() as TextContent).text,
        )
        assertTrue(tool.executedCalls.isEmpty())
        assertEquals(2, typeLabels(events).count { it == "TurnStart" })
        assertEquals(4, result.size)
    }

    @Test
    fun `non-object json arguments fail validation with stable message`() = runTest {
        val tool = FakeTool()
        val events = mutableListOf<AgentEvent>()
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "my_tool", """[1,2]""")),
            assistant("done"),
        )
        runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val end = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single()
        assertEquals(
            "Validation failed for tool \"my_tool\": arguments are not a JSON object",
            (end.result.content.single() as TextContent).text,
        )
        assertTrue(tool.executedCalls.isEmpty())
    }

    @Test
    fun `validator rejection message becomes the error result`() = runTest {
        val tool = FakeTool(validate = { throw IllegalArgumentException("bad args") })
        val events = mutableListOf<AgentEvent>()
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "my_tool", "{}")),
            assistant("done"),
        )
        runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val end = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single()
        assertTrue(end.isError)
        assertEquals("bad args", (end.result.content.single() as TextContent).text)
        assertTrue(tool.executedCalls.isEmpty())
    }

    @Test
    fun `unknown tool produces verbatim not-found error and a follow-up turn`() = runTest {
        val tool = FakeTool()
        val events = mutableListOf<AgentEvent>()
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "ghost", "{}")),
            assistant("done"),
        )
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val end = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single()
        assertTrue(end.isError)
        assertEquals("Tool ghost not found", (end.result.content.single() as TextContent).text)
        assertTrue(tool.executedCalls.isEmpty())
        assertEquals(2, typeLabels(events).count { it == "TurnStart" })
        assertEquals(4, result.size)
    }

    @Test
    fun `thrown execution failure becomes an error result and a follow-up turn`() = runTest {
        val tool = FakeTool(executeImpl = { _, _, _ -> throw IllegalStateException("boom") })
        val events = mutableListOf<AgentEvent>()
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "my_tool", "{}")),
            assistant("done"),
        )
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        assertEquals(1, tool.executedCalls.size)
        val end = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single()
        assertTrue(end.isError)
        assertEquals("boom", (end.result.content.single() as TextContent).text)
        assertEquals(JsonObject(emptyMap()), end.result.details)
        val trm = result[2] as ToolResultMessage
        assertTrue(trm.isError)
        assertEquals(2, typeLabels(events).count { it == "TurnStart" })
    }

    @Test
    fun `tool result fields survive message construction`() = runTest {
        val usage = Usage(input = 3, output = 5)
        val details = buildJsonObject { put("kind", "diff") }
        val content = listOf(
            TextContent("summary"),
            ImageContent(data = "aGk=", mimeType = "image/png"),
        )
        val tool = FakeTool(
            executeImpl = { _, _, _ ->
                AgentToolResult(content = content, details = details, usage = usage, addedToolNames = listOf("extra"))
            },
        )
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "my_tool", "{}")),
            assistant("done"),
        )

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val trm = result.filterIsInstance<ToolResultMessage>().single()
        assertEquals("c1", trm.toolCallId)
        assertEquals("my_tool", trm.toolName)
        assertEquals(content, trm.content)
        assertEquals(details, trm.details)
        assertEquals(usage, trm.usage)
        assertEquals(listOf("extra"), trm.addedToolNames)
        assertFalse(trm.isError)
        assertTrue("timestamp set", trm.timestamp > 0)
    }

    @Test
    fun `parallel batch starts in source order, ends in completion order, results in source order`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val started1 = CompletableDeferred<Unit>()
        val started2 = CompletableDeferred<Unit>()
        val tool1 = FakeTool(
            Tool("t1", "d", buildJsonObject {}),
            executeImpl = { _, _, _ ->
                started1.complete(Unit)
                gate1.await()
                AgentToolResult(listOf(TextContent("r1")))
            },
        )
        val tool2 = FakeTool(
            Tool("t2", "d", buildJsonObject {}),
            executeImpl = { _, _, _ ->
                started2.complete(Unit)
                gate2.await()
                AgentToolResult(listOf(TextContent("r2")))
            },
        )
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "t1", "{}"), ToolCall("c2", "t2", "{}")),
            assistant("done"),
        )

        val events = mutableListOf<AgentEvent>()
        val mutex = Mutex()
        val job = backgroundScope.launch {
            runAgentLoop(
                listOf(UserMessage.ofText("q")),
                AgentContext(messages = emptyList(), tools = listOf(tool1, tool2)),
                AgentLoopConfig(model, streamFn = streamFn, toolExecution = ToolExecutionMode.PARALLEL),
            ) { event -> mutex.withLock { events.add(event) } }
        }
        started1.await()
        started2.await()
        // Release the second first: its end event must precede the first's.
        gate2.complete(Unit)
        testScheduler.runCurrent()
        gate1.complete(Unit)
        job.join()

        assertEquals(listOf("c1", "c2"), events.filterIsInstance<AgentEvent.ToolExecutionStart>().map { it.toolCallId })
        assertEquals(listOf("c2", "c1"), events.filterIsInstance<AgentEvent.ToolExecutionEnd>().map { it.toolCallId })
        val trms = events.mapNotNull { (it as? AgentEvent.MessageStart)?.message }.filterIsInstance<ToolResultMessage>()
        assertEquals(listOf("c1", "c2"), trms.map { it.toolCallId })
        assertEquals(listOf("c1", "c2"), events.filterIsInstance<AgentEvent.TurnEnd>().first().toolResults.map { it.toolCallId })
        val resultMessages = (events.last() as AgentEvent.AgentEnd).messages
        assertEquals(listOf("c1", "c2"), resultMessages.filterIsInstance<ToolResultMessage>().map { it.toolCallId })
    }

    @Test
    fun `immediate validation failure does not prevent other prepared calls executing`() = runTest {
        val ok = FakeTool(Tool("t2", "d", buildJsonObject {}))
        val streamFn = scriptedStream(
            toolCallAssistant(
                ToolCall("c1", "ghost", "{}"),
                ToolCall("c2", "t2", "{}"),
            ),
            assistant("done"),
        )

        val events = mutableListOf<AgentEvent>()
        val result = runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(ok)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val ends = events.filterIsInstance<AgentEvent.ToolExecutionEnd>()
        assertEquals(listOf("c1", "c2"), ends.map { it.toolCallId })
        assertEquals("Tool ghost not found", (ends[0].result.content.single() as TextContent).text)
        assertTrue(ends[0].isError)
        assertFalse(ends[1].isError)
        assertEquals(listOf("c2" to JsonObject(emptyMap())), ok.executedCalls)
        assertEquals(listOf("c1", "c2"), result.filterIsInstance<ToolResultMessage>().map { it.toolCallId })
    }

    @Test
    fun `per-tool sequential execution mode serializes the whole batch`() = runTest {
        val t1Finished = CompletableDeferred<Unit>()
        val tool1 = FakeTool(
            Tool("t1", "d", buildJsonObject {}),
            executionMode = ToolExecutionMode.SEQUENTIAL,
            executeImpl = { _, _, _ ->
                AgentToolResult(listOf(TextContent("r1"))).also { t1Finished.complete(Unit) }
            },
        )
        val tool2 = FakeTool(
            Tool("t2", "d", buildJsonObject {}),
            executeImpl = { _, _, _ ->
                assertTrue("t2 must not start before t1 finished", t1Finished.isCompleted)
                AgentToolResult(listOf(TextContent("r2")))
            },
        )
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "t1", "{}"), ToolCall("c2", "t2", "{}")),
            assistant("done"),
        )

        val events = mutableListOf<AgentEvent>()
        runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool1, tool2)),
            AgentLoopConfig(model, streamFn = streamFn, toolExecution = ToolExecutionMode.PARALLEL),
        ) { events.add(it) }

        val interesting = events.dropWhile { it !is AgentEvent.ToolExecutionStart }
            .takeWhile { it !is AgentEvent.TurnEnd }
            .map { ev ->
                val id = when (ev) {
                    is AgentEvent.ToolExecutionStart -> ev.toolCallId
                    is AgentEvent.ToolExecutionEnd -> ev.toolCallId
                    is AgentEvent.MessageStart -> (ev.message as? ToolResultMessage)?.toolCallId
                    is AgentEvent.MessageEnd -> (ev.message as? ToolResultMessage)?.toolCallId
                    else -> null
                }
                ev::class.simpleName to id
            }
        assertEquals(
            listOf(
                "ToolExecutionStart" to "c1", "ToolExecutionEnd" to "c1",
                "MessageStart" to "c1", "MessageEnd" to "c1",
                "ToolExecutionStart" to "c2", "ToolExecutionEnd" to "c2",
                "MessageStart" to "c2", "MessageEnd" to "c2",
            ),
            interesting,
        )
    }

    @Test
    fun `updates are emitted in callback order before the end event`() = runTest {
        val tool = FakeTool(
            executeImpl = { _, _, onUpdate ->
                onUpdate(AgentToolResult(listOf(TextContent("u1"))))
                onUpdate(AgentToolResult(listOf(TextContent("u2"))))
                AgentToolResult(listOf(TextContent("done")))
            },
        )
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "my_tool", "{}")),
            assistant("done"),
        )

        val events = mutableListOf<AgentEvent>()
        runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val updates = events.filterIsInstance<AgentEvent.ToolExecutionUpdate>()
        assertEquals(listOf("u1", "u2"), updates.map { (it.partialResult.content.single() as TextContent).text })
        val updateIdx = events.indexOf(updates[0])
        val endIdx = events.indexOfFirst { it is AgentEvent.ToolExecutionEnd }
        val startIdx = events.indexOfFirst { it is AgentEvent.ToolExecutionStart }
        assertTrue("updates after start and before end", startIdx < updateIdx && updateIdx < endIdx)
    }

    @Test
    fun `accepted updates drain before the end event on the error path`() = runTest {
        val tool = FakeTool(
            executeImpl = { _, _, onUpdate ->
                onUpdate(AgentToolResult(listOf(TextContent("partial"))))
                throw IllegalStateException("boom")
            },
        )
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "my_tool", "{}")),
            assistant("done"),
        )

        val events = mutableListOf<AgentEvent>()
        runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val updateIdx = events.indexOfFirst { it is AgentEvent.ToolExecutionUpdate }
        val endIdx = events.indexOfFirst { it is AgentEvent.ToolExecutionEnd }
        assertTrue(updateIdx in 0 until endIdx)
        val end = events[endIdx] as AgentEvent.ToolExecutionEnd
        assertTrue(end.isError)
        assertEquals("boom", (end.result.content.single() as TextContent).text)
    }

    @Test
    fun `updates after tool settlement are ignored`() = runTest {
        lateinit var captured: AgentToolUpdateCallback
        val tool = FakeTool(
            executeImpl = { _, _, onUpdate ->
                captured = onUpdate
                onUpdate(AgentToolResult(listOf(TextContent("accepted"))))
                AgentToolResult(listOf(TextContent("done")))
            },
        )
        val streamFn = scriptedStream(
            toolCallAssistant(ToolCall("c1", "my_tool", "{}")),
            assistant("done"),
        )

        val events = mutableListOf<AgentEvent>()
        runAgentLoop(
            listOf(UserMessage.ofText("q")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(model, streamFn = streamFn),
        ) { events.add(it) }

        val before = events.size
        assertEquals(1, events.count { it is AgentEvent.ToolExecutionUpdate })

        // The callback outlives the invocation; late calls must be dropped.
        captured(AgentToolResult(listOf(TextContent("late"))))
        assertEquals(before, events.size)
    }

    @Test
    fun `cancellation during tool execution propagates without late events`() = runTest {
        val toolEntered = CompletableDeferred<Unit>()
        var toolObservedCancellation = false
        val tool = FakeTool(
            executeImpl = { _, _, _ ->
                toolEntered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    toolObservedCancellation = true
                    throw e
                }
                error("unreachable")
            },
        )
        val streamFn = scriptedStream(toolCallAssistant(ToolCall("c1", "my_tool", "{}")))

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch {
            runAgentLoop(
                listOf(UserMessage.ofText("q")),
                AgentContext(messages = emptyList(), tools = listOf(tool)),
                AgentLoopConfig(model, streamFn = streamFn),
            ) { events.add(it) }
        }
        toolEntered.await()
        job.cancel()
        job.join()

        assertTrue("tool must observe coroutine cancellation", toolObservedCancellation)
        assertTrue(job.isCancelled)
        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd",
                "MessageStart", "MessageEnd",
                "ToolExecutionStart",
            ),
            typeLabels(events),
        )
    }
}
