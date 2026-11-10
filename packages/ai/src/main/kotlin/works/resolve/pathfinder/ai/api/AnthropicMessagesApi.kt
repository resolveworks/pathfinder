package works.resolve.pathfinder.ai.api

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.ContentType
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.ProviderAuthException
import works.resolve.pathfinder.ai.ProviderResponse
import works.resolve.pathfinder.ai.ProviderStreamException
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.StreamOptions
import works.resolve.pathfinder.ai.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.anthropicCompatOf
import works.resolve.pathfinder.ai.calculateCost
import works.resolve.pathfinder.ai.hasHeader
import works.resolve.pathfinder.ai.headersToRecord
import works.resolve.pathfinder.ai.mergeHeaders
import works.resolve.pathfinder.ai.toModelThinkingLevel
import works.resolve.pathfinder.ai.toToolChoice
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.AssistantMessageDiagnostic
import works.resolve.pathfinder.ai.utils.ClientIdentity
import works.resolve.pathfinder.ai.utils.DiagnosticErrorInfo
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.ToolCallArgumentsStream
import works.resolve.pathfinder.ai.utils.appendAssistantMessageDiagnostic
import works.resolve.pathfinder.ai.utils.applyOpenRouterReportedCost
import works.resolve.pathfinder.ai.utils.clampMaxTokensToContext
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.getCurrentTools
import works.resolve.pathfinder.ai.utils.getIdentityHeaders
import works.resolve.pathfinder.ai.utils.getInitialSystemMessage
import works.resolve.pathfinder.ai.utils.getSystemMessageText
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.isOpenRouterModel
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.parseJsonWithRepair
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.ai.utils.renderSystemMessageUpdate
import works.resolve.pathfinder.ai.utils.resolveTranscript
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.shortHash
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strOrNull
import works.resolve.pathfinder.ai.utils.strictDoubleOrNull
import works.resolve.pathfinder.ai.utils.stringOrNull
import works.resolve.pathfinder.ai.utils.trimJsWhitespace
import works.resolve.pathfinder.ai.utils.withCauseDetail
import works.resolve.pathfinder.telemetry.TelemetryContext

internal fun resolveCacheRetention(
    cacheRetention: CacheRetention?,
    env: Map<String, String>
): CacheRetention {
    if (cacheRetention != null) return cacheRetention
    if (env["PI_CACHE_RETENTION"] == "long") return CacheRetention.LONG
    return CacheRetention.SHORT
}

internal fun getCacheControl(model: Model, options: AnthropicMessagesOptions): JsonObject? {
    val retention = resolveCacheRetention(options.cacheRetention, options.env)
    if (retention == CacheRetention.NONE) return null
    val ttl =
        retention == CacheRetention.LONG && anthropicCompatOf(model).supportsLongCacheRetention
    return buildJsonObject {
        put("type", "ephemeral")
        if (ttl) put("ttl", "1h")
    }
}

// Stealth mode: on OAuth requests, tool names mimic Claude Code's exactly.
internal const val CLAUDE_CODE_VERSION = "2.1.280"

private val CLAUDE_CODE_TOOLS = listOf(
    "Read", "Write", "Edit", "Bash", "Grep", "Glob", "AskUserQuestion",
    "EnterPlanMode", "ExitPlanMode", "KillShell", "NotebookEdit", "Skill",
    "Task", "TaskOutput", "TodoWrite", "WebFetch", "WebSearch"
)

private val CC_TOOL_LOOKUP = CLAUDE_CODE_TOOLS.associateBy { it.lowercase() }

internal const val FINE_GRAINED_TOOL_STREAMING_BETA = "fine-grained-tool-streaming-2025-05-14"
internal const val INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14"
internal const val SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01"
internal const val MID_CONVERSATION_OUTPUT_CONFIG_BETA = "mid-conversation-output-config-2026-07-01"
internal const val THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01"
internal const val INLINE_TOOLS_BETA = "inline-tools-2026-09-15"
internal const val MESSAGE_THREADS_BETA = "message-threads-2026-08-12"

/**
 * Stable deferred tool declared whenever native tool changes are in use. Anthropic adds
 * hidden prompt scaffolding for mid-conversation tool changes; declaring this placeholder
 * from the first request keeps that scaffolding in the cached prefix, so the first tool
 * change does not invalidate the cache (measured: full miss without it). It is never
 * activated and the model cannot see it.
 */
private val DEFERRED_TOOL_PLACEHOLDER: JsonObject = buildJsonObject {
    put("name", "__pi_deferred_placeholder__")
    put("description", "Reserved placeholder. Never available. Never call this.")
    put(
        "input_schema",
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
            put("required", JsonArray(emptyList()))
        }
    )
    put("defer_loading", true)
}

internal data class AnthropicThreadEntry(
    /** Number of messages in the last full request as sent plus the converted reply. */
    val prefixCount: Int,
    /** [hashThreadPrefix] of the request's settings and those messages. */
    val prefixHash: String,
    /** Id of the reply to continue from. */
    val replyId: String
)

internal data class PreparedThreadRequest(val sessionId: String, val body: JsonObject)

/**
 * Per-session message-thread continuation state, keyed by the same session id
 * as pi's module-level `threadState`. The instance lives as long as the
 * process-wide [AnthropicMessagesApi] that owns it, so a restored or
 * re-created agent session keeps continuing the thread the server holds.
 * pi frees entries through `registerSessionResourceCleanup`; Android has no
 * session-lifecycle hook here, so the session cap bounds memory instead.
 * Dropping the least recently updated session is cheap: its thread has most
 * likely expired on the server already (after a few minutes idle), and
 * otherwise its next request just starts a new thread.
 */
internal class AnthropicMessageThreads {
    private sealed interface Value {
        data class Entry(val entry: AnthropicThreadEntry) : Value
        data object Off : Value
    }

    private companion object {
        const val MAX_ENTRIES = 10_000
    }

    private val lock = Any()
    private val states = LinkedHashMap<String, Value>()

    /**
     * Returns the request to send for [params]: a `continue` when the session's
     * thread prefix is unchanged, a `create` otherwise, or null after the
     * endpoint rejected threads for the session.
     */
    fun prepare(model: Model, sessionId: String, params: JsonObject): PreparedThreadRequest? =
        synchronized(lock) {
            val messages = params["messages"] as? JsonArray
                ?: error("Anthropic request is missing its messages array")
            val state = states[sessionId]
            if (state == Value.Off) return null
            val entry = (state as? Value.Entry)?.entry
            if (entry != null &&
                messages.size > entry.prefixCount &&
                hashThreadPrefix(
                    model,
                    params,
                    JsonArray(messages.take(entry.prefixCount))
                ) == entry.prefixHash
            ) {
                // Each reply can be continued once; deleting also keeps a
                // concurrent request from trying.
                states.remove(sessionId)
                return PreparedThreadRequest(
                    sessionId,
                    buildJsonObject {
                        params.forEach { (key, value) ->
                            if (key != "system" && key != "tools") put(key, value)
                        }
                        put("messages", JsonArray(messages.drop(entry.prefixCount)))
                        put("thread", continueThreadParam(entry.replyId))
                    }
                )
            }
            createThreadRequest(sessionId, params)
        }

    /** Records where the session's next request can continue after [output]. */
    fun recordReply(
        model: Model,
        thread: PreparedThreadRequest,
        params: JsonObject,
        output: AssistantMessage,
        isOAuth: Boolean,
        allowEmptySignature: Boolean
    ) {
        // `responseModel` is set only when a fallback model answered; the
        // server does not store those replies, so they cannot be continued.
        val replyId = output.responseId ?: return
        if (output.responseModel != null) return
        // Convert the reply like the next request will, keeping only the
        // assistant message (transformMessages adds placeholder results for
        // trailing tool calls).
        val reply = convertMessages(
            transformMessages(listOf(output), model) { id, _ -> normalizeToolCallId(id) },
            isOAuth,
            null,
            allowEmptySignature
        ).messages.filter { it.str("role") == "assistant" }
        val conversation = params["messages"] as? JsonArray
            ?: error("Anthropic request is missing its messages array")
        val prefix = JsonArray(conversation + reply)
        setState(
            thread.sessionId,
            Value.Entry(
                AnthropicThreadEntry(
                    prefixCount = prefix.size,
                    prefixHash = hashThreadPrefix(model, params, prefix),
                    replyId = replyId
                )
            )
        )
    }

    /** Turns threads off for the session after the endpoint rejected them. */
    fun disable(sessionId: String) {
        setState(sessionId, Value.Off)
    }

    private fun setState(sessionId: String, value: Value) = synchronized(lock) {
        // Re-insert so map order stays oldest-first for the cap.
        states.remove(sessionId)
        states[sessionId] = value
        if (states.size > MAX_ENTRIES) {
            states.keys.firstOrNull()?.let(states::remove)
        }
    }
}

/** Rejections of a `continue` that a new thread fixes: expired, raced, or a fixed setting changed. */
private val ANTHROPIC_NEW_THREAD_ERRORS: Set<String> =
    setOf(
        "thread_not_found",
        "thread_already_continued",
        "thread_continue_in_flight",
        "thread_fingerprint_mismatch"
    )

private fun createThreadRequest(sessionId: String, params: JsonObject): PreparedThreadRequest =
    PreparedThreadRequest(
        sessionId,
        buildJsonObject {
            params.forEach { (key, value) -> put(key, value) }
            put("thread", buildJsonObject { put("type", "create") })
        }
    )

