package works.resolve.pathfinder.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.AuthCheck
import works.resolve.pathfinder.ai.auth.AuthContext
import works.resolve.pathfinder.ai.auth.AuthResult
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.CatalogProviderAuth
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.CredentialInfo
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.CredentialType
import works.resolve.pathfinder.ai.auth.InMemoryCredentialStore
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ModelsErrorCode
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.testing.TestCatalogs
import works.resolve.pathfinder.ai.testing.credentialApiKeyAuth
import works.resolve.pathfinder.ai.testing.fixedApiKeyAuth
import works.resolve.pathfinder.ai.testing.testApiKeyAuth
import works.resolve.pathfinder.ai.testing.testProviderAuth

class ModelsTest {
    private fun model() = Model(
        id = "m1",
        name = "Model One",
        api = "openai-completions",
        provider = "prov",
        baseUrl = "https://example.test"
    )

    private class RecordingApi : ChatApi {
        var lastApiKey: String? = null
        var lastEnv: Map<String, String> = emptyMap()
        var lastHeaders: Map<String, String?> = emptyMap()
        var calls = 0

        override fun streamSimple(
            model: Model,
            context: TranscriptContext,
            options: SimpleStreamOptions
        ): Flow<AssistantMessageEvent> = flow {
            calls += 1
            lastApiKey = options.apiKey
            lastEnv = options.env
            lastHeaders = options.headers
            val done = AssistantMessage(
                content = emptyList(),
                api = model.api,
                provider = model.provider,
                model = model.id,
                stopReason = StopReason.STOP
            )
            emit(AssistantMessageEvent.Done(done.stopReason, done))
        }
    }

    private class FailingStore(private val failRead: Boolean = false) : CredentialStore {
        private val delegate = InMemoryCredentialStore()

        override suspend fun read(providerId: String): Credential? {
            if (failRead) throw IllegalStateException("store unavailable")
            return delegate.read(providerId)
        }

        override suspend fun list(): List<CredentialInfo> = delegate.list()

        override suspend fun modify(
            providerId: String,
            update: suspend (Credential?) -> Credential?
        ): Credential? = delegate.modify(providerId, update)

        override suspend fun delete(providerId: String) = delegate.delete(providerId)
    }

    private class FakeOAuth(
        private val onRefresh: suspend (OAuthCredential) -> OAuthCredential = { it }
    ) : OAuthAuth {
        var refreshCalls = 0

        override val name = "Fake OAuth"

        override suspend fun login(
            interaction: works.resolve.pathfinder.ai.auth.AuthInteraction,
            options: works.resolve.pathfinder.ai.auth.LoginOptions?
        ): OAuthCredential = OAuthCredential(access = "a", refresh = "r", expires = Long.MAX_VALUE)

        override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
            refreshCalls++
            return onRefresh(credential)
        }

