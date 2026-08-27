package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.calculateCost
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Streaming engine for the Google Generative AI adapter. This is the loop
 * body of pi's google-generative-ai.ts `stream` function; Pathfinder keeps it
 * as one object parameterized by the request plan.
 *
 * The wire protocol is the Generative Language
 * `streamGenerateContent?alt=sse` REST streaming shape the `@google/genai`
 * SDK drives upstream: each SSE data payload is a GenerateContentResponse
 * JSON object with `candidates[0].content.parts[]`, `finishReason`,
 * `usageMetadata`, and `responseId`.
 *
 * Abort semantics divergence: pi checks `options.signal.aborted` after the
 * loop and emits an `aborted` error event; the Kotlin core has no AbortSignal,
 * so cancellation propagates through coroutine cancellation and no Error
 * event is produced (per the core's Events contract).
 */
internal object GoogleStreamEngine {

    /** Module-level tool call ID counter, pi's `toolCallCounter`. */
    private val toolCallCounter = AtomicLong()

    /** A fully shaped HTTP request plan built by a Google adapter. */
    data class Plan(
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val timeoutMs: Long? = null,
        val maxRetries: Int = 0,
        val maxRetryDelayMs: Long = works.resolve.pathfinder.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    ) {
        override fun toString(): String =
            "Plan(url=$url, headers=${headers.filterKeys { !it.equals("x-goog-api-key", true) }.keys}" +
                ", body=<${body.size} bytes>, timeoutMs=$timeoutMs, maxRetries=$maxRetries)"
    }

    fun stream(
        transport: HttpStreamingTransport,
        retry: ProviderRetry,
        nowMs: () -> Long,
        model: Model,
        plan: Plan,
    ): Flow<AssistantMessageEvent> = flow {
        val state = State(model, nowMs())
        try {
            // Retries only cover failures before SSE content begins, pi's
            // retryGoogleRequest around generateContentStream.
            val response = retry.retryProviderRequest<TransportResponse>(
                plan.maxRetries,
                plan.maxRetryDelayMs,
            ) {
                transport.post(
                    TransportRequest(
                        url = plan.url,
                        bearerToken = null,
                        headers = plan.headers,
                        body = plan.body,
                        timeoutMs = plan.timeoutMs,
                    ),
                )
            }

            emit(AssistantMessageEvent.Start(state.snapshot()))

            response.events.collect { event ->
                for (toEmit in state.processChunk(event)) emit(toEmit)
            }
            state.closeOpenBlock()?.let { emit(it) }

            if (state.stopReason == StopReason.PENDING) {
                throw ProviderStreamException("Google stream ended without a finish reason")
            }
            if (state.stopReason == StopReason.ABORTED || state.stopReason == StopReason.ERROR) {
                val errorMessage = state.rawStopReason
                    ?.let { "Provider stopped with: $it" }
                    ?: "An unknown error occurred"
                throw ProviderStreamException(errorMessage)
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

    /** pi's module-level tool call id generator: `name_${Date.now()}_${++counter}`. */
    fun nextToolCallId(name: String, nowMs: Long): String =
        "${name}_${nowMs}_${toolCallCounter.incrementAndGet()}"

    /**
     * Port of pi's google-generative-ai.ts catch block (~288). Upstream
     * surfaces the `@google/genai` SDK's `error.message`, which already
     * carries the body, so the shared formatter keeps the message; the raw
     * transport body is the port's stand-in, so the output is the composed
     * `"<status>: <body>"` (no prefix upstream).
     */
    private fun formatProviderError(error: Exception): String = when (error) {
        is ProviderHttpException -> formatProviderError(normalizeProviderError(error))
        // Non-HTTP exceptions keep the port's `message ?: simpleName` handling;
        // pi's safeJsonStringify fallback for non-Error throws is moot in Kotlin.
        is ProviderStreamException -> error.message ?: "Provider stream error"
        else -> error.message ?: error::class.simpleName ?: "Unknown error"
    }

    /**
     * Accumulates the streamed output, producing pi's exact event ordering:
     * text/thinking blocks are closed and reopened on type transitions,
     * functionCall parts close any open block and emit a complete tool call
     * (start, delta of the raw args JSON, end).
     */
    private class State(private val model: Model, private val timestampMs: Long) {
        private val content = mutableListOf<Content>()
        private var currentText: StringBuilder? = null
        private var currentTextSignature: String? = null
        private var currentThinking: StringBuilder? = null
        private var currentThinkingSignature: String? = null

        var usage: Usage = Usage()
        var stopReason: StopReason = StopReason.PENDING
        var rawStopReason: String? = null
        var responseId: String? = null

        private fun blockIndex(): Int = content.size - 1

        fun snapshot(): AssistantMessage = AssistantMessage(
            content = snapshotContent(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = usage,
            stopReason = stopReason,
            rawStopReason = rawStopReason,
            responseId = responseId,
            timestamp = timestampMs,
        )

        private fun snapshotContent(): List<Content> {
            val blocks = content.toMutableList()
            openBlockSnapshot()?.let { blocks[blocks.size - 1] = it }
            return blocks
        }

        /** The current open block as it stands mid-stream, if any. */
        private fun openBlockSnapshot(): Content? {
            val text = currentText
            if (text != null) {
                content[content.size - 1] = TextContent(text.toString(), currentTextSignature)
                return content[content.size - 1]
            }
            val thinking = currentThinking
            if (thinking != null) {
                content[content.size - 1] = ThinkingContent(thinking.toString(), currentThinkingSignature)
                return content[content.size - 1]
            }
            return null
        }

        fun processChunk(event: SseEvent): List<AssistantMessageEvent> {
            val events = mutableListOf<AssistantMessageEvent>()
            val chunk = try {
                GoogleShared.json.parseToJsonElement(event.data)
            } catch (error: Exception) {
                throw ProviderStreamException(
                    "Malformed SSE JSON payload: ${error.message ?: error::class.simpleName}",
                )
            }
            if (chunk !is JsonObject) {
                throw ProviderStreamException("Malformed SSE JSON payload: expected a JSON object")
            }

            // @google/genai documents responseId as an output-only identifier;
            // keep the first non-empty one from the stream.
            if (responseId.isNullOrEmpty()) {
                responseId = chunk["responseId"].stringOrNull()?.takeIf { it.isNotEmpty() }
            }

            val candidate = (chunk["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            for (part in ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray)
                ?.filterIsInstance<JsonObject>() ?: emptyList()) {
                events += processPart(part)
            }

            candidate?.get("finishReason").stringOrNull()?.let { reason ->
                rawStopReason = reason
                stopReason = GoogleShared.mapStopReason(reason)
                if (content.any { it is ToolCall } && stopReason == StopReason.STOP) {
                    stopReason = StopReason.TOOL_USE
                }
            }

            (chunk["usageMetadata"] as? JsonObject)?.let { meta ->                val promptTokens = meta.intOrZero("promptTokenCount")
                val cachedTokens = meta.intOrZero("cachedContentTokenCount")
                val candidatesTokens = meta.intOrZero("candidatesTokenCount")
                val thoughtsTokens = meta.intOrZero("thoughtsTokenCount")
                val newUsage = Usage(
                    input = promptTokens - cachedTokens,
                    output = candidatesTokens + thoughtsTokens,
                    cacheRead = cachedTokens,
                    cacheWrite = 0,
                    reasoning = thoughtsTokens,
                    totalTokens = meta.intOrZero("totalTokenCount"),
                )
                usage = newUsage.copy(cost = calculateCost(model, newUsage))
            }

            return events
        }

        private fun processPart(part: JsonObject): List<AssistantMessageEvent> {
            val events = mutableListOf<AssistantMessageEvent>()
            val text = part["text"].stringOrNull()

            if (text != null) {
                val isThinking = GoogleShared.isThinkingPart(part)
                if (currentText == null && currentThinking == null ||
                    (isThinking && currentThinking == null) ||
                    (!isThinking && currentText == null)
                ) {
                    closeOpenBlock()?.let { events.add(it) }
                    if (isThinking) {
                        currentThinking = StringBuilder()
                        currentThinkingSignature = null
                        content.add(ThinkingContent(""))
                        events.add(AssistantMessageEvent.ThinkingStart(blockIndex(), snapshot()))
                    } else {
                        currentText = StringBuilder()
                        currentTextSignature = null
                        content.add(TextContent(""))
                        events.add(AssistantMessageEvent.TextStart(blockIndex(), snapshot()))
                    }
                }
                if (isThinking) {
                    currentThinking!! .append(text)
                    currentThinkingSignature = GoogleShared.retainThoughtSignature(
                        currentThinkingSignature,
                        part["thoughtSignature"].stringOrNull(),
                    )
                    events.add(AssistantMessageEvent.ThinkingDelta(blockIndex(), text, snapshot()))
                } else {
                    currentText!!.append(text)
                    currentTextSignature = GoogleShared.retainThoughtSignature(
                        currentTextSignature,
                        part["thoughtSignature"].stringOrNull(),
                    )
                    events.add(AssistantMessageEvent.TextDelta(blockIndex(), text, snapshot()))
                }
            }

            val functionCall = part["functionCall"] as? JsonObject
            if (functionCall != null) {
                closeOpenBlock()?.let { events.add(it) }

                val args = functionCall["args"] as? JsonObject ?: JsonObject(emptyMap())
                val name = functionCall["name"].stringOrNull() ?: ""
                val providedId = functionCall["id"].stringOrNull()
                val needsNewId = providedId.isNullOrEmpty() ||
                    content.any { it is ToolCall && it.id == providedId }
                val toolCallId = if (needsNewId) {
                    nextToolCallId(name, timestampMs)
                } else {
                    providedId!!
                }

                val toolCall = ToolCall(
                    id = toolCallId,
                    name = name,
                    arguments = args.toString(),
                    thoughtSignature = part["thoughtSignature"].stringOrNull()
                        ?.takeIf { it.isNotEmpty() },
                )
                content.add(toolCall)
                events.add(AssistantMessageEvent.ToolCallStart(blockIndex(), snapshot()))
                events.add(AssistantMessageEvent.ToolCallDelta(blockIndex(), args.toString(), snapshot()))
                events.add(AssistantMessageEvent.ToolCallEnd(blockIndex(), toolCall, snapshot()))
            }

            return events
        }

        /** Closes the currently open text/thinking block, if any. */
        fun closeOpenBlock(): AssistantMessageEvent? {
            val text = currentText
            if (text != null) {
                val finished = TextContent(text.toString(), currentTextSignature)
                content[content.size - 1] = finished
                currentText = null
                currentTextSignature = null
                return AssistantMessageEvent.TextEnd(blockIndex(), finished.text, snapshot())
            }
            val thinking = currentThinking
            if (thinking != null) {
                val finished = ThinkingContent(thinking.toString(), currentThinkingSignature)
                content[content.size - 1] = finished
                currentThinking = null
                currentThinkingSignature = null
                return AssistantMessageEvent.ThinkingEnd(blockIndex(), finished.thinking, snapshot())
            }
            return null
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.intOrZero(key: String): Int {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return 0
    return runCatching { primitive.content.toInt() }.getOrElse { 0 }
}
