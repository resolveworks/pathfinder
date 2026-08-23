package com.aletheia.ai.providers

import com.aletheia.ai.core.AssistantMessageEvent
import com.aletheia.ai.core.Context
import com.aletheia.ai.core.SimpleStreamOptions
import com.aletheia.ai.core.StopReason
import com.aletheia.ai.core.TextContent
import com.aletheia.ai.core.UserMessage
import com.aletheia.ai.models.Models
import com.aletheia.ai.transport.OkHttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Canned full-stack integration test: Models registry -> ZaiProvider factory
 * -> OpenAiCompletionsApi -> OkHttpTransport -> MockWebServer. No live
 * credential or network is used; the API key is a distinctive test value and
 * is never logged.
 */
class ZaiProviderIntegrationTest {

    @Test
    fun `streams glm through the full native stack against a mock server`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"id":"resp-int","model":"glm-4.7","choices":[{"delta":{"content":"Hel"}}]}
                        
                        data: {"choices":[{"delta":{"content":"lo"}}]}
                        
                        data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
                        
                        data: [DONE]
                        
                        """.trimIndent(),
                    ),
            )

            val testKey = "zai-integration-test-key"
            val provider = ZaiProvider.create(
                transport = OkHttpTransport(),
                apiKeyResolver = { testKey },
                baseUrl = server.url("/v4").toString(),
            )
            val models = Models(listOf(provider))

            val events = runBlocking {
                models.stream(
                    "zai",
                    "glm-4.7",
                    Context(messages = listOf(UserMessage.ofText("hi"))),
                ).toList()
            }

            // Lifecycle reaches Done with the streamed text and identity.
            assertIs<AssistantMessageEvent.Start>(events.first())
            val done = assertIs<AssistantMessageEvent.Done>(events.last())
            assertEquals(StopReason.STOP, done.reason)
            assertEquals("Hello", assertIs<TextContent>(done.message.content.single()).text)
            assertEquals("zai", done.message.provider)
            assertEquals("glm-4.7", done.message.model)
            assertEquals("resp-int", done.message.responseId)
            assertEquals(7, done.message.usage.input)

            // The recorded request hit the overridden base URL with ZAI auth/payload.
            val recorded = server.takeRequest()
            assertEquals("/v4/chat/completions", recorded.path)
            assertEquals("POST", recorded.method)
            assertEquals("Bearer $testKey", recorded.getHeader("Authorization"))
            assertEquals("application/json", recorded.getHeader("Content-Type"))

            val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals("glm-4.7", body["model"]!!.jsonPrimitive.content)
            assertEquals(true, body["stream"]!!.jsonPrimitive.booleanOrNull)
            assertTrue(body["max_tokens"]!!.jsonPrimitive.longOrNull!! > 0)
            assertTrue(body.containsKey("thinking"), "ZAI payload should carry the thinking field")
        } finally {
            server.shutdown()
        }
    }
}
