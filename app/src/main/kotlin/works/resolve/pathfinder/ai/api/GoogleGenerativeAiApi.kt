package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.api.GoogleRequest.CommonOptions
import works.resolve.pathfinder.ai.api.GoogleRequest.GoogleThinking
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.ChatApi
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.ProviderResponse
import works.resolve.pathfinder.ai.core.ProviderStreamException
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.SimpleToolChoice
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.mergeHeaders
import works.resolve.pathfinder.ai.core.toModelThinkingLevel
import works.resolve.pathfinder.ai.core.toThinkingLevelOrNull
import works.resolve.pathfinder.ai.models.calculateCost
import works.resolve.pathfinder.ai.models.clampThinkingLevel
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
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
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strOrNull
import works.resolve.pathfinder.telemetry.TelemetryContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Google Generative AI (Gemini API) streaming adapter.
 *
 * Upstream drives the `@google/genai` SDK; Pathfinder implements the same
 * wire protocol directly. A blank [Model.baseUrl] means the SDK default
 * ([DEFAULT_BASE_URL]); a non-blank one already includes the version path
 * (upstream sets `apiVersion: ""` so the SDK does not append one).
 *
 * Divergences from pi (also see [GoogleShared] and [GoogleStreamEngine]):
 * - `options.fetch` has no Kotlin counterpart; requests go through the
 *   injected [HttpStreamingTransport].
 * - pi's streamSimple throws synchronously for a missing API key; here the
 *   failure is a terminal Error event, per the ChatApi contract.
 * - The User-Agent is [getPiUserAgent]; only its platform-string details
 *   differ from pi's.
 */
class GoogleGenerativeAiApi(
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val clock: Clock = Clock.System,
) : ChatApi {

    data class GoogleOptions(
        val apiKey: String? = null,
        val sessionId: String? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val timeoutMs: Long? = null,
        val maxRetries: Int = 0,
        val maxRetryDelayMs: Long = works.resolve.pathfinder.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
        val env: Map<String, String> = emptyMap(),
        val headers: Map<String, String?> = emptyMap(),
        /** "auto" | "none" | "any". */
        val toolChoice: String? = null,
        val thinking: GoogleThinking? = null,
        /**
         * Request hook: may return a replacement for the outgoing payload.
         * Divergence: upstream's hook receives the `@google/genai` SDK's
         * GenerateContentParameters; here it receives the wire-format JSON
         * payload this port builds. Receives full message content —
         * installers must not log it. Never included in toString().
         */
        val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
        /**
         * Carried for shape fidelity: pi inherits this hook from StreamOptions
         * but the Google adapter never invokes it. Never included in
         * toString().
         */
        val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
        /**
         * Explicit parent telemetry context for this request. Dormant in this
         * port — carried for shape fidelity.
         */
        val telemetryContext: TelemetryContext? = null,
    ) {
        override fun toString(): String = CommonOptions(
            apiKey, sessionId, temperature, maxTokens, timeoutMs, maxRetries, maxRetryDelayMs,
            env, headers, toolChoice, thinking,
        ).toString().dropLast(1) +
            ", onPayload=${onPayload != null}, onResponse=${onResponse != null}" +
            ", telemetryContext=${telemetryContext != null})"

        internal fun toCommon() = CommonOptions(
            apiKey, sessionId, temperature, maxTokens, timeoutMs, maxRetries, maxRetryDelayMs,
            env, headers, toolChoice, thinking,
        )
    }

    fun stream(
        model: Model,
        context: Context,
        options: GoogleOptions,
    ): Flow<works.resolve.pathfinder.ai.core.AssistantMessageEvent> {
        val apiKey = options.apiKey
            ?: return missingApiKeyFlow(model)

        val body = GoogleRequest.buildGenerateContentRequest(model, context, options.toCommon(), gemmaSupported = true)

        val baseUrl = model.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val url = "$baseUrl/models/${model.id}:streamGenerateContent?alt=sse"

        val headers = mergeHeaders(
            mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), model.headers),
            options.headers,
        ).filterValues { it != null }
            .mapValues { it.value!! } + mapOf("x-goog-api-key" to apiKey)

        // onPayload is suspend, so the plan is built inside the flow, at
        // collection time.
        return kotlinx.coroutines.flow.flow {
            emitAll(
                GoogleStreamEngine.stream(
                    transport,
                    retry,
                    clock,
                    model,
                    GoogleStreamEngine.Plan(
                        url = url,
                        headers = headers,
                        body = (options.onPayload?.let { it(body, model) } ?: body)
                            .toString().toByteArray(Charsets.UTF_8),
                        timeoutMs = options.timeoutMs,
                        maxRetries = options.maxRetries,
                        maxRetryDelayMs = options.maxRetryDelayMs,
                    ),
                ),
            )
        }
    }

    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<works.resolve.pathfinder.ai.core.AssistantMessageEvent> =
        stream(model, context, buildGoogleOptions(model, context, options))

    private companion object {
        /** The `@google/genai` SDK default endpoint for the Gemini API. */
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    private fun missingApiKeyFlow(model: Model) = kotlinx.coroutines.flow.flow {
        val message = works.resolve.pathfinder.ai.core.AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = works.resolve.pathfinder.ai.core.StopReason.ERROR,
            errorMessage = "No API key for provider: ${model.provider}",
            timestamp = clock.now().toEpochMilliseconds(),
        )
        emit(
            works.resolve.pathfinder.ai.core.AssistantMessageEvent.Error(
                works.resolve.pathfinder.ai.core.StopReason.ERROR,
                message,
            ),
        )
    }
}

