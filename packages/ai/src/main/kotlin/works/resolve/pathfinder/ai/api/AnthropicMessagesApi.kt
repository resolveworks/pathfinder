package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.Context
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
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.anthropicCompatOf
import works.resolve.pathfinder.ai.hasHeader
import works.resolve.pathfinder.ai.headersToRecord
import works.resolve.pathfinder.ai.mergeHeaders
import works.resolve.pathfinder.ai.toModelThinkingLevel
import works.resolve.pathfinder.ai.toToolChoice
import works.resolve.pathfinder.ai.calculateCost
import works.resolve.pathfinder.ai.utils.AssistantMessageDiagnostic
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.appendAssistantMessageDiagnostic
import works.resolve.pathfinder.ai.utils.clampMaxTokensToContext
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.splitDeferredTools
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strOrNull
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.telemetry.TelemetryContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

internal fun resolveCacheRetention(cacheRetention: CacheRetention?, env: Map<String, String>): CacheRetention {
    if (cacheRetention != null) return cacheRetention
    if (env["PI_CACHE_RETENTION"] == "long") return CacheRetention.LONG
    return CacheRetention.SHORT
}

internal fun getCacheControl(model: Model, options: AnthropicMessagesOptions): JsonObject? {
    val retention = resolveCacheRetention(options.cacheRetention, options.env)
    if (retention == CacheRetention.NONE) return null
    val ttl = retention == CacheRetention.LONG && anthropicCompatOf(model).supportsLongCacheRetention
    return buildJsonObject {
        put("type", "ephemeral")
        if (ttl) put("ttl", "1h")
    }
}

// Stealth mode: on OAuth requests, tool names mimic Claude Code's exactly.
internal const val CLAUDE_CODE_VERSION = "2.1.251"

private val CLAUDE_CODE_TOOLS = listOf(
    "Read", "Write", "Edit", "Bash", "Grep", "Glob", "AskUserQuestion",
    "EnterPlanMode", "ExitPlanMode", "KillShell", "NotebookEdit", "Skill",
    "Task", "TaskOutput", "TodoWrite", "WebFetch", "WebSearch",
)

private val CC_TOOL_LOOKUP = CLAUDE_CODE_TOOLS.associateBy { it.lowercase() }

internal const val FINE_GRAINED_TOOL_STREAMING_BETA = "fine-grained-tool-streaming-2025-05-14"
internal const val INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14"
internal const val SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01"
internal const val MID_CONVERSATION_OUTPUT_CONFIG_BETA = "mid-conversation-output-config-2026-07-01"
internal const val THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01"

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
        return sanitizeSurrogates(content.filterIsInstance<TextContent>().joinToString("\n") { it.text })
    }

    val blocks = content.map { block ->
        when (block) {
            is TextContent -> buildJsonObject {
                put("type", "text")
                put("text", sanitizeSurrogates(block.text))
            }
            is ImageContent -> buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", block.mimeType)
                    put("data", block.data)
                })
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
            },
        )
    }
    return JsonArray(blocks)
}

enum class AnthropicEffort { LOW, MEDIUM, HIGH, XHIGH, MAX }

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
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Null means "unspecified" (thinking omitted). */
    val thinkingEnabled: Boolean? = null,
    /** Default 1024 when enabled (older models). */
    val thinkingBudgetTokens: Int? = null,
    /** Adaptive-thinking effort level. */
    val effort: AnthropicEffort? = null,
    val thinkingDisplay: AnthropicThinkingDisplay = AnthropicThinkingDisplay.SUMMARIZED,
    val interleavedThinking: Boolean = true,
    val toolChoice: AnthropicToolChoice? = null,
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
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
    val telemetryContext: TelemetryContext? = null,
) {
    override fun toString(): String = optionsToString(
        "AnthropicMessagesOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
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
        "telemetryContext" to (telemetryContext != null),
    )
}

/**
 * Anthropic Messages streaming adapter.
 *
 * Divergences from pi:
 * - pi repairs SSE data (parseJsonWithRepair) and streamed tool JSON
 *   (parseStreamingJson); here a malformed data payload is a protocol error
 *   and tool arguments accumulate as raw JSON strings.
 * - pi's ambient ANTHROPIC_AUTH_TOKEN / ANTHROPIC_OAUTH_TOKEN env paths are
 *   reduced to ANTHROPIC_API_KEY.
 * - AbortSignal aborts map to coroutine cancellation, which propagates
 *   without an Error event — the established Pathfinder convention
 *   (see OpenAiCompletionsApi / Types.kt).
 */
