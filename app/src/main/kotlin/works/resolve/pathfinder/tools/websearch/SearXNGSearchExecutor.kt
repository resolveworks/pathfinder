package works.resolve.pathfinder.tools.websearch

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.double
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.truthyString

/**
 * SearXNG executor (open-webui's searxng.py wire shape). Unlike the other
 * search providers there is no API key: [apiKey] carries the user's
 * configured instance base URL.
 */
class SearXNGSearchExecutor(private val transport: SearchTransport) : SearchExecutor {

    override val providerId: String = SearchProviderService.SEARXNG_PROVIDER_ID
    override val providerName: String = "SearXNG"
    override val missingKeyMessage: String =
        "Error: No SearXNG instance URL is configured."

    override val parameters: List<SearchParameter> = emptyList()

    override suspend fun search(
        query: String,
        arguments: JsonObject,
        apiKey: String
    ): List<SearchResult> {
        val base = apiKey.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid SearXNG instance URL: $apiKey")

        val request = Request.Builder()
            .url(
                base.newBuilder()
                    .addPathSegments("search")
                    .addQueryParameter("q", query)
                    .addQueryParameter("format", "json")
                    .addQueryParameter("pageno", "1")
                    .addQueryParameter("safesearch", "1")
                    .addQueryParameter("language", "all")
                    .addQueryParameter("time_range", "")
                    .addQueryParameter("categories", "")
                    .addQueryParameter("theme", "simple")
                    .addQueryParameter("image_proxy", "0")
                    .build()
            )
            .get()
            .build()

        val response = transport.send(request)
        if (!response.successful) {
            throw SearchRequestException(response.code, response.reasonPhrase, response.body)
        }

        val data = lenientJson.parseToJsonElement(response.body) as? JsonObject
            ?: throw IllegalStateException("SearXNG search response was not a JSON object")
        val results = data.arr("results")
            ?: throw IllegalStateException("SearXNG search response is missing the results array")
        // The instance's array is not ranked; open-webui sorts by score
        // descending before truncating.
        return results
            .map { element ->
                val r = element as? JsonObject
                    ?: throw IllegalStateException("SearXNG search result was not a JSON object")
                r to (r.double("score") ?: 0.0)
            }
            .sortedByDescending { it.second }
            .take(RESULT_COUNT)
            .mapIndexed { index, (r, _) ->
                SearchResult(
                    title = r.string("title")?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException(
                            "SearXNG search result $index is missing a non-blank title"
                        ),
                    url = r.string("url")?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException(
                            "SearXNG search result $index is missing a non-blank url"
                        ),
                    snippet = r.truthyString("content")
                )
            }
    }

    companion object {
        /** Top results per search; kept small since the agent can web_fetch any result for depth. */
        private const val RESULT_COUNT = 5

        /**
         * The single normalization both save-time validation and the
         * executor's URL parse rely on: drop any legacy "?query" part,
         * strip a trailing "/", keep the rest verbatim.
         *
         * @throws IllegalArgumentException when the result is not a
         * parseable http/https URL.
         */
        fun normalizeInstanceUrl(input: String): String {
            val normalized = input.trim().substringBefore('?').removeSuffix("/")
            val parsed = normalized.toHttpUrlOrNull()
            require(parsed != null && parsed.scheme in HTTP_SCHEMES) {
                "Invalid SearXNG instance URL: $input"
            }
            return normalized
        }

        private val HTTP_SCHEMES = setOf("http", "https")
    }
}
