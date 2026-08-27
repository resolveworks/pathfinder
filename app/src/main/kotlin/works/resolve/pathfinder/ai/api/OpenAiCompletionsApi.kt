package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.api.OpenAiCompletionsPayload.REASONING_FIELDS
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.calculateCost
import works.resolve.pathfinder.ai.core.hasHeader
import works.resolve.pathfinder.ai.core.mergeHeaders
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.resolveCloudflareBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OpenAI Chat Completions streaming adapter, ported from pi's
 * openai-completions.ts and reduced to the ZAI-relevant behavior: SSE chunk
 * parsing, streamed text, `reasoning_content`/`reasoning`/`reasoning_text`
 * thinking, fragmented and interleaved tool calls with raw-string argument
 * accumulation, finish reasons, final usage/cached/reasoning accounting with
 * costs, and provider JSON error events.
 *
 * Like pi's stream(), failures after the stream starts are encoded as an
 * [AssistantMessageEvent.Error] carrying the partial message, not thrown.
 * Malformed complete SSE data payloads are protocol errors, never silently
 * ignored.
 */
class OpenAiCompletionsApi(
    private val transport: works.resolve.pathfinder.ai.transport.HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ChatApi {

    /** pi's streamSimple for openai-completions: clamps the thinking level
     * against the model and max tokens against the estimated context before
     * delegating to [stream]. */
    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<AssistantMessageEvent> {
        val clamped = options.reasoning?.let {
            works.resolve.pathfinder.ai.core.clampThinkingLevel(model, ModelThinkingLevel.valueOf(it.name))
        }
        val effort = if (clamped == ModelThinkingLevel.OFF) null else clamped
        val maxTokens = works.resolve.pathfinder.ai.utils.clampMaxTokensToContext(
            model,
            context,
            options.maxTokens ?: model.maxTokens,
        )
        return stream(model, context, options.toStreamOptions(effort).copy(maxTokens = maxTokens))
    }

    /** Internal control-flow signal: stop consuming the body after `[DONE]`. */
    private class DoneSentinel : RuntimeException()

    fun stream(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
    ): Flow<AssistantMessageEvent> = flow {
        // One request-start timestamp shared by every partial/final snapshot.
        val startedAtMs = nowMs()
        val state = StreamingState(model, startedAtMs)
        try {
            // Header-based auth (e.g. Cloudflare's cf-aig-authorization) needs
            // no apiKey; pi's getClientApiKey allows both headers to stand in.
            val hasAuthHeader = hasHeader(options.headers, "authorization") ||
                hasHeader(options.headers, "cf-aig-authorization")
            val apiKey = options.apiKey
                ?: if (hasAuthHeader) null else throw IllegalStateException(
                    "No API key for provider: ${model.provider}",
                )

            val body = OpenAiCompletionsPayload.buildRequestBody(model, context, options)
                .toString()
                .toByteArray(Charsets.UTF_8)

            // Base URL placeholders (Cloudflare account/gateway ids) are
            // substituted from the request-time env, mirroring pi's
            // cloudflare-stream wrapper. Headers merge like pi's
            // openai-completions createClient: model headers first, then the
            // Copilot dynamic headers (github-copilot only; they override
            // model headers), then the merged request/auth headers (explicit
            // requests win), then the
            // always-sent Accept header, which can never be overridden
            // (a null request value cannot remove it).
            val mergedHeaders = mergeHeaders(
                mergeHeaders(
                    mergeHeaders(model.headers, copilotDynamicHeadersFor(model, context)),
                    options.headers,
                ),
                mapOf("Accept" to "text/event-stream"),
            ).filterValues { it != null }.mapValues { it.value!! }
            val url = resolveCloudflareBaseUrl(model.baseUrl, options.env)
                .trimEnd('/') + "/chat/completions"
            val request = TransportRequest(
                url = url,
                bearerToken = apiKey,
                headers = mergedHeaders,
                body = body,
                timeoutMs = options.timeoutMs,
            )

            // Retries only cover failures before SSE content begins; once the
            // response starts the request is never retried.
            val response = retry.retryProviderRequest<TransportResponse>(options.maxRetries, options.maxRetryDelayMs) {
                transport.post(request)
            }

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
                    state.errorMessage ?: "Provider returned an error stop reason",
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
            val finalMessage = state.snapshot().copy(
                stopReason = StopReason.ERROR,
                errorMessage = formatProviderError(error),
            )
            emit(AssistantMessageEvent.Error(finalMessage.stopReason, finalMessage))
        }
    }

    /**
     * Parses one complete SSE data payload strictly; null events ([DONE])
     * mark the stream complete. Returns the block events to emit.
     */
    private fun processSseEvent(
        event: SseEvent,
        model: Model,
        state: StreamingState,
    ): List<AssistantMessageEvent>? {
        if (event.data.trim() == DONE) {
            state.markDone()
            return null
        }
        val chunk = try {
            json.parseToJsonElement(event.data)
        } catch (error: Exception) {
            throw ProviderStreamException(
                "Malformed SSE JSON payload: ${error.message ?: error::class.simpleName}",
            )
        }
        if (chunk !is JsonObject) {
            throw ProviderStreamException("Malformed SSE JSON payload: expected a JSON object")
        }

        // Some providers deliver errors as JSON events mid-stream.
        (chunk["error"] as? JsonObject)?.let { error ->
            throw ProviderStreamException(formatJsonError(error))
        }

        (chunk["id"] as? JsonPrimitive)?.stringOrNull()
            ?.takeIf { it.isNotEmpty() && state.responseId == null }
            ?.let { state.responseId = it }
        (chunk["model"] as? JsonPrimitive)?.stringOrNull()
            ?.takeIf { it.isNotEmpty() && it != model.id && state.responseModel == null }
            ?.let { state.responseModel = it }

        (chunk["usage"] as? JsonObject)?.let { state.usage = parseChunkUsage(it, model) }

        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return emptyList()

        // Fallback: some providers return usage in choice.usage.
        if (chunk["usage"] == null) {
            (choice["usage"] as? JsonObject)?.let { state.usage = parseChunkUsage(it, model) }
        }

        (choice["finish_reason"] as? JsonPrimitive)?.stringOrNull()?.let { raw ->
            state.rawStopReason = raw
            val (stopReason, errorMessage) = mapStopReason(raw)
            state.stopReason = stopReason
            if (errorMessage != null) state.errorMessage = errorMessage
            state.hasFinishReason = true
        }

        val delta = choice["delta"] as? JsonObject ?: return emptyList()

        val events = mutableListOf<AssistantMessageEvent>()
        delta["content"].stringOrNull()?.takeIf { it.isNotEmpty() }?.let { events += state.appendText(it) }

        // Reasoning arrives in reasoning_content (llama.cpp-style), reasoning,
        // or reasoning_text; use the first non-empty field to avoid duplication.
        for (field in REASONING_FIELDS) {
            val value = delta[field].stringOrNull()
            if (!value.isNullOrEmpty()) {
                events += state.appendThinking(value, field)
                break
            }
        }

        (delta["tool_calls"] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { events += state.appendToolCallDelta(it) }
        }
        return events
    }

    private fun parseChunkUsage(raw: JsonObject, model: Model): Usage {
        val promptTokens = raw.intOrZero("prompt_tokens")
        val details = raw.obj("prompt_tokens_details")
        // Nullish fallback: an explicit 0 stays 0; an absent field falls through.
        val cacheReadTokens = details?.intOrNull("cached_tokens")
            ?: raw.intOrNull("prompt_cache_hit_tokens")
            ?: raw.intOrNull("cached_tokens")
            ?: 0
        val cacheWriteTokens = details?.intOrNull("cache_write_tokens") ?: 0
        val outputTokens = raw.intOrZero("completion_tokens")
        val reasoningTokens = raw.obj("completion_tokens_details")?.intOrNull("reasoning_tokens") ?: 0

        // Follow documented semantics: cached_tokens counts cache-read hits;
        // do not subtract writes from it.
        val input = maxOf(0, promptTokens - cacheReadTokens - cacheWriteTokens)
        val usage = Usage(
            input = input,
            output = outputTokens,
            cacheRead = cacheReadTokens,
            cacheWrite = cacheWriteTokens,
            reasoning = reasoningTokens,
            totalTokens = input + outputTokens + cacheReadTokens + cacheWriteTokens,
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
            append("Provider returned HTTP ${error.status}")
            formatErrorBody(error.body)?.let { append(": ").append(it) }
        }
        is ProviderStreamException -> error.message ?: "Provider stream error"
        else -> error.message ?: error::class.simpleName ?: "Unknown error"
    }

    private fun formatErrorBody(body: String): String? {
        if (body.isBlank()) return null
        return try {
            formatJsonError(json.parseToJsonElement(body).let { it as? JsonObject }
                ?: return body.take(500))
        } catch (_: Exception) {
            body.take(500)
        }
    }

    private fun formatJsonError(error: JsonObject): String {
        val message = error["message"].stringOrNull()
        val type = error["type"].stringOrNull()
        val code = error["code"].stringOrNull()
        return listOfNotNull(
            type,
            message ?: error.toString().take(500).ifEmpty { null },
            code?.let { "code: $it" },
        ).joinToString(" — ")
    }

    private companion object {
        const val DONE = "[DONE]"
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.intOrZero(key: String): Int =
    intOrNull(key) ?: 0

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull?.toInt()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private suspend fun kotlinx.coroutines.flow.FlowCollector<AssistantMessageEvent>.emitAll(
    events: List<AssistantMessageEvent>,
) {
    for (event in events) emit(event)
}

/**
 * Accumulates streamed content, producing pi-style block events. Content
 * blocks are immutable values; every [snapshot] builds fresh instances so
 * partial snapshots never share mutable state across events. Streamed
 * `tool_calls[].function.arguments` fragments are accumulated as a raw string
 * per tool-call index/id; strict parsing belongs to tool execution, not this
 * provider layer.
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
        val events = mutableListOf<AssistantMessageEvent>()
        if (thinkingIndex == -1) {
            thinkingIndex = blocks.size
            blocks.add(Block.Thinking)
            events.add(AssistantMessageEvent.ThinkingStart(thinkingIndex, snapshot()))
        }
        thinkingSignature = signature
        thinking += delta
        events.add(AssistantMessageEvent.ThinkingDelta(thinkingIndex, delta, snapshot()))
        return events
    }

    fun appendToolCallDelta(delta: JsonObject): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        val streamIndex = (delta["index"] as? JsonPrimitive)?.longOrNull?.toInt()
        val id = delta["id"].stringOrNull()
        val function = delta["function"] as? JsonObject
        val name = function?.get("name").stringOrNull()

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
        val argDelta = function?.get("arguments").stringOrNull() ?: ""
        accumulator.arguments.append(argDelta)

        events.add(AssistantMessageEvent.ToolCallDelta(blockIndex, argDelta, snapshot()))
        return events
    }

    /** Emits the terminal event for every open block, exactly once per block. */
    fun finish(): List<AssistantMessageEvent> = blocks.mapIndexed { index, block ->
        when (block) {
            Block.Text -> AssistantMessageEvent.TextEnd(index, text, snapshot())
            Block.Thinking -> AssistantMessageEvent.ThinkingEnd(index, thinking, snapshot())
            is Block.Tool -> AssistantMessageEvent.ToolCallEnd(index, toolCallOf(block.accumulator), snapshot())
        }
    }

    private fun toolCallOf(accumulator: ToolCallAccumulator): ToolCall =
        ToolCall(
            id = accumulator.id,
            name = accumulator.name,
            arguments = accumulator.arguments.toString(),
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
        timestamp = timestampMs,
    )
}
