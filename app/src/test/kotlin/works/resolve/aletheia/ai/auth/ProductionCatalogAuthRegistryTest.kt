package works.resolve.aletheia.ai.auth

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import works.resolve.aletheia.ai.auth.oauth.AnthropicOAuthAuth
import works.resolve.aletheia.ai.auth.oauth.KimiCodingOAuthAuth
import works.resolve.aletheia.ai.auth.oauth.OpenRouterOAuthAuth
import works.resolve.aletheia.ai.auth.oauth.XaiOAuthAuth
import works.resolve.aletheia.ai.providers.CatalogProvider

/** Verifies Aletheia's concrete registry uses pi's exact static provider ids. */
class ProductionCatalogAuthRegistryTest {

    @Test
    fun `production registry exposes only the implemented OAuth flows`() {
        assertIs<AnthropicOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(provider("anthropic")))
        assertIs<OpenRouterOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(provider("openrouter")))
        assertIs<KimiCodingOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(provider("kimi-coding")))
        assertIs<XaiOAuthAuth>(ProductionCatalogAuthRegistry.oauthAuth(provider("xai")))

        // These catalog-advertised account methods remain hidden until their
        // concrete pi flows are ported and registered.
        assertNull(ProductionCatalogAuthRegistry.oauthAuth(provider("github-copilot")))
        assertNull(ProductionCatalogAuthRegistry.oauthAuth(provider("openai-codex")))
    }

    private fun provider(id: String) = CatalogProvider(
        id = id,
        name = id,
        baseUrl = "https://example.invalid",
        models = emptyList(),
    )
}