class AnthropicMessagesApi(
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val clock: Clock = Clock.System,
) : ChatApi {

    fun stream(
        model: Model,
        context: Context,
        options: AnthropicMessagesOptions = AnthropicMessagesOptions(),
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = clock.now().toEpochMilliseconds()
        // Copilot is never OAuth: its Bearer-auth branch is checked first,
        // as in pi's createClient.
        val isOAuth = model.provider != "github-copilot" && options.apiKey?.let { isOAuthToken(it) } == true
        val providerThinkingLevel =
            if (anthropicCompatOf(model).supportsMidConvoEffort) {
                (options.effort ?: AnthropicEffort.HIGH).name.lowercase()
            } else {
                null
            }
        val state = AnthropicStreamState(model, startedAtMs, isOAuth, providerThinkingLevel)
        try {
            assertRequestAuth(model.provider, options.apiKey, options.headers)

            val retention = resolveCacheRetention(options.cacheRetention, options.env)
            val cacheSessionId = if (retention == CacheRetention.NONE) null else options.sessionId

            var params = buildRequestBody(model, context, isOAuth, options)
            options.onPayload?.let { hook ->
                hook(params, model)?.let { next ->
                    // A replacement payload must not turn off streaming.
                    params = JsonObject(next.toMutableMap().apply { put("stream", JsonPrimitive(true)) })
                }
            }
            // The beta namespace moves `betas` out of the body and into the
            // `anthropic-beta` header (see getBetaFeatures).
            val betas = (params["betas"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            params = JsonObject(params.toMutableMap().apply { remove("betas") })
            val body = params
                .toString()
                .toByteArray(Charsets.UTF_8)

            val (headers, bearerToken) = buildHeaders(model, isOAuth, options, context, cacheSessionId, betas)
            val url = model.baseUrl.trimEnd('/') + "/v1/messages?beta=true"
            val request = TransportRequest(
                url = url,
                bearerToken = bearerToken,
                headers = headers,
                body = body,
                timeoutMs = options.timeoutMs,
            )

            val response = retry.retryProviderRequest<TransportResponse>(options.maxRetries, options.maxRetryDelayMs) {
                transport.post(request)
            }

            options.onResponse?.invoke(ProviderResponse(response.status, headersToRecord(response.headers)), model)

            emit(AssistantMessageEvent.Start(state.snapshot()))

            response.events.collect { event ->
                processSseEvent(event, model, context, state)?.forEach { emit(it) }
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

            var message = state.snapshot()
            state.inputTransformationsDiagnostic(clock.now().toEpochMilliseconds())?.let { diagnostic ->
                message = appendAssistantMessageDiagnostic(message, diagnostic)
            }
            emit(AssistantMessageEvent.Done(state.stopReason, message))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val finalMessage = state.snapshot().copy(
                stopReason = StopReason.ERROR,
                errorMessage = formatProviderError(error),
            )
            emit(AssistantMessageEvent.Error(finalMessage.stopReason, finalMessage))
        }
    }

    /**
     * Divergence from pi: missing auth emits a terminal
     * [AssistantMessageEvent.Error] instead of throwing synchronously.
     */
    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<AssistantMessageEvent> = flow {
        try {
            assertRequestAuth(model.provider, options.apiKey, options.headers)
        } catch (error: Exception) {
            emit(missingAuthEvent(model, error))
            return@flow
        }

        val base = buildBaseOptions(model, context, options).copy(
            toolChoice = mapToolChoice(options.toolChoice?.toToolChoice()),
        )
        val reasoning = options.reasoning
        val resolved: AnthropicMessagesOptions = if (reasoning == null) {
            base.copy(thinkingEnabled = false)
        } else if (anthropicCompatOf(model).forceAdaptiveThinking == true) {
            base.copy(
                thinkingEnabled = true,
                effort = mapThinkingLevelToEffort(model, reasoning),
            )
        } else {
            val (maxTokens, thinkingBudget) =
                adjustMaxTokensForThinking(base.maxTokens, model.maxTokens, reasoning, options.thinkingBudgets)
            val clamped = clampMaxTokensToContext(model, context, maxTokens)
            base.copy(
                maxTokens = clamped,
                thinkingEnabled = true,
                thinkingBudgetTokens = minOf(thinkingBudget, maxOf(0, clamped - MIN_ANSWER_TOKENS)),
            )
        }
        emitAll(stream(model, context, resolved))
    }

    private fun processSseEvent(
        event: works.resolve.pathfinder.ai.transport.SseEvent,
        model: Model,
        context: Context,
        state: AnthropicStreamState,
    ): List<AssistantMessageEvent> {
        if (event.name == "error") {
            throw ProviderStreamException(event.data)
        }
        val name = event.name ?: return emptyList()
        if (name !in ANTHROPIC_MESSAGE_EVENTS) return emptyList()

        val parsed = try {
            lenientJson.parseToJsonElement(event.data)
        } catch (error: Exception) {
            throw ProviderStreamException(
                "Could not parse Anthropic SSE event $name: ${error.message ?: error::class.simpleName}; data=${event.data}",
            )
        }
        if (parsed !is JsonObject) {
            throw ProviderStreamException("Could not parse Anthropic SSE event $name: expected object; data=${event.data}")
        }

        return when (name) {
            "message_start" -> state.onMessageStart(parsed, model)
            "content_block_start" -> state.onContentBlockStart(parsed, context)
            "content_block_delta" -> state.onContentBlockDelta(parsed)
            "content_block_stop" -> state.onContentBlockStop(parsed)
            "message_delta" -> state.onMessageDelta(parsed, model)
            "message_stop" -> state.onMessageStop()
            else -> emptyList()
        }
    }

    private fun assertRequestAuth(provider: String, apiKey: String?, headers: Map<String, String?>) {
        if (apiKey != null) return
        if (hasHeader(headers, "authorization") ||
            hasHeader(headers, "x-api-key") ||
            hasHeader(headers, "cf-aig-authorization")
        ) {
            return
        }
        throw ProviderAuthException("No API key for provider: $provider")
    }

    private fun missingAuthEvent(
        model: Model,
        error: Exception,
    ): AssistantMessageEvent.Error {
        val message = AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = StopReason.ERROR,
            errorMessage = error.message ?: "Unknown error",
            timestamp = clock.now().toEpochMilliseconds(),
        )
        return AssistantMessageEvent.Error(message.stopReason, message)
    }

    private fun formatProviderError(error: Exception): String = when (error) {
        is ProviderHttpException -> formatProviderError(normalizeProviderError(error))
        is ProviderStreamException -> error.message ?: "Provider stream error"
        else -> error.message ?: error::class.simpleName ?: "Unknown error"
    }

    private companion object {
        val ANTHROPIC_MESSAGE_EVENTS = setOf(
            "message_start",
            "message_delta",
            "message_stop",
            "content_block_start",
            "content_block_delta",
            "content_block_stop",
        )
    }
}

internal fun mapThinkingLevelToEffort(
    model: Model,
    level: works.resolve.pathfinder.ai.ThinkingLevel?,
): AnthropicEffort {
    // Core ThinkingLevel has no OFF case, so toModelThinkingLevel is total here.
    val mapped = level?.let { model.thinkingLevelMap?.forLevel(it.toModelThinkingLevel()) }
    if (mapped is String) {
        return try {
            AnthropicEffort.valueOf(mapped.uppercase())
        } catch (_: IllegalArgumentException) {
            AnthropicEffort.HIGH
        }
    }
    return when (level) {
        works.resolve.pathfinder.ai.ThinkingLevel.MINIMAL,
        works.resolve.pathfinder.ai.ThinkingLevel.LOW,
        -> AnthropicEffort.LOW
        works.resolve.pathfinder.ai.ThinkingLevel.MEDIUM -> AnthropicEffort.MEDIUM
        else -> AnthropicEffort.HIGH
    }
}

internal const val MIN_ANSWER_TOKENS = 1024

internal val DEFAULT_THINKING_BUDGETS = mapOf(
    works.resolve.pathfinder.ai.ThinkingLevel.MINIMAL to 1024,
    works.resolve.pathfinder.ai.ThinkingLevel.LOW to 2048,
    works.resolve.pathfinder.ai.ThinkingLevel.MEDIUM to 8192,
    works.resolve.pathfinder.ai.ThinkingLevel.HIGH to 16384,
)

/** Xhigh/max have no budget entries, so they clamp to high before lookup. */
internal fun clampReasoning(
    level: works.resolve.pathfinder.ai.ThinkingLevel,
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
    customBudgets: Map<works.resolve.pathfinder.ai.ThinkingLevel, Int> = emptyMap(),
): Int {
    val budgets = DEFAULT_THINKING_BUDGETS + customBudgets
    return budgets[clampReasoning(level)]!!
}

internal fun adjustMaxTokensForThinking(
    baseMaxTokens: Int?,
    modelMaxTokens: Int,
    reasoningLevel: works.resolve.pathfinder.ai.ThinkingLevel,
    customBudgets: Map<works.resolve.pathfinder.ai.ThinkingLevel, Int> = emptyMap(),
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
        works.resolve.pathfinder.ai.ToolChoice.Required,
        -> AnthropicToolChoice.Any
        is works.resolve.pathfinder.ai.ToolChoice.Function -> AnthropicToolChoice.Tool(choice.name)
        null -> null
    }

internal fun buildBaseOptions(
    model: Model,
    context: Context,
    options: works.resolve.pathfinder.ai.SimpleStreamOptions,
): AnthropicMessagesOptions =
    AnthropicMessagesOptions(
        apiKey = options.apiKey,
        sessionId = options.sessionId,
        temperature = options.temperature,
        maxTokens = clampMaxTokensToContext(model, context, options.maxTokens ?: model.maxTokens),
        cacheRetention = options.cacheRetention,
        timeoutMs = options.timeoutMs,
        maxRetries = options.maxRetries,
        maxRetryDelayMs = options.maxRetryDelayMs,
        env = options.env,
        headers = options.headers,
        onPayload = options.onPayload,
        onResponse = options.onResponse,
        telemetryContext = options.telemetryContext,
    )

internal fun isOAuthToken(apiKey: String): Boolean = apiKey.contains("sk-ant-oat")

/** pi's pinned Anthropic SDK version default. */
internal const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Default for [AnthropicMessagesCompat.supportsToolReferences]: first-party
 * Anthropic models except Haiku (rejects client-side `tool_reference`
 * blocks) and models that predate tool search (Claude 3.x, Opus/Sonnet 4.0,
 * Opus 4.1).
 */
internal fun defaultSupportsToolReferences(model: Model): Boolean {
    if (model.provider != "anthropic" || model.id.contains("haiku")) return false
    val version = Regex("^claude-(?:opus|sonnet|fable)-(\\d+)(?:-(\\d+))?(?:-|$)").find(model.id) ?: return false
    val major = version.groupValues[1].toInt()
    // A long "minor" (a date suffix, >= 8 chars) is not a version minor.
    val minor = version.groupValues[2].takeIf { it.isNotEmpty() && it.length < 8 }?.toInt() ?: 0
    return major > 4 || (major == 4 && minor >= 5)
}

internal fun supportsToolReferences(model: Model): Boolean =
    anthropicCompatOf(model).supportsToolReferences ?: defaultSupportsToolReferences(model)

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
    context: Context,
    isOAuthToken: Boolean,
    options: AnthropicMessagesOptions?,
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
        return configuredFeatures.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    val compat = anthropicCompatOf(model)
    val features = mutableListOf<String>()
    if (isOAuthToken) {
        features.add("claude-code-20250219")
        features.add("oauth-2025-04-20")
    }
    if (context.tools.isNotEmpty() && !compat.supportsEagerToolInputStreaming) {
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
    context: Context,
    cacheSessionId: String?,
    betas: List<String>,
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
                mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), base),
                // Copilot dynamic headers override model headers but not
                // per-request options headers.
                mergeHeaders(
                    mergeHeaders(model.headers, copilotDynamicHeadersFor(model, context)),
                    options.headers,
                ),
            ),
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
                mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), base),
                mergeHeaders(model.headers, options.headers),
            ),
        ) to options.apiKey
    }

    val sessionAffinity: Map<String, String?> =
        if (cacheSessionId != null && compat.sendSessionAffinityHeaders) {
            mapOf("x-session-affinity" to cacheSessionId)
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
        sessionAffinity,
    )
    val merged = filterNonNull(
        mergeHeaders(
            mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), base),
            mergeHeaders(model.headers, options.headers),
        ),
    )
    return merged to null
}

