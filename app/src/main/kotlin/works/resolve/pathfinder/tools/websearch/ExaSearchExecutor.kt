package works.resolve.pathfinder.tools.websearch

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.truthyString

/** Exa search executor (open-webui's exa.py wire shape). */
class ExaSearchExecutor(
    private val transport: SearchTransport,
    private val baseUrl: String = DEFAULT_BASE_URL
) : SearchExecutor {

    override val providerId: String = SearchProviderService.EXA_PROVIDER_ID
    override val providerName: String = "Exa"
    override val missingKeyMessage: String =
        "Error: No Exa API key is configured."

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
                    put("query", query)
                    put("numResults", RESULT_COUNT)
                    put("type", "auto")
                    put(
                        "contents",
                        buildJsonObject {
                            put("text", true)
                        }
                    )
                }.toString().toRequestBody("application/json".toMediaType())
            )
            .header("Authorization", "Bearer $apiKey")
            .build()

        val response = transport.send(request)
        if (!response.successful) {
            throw SearchRequestException(response.code, response.reasonPhrase, response.body)
        }

        val data = lenientJson.parseToJsonElement(response.body) as? JsonObject
        val results = data?.arr("results") ?: return emptyList()
        return results.map { element ->
            val r = element as? JsonObject ?: JsonObject(emptyMap())
            SearchResult(
                title = r.str("title") ?: "",
                url = r.str("url") ?: "",
                snippet = r.truthyString("text")
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.exa.ai"

        /** Top results per search; kept small since the agent can web_fetch any result for depth. */
        private const val RESULT_COUNT = 5
    }
}
