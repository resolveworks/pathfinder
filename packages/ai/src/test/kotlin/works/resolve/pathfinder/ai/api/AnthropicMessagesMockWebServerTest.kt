package works.resolve.pathfinder.ai.api

import kotlin.test.AfterTest
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.transport.OkHttpTransport

class AnthropicMessagesMockWebServerTest {

    private val server = MockWebServer()

    private val model = Model(
        id = "claude-sonnet-4-5",
        name = "Claude Sonnet 4.5",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "", // replaced per-test with the mock server URL
        reasoning = true,
        input = listOf(InputModality.TEXT),
        cost = ModelCost(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
        contextWindow = 200_000,
        maxTokens = 64_000
    )

    private val context = Context(
        systemPrompt = "Be terse.",
        messages = listOf(UserMessage.ofText("hi"))
    )

    private val sseBody = sequence {
        yield(
            "message_start" to
                """{"type":"message_start","message":{"id":"msg_live","model":"claude-sonnet-4-5","usage":{"input_tokens":9,"output_tokens":0}}}"""
        )
        yield(
            "content_block_start" to
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        )
        yield(
            "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}"""
        )
        yield(
            "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}"""
        )
        yield("content_block_stop" to """{"type":"content_block_stop","index":0}""")
        yield(
            "message_delta" to
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""
        )
        yield("message_stop" to """{"type":"message_stop"}""")
    }.joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams over real sse framing with api key auth`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "text/event-stream")
                .setBody(sseBody)
        )
        server.start()
        val api = AnthropicMessagesApi(OkHttpTransport())

        val events = api.stream(
            model.copy(baseUrl = server.url("/").toString().trimEnd('/')),
            context,
            AnthropicMessagesOptions(apiKey = "test-key")
        ).toList()

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        assertEquals("Hello", assertIs<TextContent>(done.message.content.single()).text)
        assertEquals("msg_live", done.message.responseId)
        assertEquals(9, done.message.usage.input)
        assertEquals(2, done.message.usage.output)

        val recorded = server.takeRequest()
        assertEquals("/v1/messages?beta=true", recorded.path)
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        assertEquals("application/json", recorded.getHeader("accept"))
        // Thinking not enabled: no composed beta features.
        assertNull(recorded.getHeader("anthropic-beta"))
        assertNull(recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(
            "Be terse.",
            body["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `oauth token authenticates as bearer over the wire`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "text/event-stream")
                .setBody(sseBody)
        )
        server.start()
        val api = AnthropicMessagesApi(OkHttpTransport())

        api.stream(
            model.copy(baseUrl = server.url("/").toString().trimEnd('/')),
            context,
            AnthropicMessagesOptions(apiKey = "sk-ant-oat-tok")
        ).toList()

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-ant-oat-tok", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("x-api-key"))
        assertTrue(
            recorded.getHeader(
                "anthropic-beta"
            )!!.startsWith("claude-code-20250219,oauth-2025-04-20")
        )
    }

    @Test
    fun `http error bodies surface in the error event`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("content-type", "application/json")
                .setBody(
                    """{"type":"error","error":{"type":"rate_limit_error","message":"Rate limited"}}"""
                )
        )
        server.start()
        val api = AnthropicMessagesApi(OkHttpTransport())

        val events = api.stream(
            model.copy(baseUrl = server.url("/").toString().trimEnd('/')),
            context,
            AnthropicMessagesOptions(apiKey = "test-key")
        ).toList()

        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(StopReason.ERROR, error.reason)
        val message = error.error.errorMessage ?: ""
        assertEquals(
            """429: {"type":"error","error":{"type":"rate_limit_error","message":"Rate limited"}}""",
            message
        )
    }
}
