package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.core.ProviderResponse
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolChoice
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.calculateCost
import works.resolve.pathfinder.ai.core.clampThinkingLevel
import works.resolve.pathfinder.ai.core.headersToRecord
import works.resolve.pathfinder.ai.core.toModelThinkingLevel
import works.resolve.pathfinder.ai.core.toToolChoice
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.NetworkException
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.utils.MAX_PROVIDER_ERROR_BODY_CHARS
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.clampMaxTokensToContext
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.long
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.shortHash
import works.resolve.pathfinder.ai.utils.strOrNull
import works.resolve.pathfinder.ai.utils.truncateErrorText
import works.resolve.pathfinder.telemetry.TelemetryContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

typealias MistralReasoningEffort = String // "none" | "high"

/** Only "reasoning" exists. */
enum class MistralPromptMode(val wire: String) { REASONING("reasoning") }

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
    /** Explicit request headers; a null value removes the header. */
    val headers: Map<String, String?> = emptyMap(),
    val toolChoice: ToolChoice? = null,
    val promptMode: MistralPromptMode? = null,
    val reasoningEffort: MistralReasoningEffort? = null,
    val cacheRetention: CacheRetention? = null,
    /**
     * Request hook that may return a replacement for the outgoing payload.
     * Divergence: it sees the snake_case wire object, where pi's hook sees
     * the internal payload before wire remapping. Receives full message
     * content; installers must not log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /**
     * Invoked after response headers arrive — including non-2xx, as in pi,
     * whose hook fires before the response.ok check. The transport here
     * throws for non-2xx, so the error path invokes it from the exception's
     * status/headers. Never included in toString().
     */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * Explicit parent telemetry context for this request. Dormant in this
     * port — carried for shape fidelity.
     */
    val telemetryContext: TelemetryContext? = null,
) {
    override fun toString(): String = optionsToString(
        "MistralOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "toolChoice" to toolChoice,
        "promptMode" to promptMode,
        "reasoningEffort" to "<set>",
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

internal fun buildMistralOptions(
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
): MistralOptions {
    val clamped = options.reasoning?.let { clampThinkingLevel(model, it.toModelThinkingLevel()) }
    val reasoning = if (clamped == ModelThinkingLevel.OFF) null else clamped
    val useReasoning = model.reasoning && reasoning != null
    val maxTokens = clampMaxTokensToContext(model, context, options.maxTokens ?: model.maxTokens)

    return MistralOptions(
        apiKey = options.apiKey,
        sessionId = options.sessionId,
        temperature = options.temperature,
        maxTokens = maxTokens,
        timeoutMs = options.timeoutMs,
        maxRetries = options.maxRetries,
        maxRetryDelayMs = options.maxRetryDelayMs,
        env = options.env,
        headers = options.headers,
        toolChoice = options.toolChoice?.toToolChoice(),
        cacheRetention = options.cacheRetention,
        onPayload = options.onPayload,
        onResponse = options.onResponse,
        promptMode = if (useReasoning && usesPromptModeReasoning(model)) MistralPromptMode.REASONING else null,
        reasoningEffort = if (useReasoning && usesReasoningEffort(model)) {
            mapReasoningEffort(model, reasoning)
        } else {
            null
        },
        telemetryContext = options.telemetryContext,
    )
}

internal fun toMistralOptions(
    model: Model,
    options: OpenAiCompletionsOptions,
): MistralOptions {
    val useReasoning = model.reasoning && options.reasoningEffort != null
    return MistralOptions(
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
            mapReasoningEffort(model, options.reasoningEffort)
        } else {
            null
        },
        telemetryContext = options.telemetryContext,
    )
}

/**
 * Native Mistral Chat Completions streaming adapter.
 *
 * Divergences from pi: aborts surface as coroutine cancellation and end the
 * flow without an error event (pi emits a terminal "aborted" error), and
 * requests are not retried — pi calls raw `fetch` with no retry wrapper, so
 * Mistral effectively ignores `maxRetries`; `transport.post` is called
 * directly for the same behavior.
 */
class MistralConversationsApi(
    private val transport: HttpStreamingTransport,
    private val clock: Clock = Clock.System,
) : ChatApi {

    fun stream(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
    ): Flow<AssistantMessageEvent> = stream(model, context, toMistralOptions(model, options))

    fun stream(
        model: Model,
        context: Context,
        options: MistralOptions,
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = clock.now().toEpochMilliseconds()
        val state = MistralStreamingState(model, startedAtMs)
        try {
            val apiKey = options.apiKey
                ?: throw ProviderAuthException("No API key for provider: ${model.provider}")

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
            var payload = MistralConversationsPayload.buildRequestBody(model, context, wireMessages, options)
            options.onPayload?.let { hook -> hook(payload, model)?.let { payload = it } }

            val url = model.baseUrl.trimEnd('/') + "/v1/chat/completions"
            val (bearerToken, headers) = buildMistralHeaders(model, apiKey, options)
            val request = TransportRequest(
                url = url,
                bearerToken = bearerToken,
                headers = headers,
                body = payload.toString().toByteArray(Charsets.UTF_8),
                timeoutMs = options.timeoutMs ?: DEFAULT_TIMEOUT_MS,
            )

            val response = try {
                transport.post(request)
            } catch (error: ProviderHttpException) {
                options.onResponse?.invoke(ProviderResponse(error.status, headersToRecord(error.headers)), model)
                throw error
            }
            options.onResponse?.invoke(ProviderResponse(response.status, headersToRecord(response.headers)), model)

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

    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<AssistantMessageEvent> {
        val apiKey = options.apiKey
            ?: throw ProviderAuthException("No API key for provider: ${model.provider}")

        return stream(
            model,
            context,
            buildMistralOptions(model, context, options).copy(apiKey = apiKey),
        )
    }

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
            lenientJson.parseToJsonElement(event.data)
        } catch (error: Exception) {
            throw ProviderStreamException(
                "Invalid Mistral streaming event: ${error.message ?: error::class.simpleName}",
            )
        }
        if (chunk !is JsonObject || chunk["choices"] !is JsonArray) {
            throw ProviderStreamException("Invalid Mistral streaming event")
        }
        val data = chunk

        data["id"].strOrNull()?.takeIf { it.isNotEmpty() && state.responseId == null }
            ?.let { state.responseId = it }

        data.obj("usage")?.let { state.usage = parseChunkUsage(it, model) }

        val choice = data.arr("choices")!!.firstOrNull() as? JsonObject ?: return emptyList()

        // pi guards with truthiness (`if (choice.finish_reason)`), so an empty
        // string counts as absent and the stream must still yield a finish
        // reason — same lenient read as the responseId check above.
        choice["finish_reason"].strOrNull()?.takeIf { it.isNotEmpty() }?.let { raw ->
            state.rawStopReason = raw
            val (stopReason, errorMessage) = mapChatStopReason(raw)
            state.stopReason = stopReason
            if (errorMessage != null) state.errorMessage = errorMessage
        }

        val events = mutableListOf<AssistantMessageEvent>()
        val delta = choice.obj("delta")

        if (delta != null) {
            when (val content = delta["content"]) {
                is JsonPrimitive -> if (content != JsonNull) {
                    events += state.appendText(content.content)
                }
                is JsonArray -> for (item in content) {
                    val obj = item as? JsonObject ?: continue
                    when (obj["type"].strOrNull()) {
                        "thinking" -> {
                            val deltaText = (obj["thinking"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonObject)?.get("text").strOrNull() }
                                ?.filter { it.isNotEmpty() }
                                ?.joinToString("")
                                ?: ""
                            if (deltaText.isNotEmpty()) events += state.appendThinking(deltaText)
                        }
                        "text" -> events += state.appendText(obj["text"].strOrNull() ?: "")
                    }
                }
                else -> Unit
            }

            delta.arr("tool_calls")?.forEach { element ->
                (element as? JsonObject)?.let { events += state.appendToolCallDelta(it) }
            }
        }
        return events
    }

    /** Cached tokens reduce the input count; cache writes are always 0. */
    private fun parseChunkUsage(raw: JsonObject, model: Model): Usage {
        val promptTokens = raw.int("prompt_tokens") ?: 0
        val cachedPromptTokens = cachedPromptTokens(raw, promptTokens)
        val input = maxOf(0, promptTokens - cachedPromptTokens)
        val output = raw.int("completion_tokens") ?: 0
        // total_tokens || input+output+cacheRead; 0 falls back to the sum
        val totalTokens = (raw.int("total_tokens") ?: 0).takeIf { it != 0 }
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

    /** Handles the several provider spellings of the cached-token count, clamped to prompt tokens. */
    private fun cachedPromptTokens(raw: JsonObject, promptTokens: Int): Int {
        val rawCached = raw.obj("promptTokensDetails")?.int("cachedTokens")
            ?: raw.obj("prompt_tokens_details")?.int("cached_tokens")
            ?: raw.obj("promptTokenDetails")?.int("cachedTokens")
            ?: raw.obj("prompt_token_details")?.int("cached_tokens")
            ?: raw.int("numCachedTokens")
            ?: raw.int("num_cached_tokens")
            ?: 0
        return minOf(promptTokens, maxOf(0, rawCached))
    }

    internal fun mapChatStopReason(reason: String): Pair<StopReason, String?> = when (reason) {
        "stop" -> StopReason.STOP to null
        "length", "model_length" -> StopReason.LENGTH to null
        "tool_calls" -> StopReason.TOOL_USE to null
        "error" -> StopReason.ERROR to "Provider stopped with: error"
        else -> StopReason.ERROR to "Provider stopped with: $reason"
    }

    /** Mistral-specific error formatting, as in pi — not the shared provider error formatter. */
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
        /** pi's AbortSignal.timeout default. */
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}

/**
 * Accumulates streamed Mistral content with pi's block semantics: one open
 * text/thinking block at a time, closed when a block of another type (or a
 * tool call) starts; tool-call fragments merged by `index ?? callId` even
 * when later chunks carry no id; toolcall_end events emitted (in first-seen
 * order) after the final text/thinking block closes.
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
    // Pi types this Map<string | number, number>; boxed Int vs String keys
    // keep the same non-colliding number/string distinction.
    private val toolBlocksByKey = LinkedHashMap<Any, Int>()

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

        val id = toolCall["id"].strOrNull()
        val index = toolCall.long("index")?.toInt()
        val callId = if (!id.isNullOrEmpty() && id != "null") {
            id
        } else {
            deriveMistralToolCallId("toolcall:${index ?: 0}", 0)
        }
        // Id and name are set only at block creation, so a later chunk's
        // derived id never overwrites the block's id.
        val key: Any = index ?: callId
        val function = toolCall.obj("function")
        val name = function?.get("name").strOrNull() ?: ""

        var blockIndex = toolBlocksByKey[key]
        if (blockIndex == null) {
            blockIndex = blocks.size
            blocks.add(Block.Tool(callId, name, StringBuilder()))
            toolBlocksByKey[key] = blockIndex
            events.add(AssistantMessageEvent.ToolCallStart(blockIndex, snapshot()))
        }

        val toolBlock = blocks[blockIndex] as Block.Tool
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

/** Normalizes arbitrary tool call IDs to Mistral's 9-character alphanumeric format, avoiding collisions. */
class MistralToolCallIdNormalizer {
    private val idMap = mutableMapOf<String, String>()
    private val reverseMap = mutableMapOf<String, String>()

    fun normalize(id: String): String {
        idMap[id]?.let { return it }

        var attempt = 0
        while (true) {
            val candidate = deriveMistralToolCallId(id, attempt)
            val owner = reverseMap[candidate]
            if (owner == null || owner == id) {
                idMap[id] = candidate
                reverseMap[candidate] = id
                return candidate
            }
            attempt++
        }
    }
}

internal fun deriveMistralToolCallId(id: String, attempt: Int): String {
    val normalized = id.replace(Regex("[^a-zA-Z0-9]"), "")
    if (attempt == 0 && normalized.length == MistralConversationsPayload.MISTRAL_TOOL_CALL_ID_LENGTH) {
        return normalized
    }
    val seedBase = normalized.ifEmpty { id }
    val seed = if (attempt == 0) seedBase else "$seedBase:$attempt"
    return shortHash(seed)
        .replace(Regex("[^a-zA-Z0-9]"), "")
        .take(MistralConversationsPayload.MISTRAL_TOOL_CALL_ID_LENGTH)
}

/**
 * Request construction for the native Mistral Chat Completions API.
 *
 * Divergence from pi: the wire payload is built directly in snake_case; pi
 * builds an internal camelCase payload and remaps it in `toMistralWirePayload`
 * so its onPayload hook can add SDK-style fields. This port builds the wire
 * payload directly, so the ported onPayload hook (see [MistralOptions]) sees
 * and returns the snake_case wire object.
 */
object MistralConversationsPayload {

    /** Mistral tool call IDs must be 9 alphanumeric characters. */
    const val MISTRAL_TOOL_CALL_ID_LENGTH = 9

    /** Builds the `v1/chat/completions` request body. */
    fun buildRequestBody(
        model: Model,
        context: Context,
        messages: List<JsonObject>,
        options: MistralOptions,
    ): JsonObject = buildJsonObject {
        put("model", model.id)
        put("stream", true)
        put("messages", JsonArray(messages))
        if (context.tools.isNotEmpty()) {
            put("tools", JsonArray(context.tools.map { toFunctionTool(it) }))
        }
        options.temperature?.let { put("temperature", it) }
        options.maxTokens?.let { put("max_tokens", it) }
        options.toolChoice?.let { put("tool_choice", mapToolChoice(it)) }
        options.promptMode?.let { put("prompt_mode", it.wire) }
        options.reasoningEffort?.let { put("reasoning_effort", it) }
        if (shouldUsePromptCaching(options)) {
            requireNotNull(options.sessionId) // guarded by shouldUsePromptCaching
            put("prompt_cache_key", options.sessionId)
        }
    }

    fun shouldUsePromptCaching(options: MistralOptions): Boolean =
        options.cacheRetention != CacheRetention.NONE && options.sessionId != null

    private fun mapToolChoice(choice: ToolChoice): kotlinx.serialization.json.JsonElement = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Any -> JsonPrimitive("any")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Function -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", choice.name) })
        }
    }

    /** Mistral always supports strict mode, so the schema is rewritten when strict applies. */
    private fun toFunctionTool(tool: Tool): JsonObject {
        val strict = resolveJsonSchemaStrictSampling(tool, supportsStrictMode = true)
        return buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", getJsonSchemaToolParameters(tool, strict))
                    put("strict", strict ?: false)
                },
            )
        }
    }

    /**
     * Converts transformed messages (see [transformMessages]) to Mistral wire
     * messages: thinking is already plain text, tool call IDs are already
     * normalized (with tool results remapped), and orphaned tool calls already
     * have synthetic results.
     */
    fun toChatMessages(
        messages: List<Message>,
        supportsImages: Boolean,
    ): List<JsonObject> {
        val result = mutableListOf<JsonObject>()

        for (msg in messages) {
            when (msg.role) {
                MessageRole.USER -> {
                    val content = (msg as works.resolve.pathfinder.ai.core.UserMessage).content
                    // A single-text-block message is the Kotlin equivalent of
                    // pi's plain-text content string for user prompts.
                    if (content.size == 1 && content[0].type == ContentType.TEXT) {
                        result.add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    sanitize(content[0] as TextContent),
                                )
                            },
                        )
                        continue
                    }
                    val hadImages = content.any { it.type == ContentType.IMAGE }
                    val chunks = buildJsonArray {
                        for (item in content) {
                            if (item.type == ContentType.TEXT) {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", sanitize(item as TextContent))
                                    },
                                )
                            } else if (supportsImages && item.type == ContentType.IMAGE) {
                                add(imageChunk(item as works.resolve.pathfinder.ai.core.ImageContent))
                            }
                        }
                    }
                    if (chunks.size > 0) {
                        result.add(buildJsonObject {
                            put("role", "user")
                            put("content", chunks)
                        })
                        continue
                    }
                    if (hadImages && !supportsImages) {
                        result.add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "(image omitted: model does not support images)")
                            },
                        )
                    }
                }

                MessageRole.ASSISTANT -> {
                    val assistant = msg as AssistantMessage
                    val contentParts = mutableListOf<JsonObject>()
                    val toolCalls = mutableListOf<JsonObject>()

                    for (block in assistant.content) {
                        when (block.type) {
                            ContentType.TEXT -> {
                                val text = sanitize(block as TextContent)
                                if (text.trim().isNotEmpty()) {
                                    contentParts.add(
                                        buildJsonObject { put("type", "text"); put("text", text) },
                                    )
                                }
                            }
                            ContentType.THINKING -> {
                                val thinking = sanitizeText((block as works.resolve.pathfinder.ai.core.ThinkingContent).thinking)
                                if (thinking.trim().isNotEmpty()) {
                                    contentParts.add(
                                        buildJsonObject {
                                            put("type", "thinking")
                                            put(
                                                "thinking",
                                                buildJsonArray {
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "text")
                                                            put("text", thinking)
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                            ContentType.TOOL_CALL -> {
                                val call = block as works.resolve.pathfinder.ai.core.ToolCall
                                toolCalls.add(
                                    buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", call.name)
                                                put("arguments", call.arguments.ifEmpty { "{}" })
                                            },
                                        )
                                        put("index", 0)
                                    },
                                )
                            }
                            ContentType.IMAGE -> Unit
                        }
                    }

                    val wireMessage = buildJsonObject {
                        put("role", "assistant")
                        put("prefix", false)
                        if (contentParts.isNotEmpty()) put("content", JsonArray(contentParts))
                        if (toolCalls.isNotEmpty()) {
                            put("tool_calls", JsonArray(toolCalls))
                        }
                    }
                    if (contentParts.isNotEmpty() || toolCalls.isNotEmpty()) result.add(wireMessage)
                }

                MessageRole.TOOL_RESULT -> {
                    val toolMsg = msg as works.resolve.pathfinder.ai.core.ToolResultMessage
                    val textResult = toolMsg.content
                        .filter { it.type == ContentType.TEXT }
                        .joinToString("\n") { sanitize(it as TextContent) }
                    val hasImages = toolMsg.content.any { it.type == ContentType.IMAGE }
                    val toolText = buildToolResultText(textResult, hasImages, supportsImages, toolMsg.isError)
                    val contentChunks = buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", toolText) })
                        for (part in toolMsg.content) {
                            if (!supportsImages) continue
                            if (part.type != ContentType.IMAGE) continue
                            add(imageChunk(part as works.resolve.pathfinder.ai.core.ImageContent))
                        }
                    }
                    result.add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", toolMsg.toolCallId)
                            put("name", toolMsg.toolName)
                            put("content", contentChunks)
                        },
                    )
                }
            }
        }

        return result
    }

    private fun imageChunk(image: works.resolve.pathfinder.ai.core.ImageContent): JsonObject =
        buildJsonObject {
            put("type", "image_url")
            put("image_url", "data:${image.mimeType};base64,${image.data}")
        }

    internal fun buildToolResultText(
        text: String,
        hasImages: Boolean,
        supportsImages: Boolean,
        isError: Boolean,
    ): String {
        val trimmed = text.trim()
        val errorPrefix = if (isError) "[tool error] " else ""

        if (trimmed.isNotEmpty()) {
            val imageSuffix =
                if (hasImages && !supportsImages) "\n[tool image omitted: model does not support images]" else ""
            return "$errorPrefix$trimmed$imageSuffix"
        }

        if (hasImages) {
            if (supportsImages) {
                return if (isError) "[tool error] (see attached image)" else "(see attached image)"
            }
            return if (isError) {
                "[tool error] (image omitted: model does not support images)"
            } else {
                "(image omitted: model does not support images)"
            }
        }

        return if (isError) "[tool error] (no tool output)" else "(no tool output)"
    }

    private fun sanitize(content: TextContent): String = sanitizeText(content.text)

    private fun sanitizeText(text: String): String = sanitizeSurrogates(text)
}

internal fun buildMistralSystemMessage(systemPrompt: String): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("role", "system")
        put("content", sanitizeSurrogates(systemPrompt))
    }

internal fun usesReasoningEffort(model: Model): Boolean =
    model.id == "mistral-small-2603" || model.id == "mistral-small-latest" || model.id == "mistral-medium-3.5"

internal fun usesPromptModeReasoning(model: Model): Boolean = model.reasoning && !usesReasoningEffort(model)

internal fun mapReasoningEffort(model: Model, level: ModelThinkingLevel): MistralReasoningEffort =
    model.thinkingLevelMap?.forLevel(level) ?: "high"
