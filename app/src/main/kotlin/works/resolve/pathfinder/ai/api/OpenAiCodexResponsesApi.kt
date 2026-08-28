package works.resolve.pathfinder.ai.api

import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.toToolChoice
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.getPiUserAgent

/**
 * OpenAI Codex Responses streaming adapter, ported from pi's
 * openai-codex-responses.ts (SSE transport).
 *
 * Transport divergences from pi, by design (documented narrow Android/runtime
 * adaptations over Pathfinder's HTTP/SSE transport):
 * - No WebSocket transport: pi's `auto`/`websocket` modes, connection caching,
 *   `previous_response_id` continuation, and WebSocket-specific retries are
 *   omitted; every request is a full-context SSE POST (`store: false`), which
 *   is exactly pi's SSE fallback path. Also excluded with it: pi's
 *   session-resources.ts, whose only pi consumer is Codex WebSocket session
 *   cleanup.
 * - No zstd request-body compression (Content-Encoding: zstd): the platform
 *   has no zstd encoder; the uncompressed JSON body is sent, matching pi's
 *   browser-build behavior where compressRequestBodyZstd returns null.
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
) {
    override fun toString(): String =
        "OpenAICodexResponsesOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoningEffort=$reasoningEffort, reasoningSummary=$reasoningSummary" +
            ", serviceTier=$serviceTier, textVerbosity=$textVerbosity, toolChoice=$toolChoice" +
            ", cacheRetention=$cacheRetention, timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs, env=${env.keys}, headers=${headers.keys})"
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
        val auth = json[JWT_CLAIM_PATH] as? JsonObject
        val accountId = (auth?.get("chatgpt_account_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
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
    val type = event["type"].textOrNull() ?: return null

    if (type == "error") {
        val nested = event.respObj("error")
        val code = event["code"].textOrNull() ?: nested?.get("code").textOrNull()
        val message = event["message"].textOrNull() ?: nested?.get("message").textOrNull()
        throw CodexApiException(
            "Codex error: ${message ?: code ?: event.toString()}",
            code,
        )
    }

    if (type == "response.failed") {
        val response = event.respObj("response")
        val error = response?.respObj("error")
        throw CodexApiException(
            error?.get("message").textOrNull() ?: "Codex response failed",
            error?.get("code").textOrNull(),
        )
    }

    if (type == "response.done" || type == "response.completed" || type == "response.incomplete") {
        val response = event.respObj("response")
        val endTurn = response?.get("end_turn") as? JsonPrimitive
        if (endTurn != null && !endTurn.isString &&
            (endTurn.content == "true" || endTurn.content == "false")
        ) {
            onEndTurn(endTurn.content == "true")
        }
        val normalizedResponse = response?.let {
            buildJsonObject {
                it.forEach { (k, v) ->
                    if (k == "status") {
                        val status = (v as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                        if (status != null && status in CODEX_RESPONSE_STATUSES) put(k, v)
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
            val code = err["code"].textOrNull() ?: err["type"].textOrNull() ?: ""
            val usageLimit = Regex("usage_limit_reached|usage_not_included|rate_limit_exceeded")
                .containsMatchIn(code) || status == 429
            if (usageLimit) {
                val plan = err["plan_type"].textOrNull()?.let { " (${it.lowercase()} plan)" } ?: ""
                val resetsAt = (err["resets_at"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val whenText = resetsAt?.let {
                    val mins = maxOf(0, Math.round((it * 1000 - System.currentTimeMillis()) / 60000.0))
                    " Try again in ~${mins.toInt()} min."
                } ?: ""
                friendly = ("You have hit your ChatGPT usage limit$plan.$whenText").trim()
            }
            message = err["message"].textOrNull() ?: friendly ?: message
        }
    } catch (_: Exception) {
        // Non-JSON body: keep the raw text.
    }
    return message to friendly
}

/** Pi's buildSSEHeaders (SSE transport only). */
internal fun buildCodexSSEHeaders(
    modelHeaders: Map<String, String>,
    optionsHeaders: Map<String, String?>,
    accountId: String,
    token: String,
    sessionId: String?,
): Map<String, String> {
    val headers = LinkedHashMap<String, String?>()
    headers.putAll(modelHeaders)
    headers.putAll(optionsHeaders)
    headers["Authorization"] = "Bearer $token"
    headers["chatgpt-account-id"] = accountId
    headers["originator"] = "pi"
    headers["User-Agent"] = getPiUserAgent()
    headers["OpenAI-Beta"] = "responses=experimental"
    headers["accept"] = "text/event-stream"
    headers["content-type"] = "application/json"
    if (sessionId != null) {
        headers["session-id"] = sessionId
        headers["x-client-request-id"] = sessionId
    }
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
            ?: throw IllegalStateException("No API key for provider: ${model.provider}")
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
        try {
            val apiKey = options.apiKey
                ?: throw IllegalStateException("No API key for provider: ${model.provider}")
            val accountId = extractAccountId(apiKey)
            val cacheRetention = OpenAiResponsesShared.resolveCacheRetention(
                options.cacheRetention,
                options.env,
            )
            val cacheSessionId = if (cacheRetention == CacheRetention.NONE) null else options.sessionId
            val codexSessionId = OpenAiResponsesShared.clampOpenAIPromptCacheKey(cacheSessionId)
            val body = buildCodexRequestBody(model, context, options, codexSessionId, grammarToolInputProperties)
                .toString().toByteArray(Charsets.UTF_8)
            val headers = buildCodexSSEHeaders(model.headers, options.headers, accountId, apiKey, codexSessionId)

            val response = requestWithRetries(model, options, headers, body)
            emit(AssistantMessageEvent.Start(state.partialSnapshot()))

            var finished = false
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
                        finished = true
                        throw TerminalEventReached
                    }
                }
            } catch (_: TerminalEventReached) {
                // Terminal event processed; stop consuming the SSE body.
            }
            if (!finished) {
                // parseSSE consumed the whole body without a terminal event; the
                // shared state machine reports the missing terminal event.
            }
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
                return transport.post(
                    TransportRequest(
                        url = url,
                        bearerToken = null,
                        headers = headers,
                        body = body,
                        timeoutMs = options.timeoutMs,
                    ),
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
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
