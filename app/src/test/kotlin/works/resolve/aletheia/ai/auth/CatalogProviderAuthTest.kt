package works.resolve.aletheia.ai.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import works.resolve.aletheia.ai.testing.TestCatalogs
import works.resolve.aletheia.ai.providers.CatalogProvider
import works.resolve.aletheia.ai.providers.ProviderCatalog

/**
 * Focused tests for the catalog→auth bridge (`CatalogProviderAuth` and the
 * factory's `catalogAuthResolver`): the pi `auth.resolve` semantics applied
 * to generated catalog providers, including OAuth composition through the
 * registry.
 */
class CatalogProviderAuthTest {

    private class RecordingAuthContext(val env: Map<String, String> = emptyMap()) : AuthContext {
        val envLookups = mutableListOf<String>()
        override suspend fun env(name: String): String? {
            envLookups += name
            return env[name]
        }

        override suspend fun fileExists(path: String): Boolean = false
    }

    private class FakeOAuthAuth(
        private val refreshed: OAuthCredential,
        private val auth: ModelAuth = ModelAuth(apiKey = "oauth-token"),
        private val failRefresh: Boolean = false,
    ) : OAuthAuth {
        override val name = "Fake OAuth"
        override val isSubscription = true
        override val loginLabel = "Sign in with Fake"
        var refreshCalls = 0
        var cancelledRefresh = false

        override suspend fun login(interaction: AuthInteraction): OAuthCredential = refreshed

        override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
            refreshCalls++
            if (failRefresh) throw IllegalStateException("invalid_grant")
            return refreshed
        }

