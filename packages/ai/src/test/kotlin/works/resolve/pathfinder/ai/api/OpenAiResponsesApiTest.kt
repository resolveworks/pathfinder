package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.testing.FakeClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCost
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.SessionAffinityFormat
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingLevelMap
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import java.io.File
import org.junit.Assume.assumeTrue

class OpenAiResponsesApiTest {

    private val model = Model(
        id = "gpt-5-mini",
        name = "GPT-5 Mini",
        api = "openai-responses",
        provider = "openai",
        baseUrl = "https://api.openai.com/v1",
        reasoning = true,
        input = listOf(works.resolve.pathfinder.ai.InputModality.TEXT),
        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.25, cacheWrite = 0.5),
        contextWindow = 400_000,
        maxTokens = 128_000,
        responsesCompat = OpenAiResponsesCompat(),
    )

    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun api(transport: FakeTransport) = OpenAiResponsesApi(
        transport,
        ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }),
        clock = FakeClock(1_770_000_000_000L),
    )

    private fun completedChunk(text: String = "ok") = listOf(
        """{"type":"response.output_item.added","output_index":0,
            "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
        """{"type":"response.output_text.delta","output_index":0,"delta":"$text"}""",
        """{"type":"response.output_item.done","output_index":0,
            "item":{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                "content":[{"type":"output_text","text":"$text","annotations":[]}]}}""",
        """{"type":"response.completed","response":{"id":"resp_1","status":"completed",
            "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        "[DONE]",
    )

    private fun body(transport: FakeTransport) =
        responsesJson.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject

    @Test
    fun `posts to the responses endpoint with store false`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val events = api(transport).stream(model, context, OpenAiResponsesOptions(apiKey = "k")).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("https://api.openai.com/v1/responses", transport.requests.single().url)
        assertEquals("k", transport.requests.single().bearerToken)
        val body = body(transport)
        assertEquals("gpt-5-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `model headers override the default user agent`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).stream(
            model.copy(headers = mapOf("User-Agent" to "provider-agent")),
            context,
            OpenAiResponsesOptions(apiKey = "k"),
        ).toList()
        assertEquals("provider-agent", transport.requests.single().headers["User-Agent"])
    }

    @Test
    fun `session id becomes a clamped prompt cache key and affinity headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val sessionId = "s".repeat(80)
        api(transport).stream(
            model,
            context,
            OpenAiResponsesOptions(apiKey = "k", sessionId = sessionId),
        ).toList()
        val request = transport.requests.single()
        // pi sends the raw session id in the affinity headers; only the
        // prompt_cache_key body param is clamped.
        assertEquals(sessionId, request.headers["session_id"])
        assertEquals(sessionId, request.headers["x-client-request-id"])
        assertEquals(
            "s".repeat(64),
            body(transport)["prompt_cache_key"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `openrouter affinity uses x-session-id`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val routerModel = model.copy(
            provider = "openrouter",
            baseUrl = "https://openrouter.ai/api/v1",
            responsesCompat = OpenAiResponsesCompat(sessionAffinityFormat = SessionAffinityFormat.OPENROUTER),
        )
        api(transport).stream(routerModel, context, OpenAiResponsesOptions(apiKey = "k", sessionId = "s1")).toList()
        val headers = transport.requests.single().headers
        assertEquals("s1", headers["x-session-id"])
        assertNull(headers["session_id"])
    }

    @Test
    fun `max output tokens clamp to the minimum of 16`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).stream(model, context, OpenAiResponsesOptions(apiKey = "k", maxTokens = 4)).toList()
        assertEquals(16, body(transport)["max_output_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `max output tokens are omitted when unsupported (pi b8b873b98, #8941)`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val gated = model.copy(
            responsesCompat = OpenAiResponsesCompat(supportsMaxOutputTokens = false),
        )
        api(transport).stream(gated, context, OpenAiResponsesOptions(apiKey = "k", maxTokens = 100)).toList()
        assertNull(body(transport)["max_output_tokens"])
    }

    @Test
    fun `reasoning effort maps through the thinking level map`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val mapped = model.copy(
            thinkingLevelMap = ThinkingLevelMap.of(
                ModelThinkingLevel.OFF to "none",
                ModelThinkingLevel.LOW to "low",
                ModelThinkingLevel.MEDIUM to null,
                ModelThinkingLevel.HIGH to "high",
            ),
        )
        api(transport).stream(
            mapped,
            context,
            OpenAiResponsesOptions(apiKey = "k", reasoningEffort = ModelThinkingLevel.LOW),
        ).toList()
        val body = body(transport)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("auto", body["reasoning"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
        assertEquals(
            "reasoning.encrypted_content",
            body["include"]!!.jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun `no reasoning requested sends the off effort`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val mapped = model.copy(
            thinkingLevelMap = ThinkingLevelMap.of(ModelThinkingLevel.OFF to "none"),
        )
        api(transport).stream(mapped, context, OpenAiResponsesOptions(apiKey = "k")).toList()
        val body = body(transport)
        assertEquals("none", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(body["include"])
    }

    @Test
    fun `explicitly unsupported off disables reasoning entirely`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val mapped = model.copy(
            thinkingLevelMap = ThinkingLevelMap.of(ModelThinkingLevel.OFF to null),
        )
        api(transport).stream(mapped, context, OpenAiResponsesOptions(apiKey = "k")).toList()
        assertNull(body(transport)["reasoning"])
    }

    @Test
    fun `tools and tool choice land in the payload`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val tool = Tool("get_weather", "Get weather", buildJsonObject { put("type", "object") })
        api(transport).stream(
            model,
            context.copy(tools = listOf(tool)),
            OpenAiResponsesOptions(apiKey = "k", toolChoice = "required"),
        ).toList()
        val body = body(transport)
        assertEquals(
            "get_weather",
            body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cache retention none omits the cache key and requests explicit mode`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val explicit = model.copy(
            responsesCompat = OpenAiResponsesCompat(supportsExplicitPromptCacheMode = true),
        )
        api(transport).stream(
            explicit,
            context,
            OpenAiResponsesOptions(
                apiKey = "k",
                sessionId = "s1",
                cacheRetention = works.resolve.pathfinder.ai.CacheRetention.NONE,
            ),
        ).toList()
        val body = body(transport)
        assertNull(body["prompt_cache_key"])
        assertEquals("explicit", body["prompt_cache_options"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `long cache retention requests 24h retention`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).stream(
            model,
            context,
            OpenAiResponsesOptions(
                apiKey = "k",
                sessionId = "s1",
                cacheRetention = works.resolve.pathfinder.ai.CacheRetention.LONG,
            ),
        ).toList()
        assertEquals("24h", body(transport)["prompt_cache_retention"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing api key surfaces an error event without a request`() = runTest {
        val transport = FakeTransport()
        val events = api(transport).stream(model, context, OpenAiResponsesOptions()).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals("No API key for provider: openai", error.error.errorMessage)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `authorization header stands in for an api key`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val events = api(transport).stream(
            model,
            context,
            OpenAiResponsesOptions(headers = mapOf("authorization" to "Bearer x")),
        ).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("unused", transport.requests.single().bearerToken)
    }

    @Test
    fun `early stream end surfaces the terminal-event error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"type":"response.created","response":{"id":"r1"}}"""),
        )
        val events = api(transport).stream(model, context, OpenAiResponsesOptions(apiKey = "k")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(
            "OpenAI Responses stream ended before a terminal response event",
            error.error.errorMessage,
        )
    }

    @Test
    fun `http errors format status and whole body`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(500, """{"error":{"code":"server_error","message":"boom"}}""")
        val events = api(transport).stream(model, context, OpenAiResponsesOptions(apiKey = "k")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(
            """OpenAI API error (500): {"error":{"code":"server_error","message":"boom"}}""",
            error.error.errorMessage,
        )
    }

    @Test
    fun `cancellation mid-stream rethrows and never emits an error event`() = runTest {
        val transport = FakeTransport()
        transport.enqueueHangingResponse(
            """{"type":"response.output_item.added","output_index":0,
                "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
            """{"type":"response.output_text.delta","output_index":0,"delta":"partial"}""",
        )
        val collected = mutableListOf<AssistantMessageEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            api(transport)
                .stream(model, context, OpenAiResponsesOptions(apiKey = "k"))
                .toList(collected)
        }
        assertTrue(collected.any { it is AssistantMessageEvent.Start })
        job.cancelAndJoin()
        // Documented divergence: AbortSignal-style aborts map to coroutine
        // cancellation and rethrow CancellationException (no Error event).
        assertTrue(collected.none { it is AssistantMessageEvent.Error })
        assertTrue(transport.cancelled.value)
    }

    @Test
    fun `done carries usage costs and block content`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk("hello").toTypedArray()))
        val events = api(transport).stream(model, context, OpenAiResponsesOptions(apiKey = "k")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        assertEquals("hello", (done.message.content.single() as TextContent).text)
        assertEquals("resp_1", done.message.responseId)
        assertEquals(10, done.message.usage.input)
        assertEquals(5, done.message.usage.output)
        assertEquals(0.00001, done.message.usage.cost.output, 1e-9)
        assertIs<AssistantMessageEvent.Start>(events.first())
    }

    @Test
    fun `github-copilot models omit the default reasoning block`() = runTest {
        // pi b8b873b98 openai-responses-compat: "omits reasoning when no
        // reasoning is requested" — the default-effort branch skips
        // github-copilot entirely.
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).stream(
            model.copy(provider = "github-copilot"),
            context,
            OpenAiResponsesOptions(apiKey = "k"),
        ).toList()
        assertNull(body(transport)["reasoning"])
    }

    @Test
    fun `sends max output tokens above the floor by default`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).stream(model, context, OpenAiResponsesOptions(apiKey = "k", maxTokens = 1024)).toList()
        assertEquals(1024, body(transport)["max_output_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `strict-capable providers emit explicit strict flags per tool`() = runTest {
        // pi b8b873b98 openai-responses-compat: "sets strict mode explicitly
        // for Cloudflare OpenAI Responses tools" — ordinary tools carry
        // strict:false, constrained ones strict:true.
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val schema = {
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
                put("additionalProperties", false)
            }
        }
        val ordinary = Tool("ordinary", "An ordinary tool", schema())
        val constrained = Tool(
            "constrained",
            "A constrained tool",
            schema(),
            constrainedSampling = works.resolve.pathfinder.ai.ConstrainedSamplingConfig.JsonSchema(
                works.resolve.pathfinder.ai.StrictJsonSchemaMode.PREFER,
            ),
        )
        api(transport).stream(
            model.copy(responsesCompat = OpenAiResponsesCompat(supportsStrictMode = true)),
            context.copy(tools = listOf(ordinary, constrained)),
            OpenAiResponsesOptions(apiKey = "k"),
        ).toList()
        val tools = body(transport)["tools"]!!.jsonArray
        assertEquals("ordinary", tools[0]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(false, tools[0]!!.jsonObject["strict"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("constrained", tools[1]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(true, tools[1]!!.jsonObject["strict"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `openai-nosession affinity keeps only the client request id`() = runTest {
        // pi b8b873b98 openai-responses-compat: "uses OpenAI no-session format
        // when configured" — session_id header dropped, prompt cache kept.
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val nosession = model.copy(
            provider = "proxy",
            baseUrl = "https://proxy.example.com/v1",
            responsesCompat = OpenAiResponsesCompat(sessionAffinityFormat = SessionAffinityFormat.OPENAI_NOSESSION),
        )
        api(transport).stream(nosession, context, OpenAiResponsesOptions(apiKey = "k", sessionId = "session-proxy"))
            .toList()
        val headers = transport.requests.single().headers
        assertEquals("session-proxy", headers["x-client-request-id"])
        assertNull(headers["session_id"])
        assertNull(headers["x-session-id"])
        assertEquals("session-proxy", body(transport)["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openrouter affinity auto-detects from the endpoint`() = runTest {
        // pi b8b873b98 openai-responses-compat: "auto-detects OpenRouter
        // session-affinity header for OpenRouter Responses endpoints".
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val openrouter = model.copy(provider = "openrouter", baseUrl = "https://openrouter.ai/api/v1")
        api(transport).stream(openrouter, context, OpenAiResponsesOptions(apiKey = "k", sessionId = "session-or"))
            .toList()
        val headers = transport.requests.single().headers
        assertEquals("session-or", headers["x-session-id"])
        assertNull(headers["session_id"])
        assertNull(headers["x-client-request-id"])
        assertEquals("session-or", body(transport)["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `explicit headers override the default cache-affinity headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).stream(
            model,
            context,
            OpenAiResponsesOptions(
                apiKey = "k",
                sessionId = "session-123",
                headers = mapOf("session_id" to "override-session", "x-client-request-id" to "override-request"),
            ),
        ).toList()
        val headers = transport.requests.single().headers
        assertEquals("override-session", headers["session_id"])
        assertEquals("override-request", headers["x-client-request-id"])
    }

    @Test
    fun `cache retention none omits the cache-affinity headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).stream(
            model,
            context,
            OpenAiResponsesOptions(
                apiKey = "k",
                sessionId = "session-123",
                cacheRetention = works.resolve.pathfinder.ai.CacheRetention.NONE,
            ),
        ).toList()
        val headers = transport.requests.single().headers
        assertNull(headers["session_id"])
        assertNull(headers["x-client-request-id"])
    }

    @Test
    fun `service tier priority scales the completed usage cost`() = runTest {
        // pi b8b873b98 openai-responses-compat: "applies %s %s service-tier
        // cost multiplier" — priority doubles the per-million cost.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"type":"response.completed","response":{"id":"r1","status":"completed",
                    "service_tier":"priority",
                    "usage":{"input_tokens":20000,"output_tokens":10000,"total_tokens":30000,
                        "input_tokens_details":{"cached_tokens":0}}}}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiResponsesOptions(apiKey = "k")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        // Model cost input 1.0 / output 2.0 per million; priority ×2 for
        // non-gpt-5.5 models.
        assertEquals(0.04, done.message.usage.cost.input, 1e-9)
        assertEquals(0.04, done.message.usage.cost.output, 1e-9)
    }

    @Test
    fun `streamSimple forwards provider-neutral tool choice`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).streamSimple(
            model,
            context.copy(tools = listOf(Tool("read", "Read a file", buildJsonObject { put("type", "object") }))),
            works.resolve.pathfinder.ai.SimpleStreamOptions(
                apiKey = "k",
                toolChoice = works.resolve.pathfinder.ai.SimpleToolChoice.None,
            ),
        ).toList()
        val body = body(transport)
        assertEquals("none", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(1, body["tools"]!!.jsonArray.size)
    }

    // ---- Deferred tools (ports deferred-tools.test.ts OpenAI Responses cases) ----

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

    private fun makeTool(name: String) = Tool(name, "The $name tool", buildJsonObject { put("type", "object") })

    /** Upstream makeContext(): base tool call, then a result that loads late_tool. */
    private fun deferredContext(tools: List<Tool>): Context = Context(
        messages = listOf(
            UserMessage.ofText("Hello", 1),
            AssistantMessage(
                content = listOf(ToolCall("call_1", "base_tool", "{}")),
                api = "anthropic-messages",
                provider = "anthropic",
                model = "claude-opus-4-6",
                stopReason = StopReason.TOOL_USE,
                timestamp = 2,
            ),
            ToolResultMessage(
                toolCallId = "call_1",
                toolName = "base_tool",
                content = listOf(TextContent("done")),
                addedToolNames = listOf("late_tool"),
                timestamp = 3,
            ),
            UserMessage.ofText("again", 4),
        ),
        tools = tools,
    )

    private fun params(model: Model, context: Context): JsonObject =
        buildParams(
            model,
            context,
            OpenAiResponsesOptions(apiKey = "k"),
            getCompat(model),
            CacheRetention.SHORT,
        )

    private fun toolNames(json: JsonObject): List<String> =
        json["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun JsonObject.typeName(): String? = this["type"]?.jsonPrimitive?.content

    @Test
    fun `deferred tools load through additional_tools for gpt-5_4`() {
        val model = realAsset().getModel("openai", "gpt-5.4")!!
        val json = params(model, deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))))
        assertEquals(listOf("base_tool"), toolNames(json))
        val input = json["input"]!!.jsonArray.map { it.jsonObject }
        val additional = input.single { it.typeName() == "additional_tools" }
        assertEquals("developer", additional["role"]!!.jsonPrimitive.content)
        val tool = additional["tools"]!!.jsonArray.single().jsonObject
        assertEquals("late_tool", tool["name"]!!.jsonPrimitive.content)
        assertNull(tool["defer_loading"])
        assertTrue(input.none { it.typeName()?.startsWith("tool_search") == true })
    }

    @Test
    fun `additional_tools marker is preserved after the loaded tool is used`() {
        val model = realAsset().getModel("openai", "gpt-5.4")!!
        val messages = deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))).messages.toMutableList()
        messages.addAll(
            3,
            listOf(
                AssistantMessage(
                    content = listOf(ToolCall("call_late|fc_late", "late_tool", "{}")),
                    api = "openai-responses",
                    provider = "openai",
                    model = "gpt-5.4",
                    stopReason = StopReason.TOOL_USE,
                    timestamp = 3,
                ),
                ToolResultMessage(
                    toolCallId = "call_late|fc_late",
                    toolName = "late_tool",
                    content = listOf(TextContent("done")),
                    addedToolNames = listOf("late_tool"),
                    timestamp = 3,
                ),
            ),
        )
        val context = Context(messages = messages, tools = listOf(makeTool("base_tool"), makeTool("late_tool")))
        val json = params(model, context)
        assertEquals(listOf("base_tool"), toolNames(json))
        val input = json["input"]!!.jsonArray.map { it.jsonObject }
        val additionalIndexes = input.indices.filter { input[it].typeName() == "additional_tools" }
        val lateCallIndex = input.indexOfFirst {
            it.typeName() == "function_call" && it["name"]!!.jsonPrimitive.content == "late_tool"
        }
        assertEquals(1, additionalIndexes.size)
        assertTrue(additionalIndexes[0] < lateCallIndex)
    }

    @Test
    fun `falls back to client tool search when additional_tools is unsupported`() {
        val model = this.model.copy(
            provider = "openai-proxy",
            responsesCompat = OpenAiResponsesCompat(supportsToolSearch = true),
        )
        val json = params(model, deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))))
        assertEquals(listOf("base_tool"), toolNames(json))
        val input = json["input"]!!.jsonArray.map { it.jsonObject }
        val call = input.single { it.typeName() == "tool_search_call" }
        val output = input.single { it.typeName() == "tool_search_output" }
        assertEquals("client", call["execution"]!!.jsonPrimitive.content)
        assertEquals("completed", call["status"]!!.jsonPrimitive.content)
        assertEquals(call["call_id"]!!.jsonPrimitive.content, output["call_id"]!!.jsonPrimitive.content)
        val tool = output["tools"]!!.jsonArray.single().jsonObject
        assertEquals("late_tool", tool["name"]!!.jsonPrimitive.content)
        assertEquals(true, tool["defer_loading"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(input.none { it.typeName() == "additional_tools" })
    }

    @Test
    fun `uses the normal tool list for unsupported OpenAI models`() {
        val catalog = realAsset()
        for (modelId in listOf("gpt-5.2", "gpt-5.4-nano", "gpt-5.5-pro")) {
            val model = catalog.getModel("openai", modelId)!!
            val json = params(model, deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))))
            assertEquals(listOf("base_tool", "late_tool"), toolNames(json), modelId)
            assertTrue(
                json["input"]!!.jsonArray.map { it.jsonObject }.none { it.typeName() == "tool_search_output" },
                modelId,
            )
        }
    }

    @Test
    fun `uses the normal tool list when OpenAI tool search is explicitly disabled`() {
        val model = realAsset().getModel("openai", "gpt-5.4")!!
            .copy(provider = "openai-proxy", responsesCompat = OpenAiResponsesCompat())
        val json = params(model, deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))))
        assertEquals(listOf("base_tool", "late_tool"), toolNames(json))
        assertTrue(
            json["input"]!!.jsonArray.map { it.jsonObject }.none {
                it.typeName() == "tool_search_output" || it.typeName() == "additional_tools"
            },
        )
    }
}