private fun continueThreadParam(previousMessageId: String): JsonObject = buildJsonObject {
    put("type", "continue")
    put("previous_message_id", previousMessageId)
}

/**
 * Hash of everything a `continue` builds on: the settings that must stay the
 * same within a thread and the messages the server already holds.
 *
 * - Settings, as sent: the endpoint (a thread exists on one endpoint only),
 *   and the fixed request fields. A `continue` omits `system` and `tools` and
 *   the server keeps the thread's own, so only this check can notice a change
 *   to them, including their cache markers. The server rejects changes to the
 *   other fields with `thread_fingerprint_mismatch`; checking them here saves
 *   that rejected request. A fixed field missing here costs only that
 *   rejection, which also starts a new thread.
 * - Messages, normalized so messages that mean the same to the API hash the
 *   same.
 */
internal fun hashThreadPrefix(model: Model, params: JsonObject, messages: JsonArray): String {
    val settings = JsonArray(
        buildList {
            add(JsonPrimitive(model.provider))
            add(JsonPrimitive(model.baseUrl))
            add(params["model"] ?: JsonNull)
            for (key in listOf(
                "system",
                "tools",
                "thinking",
                "output_config",
                "tool_choice",
                "betas",
                "fallbacks"
            )) {
                add(params[key] ?: JsonNull)
            }
        }
    )
    val conversation = JsonArray(messages.map(::normalizeThreadMessage))
    return shortHash(settings.toString() + conversation.toString())
}

private fun normalizeThreadMessage(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(
        LinkedHashMap<String, JsonElement>().apply {
            for ((key, element) in value) {
                // Cache markers don't change what a message means, but they
                // move: pi puts one on the newest message, so the message that
                // had it in the last request has none in this one.
                if (key == "cache_control") continue
                // To attach that marker, the newest user message's string
                // content is sent as one text block; the next request sends
                // the same message as a string.
                if (key == "content" && element is JsonPrimitive && element.isString) {
                    put(
                        key,
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", element.content)
                                }
                            )
                        )
                    )
                } else {
                    put(key, normalizeThreadMessage(element))
                }
            }
        }
    )

    is JsonArray -> JsonArray(value.map(::normalizeThreadMessage))

    else -> value
}

internal fun toClaudeCodeName(name: String): String = CC_TOOL_LOOKUP[name.lowercase()] ?: name

/** Maps a Claude Code-cased name back onto the matching tool's real name. */
internal fun fromClaudeCodeName(name: String, tools: List<Tool>): String {
    if (tools.isNotEmpty()) {
        val lowerName = name.lowercase()
        val matched = tools.firstOrNull { it.name.lowercase() == lowerName }
        if (matched != null) return matched.name
    }
    return name
}

internal fun convertContentBlocks(content: List<Content>): Any {
    val hasImages = content.any { it.type == ContentType.IMAGE }
    if (!hasImages) {
        return sanitizeSurrogates(
            content.filterIsInstance<TextContent>().joinToString("\n") {
                it.text
            }
        )
    }

    val blocks = content.map { block ->
        when (block) {
            is TextContent -> buildJsonObject {
                put("type", "text")
                put("text", sanitizeSurrogates(block.text))
            }

            is ImageContent -> buildJsonObject {
                put("type", "image")
                put(
                    "source",
                    buildJsonObject {
                        put("type", "base64")
                        put("media_type", block.mimeType)
                        put("data", block.data)
                    }
                )
            }

            else -> null
        }
    }.filterNotNull().toMutableList()

    if (blocks.none { it.str("type") == "text" }) {
        blocks.add(
            0,
            buildJsonObject {
                put("type", "text")
                put("text", "(see attached image)")
            }
        )
    }
    return JsonArray(blocks)
}

enum class AnthropicThinkingDisplay { SUMMARIZED, OMITTED }

sealed interface AnthropicToolChoice {
    data object Auto : AnthropicToolChoice
    data object Any : AnthropicToolChoice
    data object None : AnthropicToolChoice
    data class Tool(val name: String) : AnthropicToolChoice
}

/**
 * Options for the Anthropic Messages adapter. pi's `client` field is not
 * ported: the transport is injected at construction instead.
 */
data class AnthropicMessagesOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    /**
     * Do not use or record message-thread continuation state for this
     * request: it neither continues nor replaces the session's thread.
     */
    val disableContinuation: Boolean? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Null means "unspecified" (thinking omitted). */
    val thinkingEnabled: Boolean? = null,
    /** Default 1024 when enabled (older models). */
    val thinkingBudgetTokens: Int? = null,
    /** Adaptive-thinking effort level (wire value, e.g. "low"/"high"; pi's string union). */
    val effort: String? = null,
    val thinkingDisplay: AnthropicThinkingDisplay = AnthropicThinkingDisplay.SUMMARIZED,
    val interleavedThinking: Boolean = true,
    val toolChoice: AnthropicToolChoice? = null,
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
    /** How the request identifies the calling app. */
    val identity: ClientIdentity? = null,
    /**
     * Request hook that may return a replacement for the outgoing payload,
     * applied before serialization. It receives full message content —
     * installers must not log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /** Invoked after the 2xx response headers arrive. Never included in toString(). */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * Explicit parent telemetry context for this request. Dormant in this
     * port — carried for shape fidelity.
     */
    val telemetry: TelemetryContext? = null
) {
    override fun toString(): String = optionsToString(
        "AnthropicMessagesOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
        "disableContinuation" to disableContinuation,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "thinkingEnabled" to thinkingEnabled,
        "thinkingBudgetTokens" to thinkingBudgetTokens,
        "effort" to effort,
        "thinkingDisplay" to thinkingDisplay,
        "interleavedThinking" to interleavedThinking,
        "toolChoice" to toolChoice,
        "cacheRetention" to cacheRetention,
        "timeoutMs" to timeoutMs,
        "maxRetries" to maxRetries,
        "maxRetryDelayMs" to maxRetryDelayMs,
        "env" to env.keys,
        "headers" to headers.keys,
        "onPayload" to (onPayload != null),
        "onResponse" to (onResponse != null),
        "telemetry" to (telemetry != null)
    )
}

/**
 * Anthropic Messages streaming adapter.
 *
 * Divergences from pi:
 * - pi's ambient ANTHROPIC_AUTH_TOKEN / ANTHROPIC_OAUTH_TOKEN env paths are
 *   reduced to ANTHROPIC_API_KEY.
 * - AbortSignal aborts map to coroutine cancellation, which propagates
 *   without an Error event — the established Pathfinder convention
 *   (see OpenAiCompletionsApi / Types.kt).
 */