        override suspend fun toAuth(credential: OAuthCredential): ModelAuth {
            if (cancelledRefresh) throw CancellationException("refresh interrupted")
            return auth
        }
    }

    /** OAuth-capable, prompt-less catalog provider (pi's openai-codex shape). */
    private val oauthCatalog: ProviderCatalog = ProviderCatalog.parse(
        """
        {
          "generatedAt": "test",
          "providers": [
            {
              "id": "codex",
              "name": "Codex",
              "baseUrl": "https://codex.test/v1",
              "auth": {
                "label": "Codex",
                "oauth": {"name": "Codex (subscription)", "loginLabel": "Sign in to Codex", "isSubscription": true}
              },
              "models": [
                {
                  "id": "codex-m", "name": "Codex M", "api": "openai-completions", "provider": "codex",
                  "input": ["text"], "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
                  "compat": {"supportsStore": false, "supportsDeveloperRole": false},
                  "contextWindow": 128000, "maxTokens": 8192
                }
              ]
            }
          ]
        }
        """,
    )

    private val codex: CatalogProvider = oauthCatalog.getProvider("codex")!!

    private fun oauthCredential(expiresInMs: Long): OAuthCredential =
        OAuthCredential(access = "stale", refresh = "r1", expires = System.currentTimeMillis() + expiresInMs)

    // ---- stored API key ----

    @Test
    fun `stored api key resolves with stored-credential source`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("zai") { ApiKeyCredential(key = "zai-key") }

        val result = resolveProviderAuth(
            CatalogAuthProviderRef(TestCatalogs.ZAI),
            store,
            RecordingAuthContext(env = mapOf("ZAI_API_KEY" to "ambient")),
        )!!

        assertEquals("zai-key", result.auth.apiKey)
        assertEquals("stored credential", result.source)
        assertEquals(emptyMap(), result.env)
    }

    @Test
    fun `ambient env fills missing prompt values per field`() = runTest {
        // Pi's per-field merge: a stored key with missing env slots picks up
        // ambient values for those slots only; the stored key wins over env.
        val store = InMemoryCredentialStore()
        store.modify("cloudflare-ai-gateway") {
            ApiKeyCredential(key = "cf-key", env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct"))
        }
        val ctx = RecordingAuthContext(env = mapOf("CLOUDFLARE_API_KEY" to "ambient-key", "CLOUDFLARE_GATEWAY_ID" to "gw"))

        val result = resolveProviderAuth(CatalogAuthProviderRef(TestCatalogs.CLOUDFLARE), store, ctx)!!

        // Stored key won; gateway id came from ambient; account id from stored env.
        assertEquals("Bearer cf-key", result.auth.headers["cf-aig-authorization"])
        assertEquals(mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct", "CLOUDFLARE_GATEWAY_ID" to "gw"), result.env)
        assertEquals("CLOUDFLARE_GATEWAY_ID", ctx.envLookups.single())
        assertEquals("stored credential", result.source)
    }

    @Test
    fun `ambient env alone configures a provider when nothing is stored`() = runTest {
        val ctx = RecordingAuthContext(env = mapOf("ZAI_API_KEY" to "ambient-key"))
        val result = resolveProviderAuth(CatalogAuthProviderRef(TestCatalogs.ZAI), InMemoryCredentialStore(), ctx)!!
        assertEquals("ambient-key", result.auth.apiKey)
        assertEquals("ZAI_API_KEY", result.source)
    }

    // ---- explicit key/env overrides ----

    @Test
    fun `explicit key and env shape cloudflare headers without reading the store`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("cloudflare-ai-gateway") { ApiKeyCredential(key = "stored") }
        val ctx = RecordingAuthContext()

        val auth = works.resolve.aletheia.agent.catalogAuthResolver(TestCatalogs.CLOUDFLARE, store, ctx)(
            "cf-explicit-key",
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct", "CLOUDFLARE_GATEWAY_ID" to "gw"),
        )!!

        assertNull(auth.apiKey)
        assertEquals("Bearer cf-explicit-key", auth.headers["cf-aig-authorization"])
        assertEquals(null, auth.headers["Authorization"])
        assertEquals(null, auth.headers["x-api-key"])
        assertEquals(mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct", "CLOUDFLARE_GATEWAY_ID" to "gw"), auth.env)
        assertTrue(ctx.envLookups.isEmpty(), "explicit overrides must not consult ambient env for set fields")
    }

    @Test
    fun `incomplete explicit cloudflare key resolves unconfigured`() = runTest {
        val auth = works.resolve.aletheia.agent.catalogAuthResolver(
            TestCatalogs.CLOUDFLARE,
            InMemoryCredentialStore(),
        )("cf-explicit-key", mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct-only"))
        assertNull(auth)
    }

    // ---- stored OAuth through a registered flow ----

    @Test
    fun `valid stored oauth credential derives auth without refresh`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("codex") { oauthCredential(expiresInMs = 60 * 60 * 1000) }
        val oauth = FakeOAuthAuth(refreshed = oauthCredential(2 * 60 * 60 * 1000))
        val registry = MapCatalogAuthRegistry(mapOf("codex" to oauth))

        val result = resolveProviderAuth(CatalogAuthProviderRef(codex, registry), store, NoopAuthContext)!!

        assertEquals("oauth-token", result.auth.apiKey)
        assertEquals("OAuth", result.source)
        assertEquals(0, oauth.refreshCalls)
    }

    @Test
    fun `expiring stored oauth refreshes once and persists the rotated credential`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("codex") { oauthCredential(expiresInMs = 60 * 1000) } // < 5-minute window
        val rotated = oauthCredential(expiresInMs = 2 * 60 * 60 * 1000)
        val oauth = FakeOAuthAuth(refreshed = rotated)
        val registry = MapCatalogAuthRegistry(mapOf("codex" to oauth))

        val result = resolveProviderAuth(CatalogAuthProviderRef(codex, registry), store, NoopAuthContext)!!

        assertEquals(1, oauth.refreshCalls)
        assertEquals("OAuth", result.source)
        assertEquals(rotated, store.read("codex"), "the refreshed credential must be persisted")
    }

    // ---- naming and composition shape ----

    @Test
    fun `api key auth name uses the catalog label verbatim`() {
        // pi's auth names like "Anthropic API key" already carry the suffix;
        // appending would yield "Anthropic API key API key".
        assertEquals("Z.AI API key", CatalogApiKeyAuth(TestCatalogs.ZAI).name)
        assertEquals("Cloudflare API key", CatalogApiKeyAuth(TestCatalogs.CLOUDFLARE).name)
    }

    @Test
    fun `api key auth name falls back to provider name plus API key without a label`() {
        // Label-less catalog entry: pi's envApiKeyAuth names are "<provider> API key".
        val unlabeled = CatalogProvider(
            id = "unlabeled",
            name = "Unlabeled",
            baseUrl = "https://unlabeled.test/v1",
            auth = works.resolve.aletheia.ai.providers.ProviderAuth(
                prompts = listOf(works.resolve.aletheia.ai.providers.AuthPrompt("UNLABELED_API_KEY", "Key")),
            ),
            models = emptyList(),
        )
        assertEquals("Unlabeled API key", CatalogApiKeyAuth(unlabeled).name)
    }

    @Test
    fun `oauth-only promptless provider has no api key handler`() = runTest {
        // Pi's openai-codex carries no apiKey auth: a prompt-less catalog
        // provider must expose ProviderAuth.apiKey == null, and a stray stored
        // API-key credential resolves as unconfigured (no matching handler).
        val auth = CatalogProviderAuth(codex)
        assertNull(auth.apiKey)

        val store = InMemoryCredentialStore()
        store.modify("codex") { ApiKeyCredential(key = "stray-key") }
        assertNull(resolveProviderAuth(CatalogAuthProviderRef(codex), store, NoopAuthContext))
        // Even an explicit key override has no apiKey handler to shape it.
        assertNull(
            resolveProviderAuth(
                CatalogAuthProviderRef(codex),
                store,
                NoopAuthContext,
                AuthResolutionOverrides(apiKey = "explicit"),
            ),
        )
    }

    @Test
    fun `prompted provider keeps its api key handler`() {
        assertNull(CatalogProviderAuth(codex).apiKey)
        assertTrue(CatalogProviderAuth(TestCatalogs.ZAI).apiKey != null)
    }

    // ---- mismatched/unregistered OAuth ----

    @Test
    fun `stored oauth credential without a registered flow resolves unconfigured`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("codex") { oauthCredential(expiresInMs = 60 * 60 * 1000) }

        val result = resolveProviderAuth(CatalogAuthProviderRef(codex), store, NoopAuthContext)
        assertNull(result)
    }

    @Test
    fun `registered-flow oauth on an api-key provider does not intercept a stored api key`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("zai") { ApiKeyCredential(key = "zai-key") }
        val registry = MapCatalogAuthRegistry(mapOf("zai" to FakeOAuthAuth(oauthCredential(0))))

        val result = resolveProviderAuth(CatalogAuthProviderRef(TestCatalogs.ZAI, registry), store, NoopAuthContext)!!

        assertEquals("zai-key", result.auth.apiKey)
        assertEquals("stored credential", result.source)
    }

    // ---- error and cancellation safety ----

    @Test
    fun `failing oauth refresh surfaces as a ModelsError oauth failure`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("codex") { oauthCredential(expiresInMs = 60 * 1000) }
        val registry = MapCatalogAuthRegistry(mapOf("codex" to FakeOAuthAuth(oauthCredential(0), failRefresh = true)))

        val error = assertFailsWith<ModelsError> {
            resolveProviderAuth(CatalogAuthProviderRef(codex, registry), store, NoopAuthContext)
        }
        assertEquals(ModelsErrorCode.OAUTH, error.code)
        assertTrue("invalid_grant" in (error.message ?: ""))
    }

    @Test
    fun `cancellation during refresh propagates as cancellation, not ModelsError`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("codex") { oauthCredential(expiresInMs = 60 * 1000) }
        val oauth = object : OAuthAuth {
            override val name = "Cancelling OAuth"
            override suspend fun login(interaction: AuthInteraction): OAuthCredential = oauthCredential(0)
            override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
                throw CancellationException("caller cancelled")

            override suspend fun toAuth(credential: OAuthCredential): ModelAuth = ModelAuth()
        }
        val registry = MapCatalogAuthRegistry(mapOf("codex" to oauth))

        val error = assertFailsWith<Throwable> {
            resolveProviderAuth(CatalogAuthProviderRef(codex, registry), store, NoopAuthContext)
        }
        assertIs<CancellationException>(error)
    }

    @Test
    fun `failing credential read wraps as an auth ModelsError`() = runTest {
        val failing = object : CredentialStore by InMemoryCredentialStore() {
            override suspend fun read(providerId: String): Credential? = throw IllegalStateException("disk error")
        }
        val error = assertFailsWith<ModelsError> {
            resolveProviderAuth(CatalogAuthProviderRef(TestCatalogs.ZAI), failing, NoopAuthContext)
        }
        assertEquals(ModelsErrorCode.AUTH, error.code)
        assertTrue("disk error" in (error.message ?: ""))
    }
}
