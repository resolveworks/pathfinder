package works.resolve.pathfinder.tools.websearch

import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.truthyString

/** Brave Web Search API executor (open-webui's brave.py wire shape). */
class BraveSearchExecutor(
    private val transport: SearchTransport,
    private val baseUrl: String = DEFAULT_BASE_URL
) : SearchExecutor {

    override val providerId: String = SearchProviderService.BRAVE_PROVIDER_ID
    override val providerName: String = "Brave"
    override val supportsFreshness: Boolean = true
    override val missingKeyMessage: String =
        "Error: No Brave Search API key is configured."

    override suspend fun search(
        query: String,
        freshness: String?,
        apiKey: String
    ): List<SearchResult> {
        val request = buildRequest(query, freshness, apiKey)

        var response = transport.send(request)
        if (response.code == RATE_LIMIT_CODE) {
            // Brave's free tier allows 1 req/s (open-webui behavior): wait
            // once and retry; a second 429 fails normally.
            delay(RATE_LIMIT_RETRY_DELAY_MS)
            response = transport.send(request)
        }
        if (!response.successful) {
            throw SearchRequestException(response.code, response.reasonPhrase, response.body)
        }

        val data = lenientJson.parseToJsonElement(response.body) as? JsonObject
        val results = data?.obj("web")?.arr("results") ?: return emptyList()
        return results.map { element ->
            val r = element as? JsonObject ?: JsonObject(emptyMap())
            SearchResult(
                title = r.str("title") ?: "",
                url = r.str("url") ?: "",
                snippet = r.truthyString("description")
            )
        }
    }

    private fun buildRequest(query: String, freshness: String?, apiKey: String): Request {
        val url = buildString {
            append(baseUrl.removeSuffix("/"))
            append("/res/v1/web/search?q=")
            append(URLEncoder.encode(query, "UTF-8"))
            append("&count=").append(RESULT_COUNT)
            if (freshness != null) append("&freshness=").append(freshness)
        }
        return Request.Builder()
            .url(url)
            .get()
            .header("X-Subscription-Token", apiKey)
            .header("Accept", "application/json")
            .build()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.search.brave.com"

        /** Top results per search; kept small since the agent can web_fetch any result for depth. */
        private const val RESULT_COUNT = 5

        private const val RATE_LIMIT_CODE = 429
        private const val RATE_LIMIT_RETRY_DELAY_MS = 1_000L
    }
}