class AnthropicMessagesApi(
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val clock: Clock = Clock.System
) : ChatApi {

    private val threads = AnthropicMessageThreads()

    fun stream(
        model: Model,
        context: TranscriptContext,
        options: AnthropicMessagesOptions = AnthropicMessagesOptions()
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = clock.now().toEpochMilliseconds()
        val normalizedContext =
            resolveTranscript(context, anthropicCompatOf(model).supportsMidConvoSystemMessages)
        val currentTools = getCurrentTools(normalizedContext.messages)
        // Copilot is never OAuth: its Bearer-auth branch is checked first,
        // as in pi's createClient.
        val isOAuth =
            model.provider != "github-copilot" && options.apiKey?.let { isOAuthToken(it) } == true
        val state = AnthropicStreamState(model, startedAtMs, isOAuth)
        val compat = anthropicCompatOf(model)
        try {
            assertRequestAuth(model.provider, options.apiKey, options.headers)

            val retention = resolveCacheRetention(options.cacheRetention, options.env)
            val cacheSessionId = if (retention == CacheRetention.NONE) null else options.sessionId
            // Sessions where the endpoint rejected threads ("off") still send
            // the threads beta, which the resend that turned them off was
            // accepted with; prepare() just sends no `thread`.
            val threadSessionId = if (compat.supportsMessageThreads) cacheSessionId else null
            val url = model.baseUrl.trimEnd('/') + "/v1/messages?beta=true"

            suspend fun send(body: JsonObject): TransportResponse {
                // The beta namespace moves `betas` out of the body and into the
                // `anthropic-beta` header (see getBetaFeatures).
                val betas = (body["betas"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList()
                val wireBody = JsonObject(body.toMutableMap().apply { remove("betas") })
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                val (headers, bearerToken) = buildHeaders(
                    model,
                    isOAuth,
                    options,
                    normalizedContext,
                    cacheSessionId,
                    betas
                )
                val request = TransportRequest(
                    url = url,
                    bearerToken = bearerToken,
                    headers = headers,
                    body = wireBody,
                    timeoutMs = options.timeoutMs
                )
                return retry.retryProviderRequest(options.maxRetries, options.maxRetryDelayMs) {
                    transport.post(request)
                }
            }

            suspend fun sendThreadRequest(
                thread: PreparedThreadRequest,
                params: JsonObject
            ): Pair<TransportResponse, PreparedThreadRequest?> {
                try {
                    return send(thread.body) to thread
                } catch (error: ProviderHttpException) {
                    currentCoroutineContext().ensureActive()
                    val apiError = anthropicApiError(error)
                    val errorCode = apiError?.errorCode
                    val startNewThread =
                        errorCode != null && errorCode in ANTHROPIC_NEW_THREAD_ERRORS
                    // The endpoint can't use threads: it can't thread this
                    // request, or it doesn't know the `thread` field and
                    // rejects it without a code.
                    val threadsUnsupported =
                        errorCode == "thread_unsupported_request" ||
                            (
                                errorCode == null &&
                                    error.status == 400 &&
                                    (
                                        apiError?.message?.contains("thread") == true ||
                                            (apiError == null && error.body.contains("thread"))
                                        )
                                )
                    // Any other error (prompt too long, unknown model, strict
                    // tool limits, ...) is not about the thread, and resending
                    // would only fail again.
                    if (!startNewThread && !threadsUnsupported) throw error
                    // Recorded before the resend, so a failed resend keeps it.
                    state.appendDiagnostic(
                        AssistantMessageDiagnostic(
                            type = "anthropic_thread_fallback",
                            timestamp = clock.now().toEpochMilliseconds(),
                            error = DiagnosticErrorInfo(
                                message = apiError?.message
                                    ?: error.message
                                    ?: error::class.simpleName
                                    ?: "Anthropic thread fallback"
                            ),
                            details = buildJsonObject {
                                put("reason", errorCode ?: error.status.toString())
                            }
                        )
                    )
                    if (startNewThread) {
                        val created = createThreadRequest(thread.sessionId, params)
                        return send(created.body) to created
                    }
                    // With the threads beta, a request without `thread` is a
                    // normal request.
                    val response = send(params)
                    threads.disable(thread.sessionId)
                    return response to null
                }
            }

            var disablePreferredStrictTools = false
            var response: TransportResponse
            var params: JsonObject
            // The thread request that produced the response; null when no
            // `thread` was sent.
            var thread: PreparedThreadRequest? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val built = buildAnthropicParams(
                    model,
                    normalizedContext,
                    isOAuth,
                    options,
                    disablePreferredStrictTools,
                    messageThreads = threadSessionId != null
                )
                built.providerThinkingLevel?.let { state.providerThinkingLevel = it }
                params = built.params
                options.onPayload?.let { hook ->
                    hook(params, model)?.let { next ->
                        // A replacement payload must not turn off streaming.
                        params = JsonObject(
                            next.toMutableMap().apply { put("stream", JsonPrimitive(true)) }
                        )
                    }
                }
                val prepared =
                    if (threadSessionId != null && options.disableContinuation != true) {
                        threads.prepare(model, threadSessionId, params)
                    } else {
                        null
                    }
                try {
                    val sent = if (prepared != null) {
                        sendThreadRequest(prepared, params)
                    } else {
                        send(params) to null
                    }
                    response = sent.first
                    thread = sent.second
                    break
                } catch (error: ProviderHttpException) {
                    // Only downgrade a rejected request, never an already-started stream.
                    if (disablePreferredStrictTools || !built.hasPreferredStrictTools) throw error
                    val apiError = anthropicApiError(error) ?: throw error
                    if (!isStrictToolLimitError(apiError)) throw error
                    disablePreferredStrictTools = true
                    state.appendDiagnostic(
                        AssistantMessageDiagnostic(
                            type = "anthropic_strict_tools_fallback",
                            timestamp = clock.now().toEpochMilliseconds(),
                            error = DiagnosticErrorInfo(message = apiError.message),
                            details = buildJsonObject {
                                put("reason", "strict_tool_limits_exceeded")
                            }
                        )
                    )
                }
            }

            options.onResponse?.invoke(
                ProviderResponse(response.status, headersToRecord(response.headers)),
                model
            )

            emit(AssistantMessageEvent.Start(state.snapshot()))

            // No early break: like pi, the SSE body is consumed to its end
            // and the terminal state is validated afterwards.
            response.events.collect { event ->
                processSseEvent(event, model, normalizedContext, currentTools, state)?.forEach {
                    emit(it)
                }
            }

            if (state.sawMessageStart && !state.sawMessageStop) {
                throw ProviderStreamException("Anthropic stream ended before message_stop")
            }
            if (state.stopReason == StopReason.PENDING) {
                throw ProviderStreamException("Anthropic stream ended without a stop reason")
            }
            if (state.stopReason == StopReason.ERROR) {
                throw ProviderStreamException(state.errorMessage ?: "An unknown error occurred")
            }

            thread?.let {
                threads.recordReply(
                    model,
                    it,
                    params,
                    state.snapshot(),
                    isOAuth,
                    compat.allowEmptySignature
                )
            }
            var message = state.snapshot()
            state.inputTransformationsDiagnostic(
                clock.now().toEpochMilliseconds()
            )?.let { diagnostic ->
                message = appendAssistantMessageDiagnostic(message, diagnostic)
            }
            emit(AssistantMessageEvent.Done(state.stopReason, message))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val finalMessage = state.snapshot().copy(
                stopReason = StopReason.ERROR,
                errorMessage = formatProviderError(error)
            )
            emit(AssistantMessageEvent.Error(finalMessage.stopReason, finalMessage))
        }
    }

    override fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        assertRequestAuth(model.provider, options.apiKey, options.headers)

        val base = buildBaseOptions(model, context, options).copy(
            toolChoice = mapToolChoice(options.toolChoice?.toToolChoice())
        )
        val reasoning = options.reasoning
        val resolved: AnthropicMessagesOptions = if (reasoning == null) {
            base.copy(thinkingEnabled = false)
        } else if (anthropicCompatOf(model).forceAdaptiveThinking == true) {
            base.copy(
                thinkingEnabled = true,
                effort = mapThinkingLevelToEffort(model, reasoning)
            )
        } else {
            val (maxTokens, thinkingBudget) =
                adjustMaxTokensForThinking(
                    base.maxTokens,
                    model.maxTokens,
                    reasoning,
                    options.thinkingBudgets
                )
            val clamped = clampMaxTokensToContext(model, context, maxTokens)
            base.copy(
                maxTokens = clamped,
                thinkingEnabled = true,
                thinkingBudgetTokens = minOf(thinkingBudget, maxOf(0, clamped - MIN_ANSWER_TOKENS))
            )
        }
        return stream(model, context, resolved)
    }

    private fun processSseEvent(
        event: works.resolve.pathfinder.ai.transport.SseEvent,
        model: Model,
        context: TranscriptContext,
        currentTools: List<Tool>,
        state: AnthropicStreamState
    ): List<AssistantMessageEvent> {
        if (event.name == "error") {
            throw ProviderStreamException(event.data)
        }
        val name = event.name ?: return emptyList()
        if (name !in ANTHROPIC_MESSAGE_EVENTS) return emptyList()

        val parsed = try {
            parseJsonWithRepair(event.data)
        } catch (error: Exception) {
            throw ProviderStreamException(
                "Could not parse Anthropic SSE event $name: " +
                    "${error.message ?: error::class.simpleName}; data=${event.data}; " +
                    "raw=${rawEventLines(event)}"
            )
        }
        // pi reads the payload's `type` off whatever JSON value arrived; only
        // an object can carry a known one, so anything else is ignored.
        if (parsed !is JsonObject) return emptyList()

        return when (name) {
            "message_start" -> state.onMessageStart(parsed, model)
            "content_block_start" -> state.onContentBlockStart(parsed, currentTools)
            "content_block_delta" -> state.onContentBlockDelta(parsed)
            "content_block_stop" -> state.onContentBlockStop(parsed)
            "message_delta" -> state.onMessageDelta(parsed, model)
            "message_stop" -> state.onMessageStop()
            else -> emptyList()
        }
    }

    /**
     * pi's error text carries the event's raw framing lines; the transport
     * boundary discards them, so they are rebuilt from the retained fields
     * in pi's line order (joined with a literal `\n`, as upstream joins).
     */
    private fun rawEventLines(event: works.resolve.pathfinder.ai.transport.SseEvent): String =
        buildList {
            event.name?.let { add("event: $it") }
            event.data.split('\n').forEach { add("data: $it") }
        }.joinToString("\\n")

    private fun assertRequestAuth(
        provider: String,
        apiKey: String?,
        headers: Map<String, String?>
    ) {
        if (apiKey != null) return
        if (hasHeader(headers, "authorization") ||
            hasHeader(headers, "x-api-key") ||
            hasHeader(headers, "cf-aig-authorization")
        ) {
            return
        }
        throw ProviderAuthException("No API key for provider: $provider")
    }

    private fun formatProviderError(error: Exception): String = when (error) {
        is ProviderHttpException -> formatProviderError(normalizeProviderError(error))
        is ProviderStreamException -> error.message ?: "Provider stream error"
        else -> withCauseDetail(error)
    }

    private companion object {
        val ANTHROPIC_MESSAGE_EVENTS = setOf(
            "message_start",
            "message_delta",
            "message_stop",
            "content_block_start",
            "content_block_delta",
            "content_block_stop"
        )
    }
}

internal fun mapThinkingLevelToEffort(
    model: Model,
    level: works.resolve.pathfinder.ai.ThinkingLevel?
): String {
    // Core ThinkingLevel has no OFF case, so toModelThinkingLevel is total here.
    // A mapped string is passed through verbatim, exactly as pi casts it.
    val mapped = level?.let { model.thinkingLevelMap?.forLevel(it.toModelThinkingLevel()) }
    if (mapped is String) return mapped

    return when (level) {
        works.resolve.pathfinder.ai.ThinkingLevel.MINIMAL,
        works.resolve.pathfinder.ai.ThinkingLevel.LOW
        -> "low"

        works.resolve.pathfinder.ai.ThinkingLevel.MEDIUM -> "medium"

        else -> "high"
    }
}

internal const val MIN_ANSWER_TOKENS = 1024

