package works.resolve.aletheia.ai.core

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
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
) {
    override fun toString(): String =
        "SimpleStreamOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoning=$reasoning, timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs)"

    fun toStreamOptions(reasoningEffort: ModelThinkingLevel?): OpenAiCompletionsOptions =
        OpenAiCompletionsOptions(
            apiKey = apiKey,
            sessionId = sessionId,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort,
            timeoutMs = timeoutMs,
            maxRetries = maxRetries,
            maxRetryDelayMs = maxRetryDelayMs,
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
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
) {
    override fun toString(): String =
        "OpenAiCompletionsOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoningEffort=$reasoningEffort, timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs)"
}
