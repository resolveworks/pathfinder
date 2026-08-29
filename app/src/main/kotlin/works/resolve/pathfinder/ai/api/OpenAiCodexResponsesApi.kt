package works.resolve.pathfinder.ai.api

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.ProviderResponse
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.Transport
import works.resolve.pathfinder.ai.core.headersToRecord
import works.resolve.pathfinder.ai.core.toToolChoice
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.WebSocketCloseException
import works.resolve.pathfinder.ai.transport.WebSocketConnection
import works.resolve.pathfinder.ai.transport.WebSocketEvent
import works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.uuidv7
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.compressRequestBodyZstd
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strictBoolean
import works.resolve.pathfinder.ai.utils.strictDouble
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * OpenAI Codex Responses streaming adapter, ported from pi's
 * openai-codex-responses.ts (SSE and WebSocket transports).
 *
 * Transport divergences from pi, by design (documented narrow Android/runtime
 * adaptations over Pathfinder's HTTP/SSE and WebSocket transports):
 * - pi supports runtimes without a WebSocket constructor (browsers, old
 *   Node): the WebSocket path throws "WebSocket transport is not available in
 *   this runtime" and the request falls back to the full-context SSE POST
 *   (openai-codex-responses.ts ~:1046-1048). That branch is deliberately not
 *   ported (owner decision): Android always provides an OkHttp WebSocket
 *   transport, so [webSocketTransport] is required. Real WebSocket
 *   connect/transport failures still fall back to SSE exactly like pi.
 * - pi's AssistantMessage diagnostics (`appendAssistantMessageDiagnostic` with
 *   `provider_transport_failure`) are not ported (AssistantMessage has no
 *   diagnostics field); the failure/retry behavior is preserved without the
 *   attached diagnostic.
 * - pi's `registerSessionResourceCleanup` (session-resources.ts, a Node
 *   session-lifecycle hook) has no counterpart; the public close/reset API
 *   ([closeOpenAICodexWebSocketSessions]) plus the pool's idle TTL and max
 *   connection age own cleanup.
 * - AbortSignal aborts map to coroutine cancellation (no ABORTED error event).
 */

/** Options for the Codex adapter, pi's OpenAICodexResponsesOptions (SSE subset). */
data class OpenAICodexResponsesOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** "none" | "minimal" | ... | "max". */
    val reasoningEffort: ModelThinkingLevel? = null,
    val reasoningSummary: String? = null,
    val serviceTier: String? = null,
    val textVerbosity: String? = null,
    val toolChoice: String? = null,
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = works.resolve.pathfinder.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
    /**
     * pi's onPayload request hook (ProviderRequestOptions, types.ts:145-149;
     * openai-codex-responses.ts:270): replaces the request body object before
     * serialization when it returns non-null. Receives full message content;
     * installers must not log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /**
     * pi's onResponse request hook (types.ts:184;
     * openai-codex-responses.ts:406): invoked after each SSE attempt's
     * response headers arrive — before the ok check, so also for non-2xx
     * (and once per retry attempt). Never included in toString().
     */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * pi's transport (types.ts:110; stream() ~:286): effective default
     * [Transport.AUTO] — WebSocket-first with per-session SSE fallback.
     */
    val transport: Transport? = null,
    /** pi's websocketConnectTimeoutMs (types.ts:216): WS handshake timeout. */
    val websocketConnectTimeoutMs: Long? = null,
) {
    override fun toString(): String =
        optionsToString(
            "OpenAICodexResponsesOptions",
            "apiKey" to redactedSecret(apiKey),
            "sessionId" to sessionId,
            "temperature" to temperature,
            "maxTokens" to maxTokens,
            "reasoningEffort" to reasoningEffort,
            "reasoningSummary" to reasoningSummary,
            "serviceTier" to serviceTier,
            "textVerbosity" to textVerbosity,
            "toolChoice" to toolChoice,
            "cacheRetention" to cacheRetention,
            "timeoutMs" to timeoutMs,
            "maxRetries" to maxRetries,
            "maxRetryDelayMs" to maxRetryDelayMs,
            "env" to env.keys,
            "headers" to headers.keys,
            "onPayload" to (onPayload != null),
            "onResponse" to (onResponse != null),
            "transport" to transport,
            "websocketConnectTimeoutMs" to websocketConnectTimeoutMs,
        )
}

internal const val DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api"
private const val JWT_CLAIM_PATH = "https://api.openai.com/auth"
private const val BASE_DELAY_MS = 1000.0
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L

/** Pi's CodexApiError: a provider error with a stable code. */
internal class CodexApiException(
    message: String,
    val code: String? = null,
) : Exception(message)

/**
 * Pi's CodexProtocolError: a protocol/parse failure (non-transport), e.g.
 * invalid WebSocket JSON. Pi attaches the offending payload; that field is
 * not consumed by the port's error formatting, so it is omitted here.
 */
internal class CodexProtocolException(message: String) : Exception(message)

/** Pi's isCodexNonTransportError (~:692). */
internal fun isCodexNonTransportError(error: Throwable): Boolean =
    error is CodexApiException || error is CodexProtocolException

