package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.testing.TestCatalogs
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.CredentialInfo
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.ProductionCatalogAuthRegistry
import works.resolve.pathfinder.data.settings.ModelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Focused JVM tests for the production factory: validation, transcript
 * copying, and end-to-end wiring through the native stack against a fake
 * transport and the shared catalog fixture. The fake key is a distinctive
 * test value and is never printed.
 */
class NativeAgentFactoryTest {

    /** Compaction off: URL/auth tests are single-request and not about compaction. */
    private val COMPACT_OFF = works.resolve.pathfinder.agent.compaction.CompactionSettings(enabled = false, reserveTokens = 16384, keepRecentTokens = 20000)

    private fun emptyConversation(): works.resolve.pathfinder.data.sessions.Conversation =
        works.resolve.pathfinder.data.sessions.Conversation(emptyList(), null)


    private class FakeCredentialStore(initialCredential: Credential? = null) : CredentialStore {
        val credential = MutableStateFlow(initialCredential)
        var readCalls = 0
        var modifyCalls = 0

        override suspend fun read(providerId: String): Credential? {
            readCalls++
            return credential.value
        }

        override suspend fun list(): List<CredentialInfo> =
            credential.value?.let { listOf(CredentialInfo(it.javaClass.simpleName, it.type)) } ?: emptyList()

        override suspend fun modify(
            providerId: String,
            update: suspend (current: Credential?) -> Credential?,
        ): Credential? {
            modifyCalls++
            val next = update(credential.value)
            if (next != null) credential.value = next
            return next ?: credential.value
        }

        override suspend fun delete(providerId: String) {
            credential.value = null
        }
    }

    private class RecordingTransport : HttpStreamingTransport {
        val requests = mutableListOf<TransportRequest>()