internal val DEFAULT_THINKING_BUDGETS = mapOf(
    works.resolve.pathfinder.ai.ThinkingLevel.MINIMAL to 1024,
    works.resolve.pathfinder.ai.ThinkingLevel.LOW to 2048,
    works.resolve.pathfinder.ai.ThinkingLevel.MEDIUM to 8192,
    works.resolve.pathfinder.ai.ThinkingLevel.HIGH to 16384
)

/** Xhigh/max have no budget entries, so they clamp to high before lookup. */
internal fun clampReasoning(
    level: works.resolve.pathfinder.ai.ThinkingLevel
): works.resolve.pathfinder.ai.ThinkingLevel =
    if (level == works.resolve.pathfinder.ai.ThinkingLevel.XHIGH ||
        level == works.resolve.pathfinder.ai.ThinkingLevel.MAX
    ) {
        works.resolve.pathfinder.ai.ThinkingLevel.HIGH
    } else {
        level
    }

internal fun thinkingBudgetForLevel(
    level: works.resolve.pathfinder.ai.ThinkingLevel,
    customBudgets: Map<works.resolve.pathfinder.ai.ThinkingLevel, Int> = emptyMap()
): Int {
    val budgets = DEFAULT_THINKING_BUDGETS + customBudgets
    return budgets[clampReasoning(level)]!!
}

internal fun adjustMaxTokensForThinking(
    baseMaxTokens: Int?,
    modelMaxTokens: Int,
    reasoningLevel: works.resolve.pathfinder.ai.ThinkingLevel,
    customBudgets: Map<works.resolve.pathfinder.ai.ThinkingLevel, Int> = emptyMap()
): Pair<Int, Int> {
    var thinkingBudget = thinkingBudgetForLevel(reasoningLevel, customBudgets)
    val maxTokens = if (baseMaxTokens == null) {
        modelMaxTokens
    } else {
        minOf(baseMaxTokens + thinkingBudget, modelMaxTokens)
    }
    if (maxTokens <= thinkingBudget) {
        thinkingBudget = minOf(thinkingBudget, maxOf(0, maxTokens - MIN_ANSWER_TOKENS))
    }
    return maxTokens to thinkingBudget
}

/**
 * Required maps to [AnthropicToolChoice.Any]: the Anthropic protocol has no
 * "required" (a tool must be called), matching the Any/Required collapse in
 * the other adapters.
 */
internal fun mapToolChoice(choice: works.resolve.pathfinder.ai.ToolChoice?): AnthropicToolChoice? =
    when (choice) {
        works.resolve.pathfinder.ai.ToolChoice.Auto -> AnthropicToolChoice.Auto

        works.resolve.pathfinder.ai.ToolChoice.None -> AnthropicToolChoice.None

        works.resolve.pathfinder.ai.ToolChoice.Any,
        works.resolve.pathfinder.ai.ToolChoice.Required
        -> AnthropicToolChoice.Any

        is works.resolve.pathfinder.ai.ToolChoice.Function -> AnthropicToolChoice.Tool(choice.name)

        null -> null
    }

internal fun buildBaseOptions(
    model: Model,
    context: TranscriptContext,
    options: works.resolve.pathfinder.ai.SimpleStreamOptions
): AnthropicMessagesOptions = AnthropicMessagesOptions(
    apiKey = options.apiKey,
    sessionId = options.sessionId,
    disableContinuation = options.disableContinuation,
    temperature = options.temperature,
    maxTokens = clampMaxTokensToContext(model, context, options.maxTokens ?: model.maxTokens),
    cacheRetention = options.cacheRetention,
    timeoutMs = options.timeoutMs,
    maxRetries = options.maxRetries,
    maxRetryDelayMs = options.maxRetryDelayMs,
    env = options.env,
    headers = options.headers,
    identity = options.identity,
    onPayload = options.onPayload,
    onResponse = options.onResponse,
    telemetry = options.telemetry
)

internal fun isOAuthToken(apiKey: String): Boolean = apiKey.contains("sk-ant-oat")

/** pi's pinned Anthropic SDK version default. */
internal const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Composes the `anthropic-beta` feature list. An explicit `anthropic-beta`
 * entry in model or options headers wins: a string replaces the composed
 * list (split, trimmed, deduped), an explicit null suppresses betas entirely.
 *
 * The result travels as the `anthropic-beta` request header. pi passes it
 * through the Anthropic SDK's beta namespace (`client.beta.messages.create`),
 * which posts to `/v1/messages?beta=true`, strips `betas` from the JSON body,
 * and sends `betas.join(",")` as the header — verified against
 * `@anthropic-ai/sdk` 0.91.1. This port builds the same wire shape directly:
 * [buildRequestBody] carries `betas` so [AnthropicMessagesOptions.onPayload]
 * sees the same payload pi's hook sees, then `stream` moves it into the
 * header.
 */
internal fun getBetaFeatures(
    model: Model,
    context: TranscriptContext,
    isOAuthToken: Boolean,
    nativeToolChanges: Boolean,
    options: AnthropicMessagesOptions?,
    messageThreads: Boolean = false
): List<String> {
    var configuredFeatures: String? = null
    var configured = false
    for (headers in listOf<Map<String, String?>>(model.headers, options?.headers ?: emptyMap())) {
        for ((name, value) in headers) {
            if (name.lowercase() == "anthropic-beta") {
                configuredFeatures = value
                configured = true
            }
        }
    }
    if (configured) {
        if (configuredFeatures == null) return emptyList()
        return configuredFeatures.split(",").map {
            trimJsWhitespace(it)
        }.filter { it.isNotEmpty() }.distinct()
    }

    val compat = anthropicCompatOf(model)
    val features = mutableListOf<String>()
    if (isOAuthToken) {
        features.add("claude-code-20250219")
        features.add("oauth-2025-04-20")
    }
    if (getCurrentTools(context.messages).isNotEmpty() && !compat.supportsEagerToolInputStreaming) {
        features.add(FINE_GRAINED_TOOL_STREAMING_BETA)
    }
    if (
        model.reasoning &&
        options?.thinkingEnabled == true &&
        options.interleavedThinking &&
        compat.forceAdaptiveThinking != true
    ) {
        features.add(INTERLEAVED_THINKING_BETA)
    }
    if (compat.allowedFallbackModels.isNotEmpty()) {
        features.add(SERVER_SIDE_FALLBACK_BETA)
    }
    if (compat.supportsMidConvoEffort) {
        features.add(MID_CONVERSATION_OUTPUT_CONFIG_BETA)
        features.add(THINKING_BINDING_CONTROLS_BETA)
    }
    if (nativeToolChanges) features.add(INLINE_TOOLS_BETA)
    if (messageThreads) features.add(MESSAGE_THREADS_BETA)
    return features.distinct()
}

/**
 * Divergence (owner decision): pi unconditionally sends
 * `anthropic-dangerous-direct-browser-access: true`, a CORS-relaxation
 * header for browser clients; Pathfinder's OkHttp transport is not a browser
 * client, so the header is deliberately not sent.
 *
 * [betas] is the composed feature list (see [getBetaFeatures]); it rides in
 * the base layer so explicit model/options `anthropic-beta` headers still
 * win through [mergeHeaders].
 */
private fun buildHeaders(
    model: Model,
    isOAuth: Boolean,
    options: AnthropicMessagesOptions,
    context: TranscriptContext,
    cacheSessionId: String?,
    betas: List<String>
): Pair<Map<String, String>, String?> {
    val compat = anthropicCompatOf(model)

    if (model.provider == "github-copilot") {
        val base = buildMap<String, String?> {
            put("accept", "application/json")
            put("anthropic-version", ANTHROPIC_VERSION)
            if (betas.isNotEmpty()) put("anthropic-beta", betas.joinToString(","))
        }
        val merged = filterNonNull(
            mergeHeaders(
                getIdentityHeaders(model, options.identity),
                mergeHeaders(
                    base,
                    // Copilot dynamic headers override model headers but not
                    // per-request options headers.
                    mergeHeaders(
                        mergeHeaders(model.headers, copilotDynamicHeadersFor(model, context)),
                        options.headers
                    )
                )
            )
        )
        return merged to options.apiKey
    }

    if (isOAuth) {
        val base = buildMap<String, String?> {
            put("accept", "application/json")
            put("anthropic-version", ANTHROPIC_VERSION)
            if (betas.isNotEmpty()) put("anthropic-beta", betas.joinToString(","))
            put("user-agent", "claude-cli/$CLAUDE_CODE_VERSION")
            put("x-app", "cli")
        }
        return filterNonNull(
            mergeHeaders(
                base,
                mergeHeaders(model.headers, options.headers)
            )
        ) to options.apiKey
    }

    val sessionAffinity: Map<String, String?> =
        if (cacheSessionId != null && compat.sendSessionAffinityHeaders) {
            val header =
                if (compat.sessionAffinityFormat ==
                    works.resolve.pathfinder.ai.SessionAffinityFormat.OPENROUTER
                ) {
                    "x-session-id"
                } else {
                    "x-session-affinity"
                }
            mapOf(header to cacheSessionId)
        } else {
            emptyMap()
        }

    val base = mergeHeaders(
        buildMap<String, String?> {
            put("accept", "application/json")
            put("anthropic-version", ANTHROPIC_VERSION)
            options.apiKey?.let { put("x-api-key", it) }
            if (betas.isNotEmpty()) put("anthropic-beta", betas.joinToString(","))
        },
        sessionAffinity
    )
    val merged = filterNonNull(
        mergeHeaders(
            getIdentityHeaders(model, options.identity),
            mergeHeaders(base, mergeHeaders(model.headers, options.headers))
        )
    )
    return merged to null
}

