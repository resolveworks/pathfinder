package works.resolve.pathfinder.ai.api

import com.github.luben.zstd.Zstd
import java.io.IOException
import java.util.Base64
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import works.resolve.pathfinder.ai.core.toModelThinkingLevel
import works.resolve.pathfinder.ai.core.toToolChoice
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.transport.WebSocketCloseException
import works.resolve.pathfinder.ai.transport.WebSocketConnection
import works.resolve.pathfinder.ai.transport.WebSocketEvent
import works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport
import works.resolve.pathfinder.ai.utils.RetryDelayExceededError
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.splitDeferredTools
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strictBoolean
import works.resolve.pathfinder.ai.utils.strictDouble
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull
import works.resolve.pathfinder.ai.utils.uuidv7
import works.resolve.pathfinder.ai.utils.validateRetryDelayMs
import works.resolve.pathfinder.telemetry.TelemetryContext

/**
 * OpenAI Codex Responses streaming adapter (SSE and WebSocket transports).
 *
 * Divergences from pi, by design:
 * - The "no WebSocket constructor in this runtime" branch is not ported:
 *   Android always provides a WebSocket transport, so [webSocketTransport]
 *   is required. Real WebSocket connect/transport failures still fall back
 *   to SSE exactly like pi.
 * - AssistantMessage transport-failure diagnostics are not ported
 *   (AssistantMessage has no diagnostics field); the failure/retry behavior
 *   is preserved without the attached diagnostic.
 * - pi's Node session-lifecycle cleanup hook has no counterpart; the public
 *   close/reset API ([closeOpenAICodexWebSocketSessions]) plus the pool's
 *   idle TTL and max connection age own cleanup.
 * - AbortSignal aborts map to coroutine cancellation (no ABORTED error
 *   event).
 */

internal const val DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api"
private const val JWT_CLAIM_PATH = "https://api.openai.com/auth"
private const val BASE_DELAY_MS = 1000.0
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L

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
    /**
     * Divergence: pi types this as the string-literal union
     * `"auto" | "none" | "required"`. Kept a passthrough `String?` because
     * the value originates as a Responses wire string (the upstream union
     * carries non-constant `function` members) and is re-serialized verbatim.
     */
    val toolChoice: String? = null,
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = works.resolve.pathfinder.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
    /**
     * Request hook: replaces the request body object before serialization
     * when it returns non-null. Receives full message content; installers
     * must not log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /**
     * Response hook: invoked after each attempt's response headers arrive —
     * before the ok check, so also for non-2xx (and once per retry attempt).
     * Never included in toString().
     */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /** Effective default [Transport.AUTO]: WebSocket-first with per-session SSE fallback. */
    val transport: Transport? = null,
    val websocketConnectTimeoutMs: Long? = null,
    /**
     * Explicit parent context for telemetry produced by this logical
     * request. Dormant in this port — carried for shape fidelity. Presence
     * boolean only in toString().
     */
    val telemetryContext: TelemetryContext? = null,
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
            "telemetryContext" to (telemetryContext != null),
        )
}

internal fun isTerminalRateLimitError(errorText: String): Boolean =
    Regex(
        "GoUsageLimitError|FreeUsageLimitError|Monthly usage limit reached|available balance|insufficient_quota|" +
            "out of budget|quota exceeded|billing",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(errorText)

internal fun isRetryableError(status: Int, errorText: String): Boolean {
    if (status == 429 && isTerminalRateLimitError(errorText)) return false
    if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) return true
    return Regex("rate.?limit|overloaded|service.?unavailable|upstream.?connect|connection.?refused", RegexOption.IGNORE_CASE)
        .containsMatchIn(errorText)
}

/**
 * Retry delay from the retry-after-ms / retry-after headers. Parsing stays
 * codex-local rather than shared with ProviderRetry: the two parsers are
 * deliberately different (this one uses strict whole-string number parsing
 * and clamps to >= 0; ProviderRetry parses lenient floats without
 * clamping), so they are not deduplicated. The delay-cap check they feed
 * into is shared ([validateRetryDelayMs]).
 */
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
    // retry-after can be an HTTP date ("Wed, 21 Oct 2015 07:28:00 GMT" —
    // the spec format) or ISO-8601; try both, RFC 1123 first (Instant.parse
    // only accepts ISO-8601-with-Z).
    val date = works.resolve.pathfinder.ai.utils.parseHttpDateMsOrNull(retryAfter)
        ?: try {
            java.time.Instant.parse(retryAfter).toEpochMilli()
        } catch (_: Exception) {
            return null
        }
    return maxOf(0L, date - nowMs())
}

