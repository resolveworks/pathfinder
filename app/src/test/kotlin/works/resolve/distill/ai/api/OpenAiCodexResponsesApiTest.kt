package works.resolve.distill.ai.api

import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import works.resolve.distill.ai.core.AssistantMessageEvent
import works.resolve.distill.ai.core.Context
import works.resolve.distill.ai.core.Model
import works.resolve.distill.ai.core.ModelCost
import works.resolve.distill.ai.core.ModelThinkingLevel
import works.resolve.distill.ai.core.OpenAiResponsesCompat
import works.resolve.distill.ai.core.StopReason
import works.resolve.distill.ai.core.TextContent
import works.resolve.distill.ai.core.ThinkingLevelMap
import works.resolve.distill.ai.core.UserMessage
import works.resolve.distill.ai.testing.FakeTransport
import works.resolve.distill.ai.testing.sse
import works.resolve.distill.ai.transport.NetworkException
import works.resolve.distill.ai.transport.ProviderHttpException

/**
 * Canned tests for [OpenAICodexResponsesApi] (SSE transport), ported
 * alongside pi's openai-codex-responses.ts (URL/headers/body shape, Codex
 * event normalization, end_turn, retry/usage-limit handling from
 * openai-codex-stream.test.ts).
 */
class OpenAiCodexResponsesApiTest {

    private val model = Model(
        id = "gpt-5.1-codex",
        name = "GPT-5.1 Codex",
        api = "openai-codex-responses",
        provider = "openai-codex",
        baseUrl = "https://chatgpt.com/backend-api",
        reasoning = true,
        cost = ModelCost(input = 1.0, output = 2.0),
        contextWindow = 400_000,
        maxTokens = 128_000,
        responsesCompat = OpenAiResponsesCompat(supportsStrictMode = true),
    )

    private val context = Context(systemPrompt = "You are Codex.", messages = listOf(UserMessage.ofText("hi")))

    private val apiKey = jwt("acc-123")

    private fun api(transport: FakeTransport, calls: MutableList<Long> = mutableListOf()) =
        OpenAICodexResponsesApi(
            transport,
            nowMs = { 0L },
            sleep = { calls.add(it) },
        )

    private fun bodyOf(transport: FakeTransport, index: Int = 0) =
        responsesJson.parseToJsonElement(transport.requests[index].body.decodeToString()).jsonObject

    // -----------------------------------------------------------------------
    // URL / auth helpers
    // -----------------------------------------------------------------------

