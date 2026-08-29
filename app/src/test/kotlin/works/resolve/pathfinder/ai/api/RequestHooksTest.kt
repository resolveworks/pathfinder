package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.core.ProviderResponse
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.NoWebSocketTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Request-hook tests for the ported pi `onPayload`/`onResponse` hooks and
 * `samplingParams` (packages/ai/src/types.ts:145-149, :184-193): per-adapter
 * presence mirrors upstream exactly (see each test), including the negative
 * assertions for adapters whose upstream buildParams never applies
 * samplingParams (anthropic, google, mistral, codex) and the adapters whose
 * upstream never invokes onResponse (google) or fires it before the !ok check
 * (mistral, codex).
 */
class RequestHooksTest {

    private val retry = ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 })

    private fun bodyOf(transport: FakeTransport, index: Int = 0): JsonObject {
        val request = transport.requests[index]
        val text = if (request.headers["content-encoding"] == "zstd") {
            com.github.luben.zstd.ZstdInputStream(request.body.inputStream()).readBytes().decodeToString()
        } else {
            request.body.decodeToString()
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun enqueueMultiHeaderResponse(transport: FakeTransport, chunks: List<String>) {
        transport.outcomes.add {
            val events = flow { chunks.forEach { emit(works.resolve.pathfinder.ai.transport.SseEvent(it)) } }
            TransportResponse(
                status = 200,
                headers = mapOf(
                    "content-type" to listOf("text/event-stream"),
                    "x-multi" to listOf("a", "b"),
                ),
                events = events,
            )
        }
    }

    // -----------------------------------------------------------------------
    // openai-completions
    // -----------------------------------------------------------------------

    @Test
    fun `completions onPayload observes the serialized payload and replaces the wire body`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        val model = works.resolve.pathfinder.ai.testing.TestCatalogs.GLM_5_2
        val context = Context(messages = listOf(UserMessage.ofText("hi")))
        val seen = mutableListOf<JsonObject>()
        val events = OpenAiCompletionsApi(transport, retry).streamSimple(
            model,
            context,
            SimpleStreamOptions(
                apiKey = "k",
                temperature = 0.5,
                onPayload = { payload, m ->
                    assertEquals(model, m)
                    seen.add(payload)
                    JsonObject(payload.toMap() + ("top_k" to JsonPrimitive(4)))
                },
            ),
        ).toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        // The hook saw the fully built params (pre-replacement)...
        assertEquals(0.5, seen.single()["temperature"]?.jsonPrimitive?.doubleOrNull)
        // ...and the replacement reached the wire.
        assertEquals(4, bodyOf(transport)["top_k"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `completions onPayload null return keeps the payload`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        OpenAiCompletionsApi(transport, retry).streamSimple(
            works.resolve.pathfinder.ai.testing.TestCatalogs.GLM_5_2,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(apiKey = "k", onPayload = { _, _ -> null }),
        ).toList()
        assertNull(bodyOf(transport)["top_k"])
    }

    @Test
    fun `completions onResponse receives status and flattened multi-value headers`() = runTest {
        val transport = FakeTransport()
        enqueueMultiHeaderResponse(
            transport,
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        val responses = mutableListOf<ProviderResponse>()
        OpenAiCompletionsApi(transport, retry).streamSimple(
            works.resolve.pathfinder.ai.testing.TestCatalogs.GLM_5_2,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(
                apiKey = "k",
                onResponse = { response, _ -> responses.add(response) },
            ),
        ).toList()
        assertEquals(200, responses.single().status)
        // pi's headersToRecord joins repeated values with ", ".
        assertEquals("a, b", responses.single().headers["x-multi"])
        assertEquals("text/event-stream", responses.single().headers["content-type"])
    }

    @Test
    fun `completions onResponse is not invoked for non-2xx`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(500, "boom")
        val responses = mutableListOf<ProviderResponse>()
        OpenAiCompletionsApi(transport, retry).streamSimple(
            works.resolve.pathfinder.ai.testing.TestCatalogs.GLM_5_2,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(apiKey = "k", onResponse = { r, _ -> responses.add(r) }),
        ).toList()
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `completions samplingParams override named fields and merge over model defaults`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        val model = works.resolve.pathfinder.ai.testing.TestCatalogs.GLM_5_2.copy(
            samplingParams = mapOf("top_p" to JsonPrimitive(0.9), "repetition_penalty" to JsonPrimitive(2.0)),
        )
        OpenAiCompletionsApi(transport, retry).streamSimple(
            model,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(
                apiKey = "k",
                temperature = 0.5,
                samplingParams = mapOf("top_p" to JsonPrimitive(0.5)),
            ),
        ).toList()
        val body = bodyOf(transport)
        // Request key wins per key; untouched model default passes through.
        assertEquals(0.5, body["top_p"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(2.0, body["repetition_penalty"]?.jsonPrimitive?.doubleOrNull)
        // Named fields stay intact unless explicitly overridden.
        assertEquals(0.5, body["temperature"]?.jsonPrimitive?.doubleOrNull)
    }

    @Test
    fun `completions omits samplingParams keys when absent`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        OpenAiCompletionsApi(transport, retry).streamSimple(
            works.resolve.pathfinder.ai.testing.TestCatalogs.GLM_5_2,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(apiKey = "k"),
        ).toList()
        assertNull(bodyOf(transport)["top_p"])
        assertNull(bodyOf(transport)["repetition_penalty"])
    }

    // -----------------------------------------------------------------------
    // anthropic-messages
    // -----------------------------------------------------------------------

    private val claude = Model(
        id = "claude-sonnet-4-5",
        name = "Claude Sonnet 4.5",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "https://api.anthropic.com",
        contextWindow = 200_000,
        maxTokens = 64_000,
    )

    private val anthropicContext = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun anthropicEvents() = listOf(
        "message_start" to
            """{"type":"message_start","message":{"id":"msg_test","model":"claude-sonnet-4-5","usage":{"input_tokens":3,"output_tokens":0}}}""",
        "content_block_start" to
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun `anthropic onPayload and onResponse fire and samplingParams are ignored`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(anthropicEvents())
        val seen = mutableListOf<JsonObject>()
        val responses = mutableListOf<ProviderResponse>()
        AnthropicMessagesApi(transport, retry).streamSimple(
            claude,
            anthropicContext,
            SimpleStreamOptions(
                apiKey = "k",
                maxTokens = 100,
                onPayload = { payload, _ -> seen.add(payload); null },
                onResponse = { r, _ -> responses.add(r) },
                samplingParams = mapOf("top_k" to JsonPrimitive(9)),
            ),
        ).toList()
        // The hook saw the params object that reached the wire unchanged.
        assertEquals(bodyOf(transport), seen.single())
        assertEquals(200, responses.single().status)
        // Upstream anthropic-messages buildParams never applies samplingParams.
        assertNull(bodyOf(transport)["top_k"])
    }

    @Test
    fun `anthropic onPayload replacement reaches the wire`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(anthropicEvents())
        AnthropicMessagesApi(transport, retry).streamSimple(
            claude,
            anthropicContext,
            SimpleStreamOptions(
                apiKey = "k",
                onPayload = { payload, _ -> JsonObject(payload.toMap() + ("metadata" to JsonPrimitive("x"))) },
            ),
        ).toList()
        assertEquals("x", bodyOf(transport)["metadata"]?.jsonPrimitive?.content)
    }

    @Test
    fun `anthropic onResponse is not invoked for non-2xx`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(500, "boom")
        val responses = mutableListOf<ProviderResponse>()
        AnthropicMessagesApi(transport, retry).streamSimple(
            claude,
            anthropicContext,
            SimpleStreamOptions(apiKey = "k", onResponse = { r, _ -> responses.add(r) }),
        ).toList()
        assertTrue(responses.isEmpty())
    }

    // -----------------------------------------------------------------------
    // google-generative-ai
    // -----------------------------------------------------------------------

    @Test
    fun `google onPayload fires, onResponse never fires, samplingParams are ignored`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                    "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}""",
            ),
        )
        val model = Model(
            id = "gemini-2.5-flash",
            name = "gemini-2.5-flash",
            api = "google-generative-ai",
            provider = "google",
            baseUrl = "",
            reasoning = true,
            input = listOf(InputModality.TEXT, InputModality.IMAGE),
            contextWindow = 128_000,
            maxTokens = 8_192,
        )
        val seen = mutableListOf<JsonObject>()
        val responses = mutableListOf<ProviderResponse>()
        GoogleGenerativeAiApi(transport, retry).streamSimple(
            model,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(
                apiKey = "k",
                onPayload = { payload, _ -> seen.add(payload); null },
                onResponse = { r, _ -> responses.add(r) },
                samplingParams = mapOf("top_k" to JsonPrimitive(9)),
            ),
        ).toList()
        assertEquals(bodyOf(transport), seen.single())
        // pi's google-generative-ai has no onResponse call site.
        assertTrue(responses.isEmpty())
        assertNull(bodyOf(transport)["top_k"])
    }

    // -----------------------------------------------------------------------
    // mistral-conversations
    // -----------------------------------------------------------------------

    private val mistral = Model(
        id = "mistral-large-latest",
        name = "mistral-large-latest",
        api = "mistral-conversations",
        provider = "mistral",
        baseUrl = "https://api.mistral.ai",
        contextWindow = 131_000,
        maxTokens = 131_000,
    )

    private val mistralContext = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun mistralTerminal() = """
        {"id":"mistral-response-id","model":"${mistral.id}",
         "choices":[{"index":0,"finish_reason":"stop","delta":{}}],
         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
    """.trimIndent()

    @Test
    fun `mistral onPayload fires and samplingParams are ignored`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(mistralTerminal(), "[DONE]"))
        val seen = mutableListOf<JsonObject>()
        MistralConversationsApi(transport).streamSimple(
            mistral,
            mistralContext,
            SimpleStreamOptions(
                apiKey = "k",
                onPayload = { payload, _ -> seen.add(payload); null },
                samplingParams = mapOf("top_k" to JsonPrimitive(9)),
            ),
        ).toList()
        assertEquals(bodyOf(transport), seen.single())
        assertNull(bodyOf(transport)["top_k"])
    }

    @Test
    fun `mistral onResponse fires for 2xx and for non-2xx`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(500, "boom")
        val responses = mutableListOf<ProviderResponse>()
        MistralConversationsApi(transport).streamSimple(
            mistral,
            mistralContext,
            SimpleStreamOptions(apiKey = "k", onResponse = { r, _ -> responses.add(r) }),
        ).toList()
        // pi fires onResponse before the !ok check, so error responses hit the hook.
        assertEquals(500, responses.single().status)
    }

    // -----------------------------------------------------------------------
    // openai-responses
    // -----------------------------------------------------------------------

    private val gpt = Model(
        id = "gpt-5-mini",
        name = "GPT-5 Mini",
        api = "openai-responses",
        provider = "openai",
        baseUrl = "https://api.openai.com/v1",
        reasoning = true,
        cost = ModelCost(input = 1.0, output = 2.0),
        contextWindow = 400_000,
        maxTokens = 128_000,
        responsesCompat = OpenAiResponsesCompat(),
    )

    private fun responsesTerminal() = sse(
        """{"type":"response.output_item.added","output_index":0,
            "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
        """{"type":"response.completed","response":{"id":"resp_1","status":"completed",
            "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        "[DONE]",
    )

    @Test
    fun `responses onPayload and onResponse fire and samplingParams override named fields`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(responsesTerminal())
        val seen = mutableListOf<JsonObject>()
        val responses = mutableListOf<ProviderResponse>()
        OpenAiResponsesApi(transport, retry).streamSimple(
            gpt,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(
                apiKey = "k",
                maxTokens = 512,
                onPayload = { payload, _ -> seen.add(payload); null },
                onResponse = { r, _ -> responses.add(r) },
                samplingParams = mapOf("max_output_tokens" to JsonPrimitive(7), "min_p" to JsonPrimitive(0.1)),
            ),
        ).toList()
        assertEquals(200, responses.single().status)
        val body = bodyOf(transport)
        // The hook saw the params before the hook ran (post-samplingParams).
        assertEquals(body, seen.single())
        // samplingParams merged last: named field overridden, custom key added.
        assertEquals(7, body["max_output_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0.1, body["min_p"]?.jsonPrimitive?.doubleOrNull)
    }

    // -----------------------------------------------------------------------
    // azure-openai-responses
    // -----------------------------------------------------------------------

    @Test
    fun `azure onPayload and onResponse fire and samplingParams override named fields`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"type":"response.completed","response":{"id":"r1","status":"completed",
                    "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
                "[DONE]",
            ),
        )
        val model = Model(
            id = "gpt-4o-mini",
            name = "GPT-4o mini",
            api = "azure-openai-responses",
            provider = "azure-openai-responses",
            baseUrl = "https://my-resource.openai.azure.com/openai/v1",
            contextWindow = 128_000,
            maxTokens = 16_384,
            responsesCompat = OpenAiResponsesCompat(),
        )
        val seen = mutableListOf<JsonObject>()
        val responses = mutableListOf<ProviderResponse>()
        AzureOpenAiResponsesApi(transport, retry).streamSimple(
            model,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(
                apiKey = "k",
                temperature = 0.5,
                onPayload = { payload, _ -> seen.add(payload); null },
                onResponse = { r, _ -> responses.add(r) },
                samplingParams = mapOf("temperature" to JsonPrimitive(0.9)),
            ),
        ).toList()
        assertEquals(200, responses.single().status)
        val body = bodyOf(transport)
        assertEquals(body, seen.single())
        assertEquals(0.9, body["temperature"]?.jsonPrimitive?.doubleOrNull)
    }

    // -----------------------------------------------------------------------
    // openai-codex-responses
    // -----------------------------------------------------------------------

    @Test
    fun `codex onPayload fires, onResponse fires for non-2xx, samplingParams are ignored`() = runTest {
        val transport = FakeTransport()
        // 429 with a terminal usage-limit body: no retry, hook fires once.
        transport.enqueueError(429, """{"error":"GoUsageLimitError"}""")
        val model = Model(
            id = "gpt-5.1-codex",
            name = "GPT-5.1 Codex",
            api = "openai-codex-responses",
            provider = "openai-codex",
            baseUrl = "https://chatgpt.com/backend-api",
            reasoning = true,
            contextWindow = 400_000,
            maxTokens = 128_000,
            responsesCompat = OpenAiResponsesCompat(supportsStrictMode = true),
        )
        val seen = mutableListOf<JsonObject>()
        val responses = mutableListOf<ProviderResponse>()
        val events = OpenAICodexResponsesApi(transport, clock = FakeClock(0L), webSocketTransport = NoWebSocketTransport).streamSimple(
            model,
            Context(systemPrompt = "You are Codex.", messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(
                apiKey = jwt("acc-1"),
                onPayload = { payload, _ -> seen.add(payload); null },
                onResponse = { r, _ -> responses.add(r) },
                samplingParams = mapOf("top_k" to JsonPrimitive(9)),
            ),
        ).toList()
        assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(bodyOf(transport), seen.single())
        // pi fires onResponse before the ok check, so non-2xx hits the hook.
        assertEquals(429, responses.single().status)
        assertNull(bodyOf(transport)["top_k"])
    }

    // -----------------------------------------------------------------------
    // redaction
    // -----------------------------------------------------------------------

    @Test
    fun `hook presence and sampling keys never leak payload or parameter values into toString`() {
        val secret = "sk-SECRET-9f8e7d6c5b4a"
        val options = SimpleStreamOptions(
            apiKey = secret,
            samplingParams = mapOf("x-custom" to JsonPrimitive(secret)),
            onPayload = { payload, _ -> payload },
            onResponse = { _, _ -> },
        )
        val rendered = options.toString()
        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("x-custom"))
        assertTrue(rendered.contains("onPayload=true"))
        assertTrue(rendered.contains("onResponse=true"))
        // OpenAiCompletionsOptions mirrors the same redaction.
        val completionsRendered = options.toStreamOptions(null).toString()
        assertFalse(completionsRendered.contains(secret))
        assertTrue(completionsRendered.contains("x-custom"))
    }
}
