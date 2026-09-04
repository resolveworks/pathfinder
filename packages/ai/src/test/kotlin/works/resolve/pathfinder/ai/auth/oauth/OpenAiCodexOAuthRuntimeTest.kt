package works.resolve.pathfinder.ai.auth.oauth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.auth.CatalogAuthProviderRef
import works.resolve.pathfinder.ai.auth.InMemoryCredentialStore
import works.resolve.pathfinder.ai.auth.MapCatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.resolveProviderAuth
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.testing.NoWebSocketTransport
import works.resolve.pathfinder.ai.transport.OkHttpTransport

class OpenAiCodexOAuthRuntimeTest {

    private object NoNetwork : OAuthHttpClient {
        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse =
            error("unexpected OAuth network call: ${request.url}")
    }

    @Test
    fun `stored oauth credential authenticates an openai-codex-responses request`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}

                        data: {"type":"response.output_text.delta","output_index":0,"delta":"Hi there"}

                        data: {"type":"response.done","response":{"id":"resp-codex","status":"completed","end_turn":true,"usage":{"input_tokens":9,"output_tokens":2,"total_tokens":11}}}

                        data: [DONE]

                        """.trimIndent()
                    )
            )

            val catalog = ProviderCatalog.parse(
                File("src/main/assets/models-catalog.json").readText()
            )
            val provider = assertNotNull(catalog.getProvider("openai-codex"))
            val model = assertNotNull(provider.models.first { it.id == "gpt-5.3-codex-spark" })

            val accountId = "runtime-account"
            val accessJwt = fakeAccessJwt(accountId)
            val credentials = InMemoryCredentialStore()
            runBlocking {
                credentials.modify("openai-codex") {
                    OAuthCredential(
                        access = accessJwt,
                        refresh = "runtime-refresh-token",
                        expires = System.currentTimeMillis() + 3_600_000,
                        extras = mapOf("accountId" to JsonPrimitive(accountId))
                    )
                }
            }

            val resolved = runBlocking {
                resolveProviderAuth(
                    CatalogAuthProviderRef(
                        provider,
                        MapCatalogAuthRegistry(
                            mapOf("openai-codex" to OpenAiCodexOAuthAuth(NoNetwork))
                        )
                    ),
                    credentials,
                    NoopAuthContext
                )
            }
            assertNotNull(resolved)
            assertEquals("OAuth", resolved.source)
            assertEquals(accessJwt, resolved.auth.apiKey)

            val runtimeProvider = provider.toRuntimeProvider(
                transport = OkHttpTransport(),
                authResolver = { _, _ -> ResolvedAuth(resolved.auth.apiKey, resolved.env) },
                webSocketTransport = NoWebSocketTransport
            )
            val models = Models(listOf(runtimeProvider))

            val events = runBlocking {
                models.stream(
                    model.copy(
                        baseUrl = works.resolve.pathfinder.ai.providers.normalizeBaseUrl(
                            server.url("/backend-api").toString()
                        )
                    ),
                    Context(messages = listOf(UserMessage.ofText("hi")))
                ).toList()
            }

            assertIs<AssistantMessageEvent.Start>(events.first())
            val done = assertIs<AssistantMessageEvent.Done>(events.last())
            assertEquals(StopReason.STOP, done.reason)
            assertEquals("Hi there", assertIs<TextContent>(done.message.content.single()).text)
            assertEquals("openai-codex", done.message.provider)

            val recorded = server.takeRequest()
            assertEquals("/backend-api/codex/responses", recorded.path)
            assertEquals("Bearer $accessJwt", recorded.getHeader("Authorization"))
            assertEquals(accountId, recorded.getHeader("chatgpt-account-id"))
            assertEquals("pathfinder", recorded.getHeader("originator"))
            assertTrue(recorded.getHeader("Authorization")!!.startsWith("Bearer "))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `openai-codex projects a single subscription oauth method`() {
        val catalog = ProviderCatalog.parse(
            File("src/main/assets/models-catalog.json").readText()
        )
        val authService = works.resolve.pathfinder.ai.auth.ProviderAuthService(
            catalog,
            works.resolve.pathfinder.ai.auth.ProductionCatalogAuthRegistry(),
            InMemoryCredentialStore()
        )
        val methods = authService.authMethods("openai-codex")
        assertEquals(1, methods.size)
        assertEquals(works.resolve.pathfinder.ai.auth.AuthType.OAUTH, methods.single().type)
        assertEquals("OpenAI (ChatGPT Plus/Pro)", methods.single().label)
        assertTrue(methods.single().isSubscription)
    }

    private fun fakeAccessJwt(accountId: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}""".toByteArray()
        )
        return "$header.$payload.signature"
    }
}