    @Test
    fun `codex urls resolve from base url variants`() {
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            resolveCodexUrl(null),
        )
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            resolveCodexUrl("https://chatgpt.com/backend-api/"),
        )
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            resolveCodexUrl("https://chatgpt.com/backend-api/codex"),
        )
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            resolveCodexUrl("https://chatgpt.com/backend-api/codex/responses/"),
        )
        assertEquals(
            "https://proxy.example/codex/responses",
            resolveCodexUrl("https://proxy.example"),
        )
    }

    @Test
    fun `account id extracts from the jwt claim`() {
        assertEquals("acc-123", extractAccountId(apiKey))
        assertTrue(
            kotlin.test.assertFailsWith<IllegalStateException> { extractAccountId("not-a-jwt") }
                .message!!.contains("Failed to extract accountId"),
        )
    }

    // -----------------------------------------------------------------------
    // Request shape
    // -----------------------------------------------------------------------

    private fun doneEvents(text: String = "ok") = listOf(
        """{"type":"response.output_item.added","output_index":0,
            "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
        """{"type":"response.output_text.delta","output_index":0,"delta":"$text"}""",
        """{"type":"response.done","response":{"id":"resp_1","status":"completed","end_turn":true,
            "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        "[DONE]",
    )

    @Test
    fun `requests carry codex headers instructions and body defaults`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*doneEvents().toTypedArray()))
        val events = api(transport).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, sessionId = "session-1"),
        ).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        val request = transport.requests.single()
        assertEquals("https://chatgpt.com/backend-api/codex/responses", request.url)
        assertEquals("Bearer $apiKey", request.headers["Authorization"])
        assertEquals("acc-123", request.headers["chatgpt-account-id"])
        assertEquals("pi", request.headers["originator"])
        assertEquals("responses=experimental", request.headers["OpenAI-Beta"])
        assertEquals("text/event-stream", request.headers["accept"])
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("session-1", request.headers["session-id"])
        assertEquals("session-1", request.headers["x-client-request-id"])

        val body = bodyOf(transport)
        assertEquals("gpt-5.1-codex", body["model"]!!.jsonPrimitive.content)
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("You are Codex.", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("low", body["text"]!!.jsonObject["verbosity"]!!.jsonPrimitive.content)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(true, body["parallel_tool_calls"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("session-1", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `default instructions apply when no system prompt`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*doneEvents().toTypedArray()))
        api(transport).stream(
            model,
            context.copy(systemPrompt = null),
            OpenAICodexResponsesOptions(apiKey = apiKey),
        ).toList()
        assertEquals(
            "You are a helpful assistant.",
            bodyOf(transport)["instructions"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `reasoning effort maps through the codex thinking level map`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*doneEvents().toTypedArray()))
        api(transport).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, reasoningEffort = works.resolve.distill.ai.core.ModelThinkingLevel.HIGH),
        ).toList()
        val body = bodyOf(transport)
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("auto", body["reasoning"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `explicit unsupported off level omits raw reasoning options`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*doneEvents().toTypedArray()))
        api(transport).stream(
            model.copy(thinkingLevelMap = ThinkingLevelMap.of(ModelThinkingLevel.OFF to null)),
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, reasoningEffort = ModelThinkingLevel.OFF),
        ).toList()
        assertNull(bodyOf(transport)["reasoning"])
    }

    @Test
    fun `response done normalizes to completed with end turn and usage`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*doneEvents("answer").toTypedArray()))
        val events = api(transport).stream(model, context, OpenAICodexResponsesOptions(apiKey = apiKey)).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        assertEquals(true, done.message.endTurn)
        assertEquals("resp_1", done.message.responseId)
        assertEquals("answer", (done.message.content.single() as TextContent).text)
        assertEquals(10, done.message.usage.input)
        assertEquals(5, done.message.usage.output)
    }

    @Test
    fun `unknown response done statuses are dropped`() = runTest {
        // A bogus status must not be mapped; the completed terminal event that
        // follows still finalizes the message.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"type":"response.done","response":{"id":"r","status":"bogus"}}""",
                """{"type":"response.completed","response":{"id":"r","status":"completed"}}""",
            ),
        )
        val events = api(transport).stream(model, context, OpenAICodexResponsesOptions(apiKey = apiKey)).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
    }

    @Test
    fun `codex error events surface code and message`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"type":"error","code":"usage_limit_reached","message":"limit"}"""),
        )
        val events = api(transport).stream(model, context, OpenAICodexResponsesOptions(apiKey = apiKey)).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("Codex error: limit", error.error.errorMessage)
    }

    @Test
    fun `response failed events throw the provider error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"type":"response.failed","response":{"error":{"code":"x","message":"boom"}}}"""),
        )
        val events = api(transport).stream(model, context, OpenAICodexResponsesOptions(apiKey = apiKey)).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("boom", error.error.errorMessage)
    }

    @Test
    fun `malformed sse json is a protocol error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("not json"))
        val events = api(transport).stream(model, context, OpenAICodexResponsesOptions(apiKey = apiKey)).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertTrue(error.error.errorMessage!!.startsWith("Invalid Codex SSE JSON"))
    }

    @Test
    fun `missing api key is an error without a request`() = runTest {
        val transport = FakeTransport()
        val events = api(transport).stream(model, context, OpenAICodexResponsesOptions()).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals("No API key for provider: openai-codex", error.error.errorMessage)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `invalid jwt fails with the account id error`() = runTest {
        val transport = FakeTransport()
        val events = api(transport).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = "not-a-jwt"),
        ).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals("Failed to extract accountId from token", error.error.errorMessage)
    }

    // -----------------------------------------------------------------------
    // Retry behavior (pi's SSE retry loop)
    // -----------------------------------------------------------------------

    @Test
    fun `terminal usage limits on 429 are not retried and get friendly text`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            429,
            """{"error":{"code":"usage_limit_reached","message":"limit","plan_type":"Plus","resets_at":1893456000}}""",
        )
        val delays = mutableListOf<Long>()
        val events = api(transport, delays).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, maxRetries = 3),
        ).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertTrue(error.error.errorMessage!!.startsWith("You have hit your ChatGPT usage limit (plus plan)."))
        assertEquals(1, transport.requests.size)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `transient 500 retries with exponential backoff then succeeds`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(500, "upstream error")
        transport.enqueueResponse(sse(*doneEvents().toTypedArray()))
        val delays = mutableListOf<Long>()
        val events = api(transport, delays).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, maxRetries = 1),
        ).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(listOf(1000L), delays)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `server-requested retry delays are honored and validated`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            429,
            "rate limit",
            headers = mapOf("retry-after-ms" to listOf("1500")),
        )
        transport.enqueueResponse(sse(*doneEvents().toTypedArray()))
        val delays = mutableListOf<Long>()
        val events = api(transport, delays).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, maxRetries = 1),
        ).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(listOf(1500L), delays)

        // Oversized server delays fail instead of sleeping.
        val transport2 = FakeTransport()
        transport2.enqueueError(
            429,
            "rate limit",
            headers = mapOf("retry-after" to listOf("120")),
        )
        val delays2 = mutableListOf<Long>()
        val events2 = api(transport2, delays2).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, maxRetries = 1, maxRetryDelayMs = 60_000),
        ).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events2.last())
        assertTrue(error.error.errorMessage!!.contains("retry delay"))
        assertTrue(delays2.isEmpty())
    }

    @Test
    fun `network errors are retryable`() = runTest {
        val transport = FakeTransport()
        transport.outcomes.add { throw NetworkException(java.io.IOException("reset")) }
        transport.enqueueResponse(sse(*doneEvents().toTypedArray()))
        val delays = mutableListOf<Long>()
        val events = api(transport, delays).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, maxRetries = 1),
        ).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(listOf(1000L), delays)
    }

    @Test
    fun `non retryable errors surface the parsed provider message`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(400, """{"error":{"code":"bad","message":"nope"}}""")
        val events = api(transport).stream(model, context, OpenAICodexResponsesOptions(apiKey = apiKey)).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("nope", error.error.errorMessage)
    }

    // -----------------------------------------------------------------------
    // Service tier resolution
    // -----------------------------------------------------------------------

    @Test
    fun `default service tier resolves to the requested flex or priority tier`() {
        assertEquals("flex", resolveCodexServiceTier("default", "flex"))
        assertEquals("priority", resolveCodexServiceTier("default", "priority"))
        assertEquals("flex", resolveCodexServiceTier("flex", null))
        assertEquals("priority", resolveCodexServiceTier("priority", "flex"))
        assertNull(resolveCodexServiceTier(null, null))
    }

    @Test
    fun `resolved priority tier doubles cost`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"type":"response.done","response":{"id":"r","status":"completed",
                    "service_tier":"default",
                    "usage":{"input_tokens":1000,"output_tokens":1000,"total_tokens":2000}}}""",
            ),
        )
        val events = api(transport).stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = apiKey, serviceTier = "priority"),
        ).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        // 1000 input * 1 + 1000 output * 2 per million, doubled.
        assertEquals(0.002, done.message.usage.cost.input, 1e-9)
        assertEquals(0.004, done.message.usage.cost.output, 1e-9)
        assertEquals(0.006, done.message.usage.cost.total, 1e-9)
    }
}

/** Builds a minimal unsigned JWT carrying the ChatGPT account id claim. */
internal fun jwt(accountId: String): String {
    val encode: (String) -> String = { text ->
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    }
    val payload =
        """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
    return "${encode("""{"alg":"none"}""")}.${encode(payload)}.sig"
}
