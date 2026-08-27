package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
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
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.clampMaxTokensToContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Anthropic Messages streaming adapter, ported from pi's
 * packages/ai/src/api/anthropic-messages.ts.
 *
 * Covers: request building (see [AnthropicMessagesPayload]), API-key vs OAuth
 * bearer auth headers with beta negotiation, SSE event handling for
 * message_start / content_block_start / content_block_delta /
 * content_block_stop / message_delta / message_stop, text / thinking /
 * signature / tool-input deltas keyed by the upstream block index, OAuth
 * tool-name round-tripping through fromClaudeCodeName, seeding tool
 * arguments from content_block_start input, usage and
 * cost accounting from message_start plus message_delta, stop-reason mapping,
 * and stop/error semantics (stream must end with a stop reason after
 * message_stop).
 *
 * Documented divergences from pi:
 * - Abort semantics: pi's AbortSignal maps to coroutine cancellation here, and
 *   per this codebase's ported convention cancellation propagates without an
 *   Error event (see OpenAiCompletionsApi / Events.kt).
 * - pi parses SSE data with JSON repair (parseJsonWithRepair) and repairs
 *   streamed tool JSON (parseStreamingJson). Here a malformed data payload is
 *   a protocol error and tool arguments are accumulated as the raw JSON
 *   string, matching the ported ToolCall model; a blank buffer finalizes as
 *   `{}`.
 * - The User-Agent is pi's getPiUserAgent() (ai/utils/PiUserAgent.kt), merged
 *   first like pi's mergeClientHeaders; only its platform-string details
 *   diverge.
 * - github-copilot dynamic headers are ported
 *   (GithubCopilotHeaders.kt); deferred tool loading, server-side
 *   fallbacks, metadata, and strict tool sampling are not ported (no surface
 *   needs them).
 */
