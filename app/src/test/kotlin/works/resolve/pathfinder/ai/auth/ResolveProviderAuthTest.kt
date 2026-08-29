package works.resolve.pathfinder.ai.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Ports the semantics of pi `packages/ai/src/auth/resolve.ts`. */
class ResolveProviderAuthTest {
    /** Fixed epoch base so expiry boundaries never depend on the wall clock. */
    private val nowMs = 1_770_000_000_000L
    private val clock = works.resolve.pathfinder.ai.testing.FakeClock(nowMs)


    private class RecordingAuthContext(val env: Map<String, String> = emptyMap()) : AuthContext {
        override suspend fun env(name: String): String? = env[name]
        override suspend fun fileExists(path: String): Boolean = false
    }

    private class StubApiKeyAuth(
        val resolved: AuthResult? = null,
        val resolveImpl: (suspend (credential: ApiKeyCredential?) -> AuthResult?)? = null,
    ) : ApiKeyAuth {
        override val name = "Stub API key"
        var resolveCalls = 0
        override suspend fun resolve(ctx: AuthContext, credential: ApiKeyCredential?): AuthResult? {
            resolveCalls++
            return resolveImpl?.invoke(credential) ?: resolved
        }
    }

    private class StubOAuthAuth(
        val refreshed: OAuthCredential,
        val auth: ModelAuth = ModelAuth(apiKey = "token"),
    ) : OAuthAuth {
        override val name = "Stub OAuth"
        var refreshCalls = 0
        val refreshedFrom = mutableListOf<OAuthCredential>()
        override suspend fun login(interaction: AuthInteraction): OAuthCredential = refreshed
        override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
            refreshCalls++
            refreshedFrom += credential
            return refreshed
        }