/**
 * Streaming engine for the Google Generative AI adapter. Each SSE data
 * payload is a GenerateContentResponse JSON object with
 * `candidates[0].content.parts[]`, `finishReason`, `usageMetadata`, and
 * `responseId`.
 *
 * Divergence from pi: pi checks `options.signal.aborted` after the loop and
 * emits an `aborted` error event; the Kotlin core has no AbortSignal, so
 * cancellation propagates as coroutine cancellation and produces no Error
 * event.
 */
internal object GoogleStreamEngine {

    private val toolCallCounter = AtomicLong()

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
        clock: Clock = Clock.System,
        model: Model,
        plan: Plan,
    ): Flow<AssistantMessageEvent> = flow {
        val state = State(model, clock.now().toEpochMilliseconds())
        try {
            // Retries cover only the request, never the SSE stream.
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

    fun nextToolCallId(name: String, nowMs: Long): String =
        "${name}_${nowMs}_${toolCallCounter.incrementAndGet()}"

    /**
     * Upstream surfaces the `@google/genai` SDK's `error.message`, which
     * already carries the response body; here the shared formatter composes
     * `"<status>: <body>"` from the raw transport response instead.
     */
    private fun formatProviderError(error: Exception): String = when (error) {
        is ProviderHttpException -> formatProviderError(normalizeProviderError(error))
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
                lenientJson.parseToJsonElement(event.data)
            } catch (error: Exception) {
                throw ProviderStreamException(
                    "Malformed SSE JSON payload: ${error.message ?: error::class.simpleName}",
                )
            }
            if (chunk !is JsonObject) {
                throw ProviderStreamException("Malformed SSE JSON payload: expected a JSON object")
            }

            // responseId is output-only per the API; keep the first non-empty value.
            if (responseId.isNullOrEmpty()) {
                responseId = chunk["responseId"].strOrNull()?.takeIf { it.isNotEmpty() }
            }

            val candidate = chunk.arr("candidates")?.filterIsInstance<JsonObject>()?.firstOrNull()
            for (part in candidate?.obj("content")?.arr("parts")
                ?.filterIsInstance<JsonObject>() ?: emptyList<JsonObject>()) {
                events += processPart(part)
            }

            candidate.str("finishReason")?.let { reason ->
                rawStopReason = reason
                stopReason = GoogleShared.mapStopReason(reason)
                if (content.any { it is ToolCall } && stopReason == StopReason.STOP) {
                    stopReason = StopReason.TOOL_USE
                }
            }

            chunk.obj("usageMetadata")?.let { meta ->
                val promptTokens = meta.int("promptTokenCount") ?: 0
                val cachedTokens = meta.int("cachedContentTokenCount") ?: 0
                val candidatesTokens = meta.int("candidatesTokenCount") ?: 0
                val thoughtsTokens = meta.int("thoughtsTokenCount") ?: 0
                val newUsage = Usage(
                    input = promptTokens - cachedTokens,
                    output = candidatesTokens + thoughtsTokens,
                    cacheRead = cachedTokens,
                    cacheWrite = 0,
                    reasoning = thoughtsTokens,
                    totalTokens = meta.int("totalTokenCount") ?: 0,
                )
                usage = newUsage.copy(cost = calculateCost(model, newUsage))
            }

            return events
        }

        private fun processPart(part: JsonObject): List<AssistantMessageEvent> {
            val events = mutableListOf<AssistantMessageEvent>()
            val text = part["text"].strOrNull()

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
                        part["thoughtSignature"].strOrNull(),
                    )
                    events.add(AssistantMessageEvent.ThinkingDelta(blockIndex(), text, snapshot()))
                } else {
                    currentText!!.append(text)
                    currentTextSignature = GoogleShared.retainThoughtSignature(
                        currentTextSignature,
                        part["thoughtSignature"].strOrNull(),
                    )
                    events.add(AssistantMessageEvent.TextDelta(blockIndex(), text, snapshot()))
                }
            }

            val functionCall = part.obj("functionCall")
            if (functionCall != null) {
                closeOpenBlock()?.let { events.add(it) }

                val args = functionCall.obj("args") ?: JsonObject(emptyMap())
                val name = functionCall["name"].strOrNull() ?: ""
                val providedId = functionCall["id"].strOrNull()
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
                    thoughtSignature = part["thoughtSignature"].strOrNull()
                        ?.takeIf { it.isNotEmpty() },
                )
                content.add(toolCall)
                events.add(AssistantMessageEvent.ToolCallStart(blockIndex(), snapshot()))
                events.add(AssistantMessageEvent.ToolCallDelta(blockIndex(), args.toString(), snapshot()))
                events.add(AssistantMessageEvent.ToolCallEnd(blockIndex(), toolCall, snapshot()))
            }

            return events
        }

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