private fun filterNonNull(headers: Map<String, String?>): Map<String, String> =
    headers.filterValues { it != null }.mapValues { it.value!! }

internal const val CLAUDE_CODE_IDENTITY =
    "You are Claude Code, Anthropic's official CLI for Claude."

internal data class AnthropicBuiltRequest(
    val params: JsonObject,
    val hasPreferredStrictTools: Boolean,
    /**
     * Effort of the final effort marker, recorded on the reply so later requests can replay it.
     */
    val providerThinkingLevel: String?
)

internal fun buildRequestBody(
    model: Model,
    context: TranscriptContext,
    isOAuthToken: Boolean,
    options: AnthropicMessagesOptions
): JsonObject = buildAnthropicParams(model, context, isOAuthToken, options).params

internal fun buildAnthropicParams(
    model: Model,
    context: TranscriptContext,
    isOAuthToken: Boolean,
    options: AnthropicMessagesOptions,
    disablePreferredStrictTools: Boolean = false,
    messageThreads: Boolean = false
): AnthropicBuiltRequest {
    val cacheControl = getCacheControl(model, options)
    val compat = anthropicCompatOf(model)
    val initialSystemMessage = getInitialSystemMessage(context.messages)
    val initialSystemText =
        if (initialSystemMessage != null) getSystemMessageText(initialSystemMessage) else ""
    val transformed =
        transformMessages(context.messages, model) { id, _ -> normalizeToolCallId(id) }
    val conversationMessages = if (initialSystemMessage !=
        null
    ) {
        transformed.drop(1)
    } else {
        transformed
    }
    // Native tool changes keep the request-level tool list fixed and define every later tool
    // by value in a `tool_addition` block, which also expresses same-name redefinitions.
    // Anthropic rejects a tool list where every tool is deferred, so there must be an initial
    // active tool to anchor the placeholder. Otherwise the current tool list is sent.
    var hasPreferredStrictTools = false
    fun convertToolDefinitions(
        tools: List<Tool>,
        toolCacheControl: JsonObject? = null
    ): List<JsonObject> {
        val definitions = convertTools(
            tools,
            isOAuthToken,
            compat.supportsEagerToolInputStreaming,
            compat.supportsStrictTools,
            toolCacheControl,
            disablePreferredStrictTools
        )
        if (definitions.indices.any { index ->
                definitions[index]["strict"] == JsonPrimitive(true) &&
                    tools[index].constrainedSampling is ConstrainedSamplingConfig.JsonSchema &&
                    (tools[index].constrainedSampling as ConstrainedSamplingConfig.JsonSchema)
                        .strict == StrictJsonSchemaMode.PREFER
            }
        ) {
            hasPreferredStrictTools = true
        }
        return definitions
    }
    val initialTools = initialSystemMessage?.toolsAdded.orEmpty()
    val nativeToolChanges = compat.supportsMidConvoSystemMessages &&
        compat.supportsMidConvoToolChanges &&
        initialTools.isNotEmpty()
    val thinkingDisabled = model.reasoning &&
        options.thinkingEnabled == false &&
        !thinkingOffExplicitlyUnsupported(model)
    // Effort cannot change between messages while thinking is disabled, so "off" sends no effort markers.
    val effortMessages = compat.supportsMidConvoEffort && !thinkingDisabled
    val converted = convertMessages(
        conversationMessages,
        isOAuthToken,
        cacheControl,
        compat.allowEmptySignature,
        if (effortMessages) model.provider else null,
        if (nativeToolChanges) ({ tools: List<Tool> -> convertToolDefinitions(tools) }) else null
    )
    val activeEffort = options.effort ?: "high"
    val betaFeatures = getBetaFeatures(
        model,
        context,
        isOAuthToken,
        nativeToolChanges,
        options,
        messageThreads
    )

    val body = mutableMapOf<String, JsonElement>()
    body["model"] = JsonPrimitive(model.id)
    body["messages"] = JsonArray(
        if (effortMessages) {
            insertThinkingLevelMessages(
                converted,
                activeEffort
            )
        } else {
            converted.messages
        }
    )
    body["max_tokens"] = JsonPrimitive(options.maxTokens ?: model.maxTokens)
    body["stream"] = JsonPrimitive(true)
    // The transport moves `betas` into the anthropic-beta header (see
    // getBetaFeatures); carried here so onPayload sees pi's payload shape.
    if (betaFeatures.isNotEmpty()) {
        body["betas"] = JsonArray(betaFeatures.map { JsonPrimitive(it) })
    }

    // OAuth requests prepend the Claude Code identity to the system prompt.
    val systemBlocks = mutableListOf<JsonObject>()
    if (isOAuthToken) {
        systemBlocks.add(textBlock(CLAUDE_CODE_IDENTITY, cacheControl))
        if (initialSystemText.isNotEmpty()) {
            systemBlocks.add(textBlock(initialSystemText, cacheControl))
        }
    } else if (initialSystemText.isNotEmpty()) {
        systemBlocks.add(textBlock(initialSystemText, cacheControl))
    }
    if (systemBlocks.isNotEmpty()) body["system"] = JsonArray(systemBlocks)

    // Temperature is incompatible with extended thinking and unsupported on
    // managed-effort models.
    if (options.temperature != null &&
        options.thinkingEnabled != true &&
        !compat.supportsMidConvoEffort &&
        compat.supportsTemperature
    ) {
        body["temperature"] = JsonPrimitive(options.temperature)
    }

    val toolCacheControl = if (compat.supportsCacheControlOnTools) cacheControl else null
    if (nativeToolChanges) {
        // Initial tools stay active with the cache breakpoint on the last one, followed by the
        // placeholder. The list never changes afterwards: later tools are defined by value in
        // `tool_addition` blocks and withdrawn by `tool_removal`, so the cached prefix survives
        // every tool change.
        body["tools"] = JsonArray(
            convertToolDefinitions(initialTools, toolCacheControl) + DEFERRED_TOOL_PLACEHOLDER
        )
    } else {
        val tools = getCurrentTools(context.messages)
        if (tools.isNotEmpty()) {
            body["tools"] = JsonArray(convertToolDefinitions(tools, toolCacheControl))
        }
    }

    if (thinkingDisabled) {
        // No effort is sent, so the model default applies. Anthropic rejects `block_binding`
        // with disabled thinking.
        body["thinking"] = buildJsonObject { put("type", "disabled") }
    } else if (effortMessages) {
        // Managed effort models use adaptive thinking so prefix mismatches can
        // be dropped instead of surfacing as persistent 400 responses.
        body["thinking"] = buildJsonObject {
            put("type", "adaptive")
            put("display", options.thinkingDisplay.name.lowercase())
            put("block_binding", buildJsonObject { put("prefix_mismatch_behavior", "drop_block") })
        }
        // Effort markers set the level for every reply. The top-level effort is part of the cached
        // prompt, so it stays fixed. It is "low" because organizations can cap effort and reject
        // requests containing any level above the cap.
        body["output_config"] = buildJsonObject { put("effort", "low") }
    } else if (model.reasoning && options.thinkingEnabled == true) {
        // Default to "summarized" so Opus 4.7 and Mythos Preview behave like
        // older Claude 4 models (whose API default is also "summarized").
        val display = options.thinkingDisplay
        if (compat.forceAdaptiveThinking == true) {
            // Adaptive thinking: Claude decides when and how much to think.
            body["thinking"] = buildJsonObject {
                put("type", "adaptive")
                put("display", display.name.lowercase())
            }
            options.effort?.let {
                body["output_config"] = buildJsonObject {
                    put("effort", it)
                }
            }
        } else {
            // Budget-based thinking for older models
            body["thinking"] = buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", options.thinkingBudgetTokens?.takeIf { it != 0 } ?: 1024)
                put("display", display.name.lowercase())
            }
        }
    }

    when (val choice = options.toolChoice) {
        AnthropicToolChoice.Auto -> body["tool_choice"] = buildJsonObject { put("type", "auto") }

        AnthropicToolChoice.Any -> body["tool_choice"] = buildJsonObject { put("type", "any") }

        AnthropicToolChoice.None -> body["tool_choice"] = buildJsonObject { put("type", "none") }

        is AnthropicToolChoice.Tool -> body["tool_choice"] = buildJsonObject {
            put("type", "tool")
            put("name", choice.name)
        }

        null -> {}
    }

    // Fallbacks carry model ids only; provider/cost are local metadata.
    // Omitted when empty: Anthropic rejects the field without permitted
    // fallback targets.
    val allowedFallbackModels = compat.allowedFallbackModels
    if (allowedFallbackModels.isNotEmpty()) {
        body["fallbacks"] = JsonArray(
            allowedFallbackModels.map { buildJsonObject { put("model", it.model) } }
        )
    }

    return AnthropicBuiltRequest(
        params = JsonObject(body),
        hasPreferredStrictTools = hasPreferredStrictTools,
        providerThinkingLevel = if (effortMessages) activeEffort else null
    )
}

/** An explicit null OFF entry in [Model.thinkingLevelMap] means off is unsupported. */
internal fun thinkingOffExplicitlyUnsupported(model: Model): Boolean {
    val map = model.thinkingLevelMap ?: return false
    return map.isSpecified(ModelThinkingLevel.OFF) && map.forLevel(ModelThinkingLevel.OFF) == null
}

