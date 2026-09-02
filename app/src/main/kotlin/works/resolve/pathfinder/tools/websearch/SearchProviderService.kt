package works.resolve.pathfinder.tools.websearch

import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.CredentialStore

data class SearchProvider(
    val id: String,
    val name: String,
)

/**
 * Upstream Scry reads the key from the `BRAVE_API_KEY` environment variable;
 * Android has no ambient environment, so API keys are persisted through the
 * app's [ApiKeyCredential]/[CredentialStore] boundary under `search_`-prefixed
 * credential ids instead.
 *
 * Never logs or exposes secret material.
 */
class SearchProviderService(private val credentials: CredentialStore) {

    val providers: List<SearchProvider> = listOf(
        SearchProvider(BRAVE_PROVIDER_ID, "Brave Search"),
    )

    suspend fun isConfigured(providerId: String): Boolean = apiKey(providerId) != null

    /** @throws IllegalArgumentException for an unknown provider id or a blank key. */
    suspend fun saveApiKey(providerId: String, apiKey: String) {
        requireKnown(providerId)
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        credentials.modify(credentialId(providerId)) { ApiKeyCredential(key = apiKey.trim()) }
    }

    /** @throws IllegalArgumentException for an unknown provider id. */
    suspend fun remove(providerId: String) {
        requireKnown(providerId)
        credentials.delete(credentialId(providerId))
    }

    /** Null when not configured; a blank stored key counts as unconfigured. */
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

        const val BRAVE_CREDENTIAL_ID = "$CREDENTIAL_PREFIX$BRAVE_PROVIDER_ID"
    }
}