/**
 * The streamSimple options conversion (resolved thinking config), extracted
 * as a named function so the conversion — including the telemetryContext
 * identity — is directly testable.
 */
internal fun buildGoogleOptions(
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
): GoogleGenerativeAiApi.GoogleOptions = GoogleGenerativeAiApi.GoogleOptions(
    apiKey = options.apiKey,
    sessionId = options.sessionId,
    temperature = options.temperature,
    maxTokens = works.resolve.pathfinder.ai.utils.clampMaxTokensToContext(
        model,
        context,
        options.maxTokens ?: model.maxTokens,
    ),
    timeoutMs = options.timeoutMs,
    maxRetries = options.maxRetries,
    maxRetryDelayMs = options.maxRetryDelayMs,
    env = options.env,
    headers = options.headers,
    onPayload = options.onPayload,
    onResponse = options.onResponse,
    toolChoice = when (options.toolChoice) {
        null -> null
        SimpleToolChoice.Auto -> "auto"
        SimpleToolChoice.None -> "none"
    },
    thinking = GoogleRequest.thinkingForSimpleStream(
        model,
        options.reasoning,
        options.thinkingBudgets,
        gemmaSupported = true,
    ),
    telemetryContext = options.telemetryContext,
)

/**
 * Request construction for the Google Generative AI adapter.
 *
 * Wire shape: where pi hands `config` to the `@google/genai` SDK, Pathfinder
 * writes the documented GenerateContentRequest REST shape directly —
 * `contents`, `systemInstruction` (string), `tools`, `toolConfig` at the top
 * level, and `temperature`/`maxOutputTokens`/`thinkingConfig` nested in
 * `generationConfig`.
 */
object GoogleRequest {

    data class GoogleThinking(
        val enabled: Boolean,
        /** -1 for dynamic, 0 to disable. */
        val budgetTokens: Int? = null,
        val level: GoogleShared.GoogleApiThinkingLevel? = null,
    )