/** Pi's isWebSocketConnectionLimitReachedError (~:696). */
internal fun isWebSocketConnectionLimitReachedError(error: Throwable): Boolean =
    error is CodexApiException && error.code == "websocket_connection_limit_reached"

/** Pi's isPreviousResponseNotFoundError (~:699). */
internal fun isPreviousResponseNotFoundError(error: Throwable): Boolean =
    error is CodexApiException && error.code == "previous_response_not_found"

/** Pi's isTerminalRateLimitError. */
internal fun isTerminalRateLimitError(errorText: String): Boolean =
    Regex(
        "GoUsageLimitError|FreeUsageLimitError|Monthly usage limit reached|available balance|insufficient_quota|" +
            "out of budget|quota exceeded|billing",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(errorText)

/** Pi's isRetryableError. */
internal fun isRetryableError(status: Int, errorText: String): Boolean {
    if (status == 429 && isTerminalRateLimitError(errorText)) return false
    if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) return true
    return Regex("rate.?limit|overloaded|service.?unavailable|upstream.?connect|connection.?refused")
        .containsMatchIn(errorText)
}

/** Pi's getRetryAfterDelayMs from response headers. */
internal fun getRetryAfterDelayMs(
    retryAfterMs: String?,
    retryAfter: String?,
    nowMs: () -> Long,
): Long? {
    retryAfterMs?.let {
        it.toDoubleOrNull()?.let { millis -> return maxOf(0.0, millis).toLong() }
    }
    if (retryAfter == null) return null
    retryAfter.toDoubleOrNull()?.let { seconds -> return maxOf(0.0, seconds * 1000).toLong() }
    // Pi's Date.parse accepts HTTP dates ("Wed, 21 Oct 2015 07:28:00 GMT" —
    // the retry-after spec format) and ISO-8601. Try both (RFC 1123 first,
    // as Instant.parse only accepts ISO-8601-with-Z).
    val date = works.resolve.pathfinder.ai.utils.parseHttpDateMsOrNull(retryAfter)
        ?: try {
            java.time.Instant.parse(retryAfter).toEpochMilli()
        } catch (_: Exception) {
            return null
        }
    return maxOf(0L, date - nowMs())
}

/** Pi's validateRetryDelayMs; throws when the server delay exceeds the cap. */
internal fun validateRetryDelayMs(delayMs: Long, maxRetryDelayMs: Long): Long {
    if (maxRetryDelayMs > 0 && delayMs > maxRetryDelayMs) {
        throw CodexRetryDelayExceededException(
            "Server requested ${ceilSeconds(delayMs)}s retry delay (max: ${ceilSeconds(maxRetryDelayMs)}s)",
        )
    }
    return delayMs
}

/** Pi's RetryDelayExceededError: never retried. */
internal class CodexRetryDelayExceededException(message: String) : IllegalStateException(message)

private fun ceilSeconds(ms: Long): Long = (ms + 999) / 1000

/** Pi's extractAccountId: chatgpt_account_id claim from the JWT API key. */
internal fun extractAccountId(token: String): String {
    try {
        val parts = token.split(".")
        require(parts.size == 3) { "Invalid token" }
        val payload = Base64.getDecoder().decode(
            parts[1].replace('-', '+').replace('_', '/').padBase64(),
        ).decodeToString()
        val json = responsesJson.parseToJsonElement(payload) as? JsonObject ?: error("Invalid token")
        val accountId = json.obj(JWT_CLAIM_PATH)?.str("chatgpt_account_id")
        if (accountId.isNullOrEmpty()) error("No account ID in token")
        return accountId
    } catch (_: Exception) {
        throw IllegalStateException("Failed to extract accountId from token")
    }
}

private fun String.padBase64(): String {
    val remainder = length % 4
    return if (remainder == 0) this else this + "=".repeat(4 - remainder)
}

/** Pi's resolveCodexUrl. */
internal fun resolveCodexUrl(baseUrl: String?): String {
    val raw = if (!baseUrl.isNullOrBlank()) baseUrl else DEFAULT_CODEX_BASE_URL
    val normalized = raw.replace(Regex("/+$"), "")
    if (normalized.endsWith("/codex/responses")) return normalized
    if (normalized.endsWith("/codex")) return "$normalized/responses"
    return "$normalized/codex/responses"
}

/** Pi's resolveCodexWebSocketUrl (~:641-647): https→wss, http→ws over the resolved Codex URL. */
internal fun resolveCodexWebSocketUrl(baseUrl: String?): String {
    val url = resolveCodexUrl(baseUrl)
    return when {
        url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
        url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
        else -> url
    }
}

/** Pi's resolveCodexServiceTier. */
internal fun resolveCodexServiceTier(responseTier: String?, requestTier: String?): String? =
    if (responseTier == "default" && (requestTier == "flex" || requestTier == "priority")) {
        requestTier
    } else {
        responseTier ?: requestTier
    }

private val CODEX_RESPONSE_STATUSES = setOf(
    "completed", "incomplete", "failed", "cancelled", "queued", "in_progress",
)

/**
 * Pi's mapCodexEvents for one event: throws [CodexApiException] for error /
 * response.failed events, normalizes `response.done` to `response.completed`
 * with a status whitelist and end_turn capture, and returns null for
 * non-terminal handling continuation. Returns the event to process plus
 * whether the event stream is finished (terminal normalization returns true).
 */
