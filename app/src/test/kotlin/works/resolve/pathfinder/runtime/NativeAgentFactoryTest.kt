package works.resolve.pathfinder.runtime

import works.resolve.pathfinder.agent.*
import works.resolve.pathfinder.codingagent.core.buildSystemPrompt

import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
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

class NativeAgentFactoryTest {

    /** Compaction off: URL/auth tests are single-request and not about compaction. */
    private val COMPACT_OFF = works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings(enabled = false, reserveTokens = 16384, keepRecentTokens = 20000)

    private fun emptyConversation(): works.resolve.pathfinder.codingagent.core.session.Conversation =
        works.resolve.pathfinder.codingagent.core.session.Conversation(emptyList(), null)

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

    @Test
    fun `copies the initial transcript into the agent`() {
        val transcript: MutableList<works.resolve.pathfinder.ai.Message> = mutableListOf(
            UserMessage.ofText("hello"),
            UserMessage.ofText("again"),
        )
        val agent = factory(FakeCredentialStore(ApiKeyCredential("k")), RecordingTransport())
            .create(settings(), "s1", works.resolve.pathfinder.codingagent.core.session.Conversation.fromMessages(transcript))
        assertEquals(transcript, agent.state.value.messages)
        transcript.clear()
        assertEquals(2, agent.state.value.messages.size)
    }

    @Test
    fun `passes configured tools to created agents and defaults to none`() {
        val tool = object : AgentTool {
            override val definition = works.resolve.pathfinder.ai.Tool("t", "test", JsonPrimitive("object"))
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

    /** Fake production tool with a prompt snippet so prompt rebuild is observable. */
    private class FakeFactoryTool(
        override val definition: works.resolve.pathfinder.ai.Tool = works.resolve.pathfinder.ai.Tool("web_search", "search", JsonPrimitive("object")),
        override val label: String = "web_search",
        override val promptSnippet: String? = "does web_search",
    ) : AgentTool {
        override fun validateArguments(arguments: kotlinx.serialization.json.JsonObject) = arguments
        override suspend fun execute(
            toolCallId: String,
            arguments: kotlinx.serialization.json.JsonObject,
            onUpdate: AgentToolUpdateCallback,
        ) = AgentToolResult(content = listOf(TextContent("done")))
    }

    @Test
    fun `a factory-created session can disable and re-enable configured tools by name`() = runBlocking {
        val webSearch = FakeFactoryTool()
        val configured = mutableListOf<AgentTool>(webSearch)
        val agent = NativeAgentFactory(
            credentials = FakeCredentialStore(ApiKeyCredential("k")),
            catalog = catalog,
            transport = RecordingTransport(),
            tools = configured,
        ).create(settings(), "s1", emptyConversation())

        assertEquals(listOf("web_search"), agent.getActiveToolNames())
        assertEquals(buildSystemPrompt(listOf(webSearch)), agent.state.value.systemPrompt)

        agent.setActiveToolsByName(emptyList())
        assertEquals(emptyList<String>(), agent.getActiveToolNames())
        assertEquals(emptyList<AgentTool>(), agent.state.value.tools)
        assertNull(agent.state.value.systemPrompt)

        agent.setActiveToolsByName(listOf("web_search", "unknown"))
        assertEquals(listOf("web_search"), agent.getActiveToolNames())
        assertEquals(buildSystemPrompt(listOf(webSearch)), agent.state.value.systemPrompt)

        configured.clear()
        assertEquals(listOf("web_search"), agent.getActiveToolNames())
        agent.setActiveToolsByName(emptyList())
        agent.setActiveToolsByName(listOf("web_search"))
        assertEquals(listOf("web_search"), agent.getActiveToolNames())
    }

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
            assertTrue(last is works.resolve.pathfinder.ai.AssistantMessage)
            assertEquals("Hi", (last.content.single() as TextContent).text)

            assertEquals(1, store.readCalls)
            val request = transport.requests.single()
            assertEquals("https://api.z.ai/api/coding/paas/v4/chat/completions", request.url)
            assertEquals("factory-test-key-2", request.bearerToken)
            val timeoutMs = request.timeoutMs
            assertTrue(timeoutMs != null && timeoutMs > 0)

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
            assertNull(request.bearerToken)
            assertEquals("Bearer cf-factory-test-key", request.headers["cf-aig-authorization"])
            assertFalse(request.headers.containsKey("Authorization"))
            assertFalse(request.headers.containsKey("authorization"))
        }
    }