private fun filterNonNull(headers: Map<String, String?>): Map<String, String> =
    headers.filterValues { it != null }.mapValues { it.value!! }

internal const val CLAUDE_CODE_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."

internal fun buildRequestBody(
    model: Model,
    context: Context,
    isOAuthToken: Boolean,
    options: AnthropicMessagesOptions,
): JsonObject {
    val cacheControl = getCacheControl(model, options)
    val compat = anthropicCompatOf(model)
    val transformed = transformMessages(context.messages, model) { id, _ -> normalizeToolCallId(id) }
    val normalizeToolName: (String) -> String = if (isOAuthToken) ::toClaudeCodeName else { name -> name }
    val toolPlacement = splitDeferredTools(
        context.copy(messages = transformed),
        supportsToolReferences(model),
        normalizeToolName,
    )
    var immediateTools = toolPlacement.immediate
    var deferredTools = toolPlacement.deferred.values.toList()
    if (immediateTools.isEmpty() && deferredTools.isNotEmpty()) {
        immediateTools = deferredTools
        deferredTools = emptyList()
    }
    val deferredToolNames = deferredTools.map { normalizeToolName(it.name) }.toSet()
    val managed = compat.supportsMidConvoEffort
    val converted = convertMessages(
        transformed,
        isOAuthToken,
        cacheControl,
        compat.allowEmptySignature,
        deferredToolNames,
        normalizeToolName,
        if (managed) model.provider else null,
    )
    val activeEffort = options.effort ?: AnthropicEffort.HIGH
    val betaFeatures = getBetaFeatures(model, context, isOAuthToken, options)

    val body = mutableMapOf<String, JsonElement>()
    body["model"] = JsonPrimitive(model.id)
    body["messages"] = JsonArray(
        if (managed) insertThinkingLevelMessages(converted, activeEffort) else converted.messages,
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
        if (context.systemPrompt != null) {
            systemBlocks.add(textBlock(context.systemPrompt, cacheControl))
        }
    } else if (context.systemPrompt != null) {
        systemBlocks.add(textBlock(context.systemPrompt, cacheControl))
    }
    if (systemBlocks.isNotEmpty()) body["system"] = JsonArray(systemBlocks)

    // Temperature is incompatible with extended thinking and unsupported on
    // managed-effort models.
    if (options.temperature != null &&
        options.thinkingEnabled != true &&
        !managed &&
        compat.supportsTemperature
    ) {
        body["temperature"] = JsonPrimitive(options.temperature)
    }

    if (immediateTools.isNotEmpty() || deferredTools.isNotEmpty()) {
        body["tools"] = JsonArray(
            convertTools(
                immediateTools,
                isOAuthToken,
                compat.supportsEagerToolInputStreaming,
                compat.supportsStrictTools,
                if (compat.supportsCacheControlOnTools) cacheControl else null,
            ) + convertTools(
                deferredTools,
                isOAuthToken,
                compat.supportsEagerToolInputStreaming,
                compat.supportsStrictTools,
                cacheControl = null,
                deferLoading = true,
            ),
        )
    }

    // Managed effort models always use adaptive thinking so prefix mismatches
    // can be dropped instead of surfacing as persistent 400 responses; the
    // active effort travels per-message (see insertThinkingLevelMessages), so
    // the top-level output_config stays "high".
    if (managed) {
        body["thinking"] = buildJsonObject {
            put("type", "adaptive")
            put("display", options.thinkingDisplay.name.lowercase())
            put("block_binding", buildJsonObject { put("prefix_mismatch_behavior", "drop_block") })
        }
        body["output_config"] = buildJsonObject { put("effort", "high") }
    } else if (model.reasoning) {
        if (options.thinkingEnabled == true) {
            val display = options.thinkingDisplay
            if (compat.forceAdaptiveThinking == true) {
                body["thinking"] = buildJsonObject {
                    put("type", "adaptive")
                    put("display", display.name.lowercase())
                }
                options.effort?.let {
                    body["output_config"] = buildJsonObject {
                        put("effort", it.name.lowercase())
                    }
                }
            } else {
                body["thinking"] = buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", options.thinkingBudgetTokens?.takeIf { it != 0 } ?: 1024)
                    put("display", display.name.lowercase())
                }
            }
        } else if (options.thinkingEnabled == false && !thinkingOffExplicitlyUnsupported(model)) {
            body["thinking"] = buildJsonObject { put("type", "disabled") }
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
            allowedFallbackModels.map { buildJsonObject { put("model", it.model) } },
        )
    }

    return JsonObject(body)
}