internal fun mapCodexEvent(
    event: JsonObject,
    onEndTurn: (Boolean) -> Unit,
): Pair<JsonObject, Boolean>? {
    val type = event.string("type") ?: return null

    if (type == "error") {
        val nested = event.obj("error")
        val code = event.string("code") ?: nested?.get("code").stringOrNull()
        val message = event.string("message") ?: nested?.get("message").stringOrNull()
        throw CodexApiException(
            "Codex error: ${message ?: code ?: event.toString()}",
            code,
        )
    }

    if (type == "response.failed") {
        val response = event.obj("response")
        val error = response?.obj("error")
        throw CodexApiException(
            error?.get("message").stringOrNull() ?: "Codex response failed",
            error?.get("code").stringOrNull(),
        )
    }

    if (type == "response.done" || type == "response.completed" || type == "response.incomplete") {
        val response = event.obj("response")
        // pi checks `typeof response.end_turn === "boolean"` — strict read
        // (the old inline cast accepted "true"/"false" string primitives).
        response?.strictBoolean("end_turn")?.let(onEndTurn)
        val normalizedResponse = response?.let {
            buildJsonObject {
                it.forEach { (k, v) ->
                    if (k == "status") {
                        if (v.stringOrNull() in CODEX_RESPONSE_STATUSES) put(k, v)
                    } else {
                        put(k, v)
                    }
                }
            }
        }
        return buildJsonObject {
            event.forEach { (k, v) -> put(k, v) }
            put("type", "response.completed")
            normalizedResponse?.let { put("response", it) }
        } to true
    }

    return event to false
}

/** Pi's parseErrorResponse friendly usage-limit message. `raw || statusText || "Request failed"`
 * (openai-codex-responses.ts parseErrorResponse); the status line reason
 * phrase surfaces when the body is empty, as fetch's Response.statusText does. */
internal fun parseCodexErrorResponse(status: Int, body: String, statusText: String? = null): Pair<String, String?> {
    var message = body.ifEmpty { statusText?.takeIf { it.isNotEmpty() } ?: "Request failed" }
    var friendly: String? = null
    try {
        val parsed = responsesJson.parseToJsonElement(body) as? JsonObject
        val err = parsed?.get("error") as? JsonObject
        if (err != null) {
            val code = err.str("code") ?: err.str("type") ?: ""
            val usageLimit = Regex("usage_limit_reached|usage_not_included|rate_limit_exceeded")
                .containsMatchIn(code) || status == 429
            if (usageLimit) {
                val plan = err.str("plan_type")?.let { " (${it.lowercase()} plan)" } ?: ""
                // pi types resets_at as a number (openai-codex-responses.ts
                // parseErrorResponse); strict numeric read — string-encoded
                // timestamps do not count.
                val resetsAt = err.strictDouble("resets_at")
                val whenText = resetsAt?.let {
                    val mins = maxOf(0, Math.round((it * 1000 - System.currentTimeMillis()) / 60000.0))
                    " Try again in ~${mins.toInt()} min."
                } ?: ""
                friendly = ("You have hit your ChatGPT usage limit$plan.$whenText").trim()
            }
            message = err.str("message") ?: friendly ?: message
        }
    } catch (_: Exception) {
        // Non-JSON body: keep the raw text.
    }
    return message to friendly
}

/** Pi's buildBaseCodexHeaders (~:1631): model + additional headers, auth, originator, User-Agent. */
internal fun buildBaseCodexHeaders(
    modelHeaders: Map<String, String>,
    optionsHeaders: Map<String, String?>,
    accountId: String,
    token: String,
): MutableMap<String, String?> {
    val headers = LinkedHashMap<String, String?>()
    headers.putAll(modelHeaders)
    headers.putAll(optionsHeaders) // null values delete (pi's Headers.delete)
    headers["Authorization"] = "Bearer $token"
    headers["chatgpt-account-id"] = accountId
    // pi sends `originator: "pi"` (its own client identity); Pathfinder
    // identifies itself instead — deliberate, owner-approved divergence,
    // consistent with the OAuth authorize URL's originator default.
    headers["originator"] = "pathfinder"
    headers["User-Agent"] = getPiUserAgent()
    return headers
}

/** Pi's buildSSEHeaders (SSE transport). */
internal fun buildCodexSSEHeaders(
    modelHeaders: Map<String, String>,
    optionsHeaders: Map<String, String?>,
    accountId: String,
    token: String,
    sessionId: String?,
): Map<String, String> {
    val headers = buildBaseCodexHeaders(modelHeaders, optionsHeaders, accountId, token)
    headers["OpenAI-Beta"] = "responses=experimental"
    headers["accept"] = "text/event-stream"
    headers["content-type"] = "application/json"
    if (sessionId != null) {
        headers["session-id"] = sessionId
        headers["x-client-request-id"] = sessionId
    }
    return headers.filterValues { it != null }.mapValues { it.value!! }
}

