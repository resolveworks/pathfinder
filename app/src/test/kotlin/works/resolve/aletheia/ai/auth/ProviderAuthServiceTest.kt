package works.resolve.aletheia.ai.auth

import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.providers.CatalogProvider
import works.resolve.aletheia.ai.providers.ProviderAuth as CatalogProviderAuthMetadata
import works.resolve.aletheia.ai.providers.ProviderCatalog
import works.resolve.aletheia.ai.providers.ProviderOAuth
import works.resolve.aletheia.ai.providers.AuthPrompt as CatalogPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [ProviderAuthService] and [CatalogApiKeyAuth.login], mirroring
 * pi's `Models.login`/`logout` and `envApiKeyAuth` semantics.
 */
class ProviderAuthServiceTest {

    /** Records prompts; answers come from a queue of scripted values. */
    private class FakeInteraction(
        val answers: MutableList<String>,
        val events: MutableList<AuthEvent> = mutableListOf(),
    ) : AuthInteraction {
        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            if (answers.isEmpty()) throw IllegalStateException("unexpected prompt")
            return answers.removeAt(0)
        }

        val prompts = mutableListOf<AuthPrompt>()

        override suspend fun notify(event: AuthEvent) {
            events += event
        }
    }

    /** Fake OAuth flow: emits one event, persists nothing itself. */
    private class FakeOAuthAuth(
        override val name: String = "Acme (subscription)",
        override val loginLabel: String? = null,
        override val isSubscription: Boolean = false,
        private val onLogin: suspend () -> OAuthCredential = { oauthCredential() },
    ) : OAuthAuth {
        override suspend fun login(interaction: AuthInteraction): OAuthCredential {
            interaction.notify(AuthEvent.Info("fake oauth"))
            return onLogin()
        }

        override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential

        override suspend fun toAuth(credential: OAuthCredential) = ModelAuth(apiKey = credential.access)
    }

    /** Credential store whose writes can be scripted to fail. */
    private class FailingStore(
        private val delegate: CredentialStore = InMemoryCredentialStore(),
        var failModify: Boolean = false,
        var failDelete: Boolean = false,
        var failRead: Boolean = false,
    ) : CredentialStore {
        override suspend fun read(providerId: String): Credential? {
            if (failRead) throw IllegalStateException("disk error")
            return delegate.read(providerId)
        }

        override suspend fun list(): List<CredentialInfo> = delegate.list()

        override suspend fun modify(
            providerId: String,
            update: suspend (Credential?) -> Credential?,
        ): Credential? {
            if (failModify) throw IllegalStateException("disk error")
            return delegate.modify(providerId, update)
        }

        override suspend fun delete(providerId: String) {
            if (failDelete) throw IllegalStateException("disk error")
            delegate.delete(providerId)
        }
    }

    companion object {
        fun oauthCredential() = OAuthCredential(
        access = "access-token",
        refresh = "refresh-token",
        expires = 1L,
            extras = mapOf("account" to JsonPrimitive("account-id")),
        )
    }

    private fun catalog(
        apiKey: Boolean = true,
        oauth: Boolean = false,
    ): ProviderCatalog {
        val prompts = if (apiKey) {
            listOf(
                CatalogPrompt("ACME_API_KEY", "Enter Acme API key", secret = true),
                CatalogPrompt("ACME_ACCOUNT_ID", "Enter Acme account id", secret = false),
            )
        } else {
            emptyList()
        }
        val provider = CatalogProvider(
            id = "acme",
            name = "Acme",
            baseUrl = "https://api.acme.test",
            auth = CatalogProviderAuthMetadata(
                label = "Acme API key",
                oauth = if (oauth) ProviderOAuth("Acme (subscription)", loginLabel = "Sign in with Acme") else null,
                prompts = prompts,
            ),
            models = listOf(
                Model(
                    id = "acme-1",
                    name = "Acme One",
                    api = "openai-completions",
                    provider = "acme",
                    baseUrl = "https://api.acme.test",
                ),
            ),
        )
        return ProviderCatalog(listOf(provider))
    }

    private fun service(
        catalog: ProviderCatalog,
        store: CredentialStore = InMemoryCredentialStore(),
        oauth: OAuthAuth? = null,
    ) = ProviderAuthService(
        catalog,
        MapCatalogAuthRegistry(if (oauth != null) mapOf("acme" to oauth) else emptyMap()),
        store,
    )

    @Test
    fun `lists api key method for api-key-only provider`() {
        val methods = service(catalog(oauth = false)).authMethods("acme")
        assertEquals(listOf(AuthMethodInfo(AuthType.API_KEY, "Acme API key", false)), methods)
    }

    @Test
    fun `production OpenRouter offers API key and account methods`() {
        val provider = CatalogProvider(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            auth = CatalogProviderAuthMetadata(
                label = "OpenRouter API key",
                oauth = ProviderOAuth("OpenRouter OAuth", loginLabel = "Sign in with OpenRouter"),
                prompts = listOf(CatalogPrompt("OPENROUTER_API_KEY", "Enter OpenRouter API key")),
            ),
            models = listOf(
                Model(
                    id = "test-model",
                    name = "Test Model",
                    api = "openai-completions",
                    provider = "openrouter",
                    baseUrl = "https://openrouter.ai/api/v1",
                ),
            ),
        )
        val methods = ProviderAuthService(
            ProviderCatalog(listOf(provider)),
            ProductionCatalogAuthRegistry,
            InMemoryCredentialStore(),
        ).authMethods("openrouter")

        assertEquals(
            listOf(
                AuthMethodInfo(AuthType.API_KEY, "OpenRouter API key", false),
                AuthMethodInfo(AuthType.OAUTH, "Sign in with OpenRouter", false),
            ),
            methods,
        )
    }

    @Test
    fun `lists oauth method for oauth-only provider`() {
        val methods = service(
            catalog(apiKey = false, oauth = true),
            oauth = FakeOAuthAuth(isSubscription = true),
        ).authMethods("acme")
        // No loginLabel on the flow: falls back to its name (pi's default).
        assertEquals(listOf(AuthMethodInfo(AuthType.OAUTH, "Acme (subscription)", true)), methods)
    }

    @Test
    fun `lists both methods with oauth loginLabel preferred`() {
        val methods = service(
            catalog(apiKey = true, oauth = true),
            oauth = FakeOAuthAuth(
                name = "Acme (subscription)",
                loginLabel = "Sign in with Acme",
                isSubscription = true,
            ),
        ).authMethods("acme")
        assertEquals(
            listOf(
                AuthMethodInfo(AuthType.API_KEY, "Acme API key", false),
                AuthMethodInfo(AuthType.OAUTH, "Sign in with Acme", true),
            ),
            methods,
        )
    }

    @Test
    fun `unknown provider throws provider error for every operation`() = runTest {
        val service = service(catalog())
        val operations: List<suspend () -> Unit> = listOf(
            { service.authMethods("nope") },
            { service.authStatus("nope") },
            { service.login("nope", AuthType.API_KEY, FakeInteraction(mutableListOf())) },
            { service.logout("nope") },
        )
        operations.forEach { operation ->
            try {
                operation()
                fail("expected ModelsError")
            } catch (error: ModelsError) {
                assertEquals(ModelsErrorCode.PROVIDER, error.code)
                assertEquals("Unknown provider: nope", error.message)
            }
        }
    }

    @Test
    fun `api key login prompts in catalog order with secret and text prompts`() = runTest {
        val interaction = FakeInteraction(mutableListOf("sk-test", "account-42"))
        val status = service(catalog()).login("acme", AuthType.API_KEY, interaction)
        assertEquals(AuthStatus("acme", CredentialType.API_KEY), status)
        assertEquals(
            listOf(
                AuthPrompt.Secret("Enter Acme API key"),
                AuthPrompt.Text("Enter Acme account id"),
            ),
            interaction.prompts,
        )
    }

    @Test
    fun `api key login maps first value to key and later values to env`() = runTest {
        val store = InMemoryCredentialStore()
        service(catalog(), store).login(
            "acme",
            AuthType.API_KEY,
            FakeInteraction(mutableListOf("sk-test", "account-42")),
        )
        val credential = store.read("acme")
        assertTrue(credential is ApiKeyCredential)
        credential as ApiKeyCredential
        assertEquals("sk-test", credential.key)
        assertEquals(mapOf("ACME_ACCOUNT_ID" to "account-42"), credential.env)
    }

    @Test
    fun `blank api key value is rejected and nothing is stored`() = runTest {
        val store = InMemoryCredentialStore()
        val interaction = FakeInteraction(mutableListOf("  ", "account-42"))
        try {
            service(catalog(), store).login("acme", AuthType.API_KEY, interaction)
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
            assertEquals("Acme requires a value for ACME_API_KEY", error.message)
        }
        assertEquals(null, store.read("acme"))
        // Only the first prompt ran before rejection.
        assertEquals(1, interaction.prompts.size)
    }

    @Test
    fun `blank later prompt value is rejected and nothing is stored`() = runTest {
        val store = InMemoryCredentialStore()
        try {
            service(catalog(), store).login(
                "acme",
                AuthType.API_KEY,
                FakeInteraction(mutableListOf("sk-test", "")),
            )
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
        }
        assertEquals(null, store.read("acme"))
    }

    @Test
    fun `oauth login persists the returned credential without touching prompts`() = runTest {
        val store = InMemoryCredentialStore()
        val interaction = FakeInteraction(mutableListOf())
        val status = service(catalog(apiKey = false, oauth = true), store, FakeOAuthAuth())
            .login("acme", AuthType.OAUTH, interaction)
        assertEquals(AuthStatus("acme", CredentialType.OAUTH), status)
        assertEquals(oauthCredential(), store.read("acme"))
        assertTrue(interaction.prompts.isEmpty())
    }

    @Test
    fun `switching account to key replaces the credential type`() = runTest {
        val store = InMemoryCredentialStore()
        val service = service(catalog(apiKey = true, oauth = true), store, FakeOAuthAuth())
        service.login("acme", AuthType.OAUTH, FakeInteraction(mutableListOf()))
        assertEquals(CredentialType.OAUTH, service.authStatus("acme").storedType)
        service.login("acme", AuthType.API_KEY, FakeInteraction(mutableListOf("sk-new", "acct")))
        assertEquals(CredentialType.API_KEY, service.authStatus("acme").storedType)
        service.login("acme", AuthType.OAUTH, FakeInteraction(mutableListOf()))
        assertEquals(CredentialType.OAUTH, service.authStatus("acme").storedType)
    }

    @Test
    fun `unsupported method throws auth error`() = runTest {
        val service = service(catalog(apiKey = false, oauth = false))
        try {
            service.login("acme", AuthType.OAUTH, FakeInteraction(mutableListOf()))
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
            assertEquals("Acme does not support OAUTH login", error.message)
        }
        // API key on an OAuth-only catalog entry: no prompts -> no handler.
        val oauthOnly = service(catalog(apiKey = false, oauth = true), oauth = FakeOAuthAuth())
        try {
            oauthOnly.login("acme", AuthType.API_KEY, FakeInteraction(mutableListOf("sk")))
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
            assertEquals("Acme does not support API_KEY login", error.message)
        }
    }

    @Test
    fun `cancelled login propagates cancellation and does not mutate`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("acme") { ApiKeyCredential(key = "old") }
        val cancellingOAuth = FakeOAuthAuth(
            onLogin = { throw CancellationException("user cancelled") },
        )
        try {
            service(catalog(apiKey = false, oauth = true), store, cancellingOAuth)
                .login("acme", AuthType.OAUTH, FakeInteraction(mutableListOf()))
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(CredentialType.API_KEY, serviceAuthStatusType(store))
    }

    private suspend fun serviceAuthStatusType(store: CredentialStore): CredentialType? =
        (store.read("acme") as Credential).type

    @Test
    fun `cancelled prompt propagates cancellation and does not mutate`() = runTest {
        val store = InMemoryCredentialStore()
        val cancellingInteraction = object : AuthInteraction {
            override suspend fun prompt(prompt: AuthPrompt): String = throw CancellationException("cancelled")

            override suspend fun notify(event: AuthEvent) {}
        }
        try {
            service(catalog(), store).login("acme", AuthType.API_KEY, cancellingInteraction)
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(null, store.read("acme"))
    }

    @Test
    fun `failed login wraps in auth error and does not mutate`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("acme") { ApiKeyCredential(key = "old") }
        val failingOAuth = FakeOAuthAuth(onLogin = { throw IllegalStateException("network down") })
        try {
            service(catalog(apiKey = false, oauth = true), store, failingOAuth)
                .login("acme", AuthType.OAUTH, FakeInteraction(mutableListOf()))
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
            assertTrue(error.message!!.contains("network down"))
        }
        assertEquals("old", (store.read("acme") as ApiKeyCredential).key)
    }

    @Test
    fun `storage failures wrap with pi messages`() = runTest {
        val store = FailingStore(failModify = true)
        try {
            service(catalog(), store).login(
                "acme",
                AuthType.API_KEY,
                FakeInteraction(mutableListOf("sk", "acct")),
            )
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
            assertEquals("Credential store modify failed for acme: disk error", error.message)
        }
        val deleteStore = FailingStore(failDelete = true)
        try {
            service(catalog(), deleteStore).logout("acme")
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
            assertEquals("Credential store delete failed for acme: disk error", error.message)
        }
        val readStore = FailingStore(failRead = true)
        try {
            service(catalog(), readStore).authStatus("acme")
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
        }
    }

    @Test
    fun `logout deletes the stored credential`() = runTest {
        val store = InMemoryCredentialStore()
        val service = service(catalog(), store)
        service.login("acme", AuthType.API_KEY, FakeInteraction(mutableListOf("sk", "acct")))
        assertEquals(CredentialType.API_KEY, service.authStatus("acme").storedType)
        service.logout("acme")
        assertEquals(null, service.authStatus("acme").storedType)
        assertEquals(null, store.read("acme"))
    }

    @Test
    fun `no credential values leak through status methods or toString`() = runTest {
        val service = service(catalog(apiKey = true, oauth = true), oauth = FakeOAuthAuth())
        service.login("acme", AuthType.OAUTH, FakeInteraction(mutableListOf()))
        val status = service.authStatus("acme")
        val methods = service.authMethods("acme")
        val surfaces = buildString {
            append(status.toString()).append(' ')
            append(status.providerId).append(' ')
            append(status.storedType.toString()).append(' ')
            methods.forEach { append(it.toString()).append(' ') }
        }
        for (secret in listOf("access-token", "refresh-token", "account-id")) {
            assertTrue("$secret leaked: $surfaces", !surfaces.contains(secret))
        }
    }

    @Test
    fun `blank answer without prompts script is a login failure not a crash loop`() = runTest {
        // A provider whose only prompt is answered blank still yields AUTH.
        val singlePrompt = ProviderCatalog(
            listOf(
                CatalogProvider(
                    id = "acme",
                    name = "Acme",
                    baseUrl = "https://api.acme.test",
                    auth = CatalogProviderAuthMetadata(
                        label = "Acme API key",
                        prompts = listOf(CatalogPrompt("ACME_API_KEY", "Enter Acme API key")),
                    ),
                    models = listOf(
                        Model("acme-1", "Acme One", "openai-completions", "acme", "https://api.acme.test"),
                    ),
                ),
            ),
        )
        val store = InMemoryCredentialStore()
        try {
            service(singlePrompt, store).login("acme", AuthType.API_KEY, FakeInteraction(mutableListOf("")))
            fail("expected ModelsError")
        } catch (error: ModelsError) {
            assertEquals(ModelsErrorCode.AUTH, error.code)
        }
        assertEquals(null, store.read("acme"))
    }
}
