package works.resolve.aletheia.ai.auth

import works.resolve.aletheia.ai.auth.oauth.OpenRouterOAuthAuth
import works.resolve.aletheia.ai.auth.oauth.UrlConnectionOAuthHttpClient

/**
 * Production [CatalogAuthRegistry]: the app's single composition point
 * wiring catalog providers to their concrete OAuth flow ports (pi wires the
 * flows directly inside its provider definitions; Aletheia composes them
 * here because the flows are injected ports with an HTTP boundary).
 *
 * Currently registered: `openrouter` → [OpenRouterOAuthAuth] over the JDK
 * [UrlConnectionOAuthHttpClient]. New flows are added by extending the map
 * — never by leaking provider knowledge into the catalog bridge.
 */
object ProductionCatalogAuthRegistry : CatalogAuthRegistry {
    private val delegate = MapCatalogAuthRegistry(
        mapOf(
            "openrouter" to OpenRouterOAuthAuth(UrlConnectionOAuthHttpClient()),
        ),
    )

    override fun oauthAuth(provider: works.resolve.aletheia.ai.providers.CatalogProvider): OAuthAuth? =
        delegate.oauthAuth(provider)
}
