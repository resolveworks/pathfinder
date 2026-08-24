package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.InputModality
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ThinkingContent
import works.resolve.aletheia.ai.core.ThinkingLevel
import works.resolve.aletheia.ai.core.ToolCall
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.testing.FakeTransport
import works.resolve.aletheia.ai.testing.sse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Canned streaming tests for the Google Generative AI adapter, ported from
 * pi's google-generative-ai.ts stream loop (exercised upstream via
 * test/google-thinking-signature.test.ts, google-raw-stop-reason.test.ts,
 * and the SDK-mocked adapter tests). No network or live credentials.
 */
class GoogleGenerativeAiStreamTest {

    private val model = geminiModel()
    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun geminiModel(
        id: String = "gemini-2.5-flash",
        baseUrl: String = "",
    ) = Model(
        id = id, name = id, api = "google-generative-ai", provider = "google",
        baseUrl = baseUrl, reasoning = true, input = listOf(InputModality.TEXT, InputModality.IMAGE),
        contextWindow = 128000, maxTokens = 8192,
    )

    private fun api(transport: FakeTransport) = GoogleGenerativeAiApi(
        transport,
        works.resolve.aletheia.ai.utils.ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 }),
        nowMs = { 1_770_000_000_000L },
    )

    private suspend fun events(transport: FakeTransport) = api(transport)
        .stream(model, context, GoogleGenerativeAiApi.GoogleOptions(apiKey = "test-key"))
        .toList()

    @Test
    fun `streams text with start, delta, end, done ordering`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]},"finishReason":"STOP"}],
                    "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"totalTokenCount":12},
                    "responseId":"resp-1"}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]}}],
                    "usageMetadata":{"promptTokenCount":10,"cachedContentTokenCount":4,
                    "candidatesTokenCount":4,"thoughtsTokenCount":2,"totalTokenCount":16}}""",
            ),
        )
        val events = events(transport)

        assertIs<AssistantMessageEvent.Start>(events.first())
        val delta = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("Hel", "lo"), delta.map { it.delta })
        assertEquals(0, delta.first().contentIndex)
        val textEnd = events.filterIsInstance<AssistantMessageEvent.TextEnd>().single()
        assertEquals("Hello", textEnd.content)

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        // usageMetadata: input = prompt - cached, output = candidates + thoughts,
        // cacheRead = cached, reasoning = thoughts; total straight from the wire.
        assertEquals(6, done.message.usage.input)
        assertEquals(6, done.message.usage.output)
        assertEquals(4, done.message.usage.cacheRead)
        assertEquals(2, done.message.usage.reasoning)
        assertEquals(16, done.message.usage.totalTokens)
        assertEquals("resp-1", done.message.responseId)
        assertEquals(listOf(TextContent("Hello")), done.message.content)
    }

    @Test
    fun `thought parts stream as interleaved thinking and text blocks`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[
                    {"text":"thinking hard","thought":true,"thoughtSignature":"AAAAAAAAAAAAAAAAAAAAAA=="},
                    {"text":"answer"},
                    {"text":" more","thought":true}
                    ]},"finishReason":"STOP"}]}""",
            ),
        )
        val events = events(transport)
        val thinkingStarts = events.filterIsInstance<AssistantMessageEvent.ThinkingStart>()
        val thinkingEnds = events.filterIsInstance<AssistantMessageEvent.ThinkingEnd>()
        val textEnd = events.filterIsInstance<AssistantMessageEvent.TextEnd>().single()
        // Blocks: thinking(0), text(1), thinking(2); each start/end pairs up.
        assertEquals(listOf(0, 2), thinkingStarts.map { it.contentIndex })
        assertEquals("thinking hard", thinkingEnds[0].content)
        assertEquals(0, thinkingEnds[0].contentIndex)
        assertEquals(" more", thinkingEnds[1].content)
        assertEquals(2, thinkingEnds[1].contentIndex)
        assertEquals("answer", textEnd.content)
        assertEquals(1, textEnd.contentIndex)
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val content = done.message.content
        assertEquals(3, content.size)
        assertEquals("thinking hard", (content[0] as ThinkingContent).thinking)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", (content[0] as ThinkingContent).thinkingSignature)
        assertEquals("answer", (content[1] as TextContent).text)
        assertEquals(" more", (content[2] as ThinkingContent).thinking)
    }

    @Test
    fun `functionCall parts become complete tool calls with generated ids`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[
                    {"text":"let me check"},
                    {"functionCall":{"name":"bash","args":{"command":"ls"}}}
                    ]},"finishReason":"STOP"}]}""",
            ),
        )
        val events = events(transport)
        val toolEnd = events.filterIsInstance<AssistantMessageEvent.ToolCallEnd>().single()
        val call = toolEnd.toolCall
        assertTrue(call.id.startsWith("bash_"), "generated id was ${call.id}")
        assertEquals("bash", call.name)
        assertEquals("""{"command":"ls"}""", call.arguments)
        val toolDelta = events.filterIsInstance<AssistantMessageEvent.ToolCallDelta>().single()
        assertEquals("""{"command":"ls"}""", toolDelta.delta)

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        // stop upgrade: STOP with tool calls becomes TOOL_USE (upstream rule).
        assertEquals(StopReason.TOOL_USE, done.reason)
        assertEquals("STOP", done.message.rawStopReason)
    }

    @Test
    fun `provided duplicate tool call ids are regenerated`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[
                    {"functionCall":{"id":"dup","name":"a","args":{}}},
                    {"functionCall":{"id":"dup","name":"b","args":{}}}
                    ]},"finishReason":"STOP"}]}""",
            ),
        )
        val events = events(transport)
        val calls = events.filterIsInstance<AssistantMessageEvent.ToolCallEnd>().map { it.toolCall }
        assertEquals(2, calls.size)
        assertEquals("dup", calls[0].id)
        assertTrue(calls[1].id.startsWith("b_"))
    }

    @Test
    fun `error finish reason terminates with an error event carrying rawStopReason`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        val events = events(transport)
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals("Provider stopped with: SAFETY", error.error.errorMessage)
        assertEquals("SAFETY", error.error.rawStopReason)
    }

    @Test
    fun `stream without finish reason is an error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""))
        val events = events(transport)
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("Google stream ended without a finish reason", error.error.errorMessage)
    }

    @Test
    fun `missing api key is a terminal error event`() = runTest {
        val transport = FakeTransport()
        val events = api(transport)
            .stream(model, context, GoogleGenerativeAiApi.GoogleOptions())
            .toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals("No API key for provider: google", error.error.errorMessage)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `http error body is formatted from the google error envelope`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            400,
            """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}""",
        )
        val events = events(transport)
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue("Provider returned HTTP 400" in error.error.errorMessage!!)
        assertTrue("API key not valid" in error.error.errorMessage!!)
    }

    @Test
    fun `request shaping uses default base url, api key header, and merged headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        val headerModel = model.copy(
            headers = mapOf("X-Model" to "model-value"),
        )
        api(transport).stream(
            headerModel,
            context,
            GoogleGenerativeAiApi.GoogleOptions(
                apiKey = "k",
                headers = mapOf("x-model" to "request-value"),
            ),
        ).toList()

        val request = transport.requests.single()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            request.url,
        )
        assertEquals("k", request.headers["x-goog-api-key"])
        assertEquals("request-value", request.headers["x-model"])
        assertEquals(GoogleRequest.USER_AGENT, request.headers["User-Agent"])
        assertNull(request.bearerToken)

        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertTrue(body.containsKey("contents"))
        assertEquals(
            "user",
            body["contents"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `custom base url replaces the default and keeps its version path`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            geminiModel(baseUrl = "https://proxy.example/v1beta"),
            context,
            GoogleGenerativeAiApi.GoogleOptions(apiKey = "k"),
        ).toList()
        assertEquals(
            "https://proxy.example/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            transport.requests.single().url,
        )
    }

    @Test
    fun `system prompt, tools, and tool choice shape the request`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            model,
            Context(
                systemPrompt = "be brief",
                messages = listOf(UserMessage.ofText("hi")),
                tools = listOf(
                    works.resolve.aletheia.ai.core.Tool(
                        name = "bash",
                        description = "run",
                        parameters = Json.parseToJsonElement("""{"type":"object"}"""),
                    ),
                ),
            ),
            GoogleGenerativeAiApi.GoogleOptions(
                apiKey = "k",
                toolChoice = "any",
                temperature = 0.5,
                maxTokens = 128,
            ),
        ).toList()

        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("be brief", body["systemInstruction"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("tools"))
        assertEquals(
            "ANY",
            body["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"]!!.jsonPrimitive.content,
        )
        val generationConfig = body["generationConfig"]!!.jsonObject
        assertEquals(0.5, generationConfig["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(128, generationConfig["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `streamSimple maps reasoning levels through google budgets`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            model,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = ThinkingLevel.MEDIUM),
        ).toList()

        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(true, thinkingConfig["includeThoughts"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(8192, thinkingConfig["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `streamSimple without reasoning disables thinking`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(model, context, SimpleStreamOptions(apiKey = "k")).toList()

        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(0, thinkingConfig["thinkingBudget"]!!.jsonPrimitive.content.toInt())
        assertNull(thinkingConfig["includeThoughts"])
    }

    @Test
    fun `retryable failures are retried before the stream starts`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(503, "busy")
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        val done = api(transport)
            .stream(
                model,
                context,
                GoogleGenerativeAiApi.GoogleOptions(apiKey = "k", maxRetries = 1),
            )
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Done>(done)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `chatapi options path resolves gemini3 thinking levels`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            geminiModel(id = "gemini-3-pro-preview"),
            context,
            OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = works.resolve.aletheia.ai.core.ModelThinkingLevel.MEDIUM),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("HIGH", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        assertNull(thinkingConfig["thinkingBudget"])
    }
}