/**
 * The Codex backend accepts zstd-compressed request bodies on the SSE
 * responses endpoint (the same endpoint the official Codex client compresses
 * against).
 */
internal const val REQUEST_COMPRESSION_ZSTD_LEVEL = 3

/**
 * Compresses the serialized request body, or returns null when compression
 * fails — callers fall back to sending the uncompressed JSON, as pi does when
 * `zlib.zstdCompressSync` throws.
 *
 * Divergence: pi's other null case — Node's zlib being absent in browser
 * builds — is not modeled; zstd-jni is an ordinary Android runtime dependency,
 * so a native-library failure is just a compression failure.
 *
 * [Zstd.compress] is a blocking JNI call, but this stays synchronous (not
 * `suspend`): callers run it under an injected IO dispatcher, and making it
 * suspend would force a real-dispatch hop into virtual-time-ordered stream
 * tests without changing the fallback semantics.
 */
fun compressRequestBodyZstd(
    bodyJson: String,
    level: Int = REQUEST_COMPRESSION_ZSTD_LEVEL,
): ByteArray? = try {
    Zstd.compress(bodyJson.toByteArray(Charsets.UTF_8), level)
} catch (_: Throwable) {
    null
}

/**
 * Codex-specific retry loop (terminal rate limits are not retried; retry
 * delays come from retry-after headers). The event stream runs through the
 * shared Responses state machine with Codex event normalization and
 * service-tier resolution.
 */
