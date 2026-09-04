package works.resolve.pathfinder.agent

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage

class AgentTest {

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
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = stopReason,
        errorMessage = errorMessage,
        timestamp = 42L
    )

    private fun okStream(): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(text = "")),
        AssistantMessageEvent.TextDelta(0, "he", assistant(text = "he")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant(text = "hello"))
    )

    /** Stream that starts but never terminates; only cancellation ends it. */
    private fun hangingStream(): Flow<AssistantMessageEvent> = flow {
        emit(AssistantMessageEvent.Start(assistant(text = "")))
        awaitCancellation()
    }

    private fun agent(
        streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
        tools: List<AgentTool> = emptyList(),
        streamFn: StreamFn
    ) = Agent(
        model = model,
        systemPrompt = "be brief",
        streamOptions = streamOptions,
        tools = tools,
        streamFn = streamFn
    )

    @Test
    fun `fresh agent exposes default state`() {
        val agent = Agent(model, streamFn = StreamFn { _, _, _ -> okStream() })

        val state = agent.state.value
        // Divergence: pi defaults systemPrompt to ""; this port keeps it nullable
        // to match Context.systemPrompt.
        assertNull(state.systemPrompt)
        assertEquals(model, state.model)
        assertEquals(ModelThinkingLevel.OFF, state.thinkingLevel)
        assertTrue(state.tools.isEmpty())
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isStreaming)
        assertNull(state.streamingMessage)
        assertTrue(state.pendingToolCalls.isEmpty())
        assertNull(state.errorMessage)
    }

    @Test
    fun `constructor system prompt reaches state and setters update it`() {
        val agent =
            Agent(model, "You are a helpful assistant.", SimpleStreamOptions()) { _, _, _ ->
                okStream()
            }

        assertEquals("You are a helpful assistant.", agent.state.value.systemPrompt)
        assertEquals(model, agent.state.value.model)
        assertEquals(ModelThinkingLevel.OFF, agent.state.value.thinkingLevel)

        agent.setThinkingLevel(ModelThinkingLevel.LOW)
        assertEquals(ModelThinkingLevel.LOW, agent.state.value.thinkingLevel)
        agent.setSystemPrompt("changed")
        assertEquals("changed", agent.state.value.systemPrompt)
    }

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

        agent.prompt(listOf(UserMessage.ofText("hi")))
        collector.cancelAndJoin()

        val types = events.map { it::class.simpleName }
        assertEquals(
            listOf(
                "AgentStart", "TurnStart",
                "MessageStart", "MessageEnd", // user
                "MessageStart", "MessageUpdate", "MessageEnd", // assistant
                "TurnEnd", "AgentEnd"
            ),
            types
        )

        val final = agent.state.value
        assertEquals(2, final.messages.size)
        assertEquals(
            "hi",
            ((final.messages[0] as UserMessage).content.single() as TextContent).text
        )
        val reply = final.messages[1] as AssistantMessage
        assertEquals("hello", (reply.content.single() as TextContent).text)
        assertEquals(StopReason.STOP, reply.stopReason)
        assertNull(final.streamingMessage)
        assertFalse(final.isStreaming)
        assertNull(final.errorMessage)

        fun textOf(msg: Message) = when (msg) {
            is UserMessage -> (msg.content.single() as TextContent).text
            is AssistantMessage -> (msg.content.single() as TextContent).text
            else -> "tool-result"
        }
        agent.prompt(listOf(UserMessage.ofText("again")))
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

        agent.prompt(listOf(UserMessage.ofText("hi")))
        val observed = sawAssistantEnd.await()
        assertTrue(observed.messages.any { it is AssistantMessage })
        assertNull(observed.streamingMessage)
        assertTrue(observed.isStreaming)
        collector.cancelAndJoin()
    }

    @Test
    fun `events have no replay, mutators emit nothing, and cancelled collectors stop receiving`() =
        runTest {
            val agent = agent(streamFn = StreamFn { _, _, _ -> okStream() })

            val received = CopyOnWriteArrayList<AgentEvent>()
            val collector = launch { agent.events.collect { received.add(it) } }
            yield() // subscribe before asserting
            assertTrue("no initial event on subscribe", received.isEmpty())

            // State mutators do not emit events.
            agent.setSystemPrompt("mutated")
            agent.setTools(emptyList())
            assertTrue(received.isEmpty())

            agent.prompt(listOf(UserMessage.ofText("hi")))
            assertTrue(received.isNotEmpty())
            val countAfterFirstRun = received.size

            collector.cancelAndJoin()
            agent.prompt(listOf(UserMessage.ofText("again")))
            assertEquals(countAfterFirstRun, received.size)
            // Unsubscribed observers do not affect reduction.
            assertEquals(4, agent.state.value.messages.size)
        }

    @Test
    fun `replace and reset transcript copy caller lists while idle`() = runTest {
        // Gate on provider start, not merely isStreaming, so the run is
        // deterministically established.
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

        val job = launch { agent.prompt(listOf(UserMessage.ofText("hi"))) }
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
        }
        providerStarted.await()
        job.cancelAndJoin()
        assertEquals(2, agent.state.value.messages.size)
        assertEquals(
            StopReason.ABORTED,
            (agent.state.value.messages[1] as AssistantMessage).stopReason
        )
    }

    @Test
    fun `concurrent prompt is rejected`() = runTest {
        val providerStarted = CompletableDeferred<Unit>()
        val agent = Agent(model, null, SimpleStreamOptions()) { _, _, _ ->
            providerStarted.complete(Unit)
            hangingStream()
        }
        val job = launch { agent.prompt(listOf(UserMessage.ofText("first"))) }
        providerStarted.await()
        agent.state.first { it.isStreaming }

        try {
            agent.prompt(listOf(UserMessage.ofText("second")))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already processing"))
        }

        agent.abort()
        job.join()
        assertFalse(agent.state.value.isStreaming)
    }

    @Test
    fun `continueRun while a prompt is streaming is rejected`() = runTest {
        val providerStarted = CompletableDeferred<Unit>()
        val agent = Agent(model, null, SimpleStreamOptions()) { _, _, _ ->
            providerStarted.complete(Unit)
            hangingStream()
        }
        val job = launch { agent.prompt(listOf(UserMessage.ofText("first"))) }
        providerStarted.await()
        agent.state.first { it.isStreaming }

        try {
            agent.continueRun()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already processing"))
        }

        agent.abort()
        job.join()
        assertFalse(agent.state.value.isStreaming)
    }

    @Test
    fun `continueRun streams a follow-up assistant from the committed transcript`() = runTest {
        val contexts = CopyOnWriteArrayList<List<Message>>()
        val agent = agent(
            streamFn = StreamFn { _, context, _ ->
                contexts.add(context.messages)
                okStream()
            }
        )
        // Seeded like pi's continue(): the committed tail is a user message.
        agent.replaceTranscript(listOf(UserMessage.ofText("hi")))

        agent.continueRun()

        // The continuation adds no prompt messages: the provider sees exactly
        // the committed transcript.
        fun textOf(msg: Message) = when (msg) {
            is UserMessage -> (msg.content.single() as TextContent).text
            is AssistantMessage -> (msg.content.single() as TextContent).text
            else -> "tool-result"
        }
        assertEquals(listOf(listOf("hi")), contexts.map { ctx -> ctx.map(::textOf) })
        val final = agent.state.value
        assertEquals(2, final.messages.size)
        val reply = final.messages[1] as AssistantMessage
        assertEquals("hello", (reply.content.single() as TextContent).text)
        assertFalse(final.isStreaming)
    }

    @Test
    fun `continueRun on an empty transcript is rejected`() = runTest {
        var streams = 0
        val agent = agent(
            streamFn = StreamFn { _, _, _ ->
                streams++
                okStream()
            }
        )

        try {
            agent.continueRun()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("No messages to continue from", e.message)
        }
        assertEquals(0, streams)
        assertTrue(agent.state.value.messages.isEmpty())
        assertFalse(agent.state.value.isStreaming)
    }

    @Test
    fun `continueRun from an assistant tail without queued messages is rejected`() = runTest {
        var streams = 0
        val agent = agent(
            streamFn = StreamFn { _, _, _ ->
                streams++
                okStream()
            }
        )
        // Upstream drains steer()/followUp() queues from an assistant tail;
        // those queues are unported, so the tail is never continuable.
        agent.replaceTranscript(listOf(UserMessage.ofText("hi"), assistant()))

        try {
            agent.continueRun()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Cannot continue from message role: assistant", e.message)
        }
        assertEquals(0, streams)
        assertEquals(2, agent.state.value.messages.size)
        assertFalse(agent.state.value.isStreaming)
    }

    @Test
    fun `continueRun from a tool-result tail streams a follow-up assistant`() = runTest {
        val contexts = CopyOnWriteArrayList<List<Message>>()
        val agent = agent(
            streamFn = StreamFn { _, context, _ ->
                contexts.add(context.messages)
                okStream()
            }
        )
        agent.replaceTranscript(
            listOf(
                UserMessage.ofText("weather?"),
                assistant(text = "", stopReason = StopReason.TOOL_USE),
                toolResult("call-1")
            )
        )

        agent.continueRun()

        // The continuation adds no prompt messages: the provider sees exactly
        // the committed transcript.
        assertEquals(1, contexts.size)
        assertEquals(3, contexts.single().size)
        val final = agent.state.value
        assertEquals(4, final.messages.size)
        assertTrue(final.messages.last() is AssistantMessage)
        assertFalse(final.isStreaming)
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

        val deferred = async { agent.prompt(listOf(UserMessage.ofText("hi"))) }
        providerStarted.await()

        agent.abort()

        try {
            withTimeout(1_000) { deferred.await() }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
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
        val job = launch { agent.prompt(listOf(UserMessage.ofText("hi"))) }
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
            streamOptions = SimpleStreamOptions(apiKey = "sk-supersecret")
        )

        val failureTypes = mutableListOf<String>()
        val collector = launch { agent.events.collect { failureTypes.add(it::class.simpleName!!) } }
        yield() // subscribe before the run starts

        // Ordinary failures resolve normally rather than throwing.
        agent.prompt(listOf(UserMessage.ofText("hi")))
        collector.cancelAndJoin()

        assertEquals(
            listOf(
                "AgentStart",
                "TurnStart",
                "MessageStart",
                "MessageEnd",
                "MessageStart",
                "MessageEnd",
                "TurnEnd",
                "AgentEnd"
            ),
            failureTypes
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
        val error =
            assistant(text = "", stopReason = StopReason.ERROR, errorMessage = "500 upstream")
        val agent = agent(
            streamFn = StreamFn { _, _, _ ->
                call++
                if (call ==
                    1
                ) {
                    flowOf(AssistantMessageEvent.Error(StopReason.ERROR, error))
                } else {
                    okStream()
                }
            }
        )

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertEquals("500 upstream", agent.state.value.errorMessage)

        agent.prompt(listOf(UserMessage.ofText("again")))
        assertNull(agent.state.value.errorMessage)
        assertEquals(4, agent.state.value.messages.size)
    }

    @Test
    fun `abort while idle is a no-op`() = runTest {
        val agent = agent(streamFn = StreamFn { _, _, _ -> okStream() })
        agent.abort() // must not throw
        assertTrue(agent.state.value.messages.isEmpty())
    }

    @Test
    fun `sessionId configured on stream options reaches the stream function`() = runTest {
        val receivedSessionIds = CopyOnWriteArrayList<String?>()
        val agent = agent(
            streamOptions = SimpleStreamOptions(sessionId = "session-abc"),
            streamFn = StreamFn { _, _, options ->
                receivedSessionIds.add(options.sessionId)
                okStream()
            }
        )

        agent.prompt(listOf(UserMessage.ofText("hello")))
        assertEquals(listOf("session-abc"), receivedSessionIds)
    }

    private fun fakeTool(name: String): AgentTool = object : AgentTool {
        override val definition = Tool(name, "fake $name", JsonPrimitive("object"))
        override val label = name
        override fun validateArguments(arguments: JsonObject) = arguments
        override suspend fun execute(
            toolCallId: String,
            arguments: JsonObject,
            onUpdate: AgentToolUpdateCallback
        ) = AgentToolResult(content = listOf(TextContent("done")))
    }

    private fun toolResult(id: String) = ToolResultMessage(
        toolCallId = id,
        toolName = "get_weather",
        content = listOf(TextContent("sunny")),
        timestamp = 43L
    )

    @Test
    fun `state holds configured tools as a copied snapshot`() {
        val tools = mutableListOf(fakeTool("a"))
        val agent = agent(streamFn = StreamFn { _, _, _ -> okStream() }, tools = tools)
        assertEquals(1, agent.state.value.tools.size)
        tools.add(fakeTool("b"))
        assertEquals(1, agent.state.value.tools.size)
    }

    @Test
    fun `pendingToolCalls populate before start observers and clear before end observers`() =
        runTest {
            val agent = agent(streamFn = StreamFn { _, _, _ -> okStream() })
            val observed = CompletableDeferred<String>()
            val collector = launch {
                agent.events.collect { event ->
                    when (event) {
                        is AgentEvent.ToolExecutionStart ->
                            observed.complete(agent.state.value.pendingToolCalls.single())

                        is AgentEvent.ToolExecutionEnd -> {
                            assertTrue(event.toolCallId !in agent.state.value.pendingToolCalls)
                        }

                        else -> Unit
                    }
                }
            }
            yield() // subscribe before driving the reduction

            agent.processEvent(
                AgentEvent.ToolExecutionStart("call-1", "get_weather", JsonObject(emptyMap()))
            )
            assertEquals("call-1", observed.await())
            agent.processEvent(
                AgentEvent.ToolExecutionEnd(
                    "call-1",
                    "get_weather",
                    AgentToolResult(content = listOf(TextContent("sunny"))),
                    isError = false
                )
            )
            assertTrue(agent.state.value.pendingToolCalls.isEmpty())
            collector.cancelAndJoin()
        }

    @Test
    fun `parallel tool completion cannot lose pending ids`() = runTest {
        val agent = agent(streamFn = StreamFn { _, _, _ -> okStream() })
        coroutineScope {
            repeat(50) { i ->
                launch {
                    agent.processEvent(
                        AgentEvent.ToolExecutionStart("id-$i", "t", JsonObject(emptyMap()))
                    )
                }
            }
        }
        assertEquals(50, agent.state.value.pendingToolCalls.size)
        coroutineScope {
            repeat(50) { i ->
                launch {
                    agent.processEvent(
                        AgentEvent.ToolExecutionEnd(
                            "id-$i",
                            "t",
                            AgentToolResult(content = emptyList()),
                            isError = false
                        )
                    )
                }
            }
        }
        assertTrue(agent.state.value.pendingToolCalls.isEmpty())
    }

    @Test
    fun `tool result message_end commits exactly once and clears streaming`() = runTest {
        val agent = agent(streamFn = StreamFn { _, _, _ -> okStream() })
        val result = toolResult("call-1")
        agent.processEvent(AgentEvent.MessageStart(result))
        assertEquals(result, agent.state.value.streamingMessage)
        agent.processEvent(AgentEvent.MessageEnd(result))
        val messages = agent.state.value.messages
        assertEquals(1, messages.size)
        assertEquals(result, messages.single())
        assertNull(agent.state.value.streamingMessage)
    }

    @Test
    fun `run end clears pending tool calls and streaming message on abort`() = runTest {
        val providerStarted = CompletableDeferred<Unit>()
        val agent = Agent(model, null, SimpleStreamOptions()) { _, _, _ ->
            providerStarted.complete(Unit)
            hangingStream()
        }
        val job = launch { agent.prompt(listOf(UserMessage.ofText("hi"))) }
        providerStarted.await()
        // Seed runtime-owned state mid-run, as parallel tool events would.
        agent.processEvent(AgentEvent.ToolExecutionStart("call-1", "t", JsonObject(emptyMap())))
        agent.abort()
        job.join()
        val final = agent.state.value
        assertTrue(final.pendingToolCalls.isEmpty())
        assertNull(final.streamingMessage)
        assertFalse(final.isStreaming)
    }

    @Test
    fun `successful run end clears seeded pending tool calls`() = runTest {
        val agent = agent(streamFn = StreamFn { _, _, _ -> okStream() })
        agent.processEvent(AgentEvent.ToolExecutionStart("call-1", "t", JsonObject(emptyMap())))
        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertTrue(agent.state.value.pendingToolCalls.isEmpty())
        assertNull(agent.state.value.streamingMessage)
    }

    @Test
    fun `full tool run persists user toolCall toolResult and follow-up assistant in source order`() =
        runTest {
            val tool = fakeTool("get_weather")
            val toolUse = assistant(text = "", stopReason = StopReason.TOOL_USE).copy(
                content = listOf(ToolCall("call-1", "get_weather", "{}"))
            )
            var call = 0
            val agent = agent(
                tools = listOf(tool),
                streamFn = StreamFn { _, _, _ ->
                    call++
                    if (call == 1) {
                        flowOf(AssistantMessageEvent.Done(StopReason.TOOL_USE, toolUse))
                    } else {
                        flowOf(
                            AssistantMessageEvent.Start(assistant(text = "")),
                            AssistantMessageEvent.Done(
                                StopReason.STOP,
                                assistant(text = "It is sunny")
                            )
                        )
                    }
                }
            )

            val pendingAtAgentEnd = CompletableDeferred<Set<String>>()
            val events = mutableListOf<AgentEvent>()
            val collector = launch {
                agent.events.collect { event ->
                    events.add(event)
                    if (event is AgentEvent.AgentEnd) {
                        pendingAtAgentEnd.complete(agent.state.value.pendingToolCalls)
                    }
                }
            }
            yield() // subscribe before the run starts

            agent.prompt(listOf(UserMessage.ofText("weather?")))
            collector.cancelAndJoin()

            val final = agent.state.value
            assertEquals(4, final.messages.size)
            assertTrue(final.messages[0] is UserMessage)
            val toolCallMessage = final.messages[1] as AssistantMessage
            assertEquals(StopReason.TOOL_USE, toolCallMessage.stopReason)
            assertEquals("call-1", (toolCallMessage.content.single() as ToolCall).id)
            val toolResult = final.messages[2] as ToolResultMessage
            assertEquals("call-1", toolResult.toolCallId)
            assertEquals("get_weather", toolResult.toolName)
            val followUp = final.messages[3] as AssistantMessage
            assertEquals("It is sunny", (followUp.content.single() as TextContent).text)
            assertNull(final.streamingMessage)
            assertFalse(final.isStreaming)

            assertTrue(pendingAtAgentEnd.await().isEmpty())
            val toolStarts = events.filterIsInstance<AgentEvent.ToolExecutionStart>()
            val toolEnds = events.filterIsInstance<AgentEvent.ToolExecutionEnd>()
            assertEquals(listOf("call-1"), toolStarts.map { it.toolCallId })
            assertEquals(listOf("call-1"), toolEnds.map { it.toolCallId })
        }

    @Test
    fun `tool updates after the run settles are ignored`() = runTest {
        lateinit var delayedUpdate: AgentToolUpdateCallback
        val tool = object : AgentTool {
            override val definition =
                Tool("delayed_tool", "captures progress callbacks", JsonPrimitive("object"))
            override val label = "delayed_tool"
            override fun validateArguments(arguments: JsonObject) = arguments
            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback
            ): AgentToolResult {
                delayedUpdate = onUpdate
                onUpdate(AgentToolResult(content = listOf(TextContent("running"))))
                return AgentToolResult(content = listOf(TextContent("ok")))
            }
        }
        val toolUse = assistant(text = "", stopReason = StopReason.TOOL_USE).copy(
            content = listOf(ToolCall("call-1", "delayed_tool", "{}"))
        )
        var call = 0
        val agent = agent(
            tools = listOf(tool),
            streamFn = StreamFn { _, _, _ ->
                call++
                if (call ==
                    1
                ) {
                    flowOf(AssistantMessageEvent.Done(StopReason.TOOL_USE, toolUse))
                } else {
                    okStream()
                }
            }
        )

        val events = CopyOnWriteArrayList<AgentEvent>()
        val collector = launch { agent.events.collect { events.add(it) } }
        yield() // subscribe before the run starts

        agent.prompt(listOf(UserMessage.ofText("run tool")))
        yield() // let the collector drain the run's tail
        val countAfterPrompt = events.size
        assertEquals(1, events.count { it is AgentEvent.ToolExecutionUpdate })

        // The callback outlives the invocation; late calls must be dropped
        // without throwing.
        delayedUpdate(AgentToolResult(content = listOf(TextContent("late"))))
        yield()
        assertEquals(countAfterPrompt, events.size)
        collector.cancelAndJoin()
    }

    @Test
    fun `settled parallel tool update is ignored while another tool still runs`() = runTest {
        val slowStarted = CompletableDeferred<Unit>()
        val settledEnded = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        lateinit var settledUpdate: AgentToolUpdateCallback
        val settledTool = object : AgentTool {
            override val definition =
                Tool("settled_tool", "settles immediately", JsonPrimitive("object"))
            override val label = "settled_tool"
            override fun validateArguments(arguments: JsonObject) = arguments
            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback
            ): AgentToolResult {
                settledUpdate = onUpdate
                return AgentToolResult(content = listOf(TextContent("done")))
            }
        }
        val slowTool = object : AgentTool {
            override val definition =
                Tool("slow_tool", "keeps the run active", JsonPrimitive("object"))
            override val label = "slow_tool"
            override fun validateArguments(arguments: JsonObject) = arguments
            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback
            ): AgentToolResult {
                slowStarted.complete(Unit)
                releaseSlow.await()
                return AgentToolResult(content = listOf(TextContent("done")))
            }
        }
        val toolUse = assistant(text = "", stopReason = StopReason.TOOL_USE).copy(
            content = listOf(
                ToolCall("call-1", "settled_tool", "{}"),
                ToolCall("call-2", "slow_tool", "{}")
            )
        )
        var call = 0
        val agent = agent(
            tools = listOf(settledTool, slowTool),
            streamFn = StreamFn { _, _, _ ->
                call++
                if (call ==
                    1
                ) {
                    flowOf(AssistantMessageEvent.Done(StopReason.TOOL_USE, toolUse))
                } else {
                    okStream()
                }
            }
        )

        val events = CopyOnWriteArrayList<AgentEvent>()
        val collector = launch {
            agent.events.collect { event ->
                events.add(event)
                if (event is AgentEvent.ToolExecutionEnd && event.toolCallId == "call-1") {
                    settledEnded.complete(Unit)
                }
            }
        }
        yield() // subscribe before the run starts

        val job = launch { agent.prompt(listOf(UserMessage.ofText("run tools"))) }
        slowStarted.await()
        settledEnded.await()
        val countBeforeLateUpdate = events.size

        // The settled tool's late update must be dropped even though the run
        // is still active through the slow tool.
        settledUpdate(AgentToolResult(content = listOf(TextContent("late"))))
        yield()
        assertEquals(countBeforeLateUpdate, events.size)

        releaseSlow.complete(Unit)
        job.join()
        assertEquals(0, events.count { it is AgentEvent.ToolExecutionUpdate })
        assertEquals(
            listOf("call-1", "call-2"),
            events.filterIsInstance<AgentEvent.ToolExecutionEnd>().map { it.toolCallId }
        )
        collector.cancelAndJoin()
    }
}