        override suspend fun toAuth(credential: OAuthCredential): ModelAuth = auth
    }

    private fun provider(apiKey: ApiKeyAuth? = null, oauth: OAuthAuth? = null): AuthProviderRef =
        object : AuthProviderRef {
            override val id = "openai"
            override val auth = object : ProviderAuth {
                override val apiKey = apiKey
                override val oauth = oauth
            }
        }

    @Test
    fun `ambient env resolves when nothing is stored`() = runTest {
        val store = InMemoryCredentialStore()
        val apiKey = StubApiKeyAuth(resolved = AuthResult(ModelAuth(apiKey = "env-key"), source = "OPENAI_API_KEY"))
        val result = resolveProviderAuth(provider(apiKey = apiKey), store, RecordingAuthContext(), clock = clock)
        assertEquals("env-key", result?.auth?.apiKey)
        assertEquals("OPENAI_API_KEY", result?.source)
    }

    @Test
    fun `stored credential owns the provider over ambient env`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { ApiKeyCredential(key = "stored") }
        lateinit var seen: ApiKeyCredential
        val apiKey = StubApiKeyAuth(
            resolveImpl = { credential ->
                seen = credential!!
                AuthResult(ModelAuth(apiKey = credential?.key))
            },
        )
        val result = resolveProviderAuth(provider(apiKey = apiKey), store, RecordingAuthContext(env = mapOf("OPENAI_API_KEY" to "env-key")), clock = clock)
        assertEquals("stored", result?.auth?.apiKey)
        assertEquals("stored", seen.key)
    }

    @Test
    fun `explicit apiKey override bypasses stored credentials`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { ApiKeyCredential(key = "stored") }
        lateinit var seen: ApiKeyCredential
        val apiKey = StubApiKeyAuth(
            resolveImpl = { credential ->
                seen = credential!!
                AuthResult(ModelAuth(apiKey = credential?.key))
            },
        )
        val result = resolveProviderAuth(
            provider(apiKey = apiKey),
            store,
            RecordingAuthContext(),
            AuthResolutionOverrides(apiKey = "explicit", env = mapOf("A" to "1")),
            clock = clock,
        )
        assertEquals("explicit", result?.auth?.apiKey)
        assertEquals(mapOf("A" to "1"), seen.env)
    }

    @Test
    fun `override env merges over stored env per field`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { ApiKeyCredential(key = "stored", env = mapOf("A" to "stored-a", "B" to "stored-b")) }
        lateinit var seen: ApiKeyCredential
        val apiKey = StubApiKeyAuth(
            resolveImpl = { credential ->
                seen = credential!!
                AuthResult(ModelAuth(apiKey = credential?.key), env = credential?.env ?: emptyMap())
            },
        )
        val result = resolveProviderAuth(
            provider(apiKey = apiKey),
            store,
            RecordingAuthContext(),
            AuthResolutionOverrides(env = mapOf("A" to "override-a")),
            clock = clock,
        )
        assertEquals(mapOf("A" to "override-a", "B" to "stored-b"), result?.env)
        assertEquals("override-a", seen.env["A"])
    }

    @Test
    fun `unconfigured provider resolves null`() = runTest {
        val store = InMemoryCredentialStore()
        val apiKey = StubApiKeyAuth(resolved = null)
        assertNull(resolveProviderAuth(provider(apiKey = apiKey), store, RecordingAuthContext(), clock = clock))
    }

    @Test
    fun `provider without apiKey handler and no stored credential resolves null`() = runTest {
        val store = InMemoryCredentialStore()
        assertNull(resolveProviderAuth(provider(), store, RecordingAuthContext(), clock = clock))
    }

    @Test
    fun `valid stored oauth credential derives auth without refresh`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { OAuthCredential(access = "a", refresh = "r", expires = nowMs + 60 * 60 * 1000L) }
        val oauth = StubOAuthAuth(refreshed = OAuthCredential("a2", "r2", nowMs + 60 * 60 * 1000L))
        val result = resolveProviderAuth(provider(oauth = oauth), store, RecordingAuthContext(), clock = clock)
        assertEquals(ModelAuth(apiKey = "token"), result?.auth)
        assertEquals("OAuth", result?.source)
        assertEquals(0, oauth.refreshCalls)
    }

    @Test
    fun `expiring stored oauth refreshes once under the store lock and persists`() = runTest {
        val store = InMemoryCredentialStore()
        val expiring = OAuthCredential(access = "a", refresh = "r", expires = nowMs + 1000L)
        store.modify("openai") { expiring }
        val refreshed = OAuthCredential(access = "a2", refresh = "r2", expires = nowMs + 60 * 60 * 1000L)
        val oauth = StubOAuthAuth(refreshed = refreshed)

        val results = List(8) {
            async { resolveProviderAuth(provider(oauth = oauth), store, RecordingAuthContext(), clock = clock) }
        }.awaitAll()

        assertEquals(1, oauth.refreshCalls)
        assertEquals(expiring, oauth.refreshedFrom.single())
        assertTrue(results.all { it?.auth?.apiKey == "token" })
        assertEquals(refreshed, store.read("openai"))
    }

    @Test
    fun `expired oauth refresh keeps ambient env out (no silent fallback)`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { OAuthCredential(access = "a", refresh = "r", expires = nowMs - 1000L) }
        val oauth = object : OAuthAuth by StubOAuthAuth(refreshed = OAuthCredential("a2", "r2", nowMs - 1000L)) {
            override suspend fun refresh(credential: OAuthCredential): OAuthCredential = error("invalid_grant")
        }
        val error = assertFailsWith<ModelsError> {
            resolveProviderAuth(provider(oauth = oauth), store, RecordingAuthContext(), clock = clock)
        }
        assertEquals(ModelsErrorCode.OAUTH, error.code)
        // Failed refresh preserves the credential for re-login.
        assertEquals("r", (store.read("openai") as OAuthCredential).refresh)
    }

    @Test
    fun `explicit minimum validity is enforced after refresh`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { OAuthCredential(access = "a", refresh = "r", expires = nowMs) }
        val stillSoon = OAuthCredential(access = "a2", refresh = "r2", expires = nowMs + 60 * 1000L)
        val oauth = StubOAuthAuth(refreshed = stillSoon)
        val error = assertFailsWith<ModelsError> {
            resolveProviderAuth(
                provider(oauth = oauth),
                store,
                RecordingAuthContext(),
                AuthResolutionOverrides(minOAuthValidityMs = 10 * 60 * 1000L),
                clock = clock,
            )
        }
        assertEquals(ModelsErrorCode.OAUTH, error.code)
        assertEquals(1, oauth.refreshCalls)
    }

    @Test
    fun `api key resolve failure wraps as auth error`() = runTest {
        val store = InMemoryCredentialStore()
        val apiKey = StubApiKeyAuth(resolveImpl = { error("ambient failure") })
        val error = assertFailsWith<ModelsError> {
            resolveProviderAuth(provider(apiKey = apiKey), store, RecordingAuthContext(), clock = clock)
        }
        assertEquals(ModelsErrorCode.AUTH, error.code)
        assertTrue(error.message.orEmpty().contains("API key auth failed for provider openai"))
        assertTrue(error.message.orEmpty().contains("ambient failure"))
    }

    @Test
    fun `stored credential without matching handler resolves null`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { OAuthCredential("a", "r", nowMs + 60 * 60 * 1000L) }
        // OAuth credential stored, but only apiKey handler configured.
        val apiKey = StubApiKeyAuth(resolved = AuthResult(ModelAuth(apiKey = "ambient")))
        assertNull(resolveProviderAuth(provider(apiKey = apiKey), store, RecordingAuthContext(), clock = clock))
    }

    @Test
    fun `oauth refresh is bounded by the 15s internal timeout`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { OAuthCredential(access = "a", refresh = "r", expires = nowMs) }
        val oauth = object : OAuthAuth by StubOAuthAuth(refreshed = OAuthCredential("a2", "r2", nowMs + 60 * 60 * 1000L)) {
            override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
                delay(Long.MAX_VALUE) // virtual time: fast-forwards past the 15s bound
                error("unreachable")
            }
        }
        val error = assertFailsWith<ModelsError> {
            resolveProviderAuth(provider(oauth = oauth), store, RecordingAuthContext(), clock = clock)
        }
        assertEquals(ModelsErrorCode.OAUTH, error.code)
        assertTrue(error.message.orEmpty().contains("timed out"))
        // Internal timeout is not caller cancellation and does not tear down the caller.
    }

    @Test
    fun `caller cancellation during refresh propagates as cancellation, not ModelsError`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { OAuthCredential(access = "a", refresh = "r", expires = nowMs) }
        val oauth = object : OAuthAuth by StubOAuthAuth(refreshed = OAuthCredential("a2", "r2", nowMs + 60 * 60 * 1000L)) {
            override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
                awaitCancellation()
            }
        }
        val job = launch {
            try {
                resolveProviderAuth(provider(oauth = oauth), store, RecordingAuthContext(), clock = clock)
            } catch (e: ModelsError) {
                error("cancellation must not be wrapped: ${e.message}")
            }
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `caller cancellation during api key resolve propagates as cancellation`() = runTest {
        val store = InMemoryCredentialStore()
        val apiKey = StubApiKeyAuth(
            resolveImpl = { awaitCancellation() },
        )
        val job = launch {
            try {
                resolveProviderAuth(provider(apiKey = apiKey), store, RecordingAuthContext(), clock = clock)
            } catch (e: ModelsError) {
                error("cancellation must not be wrapped: ${e.message}")
            }
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `caller cancellation during credential read propagates as cancellation`() = runTest {
        val store = object : CredentialStore by InMemoryCredentialStore() {
            override suspend fun read(providerId: String): Credential? = awaitCancellation()
        }
        val job = launch {
            try {
                resolveProviderAuth(provider(), store, RecordingAuthContext(), clock = clock)
            } catch (e: ModelsError) {
                error("cancellation must not be wrapped: ${e.message}")
            }
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `outer caller withTimeout cancellation is not wrapped as a refresh timeout`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { OAuthCredential(access = "a", refresh = "r", expires = nowMs) }
        val oauth = object : OAuthAuth by StubOAuthAuth(refreshed = OAuthCredential("a2", "r2", nowMs + 60 * 60 * 1000L)) {
            override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
                delay(Long.MAX_VALUE) // virtual time advances past the caller's 1s bound
                error("unreachable")
            }
        }
        val thrown = withTimeoutOrNull(1000) {
            try {
                resolveProviderAuth(provider(oauth = oauth), store, RecordingAuthContext(), clock = clock)
                null
            } catch (e: ModelsError) {
                error("outer cancellation must not be wrapped: ${e.message}")
            }
        }
        // The caller's shorter timeout wins and surfaces as cancellation,
        // not as an OAUTH ModelsError.
        assertNull(thrown)
    }

    @Test
    fun `blank override env value falls through to ambient`() = runTest {
        val store = InMemoryCredentialStore()
        // Ambient path: overrides.env overlays the context (pi's
        // overlayEnvAuthContext `env[name] || base.env(name)`), so a blank
        // override must not suppress the ambient value.
        val apiKey = object : ApiKeyAuth {
            override val name = "stub"
            override suspend fun resolve(ctx: AuthContext, credential: ApiKeyCredential?): AuthResult? =
                AuthResult(ModelAuth(apiKey = ctx.env("A")))
        }
        val result = resolveProviderAuth(
            provider(apiKey = apiKey),
            store,
            RecordingAuthContext(env = mapOf("A" to "ambient-a")),
            AuthResolutionOverrides(env = mapOf("A" to "")),
            clock = clock,
        )
        assertEquals("ambient-a", result?.auth?.apiKey)
    }

    @Test
    fun `logout during pending refresh resolves null`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { OAuthCredential(access = "a", refresh = "r", expires = nowMs) }
        val oauth = StubOAuthAuth(refreshed = OAuthCredential("a2", "r2", nowMs + 60 * 60 * 1000L))
        val storeView = object : CredentialStore by store {
            override suspend fun modify(
                providerId: String,
                update: suspend (current: Credential?) -> Credential?,
            ): Credential? {
                // Simulate a logout racing the refresh: entry removed before update runs.
                if (store.read(providerId) != null && (store.read(providerId) as? OAuthCredential)?.access == "a") {
                    store.delete(providerId)
                }
                return store.modify(providerId, update)
            }
        }
        assertNull(resolveProviderAuth(provider(oauth = oauth), storeView, RecordingAuthContext(), clock = clock))
        assertNull(store.read("openai"))
    }
}