class OpenAICodexResponsesApi(
    private val transport: works.resolve.pathfinder.ai.transport.HttpStreamingTransport,
    private val clock: Clock = Clock.System,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    // Test seam: injects the compression-failure fallback. Blocking zstd JNI
    // runs under [ioDispatcher] at the call site.
    private val compressRequestBody: (String) -> ByteArray? = ::compressRequestBodyZstd,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** WebSocket transport seam; required on Android. */
    private val webSocketTransport: WebSocketStreamingTransport,
    private val webSocketSessions: OpenAICodexWebSocketSessions = OpenAICodexWebSocketSessions(clock),
) : ChatApi {

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()


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
                    applyServiceTierPricing(usage, tier, model.id)
                },
            ),
        )
        var endTurn: Boolean? = null

        /** Shared by both transports. */
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
            val cacheRetention = OpenAiResponsesApi.resolveCacheRetention(
                options.cacheRetention,
                options.env,
            )
            val cacheSessionId = if (cacheRetention == CacheRetention.NONE) null else options.sessionId
            val codexSessionId = clampOpenAIPromptCacheKey(cacheSessionId)
            var bodyObj = buildCodexRequestBody(model, context, options, codexSessionId, grammarToolInputProperties)
            options.onPayload?.let { hook -> hook(bodyObj, model)?.let { bodyObj = it } }
            val bodyJson = bodyObj.toString()
            // Both header sets are built up front so an SSE fallback after a
            // WebSocket transport failure reuses them.
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
                webSocketSessions.isSseFallbackActive(cacheSessionId)
            if (websocketDisabledForSession) {
                webSocketSessions.recordSseFallback(cacheSessionId)
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
                        // diagnostic here; not ported (see class KDoc).
                        webSocketSessions.recordWebSocketFailure(cacheSessionId, error)
                        if (websocketStarted) throw error
                        // SSE becomes sticky for this session; fall through to
                        // the SSE path.
                        webSocketSessions.recordSseFallback(cacheSessionId)
                        break
                    }
                }
            }

            // The Codex backend decodes Content-Encoding: zstd; the body goes
            // uncompressed when compression is unavailable.
            val compressedBody = withContext(ioDispatcher) { compressRequestBody(bodyJson) }
            val body: ByteArray
            if (compressedBody != null) {
                headers["content-encoding"] = "zstd"
                body = compressedBody
            } else {
                body = bodyJson.toByteArray(Charsets.UTF_8)
            }

            val response = requestWithRetries(model, options, headers, body)
            emit(AssistantMessageEvent.Start(state.partialSnapshot()))

            // pi stops at the terminal event (done/completed/incomplete) even
            // while the SSE body stays open; mirror that by abandoning
            // collection once the terminal event has been processed — the
            // transport cancels the HTTP call when collection is abandoned.
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
            }
            // A body that ends without a terminal event is reported by
            // assertTerminalEvent in finishStream.
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

    override fun streamSimple(
        model: Model,
        context: Context,
        options: works.resolve.pathfinder.ai.core.SimpleStreamOptions,
    ): Flow<AssistantMessageEvent> {
        val apiKey = options.apiKey
            ?: throw ProviderAuthException("No API key for provider: ${model.provider}")
        val clamped = options.reasoning?.let {
            works.resolve.pathfinder.ai.core.clampThinkingLevel(model, it.toModelThinkingLevel())
        }
        val reasoningEffort = if (clamped == ModelThinkingLevel.OFF) null else clamped
        return stream(
            model,
            context,
            buildOpenAICodexResponsesOptions(model, context, options, reasoningEffort),
        )
    }

    /**
     * Pulls one parsed frame at a time. A terminal
     * response.completed/done/incomplete frame is flagged (the stream then
     * ends cleanly on close); when no frame arrives within [idleTimeoutMs]
     * the connection is closed 1000/"idle_timeout" and the wait throws.
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
     * Acquires a (pooled) socket, sends
     * `{type:"response.create", ...requestBody}` (type key first), runs the
     * frames through the same shared Responses machine as SSE, and on success
     * stores the cached-context continuation.
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
        val acquired = webSocketSessions.acquire(
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
        val stats = cacheSessionId?.let { webSocketSessions.getOrCreateStats(it) }
        if (stats != null) {
            stats.requests++
            if (acquired.reused) stats.connectionsReused++ else stats.connectionsCreated++
            if (useCachedContext) stats.cachedContextRequests++
            if (requestBody.strictBoolean("store") == true) stats.storeTrueRequests++
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
            // onStart fires exactly once, before the first mapped event is
            // processed.
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
            // Throwable (not Exception): cleanup-and-rethrow — the
            // continuation must be invalidated and the pooled connection
            // dropped even for Errors escaping OkHttp completion handlers.
            // Rethrown unchanged, so cancellation passes through untouched.
            entry?.continuation = null
            keepConnection = false
            throw error
        } finally {
            withContext(NonCancellable) { acquired.release(keepConnection) }
        }
    }

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
                options.onResponse?.invoke(
                    ProviderResponse(response.status, headersToRecord(response.headers)),
                    model,
                )
                return response
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ProviderHttpException) {
                    // Non-2xx: onResponse fired before the ok check, so fire
                    // it here too, from the exception.
                    options.onResponse?.invoke(
                        ProviderResponse(error.status, headersToRecord(error.headers)),
                        model,
                    )
                }
                // Terminal usage limits surface their friendly message and
                // never retry.
                val terminal: Exception = when (error) {
                    is ProviderHttpException -> {
                        val (message, friendly) = parseCodexErrorResponse(error.status, error.body, error.statusText, ::nowMs)
                        if (friendly != null) {
                            throw ProviderStreamException(friendly)
                        }
                        if (attempt < maxRetries && isRetryableError(error.status, error.body)) {
                            val retryAfter = getRetryAfterDelayMs(
                                error.header("retry-after-ms"),
                                error.header("retry-after"),
                                ::nowMs,
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
                // Network-style errors retry unless the server-requested delay
                // was rejected or the message is a usage-limit failure.
                lastError = terminal
                val retryable = attempt < maxRetries &&
                    terminal !is RetryDelayExceededError &&
                    !terminal.message.orEmpty().contains("usage limit")
                if (!retryable) throw terminal
                sleep((BASE_DELAY_MS * Math.pow(2.0, attempt.toDouble())).toLong())
            }
        }
        throw lastError ?: IllegalStateException("Failed after retries")
    }
}

/**
 * The streamSimple options conversion (clamped reasoning level and tool
 * choice), extracted as a named function so the conversion — including the
 * telemetryContext identity — is directly testable.
 */
