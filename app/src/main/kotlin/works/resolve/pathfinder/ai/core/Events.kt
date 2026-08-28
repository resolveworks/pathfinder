package works.resolve.pathfinder.ai.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Stream event protocol ported from pi's AssistantMessageEvent. A successful
 * stream emits `Start` first, then block events carrying immutable partial
 * snapshots, and terminates with `Done`. Failures at any point — including
 * auth or setup failures before anything is emitted — are encoded as a
 * terminal `Error` event, which may therefore arrive without a preceding
 * `Start`. `Done` and `Error` are mutually exclusive terminal events.
 *
 * Coroutine cancellation is not a failure: cancelling the collecting
 * coroutine propagates normally (the flow simply stops emitting) and no
 * `Error` event is produced.
 */
sealed class AssistantMessageEvent {
    abstract val partial: AssistantMessage

    data class Start(override val partial: AssistantMessage) : AssistantMessageEvent()

    data class TextStart(val contentIndex: Int, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class TextDelta(val contentIndex: Int, val delta: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class TextEnd(val contentIndex: Int, val content: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ThinkingStart(val contentIndex: Int, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ThinkingDelta(val contentIndex: Int, val delta: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ThinkingEnd(val contentIndex: Int, val content: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ToolCallStart(val contentIndex: Int, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ToolCallDelta(val contentIndex: Int, val delta: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ToolCallEnd(val contentIndex: Int, val toolCall: ToolCall, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class Done(
        val reason: StopReason,
        val message: AssistantMessage,
    ) : AssistantMessageEvent() {
        override val partial: AssistantMessage get() = message
    }

    data class Error(
        val reason: StopReason,
        /** Final assistant message with stopReason ABORTED/ERROR and errorMessage set. */
        val error: AssistantMessage,
    ) : AssistantMessageEvent() {
        override val partial: AssistantMessage get() = error
    }
}

/**
 * pi's ProviderResponse (packages/ai/src/types.ts:118-121): the HTTP status
 * and response headers passed to request hooks after response headers are
 * received.
 */
data class ProviderResponse(
    val status: Int,
    /** Flattened single-value response headers (pi's `Record<string, string>`). */
    val headers: Map<String, String>,
)

/**
 * pi's headersToRecord (packages/ai/src/utils/headers.ts): flattens
 * multi-valued HTTP response headers into the single-value record passed to
 * `onResponse` hooks. Fetch's `Headers` iteration joins repeated values with
 * `", "` (WHATWG fetch spec header-value combining), which this mirrors over
 * the transport's multi-map. Header names arrive already lower-cased from the
 * transport.
 */
fun headersToRecord(headers: Map<String, List<String>>): Map<String, String> =
    headers.mapValues { (_, values) -> values.joinToString(", ") }

/**
 * Options shared by all provider requests, reduced from pi's StreamOptions.
 * Timing and retry behavior matches pi's provider-retry defaults.
 */
data class StreamOptions(
    /** Explicit API key; when absent the provider's credential resolver is used. Never included in toString(). */
    val apiKey: String? = null,
    /** Session identifier usable for affinity/sticky routing. */
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    /** Cap on server-requested retry delays; delays above this fail immediately. 0 disables. */
    val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
) {
    override fun toString(): String =
        "StreamOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", timeoutMs=$timeoutMs, maxRetries=$maxRetries, maxRetryDelayMs=$maxRetryDelayMs)"

    companion object {
        const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
    }
}

/** Provider-neutral request options used by the models-level stream entry point. */
data class SimpleStreamOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoning: ThinkingLevel? = null,
    /**
     * Narrow tool choice, pi's SimpleStreamOptions.toolChoice (types.ts:316)
     * typed as pi's `ToolChoice = "auto" | "none"` (types.ts:82). The full
     * union lives only on completions-level options. Reduction: pi's
     * `deferred` flag is excluded per ai/AGENTS.md adapter-capability scope.
     */
    val toolChoice: SimpleToolChoice? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    /** Prompt-cache retention preference (pi's CacheRetention); null resolves from env/default. */
    val cacheRetention: CacheRetention? = null,
    /** Per-request provider env (credential values merged in, pi's applyAuth). */
    val env: Map<String, String> = emptyMap(),
    /** Explicit request headers; merged over resolved auth headers (pi's applyAuth). */
    val headers: Map<String, String?> = emptyMap(),
    /** Per-level thinking token budgets (pi's ThinkingBudgets); consumed by budget-based adapters. */
    val thinkingBudgets: Map<ThinkingLevel, Int> = emptyMap(),
    /**
     * pi's ProviderRequestOptions.onPayload (packages/ai/src/types.ts:145-149):
     * inspects or replaces the provider request payload object before it is
     * serialized and sent; returning null keeps the payload unchanged (pi
     * returns undefined). The hook receives the full payload including
     * message content — installers must not log payload content. Never
     * included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /**
     * pi's StreamOptions.onResponse (types.ts:184): invoked after HTTP
     * response headers are received and before the body stream is consumed,
     * with status and flattened headers. Whether it fires for non-2xx
     * responses is per adapter, mirroring pi exactly (see each adapter's
     * wiring). Never included in toString().
     */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * pi's StreamOptions.samplingParams (types.ts:184-193): arbitrary
     * sampling parameters merged into the request body as-is, after the
     * named request fields, so keys here override them. Lets custom
     * OpenAI-compatible servers (llama.cpp, vLLM, SGLang, ...) receive
     * parameters pi does not model. Merged over [Model.samplingParams] per
     * key; only applied by OpenAI-compatible adapters (completions,
     * responses, Azure responses); other APIs ignore it. `Map<String,
     * JsonElement>` mirrors pi's `Record<string, unknown>` over this port's
     * JSON payload model. Only keys (never values) may appear in toString().
     */
    val samplingParams: Map<String, JsonElement>? = null,
) {
    override fun toString(): String =
        "SimpleStreamOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoning=$reasoning, toolChoice=$toolChoice, cacheRetention=$cacheRetention" +
            ", timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs, env=${env.keys}, headers=${headers.keys}" +
            ", onPayload=${onPayload != null}, onResponse=${onResponse != null}" +
            ", samplingParams=${samplingParams?.keys})"

    fun toStreamOptions(reasoningEffort: ModelThinkingLevel?): OpenAiCompletionsOptions =
        OpenAiCompletionsOptions(
            apiKey = apiKey,
            sessionId = sessionId,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort,
            toolChoice = toolChoice?.toToolChoice(),
            cacheRetention = cacheRetention,
            timeoutMs = timeoutMs,
            maxRetries = maxRetries,
            maxRetryDelayMs = maxRetryDelayMs,
            env = env,
            headers = headers,
            thinkingBudgets = thinkingBudgets,
            onPayload = onPayload,
            onResponse = onResponse,
            samplingParams = samplingParams,
        )
}

/**
 * pi's buildBaseOptions samplingParams merge (packages/ai/src/api/simple-options.ts:20-56):
 * request-level keys override [Model.samplingParams] defaults per key, and
 * the merged value is null when both are absent. Called at each
 * OpenAI-compatible adapter's streamSimple boundary, where pi calls
 * buildBaseOptions before delegating to its per-API stream.
 */
fun mergeSamplingParams(model: Model, options: SimpleStreamOptions): Map<String, JsonElement>? =
    if (model.samplingParams.isNullOrEmpty() && options.samplingParams == null) {
        null
    } else {
        (model.samplingParams ?: emptyMap()) + (options.samplingParams ?: emptyMap())
    }

/** Options understood by the OpenAI Chat Completions adapter. */
data class OpenAiCompletionsOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Requested thinking level; null disables reasoning. */
    val reasoningEffort: ModelThinkingLevel? = null,
    /**
     * Tool selection forwarded as the Chat Completions `tool_choice` param,
     * pi's OpenAICompletionsOptions.toolChoice
     * (packages/ai/src/api/openai-completions.ts:164), serialized in
     * buildParams (openai-completions.ts:850-851).
     */
    val toolChoice: ToolChoice? = null,
    /** Prompt-cache retention preference (pi's CacheRetention); null resolves from env/default. */
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    /** Per-request provider env (credential values merged in, pi's applyAuth). */
    val env: Map<String, String> = emptyMap(),
    /** Explicit request headers; merged over resolved auth headers (pi's applyAuth). */
    val headers: Map<String, String?> = emptyMap(),
    /** Per-level thinking token budgets (pi's ThinkingBudgets); consumed by budget-based adapters. */
    val thinkingBudgets: Map<ThinkingLevel, Int> = emptyMap(),
    /**
     * pi's onPayload request hook (ProviderRequestOptions, types.ts:145-149):
     * replaces this adapter's params object before serialization when it
     * returns non-null (openai-completions.ts:352). Receives full message
     * content; installers must not log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /**
     * pi's onResponse request hook (types.ts:184; openai-completions.ts:369):
     * invoked after response headers arrive (2xx only — the SDK path throws
     * before the hook on non-2xx). Never included in toString().
     */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * pi's samplingParams (types.ts:184-193; openai-completions.ts:981-982):
     * merged into the params object last so custom keys override the named
     * request fields. Already merged over [Model.samplingParams] by
     * [mergeSamplingParams] on the streamSimple path. Only keys appear in
     * toString().
     */
    val samplingParams: Map<String, JsonElement>? = null,
) {
    override fun toString(): String =
        "OpenAiCompletionsOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoningEffort=$reasoningEffort, toolChoice=$toolChoice, cacheRetention=$cacheRetention" +
            ", timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs, env=${env.keys}, headers=${headers.keys}" +
            ", onPayload=${onPayload != null}, onResponse=${onResponse != null}" +
            ", samplingParams=${samplingParams?.keys})"
}

/**
 * Merges provider headers, porting pi's mergeHeaders: [override] wins
 * case-insensitively per header name, and a null override value removes the
 * header entirely.
 */
fun mergeHeaders(
    base: Map<String, String?>,
    override: Map<String, String?>,
): Map<String, String?> {
    if (base.isEmpty() && override.isEmpty()) return emptyMap()
    val merged = LinkedHashMap(base)
    for ((name, value) in override) {
        val lowerName = name.lowercase()
        merged.keys.filter { it.lowercase() == lowerName }.forEach { merged.remove(it) }
        merged[name] = value
    }
    return merged
}

/**
 * True when [headers] sets a non-blank value for [name] (case-insensitive),
 * pi's hasHeader.
 */
fun hasHeader(headers: Map<String, String?>, name: String): Boolean =
    headers.any { it.key.lowercase() == name && !it.value.isNullOrBlank() }