    data class CommonOptions(
        val apiKey: String? = null,
        val sessionId: String? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val timeoutMs: Long? = null,
        val maxRetries: Int = 0,
        val maxRetryDelayMs: Long = works.resolve.pathfinder.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
        val env: Map<String, String> = emptyMap(),
        val headers: Map<String, String?> = emptyMap(),
        /** "auto" | "none" | "any". */
        val toolChoice: String? = null,
        val thinking: GoogleThinking? = null,
    ) {
        override fun toString(): String = optionsToString(
            "CommonOptions",
            "apiKey" to redactedSecret(apiKey),
            "sessionId" to sessionId,
            "temperature" to temperature,
            "maxTokens" to maxTokens,
            "timeoutMs" to timeoutMs,
            "maxRetries" to maxRetries,
            "maxRetryDelayMs" to maxRetryDelayMs,
            "env" to env.keys,
            "headers" to headers.keys,
            "toolChoice" to toolChoice,
            "thinking" to thinking?.enabled,
        )
    }

    fun buildGenerateContentRequest(
        model: Model,
        context: Context,
        options: CommonOptions,
        gemmaSupported: Boolean,
    ): JsonObject {
        val supportsStrictMode = GoogleShared.supportsGoogleStrictToolSampling(model.id)
        val functionCallingMode = if (context.tools.isNotEmpty()) {
            GoogleShared.resolveGoogleFunctionCallingMode(context.tools, options.toolChoice, supportsStrictMode)
        } else {
            null
        }

        val request = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        request["contents"] = GoogleShared.convertMessages(model, context)
        if (!context.systemPrompt.isNullOrEmpty()) {
            request["systemInstruction"] = JsonPrimitive(
                sanitizeSurrogates(context.systemPrompt),
            )
        }
        if (context.tools.isNotEmpty()) {
            request["tools"] = GoogleShared.convertTools(context.tools, false, supportsStrictMode)!!
        }
        if (functionCallingMode != null) {
            request["toolConfig"] = buildJsonObject {
                put(
                    "functionCallingConfig",
                    buildJsonObject { put("mode", functionCallingMode) },
                )
            }
        }

        val generationConfig = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        options.temperature?.let { generationConfig["temperature"] = JsonPrimitive(it) }
        options.maxTokens?.let { generationConfig["maxOutputTokens"] = JsonPrimitive(it) }

        val thinking = options.thinking
        val thinkingConfig: JsonObject? = when {
            thinking != null && thinking.enabled && model.reasoning -> buildJsonObject {
                put("includeThoughts", true)
                thinking.level?.let { put("thinkingLevel", it.wire) }
                    ?: thinking.budgetTokens?.let { put("thinkingBudget", it) }
            }

            model.reasoning && thinking != null && !thinking.enabled ->
                getDisabledThinkingConfig(model, gemmaSupported)

            else -> null
        }
        thinkingConfig?.let { generationConfig["thinkingConfig"] = it }
        if (generationConfig.isNotEmpty()) {
            request["generationConfig"] = JsonObject(generationConfig)
        }

        return JsonObject(request)
    }

    private fun isGemma4Model(modelId: String): Boolean =
        Regex("gemma-?4").containsMatchIn(modelId.lowercase())

    private fun isGemini3ProModel(modelId: String): Boolean =
        Regex("gemini-3(?:\\.\\d+)?-pro").containsMatchIn(modelId.lowercase())