/** Anthropic tool-use ids must match ^[a-zA-Z0-9_-]+$ (max 64 chars). */
internal fun normalizeToolCallId(id: String): String =
    id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

private fun textBlock(text: String, cacheControl: JsonObject? = null): JsonObject =
    buildJsonObject {
        put("type", "text")
        put("text", sanitizeSurrogates(text))
        if (cacheControl != null) put("cache_control", cacheControl)
    }

private fun imageBlock(block: ImageContent): JsonObject = buildJsonObject {
    put("type", "image")
    put(
        "source",
        buildJsonObject {
            put("type", "base64")
            put("media_type", block.mimeType)
            put("data", block.data)
        }
    )
}

internal data class ConvertedAnthropicMessages(
    val messages: List<JsonObject>,
    /** Per-converted-message-index historical effort for managed models. */
    val assistantLevels: Map<Int, String>
)

private fun isAnthropicEffort(value: String?): Boolean =
    value == "low" || value == "medium" || value == "high" || value == "xhigh" || value == "max"

private fun effortMarkerMessage(effort: String): JsonObject = buildJsonObject {
    put("role", "system")
    put("content", JsonArray(emptyList()))
    put("output_config", buildJsonObject { put("effort", effort) })
}

/**
 * Managed-effort request shape: an effort-only system message precedes every
 * assistant message with a recorded historical level, and the active effort
 * is appended as the final marker.
 */
private fun insertThinkingLevelMessages(
    converted: ConvertedAnthropicMessages,
    activeEffort: String
): List<JsonObject> {
    val messages = mutableListOf<JsonObject>()
    converted.messages.forEachIndexed { index, message ->
        converted.assistantLevels[index]?.let { historicalEffort ->
            messages.add(effortMarkerMessage(historicalEffort))
        }
        messages.add(message)
    }
    messages.add(effortMarkerMessage(activeEffort))
    return messages
}

private fun convertToolResult(
    msg: ToolResultMessage,
    content: List<Content> = msg.content
): JsonObject = buildJsonObject {
    put("type", "tool_result")
    put("tool_use_id", msg.toolCallId)
    put(
        "content",
        when (val converted = convertContentBlocks(content)) {
            is JsonArray -> converted
            else -> JsonPrimitive(converted.toString())
        }
    )
    put("is_error", msg.isError)
}

internal fun convertMessages(
    transformedMessages: List<Message>,
    isOAuthToken: Boolean,
    cacheControl: JsonObject?,
    allowEmptySignature: Boolean,
    managedProvider: String? = null,
    /** Converts tool definitions for native `tool_addition` blocks; null when changes are not native. */
    convertToolDefinitions: ((List<Tool>) -> List<JsonObject>)? = null
): ConvertedAnthropicMessages {
    val params = mutableListOf<JsonObject>()
    val assistantLevels = mutableMapOf<Int, String>()
    // Later system messages are held back and emitted directly before the next assistant
    // message that is sent (or at the end of the transcript). Assistant messages that convert to
    // no blocks are dropped, so holding past them keeps an update from landing before a user
    // message (#11091). Anthropic requires `tool_result` blocks to
    // immediately follow their `tool_use`, so a system message between them is rejected; this
    // also mirrors where the managed-effort system messages are inserted. As a result an
    // update placed before a user message in the transcript lands after it on the wire.
    val pendingSystemMessages = mutableListOf<JsonObject>()
    fun flushPendingSystemMessages() {
        params.addAll(pendingSystemMessages)
        pendingSystemMessages.clear()
    }

    var i = 0
    while (i < transformedMessages.size) {
        val msg = transformedMessages[i]
        when (msg.role) {
            works.resolve.pathfinder.ai.MessageRole.SYSTEM -> {
                // Later system messages only reach this point when the model accepts them
                // natively; otherwise the transcript was collapsed before conversion.
                val system = msg as SystemMessage
                val text = renderSystemMessageUpdate(system)
                val blocks = mutableListOf<JsonObject>()
                if (text.isNotEmpty()) blocks.add(textBlock(text))
                if (convertToolDefinitions != null) {
                    val added = system.toolsAdded.orEmpty()
                    val redefined = added.map { it.name }.toHashSet()
                    for (tool in system.toolsRemoved.orEmpty()) {
                        // A new definition under the same name replaces the old one.
                        if (tool.name in redefined) continue
                        blocks.add(toolRemovalBlock(tool.name, isOAuthToken))
                    }
                    for (definition in convertToolDefinitions(added)) {
                        blocks.add(
                            buildJsonObject {
                                put("type", "tool_addition")
                                put(
                                    "tool",
                                    buildJsonObject {
                                        put("type", "tool_definition")
                                        put("definition", definition)
                                    }
                                )
                            }
                        )
                    }
                }
                if (blocks.isNotEmpty()) {
                    pendingSystemMessages.add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", JsonArray(blocks))
                        }
                    )
                }
            }

            works.resolve.pathfinder.ai.MessageRole.USER -> {
                val userContent = (msg as works.resolve.pathfinder.ai.UserMessage).content
                val blocks = userContent.mapNotNull { block ->
                    when (block) {
                        is TextContent ->
                            if (trimJsWhitespace(
                                    block.text
                                ).isNotEmpty()
                            ) {
                                textBlock(block.text)
                            } else {
                                null
                            }

                        is ImageContent -> imageBlock(block)

                        else -> null
                    }
                }
                if (blocks.isNotEmpty()) {
                    params.add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(blocks))
                        }
                    )
                }
            }

            works.resolve.pathfinder.ai.MessageRole.ASSISTANT -> {
                val assistant = msg as works.resolve.pathfinder.ai.AssistantMessage
                val blocks = mutableListOf<JsonObject>()
                for (block in assistant.content) {
                    when (block) {
                        is TextContent -> if (trimJsWhitespace(block.text).isNotEmpty()) {
                            blocks.add(
                                textBlock(block.text)
                            )
                        }

                        is ThinkingContent -> {
                            // Redacted thinking: pass the opaque payload back.
                            if (block.redacted) {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", "redacted_thinking")
                                        put("data", block.thinkingSignature ?: "")
                                    }
                                )
                                continue
                            }
                            val signature = block.thinkingSignature
                            val hasSignature =
                                !signature.isNullOrEmpty() &&
                                    trimJsWhitespace(signature).isNotEmpty()
                            if (trimJsWhitespace(block.thinking).isEmpty() &&
                                !hasSignature
                            ) {
                                continue
                            }
                            if (!hasSignature) {
                                blocks.add(
                                    if (allowEmptySignature) {
                                        buildJsonObject {
                                            put("type", "thinking")
                                            put("thinking", sanitizeSurrogates(block.thinking))
                                            put("signature", "")
                                        }
                                    } else {
                                        textBlock(block.thinking)
                                    }
                                )
                            } else {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", "thinking")
                                        put("thinking", sanitizeSurrogates(block.thinking))
                                        put("signature", signature)
                                    }
                                )
                            }
                        }

                        is ToolCall -> blocks.add(
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", block.id)
                                put(
                                    "name",
                                    if (isOAuthToken) toClaudeCodeName(block.name) else block.name
                                )
                                put("input", block.arguments)
                            }
                        )

                        else -> {}
                    }
                }
                if (blocks.isNotEmpty()) {
                    flushPendingSystemMessages()
                    val messageIndex = params.size
                    params.add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", JsonArray(blocks))
                        }
                    )
                    if (
                        managedProvider != null &&
                        assistant.api == "anthropic-messages" &&
                        assistant.provider == managedProvider &&
                        isAnthropicEffort(assistant.providerThinkingLevel)
                    ) {
                        assistantLevels[messageIndex] = assistant.providerThinkingLevel!!
                    }
                }
            }

            works.resolve.pathfinder.ai.MessageRole.TOOL_RESULT -> {
                // Collect all consecutive toolResult messages, needed for z.ai Anthropic endpoint.
                val toolResults = mutableListOf<JsonObject>()
                // Anthropic rejects error tool results that contain anything but text, so their
                // images follow all tool results of the turn, which must come first (#10794).
                val errorImages = mutableListOf<JsonObject>()
                var j = i
                while (j < transformedMessages.size &&
                    transformedMessages[j].role ==
                    works.resolve.pathfinder.ai.MessageRole.TOOL_RESULT
                ) {
                    val toolMsg = transformedMessages[j] as ToolResultMessage
                    val split = splitErrorToolResultImages(toolMsg)
                    toolResults.add(
                        convertToolResult(toolMsg, split?.content ?: toolMsg.content)
                    )
                    if (split != null) {
                        errorImages.add(textBlock(split.caption))
                        errorImages.addAll(split.images.map(::imageBlock))
                    }
                    j++
                }
                i = j - 1
                params.add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", JsonArray(toolResults + errorImages))
                    }
                )
            }
        }
        i++
    }

    flushPendingSystemMessages()

    // Prompt caching: the marker on the last user or system message caches the
    // conversation history.
    if (cacheControl != null && params.isNotEmpty()) {
        val lastMessage = params.last()
        val lastRole = lastMessage.str("role")
        if (lastRole == "user" || lastRole == "system") {
            val content = lastMessage["content"]
            val contentValue = when {
                content is JsonArray && content.isNotEmpty() -> {
                    val lastBlock = content.last() as? JsonObject
                    val type = lastBlock.str("type")
                    if (lastBlock != null &&
                        (
                            type == "text" || type == "image" || type == "tool_result" ||
                                type == "tool_addition" || type == "tool_removal"
                            )
                    ) {
                        JsonArray(
                            content.dropLast(1) + lastBlock.toMutableMap().apply {
                                put("cache_control", cacheControl)
                            }.let { JsonObject(it) }
                        )
                    } else {
                        content
                    }
                }

                content is JsonPrimitive -> JsonArray(
                    listOf(textBlock(content.content, cacheControl))
                )

                else -> content ?: JsonNull
            }
            params[params.size - 1] = buildJsonObject {
                put("role", lastRole)
                put("content", contentValue)
            }
        }
    }

    return ConvertedAnthropicMessages(params, assistantLevels)
}