internal fun buildOpenAICodexResponsesOptions(
    model: Model,
    context: Context,
    options: works.resolve.pathfinder.ai.core.SimpleStreamOptions,
    reasoningEffort: ModelThinkingLevel?,
): OpenAICodexResponsesOptions = OpenAICodexResponsesOptions(
    apiKey = options.apiKey,
    sessionId = options.sessionId,
    temperature = options.temperature,
    maxTokens = works.resolve.pathfinder.ai.utils.clampMaxTokensToContext(
        model,
        context,
        options.maxTokens ?: model.maxTokens,
    ),
    reasoningEffort = reasoningEffort,
    // Simple-API choice widened to the Responses wire union.
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
    telemetryContext = options.telemetryContext,
)

/**
 * pi's codex catch block uses the shared `formatProviderError` with NO
 * prefix — codex composes the bare `"<status>: <body>"` (or the message when
 * no body).
 */
internal fun formatCodexError(error: Exception): String = when (error) {
    is CodexApiException -> error.message ?: "Codex error"
    is ProviderHttpException -> formatProviderError(normalizeProviderError(error))
    is ProviderStreamException -> error.message ?: "Codex stream error"
    else -> error.message ?: error::class.simpleName ?: "Unknown error"
}

/** Control-flow sentinel: the terminal Codex event was processed, so the SSE body is abandoned. */
private object TerminalEventReached : RuntimeException()

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

internal fun resolveCodexServiceTier(responseTier: String?, requestTier: String?): String? =
    if (responseTier == "default" && (requestTier == "flex" || requestTier == "priority")) {
        requestTier
    } else {
        responseTier ?: requestTier
    }

internal fun resolveCodexUrl(baseUrl: String?): String {
    val raw = if (!baseUrl.isNullOrBlank()) baseUrl else DEFAULT_CODEX_BASE_URL
    val normalized = raw.replace(Regex("/+$"), "")
    if (normalized.endsWith("/codex/responses")) return normalized
    if (normalized.endsWith("/codex")) return "$normalized/responses"
    return "$normalized/codex/responses"
}

internal fun resolveCodexWebSocketUrl(baseUrl: String?): String {
    val url = resolveCodexUrl(baseUrl)
    return when {
        url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
        url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
        else -> url
    }
}

/** Provider error with a stable code. */
internal class CodexApiException(
    message: String,
    val code: String? = null,
) : Exception(message)

/**
 * Protocol/parse failure (non-transport), e.g. invalid WebSocket JSON.
 * pi attaches the offending payload; omitted here — the port's error
 * formatting never consumes it.
 */
internal class CodexProtocolException(message: String) : Exception(message)

internal fun isCodexNonTransportError(error: Throwable): Boolean =
    error is CodexApiException || error is CodexProtocolException

internal fun isWebSocketConnectionLimitReachedError(error: Throwable): Boolean =
    error is CodexApiException && error.code == "websocket_connection_limit_reached"

internal fun isPreviousResponseNotFoundError(error: Throwable): Boolean =
    error is CodexApiException && error.code == "previous_response_not_found"

private val CODEX_RESPONSE_STATUSES = setOf(
    "completed", "incomplete", "failed", "cancelled", "queued", "in_progress",
)

/**
 * Normalizes one Codex event: throws [CodexApiException] for error /
 * response.failed events; normalizes `response.done` to `response.completed`
 * with a status whitelist and captures end_turn. Returns the event to
 * process plus whether the event stream is finished, or null to continue
 * with the next event.
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

/**
 * pi's Codex WebSocket session state: the `websocketSessionCache` /
 * `websocketDebugStats` / `websocketSseFallbackSessions` module-level state
 * plus `acquireWebSocket`, pooled per session/account with cached-context
 * continuation.
 *
 * Divergences from pi:
 * - pi keeps this state in module-level variables on the single-threaded JS
 *   event loop; here it is a process-wide singleton guarded by a [Mutex], and
 *   idle expiry uses a timer coroutine instead of `setTimeout` (pi's
 *   `scheduleSessionWebSocketExpiry`).
 * - pi registers cleanup through `registerSessionResourceCleanup` (a Node
 *   session-lifecycle hook with no Android counterpart); the public
 *   [closeOpenAICodexWebSocketSessions] plus the idle TTL / max connection age
 *   own cleanup.
 * - pi's clock reads `Date.now()`; [clock] is constructor-injected for tests.
 */
