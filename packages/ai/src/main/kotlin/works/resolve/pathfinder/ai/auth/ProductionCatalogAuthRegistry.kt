package works.resolve.pathfinder.ai.auth

import works.resolve.pathfinder.ai.auth.oauth.AnthropicOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.ForegroundGatedOAuthHttpClient
import works.resolve.pathfinder.ai.auth.oauth.GitHubCopilotOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.KimiCodingOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OAuthForegroundGate
import works.resolve.pathfinder.ai.auth.oauth.OAuthHttpClient
import works.resolve.pathfinder.ai.auth.oauth.OpenAiCodexOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.OpenRouterOAuthAuth
import works.resolve.pathfinder.ai.auth.oauth.UrlConnectionOAuthHttpClient
import works.resolve.pathfinder.ai.auth.oauth.XaiOAuthAuth

/**
 * OAuth flows are registered here by provider id; provider knowledge stays
 * out of the catalog bridge.
 *
 * Both OAuth network seams are foreground-gated: every flow's HTTP client is
 * wrapped in [ForegroundGatedOAuthHttpClient] and the loopback flows also
 * pass [gate] to their callback server, so no OAuth network work runs while
 * the app is backgrounded (see [OAuthForegroundGate];
 * [OAuthForegroundGate.NONE], the default, is exact pi parity).
 */
class ProductionCatalogAuthRegistry(
    private val gate: OAuthForegroundGate = OAuthForegroundGate.NONE
) : CatalogAuthRegistry {
    private fun client(): OAuthHttpClient =
        ForegroundGatedOAuthHttpClient(UrlConnectionOAuthHttpClient(), gate)

    private val delegate = MapCatalogAuthRegistry(
        mapOf(
            "anthropic" to AnthropicOAuthAuth(client(), gate = gate),
            "openrouter" to OpenRouterOAuthAuth(client(), gate = gate),
            "kimi-coding" to KimiCodingOAuthAuth(client()),
            "xai" to XaiOAuthAuth(client()),
            "openai-codex" to OpenAiCodexOAuthAuth(client(), gate = gate)
        )
    )

    override fun oauthAuth(
        provider: works.resolve.pathfinder.ai.providers.CatalogProvider
    ): OAuthAuth? {
        // Constructed per provider because the flow's policy-enablement
        // check needs the entry's static model ids — the same generated
        // asset pi derives its GITHUB_COPILOT_MODELS list from.
        if (provider.id == "github-copilot") {
            return GitHubCopilotOAuthAuth(
                http = client(),
                knownModelIds = provider.models.map { it.id }.toSet()
            )
        }
        return delegate.oauthAuth(provider)
    }
}
