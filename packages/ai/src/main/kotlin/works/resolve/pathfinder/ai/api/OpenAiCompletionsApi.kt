package works.resolve.pathfinder.ai.api

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheControlFormat
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.ChatTemplateKwargValue
import works.resolve.pathfinder.ai.ContentType
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.DeferredToolsMode
import works.resolve.pathfinder.ai.DoneSentinel
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.MaxTokensField
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.MessageRole
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.OpenAiCompletionsCompat
import works.resolve.pathfinder.ai.ProviderAuthException
import works.resolve.pathfinder.ai.ProviderResponse
import works.resolve.pathfinder.ai.ProviderStreamException
import works.resolve.pathfinder.ai.SessionAffinityFormat
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.StreamOptions
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ThinkingFormat
import works.resolve.pathfinder.ai.ThinkingLevel
import works.resolve.pathfinder.ai.ThinkingLevelMap
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolChoice
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.api.resolveCloudflareBaseUrl
import works.resolve.pathfinder.ai.calculateCost
import works.resolve.pathfinder.ai.hasHeader
import works.resolve.pathfinder.ai.headersToRecord
import works.resolve.pathfinder.ai.mergeHeaders
import works.resolve.pathfinder.ai.mergeSamplingParams
import works.resolve.pathfinder.ai.toModelThinkingLevel
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.long
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.shortHash
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strOrNull
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull
import works.resolve.pathfinder.telemetry.TelemetryContext

/**
 * OpenRouter structured `reasoning_details`: one of `reasoning.summary`,
 * `reasoning.encrypted`, or `reasoning.text`, streamed as deltas that
 * consecutive text/summary entries merge into while encrypted entries stay
 * opaque and discrete. Accumulated details are serialized as a JSON array into
 * the thinking block's `thinkingSignature` slot and replayed as
 * `assistantMsg.reasoning_details` on the next request.
 */

private fun isReasoningDetailObject(detail: JsonElement): Boolean = detail is JsonObject

private fun hasValidCommonReasoningDetailFields(detail: JsonObject): Boolean {
    val id = detail["id"]
    if (id != null && id !is JsonNull && id.stringOrNull() == null) return false
    val format = detail["format"]
    if (format != null && format !is JsonNull && format.stringOrNull() == null) return false
    val index = detail["index"]
    if (index != null && index !is JsonNull &&
        // pi guards with `typeof index === "number"`; numeric primitives only.
        (index as? JsonPrimitive)?.let { it.longOrNull != null || it.doubleOrNull != null } != true
    ) {
        return false
    }
    return true
}

internal fun isOpenAiReasoningDetail(detail: JsonElement): Boolean {
    if (!isReasoningDetailObject(detail) ||
        !hasValidCommonReasoningDetailFields(detail as JsonObject)
    ) {
        return false
    }
    return when (detail.string("type")) {
        "reasoning.summary" -> detail.string("summary") != null

        "reasoning.encrypted" -> detail.string("data") != null

        "reasoning.text" ->
            detail.string("text") != null &&
                (
                    detail["signature"] == null || detail["signature"] is JsonNull ||
                        detail.string("signature") != null
                    )

        else -> false
    }
}

internal fun parseOpenAIReasoningDetails(signature: String?): JsonArray? {
    if (signature == null) return null
    val parsed = try {
        kotlinx.serialization.json.Json.parseToJsonElement(signature)
    } catch (_: Exception) {
        return null
    }
    if (parsed !is JsonArray || parsed.size == 0 || parsed.any { !isOpenAiReasoningDetail(it) }) {
        return null
    }
    return parsed
}

/** Legacy format: an encrypted detail stored on a tool call's
 * `thoughtSignature` by older assistant messages. */
internal fun parseLegacyEncryptedReasoningDetail(signature: String?): JsonObject? {
    if (signature == null) return null
    val parsed = try {
        kotlinx.serialization.json.Json.parseToJsonElement(signature)
    } catch (_: Exception) {
        return null
    }
    if (parsed !is JsonObject || !isOpenAiReasoningDetail(parsed)) return null
    if (parsed.string("type") != "reasoning.encrypted") return null
    val id = parsed.string("id") ?: return null
    val data = parsed.string("data") ?: return null
    return if (id.isNotEmpty() && data.isNotEmpty()) parsed else null
}

/** JS `??=` semantics: a present-but-null value counts as missing. */
private fun fillMissing(
    target: MutableMap<String, JsonElement>,
    key: String,
    source: Map<String, JsonElement>
) {
    val current = target[key]
    if ((target.containsKey(key) && current !is JsonNull)) return
    val value = source[key]
    if (value != null && value !is JsonNull) target[key] = value
}

private fun fillMissingCommonReasoningDetailFields(
    target: MutableMap<String, JsonElement>,
    source: Map<String, JsonElement>
) {
    fillMissing(target, "id", source)
    // `||=`: also replaces an empty string.
    val targetView = JsonObject(target)
    val format = targetView.string("format")
    if (format == null || format.isEmpty()) {
        JsonObject(source).string("format")?.let { target["format"] = JsonPrimitive(it) }
    }
    fillMissing(target, "index", source)
}

internal fun appendOpenAIReasoningDetail(
    details: MutableList<MutableMap<String, JsonElement>>,
    detail: Map<String, JsonElement>
) {
    val lastDetail = details.lastOrNull()
    // Reads go through JsonObject views of the mutable maps (shared strict surface).
    val view = JsonObject(detail)
    val lastView = lastDetail?.let(::JsonObject)
    if (view.string("type") == "reasoning.text" && lastView?.string("type") == "reasoning.text") {
        lastDetail!!["text"] = JsonPrimitive(lastView.string("text")!! + view.string("text")!!)
        if (lastView.string("signature") == null || lastView.string("signature")!!.isEmpty()) {
            view.string("signature")?.let { lastDetail["signature"] = JsonPrimitive(it) }
        }
        fillMissingCommonReasoningDetailFields(lastDetail, detail)
        return
    }
    if (view.string("type") == "reasoning.summary" &&
        lastView?.string("type") == "reasoning.summary"
    ) {
        lastDetail!!["summary"] =
            JsonPrimitive(lastView.string("summary")!! + view.string("summary")!!)
        fillMissingCommonReasoningDetailFields(lastDetail, detail)
        return
    }
    details.add(LinkedHashMap(detail))
}