class AnthropicMessagesApi(
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ChatApi {

    /** pi's stream(model, context, options) for anthropic-messages. */
    fun stream(
        model: Model,
        context: Context,
        options: AnthropicMessagesOptions = AnthropicMessagesOptions(),
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = nowMs()
        // pi computes isOAuth from createClient; the Copilot branch (checked
        // first) always yields a non-OAuth, Bearer-auth client.
        val isOAuth = model.provider != "github-copilot" && options.apiKey?.let { isOAuthToken(it) } == true
        val state = AnthropicStreamState(model, startedAtMs, isOAuth)
        try {
            assertRequestAuth(model.provider, options.apiKey, options.headers)

            val retention = resolveCacheRetention(options.cacheRetention, options.env)
            val cacheSessionId = if (retention == CacheRetention.NONE) null else options.sessionId

            val (headers, bearerToken) = buildHeaders(model, isOAuth, options, context, cacheSessionId)
            val body = buildRequestBody(model, context, isOAuth, options)
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
     * pi's streamSimple for anthropic-messages: reasoning levels map to
     * adaptive effort (forceAdaptiveThinking models) or budget-based thinking
     * with the max-tokens/thinking-budget split from simple-options.ts.
     * Divergence from pi: pi throws synchronously on missing auth; failures
     * are encoded as a terminal Error event here, matching this codebase's
     * stream-error convention (see Events.kt).
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
            // pi's streamSimple: toolChoice: options?.toolChoice (anthropic-messages.ts:834)
            toolChoice = mapToolChoice(options.toolChoice),
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

    /**
     * Handles one complete SSE event; returns the block events to emit.
     * Mirrors pi's event loop including the event-name filter set.
     */
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
            json.parseToJsonElement(event.data)
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

    /** pi's assertRequestAuth. */
    private fun assertRequestAuth(provider: String, apiKey: String?, headers: Map<String, String?>) {
        if (apiKey != null) return
        if (hasHeader(headers, "authorization") ||
            hasHeader(headers, "x-api-key") ||
            hasHeader(headers, "cf-aig-authorization")
        ) {
            return
        }
        throw IllegalStateException("No API key for provider: $provider")
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
            timestamp = nowMs(),
        )
        return AssistantMessageEvent.Error(message.stopReason, message)
    }

    /**
     * Port of pi's anthropic-messages.ts catch block (~791). Upstream composes
     * the shared formatter over the SDK-folded `error.message` (which already
     * carries the body, so the message survives unchanged); the raw transport
     * body is the port's stand-in, so the output is the composed
     * `"<status>: <body>"` (no prefix upstream).
     */
    private fun formatProviderError(error: Exception): String = when (error) {
        is ProviderHttpException -> formatProviderError(normalizeProviderError(error))
        // Non-HTTP exceptions keep the port's `message ?: simpleName` handling;
        // pi's safeJsonStringify fallback for non-Error throws is moot in Kotlin.
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
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Anthropic Messages API version header value (pi's pinned SDK default). */
internal const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Builds the transport headers and auth, ported from pi's createClient.
 * GitHub Copilot models use Bearer auth with Copilot static/dynamic headers
 * and no x-api-key (pi's Copilot branch, checked before the OAuth branch);
 * API-key auth sends `x-api-key` (pi's SDK default header) plus
 * `anthropic-version`; OAuth tokens (sk-ant-oat) become a Bearer token with
 * Claude Code identity headers and betas.
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

    // Copilot: Bearer auth, selective betas (pi checks this branch before OAuth).
    if (model.provider == "github-copilot") {
        val base = buildMap<String, String?> {
            put("accept", "application/json")
            put("anthropic-dangerous-direct-browser-access", "true")
            put("anthropic-version", ANTHROPIC_VERSION)
            if (betaFeatures.isNotEmpty()) put("anthropic-beta", betaFeatures.joinToString(","))
        }
        val merged = filterNonNull(
            mergeHeaders(
                mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), base),
                // Copilot dynamic headers come after the model headers and
                // before the options headers, as in pi's Copilot branch of
                // anthropic-messages createClient mergeClientHeaders.
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
                "anthropic-dangerous-direct-browser-access" to "true",
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
            put("anthropic-dangerous-direct-browser-access", "true")
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

private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * Accumulates the streamed Anthropic response, pi's output/blocks state.
 * Blocks are keyed by the upstream `index` field; events interleave freely.
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

    var sawMessageStart = false
        private set
    var sawMessageStop = false
        private set

    fun onMessageStart(event: JsonObject, model: Model): List<AssistantMessageEvent> {
        sawMessageStart = true
        val message = event["message"] as? JsonObject ?: return emptyList()
        responseId = (message["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: responseId
        responseModel = (message["model"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        // Capture initial token usage so an early abort still has input counts.
        val messageUsage = message["usage"] as? JsonObject
        if (messageUsage != null) {
            usage = usage.copy(
                input = messageUsage.intOrZero("input_tokens"),
                output = messageUsage.intOrZero("output_tokens"),
                cacheRead = messageUsage.intOrZero("cache_read_input_tokens"),
                cacheWrite = messageUsage.intOrZero("cache_creation_input_tokens"),
                // pi anthropic-messages.ts:606 — cache_creation.ephemeral_1h_input_tokens || 0.
                cacheWrite1h = (messageUsage["cache_creation"] as? JsonObject)
                    ?.intOrZero("ephemeral_1h_input_tokens") ?: 0,
            )
            usage = withTotal(usage, model)
        }
        return emptyList()
    }

    fun onContentBlockStart(
        event: JsonObject,
        context: Context,
    ): List<AssistantMessageEvent> {
        val index = (event["index"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return emptyList()
        val contentBlock = event["content_block"] as? JsonObject ?: return emptyList()
        val type = (contentBlock["type"] as? JsonPrimitive)?.content
        val block: Block = when (type) {
            "text" -> Text(index).apply {
                contentBlock["text"].stringOrNull()?.let { text.append(it) }
            }
            "thinking" -> Thinking(index, redacted = false).apply {
                contentBlock["thinking"].stringOrNull()?.let { thinking.append(it) }
                contentBlock["signature"].stringOrNull()?.let { signature = it }
            }
            "redacted_thinking" -> Thinking(index, redacted = true).apply {
                thinking.append("[Reasoning redacted]")
                signature = contentBlock["data"].stringOrNull() ?: ""
            }
            "tool_use" -> Tool(index).apply {
                id = contentBlock["id"].stringOrNull() ?: ""
                var blockName = contentBlock["name"].stringOrNull() ?: ""
                if (isOAuth) blockName = fromClaudeCodeName(blockName, context.tools)
                name = blockName
                (contentBlock["input"] as? JsonObject)?.let { seedJson = it.toString() }
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
        val index = (event["index"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return emptyList()
        val delta = event["delta"] as? JsonObject ?: return emptyList()
        val blockIndex = byStreamIndex[index] ?: return emptyList()
        return when (val deltaType = (delta["type"] as? JsonPrimitive)?.content) {
            "text_delta" -> {
                val text = (blocks[blockIndex] as? Text) ?: return emptyList()
                val value = delta["text"].stringOrNull() ?: ""
                text.text.append(value)
                listOf(AssistantMessageEvent.TextDelta(blockIndex, value, snapshot()))
            }
            "thinking_delta" -> {
                val thinking = (blocks[blockIndex] as? Thinking) ?: return emptyList()
                val value = delta["thinking"].stringOrNull() ?: ""
                thinking.thinking.append(value)
                listOf(AssistantMessageEvent.ThinkingDelta(blockIndex, value, snapshot()))
            }
            "input_json_delta" -> {
                val tool = (blocks[blockIndex] as? Tool) ?: return emptyList()
                val value = delta["partial_json"].stringOrNull() ?: ""
                tool.partialJson.append(value)
                listOf(AssistantMessageEvent.ToolCallDelta(blockIndex, value, snapshot()))
            }
            "signature_delta" -> {
                val thinking = (blocks[blockIndex] as? Thinking) ?: return emptyList()
                thinking.signature += delta["signature"].stringOrNull() ?: ""
                emptyList()
            }
            else -> emptyList()
        }
    }

    fun onContentBlockStop(event: JsonObject): List<AssistantMessageEvent> {
        val index = (event["index"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return emptyList()
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
        val delta = event["delta"] as? JsonObject
        if (delta != null) {
            val stopReason = (delta["stop_reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (stopReason != null) {
                rawStopReason = stopReason
                val (mapped, error) = mapStopReason(
                    stopReason,
                    (delta["stop_details"] as? JsonObject)?.get("explanation").stringOrNull(),
                )
                this.stopReason = mapped
                errorMessage = error ?: errorMessage
            }
        }
        // Only update usage fields when present; preserves message_start values
        // when proxies omit them in message_delta.
        val eventUsage = event["usage"] as? JsonObject
        if (eventUsage != null) {
            usage = usage.copy(
                input = eventUsage.intOrNullField("input_tokens") ?: usage.input,
                output = eventUsage.intOrNullField("output_tokens") ?: usage.output,
                cacheRead = eventUsage.intOrNullField("cache_read_input_tokens") ?: usage.cacheRead,
                cacheWrite = eventUsage.intOrNullField("cache_creation_input_tokens") ?: usage.cacheWrite,
                reasoning = (eventUsage["output_tokens_details"] as? JsonObject)
                    ?.intOrNullField("thinking_tokens") ?: usage.reasoning,
            )
            usage = withTotal(usage, model)
        }
        return emptyList()
    }

    fun onMessageStop(): List<AssistantMessageEvent> {
        sawMessageStop = true
        return emptyList()
    }

    /** pi's mapStopReason. */
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
        // pi replaces the seed with parseStreamingJson(partialJson) at stop;
        // here the streamed JSON wins when present and the seed (or "{}" for
        // a blank buffer) stands in otherwise, preserving partial snapshots.
        arguments = block.partialJson.toString().ifBlank { block.seedJson ?: "{}" },
    )

    /** Anthropic doesn't provide total_tokens; compute from components, like pi. */
    private fun withTotal(usage: Usage, model: Model): Usage {
        val total = usage.input + usage.output + usage.cacheRead + usage.cacheWrite
        val withTotal = usage.copy(totalTokens = total)
        return withTotal.copy(cost = calculateCost(model, withTotal))
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

private fun JsonObject.intOrZero(key: String): Int = intOrNullField(key) ?: 0

private fun JsonObject.intOrNullField(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull?.toInt()