// Keywords Anthropic strict tool use rejects with a 400 for the whole request.
// https://platform.claude.com/docs/en/build-with-claude/structured-outputs#json-schema-limitations
private val ANTHROPIC_STRICT_UNSUPPORTED_KEYWORDS = setOf(
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "multipleOf",
    "maxItems",
    "uniqueItems",
    "minContains",
    "maxContains",
    "minProperties",
    "maxProperties"
)
private val ANTHROPIC_STRICT_STRING_FORMATS = setOf(
    "date-time",
    "time",
    "date",
    "duration",
    "email",
    "hostname",
    "uri",
    "ipv4",
    "ipv6",
    "uuid"
)

private val isAnthropicStrictUnsupportedKeyword: UnsupportedStrictSchemaKeywordCheck = {
        key,
        value
    ->
    if (key in ANTHROPIC_STRICT_UNSUPPORTED_KEYWORDS) {
        true
    } else if (key == "minItems") {
        val number = value.strictDoubleOrNull()
        number != 0.0 && number != 1.0
    } else if (key == "format") {
        val format = value.stringOrNull()
        format == null || format !in ANTHROPIC_STRICT_STRING_FORMATS
    } else {
        false
    }
}

/** A mid-conversation `tool_removal` block referencing a tool by name. */
private fun toolRemovalBlock(toolName: String, isOAuthToken: Boolean): JsonObject =
    buildJsonObject {
        put("type", "tool_removal")
        put(
            "tool",
            buildJsonObject {
                put("type", "tool_reference")
                put("name", if (isOAuthToken) toClaudeCodeName(toolName) else toolName)
            }
        )
    }

private data class AnthropicApiError(
    val status: Int,
    val type: String?,
    val message: String,
    /** The `error.details.error_code` Anthropic puts in thread rejections. */
    val errorCode: String?
)

private fun anthropicApiError(error: ProviderHttpException): AnthropicApiError? {
    val parsed = try {
        parseJsonWithRepair(error.body)
    } catch (_: Exception) {
        return null
    }
    val detail = (parsed as? JsonObject)?.obj("error") ?: return null
    val message = detail.str("message") ?: return null
    return AnthropicApiError(
        error.status,
        detail.str("type"),
        message,
        detail.obj("details")?.str("error_code")
    )
}

private val ANTHROPIC_STRICT_TOOL_LIMIT_MESSAGES = listOf(
    "The compiled grammar is too large",
    "Too many strict tools (",
    "Schemas contains too many parameters with union types ("
)

private fun isStrictToolLimitError(error: AnthropicApiError): Boolean = error.status == 400 &&
    error.type == "invalid_request_error" &&
    ANTHROPIC_STRICT_TOOL_LIMIT_MESSAGES.any { error.message.contains(it) }

internal fun convertTools(
    tools: List<Tool>,
    isOAuthToken: Boolean,
    supportsEagerToolInputStreaming: Boolean,
    supportsStrictTools: Boolean,
    cacheControl: JsonObject?,
    disablePreferredStrictTools: Boolean = false
): List<JsonObject> = tools.mapIndexed { index, tool ->
    val config = tool.constrainedSampling
    val strict =
        if (disablePreferredStrictTools &&
            config is ConstrainedSamplingConfig.JsonSchema &&
            config.strict == StrictJsonSchemaMode.PREFER
        ) {
            null
        } else {
            resolveJsonSchemaStrictSampling(
                tool,
                supportsStrictTools,
                isAnthropicStrictUnsupportedKeyword
            )
        }
    val parameters = getJsonSchemaToolParameters(tool, strict)
    val schema = parameters as? JsonObject ?: JsonObject(emptyMap())
    // Keep shared definitions so property $refs still resolve. Other root keywords stay out:
    // Anthropic rejects root anyOf/oneOf/allOf with a 400.
    val legacyInputSchema = buildJsonObject {
        put("type", "object")
        put("properties", schema["properties"] ?: JsonObject(emptyMap()))
        put("required", schema["required"] ?: JsonArray(emptyList()))
        schema["\$defs"]?.let { put("\$defs", it) }
        schema["definitions"]?.let { put("definitions", it) }
    }
    // Legacy type/properties/required override the schema's own keys.
    val inputSchema = if (strict == true) {
        val merged = LinkedHashMap(schema)
        merged.putAll(legacyInputSchema)
        JsonObject(merged)
    } else {
        legacyInputSchema
    }
    buildJsonObject {
        put("name", if (isOAuthToken) toClaudeCodeName(tool.name) else tool.name)
        put("description", tool.description)
        if (supportsEagerToolInputStreaming) put("eager_input_streaming", true)
        if (strict == true) put("strict", true)
        put("input_schema", inputSchema)
        if (cacheControl != null && index == tools.size - 1) put("cache_control", cacheControl)
    }
}

/**
 * A schema-required SSE event field is absent: pi's direct property access
 * throws and terminates the stream; the same failure surfaces here.
 */
private fun missingSseField(eventName: String, field: String): ProviderStreamException =
    ProviderStreamException("Anthropic $eventName event is missing required field \"$field\"")

/**
 * Accumulates the streamed response. Blocks are keyed by the upstream
 * `index` field; events interleave freely.
 */