data class OpenAiCompletionsOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Requested thinking level; null disables reasoning. */
    val reasoningEffort: ModelThinkingLevel? = null,
    /** Tool selection forwarded as the Chat Completions `tool_choice` param. */
    val toolChoice: ToolChoice? = null,
    /** Prompt-cache retention preference; null resolves from env/default. */
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    /** Per-request provider env; credential values are merged in. */
    val env: Map<String, String> = emptyMap(),
    /** Explicit request headers; merged over resolved auth headers. */
    val headers: Map<String, String?> = emptyMap(),
    /** Per-level thinking token budgets; consumed by budget-based adapters. */
    val thinkingBudgets: Map<ThinkingLevel, Int> = emptyMap(),
    /**
     * Replaces the request params object before serialization when it
     * returns non-null. Receives full message content; installers must not
     * log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /**
     * Invoked after response headers arrive (2xx only — the SDK path throws
     * before the hook on non-2xx). Never included in toString().
     */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * Merged into the params object last so custom keys override the named
     * request fields. Already merged over [Model.samplingParams] by
     * [mergeSamplingParams] on the streamSimple path. Only keys appear in
     * toString().
     */
    val samplingParams: Map<String, JsonElement>? = null,
    /**
     * Explicit parent context for telemetry produced by this logical request.
     * Dormant: carried for shape fidelity, preserved through the streamSimple
     * conversion. Presence boolean only in toString().
     */
    val telemetryContext: TelemetryContext? = null
) {
    override fun toString(): String = optionsToString(
        "OpenAiCompletionsOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "reasoningEffort" to reasoningEffort,
        "toolChoice" to toolChoice,
        "cacheRetention" to cacheRetention,
        "timeoutMs" to timeoutMs,
        "maxRetries" to maxRetries,
        "maxRetryDelayMs" to maxRetryDelayMs,
        "env" to env.keys,
        "headers" to headers.keys,
        "onPayload" to (onPayload != null),
        "onResponse" to (onResponse != null),
        "samplingParams" to samplingParams?.keys,
        "telemetryContext" to (telemetryContext != null)
    )
}

/**
 * OpenAI Chat Completions streaming adapter.
 *
 * Like pi's stream(), failures after the stream starts are encoded as an
 * [AssistantMessageEvent.Error] carrying the partial message, not thrown.
 * Unparseable SSE data payloads are protocol errors; null and non-object
 * chunks are skipped, like pi's `if (!chunk || typeof chunk !== "object")`.
 */
