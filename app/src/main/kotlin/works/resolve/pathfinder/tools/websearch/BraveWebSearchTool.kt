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
import works.resolve.pathfinder.ai.utils.strOrNull
import works.resolve.pathfinder.ai.utils.truncateErrorText
import works.resolve.pathfinder.codingagent.core.AgentSession

/**
 * Brave web search agent tool, ported from the Scry pi extension. Divergences
 * from upstream:
 * - Authentication: Android has no ambient environment, so the key comes
 *   from [apiKeyResolver] backed by [SearchProviderService]
 *   (Keystore-encrypted); the missing-key message is adapted accordingly.
 * - Coroutine cancellation always rethrows (never becomes a result value)
 *   instead of returning upstream's "Search aborted." content.
 * - The application layer activates the tool only while a key is stored, so
 *   the system prompt never advertises an unusable tool; upstream registers
 *   unconditionally and errors at execute time.
 * - `details` carries the structured result entries (title, url,
 *   description, extra_snippets) for the app's result renderer; upstream
 *   Scry exposes only the markdown content.
 *
 * Never logs the API key or request/response content.
 */
class BraveWebSearchTool(
    private val client: OkHttpClient,
    private val apiKeyResolver: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL
) : AgentTool {

    override val definition: Tool = Tool(
        name = NAME,
        description = "Search Brave's web index and return relevant results as markdown.",
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
                                "Filter by recency: pd (last 24h), pw (last 7 days), pm (last 31 days), py (last year)"
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
            append("&count=10&extra_snippets=true")
            if (freshness != null) append("&freshness=").append(freshness)
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("X-Subscription-Token", apiKey)
            .header("Accept", "application/json")
            .build()

        // If cancellation wins the race after the response arrives but
        // before the continuation consumes it, the resume onCancellation
        // handler closes the response; if it comes later,
        // invokeOnCancellation only cancels the call (the already-resumed
        // caller owns the response, closed by its own `use`).
        val response = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        // If the call was cancelled, the continuation is
                        // already cancelled and this exception is dropped.
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                }
            )
        }

        response.use {
            if (!it.isSuccessful) {
                // Read at most 4x the char cap in bytes (UTF-8 worst case)
                // from the Okio source so a huge error body is never fully
                // buffered just to be truncated.
                val readLimit = MAX_PROVIDER_ERROR_BODY_CHARS.toLong() * 4
                val body = try {
                    val source = it.body.source()
                    source.request(readLimit)
                    val buffered = source.buffer
                    buffered.readUtf8(minOf(buffered.size, readLimit))
                } catch (_: IOException) {
                    ""
                }
                val reason = truncateErrorText(body.trim(), MAX_PROVIDER_ERROR_BODY_CHARS)
                    .ifEmpty { it.message }
                return AgentToolResult(
                    content = listOf(TextContent("Search failed (${it.code}): $reason")),
                    details = EMPTY_DETAILS
                )
            }

            val data = lenientJson.parseToJsonElement(it.body.string()) as? JsonObject
                ?: JsonObject(emptyMap())
            val results = data.obj("web")?.arr("results")
            if (results == null || results.isEmpty()) {
                return AgentToolResult(
                    content = listOf(TextContent("No results found for \"$query\".")),
                    details = EMPTY_DETAILS
                )
            }

            // Content keeps Scry's numbered markdown for the model; the
            // same fields go into `details` for the app's result renderer.
            val entries = mutableListOf<JsonObject>()
            val lines = results.mapIndexed { i, element ->
                val r = element as? JsonObject ?: JsonObject(emptyMap())
                val title = r.str("title") ?: ""
                val url = r.str("url") ?: ""
                // Mirrors Scry's JS truthiness: skip empty descriptions.
                val description = r.str("description")?.takeIf { it.isNotEmpty() }
                val snippets = r.arr("extra_snippets")
                entries.add(
                    buildJsonObject {
                        put("title", title)
                        put("url", url)
                        description?.let { put("description", it) }
                        snippets?.let { put("extra_snippets", it) }
                    }
                )
                val parts = mutableListOf("${i + 1}. **[$title]($url)**")
                description?.let { parts.add("   $it") }
                snippets?.forEach { snippet ->
                    snippet.strOrNull()?.let { parts.add("   > $it") }
                }
                parts.joinToString("\n")
            }

            return AgentToolResult(
                content = listOf(TextContent(lines.joinToString("\n\n"))),
                details = buildJsonObject { put("results", JsonArray(entries)) }
            )
        }
    }

    companion object {
        const val NAME = "web_search"

        val FRESHNESS_VALUES: List<String> = listOf("pd", "pw", "pm", "py")

        const val DEFAULT_BASE_URL = "https://api.search.brave.com"

        /** Message text is stable so UI and tests can match it. */
        const val MISSING_KEY_MESSAGE = "Error: No Brave Search API key is configured."

        private val EMPTY_DETAILS: JsonObject = JsonObject(emptyMap())
    }
}
