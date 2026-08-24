package works.resolve.aletheia.ai.auth.oauth

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
import works.resolve.aletheia.ai.auth.InMemoryCredentialStore
import works.resolve.aletheia.ai.auth.NoopAuthContext
import works.resolve.aletheia.ai.auth.OAuthCredential
import works.resolve.aletheia.ai.auth.resolveProviderAuth
import works.resolve.aletheia.ai.auth.CatalogAuthProviderRef
import works.resolve.aletheia.ai.auth.MapCatalogAuthRegistry
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.models.Models
import works.resolve.aletheia.ai.models.ResolvedAuth
import works.resolve.aletheia.ai.providers.ProviderCatalog
import works.resolve.aletheia.ai.transport.OkHttpTransport

/**
 * Runtime proof that a stored OpenAI Codex OAuth credential authenticates an
 * `openai-codex-responses` request: the real generated catalog entry →
 * [resolveProviderAuth] over a stored [OAuthCredential] (with the flow's
 * `accountId` extra) → [OpenAiCodexOAuthAuth.toAuth] → the runtime provider →
 * `OpenAICodexResponsesApi` → OkHttp transport → MockWebServer. The stored
 * access token is a fake JWT carrying the `chatgpt_account_id` claim; no live
 * credential or network is used and nothing secret is logged.
 */
class OpenAiCodexOAuthRuntimeTest {

    /** The flow must not touch the network: the credential is far from expiry. */
    private object NO_NETWORK : OAuthHttpClient {
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

                        """.trimIndent(),
                    ),
            )

            val catalog = ProviderCatalog.parse(
                File("src/main/assets/models-catalog.json").readText(),
            )
            val provider = assertNotNull(catalog.getProvider("openai-codex"))
            val model = assertNotNull(provider.models.first { it.id == "gpt-5.3-codex-spark" })

            // A credential exactly as OpenAiCodexOAuthAuth.login stores it:
            // JWT access token with the account claim, far-future expiry.
            val accountId = "runtime-account"
            val accessJwt = fakeAccessJwt(accountId)
            val credentials = InMemoryCredentialStore()
            runBlocking {
                credentials.modify("openai-codex") {
                    OAuthCredential(
                        access = accessJwt,
                        refresh = "runtime-refresh-token",
                        expires = System.currentTimeMillis() + 3_600_000,
                        extras = mapOf("accountId" to JsonPrimitive(accountId)),
                    )
                }
            }

            val resolved = runBlocking {
                resolveProviderAuth(
                    CatalogAuthProviderRef(
                        provider,
                        MapCatalogAuthRegistry(
                            mapOf("openai-codex" to OpenAiCodexOAuthAuth(NO_NETWORK)),
                        ),
                    ),
                    credentials,
                    NoopAuthContext,
                )
            }
            assertNotNull(resolved)
            assertEquals("OAuth", resolved.source)
            assertEquals(accessJwt, resolved.auth.apiKey)

            val runtimeProvider = provider.toRuntimeProvider(
                transport = OkHttpTransport(),
                authResolver = { _, _ -> ResolvedAuth(resolved.auth.apiKey, resolved.env) },
            )
            val models = Models(listOf(runtimeProvider))

            val events = runBlocking {
                models.stream(
                    model.copy(baseUrl = works.resolve.aletheia.ai.providers.normalizeBaseUrl(server.url("/backend-api").toString())),
                    Context(messages = listOf(UserMessage.ofText("hi"))),
                ).toList()
            }

            assertIs<AssistantMessageEvent.Start>(events.first())
            val done = assertIs<AssistantMessageEvent.Done>(events.last())
            assertEquals(StopReason.STOP, done.reason)
            assertEquals("Hi there", assertIs<TextContent>(done.message.content.single()).text)
            assertEquals("openai-codex", done.message.provider)

            // The recorded request authenticated with the stored access token
            // and its derived account header.
            val recorded = server.takeRequest()
            assertEquals("/backend-api/codex/responses", recorded.path)
            assertEquals("Bearer $accessJwt", recorded.getHeader("Authorization"))
            assertEquals(accountId, recorded.getHeader("chatgpt-account-id"))
            assertEquals("pi", recorded.getHeader("originator"))
            assertTrue(recorded.getHeader("Authorization")!!.startsWith("Bearer "))
        } finally {
            server.shutdown()
        }
    }

    /**
     * The same login menu the chat UI projects (ChatViewModel delegates to
     * [works.resolve.aletheia.ai.auth.ProviderAuthService.authMethods]): the
     * real catalog entry plus the production registry expose exactly one
     * subscription OAuth method named by the flow.
     */
    @Test
    fun `openai-codex projects a single subscription oauth method`() {
        val catalog = ProviderCatalog.parse(
            File("src/main/assets/models-catalog.json").readText(),
        )
        val authService = works.resolve.aletheia.ai.auth.ProviderAuthService(
            catalog,
            works.resolve.aletheia.ai.auth.ProductionCatalogAuthRegistry,
            InMemoryCredentialStore(),
        )
        val methods = authService.authMethods("openai-codex")
        assertEquals(1, methods.size)
        assertEquals(works.resolve.aletheia.ai.auth.AuthType.OAUTH, methods.single().type)
        assertEquals("OpenAI (ChatGPT Plus/Pro)", methods.single().label)
        assertTrue(methods.single().isSubscription)
    }

    private fun fakeAccessJwt(accountId: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}""".toByteArray(),
        )
        return "$header.$payload.signature"
    }
}
