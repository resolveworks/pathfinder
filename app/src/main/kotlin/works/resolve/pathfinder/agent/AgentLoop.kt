package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.Usage
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Stream function used by the agent loop, mirroring pi's StreamFn contract:
 * must not throw for request/model/runtime failures — failures are encoded in
 * the returned flow via a terminal [AssistantMessageEvent.Error]. The
 * OpenAI-completions path in `works.resolve.pathfinder.ai` satisfies this shape.
 *
 * The returned flow is collected exactly once per assistant turn.
 */
fun interface StreamFn {
    fun stream(model: Model, context: Context, options: SimpleStreamOptions): Flow<AssistantMessageEvent>
}

/**
 * Minimal agent loop configuration. Tools, steering, follow-up queues, and
 * context transforms are out of scope for the MVP and deliberately absent.
 */
data class AgentLoopConfig(
    val model: Model,
    val options: SimpleStreamOptions = SimpleStreamOptions(),
    val streamFn: StreamFn,
)

/** Lifecycle events emitted by the agent loop, reduced from pi's AgentEvent. */
sealed class AgentEvent {
    object AgentStart : AgentEvent()

    /** Terminal event carrying every message produced by this run, in source order. */
    data class AgentEnd(val messages: List<Message>) : AgentEvent()

    object TurnStart : AgentEvent()

    /** Final assistant message of the turn. Tool results are not part of the MVP. */
    data class TurnEnd(val message: AssistantMessage) : AgentEvent()

    data class MessageStart(val message: Message) : AgentEvent()

    /** Only emitted for assistant messages while streaming. */
    data class MessageUpdate(
        val message: AssistantMessage,
        val assistantMessageEvent: AssistantMessageEvent,
    ) : AgentEvent()

    data class MessageEnd(val message: Message) : AgentEvent()

    /**
     * A retryable run is being retried after an exponential-backoff delay.
     * Ported from pi's agent-session `auto_retry_start` event
     * (agent-session.ts:167-168); pi emits it from agent-session, not the
     * loop, so here it originates in the [Agent] facade and never appears in
     * [runAgentLoop] output.
     */
    data class AutoRetryStart(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val errorMessage: String,
    ) : AgentEvent()

    /**
     * The retry sequence ended: a retried run succeeded, the budget was
     * exhausted, or the backoff was cancelled. Ported from pi's
     * `auto_retry_end` (agent-session.ts:169).
     */
    data class AutoRetryEnd(
        val success: Boolean,
        val attempt: Int,
        val finalError: String? = null,
    ) : AgentEvent()

    /**
     * Trigger of a compaction run, pi's compaction event reason union
     * (agent-session.ts:156/161). [CompactionReason.MANUAL] is carried for
     * event-shape fidelity only: pi's manual `/compact` entry point
     * (`AgentSession.compact`, agent-session.ts ~1919) has no command
     * surface in pathfinder, so this port never emits it.
     */
    enum class CompactionReason { MANUAL, THRESHOLD, OVERFLOW }

    /**
     * Payload of [AgentEvent.CompactionEnd], pi's `CompactionResult`
     * (coding-agent core/compaction/compaction.ts) reduced to the fields of
     * the landed harness `CompactResult` plus `estimatedTokensAfter`:
     * `firstKeptEntryId` is not ported because pathfinder's
     * [works.resolve.pathfinder.data.sessions.CompactionEntry] stores the
     * retained tail directly (harness entry shape) instead of a kept-entry
     * pointer.
     */
    data class CompactionResult(
        /** Summary text that replaces the compacted history. */
        val summary: String,
        /** Estimated context tokens before compaction. */
        val tokensBefore: Int,
        /** Estimated context tokens of the rebuilt transcript. */
        val estimatedTokensAfter: Int,
        /** Usage of the summary LLM call(s), when reported. */
        val usage: Usage?,
        /** File-operation details stored on the compaction entry. */
        val details: works.resolve.pathfinder.agent.compaction.CompactionDetails?,
    )

    /**
     * Automatic compaction started, pi's `compaction_start`
     * (agent-session.ts:156). Emitted by [AgentSession] between agent runs.
     */
    data class CompactionStart(
        val reason: CompactionReason,
    ) : AgentEvent()

    /**
     * Compaction ended — succeeded, was aborted, or failed. Pi's
     * `compaction_end` (agent-session.ts:161); `result`/`errorMessage`
     * correspond to upstream's optional fields.
     */
    data class CompactionEnd(
        val reason: CompactionReason,
        val result: CompactionResult? = null,
        val aborted: Boolean,
        val willRetry: Boolean,
        val errorMessage: String? = null,
    ) : AgentEvent()

    /**
     * A summarization retry is scheduled (summary LLM call backed off),
     * pi's `summarization_retry_scheduled` (agent-session.ts:171).
     */
    data class SummarizationRetryScheduled(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val errorMessage: String,
    ) : AgentEvent()

    /**
     * A summarization retry attempt starts, pi's
     * `summarization_retry_attempt_start` (agent-session.ts:177) reduced to
     * the compaction source: the `branchSummary` source has no counterpart
     * in pathfinder (branch summarization is not ported).
     */
    data class SummarizationRetryAttemptStart(
        val reason: CompactionReason,
    ) : AgentEvent()

    /** A summarization retry sequence finished, pi's `summarization_retry_finished` (agent-session.ts:183). */
    object SummarizationRetryFinished : AgentEvent()
}

