package works.resolve.pathfinder.tools.websearch

import works.resolve.pathfinder.agent.AgentSession
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.utils.MAX_PROVIDER_ERROR_BODY_CHARS
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strOrNull
import works.resolve.pathfinder.ai.utils.truncateErrorText
import java.io.IOException
import java.net.URLEncoder
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Brave web search agent tool, ported from the Scry pi extension
 * (`~/Projects/scry/index.ts`, `pi.registerTool({ name: "web_search", ... })`).
 *
 * Ported faithfully where applicable: the parameter schema (required `query`
 * string, optional `freshness` enum `pd`/`pw`/`pm`/`py`), the GET request to
 * `/res/v1/web/search` with `q`, `count=10`, `extra_snippets=true` and the
 * optional `freshness` query parameter, the `X-Subscription-Token` and
 * `Accept: application/json` headers, numbered markdown result formatting
 * with `>`-quoted extra snippets, the no-results message, non-2xx responses
 * returned as text content (body bounded by the provider error-body cap),
 * and the always-empty `details` object.
 *
 * Divergences from Scry (each at its narrow boundary):
 * - Authentication: upstream reads `process.env.BRAVE_API_KEY`; Android has
 *   no ambient environment, so the key comes from [apiKeyResolver] backed by
 *   [SearchProviderService] (Keystore-encrypted). The missing-key message is
 *   adapted accordingly.
 * - Cancellation: upstream catches `AbortError` and returns "Search aborted."
 *   as content. Kotlin coroutine cancellation always rethrows
 *   (`CancellationException` must never be converted into a result value per
 *   the repo error conventions), so the aborted-search text content is not
 *   ported; the agent loop handles cancellation instead.
 * - Network failures: upstream rethrows non-abort fetch errors; this port
 *   likewise throws the [IOException] from the OkHttp call.
 * - Error bodies: upstream reads the whole non-2xx body and uses JS
 *   truthiness on the raw text (`body || statusText`); this port trims
 *   and caps the body via the shared provider error-body helpers
 *   ([MAX_PROVIDER_ERROR_BODY_CHARS]/[truncateErrorText]) so a huge
 *   error page is never fully buffered, and a whitespace-only body falls
 *   back to the status phrase (Android/network-reason divergence at the
 *   read boundary).
 * - Registration gating: upstream registers the tool unconditionally
 *   and errors at execute time when `BRAVE_API_KEY` is unset; here the
 *   application layer registers the tool on every agent but activates it
 *   (`AgentSession.setActiveToolsByName`) only while a key is stored, so
 *   the system prompt never advertises an unusable tool.
 * - Scry's TUI rendering (`renderCall`/`renderResult`, preview-line
 *   collapsing) is terminal-only and not ported.
 *
 * Never logs the API key or request/response content.
 */
class BraveWebSearchTool(
    private val client: OkHttpClient,
    private val apiKeyResolver: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
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
                        },
                    )
                    put(
                        "freshness",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(FRESHNESS_VALUES.map { JsonPrimitive(it) }))
                            put(
                                "description",
                                "Filter by recency: pd (last 24h), pw (last 7 days), pm (last 31 days), py (last year)",
                            )
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        },
    )

    override val label: String = "Web Search"

    override val promptSnippet: String = "Search the web for information on a topic"

    override val promptGuidelines: List<String> = listOf(
        "Use web_search when the user asks you to look up current information, facts, or content from the web.",
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
                    "web_search: 'freshness' must be one of ${FRESHNESS_VALUES.joinToString("/")}",
                )
            }
        }
        return arguments
    }

    override suspend fun execute(
        toolCallId: String,
        arguments: JsonObject,
        onUpdate: (AgentToolResult) -> Unit,
    ): AgentToolResult {
        val query = arguments.str("query")
            ?: throw IllegalArgumentException("web_search: missing required argument 'query'")
        val freshness = arguments.str("freshness")

        val apiKey = apiKeyResolver()
        if (apiKey == null || apiKey.isBlank()) {
            return AgentToolResult(
                content = listOf(TextContent(MISSING_KEY_MESSAGE)),
                details = EMPTY_DETAILS,
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

        // Cancellable OkHttp execution (pi's AbortSignal → coroutine
        // cancellation): cancelling the caller cancels the call. Any
        // CancellationException is always rethrown, never converted into a
        // tool result (see class KDoc divergence note). If cancellation wins
        // the race after the response arrives but before the continuation
        // consumes it, the resume onCancellation handler closes the
        // response; if cancellation comes later, invokeOnCancellation only
        // cancels the call (the already-resumed caller owns the response,
        // closed by its own `use`).
        val response = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        // If the call was cancelled, the continuation is
                        // already cancelled and this result is discarded.
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                },
            )
        }

        response.use {
            if (!it.isSuccessful) {
                // Scry: `Search failed (${status}): ${body || statusText}`;
                // read at most ~4x the char cap (worst-case UTF-8) from the
                // Okio source so a huge error body is never fully buffered
                // just to be truncated (same pattern as OkHttpTransport),
                // then trim/cap via the shared provider error-body helpers.
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
                    details = EMPTY_DETAILS,
                )
            }

            // Scry: `data.web?.results` — a missing/empty array is "no results".
            val data = lenientJson.parseToJsonElement(it.body.string()) as? JsonObject
                ?: JsonObject(emptyMap())
            val results = data.obj("web")?.arr("results")
            if (results == null || results.isEmpty()) {
                return AgentToolResult(
                    content = listOf(TextContent("No results found for \"$query\".")),
                    details = EMPTY_DETAILS,
                )
            }

            val lines = results.mapIndexed { i, element ->
                val r = element as? JsonObject ?: JsonObject(emptyMap())
                val parts = mutableListOf("${i + 1}. **[${r.str("title") ?: ""}](${r.str("url") ?: ""})**")
                // Scry's `if (r.description)` truthiness: skip empty strings too.
                r.str("description")?.takeIf { it.isNotEmpty() }?.let { parts.add("   $it") }
                r.arr("extra_snippets")?.forEach { snippet ->
                    snippet.strOrNull()?.let { parts.add("   > $it") }
                }
                parts.joinToString("\n")
            }

            return AgentToolResult(
                content = listOf(TextContent(lines.joinToString("\n\n"))),
                details = EMPTY_DETAILS,
            )
        }
    }

    companion object {
        const val NAME = "web_search"

        /** Scry's StringEnum values for `freshness`, verbatim. */
        val FRESHNESS_VALUES: List<String> = listOf("pd", "pw", "pm", "py")

        const val DEFAULT_BASE_URL = "https://api.search.brave.com"

        /**
         * Stable, Android-appropriate replacement for Scry's
         * "Error: BRAVE_API_KEY environment variable is not set." — the
         * Android port has no environment variable; the key lives in the
         * app's credential store. Message text is stable so UI/tests can
         * match it.
         */
        const val MISSING_KEY_MESSAGE = "Error: No Brave Search API key is configured."

        /** Scry returns an empty `details` object on every path. */
        private val EMPTY_DETAILS: JsonObject = JsonObject(emptyMap())
    }
}