/** An explicit null OFF entry in [Model.thinkingLevelMap] means off is unsupported. */
internal fun thinkingOffExplicitlyUnsupported(model: Model): Boolean {
    val map = model.thinkingLevelMap ?: return false
    return map.isSpecified(ModelThinkingLevel.OFF) && map.forLevel(ModelThinkingLevel.OFF) == null
}

/** Anthropic tool-use ids must match ^[a-zA-Z0-9_-]+$ (max 64 chars). */
internal fun normalizeToolCallId(id: String): String =
    id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

private fun textBlock(text: String, cacheControl: JsonObject? = null): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", sanitizeSurrogates(text))
    if (cacheControl != null) put("cache_control", cacheControl)
}

private fun imageBlock(block: ImageContent): JsonObject = buildJsonObject {
    put("type", "image")
    put("source", buildJsonObject {
        put("type", "base64")
        put("media_type", block.mimeType)
        put("data", block.data)
    })
}

internal data class ConvertedAnthropicMessages(
    val messages: List<JsonObject>,
    /** Per-converted-message-index historical effort for managed models. */
    val assistantLevels: Map<Int, AnthropicEffort>,
)

private fun isAnthropicEffort(value: String?): Boolean =
    value == "low" || value == "medium" || value == "high" || value == "xhigh" || value == "max"

