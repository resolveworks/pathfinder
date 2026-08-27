package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.core.ThinkingLevelMap
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolChoice
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.calculateCost
import works.resolve.pathfinder.ai.core.clampThinkingLevel
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.NetworkException
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.utils.MAX_PROVIDER_ERROR_BODY_CHARS
import works.resolve.pathfinder.ai.utils.truncateErrorText
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.clampMaxTokensToContext
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** pi's MistralReasoningEffort. */
typealias MistralReasoningEffort = String // "none" | "high"

/** pi's MistralOptions.promptMode; only "reasoning" exists. */
enum class MistralPromptMode(val wireName: String) { REASONING("reasoning") }

/**
 * Provider-specific options for the Mistral API, ported from pi's
 * MistralOptions (an extension of StreamOptions).
 */
data class MistralOptions(
    /** Explicit API key; never included in toString(). */
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = works.resolve.pathfinder.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    /** Explicit request headers; a null value removes the header (pi semantics). */
    val headers: Map<String, String?> = emptyMap(),
    val toolChoice: ToolChoice? = null,
    val promptMode: MistralPromptMode? = null,
    val reasoningEffort: MistralReasoningEffort? = null,
    val cacheRetention: CacheRetention? = null,
) {
    override fun toString(): String =
        "MistralOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", toolChoice=$toolChoice, promptMode=$promptMode, reasoningEffort=<set>" +
            ", cacheRetention=$cacheRetention, timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs, env=${env.keys}, headers=${headers.keys})"
}

/**
 * Native Mistral Chat Completions streaming adapter, ported from pi's
 * `src/api/mistral-conversations.ts`: wire payload, header/x-affinity
 * handling, native thinking/text/tool-call chunk parsing with pi's block
 * event ordering, cached-token usage accounting with cost, raw finish reasons,
 * and provider error formatting.
 *
 * Divergences from pi, at the narrowest boundary:
 * - Abort: pi maps an aborted `signal` to a terminal error event with
 *   stopReason "aborted"; here coroutine cancellation propagates normally and
 *   produces no error event, per this codebase's stream contract.
 * - onPayload/onResponse hooks are omitted; tests observe the transport.
 * - pi applies `AbortSignal.timeout(options?.timeoutMs ?? 60_000)` here; this
 *   port forwards the same 60s default to the transport as the per-call
 *   timeout when `timeoutMs` is unset.
 *
 * Retry divergence: pi's `requestMistralStream` uses a raw `fetch` with no
 * retry wrapper — Mistral effectively ignores `maxRetries`. This port is
 * aligned: `transport.post` is called directly, so retryable transport
 * errors surface immediately even when `maxRetries > 0`.
 */