class OpenAICodexWebSocketSessions(
    val clock: Clock = Clock.System,
) {

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    companion object {
        const val SESSION_WEBSOCKET_CACHE_TTL_MS: Long = 5 * 60 * 1000

        const val SESSION_WEBSOCKET_MAX_AGE_MS: Long = 55 * 60 * 1000

        const val OPENAI_BETA_RESPONSES_WEBSOCKETS = "responses_websockets=2026-02-06"
    }

    private val mutex = Mutex()
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sessionCache = mutableMapOf<String, MutableMap<String, CachedWebSocketEntry>>()
    private val debugStats = mutableMapOf<String, OpenAICodexWebSocketDebugStats>()
    private val sseFallbackSessions = mutableSetOf<String>()

    /** pi's CachedWebSocketConnection. */
    internal class CachedWebSocketEntry(val connection: WebSocketConnection, val createdAt: Long) {
        var busy = false
        var idleJob: Job? = null
        var continuation: CachedWebSocketContinuation? = null
    }

    /** pi's CachedWebSocketContinuationState. */
    internal class CachedWebSocketContinuation(
        val lastRequestBody: JsonObject,
        val lastResponseId: String,
        val lastResponseItems: List<JsonObject>,
    )

    /** pi's acquireWebSocket return shape. */
    internal class Acquired(
        val connection: WebSocketConnection,
        val entry: CachedWebSocketEntry?,
        val reused: Boolean,
        val release: suspend (keep: Boolean) -> Unit,
    )

    fun isSseFallbackActive(sessionId: String?): Boolean =
        sessionId != null && sessionId in sseFallbackSessions

    fun recordSseFallback(sessionId: String?) {
        if (sessionId == null) return
        val stats = getOrCreateStats(sessionId)
        stats.sseFallbacks++
        stats.websocketFallbackActive = isSseFallbackActive(sessionId)
    }

    /** Makes SSE sticky for the session until reset. */
    fun recordWebSocketFailure(sessionId: String?, error: Throwable) {
        if (sessionId == null) return
        sseFallbackSessions.add(sessionId)
        val stats = getOrCreateStats(sessionId)
        stats.websocketFailures++
        stats.lastWebSocketError = error.message ?: error::class.simpleName ?: "unknown"
        stats.websocketFallbackActive = true
    }

    fun getOrCreateStats(sessionId: String): OpenAICodexWebSocketDebugStats =
        debugStats.getOrPut(sessionId) { OpenAICodexWebSocketDebugStats() }

    fun getStats(sessionId: String): OpenAICodexWebSocketDebugStats? = debugStats[sessionId]?.copy()

    fun resetStats(sessionId: String?) {
        if (sessionId != null) {
            debugStats.remove(sessionId)
            sseFallbackSessions.remove(sessionId)
            return
        }
        debugStats.clear()
        sseFallbackSessions.clear()
    }

    fun closeSessions(sessionId: String?) {
        timerScope.launch {
            mutex.withLock {
                if (sessionId != null) {
                    sessionCache[sessionId]?.values?.forEach(::closeEntry)
                    sessionCache.remove(sessionId)
                } else {
                    sessionCache.values.forEach { entries -> entries.values.forEach(::closeEntry) }
                    sessionCache.clear()
                }
            }
        }
    }

    private fun closeEntry(entry: CachedWebSocketEntry) {
        entry.idleJob?.cancel()
        entry.idleJob = null
        entry.connection.close(1000, "debug_close")
    }

    /**
     * pi's acquireWebSocket. No [sessionId] gives a one-shot socket (release
     * closes it); expired-by-age, busy, or non-reusable cached entries fall
     * back to fresh connections exactly as upstream. The connect happens
     * outside the lock; insertion afterwards mirrors pi's post-await map
     * insert (last writer wins per session/account).
     */
    internal suspend fun acquire(
        transport: WebSocketStreamingTransport,
        url: String,
        headers: Map<String, String>,
        sessionId: String?,
        accountId: String,
        connectTimeoutMs: Long?,
    ): Acquired {
        val connect: suspend () -> WebSocketConnection = {
            transport.connect(url, headers, connectTimeoutMs ?: WebSocketStreamingTransport.DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS)
        }

        if (sessionId == null) {
            val connection = connect()
            return Acquired(connection, entry = null, reused = false) { connection.close() }
        }

        // Cached-entry triage under the lock; anything but a clean reuse
        // falls through to a fresh connect.
        mutex.withLock {
            val accountEntries = sessionCache[sessionId]
            val cached = accountEntries?.get(accountId)
            if (cached != null) {
                cached.idleJob?.cancel()
                cached.idleJob = null
                when {
                    !cached.busy && nowMs() - cached.createdAt >= SESSION_WEBSOCKET_MAX_AGE_MS -> {
                        cached.connection.close(1000, "connection_age_limit")
                        removeEntry(sessionId, accountId, cached)
                    }
                    !cached.busy && cached.connection.isOpen -> {
                        cached.busy = true
                        return Acquired(cached.connection, cached, reused = true) { keep ->
                            withContext(NonCancellable) { release(sessionId, accountId, cached, keep) }
                        }
                    }
                    cached.busy -> {
                        val connection = connect()
                        return Acquired(connection, entry = null, reused = false) { connection.close() }
                    }
                    else -> {
                        cached.connection.close()
                        removeEntry(sessionId, accountId, cached)
                    }
                }
            }
        }

        val connection = connect()
        val entry = CachedWebSocketEntry(connection, createdAt = nowMs())
        mutex.withLock {
            sessionCache.getOrPut(sessionId) { mutableMapOf() }[accountId] = entry
        }
        return Acquired(connection, entry, reused = false) { keep ->
            withContext(NonCancellable) { release(sessionId, accountId, entry, keep) }
        }
    }

    private suspend fun release(sessionId: String, accountId: String, entry: CachedWebSocketEntry, keep: Boolean) {
        mutex.withLock {
            if (!keep || !entry.connection.isOpen) {
                entry.idleJob?.cancel()
                entry.idleJob = null
                entry.connection.close()
                removeEntry(sessionId, accountId, entry)
                return@withLock
            }
            entry.busy = false
            scheduleIdleExpiry(sessionId, accountId, entry)
        }
    }

    private fun scheduleIdleExpiry(sessionId: String, accountId: String, entry: CachedWebSocketEntry) {
        entry.idleJob?.cancel()
        entry.idleJob = timerScope.launch {
            delay(SESSION_WEBSOCKET_CACHE_TTL_MS)
            mutex.withLock {
                if (entry.busy) return@withLock
                entry.connection.close(1000, "idle_timeout")
                removeEntry(sessionId, accountId, entry)
            }
        }
    }

    private fun removeEntry(sessionId: String, accountId: String, entry: CachedWebSocketEntry) {
        val accountEntries = sessionCache[sessionId] ?: return
        if (accountEntries[accountId] === entry) accountEntries.remove(accountId)
        if (accountEntries.isEmpty()) sessionCache.remove(sessionId)
    }

    fun getOpenAICodexWebSocketDebugStats(sessionId: String): OpenAICodexWebSocketDebugStats? =
        getStats(sessionId)

    fun resetOpenAICodexWebSocketDebugStats(sessionId: String? = null) {
        resetStats(sessionId)
    }

    /**
     * Closes the session's pooled sockets, all of them when null. Upstream
     * does NOT clear the SSE-fallback set here (only
     * [resetOpenAICodexWebSocketDebugStats] does); preserved.
     */
    fun closeOpenAICodexWebSocketSessions(sessionId: String? = null) {
        closeSessions(sessionId)
    }
}