    @Test
    fun `incomplete explicit cloudflare key env is unconfigured with no api call`() {
        runBlocking {
            val store = FakeCredentialStore(null)
            val entry = catalog.getProvider("cloudflare-ai-gateway")!!
            val transport = RecordingTransport()
            val provider = entry.toRuntimeProvider(
                transport = transport,
                authResolver = catalogAuthResolver(entry, store),
            )

            val events = Models(listOf(provider)).stream(
                entry.model("workers-ai/test-model")!!,
                works.resolve.pathfinder.ai.Context(messages = emptyList()),
                SimpleStreamOptions(
                    apiKey = "cf-explicit-key",
                    env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct-only"),
                ),
            ).toList()

            val error = events.single() as works.resolve.pathfinder.ai.AssistantMessageEvent.Error
            assertTrue(
                "Provider 'cloudflare-ai-gateway' is not configured" in (error.error.errorMessage ?: ""),
            )
            assertTrue(transport.requests.isEmpty(), "no request must be sent")
        }
    }

    @Test
    fun `explicit env overrides stored env before auth shaping`() {
        runBlocking {
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

            assertEquals(
                mapOf(
                    "CLOUDFLARE_ACCOUNT_ID" to "explicit-acct",
                    "CLOUDFLARE_GATEWAY_ID" to "stored-gw",
                ),
                auth.env,
            )
            assertEquals("Bearer cf-factory-test-key", auth.headers["cf-aig-authorization"])

            val transport = RecordingTransport()
            val provider = entry.toRuntimeProvider(
                transport = transport,
                authResolver = catalogAuthResolver(entry, store),
            )
            Models(listOf(provider)).stream(
                entry.model("workers-ai/test-model")!!,
                works.resolve.pathfinder.ai.Context(messages = emptyList()),
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
            // pi invariant: the request base URL defaults from model.baseUrl.
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
                authRegistry = ProductionCatalogAuthRegistry(),
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
    fun `resolveModel validates and normalizes a catalog model for switching`() {
        val factory = NativeAgentFactory(FakeCredentialStore(ApiKeyCredential("k")), catalog, RecordingTransport())
        val resolved = factory.resolveModel("github-copilot", "gpt-4.1")
        assertEquals("gpt-4.1", resolved.id)
        assertEquals("github-copilot", resolved.provider)
        assertEquals("https://api.individual.githubcopilot.com", resolved.baseUrl)

        assertFailsWith<IllegalArgumentException> { factory.resolveModel("nope", "gpt-4.1") }
        assertFailsWith<IllegalArgumentException> { factory.resolveModel("zai", "gpt-4.1") }
    }

    @Test
    fun `switching to another catalog provider routes the next prompt cross-provider`() {
        runBlocking {
            // GitHub Copilot needs a token credential for the auth check.
            val store = FakeCredentialStore(ApiKeyCredential("gh-factory-test-token"))
            val transport = RecordingTransport()
            val native = NativeAgentFactory(credentials = store, catalog = catalog, transport = transport)
            val agent = native.create(settings(), "s1", emptyConversation())

            agent.prompt("ping") // initial provider: zai/glm-4.7
            assertEquals("https://api.z.ai/api/coding/paas/v4/chat/completions", transport.requests[0].url)

            agent.setModel(native.resolveModel("github-copilot", "gpt-4.1"))
            agent.prompt("pong")

            assertEquals(2, transport.requests.size)
            val switched = transport.requests[1]
            assertEquals("https://api.individual.githubcopilot.com/chat/completions", switched.url)
            assertEquals("gh-factory-test-token", switched.bearerToken)
            val body = Json.parseToJsonElement(String(switched.body)).jsonObject
            assertEquals("gpt-4.1", body["model"]!!.jsonPrimitive.content)

            val state = agent.state.value
            assertEquals(4, state.messages.size)
            val entries = agent.conversation.entries
            assertTrue(entries[2] is works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry)
            assertEquals("Hi", ((state.messages[1] as works.resolve.pathfinder.ai.AssistantMessage).content.single() as TextContent).text)
        }
    }

    @Test
    fun `setModel rejects a provider without a stored credential`() {
        runBlocking {
            val store = FakeCredentialStore(null)
            val native = NativeAgentFactory(credentials = store, catalog = catalog, transport = RecordingTransport())
            val agent = native.create(settings(), "s1", emptyConversation())

            val error = runCatching { agent.setModel(native.resolveModel("github-copilot", "gpt-4.1")) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue((error as IllegalStateException).message!!.contains("No API key for github-copilot/gpt-4.1"))
            assertEquals("glm-4.7", agent.model.id)
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
            val error = assertIs<works.resolve.pathfinder.ai.AssistantMessage>(last)
            assertEquals(works.resolve.pathfinder.ai.StopReason.ERROR, error.stopReason)
            assertTrue("Provider 'cloudflare-ai-gateway' is not configured" in (error.errorMessage ?: ""))
            assertTrue(transport.requests.isEmpty(), "no request must be sent")
        }
    }
}
