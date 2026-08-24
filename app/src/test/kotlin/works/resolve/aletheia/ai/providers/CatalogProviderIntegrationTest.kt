package works.resolve.aletheia.ai.providers

import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.models.Models
import works.resolve.aletheia.ai.testing.TestCatalogs
import works.resolve.aletheia.ai.transport.OkHttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
 * Canned full-stack integration tests: catalog entry -> runtime provider ->
 * Models registry -> OpenAiCompletionsApi -> OkHttpTransport -> MockWebServer.
 * No live credential or network is used; test keys are distinctive values and
 * are never logged.
 */
class CatalogProviderIntegrationTest {

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
            val provider = TestCatalogs.ZAI.toRuntimeProvider(
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

    @Test
    fun `cloudflare gateway substitutes base URL placeholders and uses the bearer header override`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"choices":[{"delta":{"content":"ok"}}]}

                        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """.trimIndent(),
                    ),
            )

            val testKey = "cf-integration-test-key"
            val entry = TestCatalogs.CATALOG.getProvider("cloudflare-ai-gateway")!!
            // Placeholder base URL redirected to the mock server; env values
            // arrive through the options exactly like a resolved credential.
            val provider = entry.toRuntimeProvider(
                transport = OkHttpTransport(),
                apiKeyResolver = { testKey },
                // Placeholders appended after server.url() so they stay literal
                // braces (HttpUrl construction would percent-encode them); env
                // values arrive through the options exactly like a resolved credential.
                baseUrl = server.url("/v1").toString() +
                    "/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/compat",
            )
            val models = Models(listOf(provider))

            val events = runBlocking {
                models.stream(
                    "cloudflare-ai-gateway",
                    "workers-ai/test-model",
                    Context(messages = listOf(UserMessage.ofText("hi"))),
                    SimpleStreamOptions(
                        apiKey = testKey,
                        env = mapOf(
                            "CLOUDFLARE_ACCOUNT_ID" to "acct-123",
                            "CLOUDFLARE_GATEWAY_ID" to "gw-456",
                        ),
                        bearerHeaderName = entry.bearerHeaderName,
                    ),
                ).toList()
            }

            val done = assertIs<AssistantMessageEvent.Done>(events.last())
            assertEquals(StopReason.STOP, done.reason)
            assertEquals("ok", assertIs<TextContent>(done.message.content.single()).text)

            // Placeholders were substituted into the request URL and the bearer
            // credential went out on cf-aig-authorization, not Authorization.
            val recorded = server.takeRequest()
            assertEquals(
                "/v1/acct-123/gw-456/compat/chat/completions",
                recorded.path,
            )
            assertEquals("Bearer $testKey", recorded.getHeader("cf-aig-authorization"))
            assertNull(recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
