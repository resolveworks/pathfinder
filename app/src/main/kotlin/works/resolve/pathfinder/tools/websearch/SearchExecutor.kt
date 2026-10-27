package works.resolve.pathfinder.tools.websearch

import kotlinx.serialization.json.JsonObject

/**
 * One optional web_search tool parameter: its name plus its raw
 * JSON-schema property object (`type`, `enum`, `description`, …) exactly as
 * the provider's API defines the knob. Names, values, and semantics are the
 * provider's own and are never translated across providers.
 */
data class SearchParameter(val name: String, val schema: JsonObject)

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

    /** Stable missing-key message text for this provider. */
    val missingKeyMessage: String

    /**
     * The provider's own optional parameters, declared to the model as-is;
     * the dispatcher's schema is `query` plus exactly these.
     */
    val parameters: List<SearchParameter>

    /**
     * Reads its own declared [parameters] out of the validated tool
     * [arguments] (which also carries `query`).
     *
     * @throws SearchRequestException on a non-2xx answer; network failures
     * propagate as OkHttp's [java.io.IOException].
     */
    suspend fun search(query: String, arguments: JsonObject, apiKey: String): List<SearchResult>
}
