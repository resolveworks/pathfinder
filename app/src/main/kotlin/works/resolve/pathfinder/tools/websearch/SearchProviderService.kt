package works.resolve.pathfinder.tools.websearch

import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.data.settings.SettingsRepository

data class SearchProvider(val id: String, val name: String)

class SearchProviderService(
    private val settings: SettingsRepository,
    private val credentials: CredentialStore
) {

    val providers: List<SearchProvider> = listOf(
        SearchProvider(BRAVE_PROVIDER_ID, "Brave Search"),
        SearchProvider(TAVILY_PROVIDER_ID, "Tavily"),
        SearchProvider(SERPER_PROVIDER_ID, "Serper"),
        SearchProvider(EXA_PROVIDER_ID, "Exa"),
        SearchProvider(SEARXNG_PROVIDER_ID, "SearXNG")
    )

    suspend fun isConfigured(providerId: String): Boolean = apiKey(providerId) != null

    /**
     * Stores a provider's credential: an API key in the credential store,
     * or — for SearXNG, which has no API key — the validated and normalized
     * instance base URL in settings.
     *
     * @throws IllegalArgumentException for an unknown provider id, a blank
     * value, or (SearXNG) an unparseable instance URL.
     */
    suspend fun saveApiKey(providerId: String, apiKey: String) {
        requireKnown(providerId)
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        if (providerId == SEARXNG_PROVIDER_ID) {
            settings.setSearxngInstanceUrl(
                SearXNGSearchExecutor.normalizeInstanceUrl(apiKey)
            )
            return
        }
        credentials.modify(credentialId(providerId)) { ApiKeyCredential(key = apiKey.trim()) }
    }

    /** @throws IllegalArgumentException for an unknown provider id. */
    suspend fun remove(providerId: String) {
        requireKnown(providerId)
        if (providerId == SEARXNG_PROVIDER_ID) {
            settings.setSearxngInstanceUrl(null)
            return
        }
        credentials.delete(credentialId(providerId))
    }

    /**
     * The provider's configured credential, null when not configured: an
     * API key for key providers, the instance base URL for SearXNG.
     */
    suspend fun apiKey(providerId: String): String? {
        requireKnown(providerId)
        if (providerId == SEARXNG_PROVIDER_ID) {
            return settings.currentSettings().searxngInstanceUrl.takeIf { it.isNotBlank() }
        }
        return (credentials.read(credentialId(providerId)) as? ApiKeyCredential)?.key
    }

    /**
     * The active provider's id. An unknown persisted id throws rather than
     * falling back — a stored selection only a removed provider could
     * produce is invalid state.
     */
    suspend fun activeProviderId(): String {
        val id = settings.currentSettings().activeSearchProviderId
        requireKnown(id)
        return id
    }

    /** @throws IllegalArgumentException for an unknown provider id. */
    suspend fun setActiveProvider(providerId: String) {
        requireKnown(providerId)
        settings.setActiveSearchProviderId(providerId)
    }

    private fun requireKnown(providerId: String) {
        require(providerId in providers.map { it.id }) { "Unknown search provider: $providerId" }
    }

    private fun credentialId(providerId: String): String = "$CREDENTIAL_PREFIX$providerId"

    companion object {
        const val BRAVE_PROVIDER_ID = "brave"
        const val TAVILY_PROVIDER_ID = "tavily"
        const val SERPER_PROVIDER_ID = "serper"
        const val EXA_PROVIDER_ID = "exa"
        const val SEARXNG_PROVIDER_ID = "searxng"

        const val DEFAULT_PROVIDER_ID = BRAVE_PROVIDER_ID

        /** Namespace separating search credentials from AI provider credentials. */
        private const val CREDENTIAL_PREFIX = "search_"
    }
}