        override suspend fun post(request: TransportRequest): TransportResponse {
            requests.add(request)
            return TransportResponse(
                status = 200,
                headers = mapOf("content-type" to listOf("text/event-stream")),
                events = flowOf(
                    SseEvent("""{"choices":[{"delta":{"content":"Hi"}}]}"""),
                    SseEvent("""{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""),
                    SseEvent("[DONE]"),
                ),
            )
        }
    }

    private val catalog: ProviderCatalog = TestCatalogs.CATALOG

    private fun factory(store: FakeCredentialStore, transport: RecordingTransport): AgentFactory =
        NativeAgentFactory(credentials = store, catalog = catalog, transport = transport)

    private fun settings(
        providerId: String = "zai",
        modelId: String = "glm-4.7",
    ) = ModelSettings(providerId = providerId, modelId = modelId)

    // ---- validation ----

    @Test
    fun `rejects an unsupported provider`() {
        assertFailsWith<IllegalArgumentException> {
            factory(FakeCredentialStore(ApiKeyCredential("k")), RecordingTransport())
                .create(settings(providerId = "openai"), "s1", emptyConversation())
        }
    }

    @Test
    fun `rejects an unknown model`() {
        assertFailsWith<IllegalArgumentException> {
            factory(FakeCredentialStore(ApiKeyCredential("k")), RecordingTransport())
                .create(settings(modelId = "gpt-4"), "s1", emptyConversation())
        }
    }

    // ---- transcript ----

    @Test
    fun `copies the initial transcript into the agent`() {
        val transcript: MutableList<works.resolve.pathfinder.ai.core.Message> = mutableListOf(
            UserMessage.ofText("hello"),
            UserMessage.ofText("again"),
        )
        val agent = factory(FakeCredentialStore(ApiKeyCredential("k")), RecordingTransport())
            .create(settings(), "s1", works.resolve.pathfinder.data.sessions.Conversation.fromMessages(transcript))
        assertEquals(transcript, agent.state.value.messages)
        // Copied defensively: later mutation of the source list is invisible.
        transcript.clear()
        assertEquals(2, agent.state.value.messages.size)
    }

    @Test
    fun `passes configured tools to created agents and defaults to none`() {
        val tool = object : AgentTool {
            override val definition = works.resolve.pathfinder.ai.core.Tool("t", "test", JsonPrimitive("object"))
            override val label = "t"
            override fun validateArguments(arguments: kotlinx.serialization.json.JsonObject) = arguments
            override suspend fun execute(
                toolCallId: String,
                arguments: kotlinx.serialization.json.JsonObject,
                onUpdate: AgentToolUpdateCallback,
            ) = AgentToolResult(content = listOf(TextContent("done")))
        }
        val withTools = NativeAgentFactory(
            credentials = FakeCredentialStore(ApiKeyCredential("k")),
            catalog = catalog,
            transport = RecordingTransport(),
            tools = mutableListOf(tool),
        ).create(settings(), "s1", emptyConversation())
        assertEquals(1, withTools.state.value.tools.size)

        val default = factory(FakeCredentialStore(ApiKeyCredential("k")), RecordingTransport())
            .create(settings(), "s1", emptyConversation())
        assertTrue(default.state.value.tools.isEmpty())
    }

    // ---- wiring through the native stack ----

    @Test
    fun `prompt uses the selected model, effective base URL, and the lazily resolved current credential`() {
        runBlocking {
            val store = FakeCredentialStore(ApiKeyCredential("factory-test-key-1"))
            val transport = RecordingTransport()
            val agent = factory(store, transport)
                .create(settings(), "s1", emptyConversation())

            // Rotating the stored credential after construction must be observed
            // at prompt time: the resolver stays lazy and reads the store per request.
            store.credential.value = ApiKeyCredential("factory-test-key-2")

            agent.prompt("ping")

            val state = agent.state.value
            assertEquals(false, state.isStreaming)
            val last = state.messages.last()
            assertTrue(last is works.resolve.pathfinder.ai.core.AssistantMessage)
            assertEquals("Hi", (last.content.single() as TextContent).text)

            assertEquals(1, store.readCalls)
            val request = transport.requests.single()
            // Catalog base URL, normalized (trailing slashes dropped) + endpoint.
            assertEquals("https://api.z.ai/api/coding/paas/v4/chat/completions", request.url)
            assertEquals("factory-test-key-2", request.bearerToken)
            assertTrue(request.timeoutMs != null && request.timeoutMs > 0)

            val body = Json.parseToJsonElement(String(request.body)).jsonObject
            assertEquals("glm-4.7", body["model"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `credential env and bearer header override reach the request`() {
        runBlocking {
            val store = FakeCredentialStore(
                ApiKeyCredential(
                    key = "cf-factory-test-key",
                    env = mapOf(
                        "CLOUDFLARE_ACCOUNT_ID" to "acct-9",
                        "CLOUDFLARE_GATEWAY_ID" to "gw-8",
                    ),
                ),
            )
            val transport = RecordingTransport()
            val agent = factory(store, transport)
                .create(
                    settings(providerId = "cloudflare-ai-gateway", modelId = "workers-ai/test-model"),
                    "s1",
                    emptyConversation(),
                )
            agent.prompt("ping")

            val request = transport.requests.single()
            assertEquals(
                "https://gateway.test/v1/acct-9/gw-8/compat/chat/completions",
                request.url,
                "credential env must be substituted into the base URL placeholders",
            )
            // Header-based auth: cf-aig-authorization only, no bearer token.
            assertNull(request.bearerToken)
            assertEquals("Bearer cf-factory-test-key", request.headers["cf-aig-authorization"])
            assertFalse(request.headers.containsKey("Authorization"))
            assertFalse(request.headers.containsKey("authorization"))
        }
    }

    @Test
    fun `incomplete explicit cloudflare key env is unconfigured with no api call`() {
        runBlocking {
            // Explicit key but the required gateway env is missing: the
            // resolver must reject it (null), producing a single unconfigured
            // Error event and no network request.
            val store = FakeCredentialStore(null)
            val entry = catalog.getProvider("cloudflare-ai-gateway")!!
            val transport = RecordingTransport()
            val provider = entry.toRuntimeProvider(
                transport = transport,
                authResolver = catalogAuthResolver(entry, store),
            )

            val events = Models(listOf(provider)).stream(
                entry.model("workers-ai/test-model")!!,
                works.resolve.pathfinder.ai.core.Context(messages = emptyList()),
                SimpleStreamOptions(
                    apiKey = "cf-explicit-key",
                    env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct-only"),
                ),
            ).toList()

            val error = events.single() as works.resolve.pathfinder.ai.core.AssistantMessageEvent.Error
            assertTrue(
                "Provider 'cloudflare-ai-gateway' is not configured" in (error.error.errorMessage ?: ""),
            )
            assertTrue(transport.requests.isEmpty(), "no request must be sent")
        }
    }

    @Test
    fun `explicit env overrides stored env before auth shaping`() {
        runBlocking {
            // Stored credential carries account/gateway ids; the explicit
            // request env must win per field before completeness/shaping
            // (pi's stored-credential env override).
            val store = FakeCredentialStore(
                ApiKeyCredential(
                    key = "cf-factory-test-key",
                    env = mapOf(
                        "CLOUDFLARE_ACCOUNT_ID" to "stored-acct",
                        "CLOUDFLARE_GATEWAY_ID" to "stored-gw",
                    ),
                ),
            )
            val entry = catalog.getProvider("cloudflare-ai-gateway")!!

            val auth = catalogAuthResolver(entry, store)(
                null,
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "explicit-acct"),
            )!!

            // Explicit account id won; stored gateway id survived.
            assertEquals(
                mapOf(
                    "CLOUDFLARE_ACCOUNT_ID" to "explicit-acct",
                    "CLOUDFLARE_GATEWAY_ID" to "stored-gw",
                ),
                auth.env,
            )
            assertEquals("Bearer cf-factory-test-key", auth.headers["cf-aig-authorization"])

            // End to end: the merged env substitutes into the base URL via Models.
            val transport = RecordingTransport()
            val provider = entry.toRuntimeProvider(
                transport = transport,
                authResolver = catalogAuthResolver(entry, store),
            )
            Models(listOf(provider)).stream(
                entry.model("workers-ai/test-model")!!,
                works.resolve.pathfinder.ai.core.Context(messages = emptyList()),
                SimpleStreamOptions(env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "explicit-acct")),
            ).toList()
            assertEquals(
                "https://gateway.test/v1/explicit-acct/stored-gw/compat/chat/completions",
                transport.requests.single().url,
            )
        }
    }

    @Test
    fun `effective base URL defaults from the model, not the provider`() {
        runBlocking {
            // A catalog whose model carries its own base URL (the pi invariant
            // we honor: request-model selection defaults from model.baseUrl).
            val modelBaseUrlModel = Model(
                id = "m",
                name = "M",
                api = "openai-completions",
                provider = "multi",
                baseUrl = "https://model.test/v1",
            )
            val catalog = ProviderCatalog(
                listOf(
                    CatalogProvider(
                        id = "multi",
                        name = "Multi",
                        baseUrl = "https://provider.test/v1",
                        auth = works.resolve.pathfinder.ai.providers.ProviderAuth(
                            prompts = listOf(
                                works.resolve.pathfinder.ai.providers.AuthPrompt("MULTI_API_KEY", "API key"),
                            ),
                        ),
                        models = listOf(modelBaseUrlModel),
                    ),
                ),
            )
            val transport = RecordingTransport()
            val agent = NativeAgentFactory(FakeCredentialStore(ApiKeyCredential("k")), catalog, transport)
                .create(ModelSettings(providerId = "multi", modelId = "m", compaction = COMPACT_OFF), "s1", emptyConversation())

            agent.prompt("ping")

            assertEquals("https://model.test/v1/chat/completions", transport.requests.single().url)
        }
    }

    @Test
    fun `stored OpenRouter OAuth credential authenticates runtime request`() {
        runBlocking {
            val model = Model(
                id = "test-model",
                name = "Test Model",
                api = "openai-completions",
                provider = "openrouter",
                baseUrl = "https://openrouter.test/api/v1",
            )
            val openRouterCatalog = ProviderCatalog(
                listOf(
                    CatalogProvider(
                        id = "openrouter",
                        name = "OpenRouter",
                        baseUrl = "https://openrouter.test/api/v1",
                        auth = works.resolve.pathfinder.ai.providers.ProviderAuth(
                            label = "OpenRouter API key",
                            oauth = works.resolve.pathfinder.ai.providers.ProviderOAuth(
                                name = "OpenRouter OAuth",
                                loginLabel = "Sign in with OpenRouter",
                            ),
                            prompts = listOf(
                                works.resolve.pathfinder.ai.providers.AuthPrompt(
                                    "OPENROUTER_API_KEY",
                                    "Enter OpenRouter API key",
                                ),
                            ),
                        ),
                        models = listOf(model),
                    ),
                ),
            )
            val store = FakeCredentialStore(
                OAuthCredential(
                    access = "openrouter-oauth-test-key",
                    refresh = "",
                    expires = Long.MAX_VALUE,
                ),
            )
            val transport = RecordingTransport()
            val agent = NativeAgentFactory(
                credentials = store,
                catalog = openRouterCatalog,
                transport = transport,
                authRegistry = ProductionCatalogAuthRegistry,
            ).create(
                ModelSettings(providerId = "openrouter", modelId = model.id, compaction = COMPACT_OFF),
                "s1",
                emptyConversation(),
            )

            agent.prompt("ping")

            val request = transport.requests.single()
            assertEquals("https://openrouter.test/api/v1/chat/completions", request.url)
            assertEquals("openrouter-oauth-test-key", request.bearerToken)
            assertEquals(0, store.modifyCalls, "a permanent OpenRouter credential must not refresh")
        }
    }

    @Test
    fun `an incomplete credential resolves to null and surfaces as a single error event`() {
        runBlocking {
            // Cloudflare requires key + both gateway env values; the key alone
            // is incomplete, so the resolver must return null (defense in depth).
            val store = FakeCredentialStore(ApiKeyCredential("cf-incomplete-key"))
            val transport = RecordingTransport()
            val agent = factory(store, transport)
                .create(
                    settings(providerId = "cloudflare-ai-gateway", modelId = "workers-ai/test-model"),
                    "s1",
                    emptyConversation(),
                )

            agent.prompt("ping")

            val last = agent.state.value.messages.last()
            val error = assertIs<works.resolve.pathfinder.ai.core.AssistantMessage>(last)
            assertEquals(works.resolve.pathfinder.ai.core.StopReason.ERROR, error.stopReason)
            assertTrue("Provider 'cloudflare-ai-gateway' is not configured" in (error.errorMessage ?: ""))
            assertTrue(transport.requests.isEmpty(), "no request must be sent")
        }
    }
}
