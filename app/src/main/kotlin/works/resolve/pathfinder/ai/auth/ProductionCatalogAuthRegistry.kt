package works.resolve.pathfinder.ai.auth

import works.resolve.pathfinder.ai.auth.oauth.AnthropicOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.GitHubCopilotOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.KimiCodingOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OpenAiCodexOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OpenRouterOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.UrlConnectionOAuthHttpClient
import works.resolve.pathfinder.ai.auth.oauth.XaiOAuthAuth

/**
 * Production [CatalogAuthRegistry]: the app's single composition point
 * wiring catalog providers to their concrete OAuth flow ports (pi wires the
 * flows directly inside its provider definitions; Pathfinder composes them
 * here because the flows are injected ports with an HTTP boundary).
 *
 * Currently registered: `anthropic` → [AnthropicOAuthAuth], `openrouter` →
 * [OpenRouterOAuthAuth], `kimi-coding` → [KimiCodingOAuthAuth], `xai` →
 * [XaiOAuthAuth], `openai-codex` → [OpenAiCodexOAuthAuth], and
 * `github-copilot` → [GitHubCopilotOAuthAuth] (its static model-id set — pi's
 * `GITHUB_COPILOT_MODELS` — is taken from the catalog entry passed to
 * [oauthAuth], the same generated asset), all over the JDK
 * [UrlConnectionOAuthHttpClient]. New flows are added by extending the map
 * — never by leaking provider knowledge into the catalog bridge.
 */
object ProductionCatalogAuthRegistry : CatalogAuthRegistry {
    private val delegate = MapCatalogAuthRegistry(
        mapOf(
            "anthropic" to AnthropicOAuthAuth(UrlConnectionOAuthHttpClient()),
            "openrouter" to OpenRouterOAuthAuth(UrlConnectionOAuthHttpClient()),
            "kimi-coding" to KimiCodingOAuthAuth(UrlConnectionOAuthHttpClient()),
            "xai" to XaiOAuthAuth(UrlConnectionOAuthHttpClient()),
            "openai-codex" to OpenAiCodexOAuthAuth(UrlConnectionOAuthHttpClient()),
        ),
    )

    override fun oauthAuth(provider: works.resolve.pathfinder.ai.providers.CatalogProvider): OAuthAuth? {
        // The Copilot flow's policy-enablement check needs the provider's
        // static model ids (pi GITHUB_COPILOT_MODELS); the catalog entry is
        // the same generated asset pi generates that list from, so the ids
        // are read straight from it.
        if (provider.id == "github-copilot") {
            return GitHubCopilotOAuthAuth(
                http = UrlConnectionOAuthHttpClient(),
                knownModelIds = provider.models.map { it.id }.toSet(),
            )
        }
        return delegate.oauthAuth(provider)
    }
}
