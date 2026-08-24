package works.resolve.aletheia.ai.auth

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import java.io.File
import works.resolve.aletheia.ai.auth.oauth.AnthropicOAuthAuth
import works.resolve.aletheia.ai.auth.oauth.GitHubCopilotOAuthAuth
import works.resolve.aletheia.ai.auth.oauth.KimiCodingOAuthAuth
import works.resolve.aletheia.ai.auth.oauth.OpenRouterOAuthAuth
import works.resolve.aletheia.ai.auth.oauth.XaiOAuthAuth
import works.resolve.aletheia.ai.providers.CatalogProvider
import works.resolve.aletheia.ai.providers.ProviderCatalog

/** Verifies Aletheia's concrete registry uses pi's exact static provider ids. */
class ProductionCatalogAuthRegistryTest {

    @Test
    fun `production registry exposes only the implemented OAuth flows`() {
        assertIs<AnthropicOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(provider("anthropic")))
        assertIs<OpenRouterOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(provider("openrouter")))
        assertIs<KimiCodingOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(provider("kimi-coding")))
        assertIs<XaiOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(provider("xai")))

        // The Copilot flow receives the catalog entry's model ids as pi's
        // GITHUB_COPILOT_MODELS equivalent.
        val copilot = assertIs<GitHubCopilotOAuthAuth>(
            ProductionCatalogAuthRegistry.oauthAuth(
                provider("github-copilot", listOf(model("gpt-4.1"), model("claude-sonnet-5"))),
            ),
        )
        assertEquals(setOf("gpt-4.1", "claude-sonnet-5"), copilot.knownModelIdsForTest())

        // Codex remains hidden until its concrete pi flow is accepted.
        assertNull(ProductionCatalogAuthRegistry.oauthAuth(provider("openai-codex")))
    }

    /**
     * Over the real generated asset: the registered Copilot flow carries the
     * catalog's model ids, and the login-method projection surfaces both the
     * API-key and OAuth methods for the provider.
     */
    @Test
    fun `generated catalog copilot entry projects API key and OAuth login methods`() {
        val catalog = ProviderCatalog.parse(File("src/main/assets/models-catalog.json").readText())
        val copilot = assertIs<GitHubCopilotOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(catalog.getProvider("github-copilot")!!))
        assertEquals(
            catalog.getProvider("github-copilot")!!.models.map { it.id }.toSet(),
            copilot.knownModelIdsForTest(),
        )

        val service = ProviderAuthService(catalog, ProductionCatalogAuthRegistry, InMemoryCredentialStore())
        val methods = service.authMethods("github-copilot")
        assertEquals(2, methods.size)
        assertContains(methods.map { it.type }, AuthType.API_KEY)
        val oauthMethod = methods.single { it.type == AuthType.OAUTH }
        assertEquals("GitHub Copilot", oauthMethod.label)
        assertEquals(true, oauthMethod.isSubscription)
    }

    private fun model(id: String) = works.resolve.aletheia.ai.core.Model(
        id = id,
        name = id,
        api = "openai-completions",
        provider = "github-copilot",
        baseUrl = "https://example.invalid",
    )

    private fun provider(id: String, models: List<works.resolve.aletheia.ai.core.Model> = emptyList()) = CatalogProvider(
        id = id,
        name = id,
        baseUrl = "https://example.invalid",
        models = models,
    )
}