/**
 * Runs the minimal no-tools agent loop: prompts are appended to the context,
 * one assistant response is streamed, and the run's new messages (prompts plus
 * the assistant message) are returned in source order.
 *
 * Event order mirrors pi's no-tools path: agent_start, turn_start, one
 * message_start/message_end pair per prompt, assistant message_start /
 * message_update* / message_end, turn_end, agent_end.
 *
 * [context] is treated as an immutable snapshot; it is never mutated. A
 * non-empty tool list is rejected up front because tool execution is out of
 * scope. Coroutine cancellation propagates as [CancellationException] without
 * emitting a synthetic error or agent_end.
 */
suspend fun runAgentLoop(
    prompts: List<Message>,
    context: Context,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
): List<Message> {
    require(context.tools.isEmpty()) {
        "Tools are not supported by the minimal agent loop; pass an empty tool list"
    }
    prompts.forEach { prompt ->
        require(prompt !is AssistantMessage) { "Prompts must be user or toolResult messages" }
    }

    val newMessages = prompts.toMutableList()
    val llmMessages = context.messages + prompts

    emit(AgentEvent.AgentStart)
    emit(AgentEvent.TurnStart)
    for (prompt in prompts) {
        emit(AgentEvent.MessageStart(prompt))
        emit(AgentEvent.MessageEnd(prompt))
    }

    val message = streamAssistantResponse(
        llmContext = Context(systemPrompt = context.systemPrompt, messages = llmMessages),
        config = config,
        emit = emit,
    )
    newMessages.add(message)

    emit(AgentEvent.TurnEnd(message))
    emit(AgentEvent.AgentEnd(newMessages.toList()))
    return newMessages.toList()
}

/**
 * Streams one assistant response, folding provider events into message
 * lifecycle events. Ported from pi's streamAssistantResponse, reduced to the
 * no-tools path: the provider stream is created and collected exactly once.
 */
private suspend fun streamAssistantResponse(
    llmContext: Context,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
): AssistantMessage {
    val response = config.streamFn.stream(config.model, llmContext, config.options)

    var started = false
    var latestPartial: AssistantMessage? = null
    var finalMessage: AssistantMessage? = null

    // The upstream is collected in a child job that is cancelled as soon as the
    // first terminal Done/Error event is observed, mirroring pi's immediate
    // return from streamAssistantResponse: a provider that hangs after emitting
    // its terminal event cannot stall the agent, and only the first terminal
    // event is observed. External coroutine cancellation propagates normally
    // out of coroutineScope without any synthetic events.
    coroutineScope {
        val upstream = launch {
            response.collect { event ->
                when (event) {
                    is AssistantMessageEvent.Start -> {
                        started = true
                        latestPartial = event.partial
                        emit(AgentEvent.MessageStart(event.partial))
                    }

                    is AssistantMessageEvent.TextStart,
                    is AssistantMessageEvent.TextDelta,
                    is AssistantMessageEvent.TextEnd,
                    is AssistantMessageEvent.ThinkingStart,
                    is AssistantMessageEvent.ThinkingDelta,
                    is AssistantMessageEvent.ThinkingEnd,
                    is AssistantMessageEvent.ToolCallStart,
                    is AssistantMessageEvent.ToolCallDelta,
                    is AssistantMessageEvent.ToolCallEnd,
                    -> {
                        if (started) {
                            latestPartial = event.partial
                            emit(AgentEvent.MessageUpdate(event.partial, event))
                        }
                    }

                    is AssistantMessageEvent.Done,
                    is AssistantMessageEvent.Error,
                    -> {
                        finalMessage = finalizeStreamedMessage(
                            when (event) {
                                is AssistantMessageEvent.Done -> event.message
                                is AssistantMessageEvent.Error -> event.error
                            },
                        )
                        this@launch.cancel()
                    }
                }
            }
        }
        upstream.join()
    }
    var message = finalMessage
    if (!started && message != null) {
        // Setup/auth failures can arrive before any Start event; the message
        // still needs a message_start before message_end.
        emit(AgentEvent.MessageStart(message))
    }
    if (message == null) {
        // Malformed provider stream: completed without a terminal Done/Error.
        // Preserve the latest partial's content and model metadata; fall back
        // to a fresh message with a current timestamp when nothing was emitted.
        val partial = latestPartial
        message = if (partial != null) {
            partial.copy(
                stopReason = StopReason.ERROR,
                errorMessage = "Provider stream completed without a terminal event",
            )
        } else {
            unsupportedErrorMessage(config.model, System.currentTimeMillis())
        }
        if (!started) {
            emit(AgentEvent.MessageStart(message))
        }
    }
    emit(AgentEvent.MessageEnd(message))
    return message
}

/**
 * A no-tools run must never continue from a tool call: if the provider
 * unexpectedly finishes with TOOL_USE or any ToolCall content, finalize the
 * message as an error instead of starting another turn.
 */
private fun finalizeStreamedMessage(message: AssistantMessage): AssistantMessage {
    val hasToolCall = message.stopReason == StopReason.TOOL_USE ||
        message.content.any { it is ToolCall }
    if (!hasToolCall) return message
    return message.copy(
        stopReason = StopReason.ERROR,
        errorMessage = "Model responded with a tool call, but tools are not supported by this build",
    )
}

private fun unsupportedErrorMessage(model: Model, timestamp: Long): AssistantMessage =
    AssistantMessage(
        content = emptyList(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.ERROR,
        errorMessage = "Provider stream completed without a terminal event",
        timestamp = timestamp,
    )