class OpenAiCompletionsApi(
    private val transport: works.resolve.pathfinder.ai.transport.HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val clock: Clock = Clock.System
) : ChatApi {

    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        val clamped = options.reasoning?.let {
            works.resolve.pathfinder.ai.clampThinkingLevel(model, it.toModelThinkingLevel())
        }
        val effort = if (clamped == ModelThinkingLevel.OFF) null else clamped
        val maxTokens = works.resolve.pathfinder.ai.utils.clampMaxTokensToContext(
            model,
            context,
            options.maxTokens ?: model.maxTokens
        )
        return stream(
            model,
            context,
            options.toStreamOptions(effort)
                .copy(maxTokens = maxTokens, samplingParams = mergeSamplingParams(model, options))
        )
    }

    fun stream(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = clock.now().toEpochMilliseconds()
        val state = StreamingState(model, startedAtMs)
        try {
            // Header-based auth (e.g. Cloudflare's cf-aig-authorization)
            // stands in for an apiKey.
            val hasAuthHeader = hasHeader(options.headers, "authorization") ||
                hasHeader(options.headers, "cf-aig-authorization")
            val apiKey = options.apiKey
                ?: if (hasAuthHeader) {
                    null
                } else {
                    throw ProviderAuthException(
                        "No API key for provider: ${model.provider}"
                    )
                }

            var params = OpenAiCompletionsPayload.buildRequestBody(model, context, options)
            options.onPayload?.let { hook -> hook(params, model)?.let { params = it } }
            val body = params
                .toString()
                .toByteArray(Charsets.UTF_8)

            // The always-sent Accept header is merged last and can never be
            // overridden by request headers.
            val cacheRetention = OpenAiResponsesApi.resolveCacheRetention(
                options.cacheRetention,
                options.env
            )
            val cacheSessionId =
                if (cacheRetention == CacheRetention.NONE) null else options.sessionId
            val mergedHeaders = mergeHeaders(
                mergeHeaders(
                    mergeHeaders(
                        mergeHeaders(
                            mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), model.headers),
                            copilotDynamicHeadersFor(model, context)
                        ),
                        sessionAffinityHeaders(model, cacheSessionId)
                    ),
                    options.headers
                ),
                mapOf("Accept" to "text/event-stream")
            ).filterValues { it != null }.mapValues { it.value!! }
            val url = resolveCloudflareBaseUrl(model.baseUrl, options.env)
                .trimEnd('/') + "/chat/completions"
            val request = TransportRequest(
                url = url,
                bearerToken = apiKey,
                headers = mergedHeaders,
                body = body,
                timeoutMs = options.timeoutMs
            )

            // Retries only cover failures before SSE content begins; once the
            // response starts the request is never retried.
            val response = retry.retryProviderRequest<TransportResponse>(
                options.maxRetries,
                options.maxRetryDelayMs
            ) {
                transport.post(request)
            }

            // Only runs for 2xx: the transport throws ProviderHttpException
            // on non-2xx before reaching this point.
            options.onResponse?.invoke(
                ProviderResponse(response.status, headersToRecord(response.headers)),
                model
            )

            emitAll(state.start())

            try {
                response.events.collect { event ->
                    processSseEvent(event, model, state)?.let { emitAll(it) }
                    if (state.done) throw DoneSentinel()
                }
            } catch (_: DoneSentinel) {
                // Cancelling the collector closes the transport call promptly.
            }

            emitAll(state.finish())

            if (!state.hasFinishReason && !model.compat.supportsFinishReason) {
                state.stopReason =
                    if (state.hasToolCalls()) StopReason.TOOL_USE else StopReason.STOP
            }
            if (state.stopReason == StopReason.ERROR) {
                throw ProviderStreamException(
                    state.errorMessage ?: "Provider returned an error stop reason"
                )
            }
            if ((model.compat.supportsFinishReason && !state.hasFinishReason) ||
                state.stopReason == StopReason.PENDING
            ) {
                throw ProviderStreamException("Stream ended without finish_reason")
            }

            emit(AssistantMessageEvent.Done(state.stopReason, state.snapshot()))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // The partial error message still carries the serialized reasoning
            // details.
            state.applyStreamedReasoningDetails()
            val finalMessage = state.snapshot().copy(
                stopReason = StopReason.ERROR,
                errorMessage = formatProviderError(error)
            )
            emit(AssistantMessageEvent.Error(finalMessage.stopReason, finalMessage))
        }
    }

    /** Returns the block events to emit; null marks the stream complete (`[DONE]`). */
    private fun processSseEvent(
        event: SseEvent,
        model: Model,
        state: StreamingState
    ): List<AssistantMessageEvent>? {
        if (event.data.trim() == DONE) {
            state.markDone()
            return null
        }
        val chunk = try {
            lenientJson.parseToJsonElement(event.data)
        } catch (error: Exception) {
            throw ProviderStreamException(
                "Malformed SSE JSON payload: ${error.message ?: error::class.simpleName}"
            )
        }
        if (chunk !is JsonObject) {
            // pi skips null and non-object chunks (some OpenAI-compatible
            // providers send `data: null` keep-alives); only unparseable
            // payloads above are protocol errors.
            return emptyList()
        }

        // Some providers deliver errors as JSON events mid-stream.
        chunk.obj("error")?.let { error ->
            throw ProviderStreamException(formatJsonError(error))
        }

        chunk.str("id")
            ?.takeIf { it.isNotEmpty() && state.responseId == null }
            ?.let { state.responseId = it }
        chunk["model"].stringOrNull()
            ?.takeIf { it.isNotEmpty() && it != model.id && state.responseModel == null }
            ?.let { state.responseModel = it }

        chunk.obj("usage")?.let { state.usage = parseChunkUsage(it, model) }

        val choice = chunk.arr("choices")?.firstOrNull() as? JsonObject
            ?: return emptyList()

        // Fallback: some providers return usage in choice.usage.
        if (chunk["usage"] == null) {
            choice.obj("usage")?.let { state.usage = parseChunkUsage(it, model) }
        }

        choice
            .str("finish_reason")
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                state.rawStopReason = raw
                val (stopReason, errorMessage) = mapStopReason(raw)
                state.stopReason = stopReason
                if (errorMessage != null) state.errorMessage = errorMessage
                state.hasFinishReason = true
            }

        val delta = choice.obj("delta") ?: return emptyList()

        val events = mutableListOf<AssistantMessageEvent>()
        delta["content"].strOrNull()?.takeIf { it.isNotEmpty() }?.let {
            events +=
                state.appendText(it)
        }

        // Reasoning arrives in reasoning_content (llama.cpp-style), reasoning,
        // or reasoning_text; the first non-empty field wins so duplicated
        // fields do not double-count. opencode-go stores the delta field
        // "reasoning" under the signature "reasoning_content" — the field it
        // accepts on replay.
        for (field in OpenAiCompletionsPayload.REASONING_FIELDS) {
            val value = delta[field].strOrNull()
            if (!value.isNullOrEmpty()) {
                val thinkingSignature =
                    if (model.provider == "opencode-go" &&
                        field == "reasoning"
                    ) {
                        "reasoning_content"
                    } else {
                        field
                    }
                events += state.appendThinking(value, thinkingSignature)
                break
            }
        }

        delta.arr("tool_calls")?.forEach { element ->
            (element as? JsonObject)?.let { events += state.appendToolCallDelta(it) }
        }

        // reasoning_details deltas keep the provider replay data in the
        // thinking signature slot; they are not user-visible stream deltas,
        // so no thinking_delta is emitted.
        delta.arr("reasoning_details")?.forEach { element ->
            if (element is JsonObject && isOpenAiReasoningDetail(element)) {
                state.appendReasoningDetail(LinkedHashMap(element))
            }
        }
        return events
    }

    private fun parseChunkUsage(raw: JsonObject, model: Model): Usage {
        val promptTokens = raw.int("prompt_tokens") ?: 0
        val details = raw.obj("prompt_tokens_details")
        val cacheReadTokens = details?.int("cached_tokens")
            ?: raw.int("prompt_cache_hit_tokens")
            ?: raw.int("cached_tokens")
            ?: 0
        val cacheWriteTokens = details?.int("cache_write_tokens") ?: 0
        val outputTokens = raw.int("completion_tokens") ?: 0
        val reasoningTokens = raw.obj("completion_tokens_details")?.int("reasoning_tokens") ?: 0

        // cached_tokens counts cache-read hits; do not subtract cache writes
        // from it.
        val input = maxOf(0, promptTokens - cacheReadTokens - cacheWriteTokens)
        val usage = Usage(
            input = input,
            output = outputTokens,
            cacheRead = cacheReadTokens,
            cacheWrite = cacheWriteTokens,
            reasoning = reasoningTokens,
            totalTokens = input + outputTokens + cacheReadTokens + cacheWriteTokens
        )
        return usage.copy(cost = calculateCost(model, usage))
    }

    private fun mapStopReason(reason: String): Pair<StopReason, String?> = when (reason) {
        "stop", "end" -> StopReason.STOP to null
        "length" -> StopReason.LENGTH to null
        "function_call", "tool_calls" -> StopReason.TOOL_USE to null
        "content_filter" -> StopReason.ERROR to "Provider finish_reason: content_filter"
        "network_error" -> StopReason.ERROR to "Provider finish_reason: network_error"
        else -> StopReason.ERROR to "Provider finish_reason: $reason"
    }

    private fun formatProviderError(error: Exception): String = when (error) {
        is ProviderHttpException -> buildString {
            append(formatProviderError(normalizeProviderError(error)))
            // Some providers via OpenRouter put additional information in
            // error.error.metadata.raw; append it only when the formatted body
            // has not already surfaced it, to avoid double-printing.
            openRouterRawMetadata(error.body)?.let { raw ->
                if (!contains(raw)) append("\n").append(raw)
            }
        }

        // pi's safeJsonStringify fallback for non-Error throws is moot in Kotlin.
        is ProviderStreamException -> error.message ?: "Provider stream error"

        else -> error.message ?: error::class.simpleName ?: "Unknown error"
    }

    private fun formatJsonError(error: JsonObject): String {
        val message = error["message"].strOrNull()
        val type = error["type"].strOrNull()
        val code = error["code"].strOrNull()
        return listOfNotNull(
            type,
            message ?: error.toString().take(500).ifEmpty { null },
            code?.let { "code: $it" }
        ).joinToString(" — ")
    }

    private fun openRouterRawMetadata(body: String): String? {
        val parsed = try {
            lenientJson.parseToJsonElement(body)
        } catch (_: Exception) {
            return null
        }
        return (parsed as? JsonObject)?.obj("error")?.obj("metadata")?.get("raw").strOrNull()
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}