data class OpenAICodexWebSocketDebugStats(
    var requests: Int = 0,
    var connectionsCreated: Int = 0,
    var connectionsReused: Int = 0,
    var cachedContextRequests: Int = 0,
    var storeTrueRequests: Int = 0,
    var fullContextRequests: Int = 0,
    var deltaRequests: Int = 0,
    var lastInputItems: Int = 0,
    var lastDeltaInputItems: Int? = null,
    var lastPreviousResponseId: String? = null,
    var websocketFailures: Int = 0,
    var sseFallbacks: Int = 0,
    var websocketFallbackActive: Boolean? = null,
    var lastWebSocketError: String? = null,
)

internal fun requestBodyWithoutInput(body: JsonObject): JsonObject = buildJsonObject {
    body.forEach { (key, value) ->
        if (key != "input" && key != "previous_response_id") put(key, value)
    }
}

/**
 * JSON-string equality: kotlinx JsonObject/JsonArray toString() is
 * insertion-ordered like JSON.stringify, so string comparison matches pi's
 * JSON.stringify equality for identically-built arrays.
 */
internal fun responseInputsEqual(a: JsonArray?, b: JsonArray?): Boolean {
    val empty = JsonArray(emptyList())
    return (a ?: empty).toString() == (b ?: empty).toString()
}

