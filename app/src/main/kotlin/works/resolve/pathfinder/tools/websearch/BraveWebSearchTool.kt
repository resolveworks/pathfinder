package works.resolve.pathfinder.tools.websearch

import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.utils.MAX_PROVIDER_ERROR_BODY_CHARS
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.truncateErrorText

/**
 * web_search agent tool backed by the Brave Search API. Pathfinder-owned
 * app tooling (pi has no upstream web search): the parameter surface,
 * result payload, and wording are app decisions. Credentials come from the
 * provider-generic [SearchProviderService]; the vendor lives in this class
 * because the wire protocol is Brave's.
 */
class BraveWebSearchTool(
    private val client: OkHttpClient,
    private val apiKeyResolver: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL
) : AgentTool {

    override val definition: Tool = Tool(
        name = NAME,
        description = "Search the web with Brave and return the top results as a markdown list.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Search query")
                        }
                    )
                    put(
                        "freshness",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(FRESHNESS_VALUES.map { JsonPrimitive(it) }))
                            put(
                                "description",
                                "Restrict results by age: pd (past day), pw (past week), " +
                                    "pm (past month), or py (past year)"
                            )
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        }
    )

    override val label: String = "Web Search"

    override val promptSnippet: String = "Search the web for information on a topic"

    override val promptGuidelines: List<String> = listOf(
        "Use web_search when the user asks you to look up current information, facts, or content from the web."
    )

    override fun validateArguments(arguments: JsonObject): JsonObject {
        val query = arguments["query"] as? JsonPrimitive
        when {
            arguments["query"] == null ->
                throw IllegalArgumentException("web_search: missing required argument 'query'")

            query == null || !query.isString ->
                throw IllegalArgumentException("web_search: 'query' must be a string")
        }
        val freshness = arguments["freshness"]
        if (freshness != null) {
            val value = (freshness as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value == null || value !in FRESHNESS_VALUES) {
                throw IllegalArgumentException(
                    "web_search: 'freshness' must be one of ${FRESHNESS_VALUES.joinToString("/")}"
                )
            }
        }
        return arguments
    }

    override suspend fun execute(
        toolCallId: String,
        arguments: JsonObject,
        onUpdate: (AgentToolResult) -> Unit
    ): AgentToolResult {
        val query = arguments.str("query")
            ?: throw IllegalArgumentException("web_search: missing required argument 'query'")
        val freshness = arguments.str("freshness")

        val apiKey = apiKeyResolver()
        if (apiKey == null || apiKey.isBlank()) {
            return AgentToolResult(
                content = listOf(TextContent(MISSING_KEY_MESSAGE)),
                details = EMPTY_DETAILS
            )
        }

        val url = buildString {
            append(baseUrl.removeSuffix("/"))
            append("/res/v1/web/search?q=")
            append(URLEncoder.encode(query, "UTF-8"))
            append("&count=").append(RESULT_COUNT)
            if (freshness != null) append("&freshness=").append(freshness)
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("X-Subscription-Token", apiKey)
            .header("Accept", "application/json")
            .build()

        val response = send(request)
        if (!response.successful) {
            val reason = truncateErrorText(response.body.trim(), MAX_PROVIDER_ERROR_BODY_CHARS)
                .ifEmpty { response.reasonPhrase }
            return AgentToolResult(
                content = listOf(TextContent("Search failed (${response.code}): $reason")),
                details = EMPTY_DETAILS
            )
        }

        val data = lenientJson.parseToJsonElement(response.body) as? JsonObject
        val results = data?.obj("web")?.arr("results")
        if (results == null || results.isEmpty()) {
            return AgentToolResult(
                content = listOf(TextContent("No results found for \"$query\".")),
                details = EMPTY_DETAILS
            )
        }

        val lines = results.map { element ->
            val r = element as? JsonObject ?: JsonObject(emptyMap())
            val title = r.str("title") ?: ""
            val url = r.str("url") ?: ""
            val description = r.str("description")?.takeIf { it.isNotEmpty() }
            if (description == null) "- [$title]($url)" else "- [$title]($url): $description"
        }

        return AgentToolResult(
            content = listOf(TextContent(lines.joinToString("\n"))),
            details = EMPTY_DETAILS
        )
    }

    /** A response whose body was fully read on the OkHttp dispatcher thread. */
    private class ServedResponse(val code: Int, val reasonPhrase: String?, val body: String) {
        val successful: Boolean get() = code in 200..299
    }

    /**
     * The callback consumes the response (body included) before resuming, so
     * the continuation only ever owns plain values and a cancellation racing
     * the resume can never leak one. Cancellation of the call itself goes
     * through [continuation.invokeOnCancellation][kotlinx.coroutines.CancellableContinuation.invokeOnCancellation];
     * the resulting `onFailure` resume on an already-cancelled continuation
     * is dropped.
     */
    private suspend fun send(request: Request): ServedResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = try {
                                if (it.isSuccessful) {
                                    it.body.string()
                                } else {
                                    // Read at most 4x the char cap in bytes (UTF-8 worst
                                    // case) so a huge error body is never fully buffered
                                    // just to be truncated.
                                    val readLimit = MAX_PROVIDER_ERROR_BODY_CHARS.toLong() * 4
                                    val source = it.body.source()
                                    source.request(readLimit)
                                    val buffered = source.buffer
                                    buffered.readUtf8(minOf(buffered.size, readLimit))
                                }
                            } catch (e: IOException) {
                                continuation.resumeWithException(e)
                                return
                            }
                            continuation.resume(ServedResponse(it.code, it.message, body))
                        }
                    }
                }
            )
        }

    companion object {
        const val NAME = "web_search"

        val FRESHNESS_VALUES: List<String> = listOf("pd", "pw", "pm", "py")

        const val DEFAULT_BASE_URL = "https://api.search.brave.com"

        /** Top results per search; kept small since the agent can web_fetch any result for depth. */
        private const val RESULT_COUNT = 5

        /** Message text is stable so UI and tests can match it. */
        const val MISSING_KEY_MESSAGE = "Error: No Brave Search API key is configured."

        private val EMPTY_DETAILS: JsonObject = JsonObject(emptyMap())
    }
}
