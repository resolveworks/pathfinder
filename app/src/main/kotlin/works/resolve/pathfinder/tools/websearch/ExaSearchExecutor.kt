package works.resolve.pathfinder.tools.websearch

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.string
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
            ?: throw IllegalStateException("Exa search response was not a JSON object")
        val results = data.arr("results")
            ?: throw IllegalStateException("Exa search response is missing the results array")
        return results.mapIndexed { index, element ->
            val r = element as? JsonObject
                ?: throw IllegalStateException("Exa search result $index was not a JSON object")
            SearchResult(
                title = r.string("title")?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "Exa search result $index is missing a non-blank title"
                    ),
                url = r.string("url")?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "Exa search result $index is missing a non-blank url"
                    ),
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
