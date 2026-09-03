package works.resolve.pathfinder.ai.auth

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import java.io.File
import works.resolve.pathfinder.ai.auth.oauth.AnthropicOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.GitHubCopilotOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.KimiCodingOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OpenAiCodexOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OpenRouterOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.XaiOAuthAuth
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderCatalog

class ProductionCatalogAuthRegistryTest {

    @Test
    fun `production registry exposes only the implemented OAuth flows`() {
        assertIs<AnthropicOAuthAuth>(ProductionCatalogAuthRegistry().oauthAuth(provider("anthropic")))
        assertIs<OpenRouterOAuthAuth>(ProductionCatalogAuthRegistry().oauthAuth(provider("openrouter")))
        assertIs<KimiCodingOAuthAuth>(ProductionCatalogAuthRegistry().oauthAuth(provider("kimi-coding")))
        assertIs<XaiOAuthAuth>(ProductionCatalogAuthRegistry().oauthAuth(provider("xai")))
        assertIs<OpenAiCodexOAuthAuth>(ProductionCatalogAuthRegistry().oauthAuth(provider("openai-codex")))

        val copilot = assertIs<GitHubCopilotOAuthAuth>(
            ProductionCatalogAuthRegistry().oauthAuth(
                provider("github-copilot", listOf(model("gpt-4.1"), model("claude-sonnet-5"))),
            ),
        )
        assertEquals(setOf("gpt-4.1", "claude-sonnet-5"), copilot.knownModelIdsForTest())
    }

    @Test
    fun `generated catalog copilot entry projects API key and OAuth login methods`() {
        val catalog = ProviderCatalog.parse(File("src/main/assets/models-catalog.json").readText())
        val copilot = assertIs<GitHubCopilotOAuthAuth>(ProductionCatalogAuthRegistry().oauthAuth(catalog.getProvider("github-copilot")!!))
        assertEquals(
            catalog.getProvider("github-copilot")!!.models.map { it.id }.toSet(),
            copilot.knownModelIdsForTest(),
        )

        val service = ProviderAuthService(catalog, ProductionCatalogAuthRegistry(), InMemoryCredentialStore())
        val methods = service.authMethods("github-copilot")
        assertEquals(2, methods.size)
        assertContains(methods.map { it.type }, AuthType.API_KEY)
        val oauthMethod = methods.single { it.type == AuthType.OAUTH }
        assertEquals("GitHub Copilot", oauthMethod.label)
        assertEquals(true, oauthMethod.isSubscription)
    }

    private fun model(id: String) = works.resolve.pathfinder.ai.core.Model(
        id = id,
        name = id,
        api = "openai-completions",
        provider = "github-copilot",
        baseUrl = "https://example.invalid",
    )

    private fun provider(id: String, models: List<works.resolve.pathfinder.ai.core.Model> = emptyList()) = CatalogProvider(
        id = id,
        name = id,
        baseUrl = "https://example.invalid",
        models = models,
    )
}