internal fun requestBodiesMatchExceptInput(a: JsonObject, b: JsonObject): Boolean =
    requestBodyWithoutInput(a).toString() == requestBodyWithoutInput(b).toString()

/** The input suffix when the new input extends the previous input plus
 *  assistant response items with an equal prefix. */
internal fun getCachedWebSocketInputDelta(
    body: JsonObject,
    continuation: OpenAICodexWebSocketSessions.CachedWebSocketContinuation,
): JsonArray? {
    if (!requestBodiesMatchExceptInput(body, continuation.lastRequestBody)) return null
    val currentInput = body.arr("input") ?: JsonArray(emptyList())
    val lastInput = continuation.lastRequestBody.arr("input") ?: JsonArray(emptyList())
    val baseline = JsonArray(lastInput + continuation.lastResponseItems)
    if (currentInput.size < baseline.size) return null
    val prefix = JsonArray(currentInput.take(baseline.size))
    if (!responseInputsEqual(prefix, baseline)) return null
    return JsonArray(currentInput.drop(baseline.size))
}

/**
 * Sends `previous_response_id` plus the input delta when the cached
 * continuation applies; any mismatch invalidates the continuation and sends
 * the full body.
 */
internal fun buildCachedWebSocketRequestBody(
    entry: OpenAICodexWebSocketSessions.CachedWebSocketEntry,
    body: JsonObject,
): JsonObject {
    val continuation = entry.continuation ?: return body
    val delta = getCachedWebSocketInputDelta(body, continuation)
    if (delta == null) {
        entry.continuation = null
        return body
    }
    return buildJsonObject {
        body.forEach { (key, value) -> put(key, value) }
        put("previous_response_id", continuation.lastResponseId)
        put("input", delta)
    }
}

/**
 * Parses an error body into (message, friendly usage-limit text). The
 * status-line reason phrase is the fallback message when the body is empty
 * (as fetch's Response.statusText does).
 */
internal fun parseCodexErrorResponse(
    status: Int,
    body: String,
    statusText: String? = null,
    nowMs: () -> Long,
): Pair<String, String?> {
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
                val resetsAt = err.strictDouble("resets_at")
                val whenText = resetsAt?.let {
                    val mins = maxOf(0, Math.round((it * 1000 - nowMs()) / 60000.0))
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

internal fun extractAccountId(token: String): String {
    try {
        val parts = token.split(".")
        require(parts.size == 3) { "Invalid token" }
        val payload = Base64.getDecoder().decode(
            parts[1].replace('-', '+').replace('_', '/').padBase64(),
        ).decodeToString()
        val json = responsesJson.parseToJsonElement(payload)
            as? JsonObject ?: error("Invalid token")
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

internal fun buildBaseCodexHeaders(
    modelHeaders: Map<String, String>,
    optionsHeaders: Map<String, String?>,
    accountId: String,
    token: String,
): MutableMap<String, String?> {
    val headers = LinkedHashMap<String, String?>()
    headers.putAll(modelHeaders)
    headers.putAll(optionsHeaders) // a null value deletes the header (Headers.delete semantics)
    headers["Authorization"] = "Bearer $token"
    headers["chatgpt-account-id"] = accountId
    // pi sends `originator: "pi"`; Pathfinder identifies itself instead —
    // deliberate divergence, consistent with the OAuth authorize URL's
    // originator default.
    headers["originator"] = "pathfinder"
    headers["User-Agent"] = getPiUserAgent()
    return headers
}

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
 * Base codex headers minus accept/content-type and any prior OpenAI-Beta
 * (both spellings), plus the responses-websockets beta and the
 * request/session id headers.
 *
 * Upstream quirk, ported as behavior: pi's `connectWebSocket` "deletes"
 * `OpenAI-Beta` from the handshake headers, but its `headersToRecord`
 * iterates fetch Headers entries whose names are lowercase (WHATWG fetch
 * spec), so the mixed-case delete is a no-op and the beta header DOES reach
 * the handshake. This port therefore does not delete it — the headers built
 * here are passed to the transport verbatim.
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
