package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.ProviderResponse
import works.resolve.pathfinder.ai.core.headersToRecord
import works.resolve.pathfinder.ai.core.toToolChoice
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.anthropicCompatOf
import works.resolve.pathfinder.ai.core.calculateCost
import works.resolve.pathfinder.ai.core.hasHeader
import works.resolve.pathfinder.ai.core.mergeHeaders
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strOrNull
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.clampMaxTokensToContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

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
 *   (see OpenAiCompletionsApi / Events.kt).
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
        val state = AnthropicStreamState(model, startedAtMs, isOAuth)
        try {
            assertRequestAuth(model.provider, options.apiKey, options.headers)

            val retention = resolveCacheRetention(options.cacheRetention, options.env)
            val cacheSessionId = if (retention == CacheRetention.NONE) null else options.sessionId

            val (headers, bearerToken) = buildHeaders(model, isOAuth, options, context, cacheSessionId)
            var params = buildRequestBody(model, context, isOAuth, options)
            options.onPayload?.let { hook -> hook(params, model)?.let { params = it } }
            val body = params
                .toString()
                .toByteArray(Charsets.UTF_8)

            val url = model.baseUrl.trimEnd('/') + "/v1/messages"
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

            emit(AssistantMessageEvent.Done(state.stopReason, state.snapshot()))
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

/** pi's pinned Anthropic SDK version default. */
internal const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Divergence (owner decision): pi unconditionally sends
 * `anthropic-dangerous-direct-browser-access: true`, a CORS-relaxation
 * header for browser clients; Pathfinder's OkHttp transport is not a browser
 * client, so the header is deliberately not sent.
 */
private fun buildHeaders(
    model: Model,
    isOAuth: Boolean,
    options: AnthropicMessagesOptions,
    context: Context,
    cacheSessionId: String?,
): Pair<Map<String, String>, String?> {
    val compat = anthropicCompatOf(model)

    // Adaptive thinking models have interleaved thinking built in.
    val needsInterleavedBeta = options.interleavedThinking && compat.forceAdaptiveThinking != true
    val betaFeatures = mutableListOf<String>()
    if (context.tools.isNotEmpty() && !compat.supportsEagerToolInputStreaming) {
        betaFeatures.add("fine-grained-tool-streaming-2025-05-14")
    }
    if (needsInterleavedBeta) {
        betaFeatures.add("interleaved-thinking-2025-05-14")
    }
    if (compat.allowedFallbackModels.isNotEmpty()) {
        betaFeatures.add("server-side-fallback-2026-07-01")
    }

    if (model.provider == "github-copilot") {
        val base = buildMap<String, String?> {
            put("accept", "application/json")
            put("anthropic-version", ANTHROPIC_VERSION)
            if (betaFeatures.isNotEmpty()) put("anthropic-beta", betaFeatures.joinToString(","))
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
        val headers = mergeHeaders(
            mapOf(
                "accept" to "application/json",
                "anthropic-version" to ANTHROPIC_VERSION,
                "anthropic-beta" to (listOf("claude-code-20250219", "oauth-2025-04-20") + betaFeatures)
                    .joinToString(","),
            ),
            mapOf(
                "user-agent" to "claude-cli/2.1.75",
                "x-app" to "cli",
            ),
        )
        return filterNonNull(
            mergeHeaders(
                mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), headers),
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
            if (betaFeatures.isNotEmpty()) put("anthropic-beta", betaFeatures.joinToString(","))
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

/**
 * Accumulates the streamed response. Blocks are keyed by the upstream
 * `index` field; events interleave freely.
 */
internal class AnthropicStreamState(
    private val model: Model,
    private val timestampMs: Long,
    private val isOAuth: Boolean = false,
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

    var sawMessageStart = false
        private set
    var sawMessageStop = false
        private set

    fun onMessageStart(event: JsonObject, model: Model): List<AssistantMessageEvent> {
        sawMessageStart = true
        val message = event.obj("message") ?: return emptyList()
        responseId = message["id"].strOrNull() ?: responseId
        responseModel = message["model"].strOrNull()
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
        timestamp = timestampMs,
    )
}
