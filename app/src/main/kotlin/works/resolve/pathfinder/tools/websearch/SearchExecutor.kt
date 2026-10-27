package works.resolve.pathfinder.tools.websearch

/**
 * A provider's answer to one search, already stripped to the fields the
 * web_search result blocks render.
 */
data class SearchResult(val title: String, val url: String, val snippet: String?)

/**
 * The provider answered non-2xx. Carries the raw error body so the
 * dispatcher applies the single shared truncation/formatting policy.
 */
class SearchRequestException(val code: Int, val reasonPhrase: String?, val body: String) :
    Exception("Search request failed ($code)")

/**
 * One search vendor's wire protocol behind the shared web_search tool.
 * Executors stay transport-free: [SearchTransport] owns the HTTP bridge, so
 * cancellation and error-body capping behave identically across providers.
 */
interface SearchExecutor {
    val providerId: String

    /** Display name used in the tool description shown to the model. */
    val providerName: String

    /** Whether the provider honors the optional freshness parameter. */
    val supportsFreshness: Boolean

    /** Stable missing-key message text for this provider. */
    val missingKeyMessage: String

    /**
     * @throws SearchRequestException on a non-2xx answer; network failures
     * propagate as OkHttp's [java.io.IOException].
     */
    suspend fun search(query: String, freshness: String?, apiKey: String): List<SearchResult>
}
