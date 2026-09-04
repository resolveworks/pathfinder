package works.resolve.pathfinder.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.AuthContext
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.AuthResolutionOverrides
import works.resolve.pathfinder.ai.auth.CatalogApiKeyAuth
import works.resolve.pathfinder.ai.auth.CatalogAuthProviderRef
import works.resolve.pathfinder.ai.auth.CatalogProviderAuth
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.InMemoryCredentialStore
import works.resolve.pathfinder.ai.auth.MapCatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ModelsErrorCode
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.ProviderAuth
import works.resolve.pathfinder.ai.auth.resolveProviderAuth
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.testing.TestCatalogs

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
        private val failRefresh: Boolean = false
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
        """
    )

    private val codex: CatalogProvider = oauthCatalog.getProvider("codex")!!

    private fun oauthCredential(expiresInMs: Long): OAuthCredential = OAuthCredential(
        access = "stale",
        refresh = "r1",
        expires =
            System.currentTimeMillis() + expiresInMs
    )

    @Test
    fun `stored api key resolves with stored-credential source`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("zai") { ApiKeyCredential(key = "zai-key") }

        val result = resolveProviderAuth(
            CatalogAuthProviderRef(TestCatalogs.ZAI),
            store,
            RecordingAuthContext(env = mapOf("ZAI_API_KEY" to "ambient"))
        )!!

        assertEquals("zai-key", result.auth.apiKey)
        assertEquals("stored credential", result.source)
        assertEquals(emptyMap(), result.env)
    }

    @Test
    fun `ambient env fills missing prompt values per field`() = runTest {
        // pi's per-field merge: the stored credential wins; missing env
        // slots fall back to ambient values.
        val store = InMemoryCredentialStore()
        store.modify("cloudflare-ai-gateway") {
            ApiKeyCredential(key = "cf-key", env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct"))
        }
        val ctx = RecordingAuthContext(
            env = mapOf(
                "CLOUDFLARE_API_KEY" to "ambient-key",
                "CLOUDFLARE_GATEWAY_ID" to "gw"
            )
        )

        val result = resolveProviderAuth(
            CatalogAuthProviderRef(TestCatalogs.CLOUDFLARE),
            store,
            ctx
        )!!

        assertEquals("Bearer cf-key", result.auth.headers["cf-aig-authorization"])
        assertEquals(
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct", "CLOUDFLARE_GATEWAY_ID" to "gw"),
            result.env
        )
        assertEquals("CLOUDFLARE_GATEWAY_ID", ctx.envLookups.single())
        assertEquals("stored credential", result.source)
    }

    @Test
    fun `ambient env alone configures a provider when nothing is stored`() = runTest {
        val ctx = RecordingAuthContext(env = mapOf("ZAI_API_KEY" to "ambient-key"))
        val result = resolveProviderAuth(
            CatalogAuthProviderRef(TestCatalogs.ZAI),
            InMemoryCredentialStore(),
            ctx
        )!!
        assertEquals("ambient-key", result.auth.apiKey)
        assertEquals("ZAI_API_KEY", result.source)
    }

    @Test
    fun `explicit key and env shape cloudflare headers without reading the store`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("cloudflare-ai-gateway") { ApiKeyCredential(key = "stored") }
        val ctx = RecordingAuthContext()

        val auth = catalogAuthResolver(TestCatalogs.CLOUDFLARE, store, ctx)(
            "cf-explicit-key",
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct", "CLOUDFLARE_GATEWAY_ID" to "gw")
        )!!

        assertNull(auth.apiKey)
        assertEquals("Bearer cf-explicit-key", auth.headers["cf-aig-authorization"])
        assertEquals(null, auth.headers["Authorization"])
        assertEquals(null, auth.headers["x-api-key"])
        assertEquals(
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct", "CLOUDFLARE_GATEWAY_ID" to "gw"),
            auth.env
        )
        assertTrue(
            ctx.envLookups.isEmpty(),
            "explicit overrides must not consult ambient env for set fields"
        )
    }

    @Test
    fun `incomplete explicit cloudflare key resolves unconfigured`() = runTest {
        val auth = catalogAuthResolver(
            TestCatalogs.CLOUDFLARE,
            InMemoryCredentialStore()
        )("cf-explicit-key", mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct-only"))
        assertNull(auth)
    }

    @Test
    fun `valid stored oauth credential derives auth without refresh`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("codex") { oauthCredential(expiresInMs = 60 * 60 * 1000) }
        val oauth = FakeOAuthAuth(refreshed = oauthCredential(2 * 60 * 60 * 1000))
        val registry = MapCatalogAuthRegistry(mapOf("codex" to oauth))

        val result = resolveProviderAuth(
            CatalogAuthProviderRef(codex, registry),
            store,
            NoopAuthContext
        )!!

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

        val result = resolveProviderAuth(
            CatalogAuthProviderRef(codex, registry),
            store,
            NoopAuthContext
        )!!

        assertEquals(1, oauth.refreshCalls)
        assertEquals("OAuth", result.source)
        assertEquals(rotated, store.read("codex"), "the refreshed credential must be persisted")
    }

    @Test
    fun `api key auth name uses the catalog label verbatim`() {
        // pi's auth names already carry the suffix; appending would yield
        // "Anthropic API key API key".
        assertEquals("Z.AI API key", CatalogApiKeyAuth(TestCatalogs.ZAI).name)
        assertEquals("Cloudflare API key", CatalogApiKeyAuth(TestCatalogs.CLOUDFLARE).name)
    }

    @Test
    fun `api key auth name falls back to provider name plus API key without a label`() {
        val unlabeled = CatalogProvider(
            id = "unlabeled",
            name = "Unlabeled",
            baseUrl = "https://unlabeled.test/v1",
            auth = works.resolve.pathfinder.ai.providers.ProviderAuth(
                prompts = listOf(
                    works.resolve.pathfinder.ai.providers.AuthPrompt("UNLABELED_API_KEY", "Key")
                )
            ),
            models = emptyList()
        )
        assertEquals("Unlabeled API key", CatalogApiKeyAuth(unlabeled).name)
    }

    @Test
    fun `oauth-only promptless provider has no api key handler`() = runTest {
        // pi's openai-codex carries no apiKey auth: stored or explicit
        // API-key credentials have no handler to match and resolve unconfigured.
        val auth = CatalogProviderAuth(codex)
        assertNull(auth.apiKey)

        val store = InMemoryCredentialStore()
        store.modify("codex") { ApiKeyCredential(key = "stray-key") }
        assertNull(resolveProviderAuth(CatalogAuthProviderRef(codex), store, NoopAuthContext))
        assertNull(
            resolveProviderAuth(
                CatalogAuthProviderRef(codex),
                store,
                NoopAuthContext,
                AuthResolutionOverrides(apiKey = "explicit")
            )
        )
    }

    @Test
    fun `prompted provider keeps its api key handler`() {
        assertNull(CatalogProviderAuth(codex).apiKey)
        assertTrue(CatalogProviderAuth(TestCatalogs.ZAI).apiKey != null)
    }

    @Test
    fun `stored oauth credential without a registered flow resolves unconfigured`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("codex") { oauthCredential(expiresInMs = 60 * 60 * 1000) }

        val result = resolveProviderAuth(CatalogAuthProviderRef(codex), store, NoopAuthContext)
        assertNull(result)
    }

    @Test
    fun `registered-flow oauth on an api-key provider does not intercept a stored api key`() =
        runTest {
            val store = InMemoryCredentialStore()
            store.modify("zai") { ApiKeyCredential(key = "zai-key") }
            val registry = MapCatalogAuthRegistry(mapOf("zai" to FakeOAuthAuth(oauthCredential(0))))

            val result = resolveProviderAuth(
                CatalogAuthProviderRef(TestCatalogs.ZAI, registry),
                store,
                NoopAuthContext
            )!!

            assertEquals("zai-key", result.auth.apiKey)
            assertEquals("stored credential", result.source)
        }

    @Test
    fun `failing oauth refresh surfaces as a ModelsError oauth failure`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("codex") { oauthCredential(expiresInMs = 60 * 1000) }
        val registry =
            MapCatalogAuthRegistry(
                mapOf("codex" to FakeOAuthAuth(oauthCredential(0), failRefresh = true))
            )

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
            override suspend fun login(interaction: AuthInteraction): OAuthCredential =
                oauthCredential(0)
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
            override suspend fun read(providerId: String): Credential? =
                throw IllegalStateException("disk error")
        }
        val error = assertFailsWith<ModelsError> {
            resolveProviderAuth(CatalogAuthProviderRef(TestCatalogs.ZAI), failing, NoopAuthContext)
        }
        assertEquals(ModelsErrorCode.AUTH, error.code)
        assertTrue("disk error" in (error.message ?: ""))
    }
}
