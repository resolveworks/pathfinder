package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.toModelThinkingLevel
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.core.ProviderResponse
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.headersToRecord
import works.resolve.pathfinder.ai.core.toToolChoice
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
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.long
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.telemetry.TelemetryContext
import works.resolve.pathfinder.ai.utils.strOrNull
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
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** pi's MistralReasoningEffort. */
typealias MistralReasoningEffort = String // "none" | "high"

/** pi's MistralOptions.promptMode; only "reasoning" exists. */
enum class MistralPromptMode(val wire: String) { REASONING("reasoning") }

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
    /**
     * pi's onPayload request hook (ProviderRequestOptions, types.ts:145-149;
     * mistral-conversations.ts:142): replaces the payload object before
     * serialization when it returns non-null. Divergence: upstream's hook sees
     * the internal camelCase payload before `toMistralWirePayload` remaps it;
     * this port builds the snake_case wire payload directly, so the hook
     * sees the wire object. Receives full message content; installers must
     * not log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /**
     * pi's onResponse request hook (types.ts:184; mistral-conversations.ts:306):
     * invoked after response headers arrive — including non-2xx, since
     * upstream fires it before the `response.ok` check (the transport here
     * throws for non-2xx, so the error path invokes it from the exception's
     * status/headers). Never included in toString().
     */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * pi's ProviderRequestOptions.telemetryContext (types.ts:126-127),
     * inherited via StreamOptions (MistralOptions extends StreamOptions,
     * mistral-conversations.ts): explicit parent context for telemetry
     * produced by this logical request. Dormant in this port — carried for
     * shape fidelity, preserved through the streamSimple conversion
     * (buildBaseOptions). Presence boolean only in toString().
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

/**
 * pi's streamSimple options conversion for mistral-conversations:
 * buildBaseOptions plus the clamped thinking level and per-model prompt-mode
 * vs reasoning-effort selection. Extracted as a named function so the
 * conversion (including telemetryContext identity) is directly testable.
 */
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
            mapReasoningEffort(model, reasoning!!)
        } else {
            null
        },
        telemetryContext = options.telemetryContext,
    )
}

/**
 * pi's manual OpenAI-completions-style options conversion for the mistral
 * adapter's `stream(model, context, OpenAiCompletionsOptions)` overload
 * (mistral-conversations.ts accepts StreamOptions-shaped input): maps the
 * shared surface plus per-model prompt-mode vs reasoning-effort selection
 * onto [MistralOptions]. Extracted as a named function so the conversion
 * (including telemetryContext identity) is directly testable.
 */
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
            mapReasoningEffort(model, options.reasoningEffort!!)
        } else {
            null
        },
        telemetryContext = options.telemetryContext,
    )
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
    private val clock: Clock = Clock.System,
) : ChatApi {

    fun stream(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
    ): Flow<AssistantMessageEvent> = stream(model, context, toMistralOptions(model, options))

    /**
     * Streams with native Mistral options, pi's `stream` entry point. The
     * payload is built first, so API-key failures surface before any request.
     */
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
            // pi mistral-conversations.ts:142: onPayload inspects/replaces the
            // payload object before serialization; null keeps the payload.
            var payload = MistralConversationsPayload.buildRequestBody(model, context, wireMessages, options)
            options.onPayload?.let { hook -> hook(payload, model)?.let { payload = it } }

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

            // pi mistral-conversations.ts:306: onResponse fires right after
            // response headers arrive, before the !ok check — so it also runs
            // for non-2xx (surfaced from ProviderHttpException here).
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
            ?: throw ProviderAuthException("No API key for provider: ${model.provider}")

        return stream(
            model,
            context,
            buildMistralOptions(model, context, options).copy(apiKey = apiKey),
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

        choice["finish_reason"].strOrNull()?.let { raw ->
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

    /** pi's usage handling: cached tokens reduce the input count; writes are always 0. */
    private fun parseChunkUsage(raw: JsonObject, model: Model): Usage {
        val promptTokens = raw.int("prompt_tokens") ?: 0
        val cachedPromptTokens = cachedPromptTokens(raw, promptTokens)
        val input = maxOf(0, promptTokens - cachedPromptTokens)
        val output = raw.int("completion_tokens") ?: 0
        // pi: total_tokens || input+output+cacheRead (0 falls back to the sum)
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

    /** pi's getMistralCachedPromptTokens: several provider spellings, clamped to prompt tokens. */
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
    }
}

internal fun buildMistralSystemMessage(systemPrompt: String): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("role", "system")
        put("content", sanitizeSurrogates(systemPrompt))
    }


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
 * a tool call) starts, tool blocks keyed by `index ?? callId` (pi commit
 * 6c87d9a02, issue #8387: "fix(ai): merge indexed Mistral tool call
 * chunks" — indexed fragments merge regardless of whether later chunks
 * carry the same or any id, and id/name are set only at block creation),
 * and toolcall_end events emitted (in first-seen order) after the final
 * text/thinking block closes.
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
        // pi 6c87d9a02 (#8387): `const key = toolCall.index ?? callId` —
        // indexed chunks merge even when later fragments carry no id. Id and
        // name are only set at block creation (upstream), so a later chunk's
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