        override suspend fun toAuth(credential: OAuthCredential) =
            ModelAuth(apiKey = credential.access)
    }

    private fun provider(
        api: ChatApi = RecordingApi(),
        auth: works.resolve.pathfinder.ai.auth.ProviderAuth = fixedApiKeyAuth("resolved-key"),
        models: List<Model> = listOf(model())
    ) = Provider(
        id = "prov",
        name = "Provider",
        baseUrl = "https://example.test",
        auth = auth,
        models = models,
        apis = mapOf("openai-completions" to api)
    )

    @Test
    fun modelWhoseApiHasNoImplementationFailsClearly() = runTest {
        val api = RecordingApi()
        val unsupported = model().copy(id = "m2", api = "anthropic-messages")
        val registry = Models(
            listOf(provider(api = api, models = listOf(model(), unsupported)))
        )

        val events = registry.stream(unsupported, Context(messages = emptyList())).toList()

        assertEquals(1, events.size)
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals(
            "Provider prov has no API implementation for \"anthropic-messages\"",
            error.error.errorMessage
        )
        assertEquals(Usage(), error.error.usage)
        assertEquals(0, api.calls)
    }

    @Test
    fun unknownProviderSurfacesAsTerminalErrorEvent() = runTest {
        val registry = Models(emptyList())
        val alien = model().copy(provider = "alien")

        val events = registry.stream(alien, Context(messages = emptyList())).toList()

        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals("Unknown provider: alien", error.error.errorMessage)
        assertEquals(Usage(), error.error.usage)
    }

    @Test
    fun adapterSetupThrowBecomesTerminalErrorEvent() = runTest {
        val api = object : ChatApi {
            var calls = 0

            override fun streamSimple(
                model: Model,
                context: TranscriptContext,
                options: SimpleStreamOptions
            ): Flow<AssistantMessageEvent> {
                calls += 1
                throw ProviderAuthException("No API key for provider: ${model.provider}")
            }
        }
        val registry = Models(listOf(provider(api = api)))

        val events = registry.stream(model(), Context(messages = emptyList())).toList()

        assertEquals(1, api.calls)
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals("No API key for provider: prov", error.error.errorMessage)
    }

    @Test
    fun checkAuthPropagatesCredentialStoreReadFailure() = runTest {
        val registry = Models(
            listOf(provider()),
            credentials = FailingStore(failRead = true)
        )

        val error = assertFailsWith<ModelsError> { registry.checkAuth("prov") }
        assertEquals(ModelsErrorCode.AUTH, error.code)
        assertEquals(
            "Credential store read failed for prov: store unavailable",
            error.message
        )
        assertFalse(registry.hasConfiguredAuth("prov"))
    }

    @Test
    fun checkAuthPropagatesApiKeyCheckFailure() = runTest {
        val registry = Models(
            listOf(
                provider(
                    auth = testProviderAuth(
                        apiKey = testApiKeyAuth(
                            check = { _, _ -> throw IllegalStateException("check refused") },
                            resolve = { _, _ -> null }
                        )
                    )
                )
            )
        )

        val error = assertFailsWith<ModelsError> { registry.checkAuth("prov") }
        assertEquals(ModelsErrorCode.AUTH, error.code)
        assertEquals(
            "API key auth check failed for provider prov: check refused",
            error.message
        )
    }

    @Test
    fun incompleteApiKeyIsStoredButNotConfigured() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("prov") { ApiKeyCredential(key = "sk") }
        val registry = Models(
            listOf(
                provider(
                    auth = testProviderAuth(
                        apiKey = testApiKeyAuth { _, credential ->
                            credential?.env?.get("ACCOUNT")?.let {
                                AuthResult(ModelAuth(apiKey = credential.key))
                            }
                        }
                    )
                )
            ),
            credentials = store
        )

        assertNull(registry.checkAuth("prov"))
        assertFalse(registry.hasConfiguredAuth("prov"))
        assertEquals(CredentialType.API_KEY, registry.storedCredentialType("prov"))
    }

    @Test
    fun storedOAuthCheckDoesNotRefreshOrResolve() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("prov") {
            OAuthCredential(access = "expired", refresh = "r", expires = 0)
        }
        val oauth = FakeOAuth()
        val registry = Models(
            listOf(provider(auth = testProviderAuth(oauth = oauth))),
            credentials = store
        )

        assertEquals(AuthCheck("OAuth", AuthType.OAUTH), registry.checkAuth("prov"))
        assertTrue(registry.hasConfiguredAuth("prov"))
        assertTrue(registry.isUsingOAuth("prov"))
        assertEquals(0, oauth.refreshCalls)
    }

    @Test
    fun getAvailableAppliesTheCopilotFilterToStoredOAuth() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("prov") {
            OAuthCredential(
                access = "a",
                refresh = "r",
                expires = Long.MAX_VALUE,
                extras = mapOf(
                    "availableModelIds" to JsonArray(listOf(JsonPrimitive("m2")))
                )
            )
        }
        val models = listOf(model(), model().copy(id = "m2"))
        val registry = Models(
            listOf(
                Provider(
                    id = "prov",
                    name = "Provider",
                    baseUrl = "https://example.test",
                    auth = testProviderAuth(oauth = FakeOAuth()),
                    models = models,
                    apis = mapOf("openai-completions" to RecordingApi()),
                    filterModels = { available, credential ->
                        works.resolve.pathfinder.ai.providers.filterGitHubCopilotModels(
                            available,
                            credential
                        )
                    }
                )
            ),
            credentials = store
        )

        assertEquals(listOf("m2"), registry.getAvailable().map { it.id })
    }

    @Test
    fun completeSimpleNeverResolvesWithoutATerminalEvent() = runTest {
        val api = object : ChatApi {
            override fun streamSimple(
                model: Model,
                context: TranscriptContext,
                options: SimpleStreamOptions
            ): Flow<AssistantMessageEvent> = flow {
                emit(
                    AssistantMessageEvent.Start(
                        AssistantMessage(
                            content = emptyList(),
                            api = model.api,
                            provider = model.provider,
                            model = model.id
                        )
                    )
                )
            }
        }
        val registry = Models(listOf(provider(api = api)))

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1_000) {
                registry.completeSimple(model(), Context(messages = emptyList()))
            }
        }
    }

    @Test
    fun resolverFailureEmitsSingleErrorEvent() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                provider(
                    api = api,
                    auth = testProviderAuth(
                        apiKey = testApiKeyAuth { _, _ ->
                            throw IllegalStateException("keystore exploded")
                        }
                    )
                )
            )
        )

        val events = registry.stream(model(), Context(messages = emptyList())).toList()

        assertEquals(1, events.size)
        val error = events.single() as AssistantMessageEvent.Error
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals("openai-completions", error.error.api)
        assertEquals("prov", error.error.provider)
        assertEquals("m1", error.error.model)
        assertEquals(StopReason.ERROR, error.error.stopReason)
        assertTrue(error.error.timestamp > 0)
        assertEquals(
            "API key auth failed for provider prov: keystore exploded",
            error.error.errorMessage
        )
        assertEquals(0, api.calls)
    }

    @Test
    fun nullResolutionEmitsSingleErrorEvent() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                provider(
                    api = api,
                    auth = testProviderAuth(apiKey = testApiKeyAuth { _, _ -> null })
                )
            )
        )

        val events = registry.stream(model(), Context(messages = emptyList())).toList()

        assertEquals(1, events.size)
        val error = events.single() as AssistantMessageEvent.Error
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals("Provider is not configured: prov", error.error.errorMessage)
        assertEquals(0, api.calls)
    }

    @Test
    fun missingApiKeyAuthEmitsSingleErrorEvent() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(provider(api = api, auth = testProviderAuth()))
        )

        val events = registry.stream(model(), Context(messages = emptyList())).toList()

        assertEquals(1, events.size)
        val error = events.single() as AssistantMessageEvent.Error
        assertEquals("Provider is not configured: prov", error.error.errorMessage)
        assertEquals(0, api.calls)
    }

    @Test
    fun resolverCancellationPropagatesWithoutError() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                provider(
                    api = api,
                    auth = testProviderAuth(
                        apiKey = testApiKeyAuth { _, _ -> throw CancellationException() }
                    )
                )
            )
        )

        var thrown: Exception? = null
        try {
            registry.stream(model(), Context(messages = emptyList())).toList()
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
        assertEquals(0, api.calls)
    }

    @Test
    fun explicitKeyThatAuthRejectsIsUnconfigured() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                provider(
                    api = api,
                    auth = testProviderAuth(
                        apiKey = testApiKeyAuth { _, credential ->
                            if (credential?.key == null) {
                                AuthResult(ModelAuth(apiKey = "stored-key"))
                            } else {
                                null
                            }
                        }
                    )
                )
            )
        )

        val events = registry
            .stream(
                model(),
                Context(messages = emptyList()),
                SimpleStreamOptions(apiKey = "explicit")
            )
            .toList()

        assertEquals(1, events.size)
        val error = events.single() as AssistantMessageEvent.Error
        assertEquals("Provider is not configured: prov", error.error.errorMessage)
        assertEquals(0, api.calls)
    }

    @Test
    fun explicitApiKeyIsShapedWithoutReadingStoredCredentials() = runTest {
        val store = FailingStore()
        val api = RecordingApi()
        val registry = Models(
            listOf(
                provider(
                    api = api,
                    auth = credentialApiKeyAuth(fallback = "resolved-key")
                )
            ),
            credentials = store
        )

        val events = registry
            .stream(
                model(),
                Context(messages = emptyList()),
                SimpleStreamOptions(apiKey = "explicit", env = mapOf("A" to "explicit-a"))
            )
            .toList()

        assertEquals(1, api.calls)
        assertEquals("explicit", api.lastApiKey)
        assertTrue(events.single() is AssistantMessageEvent.Done)
    }

    @Test
    fun cloudflareExplicitKeyResolvesToHeaderAuthAndKeepsTheExplicitKey() = runTest {
        val entry = TestCatalogs.CLOUDFLARE
        val api = RecordingApi()
        val registry = Models(
            listOf(
                Provider(
                    id = entry.id,
                    name = entry.name,
                    baseUrl = entry.baseUrl,
                    auth = CatalogProviderAuth(entry),
                    models = entry.models,
                    apis = mapOf("openai-completions" to api)
                )
            )
        )

        val events = registry.stream(
            entry.models.single(),
            Context(messages = emptyList()),
            SimpleStreamOptions(
                apiKey = "explicit-cf-key",
                env = mapOf(
                    "CLOUDFLARE_ACCOUNT_ID" to "acct-explicit",
                    "CLOUDFLARE_GATEWAY_ID" to "gw-explicit"
                )
            )
        ).toList()

        assertTrue(events.single() is AssistantMessageEvent.Done)
        // pi's applyAuth precedence (`options.apiKey ?? auth.apiKey`): the
        // explicit request key still flows to the adapter even when the
        // resolution consumed it into auth headers.
        assertEquals("explicit-cf-key", api.lastApiKey)
        assertEquals(
            mapOf(
                "cf-aig-authorization" to "Bearer explicit-cf-key",
                "Authorization" to null,
                "x-api-key" to null
            ),
            api.lastHeaders
        )
        assertEquals(
            mapOf(
                "CLOUDFLARE_ACCOUNT_ID" to "acct-explicit",
                "CLOUDFLARE_GATEWAY_ID" to "gw-explicit"
            ),
            api.lastEnv
        )
    }

    @Test
    fun resolvedCredentialMergesWithExplicitOptionsUsingPiPrecedence() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                provider(
                    api = api,
                    auth = testProviderAuth(
                        apiKey = testApiKeyAuth { _, _ ->
                            AuthResult(
                                auth = ModelAuth(
                                    apiKey = "resolved-key",
                                    headers = mapOf(
                                        "cf-aig-authorization" to "Bearer resolved-key",
                                        "Authorization" to null
                                    )
                                ),
                                env = mapOf("A" to "resolved-a", "B" to "resolved-b")
                            )
                        }
                    )
                )
            )
        )

        val events = registry.stream(
            model(),
            Context(messages = emptyList()),
            SimpleStreamOptions(
                env = mapOf("B" to "explicit-b", "C" to "explicit-c"),
                headers = mapOf("CF-AIG-AUTHORIZATION" to "Bearer explicit-override")
            )
        ).toList()

        assertTrue(events.single() is AssistantMessageEvent.Done)
        assertEquals("resolved-key", api.lastApiKey)
        assertEquals(
            mapOf("A" to "resolved-a", "B" to "explicit-b", "C" to "explicit-c"),
            api.lastEnv
        )
        // Explicit request headers win over resolved auth headers case-insensitively.
        assertEquals(
            mapOf(
                "Authorization" to null,
                "CF-AIG-AUTHORIZATION" to "Bearer explicit-override"
            ),
            api.lastHeaders
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun settleCredentialWritesAwaitsAStartedRefresh() = runTest {
        val delegate = InMemoryCredentialStore()
        delegate.modify("prov") {
            OAuthCredential(access = "expired", refresh = "r", expires = 0)
        }
        val modifyStarted = CompletableDeferred<Unit>()
        val releaseModify = CompletableDeferred<Unit>()
        val store = object : CredentialStore by delegate {
            override suspend fun modify(
                providerId: String,
                update: suspend (Credential?) -> Credential?
            ): Credential? {
                modifyStarted.complete(Unit)
                releaseModify.await()
                return delegate.modify(providerId, update)
            }
        }
        val oauth = FakeOAuth()
        val registry = Models(
            listOf(provider(auth = testProviderAuth(oauth = oauth))),
            credentials = store
        )

        val resolve = launch { registry.getAuth("prov") }
        modifyStarted.await()
        val settle = launch { registry.settleCredentialWrites() }
        runCurrent()
        assertFalse(settle.isCompleted, "settle must wait for the in-flight refresh write")

        releaseModify.complete(Unit)
        resolve.join()
        settle.join()
        assertEquals(1, oauth.refreshCalls)
    }

    @Test
    fun openCodeSessionHeaderFollowsTheRequestTarget() = runTest {
        val api = RecordingApi()
        val openCodeModel = model().copy(
            provider = "custom",
            baseUrl = "https://opencode.ai/zen/v1"
        )
        val registry = Models(
            listOf(
                Provider(
                    id = "custom",
                    name = "Custom",
                    baseUrl = openCodeModel.baseUrl,
                    auth = fixedApiKeyAuth("key"),
                    models = listOf(openCodeModel),
                    apis = mapOf("openai-completions" to api)
                )
            )
        )

        registry.stream(
            openCodeModel,
            Context(messages = emptyList()),
            SimpleStreamOptions(sessionId = "session-1")
        ).toList()

        assertEquals("session-1", api.lastHeaders["x-opencode-session"])
    }

    @Test
    fun `modelsAreEqual compares id and provider, null never equal`() {
        val m = model()
        assertTrue(Models.modelsAreEqual(m, m))
        assertTrue(Models.modelsAreEqual(m, m.copy(name = "renamed")))
        assertTrue(!Models.modelsAreEqual(m, m.copy(id = "m2")))
        assertTrue(!Models.modelsAreEqual(m, m.copy(provider = "other")))
        assertTrue(!Models.modelsAreEqual(m, null))
        assertTrue(!Models.modelsAreEqual(null, m))
        assertTrue(!Models.modelsAreEqual(null, null))
    }
}
