
@file:Suppress("ktlint:standard:max-line-length")

package works.resolve.pathfinder.ai.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCost
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.SessionAffinityFormat
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingLevelMap
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.normalizeContext

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
        responsesCompat = OpenAiResponsesCompat()
    )

    private val context = normalizeContext(Context(messages = listOf(UserMessage.ofText("hi"))))

    private fun api(transport: FakeTransport) = OpenAiResponsesApi(
        transport,
        ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }),
        clock = FakeClock(1_770_000_000_000L)
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
        "[DONE]"
    )

    private fun body(transport: FakeTransport) = responsesJson.parseToJsonElement(
        transport.requests.single().body.decodeToString()
    ).jsonObject

    @Test
    fun `posts to the responses endpoint with store false`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val events = api(
            transport
        ).stream(model, context, OpenAiResponsesOptions(apiKey = "sk-test")).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("https://api.openai.com/v1/responses", transport.requests.single().url)
        assertEquals("sk-test", transport.requests.single().bearerToken)
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
            OpenAiResponsesOptions(apiKey = "sk-test")
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
            OpenAiResponsesOptions(apiKey = "sk-test", sessionId = sessionId)
        ).toList()
        val request = transport.requests.single()
        // pi sends the raw session id in the affinity headers; only the
        // prompt_cache_key body param is clamped.
        assertEquals(sessionId, request.headers["session_id"])
        assertEquals(sessionId, request.headers["x-client-request-id"])
        assertEquals(
            "s".repeat(64),
            body(transport)["prompt_cache_key"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `openrouter affinity uses x-session-id`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val routerModel = model.copy(
            provider = "openrouter",
            baseUrl = "https://openrouter.ai/api/v1",
            responsesCompat = OpenAiResponsesCompat(
                sessionAffinityFormat = SessionAffinityFormat.OPENROUTER
            )
        )
        api(
            transport
        ).stream(
            routerModel,
            context,
            OpenAiResponsesOptions(apiKey = "sk-test", sessionId = "s1")
        ).toList()
        val headers = transport.requests.single().headers
        assertEquals("s1", headers["x-session-id"])
        assertNull(headers["session_id"])
    }

    @Test
    fun `max output tokens clamp to the minimum of 16`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(
            transport
        ).stream(model, context, OpenAiResponsesOptions(apiKey = "sk-test", maxTokens = 4)).toList()
        assertEquals(16, body(transport)["max_output_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `max output tokens are omitted when unsupported (pi b8b873b98, #8941)`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val gated = model.copy(
            responsesCompat = OpenAiResponsesCompat(supportsMaxOutputTokens = false)
        )
        api(
            transport
        ).stream(
            gated,
            context,
            OpenAiResponsesOptions(apiKey = "sk-test", maxTokens = 100)
        ).toList()
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
                ModelThinkingLevel.HIGH to "high"
            )
        )
        api(transport).stream(
            mapped,
            context,
            OpenAiResponsesOptions(apiKey = "sk-test", reasoningEffort = ModelThinkingLevel.LOW)
        ).toList()
        val body = body(transport)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("auto", body["reasoning"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
        assertEquals(
            "reasoning.encrypted_content",
            body["include"]!!.jsonArray.single().jsonPrimitive.content
        )
    }

    @Test
    fun `no reasoning requested sends the off effort`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val mapped = model.copy(
            thinkingLevelMap = ThinkingLevelMap.of(ModelThinkingLevel.OFF to "none")
        )
        api(transport).stream(mapped, context, OpenAiResponsesOptions(apiKey = "sk-test")).toList()
        val body = body(transport)
        assertEquals("none", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(body["include"])
    }

    @Test
    fun `explicitly unsupported off disables reasoning entirely`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val mapped = model.copy(
            thinkingLevelMap = ThinkingLevelMap.of(ModelThinkingLevel.OFF to null)
        )
        api(transport).stream(mapped, context, OpenAiResponsesOptions(apiKey = "sk-test")).toList()
        assertNull(body(transport)["reasoning"])
    }

    @Test
    fun `tools and tool choice land in the payload`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val tool = Tool("get_weather", "Get weather", buildJsonObject { put("type", "object") })
        api(transport).stream(
            model,
            normalizeContext(
                Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool))
            ),
            OpenAiResponsesOptions(apiKey = "sk-test", toolChoice = "required")
        ).toList()
        val body = body(transport)
        assertEquals(
            "get_weather",
            body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content
        )
        assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cache retention none omits the cache key and requests explicit mode`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val explicit = model.copy(
            responsesCompat = OpenAiResponsesCompat(supportsExplicitPromptCacheMode = true)
        )
        api(transport).stream(
            explicit,
            context,
            OpenAiResponsesOptions(
                apiKey = "sk-test",
                sessionId = "s1",
                cacheRetention = works.resolve.pathfinder.ai.CacheRetention.NONE
            )
        ).toList()
        val body = body(transport)
        assertNull(body["prompt_cache_key"])
        assertEquals(
            "explicit",
            body["prompt_cache_options"]!!.jsonObject["mode"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `long cache retention requests 24h retention`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(transport).stream(
            model,
            context,
            OpenAiResponsesOptions(
                apiKey = "sk-test",
                sessionId = "s1",
                cacheRetention = works.resolve.pathfinder.ai.CacheRetention.LONG
            )
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
            OpenAiResponsesOptions(headers = mapOf("authorization" to "Bearer x"))
        ).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("unused", transport.requests.single().bearerToken)
    }

    @Test
    fun `early stream end surfaces the terminal-event error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"type":"response.created","response":{"id":"r1"}}""")
        )
        val events = api(
            transport
        ).stream(model, context, OpenAiResponsesOptions(apiKey = "sk-test")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(
            "OpenAI Responses stream ended before a terminal response event",
            error.error.errorMessage
        )
    }

    @Test
    fun `http errors format status and whole body`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(500, """{"error":{"code":"server_error","message":"boom"}}""")
        val events = api(
            transport
        ).stream(model, context, OpenAiResponsesOptions(apiKey = "sk-test")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(
            """OpenAI API error (500): {"error":{"code":"server_error","message":"boom"}}""",
            error.error.errorMessage
        )
    }

    @Test
    fun `cancellation mid-stream rethrows and never emits an error event`() = runTest {
        val transport = FakeTransport()
        transport.enqueueHangingResponse(
            """{"type":"response.output_item.added","output_index":0,
                "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
            """{"type":"response.output_text.delta","output_index":0,"delta":"partial"}"""
        )
        val collected = mutableListOf<AssistantMessageEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            api(transport)
                .stream(model, context, OpenAiResponsesOptions(apiKey = "sk-test"))
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
        val events = api(
            transport
        ).stream(model, context, OpenAiResponsesOptions(apiKey = "sk-test")).toList()
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
            OpenAiResponsesOptions(apiKey = "sk-test")
        ).toList()
        assertNull(body(transport)["reasoning"])
    }

    @Test
    fun `sends max output tokens above the floor by default`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(
            transport
        ).stream(
            model,
            context,
            OpenAiResponsesOptions(apiKey = "sk-test", maxTokens = 1024)
        ).toList()
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
                put(
                    "properties",
                    buildJsonObject {
                        put("value", buildJsonObject { put("type", "string") })
                    }
                )
                put("additionalProperties", false)
            }
        }
        val ordinary = Tool("ordinary", "An ordinary tool", schema())
        val constrained = Tool(
            "constrained",
            "A constrained tool",
            schema(),
            constrainedSampling = works.resolve.pathfinder.ai.ConstrainedSamplingConfig.JsonSchema(
                works.resolve.pathfinder.ai.StrictJsonSchemaMode.PREFER
            )
        )
        api(transport).stream(
            model.copy(responsesCompat = OpenAiResponsesCompat(supportsStrictMode = true)),
            normalizeContext(
                Context(
                    messages = listOf(UserMessage.ofText("hi")),
                    tools = listOf(ordinary, constrained)
                )
            ),
            OpenAiResponsesOptions(apiKey = "sk-test")
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
            responsesCompat = OpenAiResponsesCompat(
                sessionAffinityFormat = SessionAffinityFormat.OPENAI_NOSESSION
            )
        )
        api(
            transport
        ).stream(
            nosession,
            context,
            OpenAiResponsesOptions(apiKey = "sk-test", sessionId = "session-proxy")
        )
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
        val openrouter = model.copy(
            provider = "openrouter",
            baseUrl = "https://openrouter.ai/api/v1"
        )
        api(
            transport
        ).stream(
            openrouter,
            context,
            OpenAiResponsesOptions(apiKey = "sk-test", sessionId = "session-or")
        )
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
                apiKey = "sk-test",
                sessionId = "session-123",
                headers = mapOf(
                    "session_id" to "override-session",
                    "x-client-request-id" to "override-request"
                )
            )
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
                apiKey = "sk-test",
                sessionId = "session-123",
                cacheRetention = works.resolve.pathfinder.ai.CacheRetention.NONE
            )
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
                "[DONE]"
            )
        )
        val events = api(
            transport
        ).stream(model, context, OpenAiResponsesOptions(apiKey = "sk-test")).toList()
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
            normalizeContext(
                Context(
                    messages = listOf(UserMessage.ofText("hi")),
                    tools = listOf(
                        Tool(
                            "read",
                            "Read a file",
                            buildJsonObject {
                                put("type", "object")
                            }
                        )
                    )
                )
            ),
            works.resolve.pathfinder.ai.SimpleStreamOptions(
                apiKey = "sk-test",
                toolChoice = works.resolve.pathfinder.ai.SimpleToolChoice.None
            )
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

    private fun makeTool(name: String) = Tool(
        name,
        "The $name tool",
        buildJsonObject {
            put("type", "object")
        }
    )

    /** Upstream makeContext(): base tool, then a system message that adds late_tool. */
    private fun deferredContext(tools: List<Tool>): TranscriptContext = normalizeContext(
        Context(
            messages = listOf(
                UserMessage.ofText("Hello", 1),
                AssistantMessage(
                    content = listOf(ToolCall("call_1", "base_tool", JsonObject(emptyMap()))),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-opus-4-6",
                    stopReason = StopReason.TOOL_USE,
                    timestamp = 2
                ),
                ToolResultMessage(
                    toolCallId = "call_1",
                    toolName = "base_tool",
                    content = listOf(TextContent("done")),
                    timestamp = 3
                ),
                SystemMessage(
                    content = emptyList(),
                    toolsAdded = tools.drop(1),
                    timestamp = 3
                ),
                UserMessage.ofText("again", 4)
            ),
            tools = tools.take(1)
        )
    )

    private fun params(model: Model, context: TranscriptContext): JsonObject = buildParams(
        model,
        context,
        OpenAiResponsesOptions(apiKey = "sk-test"),
        getCompat(model),
        CacheRetention.SHORT
    )

    private fun toolNames(json: JsonObject): List<String> =
        json["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun JsonObject.typeName(): String? = this["type"]?.jsonPrimitive?.content

    @Test
    fun `catalog responses models without the strict flag default off`() {
        // The asset omits supportsStrictMode for opencode's responses models;
        // the openai-responses default is false, so no strict key is sent.
        val model = realAsset().getProvider("opencode")!!.models.first {
            it.api == "openai-responses"
        }
        assertNull(model.responsesCompat?.supportsStrictMode)
        assertFalse(getCompat(model).supportsStrictMode)
        val json = params(
            model,
            normalizeContext(
                Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(makeTool("t")))
            )
        )
        assertNull(
            json["tools"]!!.jsonArray.single().jsonObject["strict"]
        )
    }

    @Test
    fun `deferred tools load through additional_tools for gpt-5_4`() {
        val model = realAsset().getModel("openai", "gpt-5.4")!!
        val json =
            params(model, deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))))
        // A later additional_tools item cannot replace a top-level tool, so no tool is declared
        // there.
        assertNull(json["tools"])
        val input = json["input"]!!.jsonArray.map { it.jsonObject }
        val additional = input.filter { it.typeName() == "additional_tools" }
        assertEquals(
            listOf(listOf("base_tool"), listOf("late_tool")),
            additional.map { item ->
                item["tools"]!!.jsonArray.map {
                    it.jsonObject["name"]!!.jsonPrimitive.content
                }
            }
        )
        assertEquals("developer", additional.first()["role"]!!.jsonPrimitive.content)
        val tool = additional.first()["tools"]!!.jsonArray.single().jsonObject
        assertEquals("base_tool", tool["name"]!!.jsonPrimitive.content)
        assertNull(tool["defer_loading"])
        assertTrue(input.none { it.typeName()?.startsWith("tool_search") == true })
    }

    @Test
    fun `additional_tools marker is preserved after the loaded tool is used`() {
        val model = realAsset().getModel("openai", "gpt-5.4")!!
        val baseTool = makeTool("base_tool")
        val lateTool = makeTool("late_tool")
        val context = normalizeContext(
            Context(
                messages = listOf(
                    UserMessage.ofText("Hello", 1),
                    AssistantMessage(
                        content = listOf(ToolCall("call_1", "base_tool", JsonObject(emptyMap()))),
                        api = "anthropic-messages",
                        provider = "anthropic",
                        model = "claude-opus-4-6",
                        stopReason = StopReason.TOOL_USE,
                        timestamp = 2
                    ),
                    ToolResultMessage(
                        toolCallId = "call_1",
                        toolName = "base_tool",
                        content = listOf(TextContent("done")),
                        timestamp = 3
                    ),
                    SystemMessage(
                        content = emptyList(),
                        toolsAdded = listOf(lateTool),
                        timestamp = 3
                    ),
                    AssistantMessage(
                        content = listOf(
                            ToolCall("call_late|fc_late", "late_tool", JsonObject(emptyMap()))
                        ),
                        api = "openai-responses",
                        provider = "openai",
                        model = "gpt-5.4",
                        stopReason = StopReason.TOOL_USE,
                        timestamp = 3
                    ),
                    ToolResultMessage(
                        toolCallId = "call_late|fc_late",
                        toolName = "late_tool",
                        content = listOf(TextContent("done")),
                        timestamp = 3
                    ),
                    UserMessage.ofText("again", 4)
                ),
                tools = listOf(baseTool)
            )
        )
        val json = params(model, context)
        assertNull(json["tools"])
        val input = json["input"]!!.jsonArray.map { it.jsonObject }
        val additionalIndexes = input.indices.filter { input[it].typeName() == "additional_tools" }
        val lateCallIndex = input.indexOfFirst {
            it.typeName() == "function_call" && it["name"]!!.jsonPrimitive.content == "late_tool"
        }
        assertEquals(2, additionalIndexes.size)
        assertTrue(additionalIndexes[0] < additionalIndexes[1])
        assertTrue(additionalIndexes[1] < lateCallIndex)
    }

    @Test
    fun `falls back to client tool search when additional_tools is unsupported`() {
        val model = this.model.copy(
            provider = "openai-proxy",
            responsesCompat = OpenAiResponsesCompat(
                supportsMidConvoSystemMessages = true,
                supportsToolSearch = true
            )
        )
        val json =
            params(model, deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))))
        assertEquals(listOf("base_tool"), toolNames(json))
        val input = json["input"]!!.jsonArray.map { it.jsonObject }
        val call = input.single { it.typeName() == "tool_search_call" }
        val output = input.single { it.typeName() == "tool_search_output" }
        assertEquals("client", call["execution"]!!.jsonPrimitive.content)
        assertEquals("completed", call["status"]!!.jsonPrimitive.content)
        assertEquals(
            call["call_id"]!!.jsonPrimitive.content,
            output["call_id"]!!.jsonPrimitive.content
        )
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
            val json =
                params(model, deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))))
            assertEquals(listOf("base_tool", "late_tool"), toolNames(json), modelId)
            assertTrue(
                json["input"]!!.jsonArray.map { it.jsonObject }.none {
                    it.typeName() ==
                        "tool_search_output"
                },
                modelId
            )
        }
    }

    @Test
    fun `uses the normal tool list when OpenAI tool search is explicitly disabled`() {
        val model = realAsset().getModel("openai", "gpt-5.4")!!
            .copy(provider = "openai-proxy", responsesCompat = OpenAiResponsesCompat())
        val json =
            params(model, deferredContext(listOf(makeTool("base_tool"), makeTool("late_tool"))))
        assertEquals(listOf("base_tool", "late_tool"), toolNames(json))
        assertTrue(
            json["input"]!!.jsonArray.map { it.jsonObject }.none {
                it.typeName() == "tool_search_output" || it.typeName() == "additional_tools"
            }
        )
    }

    @Test
    fun `thinking-level sampling params sit between model and request params`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val sampled = model.copy(
            samplingParams = mapOf(
                "temperature" to JsonPrimitive(1.0),
                "top_p" to JsonPrimitive(0.95)
            ),
            samplingParamsByThinkingLevel = mapOf(
                ModelThinkingLevel.LOW to mapOf(
                    "temperature" to JsonPrimitive(0.6),
                    "top_k" to JsonPrimitive(64)
                )
            )
        )
        api(transport).stream(
            sampled,
            context,
            OpenAiResponsesOptions(
                apiKey = "sk-test",
                reasoningEffort = ModelThinkingLevel.LOW,
                samplingParams = mapOf("top_p" to JsonPrimitive(0.5))
            )
        ).toList()
        val body = body(transport)
        assertEquals(0.6, body["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.5, body["top_p"]!!.jsonPrimitive.content.toDouble())
        assertEquals(64, body["top_k"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `summary-only requests use medium sampling params`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val sampled = model.copy(
            samplingParamsByThinkingLevel = mapOf(
                ModelThinkingLevel.OFF to mapOf("temperature" to JsonPrimitive(0.7)),
                ModelThinkingLevel.MEDIUM to mapOf("temperature" to JsonPrimitive(0.8))
            )
        )
        api(transport).stream(
            sampled,
            context,
            OpenAiResponsesOptions(apiKey = "sk-test", reasoningSummary = "auto")
        ).toList()
        val body = body(transport)
        assertEquals("medium", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(0.8, body["temperature"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `configuration updates keep reasoning effort fixed and append input on level change`() =
        runTest {
            val gpt6 = model.copy(
                id = "gpt-6-luna",
                thinkingLevelMap = ThinkingLevelMap.of(
                    ModelThinkingLevel.OFF to "none",
                    ModelThinkingLevel.LOW to "low",
                    ModelThinkingLevel.MEDIUM to "medium",
                    ModelThinkingLevel.HIGH to "high"
                ),
                responsesCompat = OpenAiResponsesCompat(supportsMidConvoEffort = true)
            )

            suspend fun run(
                messages: List<Message>,
                level: ModelThinkingLevel
            ): Pair<JsonObject, AssistantMessage> {
                val transport = FakeTransport()
                transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
                val events = api(transport).stream(
                    gpt6,
                    normalizeContext(Context(messages = messages)),
                    OpenAiResponsesOptions(apiKey = "sk-test", reasoningEffort = level)
                ).toList()
                return body(transport) to (events.last() as AssistantMessageEvent.Done).message
            }

            val history = mutableListOf<Message>(UserMessage.ofText("one"))
            val (firstBody, firstMessage) = run(history.toList(), ModelThinkingLevel.HIGH)
            history += firstMessage
            history += UserMessage.ofText("two")
            val (secondBody, secondMessage) = run(history.toList(), ModelThinkingLevel.HIGH)
            history += secondMessage
            history += UserMessage.ofText("three")
            val (thirdBody, thirdMessage) = run(history.toList(), ModelThinkingLevel.LOW)

            val update = { effort: String ->
                buildJsonObject {
                    put("type", "configuration_update")
                    put("reasoning", buildJsonObject { put("effort", effort) })
                }
            }
            assertEquals(
                listOf(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", "one")
                                    }
                                )
                            )
                        )
                    },
                    update("high")
                ),
                firstBody["input"]!!.jsonArray.toList()
            )
            for (body in listOf(firstBody, secondBody, thirdBody)) {
                assertEquals(
                    "medium",
                    body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                )
            }
            val firstInput = firstBody["input"]!!.jsonArray.toList()
            val secondInput = secondBody["input"]!!.jsonArray.toList()
            val thirdInput = thirdBody["input"]!!.jsonArray.toList()
            assertEquals(firstInput, secondInput.take(firstInput.size))
            assertEquals(secondInput, thirdInput.take(secondInput.size))
            assertEquals(
                listOf(update("high"), update("high"), update("low")),
                thirdInput.filter {
                    (it as JsonObject)["type"]?.jsonPrimitive?.content == "configuration_update"
                }
            )
            assertEquals(update("low"), thirdInput.last())
            assertEquals(
                listOf("high", "high", "low"),
                listOf(
                    firstMessage,
                    secondMessage,
                    run(history.toList(), ModelThinkingLevel.LOW).second
                )
                    .map { it.providerThinkingLevel }
            )
        }

    @Test
    fun `configuration updates are skipped for sign-in disabled compat and thinking off`() =
        runTest {
            val base = model.copy(
                id = "gpt-6-luna",
                thinkingLevelMap = ThinkingLevelMap.of(
                    ModelThinkingLevel.OFF to "none",
                    ModelThinkingLevel.LOW to "low"
                )
            )

            suspend fun run(
                requestModel: Model,
                apiKey: String,
                level: ModelThinkingLevel?
            ): Pair<JsonObject, AssistantMessage> {
                val transport = FakeTransport()
                transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
                val events = api(transport).stream(
                    requestModel,
                    context,
                    OpenAiResponsesOptions(apiKey = apiKey, reasoningEffort = level)
                ).toList()
                return body(transport) to (events.last() as AssistantMessageEvent.Done).message
            }

            val cases = listOf(
                Triple(
                    base.copy(
                        responsesCompat = OpenAiResponsesCompat(supportsMidConvoEffort = true)
                    ),
                    "chatgpt-access-token",
                    "low"
                ),
                Triple(
                    base.copy(
                        responsesCompat = OpenAiResponsesCompat(supportsMidConvoEffort = false)
                    ),
                    "sk-test",
                    "low"
                ),
                Triple(
                    base.copy(
                        responsesCompat = OpenAiResponsesCompat(supportsMidConvoEffort = true)
                    ),
                    "sk-test",
                    null
                )
            )
            val expectedEfforts = listOf("low", "low", "none")
            for ((index, case) in cases.withIndex()) {
                val level = if (index == 2) null else ModelThinkingLevel.LOW
                val (payload, message) = run(case.first, case.second, level)
                assertEquals(
                    expectedEfforts[index],
                    payload["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                )
                assertTrue(
                    payload["input"]!!.jsonArray.none {
                        (it as JsonObject)["type"]?.jsonPrimitive?.content ==
                            "configuration_update"
                    }
                )
                assertNull(message.providerThinkingLevel)
            }
        }

    @Test
    fun `sign-in with ChatGPT omits request fields that token sharing rejects`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        val explicit = model.copy(
            responsesCompat = OpenAiResponsesCompat(supportsExplicitPromptCacheMode = true)
        )
        api(transport).stream(
            explicit,
            context,
            OpenAiResponsesOptions(
                apiKey = "chatgpt-access-token",
                sessionId = "s1",
                maxTokens = 1000,
                temperature = 0.5,
                cacheRetention = CacheRetention.LONG
            )
        ).toList()
        val body = body(transport)
        assertNull(body["max_output_tokens"])
        assertNull(body["temperature"])
        assertNull(body["prompt_cache_retention"])
        assertNull(body["prompt_cache_options"])
        assertEquals("s1", body["prompt_cache_key"]!!.jsonPrimitive.content)

        val apiKeyTransport = FakeTransport()
        apiKeyTransport.enqueueResponse(sse(*completedChunk().toTypedArray()))
        api(apiKeyTransport).stream(
            explicit,
            context,
            OpenAiResponsesOptions(
                apiKey = "sk-test",
                sessionId = "s1",
                cacheRetention = CacheRetention.LONG
            )
        ).toList()
        assertEquals(
            "30m",
            body(apiKeyTransport)["prompt_cache_options"]!!.jsonObject["ttl"]!!
                .jsonPrimitive.content
        )
    }

    @Test
    fun `ChatGPT usage limit errors link to usage and workspace limits`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            429,
            """{"error":{"code":"subscription_sharing_usage_limit_exceeded",""" +
                """"message":"Usage limit reached.","type":"rate_limit_error"}}"""
        )
        val error = assertIs<AssistantMessageEvent.Error>(
            api(transport).stream(model, context, OpenAiResponsesOptions(apiKey = "sk-test"))
                .toList().last()
        )
        assertTrue(error.error.errorMessage!!.contains("subscription_sharing_usage_limit_exceeded"))
        assertTrue(
            error.error.errorMessage!!.contains(
                "Check your ChatGPT usage: https://chatgpt.com/settings/usage"
            )
        )

        val workspaceTransport = FakeTransport()
        workspaceTransport.enqueueResponse(
            sse(
                """{"type":"response.failed","response":{"id":"resp_failed","status":"failed",
                    "error":{"code":
                        "subscription_sharing_workplace_individual_usage_limit_exceeded",
                        "message":"Usage limit reached."}}}"""
            )
        )
        val workspaceError = assertIs<AssistantMessageEvent.Error>(
            api(workspaceTransport).stream(
                model,
                context,
                OpenAiResponsesOptions(apiKey = "sk-test")
            ).toList().last()
        )
        assertTrue(
            workspaceError.error.errorMessage!!.contains(
                "You've reached your ChatGPT workspace's usage limit."
            )
        )
    }

    @Test
    fun `additional tools prefix grows across a removal and later loads`() {
        val additionalModel = model.copy(
            responsesCompat = OpenAiResponsesCompat(
                supportsMidConvoSystemMessages = true,
                supportsAdditionalTools = true
            )
        )
        val baseTool = makeTool("base_tool")
        val dropTool = makeTool("drop_tool")
        val lateTool = makeTool("late_tool")
        val laterTool = makeTool("later_tool")
        val messages = mutableListOf<Message>(
            SystemMessage(
                content = listOf(TextContent("base prompt")),
                toolsAdded = listOf(baseTool, dropTool),
                timestamp = 0
            ),
            UserMessage.ofText("first", 1)
        )

        fun inputOf(): List<JsonObject> = params(
            additionalModel,
            normalizeContext(Context(messages = messages.toList()))
        )["input"]!!.jsonArray.map { it.jsonObject }

        val inputs = mutableListOf(inputOf())
        messages.add(
            SystemMessage(
                content = emptyList(),
                toolsRemoved = listOf(works.resolve.pathfinder.ai.ToolReference("drop_tool")),
                timestamp = 2
            )
        )
        messages.add(UserMessage.ofText("after removal", 3))
        inputs.add(inputOf())
        messages.add(
            SystemMessage(content = emptyList(), toolsAdded = listOf(lateTool), timestamp = 4)
        )
        messages.add(UserMessage.ofText("after first load", 5))
        inputs.add(inputOf())
        messages.add(
            SystemMessage(content = emptyList(), toolsAdded = listOf(laterTool), timestamp = 6)
        )
        messages.add(UserMessage.ofText("after second load", 7))
        inputs.add(inputOf())

        for (index in 1 until inputs.size) {
            val previous = inputs[index - 1]
            assertEquals(previous, inputs[index].take(previous.size))
        }
        val last = inputs.last()
        assertEquals(
            listOf(
                listOf("base_tool", "drop_tool"),
                listOf("late_tool"),
                listOf("later_tool")
            ),
            last.filter { it.typeName() == "additional_tools" }.map { item ->
                item["tools"]!!.jsonArray.map {
                    it.jsonObject["name"]!!.jsonPrimitive.content
                }
            }
        )
        assertTrue(
            last.any {
                (it["content"] as? JsonPrimitive)?.content?.contains(
                    "no longer available. Do not call them:\n- drop_tool"
                ) == true
            }
        )
        assertEquals(
            listOf(
                "This is an incremental tools update. Previously declared tools remain available " +
                    "unless explicitly marked unavailable. A tool declared again here is " +
                    "available " +
                    "again, and its latest definition replaces the earlier one.\n" +
                    "Still unavailable: drop_tool.",
                "This is an incremental tools update. Previously declared tools remain available " +
                    "unless explicitly marked unavailable. A tool declared again here is " +
                    "available " +
                    "again, and its latest definition replaces the earlier one.\n" +
                    "Still unavailable: drop_tool."
            ),
            last.filter {
                it.typeName() == null && it["role"]?.jsonPrimitive?.content == "developer"
            }
                .map { it["content"]!!.jsonPrimitive.content }
                .filter { it.startsWith("This is an incremental tools update") }
        )
    }

    @Test
    fun `removals are not listed as unavailable before they are announced`() {
        val additionalModel = model.copy(
            responsesCompat = OpenAiResponsesCompat(
                supportsMidConvoSystemMessages = true,
                supportsAdditionalTools = true
            )
        )
        val baseTool = makeTool("base_tool")
        val dropTool = makeTool("drop_tool")
        val lateTool = makeTool("late_tool")
        val payload = params(
            additionalModel,
            normalizeContext(
                Context(
                    messages = listOf(
                        SystemMessage(
                            content = listOf(TextContent("base prompt")),
                            toolsAdded = listOf(baseTool, dropTool),
                            timestamp = 0
                        ),
                        SystemMessage(
                            content = emptyList(),
                            toolsAdded = listOf(lateTool),
                            toolsRemoved = listOf(
                                works.resolve.pathfinder.ai.ToolReference("drop_tool")
                            ),
                            timestamp = 1
                        ),
                        UserMessage.ofText("go", 2)
                    )
                )
            )
        )
        val input = payload["input"]!!.jsonArray.map { it.jsonObject }
        val notice = input.single {
            (it["content"] as? JsonPrimitive)?.content?.contains("incremental tools update") == true
        }
        assertFalse(notice["content"]!!.jsonPrimitive.content.contains("Still unavailable"))
        val tail = input.takeLast(4)
        assertTrue(
            tail[0]["content"]!!.jsonPrimitive.content.contains("incremental tools update")
        )
        assertEquals("additional_tools", tail[1].typeName())
        assertEquals(
            listOf("late_tool"),
            tail[1]["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        )
        assertTrue(
            tail[2]["content"]!!.jsonPrimitive.content.contains(
                "no longer available. Do not call them:\n- drop_tool"
            )
        )
        assertEquals("user", tail[3]["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools declared again stop being listed as unavailable`() {
        val additionalModel = model.copy(
            responsesCompat = OpenAiResponsesCompat(
                supportsMidConvoSystemMessages = true,
                supportsAdditionalTools = true
            )
        )
        val baseTool = makeTool("base_tool")
        val dropTool = makeTool("drop_tool")
        val payload = params(
            additionalModel,
            normalizeContext(
                Context(
                    messages = listOf(
                        SystemMessage(
                            content = listOf(TextContent("base prompt")),
                            toolsAdded = listOf(baseTool, dropTool),
                            timestamp = 0
                        ),
                        SystemMessage(
                            content = emptyList(),
                            toolsRemoved = listOf(
                                works.resolve.pathfinder.ai.ToolReference("drop_tool")
                            ),
                            timestamp = 1
                        ),
                        SystemMessage(
                            content = emptyList(),
                            toolsAdded = listOf(dropTool),
                            timestamp = 2
                        ),
                        UserMessage.ofText("go", 3)
                    )
                )
            )
        )
        val input = payload["input"]!!.jsonArray.map { it.jsonObject }
        val notice = input.single {
            (it["content"] as? JsonPrimitive)?.content?.contains("incremental tools update") ==
                true
        }
        assertFalse(notice["content"]!!.jsonPrimitive.content.contains("Still unavailable"))
        assertEquals(
            listOf("drop_tool"),
            input.last { it.typeName() == "additional_tools" }["tools"]!!.jsonArray.map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
        )
    }
}