private fun effortMarkerMessage(effort: AnthropicEffort): JsonObject = buildJsonObject {
    put("role", "system")
    put("content", JsonArray(emptyList()))
    put("output_config", buildJsonObject { put("effort", effort.name.lowercase()) })
}

/**
 * Managed-effort request shape: an effort-only system message precedes every
 * assistant message with a recorded historical level, and the active effort
 * is appended as the final marker.
 */
private fun insertThinkingLevelMessages(
    converted: ConvertedAnthropicMessages,
    activeEffort: AnthropicEffort,
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

/**
 * Converts one tool result. When it loads deferred tools, `tool_reference`
 * blocks replace the ordinary content (Anthropic rejects mixing them), and
 * the displaced content returns as sibling blocks to append after the whole
 * consecutive tool-result run.
 */
private fun convertToolResult(
    msg: ToolResultMessage,
    isOAuthToken: Boolean,
    deferredToolNames: Set<String>,
    loadedToolNames: MutableSet<String>,
    normalizeToolName: (String) -> String,
): Pair<JsonObject, List<JsonObject>> {
    val references = mutableListOf<JsonObject>()
    for (name in msg.addedToolNames) {
        val normalizedName = normalizeToolName(name)
        if (normalizedName !in deferredToolNames || normalizedName in loadedToolNames) continue
        loadedToolNames.add(normalizedName)
        references.add(
            buildJsonObject {
                put("type", "tool_reference")
                put("tool_name", if (isOAuthToken) toClaudeCodeName(name) else name)
            },
        )
    }
    val convertedContent = convertContentBlocks(msg.content)
    val toolResult = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", msg.toolCallId)
        put(
            "content",
            when {
                references.isNotEmpty() -> JsonArray(references)
                convertedContent is JsonArray -> convertedContent
                else -> JsonPrimitive(convertedContent.toString())
            },
        )
        put("is_error", msg.isError)
    }
    val siblingContent: List<JsonObject> = when {
        references.isEmpty() -> emptyList()
        convertedContent is JsonArray -> convertedContent.toList().filterIsInstance<JsonObject>()
        else -> listOf(textBlock(convertedContent.toString()))
    }
    return toolResult to siblingContent
}

