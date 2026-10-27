package works.resolve.pathfinder.tools.websearch

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.truthyString

/** Serper (Google SERP) search executor (open-webui's serper.py wire shape). */
class SerperSearchExecutor(
    private val transport: SearchTransport,
    private val baseUrl: String = DEFAULT_BASE_URL
) : SearchExecutor {

    override val providerId: String = SearchProviderService.SERPER_PROVIDER_ID
    override val providerName: String = "Serper"
    override val missingKeyMessage: String =
        "Error: No Serper API key is configured."

    override val parameters: List<SearchParameter> = emptyList()

    override suspend fun search(
        query: String,
        arguments: JsonObject,
        apiKey: String
    ): List<SearchResult> {
        val request = Request.Builder()
            .url(baseUrl.removeSuffix("/") + "/search")
            .post(
                buildJsonObject {
                    put("q", query)
                }.toString().toRequestBody("application/json".toMediaType())
            )
            .header("X-API-KEY", apiKey)
            .build()

        val response = transport.send(request)
        if (!response.successful) {
            throw SearchRequestException(response.code, response.reasonPhrase, response.body)
        }

        val data = lenientJson.parseToJsonElement(response.body) as? JsonObject
        val results = data?.arr("organic") ?: return emptyList()
        // The API does not guarantee organic order; rank by its "position" field before truncating.
        return results
            .sortedBy { (it as? JsonObject)?.int("position") ?: Int.MAX_VALUE }
            .take(RESULT_COUNT)
            .map { element ->
                val r = element as? JsonObject ?: JsonObject(emptyMap())
                SearchResult(
                    title = r.str("title") ?: "",
                    url = r.str("link") ?: "",
                    snippet = r.truthyString("snippet")
                )
            }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://google.serper.dev"

        /** Top results per search; kept small since the agent can web_fetch any result for depth. */
        private const val RESULT_COUNT = 5
    }
}
