package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import works.resolve.pathfinder.ai.auth.AuthResult
import works.resolve.pathfinder.ai.auth.CatalogAuthProviderRef
import works.resolve.pathfinder.ai.auth.InMemoryCredentialStore
import works.resolve.pathfinder.ai.auth.MapCatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.resolveProviderAuth
import works.resolve.pathfinder.ai.auth.oauth.AnthropicOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OAuthHttpRequest
import works.resolve.pathfinder.ai.auth.oauth.OAuthHttpResponse
import works.resolve.pathfinder.ai.auth.oauth.OAuthHttpClient
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.transport.OkHttpTransport

class AnthropicOAuthRuntimeTest {

    private class FakeHttpClient : OAuthHttpClient {
        val requests = mutableListOf<OAuthHttpRequest>()
        var respond: suspend (OAuthHttpRequest) -> OAuthHttpResponse = {
            OAuthHttpResponse(
                200,
                emptyMap(),
                """{"access_token":"sk-ant-oat-new","refresh_token":"rotated-refresh","expires_in":3600}"""
                    .toByteArray(),
            )
        }

        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            return respond(request)
        }
    }

    private fun provider(baseUrl: String): CatalogProvider =
        CatalogProvider(
            id = "anthropic",
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            models = listOf(
                Model(
                    id = "claude-sonnet-4-5",
                    name = "Claude Sonnet 4.5",
                    api = "anthropic-messages",
                    provider = "anthropic",
                    baseUrl = baseUrl,
                    reasoning = true,
                    input = listOf(InputModality.TEXT),
                    cost = ModelCost(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
                    contextWindow = 200_000,
                    maxTokens = 64_000,
                ),
            ),
        )

    private val context = Context(
        systemPrompt = "Be terse.",
        messages = listOf(UserMessage.ofText("hi")),
    )

    private val sseBody = sequence {
        yield("message_start" to """{"type":"message_start","message":{"id":"msg_oauth","model":"claude-sonnet-4-5","usage":{"input_tokens":9,"output_tokens":0}}}""")
        yield("content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        yield("content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""")
        yield("content_block_stop" to """{"type":"content_block_stop","index":0}""")
        yield("message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""")
        yield("message_stop" to """{"type":"message_stop"}""")
    }.joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }

    @Test
    fun `expired stored anthropic oauth credential refreshes with rotation and streams as bearer`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "text/event-stream")
                .setBody(sseBody),
        )
        server.start()

        val http = FakeHttpClient()
        val oauth = AnthropicOAuthAuth(http)
        val credentials = InMemoryCredentialStore()
        credentials.modify("anthropic") {
            OAuthCredential(access = "sk-ant-oat-old", refresh = "stored-refresh", expires = 0)
        }
        val catalogProvider = provider(server.url("/").toString().trimEnd('/'))
        val providerRef = CatalogAuthProviderRef(
            catalogProvider,
            MapCatalogAuthRegistry(mapOf("anthropic" to oauth)),
        )

        val resolved = resolveProviderAuth(providerRef, credentials, NoopAuthContext)
        assertIs<AuthResult>(resolved)
        assertEquals("OAuth", resolved.source)
        assertEquals("sk-ant-oat-new", resolved.auth.apiKey)

        val refreshBody = Json.parseToJsonElement(http.requests.single().body.decodeToString()).jsonObject
        assertEquals("refresh_token", refreshBody["grant_type"]!!.jsonPrimitive.content)
        assertEquals("stored-refresh", refreshBody["refresh_token"]!!.jsonPrimitive.content)
        val stored = assertIs<OAuthCredential>(credentials.read("anthropic"))
        assertEquals("sk-ant-oat-new", stored.access)
        assertEquals("rotated-refresh", stored.refresh)

        val model = catalogProvider.models.single()
        val events = AnthropicMessagesApi(OkHttpTransport())
            .stream(model, context, AnthropicMessagesOptions(apiKey = resolved.auth.apiKey))
            .toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("Hi", assertIs<TextContent>(done.message.content.single()).text)

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("Bearer sk-ant-oat-new", recorded.getHeader("Authorization"))
        assertEquals(null, recorded.getHeader("x-api-key"))
        assertTrue(
            recorded.getHeader("anthropic-beta")!!.startsWith("claude-code-20250219,oauth-2025-04-20"),
        )

        server.shutdown()
    }
}