internal fun convertMessages(
    transformedMessages: List<Message>,
    isOAuthToken: Boolean,
    cacheControl: JsonObject?,
    allowEmptySignature: Boolean,
    deferredToolNames: Set<String> = emptySet(),
    normalizeToolName: (String) -> String = { it },
    managedProvider: String? = null,
): ConvertedAnthropicMessages {
    val params = mutableListOf<JsonObject>()
    val assistantLevels = mutableMapOf<Int, AnthropicEffort>()
    val loadedToolNames = mutableSetOf<String>()

    var i = 0
    while (i < transformedMessages.size) {
        val msg = transformedMessages[i]
        when (msg.role) {
            works.resolve.pathfinder.ai.MessageRole.USER -> {
                val userContent = (msg as works.resolve.pathfinder.ai.UserMessage).content
                val blocks = userContent.mapNotNull { block ->
                    when (block) {
                        is TextContent ->
                            if (block.text.trim().isNotEmpty()) textBlock(block.text) else null
                        is ImageContent -> imageBlock(block)
                        else -> null
                    }
                }
                if (blocks.isNotEmpty()) {
                    params.add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(blocks))
                        },
                    )
                }
            }
            works.resolve.pathfinder.ai.MessageRole.ASSISTANT -> {
                val assistant = msg as works.resolve.pathfinder.ai.AssistantMessage
                val blocks = mutableListOf<JsonObject>()
                for (block in assistant.content) {
                    when (block) {
                        is TextContent -> if (block.text.trim().isNotEmpty()) blocks.add(textBlock(block.text))
                        is ThinkingContent -> {
                            // Redacted thinking: pass the opaque payload back.
                            if (block.redacted) {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", "redacted_thinking")
                                        put("data", block.thinkingSignature ?: "")
                                    },
                                )
                                continue
                            }
                            val signature = block.thinkingSignature
                            val hasSignature = !signature.isNullOrEmpty() && signature.trim().isNotEmpty()
                            if (block.thinking.trim().isEmpty() && !hasSignature) continue
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
                                    },
                                )
                            } else {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", "thinking")
                                        put("thinking", sanitizeSurrogates(block.thinking))
                                        put("signature", signature)
                                    },
                                )
                            }
                        }
                        is ToolCall -> blocks.add(
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", block.id)
                                put("name", if (isOAuthToken) toClaudeCodeName(block.name) else block.name)
                                put(
                                    "input",
                                    parseOrEmptyObject(block.arguments),
                                )
                            },
                        )
                        else -> {}
                    }
                }
                if (blocks.isNotEmpty()) {
                    val messageIndex = params.size
                    params.add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", JsonArray(blocks))
                        },
                    )
                    if (
                        managedProvider != null &&
                        assistant.api == "anthropic-messages" &&
                        assistant.provider == managedProvider &&
                        isAnthropicEffort(assistant.providerThinkingLevel)
                    ) {
                        assistantLevels[messageIndex] =
                            AnthropicEffort.valueOf(assistant.providerThinkingLevel!!.uppercase())
                    }
                }
            }
            works.resolve.pathfinder.ai.MessageRole.TOOL_RESULT -> {
                // Collect all consecutive toolResult messages, needed for z.ai Anthropic endpoint.
                val toolResults = mutableListOf<JsonObject>()
                val siblingContent = mutableListOf<JsonObject>()
                var j = i
                while (j < transformedMessages.size &&
                    transformedMessages[j].role == works.resolve.pathfinder.ai.MessageRole.TOOL_RESULT
                ) {
                    val (toolResult, siblings) = convertToolResult(
                        transformedMessages[j] as ToolResultMessage,
                        isOAuthToken,
                        deferredToolNames,
                        loadedToolNames,
                        normalizeToolName,
                    )
                    toolResults.add(toolResult)
                    siblingContent.addAll(siblings)
                    j++
                }
                i = j - 1
                // Displaced reference-bearing content must follow every tool_result block.
                params.add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", JsonArray(toolResults + siblingContent))
                    },
                )
            }
        }
        i++
    }

    // Prompt caching: the marker on the last user message caches the
    // conversation history.
    if (cacheControl != null && params.isNotEmpty()) {
        val lastMessage = params.last()
        if (lastMessage.str("role") == "user") {
            val content = lastMessage["content"]
            val contentValue = when {
                content is JsonArray && content.isNotEmpty() -> {
                    val lastBlock = content.last() as? JsonObject
                    val type = lastBlock.str("type")
                    if (lastBlock != null && (type == "text" || type == "image" || type == "tool_result")) {
                        JsonArray(content.dropLast(1) + lastBlock.toMutableMap().apply {
                            put("cache_control", cacheControl)
                        }.let { JsonObject(it) })
                    } else {
                        content
                    }
                }
                content is JsonPrimitive -> JsonArray(
                    listOf(textBlock(content.content, cacheControl)),
                )
                else -> content ?: JsonNull
            }
            params[params.size - 1] = buildJsonObject {
                put("role", "user")
                put("content", contentValue)
            }
        }
    }

    return ConvertedAnthropicMessages(params, assistantLevels)
}