    fun isGemini3FlashModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return Regex("gemini-3(?:\\.\\d+)?-flash").containsMatchIn(id) ||
            id == "gemini-flash-latest" || id == "gemini-flash-lite-latest"
    }

    /**
     * Google docs: Gemini 3.1 Pro cannot disable thinking, and Gemini 3
     * Flash/Flash-Lite (and Gemma 4, where in scope) do not support full
     * thinking-off either, so use the lowest supported thinkingLevel without
     * includeThoughts; Gemini 2.x disables via thinkingBudget = 0.
     */
    private fun getDisabledThinkingConfig(model: Model, gemmaSupported: Boolean): JsonObject = when {
        isGemini3ProModel(model.id) ->
            buildJsonObject { put("thinkingLevel", GoogleShared.GoogleApiThinkingLevel.LOW.wire) }

        isGemini3FlashModel(model.id) ->
            buildJsonObject { put("thinkingLevel", GoogleShared.GoogleApiThinkingLevel.MINIMAL.wire) }

        gemmaSupported && isGemma4Model(model.id) ->
            buildJsonObject { put("thinkingLevel", GoogleShared.GoogleApiThinkingLevel.MINIMAL.wire) }

        else -> buildJsonObject { put("thinkingBudget", 0) }
    }

    /**
     * The provider-neutral reasoning level becomes a Gemini 3 `thinkingLevel`
     * or a Gemini 2.5 `thinkingBudget`. A null reasoning still resolves to
     * `thinking { enabled: false }` — an explicit disabled thinkingConfig on
     * the wire — exactly like upstream.
     */
    fun thinkingForSimpleStream(
        model: Model,
        reasoning: ThinkingLevel?,
        budgets: Map<ThinkingLevel, Int>,
        gemmaSupported: Boolean,
    ): GoogleThinking {
        if (reasoning == null) return GoogleThinking(enabled = false)

        val clamped = clampThinkingLevel(model, reasoning.toModelThinkingLevel())
        val resolvedLevel = GoogleShared.resolveGoogleThinkingLevel(model, clamped)

        val useLevels = isGemini3ProModel(model.id) ||
            isGemini3FlashModel(model.id) ||
            (gemmaSupported && isGemma4Model(model.id))
        if (useLevels) {
            return GoogleThinking(enabled = true, level = getThinkingLevel(resolvedLevel, model, gemmaSupported))
        }
        return GoogleThinking(enabled = true, budgetTokens = getGoogleBudget(model, resolvedLevel, budgets))
    }

    private fun getThinkingLevel(
        effort: GoogleShared.ResolvedGoogleThinkingLevel,
        model: Model,
        gemmaSupported: Boolean,
    ): GoogleShared.GoogleApiThinkingLevel {
        if (isGemini3ProModel(model.id)) {
            return when (effort) {
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW,
                -> GoogleShared.GoogleApiThinkingLevel.LOW

                else -> GoogleShared.GoogleApiThinkingLevel.HIGH
            }
        }
        if (gemmaSupported && isGemma4Model(model.id)) {
            return when (effort) {
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW,
                -> GoogleShared.GoogleApiThinkingLevel.MINIMAL

                else -> GoogleShared.GoogleApiThinkingLevel.HIGH
            }
        }
        return when (effort) {
            GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL -> GoogleShared.GoogleApiThinkingLevel.MINIMAL
            GoogleShared.ResolvedGoogleThinkingLevel.LOW -> GoogleShared.GoogleApiThinkingLevel.LOW
            GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM -> GoogleShared.GoogleApiThinkingLevel.MEDIUM
            GoogleShared.ResolvedGoogleThinkingLevel.HIGH -> GoogleShared.GoogleApiThinkingLevel.HIGH
        }
    }

    /** Model-specific default budgets; -1 (dynamic) otherwise. */
    private fun getGoogleBudget(
        model: Model,
        level: GoogleShared.ResolvedGoogleThinkingLevel,
        customBudgets: Map<ThinkingLevel, Int>,
    ): Int {
        // ResolvedGoogleThinkingLevel names are a subset of ModelThinkingLevel
        // (no off/xhigh/max), so valueOf is safe; drift fails fast.
        val asThinkingLevel = ModelThinkingLevel.valueOf(level.name).toThinkingLevelOrNull()
        asThinkingLevel?.let { customBudgets[it] }?.let { return it }

        val defaults = when {
            model.id.contains("2.5-pro") -> mapOf(
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL to 128,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW to 2048,
                GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM to 8192,
                GoogleShared.ResolvedGoogleThinkingLevel.HIGH to 32768,
            )

            model.id.contains("2.5-flash-lite") -> mapOf(
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL to 512,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW to 2048,
                GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM to 8192,
                GoogleShared.ResolvedGoogleThinkingLevel.HIGH to 24576,
            )

            model.id.contains("2.5-flash") -> mapOf(
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL to 128,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW to 2048,
                GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM to 8192,
                GoogleShared.ResolvedGoogleThinkingLevel.HIGH to 24576,
            )

            else -> return -1
        }
        return defaults.getValue(level)
    }

}
