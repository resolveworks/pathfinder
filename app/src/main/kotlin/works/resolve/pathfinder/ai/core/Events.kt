package works.resolve.pathfinder.ai.core

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
    val toolChoice: ToolChoice? = null,
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
) {
    override fun toString(): String =
        "SimpleStreamOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoning=$reasoning, toolChoice=$toolChoice, cacheRetention=$cacheRetention" +
            ", timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs, env=${env.keys}, headers=${headers.keys})"

    fun toStreamOptions(reasoningEffort: ModelThinkingLevel?): OpenAiCompletionsOptions =
        OpenAiCompletionsOptions(
            apiKey = apiKey,
            sessionId = sessionId,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort,
            toolChoice = toolChoice,
            timeoutMs = timeoutMs,
            maxRetries = maxRetries,
            maxRetryDelayMs = maxRetryDelayMs,
            env = env,
            headers = headers,
            thinkingBudgets = thinkingBudgets,
        )
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
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    /** Per-request provider env (credential values merged in, pi's applyAuth). */
    val env: Map<String, String> = emptyMap(),
    /** Explicit request headers; merged over resolved auth headers (pi's applyAuth). */
    val headers: Map<String, String?> = emptyMap(),
    /** Per-level thinking token budgets (pi's ThinkingBudgets); consumed by budget-based adapters. */
    val thinkingBudgets: Map<ThinkingLevel, Int> = emptyMap(),
) {
    override fun toString(): String =
        "OpenAiCompletionsOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoningEffort=$reasoningEffort, toolChoice=$toolChoice, timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs, env=${env.keys}, headers=${headers.keys})"
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