/**
 * Pi's buildWebSocketHeaders (~:1637-1650): base codex headers minus
 * accept/content-type and any prior OpenAI-Beta (both spellings), plus the
 * responses-websockets beta and the request/session id headers.
 *
 * Verified upstream quirk, ported as behavior: pi's `connectWebSocket`
 * "deletes" `OpenAI-Beta` from the handshake headers, but its
 * `headersToRecord` (utils/headers.ts) iterates fetch Headers entries whose
 * names are lowercase (WHATWG fetch spec), so the mixed-case delete is a
 * no-op and the beta header DOES reach the handshake. This port therefore
 * does not delete it — the headers built here are passed through verbatim to
 * [works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport.connect].
 */
internal fun buildCodexWebSocketHeaders(
    modelHeaders: Map<String, String>,
    optionsHeaders: Map<String, String?>,
    accountId: String,
    token: String,
    requestId: String,
): Map<String, String> {
    val headers = buildBaseCodexHeaders(modelHeaders, optionsHeaders, accountId, token)
    headers.remove("accept")
    headers.remove("content-type")
    headers.keys.filter { it.lowercase() == "openai-beta" }.forEach { headers.remove(it) }
    headers["OpenAI-Beta"] = OpenAICodexWebSocketSessions.OPENAI_BETA_RESPONSES_WEBSOCKETS
    headers["x-client-request-id"] = requestId
    headers["session-id"] = requestId
    return headers.filterValues { it != null }.mapValues { it.value!! }
}

/** Pi's buildRequestBody for Codex. */
internal fun buildCodexRequestBody(
    model: Model,
    context: Context,
    options: OpenAICodexResponsesOptions?,
    codexSessionId: String?,
    grammarToolInputProperties: Map<String, String> = createGrammarToolInputProperties(
        context.tools,
        model.responsesCompat?.supportsOpenAIGrammarTools ?: false,
    ),
): JsonObject {
    val supportsStrictMode = model.responsesCompat?.supportsStrictMode ?: true
    val supportsOpenAIGrammarTools = model.responsesCompat?.supportsOpenAIGrammarTools ?: false
    val deferredToolsMode = when {
        model.responsesCompat?.supportsAdditionalTools == true ->
            OpenAiResponsesShared.DeferredToolsMode.ADDITIONAL_TOOLS
        model.responsesCompat?.supportsToolSearch == true -> OpenAiResponsesShared.DeferredToolsMode.TOOL_SEARCH
        else -> null
    }
    val toolPlacement = splitDeferredTools(context, deferredToolsMode != null)
    val messages = OpenAiResponsesShared.convertResponsesMessages(
        model,
        context,
        OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        OpenAiResponsesShared.ConvertResponsesMessagesOptions(
            includeSystemPrompt = false,
            grammarToolInputProperties = grammarToolInputProperties,
            deferredTools = toolPlacement.deferred,
            deferredToolsMode = deferredToolsMode,
            toolOptions = OpenAiResponsesShared.ConvertResponsesToolsOptions(
                strict = null,
                supportsStrictMode = supportsStrictMode,
                supportsOpenAIGrammarTools = supportsOpenAIGrammarTools,
            ),
        ),
    )

    return buildJsonObject {
        put("model", model.id)
        put("store", false)
        put("stream", true)
        put("instructions", context.systemPrompt?.takeIf { it.isNotEmpty() } ?: "You are a helpful assistant.")
        put("input", JsonArray(messages))
        put("text", buildJsonObject { put("verbosity", options?.textVerbosity ?: "low") })
        put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))))
        codexSessionId?.let { put("prompt_cache_key", it) }
        put("tool_choice", options?.toolChoice ?: "auto")
        put("parallel_tool_calls", true)

        options?.temperature?.let { put("temperature", it) }
        options?.serviceTier?.let { put("service_tier", it) }
        if (toolPlacement.immediate.isNotEmpty()) {
            put(
                "tools",
                JsonArray(
                    OpenAiResponsesShared.convertResponsesTools(
                        toolPlacement.immediate,
                        OpenAiResponsesShared.ConvertResponsesToolsOptions(
                            strict = null,
                            supportsStrictMode = supportsStrictMode,
                            supportsOpenAIGrammarTools = supportsOpenAIGrammarTools,
                        ),
                    ),
                ),
            )
        }
        if (options?.reasoningEffort != null) {
            val effort = if (options.reasoningEffort == ModelThinkingLevel.OFF) {
                val map = model.thinkingLevelMap
                if (map == null || !map.isSpecified(ModelThinkingLevel.OFF)) "none"
                else map.forLevel(ModelThinkingLevel.OFF)
            } else {
                OpenAiResponsesShared.resolveReasoningEffort(model, options.reasoningEffort, "medium")
            }
            if (effort != null) {
                put(
                    "reasoning",
                    buildJsonObject {
                        put("effort", effort)
                        put("summary", options.reasoningSummary ?: "auto")
                    },
                )
            }
        }
    }
}

/**
 * The Codex SSE adapter. Runs pi's Codex-specific retry loop (terminal rate
 * limits are not retried; retry delays come from retry-after headers) and
 * processes the SSE event stream through the shared Responses state machine
 * with Codex event normalization and service-tier resolution.
 */
