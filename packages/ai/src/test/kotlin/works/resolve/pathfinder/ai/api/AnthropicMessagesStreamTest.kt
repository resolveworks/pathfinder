package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.AnthropicAllowedFallbackModel
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCost
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ThinkingLevel
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.SimpleToolChoice
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.utils.ProviderRetry
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicMessagesStreamTest {

    private val claude = Model(
        id = "claude-sonnet-4-5",
        name = "Claude Sonnet 4.5",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "https://api.anthropic.com",
        reasoning = true,
        input = listOf(InputModality.TEXT, InputModality.IMAGE),
        cost = ModelCost(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
        contextWindow = 200_000,
        maxTokens = 64_000,
    )

    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun api(transport: FakeTransport) = AnthropicMessagesApi(
        transport,

ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }),
clock = FakeClock(1_770_000_000_000L),
    )

    private fun messageStart(
        input: Int = 12,
        output: Int = 0,
        cacheRead: Int = 0,
        cacheWrite: Int = 0,
        model: String = "claude-sonnet-4-5",
    ) = "message_start" to
        """{"type":"message_start","message":{"id":"msg_test","model":"$model","usage":{"input_tokens":$input,"output_tokens":$output,"cache_read_input_tokens":$cacheRead,"cache_creation_input_tokens":$cacheWrite}}}"""

    private fun messageDelta(
        stopReason: String? = "end_turn",
        input: Int? = null,
        output: Int? = null,
        cacheRead: Int? = null,
        cacheWrite: Int? = null,
        thinkingTokens: Int? = null,
        stopDetails: String? = null,
    ): Pair<String, String> {
        val usageFields = listOfNotNull(
            input?.let { """"input_tokens":$it""" },
            output?.let { """"output_tokens":$it""" },
            cacheRead?.let { """"cache_read_input_tokens":$it""" },
            cacheWrite?.let { """"cache_creation_input_tokens":$it""" },
            thinkingTokens?.let { """"output_tokens_details":{"thinking_tokens":$it}""" },
        ).joinToString(",")
        val details = stopDetails?.let { ""","stop_details":$it""" } ?: ""
        val stop = stopReason?.let { "\"$it\"" } ?: "null"
        val usageJson = if (usageFields.isEmpty()) "" else ",\"usage\":{$usageFields}"
        return "message_delta" to
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":$stop$details}$usageJson}"
    }

    private val messageStop = "message_stop" to """{"type":"message_stop"}"""

    private var realCatalog: ProviderCatalog? = null

    /** The generated asset, mirroring ProviderCatalogTest's realAsset(). */
    private fun realAsset(): ProviderCatalog {
        val file = File("src/main/assets/models-catalog.json")
        assumeTrue("real catalog asset not found at ${file.absolutePath}", file.isFile)
        var cached = realCatalog
        if (cached == null) {
            cached = ProviderCatalog.parse(file.readText())
            realCatalog = cached
        }
        return cached
    }

    private fun textStream(vararg deltas: String) = listOf(
        messageStart(),
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        *deltas.map {
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$it"}}"""
        }.toTypedArray(),
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        messageDelta(output = 5),
        messageStop,
    )

    @Test
    fun `streams text with start delta end done`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("Hel", "lo"))
        val events = api(transport)
            .stream(claude, context, AnthropicMessagesOptions(apiKey = "test-key"))
            .toList()

        assertIs<AssistantMessageEvent.Start>(events.first())
        val deltas = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("Hel", "lo"), deltas.map { it.delta })
        assertIs<AssistantMessageEvent.TextEnd>(events.filter { it is AssistantMessageEvent.TextEnd }.single())
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        assertEquals("Hello", assertIs<TextContent>(done.message.content.single()).text)
        assertEquals("msg_test", done.message.responseId)
        assertEquals("end_turn", done.message.rawStopReason)
    }

    @Test
    fun `streamSimple applies custom thinking budgets and clamps xhigh`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("hi"))
        api(transport).streamSimple(
            claude,
            context,
            SimpleStreamOptions(
                apiKey = "k",
                reasoning = ThinkingLevel.MEDIUM,
                thinkingBudgets = mapOf(ThinkingLevel.MEDIUM to 4096),
            ),
        ).toList()

        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(64_000, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(4096, body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())

        // xhigh clamps to the high budget.
        val xhigh = FakeTransport()
        xhigh.enqueueNamedResponse(textStream("hi"))
        api(xhigh).streamSimple(
            claude,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = ThinkingLevel.XHIGH),
        ).toList()
        val xhighBody = Json.parseToJsonElement(xhigh.requests.single().body.decodeToString()).jsonObject
        assertEquals(16_384, xhighBody["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `usage from message_start survives message_delta omission and cost uses rates`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(input = 100, cacheRead = 40, cacheWrite = 10),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(output = 7, thinkingTokens = 3),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        val usage = done.message.usage
        assertEquals(100, usage.input)
        assertEquals(7, usage.output)
        assertEquals(40, usage.cacheRead)
        assertEquals(10, usage.cacheWrite)
        assertEquals(3, usage.reasoning)
        assertEquals(100 + 7 + 40 + 10, usage.totalTokens)
        assertEquals(100 * 3.0 / 1_000_000, usage.cost.input, 1e-12)
        assertEquals(40 * 0.3 / 1_000_000, usage.cost.cacheRead, 1e-12)
        assertEquals(10 * 3.75 / 1_000_000, usage.cost.cacheWrite, 1e-12)
    }

    @Test
    fun `message_delta overrides non-null usage fields`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(input = 100, output = 1),
            messageDelta(input = 120, output = 9, cacheRead = 2, cacheWrite = 3),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertEquals(120, done.message.usage.input)
        assertEquals(9, done.message.usage.output)
        assertEquals(2, done.message.usage.cacheRead)
        assertEquals(3, done.message.usage.cacheWrite)
        assertEquals(134, done.message.usage.totalTokens)
    }

    @Test
    fun `thinking streams with signature deltas and redacted blocks`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"thin"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"king"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(output = 20),
            messageStop,
        )
        val events = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList()
        val deltas = events.filterIsInstance<AssistantMessageEvent.ThinkingDelta>()
        assertEquals(listOf("thin", "king"), deltas.map { it.delta })
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val thinking = assertIs<ThinkingContent>(done.message.content.single())
        assertEquals("thinking", thinking.thinking)
        assertEquals("sig-1", thinking.thinkingSignature)
        assertFalse(thinking.redacted)

        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"opaque"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(output = 1),
            messageStop,
        )
        val done2 = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        val redacted = assertIs<ThinkingContent>(done2.message.content.single())
        assertEquals("[Reasoning redacted]", redacted.thinking)
        assertEquals("opaque", redacted.thinkingSignature)
        assertTrue(redacted.redacted)
    }

    @Test
    fun `interleaved tool and thinking blocks route by upstream index`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"edit"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"pa"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"th\":1}"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"s"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(stopReason = "tool_use"),
            messageStop,
        )
        val events = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.TOOL_USE, done.reason)
        val thinking = assertIs<ThinkingContent>(done.message.content[0])
        assertEquals("hmm", thinking.thinking)
        assertEquals("s", thinking.thinkingSignature)
        val call = assertIs<ToolCall>(done.message.content[1])
        assertEquals("toolu_1", call.id)
        assertEquals("edit", call.name)
        assertEquals("""{"path":1}""", call.arguments)
        val toolEnd = events.filterIsInstance<AssistantMessageEvent.ToolCallEnd>().single()
        assertEquals(1, toolEnd.contentIndex)
        assertEquals("""{"path":1}""", toolEnd.toolCall.arguments)
    }

    @Test
    fun `blank tool input buffer finalizes as empty object`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_2","name":"noop"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(stopReason = "tool_use"),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertEquals("{}", assertIs<ToolCall>(done.message.content.single()).arguments)
    }

    @Test
    fun `oauth tool_use names round-trip through fromClaudeCodeName`() = runTest {
        val tool = Tool(
            name = "read",
            description = "reads a file",
            parameters = Json.parseToJsonElement("{}"),
        )
        val toolContext = Context(
            messages = listOf(UserMessage.ofText("hi")),
            tools = listOf(tool),
        )
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_3","name":"Read","input":{"path":"a.txt"}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"pa"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"th\":2}"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(stopReason = "tool_use"),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport)
                .stream(claude, toolContext, AnthropicMessagesOptions(apiKey = "sk-ant-oat-abc"))
                .toList()
                .last(),
        )
        val call = assertIs<ToolCall>(done.message.content.single())
        assertEquals("read", call.name)
        // Streamed partial JSON wins over the content_block_start seed.
        assertEquals("""{"path":2}""", call.arguments)
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(
            "Read",
            body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `tool arguments are seeded from content_block_start input`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_4","name":"noop","input":{"path":"a.txt"}}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(stopReason = "tool_use"),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertEquals(
            """{"path":"a.txt"}""",
            assertIs<ToolCall>(done.message.content.single()).arguments,
        )
    }

    @Test
    fun `non-oauth tool_use names pass through unmapped`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_5","name":"Read"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(stopReason = "tool_use"),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertEquals("Read", assertIs<ToolCall>(done.message.content.single()).name)
    }

    /**
     * Ports pi's anthropic-tool-name-normalization: the Claude-Code lookup
     * is case-insensitive matching of CC tool names only — pi's old
     * find→Glob *name mapping* broke the round-trip because no context tool
     * is named "glob". Upstream is live-API gated; the wire round-trip runs
     * here against the fake transport instead.
     */
    @Test
    fun `claude code name normalization is a case-insensitive lookup, never a name mapping`() = runTest {
        assertEquals("TodoWrite", toClaudeCodeName("todowrite"))
        // "find" is not a Claude Code tool name: it must pass through unmapped.
        assertEquals("find", toClaudeCodeName("find"))
        val tools = listOf(
            Tool("todowrite", "Write a todo item.", Json.parseToJsonElement("{}")),
            Tool("find", "Find files by pattern.", Json.parseToJsonElement("{}")),
            Tool("my_custom_tool", "A custom tool.", Json.parseToJsonElement("{}")),
        )
        assertEquals("todowrite", fromClaudeCodeName("TodoWrite", tools))
        // Unmatched names pass through unchanged in both directions.
        assertEquals("Glob", fromClaudeCodeName("Glob", tools))
        assertEquals("my_custom_tool", fromClaudeCodeName("my_custom_tool", tools))

        val toolContext = Context(messages = listOf(UserMessage.ofText("hi")), tools = tools)
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_a","name":"TodoWrite"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_b","name":"find"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            messageDelta(stopReason = "tool_use"),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport)
                .stream(claude, toolContext, AnthropicMessagesOptions(apiKey = "sk-ant-oat-abc"))
                .toList()
                .last(),
        )
        // Inbound calls come back with the tools' original casing.
        assertEquals(
            listOf("todowrite", "find"),
            done.message.content.map { assertIs<ToolCall>(it).name },
        )
        // Outbound tool definitions carry the Claude Code casing where one matches.
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(
            listOf("TodoWrite", "find", "my_custom_tool"),
            body["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `stop reason mapping covers length refusal pause and sensitive`() = runTest {
        val transport = FakeTransport()

        transport.enqueueNamedResponse(messageStart(), messageDelta(stopReason = "max_tokens"), messageStop)
        var last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        assertEquals(StopReason.LENGTH, assertIs<AssistantMessageEvent.Done>(last).reason)

        transport.enqueueNamedResponse(
            messageStart(),
            messageDelta(
                stopReason = "refusal",
                stopDetails = """{"explanation":"cannot help with that"}""",
            ),
            messageStop,
        )
        last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        val error = assertIs<AssistantMessageEvent.Error>(last)
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals("cannot help with that", error.error.errorMessage)
        assertEquals("refusal", error.error.rawStopReason)

        transport.enqueueNamedResponse(messageStart(), messageDelta(stopReason = "pause_turn"), messageStop)
        last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        assertEquals(StopReason.STOP, assertIs<AssistantMessageEvent.Done>(last).reason)

        transport.enqueueNamedResponse(messageStart(), messageDelta(stopReason = "sensitive"), messageStop)
        last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        assertEquals(
            "Provider stopped with: sensitive",
            assertIs<AssistantMessageEvent.Error>(last).error.errorMessage,
        )

        transport.enqueueNamedResponse(messageStart(), messageDelta(stopReason = "brand_new_reason"), messageStop)
        last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        assertTrue(
            "Unhandled stop reason: brand_new_reason" in
                (assertIs<AssistantMessageEvent.Error>(last).error.errorMessage ?: ""),
        )
    }

    @Test
    fun `stream ending before message_stop is an error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
            messageDelta(output = 1),
        )
        val last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        val error = assertIs<AssistantMessageEvent.Error>(last)
        assertTrue("ended before message_stop" in (error.error.errorMessage ?: ""))
        assertEquals("partial", assertIs<TextContent>(error.error.content.single()).text)
    }

    @Test
    fun `stream without a stop reason is an error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(messageStart(), messageDelta(stopReason = null), messageStop)
        val last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        assertTrue(
            "without a stop reason" in (assertIs<AssistantMessageEvent.Error>(last).error.errorMessage ?: ""),
        )
    }

    @Test
    fun `error sse event surfaces its data as the error message`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )
        val last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        val error = assertIs<AssistantMessageEvent.Error>(last)
        assertTrue("Overloaded" in (error.error.errorMessage ?: ""))
    }

    @Test
    fun `non-message sse events are ignored`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(*(listOf(messageStart(), "ping" to """{}""") + textStream("x").drop(1)).toTypedArray())
        val last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        assertIs<AssistantMessageEvent.Done>(last)
    }

    /** Ports anthropic-sse-parsing: content_block_start may carry content. */
    @Test
    fun `preserves content from content_block_start events`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Initial text"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" plus delta"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":"Initial thinking","signature":"initial signature"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":" plus delta"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"signature_delta","signature":" plus delta"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            messageDelta(output = 5),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertEquals("Initial text plus delta", assertIs<TextContent>(done.message.content[0]).text)
        val thinking = assertIs<ThinkingContent>(done.message.content[1])
        assertEquals("Initial thinking plus delta", thinking.thinking)
        assertEquals("initial signature plus delta", thinking.thinkingSignature)
    }

    /** Ports anthropic-sse-parsing: message_delta without usage is a no-op. */
    @Test
    fun `message_delta without usage is a no-op for usage accumulation`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(input = 12),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(stopReason = "end_turn"),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertEquals(StopReason.STOP, done.reason)
        assertNull(done.message.errorMessage)
        assertEquals("Hello", assertIs<TextContent>(done.message.content.single()).text)
        assertEquals(12, done.message.usage.input)
        assertEquals(12, done.message.usage.totalTokens)
    }

    /** Ports anthropic-sse-parsing: unknown events after message_stop are ignored. */
    @Test
    fun `ignores unknown sse events after message_stop`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            *(textStream("Hello") + listOf("done" to "[DONE]", "proxy.stats" to "not json")).toTypedArray(),
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertEquals(StopReason.STOP, done.reason)
        assertNull(done.message.errorMessage)
        assertEquals("Hello", assertIs<TextContent>(done.message.content.single()).text)
    }

    @Test
    fun `malformed sse json is a protocol error with partial content`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","te""",
        )
        val last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        val error = assertIs<AssistantMessageEvent.Error>(last)
        assertTrue("Could not parse Anthropic SSE event" in (error.error.errorMessage ?: ""))
    }

    @Test
    fun `non-2xx response produces error event with whole body`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(401, """{"type":"error","error":{"type":"auth_error","message":"Invalid API key"}}""")
        val last = api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last()
        val error = assertIs<AssistantMessageEvent.Error>(last)
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals(
            """401: {"type":"error","error":{"type":"auth_error","message":"Invalid API key"}}""",
            error.error.errorMessage,
        )
    }

    @Test
    fun `missing api key produces error event without a request`() = runTest {
        val transport = FakeTransport()
        val events = api(transport).stream(claude, context, AnthropicMessagesOptions()).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue("No API key" in (error.error.errorMessage ?: ""))
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `authorization or x-api-key header auth stands in for an api key`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .stream(claude, context, AnthropicMessagesOptions(headers = mapOf("authorization" to "Bearer gateway")))
            .toList()
        assertEquals(1, transport.requests.size)
        // The merged explicit x-api-key wins over the empty internal one.
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .stream(
                claude,
                context,
                AnthropicMessagesOptions(headers = mapOf("x-api-key" to "gateway-key")),
            )
            .toList()
        assertEquals("gateway-key", transport.requests.last().headers["x-api-key"])
        assertNull(transport.requests.last().bearerToken)
    }

    @Test
    fun `api key request carries x-api-key version and beta headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "test-key")).toList()
        val request = transport.requests.single()
        // The beta-namespace endpoint carries ?beta=true unconditionally.
        assertEquals("https://api.anthropic.com/v1/messages?beta=true", request.url)
        assertNull(request.bearerToken)
        assertEquals("test-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("application/json", request.headers["accept"])
        // Divergence (owner decision): pi's browser-CORS header is not sent.
        assertNull(request.headers["anthropic-dangerous-direct-browser-access"])
        // No tools and thinking not enabled: no beta features at all.
        assertNull(request.headers["anthropic-beta"])
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
        assertNull(body["betas"])
    }

    /** Ports anthropic-sse-parsing: interleaved thinking needs thinkingEnabled. */
    @Test
    fun `interleaved thinking beta requires thinking to be enabled`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .stream(claude, context, AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true))
            .toList()
        assertEquals(
            "interleaved-thinking-2025-05-14",
            transport.requests.single().headers["anthropic-beta"],
        )

        // Explicitly disabled: still omitted.
        val disabled = FakeTransport()
        disabled.enqueueNamedResponse(textStream("ok"))
        api(disabled)
            .stream(claude, context, AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = false))
            .toList()
        assertNull(disabled.requests.single().headers["anthropic-beta"])
    }

    @Test
    fun `tools without eager streaming add the fine-grained beta`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val tools = listOf(
            Tool("edit", "Edit.", Json.parseToJsonElement("""{"type":"object"}""")),
        )
        val legacy = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(supportsEagerToolInputStreaming = false),
        )
        api(transport)
            .stream(
                legacy,
                Context(messages = listOf(UserMessage.ofText("hi")), tools = tools),
                AnthropicMessagesOptions(apiKey = "k"),
            )
            .toList()
        // Thinking not enabled: no interleaved beta.
        assertEquals(
            "fine-grained-tool-streaming-2025-05-14",
            transport.requests.single().headers["anthropic-beta"],
        )

        val thinking = FakeTransport()
        thinking.enqueueNamedResponse(textStream("ok"))
        api(thinking)
            .stream(
                legacy,
                Context(messages = listOf(UserMessage.ofText("hi")), tools = tools),
                AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true),
            )
            .toList()
        assertEquals(
            "fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14",
            thinking.requests.single().headers["anthropic-beta"],
        )
    }

    @Test
    fun `adaptive thinking models skip the interleaved beta`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val adaptive = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(forceAdaptiveThinking = true),
        )
        api(transport).stream(adaptive, context, AnthropicMessagesOptions(apiKey = "k")).toList()
        assertNull(transport.requests.single().headers["anthropic-beta"])
    }

    @Test
    fun `allowedFallbackModels add the server-side fallback beta after the interleaved beta`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val fable = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(
                allowedFallbackModels = listOf(
                    AnthropicAllowedFallbackModel(
                        provider = "anthropic",
                        model = "claude-opus-4-8",
                        cost = ModelCost(input = 5.0, output = 25.0, cacheRead = 0.5, cacheWrite = 6.25),
                    ),
                ),
            ),
        )
        api(transport)
            .stream(fable, context, AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true))
            .toList()
        assertEquals(
            "interleaved-thinking-2025-05-14,server-side-fallback-2026-07-01",
            transport.requests.single().headers["anthropic-beta"],
        )

        // Without thinking enabled the fallback beta stands alone.
        val plain = FakeTransport()
        plain.enqueueNamedResponse(textStream("ok"))
        api(plain).stream(fable, context, AnthropicMessagesOptions(apiKey = "k")).toList()
        assertEquals(
            "server-side-fallback-2026-07-01",
            plain.requests.single().headers["anthropic-beta"],
        )
    }

    private val fableWithFallbacks = claude.copy(
        anthropicCompat = claude.anthropicCompat.copy(
            allowedFallbackModels = listOf(
                AnthropicAllowedFallbackModel(
                    provider = "anthropic",
                    model = "claude-opus-4-8",
                    cost = ModelCost(input = 5.0, output = 25.0, cacheRead = 0.5, cacheWrite = 6.25),
                ),
                AnthropicAllowedFallbackModel(
                    provider = "openrouter",
                    model = "claude-opus-5",
                    cost = ModelCost(input = 99.0, output = 99.0),
                ),
            ),
        ),
    )

    @Test
    fun `served fallback model attributes usage cost from the fallback entry`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(input = 100, model = "claude-opus-4-8"),
            messageDelta(output = 7),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport)
                .stream(fableWithFallbacks, context, AnthropicMessagesOptions(apiKey = "k"))
                .toList()
                .last(),
        )
        assertEquals("claude-opus-4-8", done.message.responseModel)
        assertEquals("claude-opus-4-8", done.message.model)
        assertEquals(100 * 5.0 / 1_000_000, done.message.usage.cost.input, 1e-12)
        assertEquals(7 * 25.0 / 1_000_000, done.message.usage.cost.output, 1e-12)
    }

    @Test
    fun `same-model response keeps the requested model cost`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(input = 100, model = "claude-sonnet-4-5"),
            messageDelta(output = 7),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport)
                .stream(fableWithFallbacks, context, AnthropicMessagesOptions(apiKey = "k"))
                .toList()
                .last(),
        )
        assertEquals(100 * 3.0 / 1_000_000, done.message.usage.cost.input, 1e-12)
        assertEquals(7 * 15.0 / 1_000_000, done.message.usage.cost.output, 1e-12)
    }

    @Test
    fun `unknown different model keeps the requested model cost`() = runTest {
        // Same model id as the openrouter fallback entry but a different
        // provider: no fallback match.
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            messageStart(input = 100, model = "claude-opus-5"),
            messageDelta(output = 7),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport)
                .stream(fableWithFallbacks, context, AnthropicMessagesOptions(apiKey = "k"))
                .toList()
                .last(),
        )
        assertEquals("claude-opus-5", done.message.responseModel)
        assertEquals(100 * 3.0 / 1_000_000, done.message.usage.cost.input, 1e-12)
        assertEquals(7 * 15.0 / 1_000_000, done.message.usage.cost.output, 1e-12)
    }

    @Test
    fun `oauth token uses bearer auth and claude code headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .stream(claude, context, AnthropicMessagesOptions(apiKey = "sk-ant-oat-abc"))
            .toList()
        val request = transport.requests.single()
        assertEquals("sk-ant-oat-abc", request.bearerToken)
        assertTrue(request.headers.keys.none { it.equals("x-api-key", ignoreCase = true) })
        // Thinking not enabled, so no interleaved beta after the OAuth pair.
        assertEquals(
            "claude-code-20250219,oauth-2025-04-20",
            request.headers["anthropic-beta"],
        )
        assertEquals("claude-cli/2.1.251", request.headers["user-agent"])
        assertEquals("cli", request.headers["x-app"])
    }

    private val copilotClaude = claude.copy(
        id = "claude-sonnet-4.6",
        provider = "github-copilot",
        baseUrl = "https://api.individual.githubcopilot.com",
        headers = mapOf(
            "User-Agent" to "GitHubCopilotChat/1.0",
            "Copilot-Integration-Id" to "vscode-chat",
        ),
        anthropicCompat = claude.anthropicCompat.copy(forceAdaptiveThinking = true),
    )

    @Test
    fun `copilot stored session token uses bearer auth with copilot headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .stream(copilotClaude, context, AnthropicMessagesOptions(apiKey = "tid_copilot_session_test_token"))
            .toList()
        val request = transport.requests.single()
        assertEquals("https://api.individual.githubcopilot.com/v1/messages?beta=true", request.url)
        assertEquals("tid_copilot_session_test_token", request.bearerToken)
        assertTrue(request.headers.keys.none { it.equals("x-api-key", ignoreCase = true) })
        assertEquals("GitHubCopilotChat/1.0", request.headers["User-Agent"])
        assertEquals("vscode-chat", request.headers["Copilot-Integration-Id"])
        assertEquals("user", request.headers["X-Initiator"])
        assertEquals("conversation-edits", request.headers["Openai-Intent"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("application/json", request.headers["accept"])
        // Divergence (owner decision): pi's browser-CORS header is not sent.
        assertNull(request.headers["anthropic-dangerous-direct-browser-access"])
        // Copilot does not support eager tool input streaming; the adaptive
        // thinking model skips the interleaved beta, so no anthropic-beta.
        assertNull(request.headers["anthropic-beta"])
    }

    @Test
    fun `copilot api-key-shaped token still takes the bearer path`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        // The Copilot branch precedes the OAuth branch, so even an
        // Anthropic-shaped token stays on Bearer with isOAuth false (no
        // Claude Code system prompt or tool-name renaming).
        api(transport)
            .stream(copilotClaude, context, AnthropicMessagesOptions(apiKey = "sk-ant-api03-copilot"))
            .toList()
        val request = transport.requests.single()
        assertEquals("sk-ant-api03-copilot", request.bearerToken)
        assertTrue(request.headers.keys.none { it.equals("x-api-key", ignoreCase = true) })
        assertEquals("user", request.headers["X-Initiator"])
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertTrue("You are Claude Code" !in request.body.decodeToString())
        assertEquals("claude-sonnet-4.6", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `copilot explicit request headers override static and dynamic ones case-insensitively`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .stream(
                copilotClaude,
                context,
                AnthropicMessagesOptions(
                    apiKey = "tid_token",
                    headers = mapOf(
                        "copilot-integration-id" to "jetbrains",
                        "OPENAI-INTENT" to "completion",
                        // A null explicit header suppresses the dynamic one.
                        "x-initiator" to null,
                    ),
                ),
            )
            .toList()
        val request = transport.requests.single()
        assertEquals("jetbrains", request.headers["copilot-integration-id"])
        assertEquals("completion", request.headers["OPENAI-INTENT"])
        assertTrue(request.headers.keys.none { it.equals("x-initiator", ignoreCase = true) })
        assertEquals("tid_token", request.bearerToken)
    }

    @Test
    fun `session affinity header sent only when enabled with a session`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val affinity = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(sendSessionAffinityHeaders = true),
        )
        api(transport)
            .stream(affinity, context, AnthropicMessagesOptions(apiKey = "k", sessionId = "sess-1"))
            .toList()
        assertEquals("sess-1", transport.requests.single().headers["x-session-affinity"])
    }

    @Test
    fun `retries retryable http failure before content begins`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(429, "slow down", mapOf("retry-after-ms" to listOf("10")))
        transport.enqueueNamedResponse(textStream("ok"))
        val events = api(transport)
            .stream(claude, context, AnthropicMessagesOptions(apiKey = "k", maxRetries = 1))
            .toList()
        assertEquals(2, transport.requests.size)
        assertIs<AssistantMessageEvent.Done>(events.last())
    }

    @Test
    fun `cancellation mid-stream never emits an error event`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("a", "b", "c"))
        val events = api(transport)
            .stream(claude, context, AnthropicMessagesOptions(apiKey = "k"))
            .take(3) // Start, TextStart, first TextDelta
            .toList()
        assertTrue(events.none { it is AssistantMessageEvent.Error }, "cancellation must not emit Error")
        assertTrue(transport.cancelled.value, "transport must observe cancellation")
    }

    @Test
    fun `streamSimple without reasoning disables thinking`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .streamSimple(claude, context, SimpleStreamOptions(apiKey = "k"))
            .toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streamSimple maps reasoning to a thinking budget with answer room`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .streamSimple(
                claude,
                context,
                SimpleStreamOptions(apiKey = "k", reasoning = ThinkingLevel.MEDIUM),
            )
            .toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        // medium -> 8192 budget.
        assertEquals(8192, thinking["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(claude.maxTokens, body["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `streamSimple clamps the budget when the ceiling is tight`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val small = claude.copy(maxTokens = 4096, contextWindow = 200_000)
        api(transport)
            .streamSimple(
                small,
                context,
                SimpleStreamOptions(apiKey = "k", reasoning = ThinkingLevel.HIGH, maxTokens = 2048),
            )
            .toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        val thinking = body["thinking"]!!.jsonObject
        // high -> 16384 budget; ceiling 2048+16384 capped at 4096, budget clamped
        // to ceiling - 1024.
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(4096 - 1024, thinking["budget_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `streamSimple on adaptive models maps reasoning to effort`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val adaptive = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(forceAdaptiveThinking = true),
        )
        api(transport)
            .streamSimple(
                adaptive,
                context,
                SimpleStreamOptions(apiKey = "k", reasoning = ThinkingLevel.LOW),
            )
            .toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    /**
     * Ports anthropic-thinking-disable: with thinking off, budget-based and
     * adaptive models alike send `thinking.type=disabled` (and nothing else),
     * while models whose thinkingLevelMap declares `off: null` omit the
     * field entirely. Upstream drives this through the live model catalog;
     * the generated asset provides the same models.
     */
    @Test
    fun `thinking off sends disabled for budget and adaptive models and omits it when off is null`() = runTest {
        val catalog = realAsset()
        val budget = catalog.getModel("anthropic", "claude-sonnet-4-5")!!
        val adaptive = catalog.getModel("anthropic", "claude-opus-4-8")!!
        val offUnsupported = catalog.getModel("anthropic", "claude-fable-5")!!

        for (model in listOf(budget, adaptive)) {
            val transport = FakeTransport()
            transport.enqueueNamedResponse(textStream("ok"))
            api(transport).streamSimple(model, context, SimpleStreamOptions(apiKey = "fake-key")).toList()
            val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
            assertEquals(
                """{"type":"disabled"}""",
                body["thinking"].toString(),
                "model ${model.id}",
            )
            assertNull(body["output_config"], "model ${model.id}")
        }

        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport).streamSimple(offUnsupported, context, SimpleStreamOptions(apiKey = "fake-key")).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertNull(body["thinking"])
        assertNull(body["output_config"])
    }

    /**
     * Ports anthropic-thinking-disable: adaptive models map reasoning levels
     * to `output_config.effort` — high by default, xhigh through the model's
     * thinkingLevelMap (Claude Opus 4.8 / Sonnet 5).
     */
    @Test
    fun `adaptive thinking maps high and xhigh reasoning to effort`() = runTest {
        val catalog = realAsset()
        val opus48 = catalog.getModel("anthropic", "claude-opus-4-8")!!
        val sonnet5 = catalog.getModel("anthropic", "claude-sonnet-5")!!

        val cases = mapOf(
            opus48 to mapOf(ThinkingLevel.HIGH to "high", ThinkingLevel.XHIGH to "xhigh"),
            sonnet5 to mapOf(ThinkingLevel.HIGH to "high"),
        )
        for ((model, levels) in cases) {
            for ((level, expectedEffort) in levels) {
                val transport = FakeTransport()
                transport.enqueueNamedResponse(textStream("ok"))
                api(transport)
                    .streamSimple(model, context, SimpleStreamOptions(apiKey = "fake-key", reasoning = level))
                    .toList()
                val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
                assertEquals(
                    """{"type":"adaptive","display":"summarized"}""",
                    body["thinking"].toString(),
                    "model ${model.id} level $level",
                )
                assertEquals(
                    expectedEffort,
                    body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
                    "model ${model.id} level $level",
                )
            }
        }
    }

    /**
     * streamSimple carries only the narrow auto/none ToolChoice; broader
     * AnthropicToolChoice shapes are exercised through AnthropicMessagesOptions
     * (stream) below and in AnthropicMessagesPayloadTest.
     */
    @Test
    fun `streamSimple forwards each toolChoice shape to the wire`() = runTest {
        val tool = Tool(name = "edit", description = "Edit a file.", parameters = Json.parseToJsonElement("""{"type":"object"}"""))
        val tooledContext = context.copy(tools = listOf(tool))
        val cases = mapOf(
            SimpleToolChoice.Auto to "auto",
            SimpleToolChoice.None to "none",
        )
        for ((choice, expected) in cases) {
            val transport = FakeTransport()
            transport.enqueueNamedResponse(textStream("ok"))
            api(transport)
                .streamSimple(claude, tooledContext, SimpleStreamOptions(apiKey = "k", toolChoice = choice))
                .toList()
            val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
            assertEquals(expected, body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        // Provider-level options still carry the full Anthropic union.
        val forced = FakeTransport()
        forced.enqueueNamedResponse(textStream("ok"))
        api(forced)
            .stream(
                claude,
                tooledContext,
                AnthropicMessagesOptions(apiKey = "k", toolChoice = AnthropicToolChoice.Tool("edit")),
            )
            .toList()
        val forcedChoice =
            Json.parseToJsonElement(forced.requests.single().body.decodeToString()).jsonObject["tool_choice"]!!.jsonObject
        assertEquals("tool", forcedChoice["type"]!!.jsonPrimitive.content)
        assertEquals("edit", forcedChoice["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streamSimple cacheRetention long uses the 1h ttl`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .streamSimple(
                claude,
                Context(systemPrompt = "s", messages = listOf(UserMessage.ofText("hi"))),
                SimpleStreamOptions(apiKey = "k", cacheRetention = CacheRetention.LONG),
            )
            .toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(
            "1h",
            body["system"]!!.jsonArray.single().jsonObject["cache_control"]!!
                .jsonObject["ttl"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `streamSimple cacheRetention none suppresses caching and session affinity`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val affinity = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(sendSessionAffinityHeaders = true),
        )
        api(transport)
            .streamSimple(
                affinity,
                Context(systemPrompt = "s", messages = listOf(UserMessage.ofText("hi"))),
                SimpleStreamOptions(apiKey = "k", sessionId = "sess-1", cacheRetention = CacheRetention.NONE),
            )
            .toList()
        val request = transport.requests.single()
        assertNull(request.headers["x-session-affinity"])
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertNull(body["system"]!!.jsonArray.single().jsonObject["cache_control"])
    }

    @Test
    fun `streamSimple missing key surfaces as error event without a request`() = runTest {
        val transport = FakeTransport()
        val events = api(transport)
            .streamSimple(claude, context, SimpleStreamOptions())
            .toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue("No API key" in (error.error.errorMessage ?: ""))
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `cross-provider replay reaches the wire as transformed anthropic messages`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val foreign = works.resolve.pathfinder.ai.AssistantMessage(
            content = listOf(ThinkingContent("foreign thoughts"), TextContent("hello")),
            api = "openai-completions",
            provider = "openai",
            model = "gpt-5",
            stopReason = StopReason.STOP,
        )
        api(transport)
            .stream(
                claude,
                Context(
                    systemPrompt = "sys",
                    messages = listOf(foreign, UserMessage.ofText("next")),
                ),
                AnthropicMessagesOptions(apiKey = "k"),
            )
            .toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        // Assistant thinking became a text block ahead of its original text.
        val assistantBlocks = messages[0].jsonObject["content"]!!
            .let { it as kotlinx.serialization.json.JsonArray }
        assertEquals("foreign thoughts", assistantBlocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("hello", assistantBlocks[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("sys", body["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    // ---- Mid-conversation effort stream behavior (ports
    // anthropic-mid-conversation-effort.test.ts + anthropic-sse-parsing) ----

    private fun managedClaude() = claude.copy(
        id = "claude-fable-5-1",
        anthropicCompat = claude.anthropicCompat.copy(
            forceAdaptiveThinking = true,
            supportsMidConvoEffort = true,
        ),
    )

    /** Ports "sends the effort and binding beta headers". */
    @Test
    fun `managed models send the mid-conversation effort and binding beta headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .stream(
                managedClaude(),
                context,
                AnthropicMessagesOptions(apiKey = "test-key", cacheRetention = CacheRetention.NONE),
            )
            .toList()
        val beta = transport.requests.single().headers["anthropic-beta"]!!
        assertTrue("mid-conversation-output-config-2026-07-01" in beta, beta)
        assertTrue("thinking-binding-controls-2026-08-01" in beta, beta)
        assertEquals("https://api.anthropic.com/v1/messages?beta=true", transport.requests.single().url)
    }

    /** Ports the providerThinkingLevel assertions of the payload-capture cases. */
    @Test
    fun `managed models record the active effort on the response`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport)
                .stream(
                    managedClaude(),
                    context,
                    AnthropicMessagesOptions(apiKey = "k", effort = AnthropicEffort.LOW),
                )
                .toList()
                .last(),
        )
        assertEquals("low", done.message.providerThinkingLevel)

        // Default effort is high.
        val defaulted = FakeTransport()
        defaulted.enqueueNamedResponse(textStream("ok"))
        val doneDefault = assertIs<AssistantMessageEvent.Done>(
            api(defaulted)
                .stream(managedClaude(), context, AnthropicMessagesOptions(apiKey = "k"))
                .toList()
                .last(),
        )
        assertEquals("high", doneDefault.message.providerThinkingLevel)

        // Unmanaged models never set it.
        val plain = FakeTransport()
        plain.enqueueNamedResponse(textStream("ok"))
        val donePlain = assertIs<AssistantMessageEvent.Done>(
            api(plain)
                .stream(claude, context, AnthropicMessagesOptions(apiKey = "k"))
                .toList()
                .last(),
        )
        assertNull(donePlain.message.providerThinkingLevel)
    }

    /** Ports anthropic-sse-parsing "uses the serving model input transformations from the final stream event". */
    @Test
    fun `input transformations from the final stream event become a diagnostic`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            "message_start" to """{"type":"message_start","message":{"id":"msg_transformations","model":"claude-fable-5-1","usage":{"input_tokens":12,"output_tokens":0},"input_transformations":[{"type":"thinking_dropped","path":"messages.1.content.0","reason":"prefix_binding_mismatch"}]}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":12,"output_tokens":5},"input_transformations":[{"type":"thinking_dropped","path":"messages.3.content.0","reason":"model_binding_mismatch"}]}""",
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(transport).stream(managedClaude(), context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        // message_delta's list replaces message_start's.
        val diagnostic = done.message.diagnostics.single()
        assertEquals("anthropic_input_transformations", diagnostic.type)
        assertEquals(
            """{"transformations":[{"type":"thinking_dropped","path":"messages.3.content.0","reason":"model_binding_mismatch"}]}""",
            diagnostic.details.toString(),
        )

        // Streams without transformations carry no diagnostics.
        val plain = FakeTransport()
        plain.enqueueNamedResponse(textStream("ok"))
        val donePlain = assertIs<AssistantMessageEvent.Done>(
            api(plain).stream(managedClaude(), context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertTrue(donePlain.message.diagnostics.isEmpty())
    }

    /** Ports anthropic-sse-parsing "fails safely when Anthropic falls back after output begins". */
    @Test
    fun `fallback content blocks are skipped before output and fail after it`() = runTest {
        // A fallback marker before any output: skipped, stream completes.
        val leading = FakeTransport()
        leading.enqueueNamedResponse(
            "message_start" to messageStart(model = "claude-opus-4-8").second,
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"fallback","from":{"model":"claude-opus-5"},"to":{"model":"claude-opus-4-8"}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            messageDelta(output = 5),
            messageStop,
        )
        val done = assertIs<AssistantMessageEvent.Done>(
            api(leading).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertEquals(StopReason.STOP, done.reason)

        // The same marker after output began is a hard failure.
        val midOutput = FakeTransport()
        midOutput.enqueueNamedResponse(
            messageStart(),
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"partial"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"fallback","from":{"model":"claude-opus-5"},"to":{"model":"claude-opus-4-8"}}}""",
        )
        val error = assertIs<AssistantMessageEvent.Error>(
            api(midOutput).stream(claude, context, AnthropicMessagesOptions(apiKey = "k")).toList().last(),
        )
        assertTrue("unsupported mid-output model fallback" in (error.error.errorMessage ?: ""))
    }

    /** Ports anthropic-sse-parsing "forces streaming after an onPayload replacement". */
    @Test
    fun `onPayload replacements cannot turn off streaming and betas leave the body`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("ok"))
        api(transport)
            .stream(
                claude,
                context,
                AnthropicMessagesOptions(
                    apiKey = "k",
                    thinkingEnabled = true,
                    onPayload = { payload, _ ->
                        JsonObject(payload.toMutableMap().apply { put("stream", JsonPrimitive(false)) })
                    },
                ),
            )
            .toList()
        val request = transport.requests.single()
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        // betas moved out of the body into the header.
        assertNull(body["betas"])
        assertEquals("interleaved-thinking-2025-05-14", request.headers["anthropic-beta"])
    }

    /** Ports anthropic-auth-token explicit beta header override/suppression. */
    @Test
    fun `explicit anthropic-beta headers override or suppress composed betas`() = runTest {
        val override = FakeTransport()
        override.enqueueNamedResponse(textStream("ok"))
        api(override)
            .stream(
                claude,
                context,
                AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true, headers = mapOf("anthropic-beta" to "custom-beta")),
            )
            .toList()
        assertEquals("custom-beta", override.requests.single().headers["anthropic-beta"])

        val suppressed = FakeTransport()
        suppressed.enqueueNamedResponse(textStream("ok"))
        api(suppressed)
            .stream(
                claude,
                context,
                AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true, headers = mapOf("anthropic-beta" to null)),
            )
            .toList()
        assertNull(suppressed.requests.single().headers["anthropic-beta"])

        // Model headers participate too. The raw string wins on the wire,
        // exactly as upstream: the SDK puts its joined-betas header first in
        // build order and the client's defaultHeaders (model/options headers)
        // override it, so no re-normalization happens at the header layer.
        // getBetaFeatures' split/trim/dedupe only shapes the betas payload
        // (visible to onPayload), which the explicit header then overrides.
        val modelOverride = FakeTransport()
        modelOverride.enqueueNamedResponse(textStream("ok"))
        api(modelOverride)
            .stream(
                claude.copy(headers = mapOf("anthropic-beta" to " b ,,a ")),
                context,
                AnthropicMessagesOptions(apiKey = "k"),
            )
            .toList()
        assertEquals(" b ,,a ", modelOverride.requests.single().headers["anthropic-beta"])
    }
}