private fun parseOrEmptyObject(arguments: String): JsonObject {
    if (arguments.isBlank()) return JsonObject(emptyMap())
    return try {
        val parsed = lenientJson.parseToJsonElement(arguments)
        parsed as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}

internal fun convertTools(
    tools: List<Tool>,
    isOAuthToken: Boolean,
    supportsEagerToolInputStreaming: Boolean,
    supportsStrictTools: Boolean,
    cacheControl: JsonObject?,
    deferLoading: Boolean = false,
): List<JsonObject> = tools.mapIndexed { index, tool ->
    val strict = resolveJsonSchemaStrictSampling(tool, supportsStrictTools)
    val parameters = getJsonSchemaToolParameters(tool, strict)
    val schema = parameters as? JsonObject ?: JsonObject(emptyMap())
    val legacyInputSchema = buildJsonObject {
        put("type", "object")
        put("properties", schema["properties"] ?: JsonObject(emptyMap()))
        put("required", schema["required"] ?: JsonArray(emptyList()))
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
        if (deferLoading) put("defer_loading", true)
        if (cacheControl != null && index == tools.size - 1) put("cache_control", cacheControl)
    }
}

/**
 * Accumulates the streamed response. Blocks are keyed by the upstream
 * `index` field; events interleave freely.
 */
internal class AnthropicStreamState(
    private val model: Model,
    private val timestampMs: Long,
    private val isOAuth: Boolean = false,
    /** Managed-effort models record the active effort on every response. */
    private val providerThinkingLevel: String? = null,
) {
    private sealed interface Block {
        val streamIndex: Int
    }

    private class Text(override val streamIndex: Int) : Block {
        val text = StringBuilder()
    }

    private class Thinking(override val streamIndex: Int, val redacted: Boolean) : Block {
        val thinking = StringBuilder()
        var signature = ""
    }

    private class Tool(override val streamIndex: Int) : Block {
        var id = ""
        var name = ""
        /** pi seeds `arguments` from content_block_start input; kept as raw JSON here. */
        var seedJson: String? = null
        val partialJson = StringBuilder()
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
        val message = event.obj("message") ?: return emptyList()
        responseId = message["id"].strOrNull() ?: responseId
        responseModel = message["model"].strOrNull()
        (message["input_transformations"] as? JsonArray)?.let { inputTransformations = it }
        if (responseModel != null && responseModel != model.id) {
            val fallbackCost = model.anthropicCompat.allowedFallbackModels
                .find { it.provider == model.provider && it.model == responseModel }?.cost
            if (fallbackCost != null) {
                usageModel = model.copy(id = responseModel!!, cost = fallbackCost)
            }
        }
        // Capture initial token usage so an early abort still has input counts.
        val messageUsage = message.obj("usage")
        if (messageUsage != null) {
            usage = usage.copy(
                input = messageUsage.int("input_tokens") ?: 0,
                output = messageUsage.int("output_tokens") ?: 0,
                cacheRead = messageUsage.int("cache_read_input_tokens") ?: 0,
                cacheWrite = messageUsage.int("cache_creation_input_tokens") ?: 0,
                cacheWrite1h = messageUsage.obj("cache_creation")
                    ?.int("ephemeral_1h_input_tokens") ?: 0,
            )
            usage = withTotal(usage)
        }
        return emptyList()
    }

    fun onContentBlockStart(
        event: JsonObject,
        context: Context,
    ): List<AssistantMessageEvent> {
        val index = event.int("index") ?: return emptyList()
        val contentBlock = event.obj("content_block") ?: return emptyList()
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
                if (isOAuth) blockName = fromClaudeCodeName(blockName, context.tools)
                name = blockName
                (contentBlock.obj("input"))?.let { seedJson = it.toString() }
            }
            // A pre-output fallback marker is expected; once output has begun
            // it means the server swapped models mid-response.
            "fallback" -> {
                if (blocks.isNotEmpty()) {
                    throw ProviderStreamException("Anthropic performed an unsupported mid-output model fallback")
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
            },
        )
    }

    fun onContentBlockDelta(event: JsonObject): List<AssistantMessageEvent> {
        val index = event.int("index") ?: return emptyList()
        val delta = event.obj("delta") ?: return emptyList()
        val blockIndex = byStreamIndex[index] ?: return emptyList()
        return when (val deltaType = delta.str("type")) {
            "text_delta" -> {
                val text = (blocks[blockIndex] as? Text) ?: return emptyList()
                val value = delta["text"].strOrNull() ?: ""
                text.text.append(value)
                listOf(AssistantMessageEvent.TextDelta(blockIndex, value, snapshot()))
            }
            "thinking_delta" -> {
                val thinking = (blocks[blockIndex] as? Thinking) ?: return emptyList()
                val value = delta["thinking"].strOrNull() ?: ""
                thinking.thinking.append(value)
                listOf(AssistantMessageEvent.ThinkingDelta(blockIndex, value, snapshot()))
            }
            "input_json_delta" -> {
                val tool = (blocks[blockIndex] as? Tool) ?: return emptyList()
                val value = delta["partial_json"].strOrNull() ?: ""
                tool.partialJson.append(value)
                listOf(AssistantMessageEvent.ToolCallDelta(blockIndex, value, snapshot()))
            }
            "signature_delta" -> {
                val thinking = (blocks[blockIndex] as? Thinking) ?: return emptyList()
                thinking.signature += delta["signature"].strOrNull() ?: ""
                emptyList()
            }
            else -> emptyList()
        }
    }

    fun onContentBlockStop(event: JsonObject): List<AssistantMessageEvent> {
        val index = event.int("index") ?: return emptyList()
        val blockIndex = byStreamIndex[index] ?: return emptyList()
        return when (val block = blocks[blockIndex]) {
            is Text -> listOf(AssistantMessageEvent.TextEnd(blockIndex, block.text.toString(), snapshot()))
            is Thinking -> listOf(
                AssistantMessageEvent.ThinkingEnd(blockIndex, block.thinking.toString(), snapshot()),
            )
            is Tool -> listOf(
                AssistantMessageEvent.ToolCallEnd(blockIndex, toolCallOf(block), snapshot()),
            )
        }
    }

    fun onMessageDelta(event: JsonObject, model: Model): List<AssistantMessageEvent> {
        (event["input_transformations"] as? JsonArray)?.let { inputTransformations = it }
        val delta = event.obj("delta")
        if (delta != null) {
            val stopReason = delta.str("stop_reason")
            if (stopReason != null) {
                rawStopReason = stopReason
                val (mapped, error) = mapStopReason(
                    stopReason,
                    delta.obj("stop_details")?.get("explanation").strOrNull(),
                )
                this.stopReason = mapped
                errorMessage = error ?: errorMessage
            }
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
                reasoning = eventUsage.obj("output_tokens_details")
                    ?.int("thinking_tokens") ?: usage.reasoning,
            )
            usage = withTotal(usage)
        }
        return emptyList()
    }

    fun onMessageStop(): List<AssistantMessageEvent> {
        sawMessageStop = true
        return emptyList()
    }

    private fun mapStopReason(reason: String, refusalExplanation: String?): Pair<StopReason, String?> =
        when (reason) {
            "end_turn" -> StopReason.STOP to null
            "max_tokens" -> StopReason.LENGTH to null
            "tool_use" -> StopReason.TOOL_USE to null
            "refusal" -> StopReason.ERROR to
                (refusalExplanation ?: "The model refused to complete the request")
            "pause_turn" -> StopReason.STOP to null
            "stop_sequence" -> StopReason.STOP to null
            "sensitive" -> StopReason.ERROR to "Provider stopped with: sensitive"
            else -> throw ProviderStreamException("Unhandled stop reason: $reason")
        }

    private fun toolCallOf(block: Tool): ToolCall = ToolCall(
        id = block.id,
        name = block.name,
        // Unlike pi, the streamed JSON is not parsed at stop; the raw string
        // (seed or "{}" when blank) preserves partial snapshots.
        arguments = block.partialJson.toString().ifBlank { block.seedJson ?: "{}" },
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
                                (transformation as? JsonObject)?.get("type").strOrNull()?.let { put("type", it) }
                                (transformation as? JsonObject)?.get("path").strOrNull()?.let { put("path", it) }
                                (transformation as? JsonObject)?.get("reason").strOrNull()?.let { put("reason", it) }
                            }
                        },
                    ),
                )
            },
        )
    }

    fun snapshot(): AssistantMessage = AssistantMessage(
        content = blocks.map { block ->
            when (block) {
                is Text -> TextContent(block.text.toString())
                is Thinking -> ThinkingContent(
                    block.thinking.toString(),
                    block.signature.ifEmpty { null },
                    block.redacted,
                )
                is Tool -> toolCallOf(block)
            }
        },
        api = model.api,
        provider = model.provider,
        model = responseModel ?: model.id,
        usage = usage,
        stopReason = stopReason,
        errorMessage = errorMessage,
        rawStopReason = rawStopReason,
        responseId = responseId,
        responseModel = responseModel,
        providerThinkingLevel = providerThinkingLevel,
        timestamp = timestampMs,
    )
}
