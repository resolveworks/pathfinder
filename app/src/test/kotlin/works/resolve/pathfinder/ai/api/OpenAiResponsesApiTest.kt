package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.testing.FakeClock
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
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.core.SessionAffinityFormat
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingLevelMap
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry

/**
 * Canned transport-level tests for [OpenAiResponsesApi], ported alongside pi's
 * openai-responses.ts (compat/payload/header behavior from
 * openai-responses-compat.test.ts and the e2e cache-affinity tests).
 */
class OpenAiResponsesApiTest {

    private val model = Model(
        id = "gpt-5-mini",
        name = "GPT-5 Mini",
        api = "openai-responses",
        provider = "openai",
        baseUrl = "https://api.openai.com/v1",
        reasoning = true,
        input = listOf(works.resolve.pathfinder.ai.core.InputModality.TEXT),
        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.25, cacheWrite = 0.5),
        contextWindow = 400_000,
        maxTokens = 128_000,
        responsesCompat = OpenAiResponsesCompat(),
    )

    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun api(transport: FakeTransport) = OpenAiResponsesApi(
        transport,
        ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }),
        nowMs = { 1_770_000_000_000L },
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
                cacheRetention = works.resolve.pathfinder.ai.core.CacheRetention.NONE,
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
                cacheRetention = works.resolve.pathfinder.ai.core.CacheRetention.LONG,
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
        // Start arrives before block events.
        assertIs<AssistantMessageEvent.Start>(events.first())
    }
}