class OpenAICodexResponsesApi(
    private val transport: works.resolve.pathfinder.ai.transport.HttpStreamingTransport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    // Narrow seam over pi's compressRequestBodyZstd for tests (injects the
    // compression-failure fallback).
    private val compressRequestBody: (String) -> ByteArray? = ::compressRequestBodyZstd,
    /** WebSocket seam (pi's WebSocket constructor); required on Android. */
    private val webSocketTransport: WebSocketStreamingTransport,
) : ChatApi {

    /**
     * pi's streamSimple for openai-codex-responses: missing keys fail fast,
     * then buildBaseOptions plus the clamped reasoning level and tool choice.
     */
    override fun streamSimple(
        model: Model,
        context: Context,
        options: works.resolve.pathfinder.ai.core.SimpleStreamOptions,
    ): Flow<AssistantMessageEvent> {
        val apiKey = options.apiKey
            ?: throw ProviderAuthException("No API key for provider: ${model.provider}")
        val clamped = options.reasoning?.let {
            works.resolve.pathfinder.ai.core.clampThinkingLevel(model, toModelThinkingLevel(it))
        }
        val reasoningEffort = if (clamped == ModelThinkingLevel.OFF) null else clamped
        return stream(
            model,
            context,
            OpenAICodexResponsesOptions(
                apiKey = apiKey,
                sessionId = options.sessionId,
                temperature = options.temperature,
                maxTokens = works.resolve.pathfinder.ai.utils.clampMaxTokensToContext(
                    model,
                    context,
                    options.maxTokens ?: model.maxTokens,
                ),
                reasoningEffort = reasoningEffort,
                // Narrow simple-API choice widened to the Responses wire union
                // (types.ts:82), pi's streamSimple pass-through.
                toolChoice = options.toolChoice?.toToolChoice()?.let(::mapResponsesToolChoice),
                cacheRetention = options.cacheRetention,
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
                env = options.env,
                headers = options.headers,
                onPayload = options.onPayload,
                onResponse = options.onResponse,
                transport = options.transport,
                websocketConnectTimeoutMs = options.websocketConnectTimeoutMs,
            ),
        )
    }
    fun stream(
        model: Model,
        context: Context,
        options: OpenAICodexResponsesOptions = OpenAICodexResponsesOptions(),
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = nowMs()
        val grammarToolInputProperties = createGrammarToolInputProperties(
            context.tools,
            model.responsesCompat?.supportsOpenAIGrammarTools ?: false,
        )
        val state = OpenAiResponsesShared.ResponsesStreamState(
            model,
            startedAtMs,
            OpenAiResponsesShared.StreamProcessingOptions(
                serviceTier = options.serviceTier,
                grammarToolInputProperties = grammarToolInputProperties,
                resolveServiceTier = ::resolveCodexServiceTier,
                applyServiceTierPricing = { usage, tier ->
                    OpenAiResponsesShared.applyServiceTierPricing(usage, tier, model.id)
                },
            ),
        )
        var endTurn: Boolean? = null

        /** pi's assertSuccessfulOutput + Done emission, shared by both transports. */
        suspend fun finishStream() {
            state.assertTerminalEvent()
            if (state.stopReason == StopReason.PENDING) {
                throw ProviderStreamException("Codex stream ended without a stop reason")
            }
            if (state.stopReason == StopReason.ERROR || state.stopReason == StopReason.ABORTED) {
                throw ProviderStreamException(state.errorMessage ?: "An unknown error occurred")
            }
            val final = state.partialSnapshot()
            emit(
                AssistantMessageEvent.Done(
                    state.stopReason,
                    if (endTurn != null) final.copy(endTurn = endTurn) else final,
                ),
            )
        }
        try {
            val apiKey = options.apiKey
                ?: throw ProviderAuthException("No API key for provider: ${model.provider}")
            val accountId = extractAccountId(apiKey)
            val cacheRetention = OpenAiResponsesShared.resolveCacheRetention(
                options.cacheRetention,
                options.env,
            )
            val cacheSessionId = if (cacheRetention == CacheRetention.NONE) null else options.sessionId
            val codexSessionId = OpenAiResponsesShared.clampOpenAIPromptCacheKey(cacheSessionId)
            // pi openai-codex-responses.ts:270: onPayload inspects/replaces
            // the body object before serialization; null keeps the payload.
            var bodyObj = buildCodexRequestBody(model, context, options, codexSessionId, grammarToolInputProperties)
            options.onPayload?.let { hook -> hook(bodyObj, model)?.let { bodyObj = it } }
            val bodyJson = bodyObj.toString()
            // pi stream() (~:274-286): both header sets are built up front so
            // an SSE fallback after a WebSocket transport failure reuses them.
            val websocketRequestId = codexSessionId ?: uuidv7()
            val headers = buildCodexSSEHeaders(model.headers, options.headers, accountId, apiKey, codexSessionId)
                .toMutableMap()
            val websocketHeaders = buildCodexWebSocketHeaders(
                model.headers,
                options.headers,
                accountId,
                apiKey,
                websocketRequestId,
            )
            val transportOption = options.transport ?: Transport.AUTO
            val websocketDisabledForSession = transportOption != Transport.SSE &&
                OpenAICodexWebSocketSessions.isSseFallbackActive(cacheSessionId)
            if (websocketDisabledForSession) {
                OpenAICodexWebSocketSessions.recordSseFallback(cacheSessionId)
            }

            if (transportOption != Transport.SSE && !websocketDisabledForSession) {
                var startEmitted = false
                var retriedWebSocketConnectionLimit = false
                var retriedMissingWebSocketContinuation = false
                while (true) {
                    var websocketStarted = false
                    try {
                        processWebSocketStream(
                            resolveCodexWebSocketUrl(model.baseUrl),
                            bodyObj,
                            websocketHeaders,
                            state,
                            model,
                            onEndTurn = { endTurn = it },
                            emit = { event -> emit(event) },
                            onStart = {
                                websocketStarted = true
                                if (!startEmitted) {
                                    startEmitted = true
                                    emit(AssistantMessageEvent.Start(state.partialSnapshot()))
                                }
                            },
                            idleTimeoutMs = options.timeoutMs,
                            websocketConnectTimeoutMs = options.websocketConnectTimeoutMs,
                            cacheSessionId = cacheSessionId,
                            accountId = accountId,
                            grammarToolInputProperties = grammarToolInputProperties,
                            options = options,
                        )
                        finishStream()
                        return@flow
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        val connectionLimitBeforeStart =
                            !websocketStarted && isWebSocketConnectionLimitReachedError(error)
                        val previousResponseNotFound = isPreviousResponseNotFoundError(error)
                        if (previousResponseNotFound && !retriedMissingWebSocketContinuation) {
                            // The server-side continuation was invalidated;
                            // retry once with a full body (the cached
                            // continuation is cleared by the failure path).
                            retriedMissingWebSocketContinuation = true
                            continue
                        }
                        if (connectionLimitBeforeStart && !retriedWebSocketConnectionLimit) {
                            retriedWebSocketConnectionLimit = true
                            continue
                        }
                        if (isCodexNonTransportError(error) && !connectionLimitBeforeStart) throw error
                        // pi also appends a provider_transport_failure
                        // AssistantMessage diagnostic here; diagnostics are not
                        // ported (see the class KDoc divergence note).
                        OpenAICodexWebSocketSessions.recordWebSocketFailure(cacheSessionId, error)
                        if (websocketStarted) throw error
                        // SSE becomes sticky for this session; fall through to
                        // the SSE path with the already-built headers/body.
                        OpenAICodexWebSocketSessions.recordSseFallback(cacheSessionId)
                        break
                    }
                }
            }

            // Compress the request body once for the SSE path
            // (openai-codex-responses.ts:368-375): the Codex backend decodes
            // Content-Encoding: zstd; the uncompressed JSON is sent unchanged
            // when compression is unavailable.
            val compressedBody = compressRequestBody(bodyJson)
            val body: ByteArray
            if (compressedBody != null) {
                headers["content-encoding"] = "zstd"
                body = compressedBody
            } else {
                body = bodyJson.toByteArray(Charsets.UTF_8)
            }

            val response = requestWithRetries(model, options, headers, body)
            emit(AssistantMessageEvent.Start(state.partialSnapshot()))

            // Pi maps Codex SSE events incrementally and stops at the terminal
            // event (response.done/completed/incomplete) even while the SSE
            // body stays open (openai-codex-responses.ts mapCodexEvents returns
            // after yielding the normalized terminal event). Mirror that by
            // collecting the events flow incrementally and abandoning collection
            // once the terminal event has been processed; the transport cancels
            // the HTTP call when collection is abandoned (onCompletion {
            // eventSource.cancel() }).
            try {
                response.events.collect { event ->
                    if (event.data.trim() == "[DONE]") return@collect
                    val parsed = try {
                        responsesJson.parseToJsonElement(event.data)
                    } catch (error: Exception) {
                        throw ProviderStreamException(
                            "Invalid Codex SSE JSON: ${error.message ?: error::class.simpleName}",
                        )
                    }
                    val obj = parsed as? JsonObject
                        ?: throw ProviderStreamException("Invalid Codex SSE JSON: expected an object")
                    val mapped = mapCodexEvent(obj) { endTurn = it } ?: return@collect
                    processSseEvent(
                        works.resolve.pathfinder.ai.transport.SseEvent(mapped.first.toString()),
                        state,
                    )?.forEach { emit(it) }
                    if (mapped.second) {
                        throw TerminalEventReached
                    }
                }
            } catch (_: TerminalEventReached) {
                // Terminal event processed; stop consuming the SSE body.
            }
            // If parseSSE consumed the whole body without a terminal event, the
            // shared state machine reports it via assertTerminalEvent below.
            finishStream()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emit(
                AssistantMessageEvent.Error(
                    StopReason.ERROR,
                    state.partialSnapshot().copy(
                        endTurn = endTurn,
                        stopReason = StopReason.ERROR,
                        errorMessage = formatCodexError(error),
                    ),
                ),
            )
        }
    }

    /**
     * pi's parseWebSocket (~:1273-1378) adapted to the transport's events
     * channel: pulls one parsed frame at a time. A terminal
     * response.completed/done/incomplete frame is flagged; invalid JSON throws
     * [CodexProtocolException] (pi's CodexProtocolError message; the payload
     * attachment and AssistantMessage diagnostics are not ported); a
     * close/failure before completion throws the transport-layer close/error
     * message (pi's extract shapes); close after completion ends the stream
     * cleanly; a channel that ends without a terminal frame throws
     * "WebSocket stream closed before response.completed"; and while waiting
     * for a frame, no event for [idleTimeoutMs] closes 1000/"idle_timeout"
     * and throws (pi arms the idle timer only while waiting and resets it on
     * every event).
     */
    private class WebSocketFrameReader(
        private val connection: WebSocketConnection,
        private val idleTimeoutMs: Long?,
    ) {
        var sawCompletion = false
            private set

        /** Next parsed frame, or null when the stream completed and closed. */
        suspend fun next(): JsonObject? {
            val event = if (idleTimeoutMs != null && idleTimeoutMs > 0) {
                withTimeoutOrNull(idleTimeoutMs) { connection.events.receive() } ?: run {
                    connection.close(1000, "idle_timeout")
                    throw IOException("WebSocket idle timeout after ${idleTimeoutMs}ms")
                }
            } else {
                connection.events.receive()
            }
            return when (event) {
                is WebSocketEvent.Message -> {
                    val text = event.text
                    val parsed = try {
                        responsesJson.parseToJsonElement(text)
                    } catch (error: Exception) {
                        throw CodexProtocolException(
                            "Invalid Codex WebSocket JSON: ${error.message ?: error::class.simpleName}",
                        )
                    }
                    val obj = parsed as? JsonObject
                        ?: throw CodexProtocolException("Invalid Codex WebSocket JSON: expected an object")
                    when (obj.string("type")) {
                        "response.completed", "response.done", "response.incomplete" -> sawCompletion = true
                    }
                    obj
                }
                is WebSocketEvent.Failure -> throw IOException(event.message)
                is WebSocketEvent.Closed -> if (sawCompletion) {
                    null
                } else {
                    throw WebSocketCloseException(event.message, event.code, event.reason, event.wasClean)
                }
                null -> throw IOException("WebSocket stream closed before response.completed")
            }
        }
    }

    /**
     * pi's processWebSocketStream (~:1455-1543): acquire a (pooled) socket,
     * send `{type:"response.create", ...requestBody}` (type first), run the
     * frames through the SAME shared responses event machine as SSE
     * (mapCodexEvent/processSseEvent), then on success store the cached-context
     * continuation; on any failure (including cancellation-as-abort) clear it
     * and drop the connection.
     */
    private suspend fun processWebSocketStream(
        url: String,
        fullBody: JsonObject,
        headers: Map<String, String>,
        state: OpenAiResponsesShared.ResponsesStreamState,
        model: Model,
        onEndTurn: (Boolean) -> Unit,
        emit: suspend (AssistantMessageEvent) -> Unit,
        onStart: suspend () -> Unit,
        idleTimeoutMs: Long?,
        websocketConnectTimeoutMs: Long?,
        cacheSessionId: String?,
        accountId: String,
        grammarToolInputProperties: Map<String, String>,
        options: OpenAICodexResponsesOptions,
    ) {
        val acquired = OpenAICodexWebSocketSessions.acquire(
            webSocketTransport,
            url,
            headers,
            cacheSessionId,
            accountId,
            websocketConnectTimeoutMs,
        )
        val connection = acquired.connection
        val entry = acquired.entry
        var keepConnection = true
        val useCachedContext =
            options.transport == Transport.WEBSOCKET_CACHED || options.transport == Transport.AUTO
        // ChatGPT Codex Responses rejects `store: true` ("Store must be set to
        // false"). WebSocket continuation still works via connection-scoped
        // previous_response_id state.
        val requestBody = if (useCachedContext && entry != null) {
            buildCachedWebSocketRequestBody(entry, fullBody)
        } else {
            fullBody
        }
        val stats = cacheSessionId?.let { OpenAICodexWebSocketSessions.getOrCreateStats(it) }
        if (stats != null) {
            stats.requests++
            if (acquired.reused) stats.connectionsReused++ else stats.connectionsCreated++
            if (useCachedContext) stats.cachedContextRequests++
            if (requestBody["store"] as? JsonPrimitive == JsonPrimitive(true)) stats.storeTrueRequests++
            val inputItems = (requestBody["input"] as? JsonArray)?.size ?: 0
            stats.lastInputItems = inputItems
            val previousResponseId = requestBody.str("previous_response_id")
            if (previousResponseId != null) {
                stats.deltaRequests++
                stats.lastDeltaInputItems = inputItems
                stats.lastPreviousResponseId = previousResponseId
            } else {
                stats.fullContextRequests++
                stats.lastDeltaInputItems = null
                stats.lastPreviousResponseId = null
            }
        }
        try {
            connection.send(
                buildJsonObject {
                    put("type", "response.create")
                    requestBody.forEach { (key, value) -> put(key, value) }
                }.toString(),
            )
            // pi's startWebSocketOutputOnFirstEvent: onStart fires exactly
            // once, before the first mapped event is processed.
            var startedOutput = false
            val reader = WebSocketFrameReader(connection, idleTimeoutMs)
            while (true) {
                val frame = reader.next() ?: break // completed, then closed
                val mapped = mapCodexEvent(frame, onEndTurn) ?: continue
                if (!startedOutput) {
                    startedOutput = true
                    onStart()
                }
                processSseEvent(SseEvent(mapped.first.toString()), state)?.forEach { emit(it) }
                if (mapped.second) break
            }
            if (useCachedContext && entry != null) {
                val responseId = state.responseId
                if (responseId != null) {
                    val responseItems =
                        OpenAiResponsesShared.convertResponsesMessages(
                            model,
                            Context(messages = listOf(state.partialSnapshot())),
                            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
                            OpenAiResponsesShared.ConvertResponsesMessagesOptions(
                                includeSystemPrompt = false,
                                grammarToolInputProperties = grammarToolInputProperties,
                            ),
                        ).filter {
                            it.str("type") != "function_call_output" &&
                                it.str("type") != "custom_tool_call_output"
                        }
                    entry.continuation = OpenAICodexWebSocketSessions.CachedWebSocketContinuation(
                        lastRequestBody = fullBody,
                        lastResponseId = responseId,
                        lastResponseItems = responseItems,
                    )
                }
            }
        } catch (error: Throwable) {
            // Throwable (not Exception): this is a cleanup-and-rethrow block
            // mirroring pi's `catch (error)` around processWebSocketStream
            // (~:1534) — the continuation must be invalidated and the pooled
            // connection dropped even for Errors escaping OkHttp completion
            // handlers. The error is rethrown unchanged, so cancellation
            // passes through untouched (stream-error contract unaffected).
            entry?.continuation = null
            keepConnection = false
            throw error
        } finally {
            withContext(NonCancellable) { acquired.release(keepConnection) }
        }
    }

    /** Pi's SSE fetch retry loop (DEFAULT_MAX_RETRIES = 0). */
    private suspend fun requestWithRetries(
        model: Model,
        options: OpenAICodexResponsesOptions,
        headers: Map<String, String>,
        body: ByteArray,
    ): TransportResponse {
        val maxRetries = options.maxRetries
        var lastError: Exception? = null
        val url = resolveCodexUrl(model.baseUrl)
        for (attempt in 0..maxRetries) {
            try {
                val response = transport.post(
                    TransportRequest(
                        url = url,
                        bearerToken = null,
                        headers = headers,
                        body = body,
                        timeoutMs = options.timeoutMs,
                    ),
                )
                // pi openai-codex-responses.ts:406: onResponse fires after each
                // attempt's response headers arrive, before the ok check.
                options.onResponse?.invoke(
                    ProviderResponse(response.status, headersToRecord(response.headers)),
                    model,
                )
                return response
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ProviderHttpException) {
                    // Non-2xx headers arrived: pi's onResponse still fired
                    // before the ok check, so mirror it from the exception.
                    options.onResponse?.invoke(
                        ProviderResponse(error.status, headersToRecord(error.headers)),
                        model,
                    )
                }
                // Terminal usage limits (pi's friendly "You have hit your
                // ChatGPT usage limit" path) must surface their message and
                // never retry.
                val terminal: Exception = when (error) {
                    is ProviderHttpException -> {
                        val (message, friendly) = parseCodexErrorResponse(error.status, error.body, error.statusText)
                        if (friendly != null) {
                            throw ProviderStreamException(friendly)
                        }
                        if (attempt < maxRetries && isRetryableError(error.status, error.body)) {
                            val retryAfter = getRetryAfterDelayMs(
                                error.header("retry-after-ms"),
                                error.header("retry-after"),
                                nowMs,
                            )
                            val delayMs = if (retryAfter == null) {
                                (BASE_DELAY_MS * Math.pow(2.0, attempt.toDouble())).toLong()
                            } else {
                                validateRetryDelayMs(retryAfter, options.maxRetryDelayMs)
                            }
                            sleep(delayMs)
                            continue
                        }
                        ProviderStreamException(message)
                    }
                    else -> error
                }
                // pi's catch-path retry rule: network-style errors retry unless
                // the server-requested delay was rejected or the message is a
                // usage-limit failure.
                lastError = terminal
                val retryable = attempt < maxRetries &&
                    terminal !is CodexRetryDelayExceededException &&
                    !terminal.message.orEmpty().contains("usage limit")
                if (!retryable) throw terminal
                sleep((BASE_DELAY_MS * Math.pow(2.0, attempt.toDouble())).toLong())
            }
        }
        throw lastError ?: IllegalStateException("Failed after retries")
    }
}

/**
 * Port of pi's codex catch block (openai-codex-responses.ts:483): the shared
 * `formatProviderError` with NO prefix — upstream codex composes the bare
 * `"<status>: <body>"` (or the message when no body). The separate
 * [parseCodexErrorResponse] usage-limit path elsewhere in this file is a
 * faithful port and unchanged.
 */
internal fun formatCodexError(error: Exception): String = when (error) {
    is CodexApiException -> error.message ?: "Codex error"
    is ProviderHttpException -> formatProviderError(normalizeProviderError(error))
    is ProviderStreamException -> error.message ?: "Codex stream error"
    else -> error.message ?: error::class.simpleName ?: "Unknown error"
}

/** Control-flow sentinel: the terminal Codex event was processed, so the SSE body is abandoned. */
private object TerminalEventReached : RuntimeException()
