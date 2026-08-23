package com.aletheia.ai.core

/**
 * Stream event protocol ported from pi's AssistantMessageEvent. A stream emits
 * `Start` first, then block events carrying immutable partial snapshots, and
 * terminates with `Done` (success) or `Error` (failure/abort) — never both.
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
    /** Explicit API key; when absent the provider's credential resolver is used. */
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
    companion object {
        const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
    }
}

/** Provider-neutral options, like pi's SimpleStreamOptions. */
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
)