class MistralConversationsApi(
    private val transport: HttpStreamingTransport,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ChatApi {

    private class DoneSentinel : RuntimeException()

    fun stream(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
    ): Flow<AssistantMessageEvent> {
        val useReasoning = model.reasoning && options.reasoningEffort != null
        return stream(
            model,
            context,
            MistralOptions(
                apiKey = options.apiKey,
                sessionId = options.sessionId,
                temperature = options.temperature,
                maxTokens = options.maxTokens,
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
                env = options.env,
                headers = options.headers,
                promptMode = if (useReasoning && usesPromptModeReasoning(model)) {
                    MistralPromptMode.REASONING
                } else {
                    null
                },
                reasoningEffort = if (useReasoning && usesReasoningEffort(model)) {
                    mapReasoningEffort(model, options.reasoningEffort!!)
                } else {
                    null
                },
            ),
        )
    }

    /**
     * Streams with native Mistral options, pi's `stream` entry point. The
     * payload is built first, so API-key failures surface before any request.
     */
    fun stream(
        model: Model,
        context: Context,
        options: MistralOptions,
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = nowMs()
        val state = MistralStreamingState(model, startedAtMs)
        try {
            val apiKey = options.apiKey
                ?: throw IllegalStateException("No API key for provider: ${model.provider}")

            val normalizer = MistralToolCallIdNormalizer()
            val transformedMessages = transformMessages(context.messages, model) { id, _ -> normalizer.normalize(id) }
            var wireMessages = MistralConversationsPayload.toChatMessages(
                transformedMessages,
                model.input.contains(InputModality.IMAGE),
            )
            if (!context.systemPrompt.isNullOrEmpty()) {
                wireMessages = listOf(
                    works.resolve.pathfinder.ai.api.buildMistralSystemMessage(context.systemPrompt),
                ) + wireMessages
            }
            val payload = MistralConversationsPayload.buildRequestBody(model, context, wireMessages, options)

            val url = model.baseUrl.trimEnd('/') + "/v1/chat/completions"
            val (bearerToken, headers) = buildMistralHeaders(model, apiKey, options)
            val request = TransportRequest(
                url = url,
                bearerToken = bearerToken,
                headers = headers,
                body = payload.toString().toByteArray(Charsets.UTF_8),
                // pi: AbortSignal.timeout(options?.timeoutMs ?? 60_000)
                timeoutMs = options.timeoutMs ?: DEFAULT_TIMEOUT_MS,
            )

            val response = transport.post(request)

            emit(AssistantMessageEvent.Start(state.snapshot()))

            try {
                response.events.collect { event ->
                    processSseEvent(event, model, state).forEach { emit(it) }
                    if (state.done) throw DoneSentinel()
                }
            } catch (_: DoneSentinel) {
                // Stop consuming promptly after [DONE]; cancelling the
                // collector closes the transport call.
            }

            state.finishOpenBlocks().forEach { emit(it) }

            if (state.stopReason == StopReason.PENDING) {
                throw ProviderStreamException("Mistral stream ended without a finish reason")
            }
            if (state.stopReason == StopReason.ERROR) {
                throw ProviderStreamException(state.errorMessage ?: "An unknown error occurred")
            }

            emit(AssistantMessageEvent.Done(state.stopReason, state.snapshot()))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val finalMessage = state.snapshot().copy(
                stopReason = StopReason.ERROR,
                errorMessage = formatMistralError(error),
            )
            emit(AssistantMessageEvent.Error(finalMessage.stopReason, finalMessage))
        }
    }

    /**
     * Maps provider-agnostic [SimpleStreamOptions] to Mistral options, pi's
     * `streamSimple`: clamps thinking level, selects prompt_mode vs
     * reasoning_effort per model, clamps max tokens against the context, and
     * forwards the tool choice.
     */
    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<AssistantMessageEvent> {
        val apiKey = options.apiKey
            ?: throw IllegalStateException("No API key for provider: ${model.provider}")

        val clamped = options.reasoning?.let { clampThinkingLevel(model, toModelThinkingLevel(it)) }
        val reasoning = if (clamped == ModelThinkingLevel.OFF) null else clamped
        val useReasoning = model.reasoning && reasoning != null
        val maxTokens = clampMaxTokensToContext(model, context, options.maxTokens ?: model.maxTokens)

        return stream(
            model,
            context,
            MistralOptions(
                apiKey = apiKey,
                sessionId = options.sessionId,
                temperature = options.temperature,
                maxTokens = maxTokens,
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
                env = options.env,
                headers = options.headers,
                toolChoice = options.toolChoice,
                cacheRetention = options.cacheRetention,
                promptMode = if (useReasoning && usesPromptModeReasoning(model)) MistralPromptMode.REASONING else null,
                reasoningEffort = if (useReasoning && usesReasoningEffort(model)) {
                    mapReasoningEffort(model, reasoning!!)
                } else {
                    null
                },
            ),
        )
    }

    /** Parses one complete SSE data payload; null means the stream is done. */
    private fun processSseEvent(
        event: SseEvent,
        model: Model,
        state: MistralStreamingState,
    ): List<AssistantMessageEvent> {
        if (event.data.trim() == DONE) {
            state.markDone()
            return emptyList()
        }
        val chunk = try {
            json.parseToJsonElement(event.data)
        } catch (error: Exception) {
            throw ProviderStreamException(
                "Invalid Mistral streaming event: ${error.message ?: error::class.simpleName}",
            )
        }
        if (chunk !is JsonObject || chunk["choices"] !is JsonArray) {
            throw ProviderStreamException("Invalid Mistral streaming event")
        }
        val data = chunk

        data["id"].stringOrNull()?.takeIf { it.isNotEmpty() && state.responseId == null }
            ?.let { state.responseId = it }

        (data["usage"] as? JsonObject)?.let { state.usage = parseChunkUsage(it, model) }

        val choice = (data["choices"] as JsonArray).firstOrNull() as? JsonObject ?: return emptyList()

        choice["finish_reason"].stringOrNull()?.let { raw ->
            state.rawStopReason = raw
            val (stopReason, errorMessage) = mapChatStopReason(raw)
            state.stopReason = stopReason
            if (errorMessage != null) state.errorMessage = errorMessage
        }

        val events = mutableListOf<AssistantMessageEvent>()
        val delta = choice["delta"] as? JsonObject

        if (delta != null) {
            when (val content = delta["content"]) {
                is JsonPrimitive -> if (content != JsonNull) {
                    events += state.appendText(content.content)
                }
                is JsonArray -> for (item in content) {
                    val obj = item as? JsonObject ?: continue
                    when (obj["type"].stringOrNull()) {
                        "thinking" -> {
                            val deltaText = (obj["thinking"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonObject)?.get("text").stringOrNull() }
                                ?.filter { it.isNotEmpty() }
                                ?.joinToString("")
                                ?: ""
                            if (deltaText.isNotEmpty()) events += state.appendThinking(deltaText)
                        }
                        "text" -> events += state.appendText(obj["text"].stringOrNull() ?: "")
                    }
                }
                else -> Unit
            }

            (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.let { events += state.appendToolCallDelta(it) }
            }
        }
        return events
    }

    /** pi's usage handling: cached tokens reduce the input count; writes are always 0. */
    private fun parseChunkUsage(raw: JsonObject, model: Model): Usage {
        val promptTokens = raw.intOrZero("prompt_tokens")
        val cachedPromptTokens = cachedPromptTokens(raw, promptTokens)
        val input = maxOf(0, promptTokens - cachedPromptTokens)
        val output = raw.intOrZero("completion_tokens")
        // pi: total_tokens || input+output+cacheRead (0 falls back to the sum)
        val totalTokens = raw.intOrZero("total_tokens").takeIf { it != 0 }
            ?: (input + output + cachedPromptTokens)
        val usage = Usage(
            input = input,
            output = output,
            cacheRead = cachedPromptTokens,
            cacheWrite = 0,
            totalTokens = totalTokens,
        )
        return usage.copy(cost = calculateCost(model, usage))
    }

    /** pi's getMistralCachedPromptTokens: several provider spellings, clamped to prompt tokens. */
    private fun cachedPromptTokens(raw: JsonObject, promptTokens: Int): Int {
        val rawCached = raw.obj("promptTokensDetails")?.intOrNull("cachedTokens")
            ?: raw.obj("prompt_tokens_details")?.intOrNull("cached_tokens")
            ?: raw.obj("promptTokenDetails")?.intOrNull("cachedTokens")
            ?: raw.obj("prompt_token_details")?.intOrNull("cached_tokens")
            ?: raw.intOrNull("numCachedTokens")
            ?: raw.intOrNull("num_cached_tokens")
            ?: 0
        return minOf(promptTokens, maxOf(0, rawCached))
    }

    /** pi's mapChatStopReason. */
    internal fun mapChatStopReason(reason: String): Pair<StopReason, String?> = when (reason) {
        "stop" -> StopReason.STOP to null
        "length", "model_length" -> StopReason.LENGTH to null
        "tool_calls" -> StopReason.TOOL_USE to null
        "error" -> StopReason.ERROR to "Provider stopped with: error"
        else -> StopReason.ERROR to "Provider stopped with: $reason"
    }

    /**
     * pi's formatMistralError (mistral-conversations.ts:261-272): a Mistral-specific
     * formatter, kept as such upstream too — pi composes the body/message itself
     * instead of going through formatProviderError. Upstream also duplicates
     * `truncateErrorText` and `MAX_PROVIDER_ERROR_BODY_CHARS` inline
     * (mistral-conversations.ts:274-278, 257); the port consolidates them into the
     * shared utils/ErrorBody.kt.
     */
    internal fun formatMistralError(error: Exception): String = when (error) {
        is ProviderHttpException -> {
            val bodyText = error.body.trim()
            if (bodyText.isNotEmpty()) {
                "Mistral API error (${error.status}): ${truncateErrorText(bodyText, MAX_PROVIDER_ERROR_BODY_CHARS)}"
            } else {
                "Mistral API error (${error.status}): ${error.message}"
            }
        }
        is NetworkException ->
            // The cause carries the platform failure (e.g. "timeout").
            error.cause?.message ?: error.message ?: "Network request failed"
        else -> error.message ?: error::class.simpleName ?: "Unknown error"
    }


    /**
     * pi's buildMistralHeaders: Accept + Authorization (via the transport's
     * bearer token unless explicitly overridden) + model headers, then request
     * headers (null removes), then x-affinity from the session id when prompt
     * caching is active and not explicitly overridden.
     *
     * The User-Agent is pi's getPiUserAgent() (ai/utils/PiUserAgent.kt);
     * only its platform-string details diverge.
     */
    private fun buildMistralHeaders(
        model: Model,
        apiKey: String,
        options: MistralOptions,
    ): Pair<String?, Map<String, String>> {
        val headers = LinkedHashMap<String, String?>()
        headers["User-Agent"] = getPiUserAgent()
        headers["accept"] = "text/event-stream"
        applyOverrides(headers, model.headers)
        applyOverrides(headers, options.headers)

        val authOverride = options.headers.entries.firstOrNull { it.key.lowercase() == "authorization" }
        // An explicit Authorization header (request or model) replaces the
        // derived Bearer token; a null request override removes it entirely.
        val explicitAuth = authOverride?.value?.let { authOverride.key to it }
            ?: if (authOverride == null) {
                model.headers.entries.firstOrNull { it.key.lowercase() == "authorization" }
                    ?.let { it.key to it.value }
            } else {
                null
            }
        if (explicitAuth != null) {
            headers.remove(explicitAuth.first)
        }

        val hasExplicitAffinity = hasOverride(model.headers, "x-affinity") || hasOverride(options.headers, "x-affinity")
        if (MistralConversationsPayload.shouldUsePromptCaching(options) && !hasExplicitAffinity) {
            headers["x-affinity"] = options.sessionId
        }

        val headerMap = headers.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
        if (explicitAuth != null) {
            headerMap[explicitAuth.first] = explicitAuth.second
        }
        return when {
            explicitAuth != null -> null
            authOverride != null -> null // removed entirely; no derived bearer either
            else -> apiKey
        } to headerMap
    }

    private fun applyOverrides(headers: LinkedHashMap<String, String?>, overrides: Map<String, String?>) {
        if (overrides.isEmpty()) return
        for ((name, value) in overrides) {
            val lowerName = name.lowercase()
            headers.keys.filter { it.lowercase() == lowerName }.forEach { headers.remove(it) }
            if (value != null) headers[name] = value else headers.remove(name)
        }
    }

    private fun hasOverride(overrides: Map<String, String?>, target: String): Boolean =
        overrides.keys.any { it.lowercase() == target }

    private companion object {
        const val DONE = "[DONE]"
        /** pi's AbortSignal.timeout default (mistral-conversations.ts). */
        const val DEFAULT_TIMEOUT_MS = 60_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal fun buildMistralSystemMessage(systemPrompt: String): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("role", "system")
        put("content", sanitizeSurrogates(systemPrompt))
    }

private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.intOrZero(key: String): Int = intOrNull(key) ?: 0

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull?.toInt()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** pi's usesReasoningEffort model list. */
internal fun usesReasoningEffort(model: Model): Boolean =
    model.id == "mistral-small-2603" || model.id == "mistral-small-latest" || model.id == "mistral-medium-3.5"

internal fun usesPromptModeReasoning(model: Model): Boolean = model.reasoning && !usesReasoningEffort(model)

/** pi's mapReasoningEffort: thinkingLevelMap entry, defaulting to "high". */
internal fun mapReasoningEffort(model: Model, level: ModelThinkingLevel): MistralReasoningEffort =
    model.thinkingLevelMap?.forLevel(level) ?: "high"


/**
 * Accumulates streamed Mistral content with pi's exact block semantics: one
 * open text/thinking block at a time, closed when a block of another type (or
 * a tool call) starts, tool blocks keyed by `id:index`, and toolcall_end
 * events emitted (in first-seen order) after the final text/thinking block
 * closes.
 */
internal class MistralStreamingState(private val model: Model, private val timestampMs: Long) {
    private sealed interface Block {
        data class Text(var text: String) : Block
        data class Thinking(var thinking: String) : Block
        data class Tool(
            var id: String,
            var name: String,
            val arguments: StringBuilder,
        ) : Block
    }

    private val blocks = mutableListOf<Block>()
    private var currentBlockIndex = -1 // index of the open text/thinking block, or -1
    private val toolBlocksByKey = LinkedHashMap<String, Int>()

    var usage = works.resolve.pathfinder.ai.core.Usage()

    var stopReason = StopReason.PENDING
    var errorMessage: String? = null
    var rawStopReason: String? = null
    var responseId: String? = null
    var done = false
        private set

    fun markDone() {
        done = true
    }

    private fun closeCurrentBlock(): List<AssistantMessageEvent> {
        val index = currentBlockIndex
        if (index == -1) return emptyList()
        currentBlockIndex = -1
        return when (val block = blocks[index]) {
            is Block.Text -> listOf(AssistantMessageEvent.TextEnd(index, block.text, snapshot()))
            is Block.Thinking -> listOf(AssistantMessageEvent.ThinkingEnd(index, block.thinking, snapshot()))
            is Block.Tool -> emptyList()
        }
    }

    fun appendText(delta: String): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        val current = currentBlockIndex.takeIf { it >= 0 }?.let { blocks[it] }
        if (current !is Block.Text) {
            events += closeCurrentBlock()
            currentBlockIndex = blocks.size
            blocks.add(Block.Text(""))
            events.add(AssistantMessageEvent.TextStart(currentBlockIndex, snapshot()))
        }
        val textBlock = blocks[currentBlockIndex] as Block.Text
        textBlock.text += delta
        events.add(AssistantMessageEvent.TextDelta(currentBlockIndex, delta, snapshot()))
        return events
    }

    fun appendThinking(delta: String): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        val current = currentBlockIndex.takeIf { it >= 0 }?.let { blocks[it] }
        if (current !is Block.Thinking) {
            events += closeCurrentBlock()
            currentBlockIndex = blocks.size
            blocks.add(Block.Thinking(""))
            events.add(AssistantMessageEvent.ThinkingStart(currentBlockIndex, snapshot()))
        }
        val thinkingBlock = blocks[currentBlockIndex] as Block.Thinking
        thinkingBlock.thinking += delta
        events.add(AssistantMessageEvent.ThinkingDelta(currentBlockIndex, delta, snapshot()))
        return events
    }

    fun appendToolCallDelta(toolCall: JsonObject): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        events += closeCurrentBlock()

        val id = toolCall["id"].stringOrNull()
        val index = toolCall["index"]?.let { (it as? JsonPrimitive)?.longOrNull?.toInt() } ?: 0
        val callId = if (!id.isNullOrEmpty() && id != "null") {
            id
        } else {
            deriveMistralToolCallId("toolcall:$index", 0)
        }
        val key = "$callId:$index"
        val function = toolCall["function"] as? JsonObject
        val name = function?.get("name").stringOrNull() ?: ""

        var blockIndex = toolBlocksByKey[key]
        if (blockIndex == null) {
            blockIndex = blocks.size
            blocks.add(Block.Tool(callId, name, StringBuilder()))
            toolBlocksByKey[key] = blockIndex
            events.add(AssistantMessageEvent.ToolCallStart(blockIndex, snapshot()))
        }

        val toolBlock = blocks[blockIndex] as Block.Tool
        if (toolBlock.id.isEmpty()) toolBlock.id = callId
        if (name.isNotEmpty() && toolBlock.name.isEmpty()) toolBlock.name = name
        val argsDelta = function?.get("arguments")?.let { arg ->
            when (arg) {
                is JsonPrimitive -> arg.content
                else -> arg.toString() // pi JSON.stringify(.... || {})
            }
        } ?: ""
        toolBlock.arguments.append(argsDelta)

        events.add(AssistantMessageEvent.ToolCallDelta(blockIndex, argsDelta, snapshot()))
        return events
    }

    /** pi's stream-end sequence: close the open block, then end each tool call. */
    fun finishOpenBlocks(): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        events += closeCurrentBlock()
        for (index in toolBlocksByKey.values) {
            val block = blocks[index]
            if (block is Block.Tool) {
                events.add(
                    AssistantMessageEvent.ToolCallEnd(
                        index,
                        ToolCall(id = block.id, name = block.name, arguments = block.arguments.toString()),
                        snapshot(),
                    ),
                )
            }
        }
        return events
    }

    fun snapshot(): AssistantMessage = AssistantMessage(
        content = blocks.map { block ->
            when (block) {
                is Block.Text -> TextContent(block.text)
                is Block.Thinking -> ThinkingContent(block.thinking)
                is Block.Tool -> ToolCall(block.id, block.name, block.arguments.toString())
            }
        },
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = usage,
        stopReason = stopReason,
        errorMessage = errorMessage,
        rawStopReason = rawStopReason,
        responseId = responseId,
        timestamp = timestampMs,
    )
}
