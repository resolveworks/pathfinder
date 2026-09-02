package works.resolve.pathfinder.tools.websearch

import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.CredentialStore

/**
 * A web search provider supported by Pathfinder. Ported from the provider
 * surface implied by the Scry extension (`~/Projects/scry/index.ts`), which
 * is hardcoded to Brave Search upstream (`process.env.BRAVE_API_KEY`).
 */
data class SearchProvider(
    val id: String,
    val name: String,
)

/**
 * Registry and credential management for web search providers.
 *
 * Upstream Scry reads the API key from the `BRAVE_API_KEY` environment
 * variable; Android has no ambient environment, so the key is persisted
 * through the app's existing [ApiKeyCredential]/[CredentialStore] boundary
 * under a `search_`-namespaced credential id. Only Brave is supported
 * (`providers` is a single-entry list, mirroring Scry's single provider).
 *
 * Never logs or exposes secret material.
 */
class SearchProviderService(private val credentials: CredentialStore) {

    /** The supported search providers; only Brave, per Scry. */
    val providers: List<SearchProvider> = listOf(
        SearchProvider(BRAVE_PROVIDER_ID, "Brave Search"),
    )

    suspend fun isConfigured(providerId: String): Boolean = apiKey(providerId) != null

    /**
     * Persist [apiKey] for [providerId].
     *
     * @throws IllegalArgumentException for an unknown provider id or a blank key.
     */
    suspend fun saveApiKey(providerId: String, apiKey: String) {
        requireKnown(providerId)
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        credentials.modify(credentialId(providerId)) { ApiKeyCredential(key = apiKey.trim()) }
    }

    /**
     * Remove the stored credential for [providerId] (logout).
     *
     * @throws IllegalArgumentException for an unknown provider id.
     */
    suspend fun remove(providerId: String) {
        requireKnown(providerId)
        credentials.delete(credentialId(providerId))
    }

    /** The stored API key for [providerId], or null when not configured. Blank stored keys count as unconfigured. */
    suspend fun apiKey(providerId: String): String? {
        requireKnown(providerId)
        return (credentials.read(credentialId(providerId)) as? ApiKeyCredential)?.key
            ?.takeIf { it.isNotBlank() }
    }

    private fun requireKnown(providerId: String) {
        require(providerId in providers.map { it.id }) { "Unknown search provider: $providerId" }
    }

    private fun credentialId(providerId: String): String = "$CREDENTIAL_PREFIX$providerId"

    companion object {
        const val BRAVE_PROVIDER_ID = "brave"

        /** Namespace separating search credentials from AI provider credentials. */
        private const val CREDENTIAL_PREFIX = "search_"

        /** Credential id under which the Brave API key is persisted. */
        const val BRAVE_CREDENTIAL_ID = "$CREDENTIAL_PREFIX$BRAVE_PROVIDER_ID"
    }
}