private fun sessionAffinityHeaders(model: Model, cacheSessionId: String?): Map<String, String> {
    if (cacheSessionId == null || !model.compat.sendSessionAffinityHeaders) return emptyMap()
    val format = model.compat.sessionAffinityFormat
        ?: detectSessionAffinityFormat(model)
    return when (format) {
        SessionAffinityFormat.OPENROUTER -> mapOf("x-session-id" to cacheSessionId)

        SessionAffinityFormat.OPENAI, SessionAffinityFormat.OPENAI_NOSESSION -> buildMap {
            if (format == SessionAffinityFormat.OPENAI) put("session_id", cacheSessionId)
            put("x-client-request-id", cacheSessionId)
            put("x-session-affinity", cacheSessionId)
        }
    }
}

private suspend fun kotlinx.coroutines.flow.FlowCollector<AssistantMessageEvent>.emitAll(
    events: List<AssistantMessageEvent>
) {
    for (event in events) emit(event)
}

/**
 * Accumulates streamed content into block events. Every [snapshot] builds
 * fresh content instances so partial snapshots never share mutable state
 * across events. Streamed `tool_calls[].function.arguments` fragments are
 * accumulated as a raw string; strict parsing belongs to tool execution, not
 * this provider layer.
 */
internal class StreamingState(private val model: Model, private val timestampMs: Long) {
    private sealed interface Block {
        data object Text : Block
        data object Thinking : Block
        data class Tool(val accumulator: ToolCallAccumulator) : Block
    }

    internal class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private val blocks = mutableListOf<Block>()
    private var textIndex = -1
    private var thinkingIndex = -1
    private val toolByIndex = mutableMapOf<Int, Int>() // stream index -> block index
    private val toolById = mutableMapOf<String, Int>()

    private var text = ""
    private var thinking = ""
    private var thinkingSignature: String? = null

    // reasoning_details are replay metadata, kept in memory during streaming
    // and serialized once when finalized.
    private var streamedReasoningDetails: MutableList<MutableMap<String, JsonElement>>? = null

    var usage: Usage = Usage()
    var stopReason: StopReason = StopReason.PENDING
    var errorMessage: String? = null
    var rawStopReason: String? = null
    var responseId: String? = null
    var responseModel: String? = null
    var hasFinishReason: Boolean = false

    /** Set when the `[DONE]` sentinel arrives; collection stops promptly after. */
    var done: Boolean = false
        private set

    fun markDone() {
        done = true
    }

    fun start(): List<AssistantMessageEvent> = listOf(AssistantMessageEvent.Start(snapshot()))

    fun hasToolCalls(): Boolean = blocks.any { it is Block.Tool }

