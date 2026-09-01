package works.resolve.pathfinder.ai.auth

import works.resolve.pathfinder.ai.auth.oauth.AnthropicOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.ForegroundGatedOAuthHttpClient
import works.resolve.pathfinder.ai.auth.oauth.GitHubCopilotOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.KimiCodingOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OAuthForegroundGate
import works.resolve.pathfinder.ai.auth.oauth.OpenAiCodexOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OpenRouterOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OAuthHttpClient
import works.resolve.pathfinder.ai.auth.oauth.UrlConnectionOAuthHttpClient
import works.resolve.pathfinder.ai.auth.oauth.XaiOAuthAuth

/**
 * Production [CatalogAuthRegistry]: the app's single composition point
 * wiring catalog providers to their concrete OAuth flow ports (pi wires the
 * flows directly inside its provider definitions; Pathfinder composes them
 * here because the flows are injected ports with an HTTP boundary).
 *
 * Registers `anthropic` → [AnthropicOAuthAuth], `openrouter` →
 * [OpenRouterOAuthAuth], `kimi-coding` → [KimiCodingOAuthAuth], `xai` →
 * [XaiOAuthAuth], `openai-codex` → [OpenAiCodexOAuthAuth], and
 * `github-copilot` → [GitHubCopilotOAuthAuth] (its static model-id set — pi's
 * `GITHUB_COPILOT_MODELS` — is taken from the catalog entry passed to
 * [oauthAuth], the same generated asset), all over the JDK
 * [UrlConnectionOAuthHttpClient]. New flows are added by extending the map
 * — never by leaking provider knowledge into the catalog bridge.
 *
 * Android foreground gating (deliberate divergence, see
 * [OAuthForegroundGate]): every flow's HTTP client is wrapped in
 * [ForegroundGatedOAuthHttpClient] and the three loopback flows pass [gate]
 * to their callback server, so no OAuth network work runs while the app is
 * backgrounded. [OAuthForegroundGate.NONE] (the default) restores pi parity
 * exactly.
 */
class ProductionCatalogAuthRegistry(
    private val gate: OAuthForegroundGate = OAuthForegroundGate.NONE,
) : CatalogAuthRegistry {
    private fun client(): OAuthHttpClient =
        ForegroundGatedOAuthHttpClient(UrlConnectionOAuthHttpClient(), gate)

    private val delegate = MapCatalogAuthRegistry(
        mapOf(
            "anthropic" to AnthropicOAuthAuth(client(), gate = gate),
            "openrouter" to OpenRouterOAuthAuth(client(), gate = gate),
            "kimi-coding" to KimiCodingOAuthAuth(client()),
            "xai" to XaiOAuthAuth(client()),
            "openai-codex" to OpenAiCodexOAuthAuth(client(), gate = gate),
        ),
    )

    override fun oauthAuth(provider: works.resolve.pathfinder.ai.providers.CatalogProvider): OAuthAuth? {
        // The Copilot flow's policy-enablement check needs the provider's
        // static model ids (pi GITHUB_COPILOT_MODELS); the catalog entry is
        // the same generated asset pi generates that list from, so the ids
        // are read straight from it.
        if (provider.id == "github-copilot") {
            return GitHubCopilotOAuthAuth(
                http = client(),
                knownModelIds = provider.models.map { it.id }.toSet(),
            )
        }
        return delegate.oauthAuth(provider)
    }
}