internal class AnthropicStreamState(
    private val model: Model,
    private val timestampMs: Long,
    private val isOAuth: Boolean = false
) {
    private sealed interface Block {
        val streamIndex: Int

        /** Last immutable [Content] rendered for this block; null when stale. */
        var built: Content?
    }

    private class Text(override val streamIndex: Int) : Block {
        val text = StringBuilder()
        override var built: Content? = null
    }

    private class Thinking(override val streamIndex: Int, val redacted: Boolean) : Block {
        val thinking = StringBuilder()
        var signature = ""
        override var built: Content? = null
    }

    private class Tool(override val streamIndex: Int) : Block {
        var id = ""
        var name = ""

        /** Parsed arguments; seeded from the content_block_start input object. */
        var arguments: JsonObject = JsonObject(emptyMap())
        var argumentsError: String? = null
        val argumentsStream = ToolCallArgumentsStream()
        override var built: Content? = null
    }

    private val blocks = mutableListOf<Block>()
    private val byStreamIndex = mutableMapOf<Int, Int>()

    var usage: Usage = Usage()
        private set
    var stopReason = StopReason.PENDING
        private set
    var errorMessage: String? = null
        private set
    var rawStopReason: String? = null
        private set
    var responseId: String? = null
        private set
    var responseModel: String? = null
        private set

    /** Managed-effort models record the active effort on every response. */
    var providerThinkingLevel: String? = null
        internal set

    private val diagnostics = mutableListOf<AssistantMessageDiagnostic>()

    fun appendDiagnostic(diagnostic: AssistantMessageDiagnostic) {
        diagnostics.add(diagnostic)
    }

    /**
     * Model used for usage cost accounting: the requested model, or a copy
     * carrying the served fallback model's id and cost when message_start
     * reports a permitted fallback model.
     */
    private var usageModel: Model = model

    /** `input_transformations` from message_start/message_delta, when arrays. */
    private var inputTransformations: List<JsonElement>? = null

    var sawMessageStart = false
        private set
    var sawMessageStop = false
        private set

    fun onMessageStart(event: JsonObject, model: Model): List<AssistantMessageEvent> {
        sawMessageStart = true
        val message = event.obj("message")
            ?: throw missingSseField("message_start", "message")
        responseId = message["id"].strOrNull() ?: responseId
        val servedModel = message["model"].strOrNull()
        if (servedModel != model.id) responseModel = servedModel
        (message["input_transformations"] as? JsonArray)?.let { inputTransformations = it }
        if (responseModel != null) {
            val fallbackCost = model.anthropicCompat.allowedFallbackModels
                .find { it.provider == model.provider && it.model == responseModel }?.cost
            if (fallbackCost != null) {
                usageModel = model.copy(id = responseModel!!, cost = fallbackCost)
            }
        }
        // Capture initial token usage so an early abort still has input counts.
        val messageUsage = message.obj("usage") ?: throw missingSseField("message_start", "usage")
        usage = usage.copy(
            input = messageUsage.int("input_tokens") ?: 0,
            output = messageUsage.int("output_tokens") ?: 0,
            cacheRead = messageUsage.int("cache_read_input_tokens") ?: 0,
            cacheWrite = messageUsage.int("cache_creation_input_tokens") ?: 0,
            cacheWrite1h = messageUsage.obj("cache_creation")
                ?.int("ephemeral_1h_input_tokens") ?: 0
        )
        usage = withTotal(usage)
        return emptyList()
    }

    fun onContentBlockStart(
        event: JsonObject,
        tools: List<works.resolve.pathfinder.ai.Tool>
    ): List<AssistantMessageEvent> {
        val index = event.int("index") ?: throw missingSseField("content_block_start", "index")
        val contentBlock = event.obj("content_block")
            ?: throw missingSseField("content_block_start", "content_block")
        val type = contentBlock.str("type")
        val block: Block = when (type) {
            "text" -> Text(index).apply {
                contentBlock["text"].strOrNull()?.let { text.append(it) }
            }

            "thinking" -> Thinking(index, redacted = false).apply {
                contentBlock["thinking"].strOrNull()?.let { thinking.append(it) }
                contentBlock["signature"].strOrNull()?.let { signature = it }
            }

            "redacted_thinking" -> Thinking(index, redacted = true).apply {
                thinking.append("[Reasoning redacted]")
                signature = contentBlock["data"].strOrNull() ?: ""
            }

            "tool_use" -> Tool(index).apply {
                id = contentBlock["id"].strOrNull() ?: ""
                var blockName = contentBlock["name"].strOrNull() ?: ""
                if (isOAuth) blockName = fromClaudeCodeName(blockName, tools)
                name = blockName
                arguments = contentBlock.obj("input") ?: JsonObject(emptyMap())
            }

            // A pre-output fallback marker is expected; once output has begun
            // it means the server swapped models mid-response.
            "fallback" -> {
                if (blocks.isNotEmpty()) {
                    throw ProviderStreamException(
                        "Anthropic performed an unsupported mid-output model fallback"
                    )
                }
                return emptyList()
            }

            else -> return emptyList()
        }
        byStreamIndex[index] = blocks.size
        blocks.add(block)
        val contentIndex = blocks.size - 1
        return listOf(
            when (block) {
                is Text -> AssistantMessageEvent.TextStart(contentIndex, snapshot())
                is Thinking -> AssistantMessageEvent.ThinkingStart(contentIndex, snapshot())
                is Tool -> AssistantMessageEvent.ToolCallStart(contentIndex, snapshot())
            }
        )
    }

    fun onContentBlockDelta(event: JsonObject): List<AssistantMessageEvent> {
        val index = event.int("index") ?: throw missingSseField("content_block_delta", "index")
        val delta = event.obj("delta") ?: throw missingSseField("content_block_delta", "delta")
        val blockIndex = byStreamIndex[index] ?: return emptyList()
        return when (val deltaType = delta.str("type")) {
            "text_delta" -> {
                val text = (blocks[blockIndex] as? Text) ?: return emptyList()
                val value = delta["text"].strOrNull() ?: ""
                text.text.append(value)
                text.built = null
                listOf(AssistantMessageEvent.TextDelta(blockIndex, value))
            }

            "thinking_delta" -> {
                val thinking = (blocks[blockIndex] as? Thinking) ?: return emptyList()
                val value = delta["thinking"].strOrNull() ?: ""
                thinking.thinking.append(value)
                thinking.built = null
                listOf(AssistantMessageEvent.ThinkingDelta(blockIndex, value))
            }

            "input_json_delta" -> {
                val tool = (blocks[blockIndex] as? Tool) ?: return emptyList()
                val value = delta["partial_json"].strOrNull() ?: ""
                tool.arguments = tool.argumentsStream.append(value)
                tool.built = null
                listOf(AssistantMessageEvent.ToolCallDelta(blockIndex, value))
            }

            "signature_delta" -> {
                val thinking = (blocks[blockIndex] as? Thinking) ?: return emptyList()
                thinking.signature += delta["signature"].strOrNull() ?: ""
                thinking.built = null
                emptyList()
            }

            else -> emptyList()
        }
    }

    fun onContentBlockStop(event: JsonObject): List<AssistantMessageEvent> {
        val index = event.int("index") ?: throw missingSseField("content_block_stop", "index")
        val blockIndex = byStreamIndex[index] ?: return emptyList()
        return when (val block = blocks[blockIndex]) {
            is Text -> listOf(
                AssistantMessageEvent.TextEnd(blockIndex, block.text.toString(), snapshot())
            )

            is Thinking -> listOf(
                AssistantMessageEvent.ThinkingEnd(
                    blockIndex,
                    block.thinking.toString(),
                    snapshot()
                )
            )

            is Tool -> {
                // The content_block_start seed input is discarded here: like
                // upstream, final arguments come from the accumulated argument
                // stream only. Finished calls parse strictly so a malformed tail
                // is reported instead of keeping a valid prefix.
                val parsed = block.argumentsStream.finish()
                block.arguments = parsed.arguments
                block.argumentsError = parsed.argumentsError
                block.built = null
                listOf(
                    AssistantMessageEvent.ToolCallEnd(blockIndex, toolCallOf(block), snapshot())
                )
            }
        }
    }

    fun onMessageDelta(event: JsonObject, model: Model): List<AssistantMessageEvent> {
        (event["input_transformations"] as? JsonArray)?.let { inputTransformations = it }
        val delta = event.obj("delta") ?: throw missingSseField("message_delta", "delta")
        val stopReason = delta.str("stop_reason")
        if (stopReason != null) {
            rawStopReason = stopReason
            val (mapped, error) = mapStopReason(
                stopReason,
                delta.obj("stop_details")?.get("explanation").strOrNull()
            )
            this.stopReason = mapped
            errorMessage = error ?: errorMessage
        }
        // Only update usage fields when present; preserves message_start values
        // when proxies omit them in message_delta.
        val eventUsage = event.obj("usage")
        if (eventUsage != null) {
            usage = usage.copy(
                input = eventUsage.int("input_tokens") ?: usage.input,
                output = eventUsage.int("output_tokens") ?: usage.output,
                cacheRead = eventUsage.int("cache_read_input_tokens") ?: usage.cacheRead,
                cacheWrite = eventUsage.int("cache_creation_input_tokens") ?: usage.cacheWrite,
                // Gateways include the TTL breakdown in deltas, though the SDK only types it
                // on message_start.
                cacheWrite1h = eventUsage.obj("cache_creation")
                    ?.int("ephemeral_1h_input_tokens") ?: usage.cacheWrite1h,
                reasoning = eventUsage.obj("output_tokens_details")
                    ?.int("thinking_tokens") ?: usage.reasoning
            )
            usage = withTotal(usage)
            if (isOpenRouterModel(model)) {
                usage = applyOpenRouterReportedCost(usage, eventUsage)
            }
        }
        return emptyList()
    }

    fun onMessageStop(): List<AssistantMessageEvent> {
        sawMessageStop = true
        return emptyList()
    }

    private fun mapStopReason(
        reason: String,
        refusalExplanation: String?
    ): Pair<StopReason, String?> = when (reason) {
        "end_turn" -> StopReason.STOP to null

        "max_tokens" -> StopReason.LENGTH to null

        "tool_use" -> StopReason.TOOL_USE to null

        "refusal" ->
            StopReason.ERROR to
                (
                    refusalExplanation?.takeIf { it.isNotEmpty() }
                        ?: "The model refused to complete the request"
                    )

        "pause_turn" -> StopReason.STOP to null

        "stop_sequence" -> StopReason.STOP to null

        "sensitive" -> StopReason.ERROR to "Provider stopped with: sensitive"

        else -> throw ProviderStreamException("Unhandled stop reason: $reason")
    }

    private fun toolCallOf(block: Tool): ToolCall = ToolCall(
        id = block.id,
        name = block.name,
        arguments = block.arguments,
        argumentsError = block.argumentsError
    )

    /** Anthropic doesn't provide total_tokens; compute from components. */
    private fun withTotal(usage: Usage): Usage {
        val total = usage.input + usage.output + usage.cacheRead + usage.cacheWrite
        val withTotal = usage.copy(totalTokens = total)
        return withTotal.copy(cost = calculateCost(usageModel, withTotal))
    }

    /**
     * Diagnostic for thinking-dropped input transformations reported by the
     * serving model; the final stream event's list wins (message_delta
     * replaces message_start's).
     */
    fun inputTransformationsDiagnostic(timestamp: Long): AssistantMessageDiagnostic? {
        val transformations = inputTransformations ?: return null
        if (transformations.isEmpty()) return null
        return AssistantMessageDiagnostic(
            type = "anthropic_input_transformations",
            timestamp = timestamp,
            details = buildJsonObject {
                put(
                    "transformations",
                    JsonArray(
                        transformations.map { transformation ->
                            buildJsonObject {
                                (transformation as? JsonObject)?.get("type").strOrNull()?.let {
                                    put("type", it)
                                }
                                (transformation as? JsonObject)?.get("path").strOrNull()?.let {
                                    put("path", it)
                                }
                                (transformation as? JsonObject)?.get("reason").strOrNull()?.let {
                                    put("reason", it)
                                }
                            }
                        }
                    )
                )
            }
        )
    }

    /**
     * Accurate as of the last boundary event (block start/stop, done,
     * error): immutable content values are cached per block and reused
     * across snapshots, so a boundary snapshot re-renders only the blocks a
     * delta landed in since the previous one.
     */
    fun snapshot(): AssistantMessage = AssistantMessage(
        content = blocks.map { block ->
            block.built ?: when (block) {
                is Text -> TextContent(block.text.toString())

                is Thinking -> ThinkingContent(
                    block.thinking.toString(),
                    block.signature,
                    block.redacted
                )

                is Tool -> toolCallOf(block)
            }.also { block.built = it }
        },
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = usage,
        stopReason = stopReason,
        errorMessage = errorMessage,
        rawStopReason = rawStopReason,
        responseId = responseId,
        responseModel = responseModel,
        providerThinkingLevel = providerThinkingLevel,
        diagnostics = diagnostics.toList(),
        timestamp = timestampMs
    )
}