    fun appendText(delta: String): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        if (textIndex == -1) {
            textIndex = blocks.size
            blocks.add(Block.Text)
            events.add(AssistantMessageEvent.TextStart(textIndex, snapshot()))
        }
        text += delta
        events.add(AssistantMessageEvent.TextDelta(textIndex, delta, snapshot()))
        return events
    }

    fun appendThinking(delta: String, signature: String): List<AssistantMessageEvent> {
        val events = ensureThinkingBlock(signature)
        thinking += delta
        events.add(AssistantMessageEvent.ThinkingDelta(thinkingIndex, delta, snapshot()))
        return events
    }

    private fun ensureThinkingBlock(signature: String): MutableList<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        if (thinkingIndex == -1) {
            thinkingIndex = blocks.size
            blocks.add(Block.Thinking)
            thinkingSignature = signature
            events.add(AssistantMessageEvent.ThinkingStart(thinkingIndex, snapshot()))
        }
        return events
    }

    /**
     * Opens the thinking block with an empty signature (overwritten at
     * finish) and merges the delta into the accumulated reasoning details.
     */
    fun appendReasoningDetail(detail: MutableMap<String, JsonElement>) {
        ensureThinkingBlock("")
        val details = streamedReasoningDetails
            ?: mutableListOf<MutableMap<String, JsonElement>>().also {
                streamedReasoningDetails = it
            }
        appendOpenAIReasoningDetail(details, detail)
    }

    /** Serializes the accumulated details into the thinking signature,
     * including on the error path. */
    fun applyStreamedReasoningDetails() {
        streamedReasoningDetails?.let {
            thinkingSignature = JsonArray(it.map { detail -> JsonObject(detail) }).toString()
        }
    }

    fun appendToolCallDelta(delta: JsonObject): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        val streamIndex = delta.long("index")?.toInt()
        val id = delta.str("id")
        val function = delta.obj("function")
        val name = function?.get("name").strOrNull()

        var blockIndex = streamIndex?.let { toolByIndex[it] }
        if (blockIndex == null && id != null && id.isNotEmpty()) blockIndex = toolById[id]
        if (blockIndex == null) {
            blockIndex = blocks.size
            blocks.add(Block.Tool(ToolCallAccumulator()))
            events.add(AssistantMessageEvent.ToolCallStart(blockIndex, snapshot()))
        }
        if (streamIndex != null) toolByIndex[streamIndex] = blockIndex
        if (id != null && id.isNotEmpty()) toolById[id] = blockIndex

        val accumulator = (blocks[blockIndex] as Block.Tool).accumulator
        if (id != null && id.isNotEmpty() && accumulator.id.isEmpty()) accumulator.id = id
        if (!name.isNullOrEmpty() && accumulator.name.isEmpty()) accumulator.name = name
        val argDelta = function?.get("arguments").strOrNull() ?: ""
        accumulator.arguments.append(argDelta)

        events.add(AssistantMessageEvent.ToolCallDelta(blockIndex, argDelta, snapshot()))
        return events
    }

    /** Emits the terminal event for every open block, exactly once per block. */
    fun finish(): List<AssistantMessageEvent> {
        // Serialized reasoning details must be applied before thinking_end.
        applyStreamedReasoningDetails()
        return blocks.mapIndexed { index, block ->
            when (block) {
                Block.Text -> AssistantMessageEvent.TextEnd(index, text, snapshot())

                Block.Thinking -> AssistantMessageEvent.ThinkingEnd(index, thinking, snapshot())

                is Block.Tool -> AssistantMessageEvent.ToolCallEnd(
                    index,
                    toolCallOf(block.accumulator),
                    snapshot()
                )
            }
        }
    }

    private fun toolCallOf(accumulator: ToolCallAccumulator): ToolCall = ToolCall(
        id = accumulator.id,
        name = accumulator.name,
        arguments = accumulator.arguments.toString()
    )

    fun snapshot(): AssistantMessage = AssistantMessage(
        content = blocks.map { block ->
            when (block) {
                Block.Text -> TextContent(text)
                Block.Thinking -> ThinkingContent(thinking, thinkingSignature)
                is Block.Tool -> toolCallOf(block.accumulator)
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
        responseModel = responseModel,
        timestamp = timestampMs
    )
}

object OpenAiCompletionsPayload {

    /** Reasoning delta fields some OpenAI-compatible servers use, in preference order. */
    val REASONING_FIELDS = listOf("reasoning_content", "reasoning", "reasoning_text")

    fun buildRequestBody(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
        compat: OpenAiCompletionsCompat = model.compat,
        cacheRetention: CacheRetention = OpenAiResponsesApi.resolveCacheRetention(
            options.cacheRetention,
            options.env
        )
    ): JsonObject {
        val body = mutableMapOf<String, JsonElement>()
        body["model"] = JsonPrimitive(model.id)
        val messages = convertMessages(model, context, compat).toMutableList()
        val cacheControl = getCompatCacheControl(compat, cacheRetention)
        body["stream"] = JsonPrimitive(true)

        val sendPromptCacheKey =
            (model.baseUrl.contains("api.openai.com") && cacheRetention != CacheRetention.NONE) ||
                (cacheRetention == CacheRetention.LONG && compat.supportsLongCacheRetention)
        if (sendPromptCacheKey) {
            clampOpenAIPromptCacheKey(options.sessionId)?.let {
                body["prompt_cache_key"] = JsonPrimitive(it)
            }
        }
        if (cacheRetention == CacheRetention.LONG && compat.supportsLongCacheRetention) {
            body["prompt_cache_retention"] = JsonPrimitive("24h")
        }

        if (compat.supportsUsageInStreaming) {
            body["stream_options"] = buildJsonObject { put("include_usage", true) }
        }
        if (compat.supportsStore) {
            body["store"] = JsonPrimitive(false)
        }

        options.maxTokens?.let { maxTokens ->
            val field = when (compat.maxTokensField) {
                MaxTokensField.MAX_TOKENS -> "max_tokens"
                MaxTokensField.MAX_COMPLETION_TOKENS -> "max_completion_tokens"
            }
            body[field] = JsonPrimitive(maxTokens)
        }
        options.temperature?.let { body["temperature"] = JsonPrimitive(it) }

        val tools: MutableList<JsonObject>? = if (context.tools.isNotEmpty()) {
            // deferredToolsMode "kimi": tools already loaded via the bare-tools
            // system message are excluded from the standard tools param.
            val deferredToolNames =
                if (compat.deferredToolsMode ==
                    DeferredToolsMode.KIMI
                ) {
                    getDeferredToolNames(context.messages)
                } else {
                    emptySet()
                }
            val activeTools = context.tools.filter { it.name !in deferredToolNames }
            if (activeTools.isNotEmpty()) {
                activeTools.map { convertTool(it, compat) }.toMutableList().also {
                    if (compat.zaiToolStream) {
                        body["tool_stream"] = JsonPrimitive(true)
                    }
                }
            } else if (hasToolHistory(context.messages)) {
                // Some proxies require the tools param when history has tool calls.
                mutableListOf()
            } else {
                null
            }
        } else if (hasToolHistory(context.messages)) {
            // Some proxies require the tools param when history has tool calls.
            mutableListOf()
        } else {
            null
        }
        if (cacheControl != null) {
            applyAnthropicCacheControl(messages, tools, cacheControl)
        }
        body["messages"] = JsonArray(messages.toList())
        tools?.let { body["tools"] = JsonArray(it.toList()) }

        mapToolChoice(options.toolChoice)?.let { body["tool_choice"] = it }

        model.compat.vllmPriority?.let { body["priority"] = JsonPrimitive(it) }

        applyThinking(model, options, compat)?.let { body.putAll(it) }

        options.samplingParams?.let { body.putAll(it) }

        return JsonObject(body)
    }

    private fun mapToolChoice(choice: ToolChoice?): JsonElement? = when (choice) {
        null -> null

        ToolChoice.Auto -> JsonPrimitive("auto")

        ToolChoice.None -> JsonPrimitive("none")

        ToolChoice.Any, ToolChoice.Required -> JsonPrimitive("required")

        is ToolChoice.Function -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", choice.name) })
        }
    }

    private fun applyThinking(
        model: Model,
        options: OpenAiCompletionsOptions,
        compat: OpenAiCompletionsCompat
    ): Map<String, JsonElement>? {
        if (!model.reasoning) return null
        // Direct OFF never enables reasoning; it is equivalent to no effort.
        val effort = options.reasoningEffort?.takeIf { it != ModelThinkingLevel.OFF }
        val map = model.thinkingLevelMap

        /**
         * Unspecified passes the level through; an explicit null entry omits
         * the field.
         *
         * Divergence (unreachable via [streamSimple]): in pi's zai/baseten
         * branches an explicit null omits the field (typeof check), matching
         * this behavior — but its qwen/deepseek/together/openrouter/plain
         * branches fall back to the raw level name (`map[level] ?? level`).
         * Those branches can only see an explicitly-null level through a
         * direct `stream()` call here, because [streamSimple] clamps through
         * `getSupportedThinkingLevels`, which never selects an explicit-null
         * level; a direct caller passing one diverges (field omitted instead
         * of the raw level name).
         */
        fun mappedEffort(level: ModelThinkingLevel): String? {
            if (map == null) return level.name.lowercase()
            return if (map.isSpecified(level)) map.forLevel(level) else level.name.lowercase()
        }

        /** True only for an explicit null OFF entry in the level map. */
        val explicitNullOff = map?.isSpecified(ModelThinkingLevel.OFF) == true &&
            map.forLevel(ModelThinkingLevel.OFF) == null

        fun effortParam(): Pair<String, JsonElement>? =
            mappedEffort(effort!!)?.let { "reasoning_effort" to JsonPrimitive(it) }

        when (compat.thinkingFormat) {
            ThinkingFormat.ZAI -> {
                val thinking = if (effort != null) {
                    buildJsonObject {
                        put("type", "enabled")
                        put("clear_thinking", false)
                    }
                } else {
                    buildJsonObject { put("type", "disabled") }
                }
                val params = mutableListOf<Pair<String, JsonElement>>("thinking" to thinking)
                if (effort != null && compat.supportsReasoningEffort) {
                    effortParam()?.let { params.add(it) }
                }
                return params.toMap()
            }

            ThinkingFormat.QWEN -> {
                val params =
                    mutableListOf<Pair<String, JsonElement>>(
                        "enable_thinking" to JsonPrimitive(effort != null)
                    )
                if (effort != null && compat.supportsReasoningEffort) {
                    effortParam()?.let { params.add(it) }
                }
                return params.toMap()
            }

            ThinkingFormat.DEEPSEEK -> {
                val params = mutableListOf<Pair<String, JsonElement>>()
                val thinking = if (effort != null) {
                    "enabled"
                } else if (!explicitNullOff) {
                    "disabled"
                } else {
                    null
                }
                thinking?.let { params.add("thinking" to buildJsonObject { put("type", it) }) }
                if (effort != null && compat.supportsReasoningEffort) {
                    effortParam()?.let { params.add(it) }
                }
                return params.toMap()
            }

            ThinkingFormat.OPENROUTER -> {
                val offEffort = map?.takeIf { it.isSpecified(ModelThinkingLevel.OFF) }
                    ?.forLevel(ModelThinkingLevel.OFF)
                val effortValue = when {
                    effort != null -> mappedEffort(effort)
                    !explicitNullOff -> offEffort ?: "none"
                    else -> null
                }
                return effortValue?.let {
                    mapOf("reasoning" to buildJsonObject { put("effort", it) })
                }
            }

            ThinkingFormat.TOGETHER -> {
                val params = mutableListOf<Pair<String, JsonElement>>(
                    "reasoning" to buildJsonObject { put("enabled", effort != null) }
                )
                if (effort != null && compat.supportsReasoningEffort) {
                    effortParam()?.let { params.add(it) }
                }
                return params.toMap()
            }

            ThinkingFormat.ANT_LING -> {
                if (effort == null) return null
                // Requires an explicitly mapped string; no level-name fallback.
                val mapped = map?.takeIf { it.isSpecified(effort) }?.forLevel(effort)
                return mapped?.let { mapOf("reasoning" to buildJsonObject { put("effort", it) }) }
            }

            ThinkingFormat.BASETEN -> {
                val params = mutableListOf<Pair<String, JsonElement>>()
                buildChatTemplateValues(compat, effort, map)?.let {
                    params.add("chat_template_args" to it)
                }
                if (compat.supportsReasoningEffort) {
                    // With null effort, only the mapped OFF entry is sent; no fallback.
                    val value = if (effort != null) {
                        mappedEffort(effort)
                    } else {
                        map?.takeIf { it.isSpecified(ModelThinkingLevel.OFF) }
                            ?.forLevel(ModelThinkingLevel.OFF)
                    }
                    value?.let { params.add("reasoning_effort" to JsonPrimitive(it)) }
                }
                return params.toMap()
            }

            ThinkingFormat.OPENAI -> {
                if (!compat.supportsReasoningEffort) return null
                if (effort != null) {
                    return effortParam()?.let { mapOf(it) }
                }
                // Only an explicitly mapped non-null off value is sent.
                val off = if (map?.isSpecified(ModelThinkingLevel.OFF) == true) {
                    map.forLevel(ModelThinkingLevel.OFF)
                } else {
                    null
                }
                return off?.let { mapOf("reasoning_effort" to JsonPrimitive(it)) }
            }
        }
    }

    private fun buildChatTemplateValues(
        compat: OpenAiCompletionsCompat,
        effort: ModelThinkingLevel?,
        map: ThinkingLevelMap?
    ): JsonObject? {
        fun resolve(value: ChatTemplateKwargValue): JsonElement? = when (value) {
            is ChatTemplateKwargValue.Scalar -> value.value

            is ChatTemplateKwargValue.Ref -> {
                if (effort == null && value.omitWhenOff) return@resolve null
                when (value.varName) {
                    "thinking.enabled" -> JsonPrimitive(effort != null)

                    "thinking.budget" -> null

                    // thinking budgets unsupported here
                    "thinking.effort" -> {
                        if (effort != null) {
                            // Explicit null mapping omits; unspecified falls back to the level name.
                            val mapped = if (map?.isSpecified(effort) == true) {
                                map.forLevel(effort)
                            } else {
                                effort.name.lowercase()
                            }
                            mapped?.let { JsonPrimitive(it) }
                        } else {
                            // No effort: only an explicitly mapped OFF string is sent.
                            map?.takeIf { it.isSpecified(ModelThinkingLevel.OFF) }
                                ?.forLevel(ModelThinkingLevel.OFF)
                                ?.let { JsonPrimitive(it) as JsonElement }
                        }
                    }

                    else -> null
                }
            }
        }

        val resolved = compat.chatTemplateArgs.mapNotNull { (k, v) ->
            resolve(v)?.let { k to it }
        }
        return resolved.toMap().takeIf { it.isNotEmpty() }?.let { JsonObject(it) }
    }

    internal data class OpenAiCompatCacheControl(val type: String, val ttl: String?) {
        fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            ttl?.let { put("ttl", it) }
        }
    }

    internal fun getCompatCacheControl(
        compat: OpenAiCompletionsCompat,
        cacheRetention: CacheRetention
    ): OpenAiCompatCacheControl? {
        if (compat.cacheControlFormat != CacheControlFormat.ANTHROPIC ||
            cacheRetention == CacheRetention.NONE
        ) {
            return null
        }
        val ttl = if (cacheRetention == CacheRetention.LONG &&
            compat.supportsLongCacheRetention
        ) {
            "1h"
        } else {
            null
        }
        return OpenAiCompatCacheControl(type = "ephemeral", ttl = ttl)
    }

    /**
     * pi mutates the converted payload objects in place; the immutable JSON
     * values are rebuilt here instead.
     */
    internal fun applyAnthropicCacheControl(
        messages: MutableList<JsonObject>,
        tools: MutableList<JsonObject>?,
        cacheControl: OpenAiCompatCacheControl
    ) {
        addCacheControlToSystemPrompt(messages, cacheControl)
        addCacheControlToLastTool(tools, cacheControl)
        addCacheControlToLastConversationMessage(messages, cacheControl)
    }

    /** Only the first system/developer message is considered; no fallback when it has no markable text. */
    private fun addCacheControlToSystemPrompt(
        messages: MutableList<JsonObject>,
        cacheControl: OpenAiCompatCacheControl
    ) {
        val index = messages.indexOfFirst { message ->
            message.str("role") == "system" || message.str("role") == "developer"
        }
        if (index >= 0) {
            addCacheControlToTextContent(messages, index, cacheControl)
        }
    }

    private fun addCacheControlToLastConversationMessage(
        messages: MutableList<JsonObject>,
        cacheControl: OpenAiCompatCacheControl
    ) {
        for (i in messages.indices.reversed()) {
            val role = messages[i].str("role")
            if (role == "user" || role == "assistant" || role == "tool") {
                if (addCacheControlToTextContent(messages, i, cacheControl)) {
                    return
                }
            }
        }
    }

    private fun addCacheControlToLastTool(
        tools: MutableList<JsonObject>?,
        cacheControl: OpenAiCompatCacheControl
    ) {
        if (tools.isNullOrEmpty()) return
        tools[tools.size - 1] =
            JsonObject(tools.last() + ("cache_control" to cacheControl.toJson()))
    }

    /**
     * Anthropic cache_control attaches to text content parts, not messages:
     * string content is rewritten to a single text part, array content is
     * marked on its last text part, and empty/absent text is not markable.
     */
    private fun addCacheControlToTextContent(
        messages: MutableList<JsonObject>,
        index: Int,
        cacheControl: OpenAiCompatCacheControl
    ): Boolean {
        val message = messages[index]
        return when (val content = message["content"] ?: return false) {
            is JsonPrimitive -> {
                val text = content.contentOrNull
                if (text == null || text.isEmpty()) return false
                messages[index] = JsonObject(
                    message + (
                        "content" to JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                    put("cache_control", cacheControl.toJson())
                                }
                            )
                        )
                        )
                )
                true
            }

            is JsonArray -> {
                for (j in content.indices.reversed()) {
                    val part = content[j]
                    if (part is JsonObject && part.str("type") == "text") {
                        val newContent = content.toMutableList().also {
                            it[j] = JsonObject(part + ("cache_control" to cacheControl.toJson()))
                        }
                        messages[index] = JsonObject(message + ("content" to JsonArray(newContent)))
                        return true
                    }
                }
                false
            }

            else -> false
        }
    }

    fun convertMessages(
        model: Model,
        context: Context,
        compat: OpenAiCompletionsCompat = model.compat
    ): List<JsonObject> {
        val params = mutableListOf<JsonObject>()

        if (!context.systemPrompt.isNullOrEmpty()) {
            val role = if (model.reasoning &&
                compat.supportsDeveloperRole
            ) {
                "developer"
            } else {
                "system"
            }
            params.add(
                buildJsonObject {
                    put("role", role)
                    put("content", sanitizeSurrogates(context.systemPrompt))
                }
            )
        }

        val messages =
            transformMessages(context.messages, model) { id, _ ->
                normalizeToolCallId(id, model.provider)
            }
        val deferredToolNames = mutableSetOf<String>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            when (msg.role) {
                MessageRole.USER ->
                    convertUserMessage(msg as works.resolve.pathfinder.ai.UserMessage)
                        ?.let { params.add(it) }

                MessageRole.ASSISTANT ->
                    convertAssistantMessage(
                        model,
                        msg as works.resolve.pathfinder.ai.AssistantMessage,
                        compat
                    )
                        ?.let { params.add(it) }

                MessageRole.TOOL_RESULT -> {
                    var j = i
                    val imageParts = mutableListOf<JsonElement>()
                    val supportsImage = model.input.contains(InputModality.IMAGE)
                    while (j < messages.size && messages[j].role == MessageRole.TOOL_RESULT) {
                        val toolMsg = messages[j] as works.resolve.pathfinder.ai.ToolResultMessage
                        val textResult = toolMsg.content
                            .filter { it.type == ContentType.TEXT }
                            .joinToString("\n") {
                                (it as works.resolve.pathfinder.ai.TextContent).text
                            }
                        val hasImages = toolMsg.content.any { it.type == ContentType.IMAGE }
                        val toolResultText = when {
                            textResult.isNotEmpty() -> textResult
                            hasImages -> "(see attached image)"
                            else -> "(no tool output)"
                        }
                        val toolMessage = mutableMapOf<String, JsonElement>(
                            "role" to JsonPrimitive("tool"),
                            "content" to JsonPrimitive(sanitizeSurrogates(toolResultText)),
                            "tool_call_id" to JsonPrimitive(toolMsg.toolCallId)
                        )
                        if (compat.requiresToolResultName && toolMsg.toolName.isNotEmpty()) {
                            toolMessage["name"] = JsonPrimitive(toolMsg.toolName)
                        }
                        params.add(JsonObject(toolMessage))
                        // deferredToolsMode "kimi": tool results mark the
                        // tools they loaded; those are re-announced as a bare
                        // `tools` system message after the group.
                        if (compat.deferredToolsMode == DeferredToolsMode.KIMI) {
                            deferredToolNames.addAll(toolMsg.addedToolNames)
                        }
                        if (supportsImage) {
                            toolMsg.content
                                .filter { it.type == ContentType.IMAGE }
                                .map { it as works.resolve.pathfinder.ai.ImageContent }
                                .forEach { imageParts.add(imagePart(it)) }
                        }
                        j++
                    }
                    i = j - 1
                    if (imageParts.isNotEmpty()) {
                        params.add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", "Attached image(s) from tool result:")
                                            }
                                        )
                                        imageParts.forEach { add(it) }
                                    }
                                )
                            }
                        )
                    }
                    if (deferredToolNames.isNotEmpty()) {
                        // Kimi accepts a system message with a bare `tools`
                        // array and no content.
                        val deferredTools = getToolsByName(context.tools, deferredToolNames)
                        if (deferredTools.isNotEmpty()) {
                            params.add(
                                buildJsonObject {
                                    put("role", "system")
                                    put(
                                        "tools",
                                        JsonArray(
                                            deferredTools.map {
                                                convertTool(it, compat)
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
            i++
        }
        return params
    }

    /**
     * Splits pipe-separated ids coming from Responses-style providers
     * (`{call_id}|{item_id}`), where item ids can be 400+ chars of special
     * chars, and recombines them as `{callId}_{itemId}` so multiple tool
     * calls sharing a call_id stay unique. Results longer than 40 chars (the
     * OpenAI limit) are truncated with a hash suffix. Plain ids are truncated
     * to 40 chars only for provider "openai".
     */
    private fun normalizeToolCallId(id: String, provider: String): String {
        if ("|" in id) {
            val separatorIndex = id.indexOf("|")
            val callId = id.substring(0, separatorIndex).replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val itemId = id.substring(separatorIndex + 1).replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val combinedId = if (itemId.isNotEmpty()) "${callId}_$itemId" else callId
            if (combinedId.length <= 40) return combinedId
            val hash = shortHash(id).take(8)
            val prefix = callId.take(maxOf(1, 40 - hash.length - 1))
            return "${prefix}_$hash"
        }

        if (provider == "openai") return if (id.length > 40) id.take(40) else id
        return id
    }

    private fun convertUserMessage(msg: works.resolve.pathfinder.ai.UserMessage): JsonObject? {
        if (msg.content.isEmpty()) return null
        return buildJsonObject {
            put("role", "user")
            val text = sanitizeSurrogates(
                msg.content.filter { it.type == ContentType.TEXT }
                    .joinToString("") { (it as works.resolve.pathfinder.ai.TextContent).text }
            )
            val images = msg.content.filter { it.type == ContentType.IMAGE }
            if (images.isEmpty()) {
                put("content", text)
            } else {
                put(
                    "content",
                    buildJsonArray {
                        if (text.isNotEmpty()) {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                }
                            )
                        }
                        images.forEach {
                            add(imagePart(it as works.resolve.pathfinder.ai.ImageContent))
                        }
                    }
                )
            }
        }
    }

    private fun imagePart(image: works.resolve.pathfinder.ai.ImageContent): JsonObject =
        buildJsonObject {
            put("type", "image_url")
            put(
                "image_url",
                buildJsonObject {
                    put("url", "data:${image.mimeType};base64,${image.data}")
                }
            )
        }

    /** Returns null for assistant messages with no content and no tool calls. */
    private fun convertAssistantMessage(
        model: Model,
        msg: works.resolve.pathfinder.ai.AssistantMessage,
        compat: OpenAiCompletionsCompat
    ): JsonObject? {
        val assistant = mutableMapOf<String, JsonElement>()

        val text = sanitizeSurrogates(
            msg.content.filter { it.type == ContentType.TEXT }
                .map { (it as works.resolve.pathfinder.ai.TextContent).text }
                .filter { it.isNotBlank() }
                .joinToString("")
        )

        val thinkingBlocks = msg.content.filter { it.type == ContentType.THINKING }
            .map { it as works.resolve.pathfinder.ai.ThinkingContent }
        val nonEmptyThinking = thinkingBlocks.filter { it.thinking.isNotBlank() }
        val toolCalls = msg.content.filter { it.type == ContentType.TOOL_CALL }
            .map { it as works.resolve.pathfinder.ai.ToolCall }

        val signedReasoningDetails = thinkingBlocks.firstNotNullOfOrNull {
            parseOpenAIReasoningDetails(it.thinkingSignature)
        }
        val legacyReasoningDetails = toolCalls.mapNotNull {
            parseLegacyEncryptedReasoningDetail(it.thoughtSignature)
        }
        val preservedReasoningDetails: JsonElement? =
            signedReasoningDetails
                ?: legacyReasoningDetails.takeIf { it.isNotEmpty() }?.let { JsonArray(it) }

        if (compat.requiresThinkingAsText && nonEmptyThinking.isNotEmpty()) {
            val thinkingText =
                sanitizeSurrogates(nonEmptyThinking.joinToString("\n\n") { it.thinking })
            val parts = buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", thinkingText)
                    }
                )
                if (text.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    )
                }
            }
            assistant["content"] = parts
        } else if (text.isNotEmpty()) {
            // Plain string content is the standard format; block arrays make
            // some models mirror the structure literally.
            assistant["content"] = JsonPrimitive(text)
        } else {
            // Some providers reject null content; absent content is only valid
            // alongside tool calls.
        }

        // The raw reasoning field is replayed only when no structured
        // reasoning_details were preserved.
        if (preservedReasoningDetails == null &&
            !compat.requiresThinkingAsText && nonEmptyThinking.isNotEmpty()
        ) {
            // opencode-go accepts "reasoning_content", not the stored
            // "reasoning" signature field.
            var signature = nonEmptyThinking.first().thinkingSignature
            if (model.provider == "opencode-go" && signature == "reasoning") {
                signature = "reasoning_content"
            }
            if (signature != null && signature in REASONING_FIELDS) {
                // Replayed unsanitized, for exact parity with pi; only
                // requiresThinkingAsText output is sanitized.
                assistant[signature] =
                    JsonPrimitive(nonEmptyThinking.joinToString("\n") { it.thinking })
            }
        }

        if (toolCalls.isNotEmpty()) {
            assistant["tool_calls"] = JsonArray(
                toolCalls.map { call ->
                    buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            }
                        )
                    }
                }
            )
        }

        preservedReasoningDetails?.let { assistant["reasoning_details"] = it }

        // DeepSeek-style endpoints reject replayed assistant messages without
        // reasoning_content when the model reasons; send an empty string.
        if (compat.requiresReasoningContentOnAssistantMessages &&
            model.reasoning &&
            !assistant.containsKey("reasoning_content")
        ) {
            assistant["reasoning_content"] = JsonPrimitive("")
        }

        // Content here is always either a primitive string we set above or a
        // block array, never JSON null, so the lenient read is equivalent.
        val hasContent = assistant["content"].strOrNull()?.isNotEmpty() == true ||
            assistant["content"] is JsonArray
        if (!hasContent && !assistant.containsKey("tool_calls")) {
            return null
        }
        return JsonObject(mapOf("role" to JsonPrimitive("assistant")) + assistant)
    }

    /**
     * pi's supportsStrictMode is tri-state (`!== false`, undefined means
     * supported); [OpenAiCompletionsCompat.supportsStrictMode] is a non-null
     * Boolean defaulting to true, so it is passed through directly.
     */
    private fun convertTool(tool: Tool, compat: OpenAiCompletionsCompat): JsonObject {
        val strict = resolveJsonSchemaStrictSampling(tool, compat.supportsStrictMode)
        return buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", getJsonSchemaToolParameters(tool, strict))
                    // Some providers reject unknown fields.
                    if (compat.supportsStrictMode) put("strict", strict ?: false)
                }
            )
        }
    }

    private fun getDeferredToolNames(messages: List<Message>): Set<String> = messages.flatMap {
        (it as? works.resolve.pathfinder.ai.ToolResultMessage)?.addedToolNames.orEmpty()
    }
        .toSet()

    private fun getToolsByName(tools: List<Tool>, names: Collection<String>): List<Tool> {
        val byName = tools.associateBy { it.name }
        return names.mapNotNull { byName[it] }
    }

    private fun hasToolHistory(messages: List<Message>): Boolean = messages.any { msg ->
        msg.role == MessageRole.TOOL_RESULT ||
            (msg as? works.resolve.pathfinder.ai.AssistantMessage)?.content?.any {
                it.type ==
                    ContentType.TOOL_CALL
            } ==
            true
    }
}
