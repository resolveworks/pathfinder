package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
 * Agent loop configuration. The tool-execution contract surface is ported
 * from pi's AgentLoopConfig (packages/agent/src/types.ts); steering,
 * follow-up queues, hooks, and context transforms are out of scope for this
 * port and deliberately absent.
 */
data class AgentLoopConfig(
    val model: Model,
    val options: SimpleStreamOptions = SimpleStreamOptions(),
    val streamFn: StreamFn,
    /**
     * Tool execution mode (pi's AgentLoopConfig.toolExecution). Default is
     * [ToolExecutionMode.PARALLEL], matching pi's `"parallel"` default.
     * The mode selects per batch how the calls of one assistant message are
     * executed; a per-tool `executionMode = SEQUENTIAL` override forces the
     * sequential path for the whole batch (pi's executeToolCalls,
     * agent-loop.ts).
     */
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
)

/** Lifecycle events emitted by the agent loop, reduced from pi's AgentEvent. */
sealed class AgentEvent {
    object AgentStart : AgentEvent()

    /** Terminal event carrying every message produced by this run, in source order. */
    data class AgentEnd(val messages: List<Message>) : AgentEvent()

    object TurnStart : AgentEvent()

    /** Final assistant message of the turn plus its tool results in source order (pi's `turn_end`). */
    data class TurnEnd(val message: AssistantMessage, val toolResults: List<ToolResultMessage> = emptyList()) : AgentEvent()

    data class MessageStart(val message: Message) : AgentEvent()

    /** Only emitted for assistant messages while streaming. */
    data class MessageUpdate(
        val message: AssistantMessage,
        val assistantMessageEvent: AssistantMessageEvent,
    ) : AgentEvent()

    data class MessageEnd(val message: Message) : AgentEvent()

    /**
     * Tool execution lifecycle, pi's AgentEvent tool events
     * (packages/agent/src/types.ts). [arguments] is the raw parsed JSON of
     * the assistant call (pi's `args: any`); Pathfinder stores
     * [ToolCall.arguments] as the provider's raw JSON string and the loop
     * parses it. Validated/normalized arguments are used only for execution
     * and for [ToolExecutionUpdate].
     */
    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val arguments: JsonObject,
    ) : AgentEvent()

    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val arguments: JsonObject,
        val partialResult: AgentToolResult,
    ) : AgentEvent()

    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: AgentToolResult,
        val isError: Boolean,
    ) : AgentEvent()

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
 * Runs the agent loop, pi's runAgentLoop + runLoop
 * (packages/agent/src/agent-loop.ts:95, :155). Prompts are appended to a
 * local mutable copy of the context; `agent_start`, `turn_start`, and one
 * `message_start`/`message_end` pair per prompt are emitted, then assistant
 * turns are streamed one at a time. Each assistant message's `ToolCall`
 * blocks are executed (sequentially or in parallel per
 * [AgentLoopConfig.toolExecution] and the tools' `executionMode`), their
 * [ToolResultMessage]s are appended to the local context and to the run's
 * new messages, and the model is asked for another turn — until a response
 * carries no tool calls, its stop reason is `ERROR`/`ABORTED`, or the batch
 * terminates (pi's `terminate` rule, always false in this port — see
 * [shouldTerminateToolBatch]). The run's new messages are returned in source
 * order.
 *
 * [context] is treated as an immutable snapshot; it is never mutated. A
 * fresh [Context] (tool definitions only) is projected for every provider
 * request — executor objects are never exposed to provider serialization.
 * Coroutine cancellation propagates as [CancellationException] without
 * emitting a synthetic error or agent_end.
 */
suspend fun runAgentLoop(
    prompts: List<Message>,
    context: AgentContext,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
): List<Message> {
    prompts.forEach { prompt ->
        require(prompt !is AssistantMessage) { "Prompts must be user or toolResult messages" }
    }

    val newMessages = prompts.toMutableList()
    val llmMessages = (context.messages + prompts).toMutableList()

    emit(AgentEvent.AgentStart)
    emit(AgentEvent.TurnStart)
    for (prompt in prompts) {
        emit(AgentEvent.MessageStart(prompt))
        emit(AgentEvent.MessageEnd(prompt))
    }

    runLoop(context, llmMessages, newMessages, config, emit)
    return newMessages.toList()
}

/** One entry of an executed tool batch: pi's ExecutedToolCallBatch (agent-loop.ts:426-430). */
private class ExecutedToolCallBatch(
    val messages: List<ToolResultMessage>,
    val terminate: Boolean,
)

/** Pi's PreparedToolCall / ImmediateToolCallOutcome union (agent-loop.ts:549-563). */
private sealed interface ToolCallPreparation

private class PreparedToolCall(
    val toolCall: ToolCall,
    val tool: AgentTool,
    /** Validated (and copied) arguments used for execution. */
    val arguments: JsonObject,
) : ToolCallPreparation

private class ImmediateToolCallOutcome(
    val result: AgentToolResult,
    val isError: Boolean,
) : ToolCallPreparation

/** Pi's FinalizedToolCallOutcome (agent-loop.ts:565-570). */
private class FinalizedToolCallOutcome(
    val toolCall: ToolCall,
    val result: AgentToolResult,
    val isError: Boolean,
)

/** Pi's ExecutedToolCallOutcome (agent-loop.ts:572-575). */
private class ExecutedToolCallOutcome(
    val result: AgentToolResult,
    val isError: Boolean,
)

/**
 * Main loop logic, pi's runLoop (agent-loop.ts:155-276) reduced to the
 * ported surface: no steering messages, follow-up queues, prepareNextTurn,
 * shouldStopAfterTurn, or context transforms, so the outer follow-up loop
 * collapses to the inner tool-call loop. A `turn_start` precedes every
 * assistant turn exactly once (pi's runAgentLoop emits the first one and
 * runLoop one per subsequent iteration via its firstTurn flag).
 *
 * Divergence: pi pushes streaming partials into its local context and
 * replaces them with the final message; this port appends only the final
 * message. Without steering/hooks nothing observes the partials, so there
 * is no behavioral difference.
 */
private suspend fun runLoop(
    context: AgentContext,
    llmMessages: MutableList<Message>,
    newMessages: MutableList<Message>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
) {
    while (true) {
        val message = streamAssistantResponse(
            llmContext = Context(
                systemPrompt = context.systemPrompt,
                messages = llmMessages.toList(),
                tools = context.tools.map { it.definition },
            ),
            config = config,
            emit = emit,
        )
        newMessages.add(message)
        llmMessages.add(message)

        if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
            emit(AgentEvent.TurnEnd(message))
            emit(AgentEvent.AgentEnd(newMessages.toList()))
            return
        }

        val toolCalls = message.content.filterIsInstance<ToolCall>()
        val toolResults = mutableListOf<ToolResultMessage>()
        var terminate = false
        if (toolCalls.isNotEmpty()) {
            // A "length" stop means the output was cut off by the token limit, so
            // every tool call in the message may carry truncated arguments. Fail
            // them all instead of executing potentially borked calls (pi's
            // runLoop comment, agent-loop.ts:210-216).
            val executedToolBatch =
                if (message.stopReason == StopReason.LENGTH) {
                    failToolCallsFromTruncatedMessage(toolCalls, emit)
                } else {
                    executeToolCalls(context, toolCalls, config, emit)
                }
            toolResults.addAll(executedToolBatch.messages)
            terminate = executedToolBatch.terminate
            for (result in toolResults) {
                llmMessages.add(result)
                newMessages.add(result)
            }
        }

        emit(AgentEvent.TurnEnd(message, toolResults.toList()))

        if (toolCalls.isEmpty() || terminate) {
            emit(AgentEvent.AgentEnd(newMessages.toList()))
            return
        }
        emit(AgentEvent.TurnStart)
    }
}

/**
 * Streams one assistant response, folding provider events into message
 * lifecycle events. Ported from pi's streamAssistantResponse
 * (agent-loop.ts:281-378), reduced to the ported surface: no context
 * transform, no convertToLlm (the loop already maintains a Message[]
 * transcript), no API-key resolution. The provider stream is created and
 * collected exactly once per turn.
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
                        finalMessage = when (event) {
                            is AssistantMessageEvent.Done -> event.message
                            is AssistantMessageEvent.Error -> event.error
                        }
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
 * Fail all tool calls from an assistant message that was truncated by the
 * output token limit. Ported from pi's failToolCallsFromTruncatedMessage
 * (agent-loop.ts:381-409): none of the calls are safe to execute; each is
 * reported as an error so the model can re-issue them, in source order.
 *
 * Divergence: pi's tool calls already carry parsed argument objects;
 * Pathfinder's [ToolCall.arguments] is the provider's raw JSON string, so
 * the raw arguments are parsed best-effort here (empty object when they do
 * not parse) for the `tool_execution_start` event.
 */
private suspend fun failToolCallsFromTruncatedMessage(
    toolCalls: List<ToolCall>,
    emit: suspend (AgentEvent) -> Unit,
): ExecutedToolCallBatch {
    val messages = mutableListOf<ToolResultMessage>()
    for (toolCall in toolCalls) {
        emit(
            AgentEvent.ToolExecutionStart(
                toolCallId = toolCall.id,
                toolName = toolCall.name,
                arguments = parseRawArguments(toolCall.arguments) ?: JsonObject(emptyMap()),
            ),
        )
        val finalized = FinalizedToolCallOutcome(
            toolCall = toolCall,
            result = createErrorToolResult(
                "Tool call \"${toolCall.name}\" was not executed: the response hit the output token limit, " +
                    "so its arguments may be truncated. Re-issue the tool call with complete arguments.",
            ),
            isError = true,
        )
        emitToolExecutionEnd(finalized, emit)
        val toolResultMessage = createToolResultMessage(finalized)
        emitToolResultMessage(toolResultMessage, emit)
        messages.add(toolResultMessage)
    }
    return ExecutedToolCallBatch(messages = messages, terminate = false)
}

/**
 * Execute tool calls from an assistant message. Ported from pi's
 * executeToolCalls (agent-loop.ts:411-431): sequential when the configured
 * mode is sequential or any call in the batch targets a *known* tool whose
 * `executionMode` is sequential (unknown tools never change the mode);
 * otherwise parallel.
 */
private suspend fun executeToolCalls(
    context: AgentContext,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
): ExecutedToolCallBatch {
    val hasSequentialToolCall = toolCalls.any { toolCall ->
        context.tools.firstOrNull { it.definition.name == toolCall.name }
            ?.executionMode == ToolExecutionMode.SEQUENTIAL
    }
    return if (config.toolExecution == ToolExecutionMode.SEQUENTIAL || hasSequentialToolCall) {
        executeToolCallsSequential(context, toolCalls, config, emit)
    } else {
        executeToolCallsParallel(context, toolCalls, config, emit)
    }
}

/**
 * Ported from pi's executeToolCallsSequential (agent-loop.ts:433-487): each
 * tool call is started, prepared, executed, and finalized (including its
 * tool-result message pair) before the next one starts.
 *
 * Divergence: pi breaks the loop when `signal.aborted` becomes true after a
 * call; Kotlin cancellation is exceptional, so the loop instead re-checks
 * cancellation between calls ([ensureActiveBetweenCalls]) and the next
 * suspension (emit/execute) throws — cancellation propagates out of the run
 * instead of returning a partial batch, matching pathfinder's contract that
 * the loop emits no synthetic events on cancellation.
 */
private suspend fun executeToolCallsSequential(
    context: AgentContext,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
): ExecutedToolCallBatch {
    val finalizedCalls = mutableListOf<FinalizedToolCallOutcome>()
    val messages = mutableListOf<ToolResultMessage>()

    for (toolCall in toolCalls) {
        emitToolExecutionStart(toolCall, emit)

        val preparation = prepareToolCall(context, toolCall)
        val finalized = when (preparation) {
            is ImmediateToolCallOutcome ->
                FinalizedToolCallOutcome(toolCall, preparation.result, preparation.isError)
            is PreparedToolCall ->
                executeAndFinalizePreparedToolCall(preparation, emit)
        }

        emitToolExecutionEnd(finalized, emit)
        val toolResultMessage = createToolResultMessage(finalized)
        emitToolResultMessage(toolResultMessage, emit)
        finalizedCalls.add(finalized)
        messages.add(toolResultMessage)

        ensureActiveBetweenCalls()
    }

    return ExecutedToolCallBatch(
        messages = messages,
        terminate = shouldTerminateToolBatch(finalizedCalls),
    )
}

/**
 * Ported from pi's executeToolCallsParallel (agent-loop.ts:489-547):
 * `tool_execution_start` emission and lookup/validation run sequentially in
 * source order (preflight); immediate failures emit their
 * `tool_execution_end` right there; only after preflight do the
 * successfully prepared calls run concurrently (pi: `Promise.all` over
 * deferred entries; here one structured `coroutineScope` of `async` jobs).
 * Each execution emits its own `tool_execution_end` when it actually
 * completes (completion order); the tool-result message pairs are then
 * emitted in original source order.
 *
 * Divergences:
 * - pi's `signal.aborted` preflight break (agent-loop.ts:520-522/543-545) is
 *   a cancellation re-check ([ensureActiveBetweenCalls]); the next
 *   suspension throws instead.
 * - `emit` is invoked concurrently from the `async` jobs; the production
 *   [Agent] facade serializes emissions with a same-mutex emit, mirroring
 *   pi's serialized push into its EventStream.
 */
private suspend fun executeToolCallsParallel(
    context: AgentContext,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
): ExecutedToolCallBatch {
    val finalizedEntries = mutableListOf<suspend () -> FinalizedToolCallOutcome>()

    for (toolCall in toolCalls) {
        emitToolExecutionStart(toolCall, emit)

        val preparation = prepareToolCall(context, toolCall)
        if (preparation is ImmediateToolCallOutcome) {
            val finalized = FinalizedToolCallOutcome(toolCall, preparation.result, preparation.isError)
            emitToolExecutionEnd(finalized, emit)
            finalizedEntries.add { finalized }
            ensureActiveBetweenCalls()
            continue
        }
        check(preparation is PreparedToolCall)
        finalizedEntries.add {
            // Pi's deferred entry: execute, finalize, then emit the end event
            // when this call actually completes (agent-loop.ts:516-527).
            val finalized = executeAndFinalizePreparedToolCall(preparation, emit)
            emitToolExecutionEnd(finalized, emit)
            finalized
        }
        ensureActiveBetweenCalls()
    }

    val orderedFinalizedCalls = coroutineScope {
        finalizedEntries.map { entry -> async { entry() } }.awaitAll()
    }
    val messages = mutableListOf<ToolResultMessage>()
    for (finalized in orderedFinalizedCalls) {
        val toolResultMessage = createToolResultMessage(finalized)
        emitToolResultMessage(toolResultMessage, emit)
        messages.add(toolResultMessage)
    }

    return ExecutedToolCallBatch(
        messages = messages,
        terminate = shouldTerminateToolBatch(orderedFinalizedCalls),
    )
}

/** Pi's `signal?.aborted` loop-break re-check, expressed as cooperative cancellation (see callers). */
private suspend fun ensureActiveBetweenCalls() {
    currentCoroutineContext().ensureActive()
}

/**
 * Pi's shouldTerminateToolBatch (agent-loop.ts:577-579): the batch
 * terminates only when there is at least one finalized call and every
 * result has `terminate === true`. Pathfinder's [AgentToolResult] omits the
 * `terminate` field (it only participates in pi's hook early-termination
 * rule, which is out of scope), so this always returns false; the function
 * is kept for provenance and the future hook port.
 */
private fun shouldTerminateToolBatch(finalizedCalls: List<FinalizedToolCallOutcome>): Boolean = false

/** JSON instance used to parse raw tool-call argument strings; pi has no counterpart (arguments arrive parsed). */
private val toolArgumentsJson = Json

/**
 * Parses the provider's raw JSON arguments string into a [JsonObject], or
 * null when the string is malformed or not a JSON object.
 */
private fun parseRawArguments(raw: String): JsonObject? =
    try {
        toolArgumentsJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Ported from pi's prepareToolCall (agent-loop.ts:581-649), reduced to the
 * ported surface (no beforeToolCall hook, no abort signal):
 *
 * 1. Find the tool by exact name; missing → immediate error result
 *    `Tool <name> not found` (verbatim upstream).
 * 2. Parse [ToolCall.arguments] (raw JSON string) into a [JsonObject];
 *    malformed JSON or a non-object value is a validation failure with the
 *    stable message `Validation failed for tool "<name>": arguments are not
 *    a JSON object` — modeled on pi's validateToolArguments message shape
 *    (packages/ai/src/utils/validation.ts:347) because serialization
 *    exception messages are unstable. This parse has no upstream
 *    equivalent (pi's arguments arrive already parsed) — raw-string
 *    adaptation.
 * 3. `tool.validateArguments`, with the returned object copied so tools
 *    cannot mutate transcript-owned values (pi's structuredClone in
 *    validateToolArguments, validation.ts:326). Anything thrown flows into
 *    an immediate error result with the exception message, mirroring pi's
 *    catch-all around prepareArguments/validateToolArguments
 *    (agent-loop.ts:643-648).
 */
private fun prepareToolCall(
    context: AgentContext,
    toolCall: ToolCall,
): ToolCallPreparation {
    val tool = context.tools.firstOrNull { it.definition.name == toolCall.name }
        ?: return ImmediateToolCallOutcome(
            result = createErrorToolResult("Tool ${toolCall.name} not found"),
            isError = true,
        )

    return try {
        val parsed = parseRawArguments(toolCall.arguments)
            ?: throw IllegalArgumentException(
                "Validation failed for tool \"${toolCall.name}\": arguments are not a JSON object",
            )
        val validated = tool.validateArguments(parsed)
        PreparedToolCall(
            toolCall = toolCall,
            tool = tool,
            arguments = JsonObject(validated),
        )
    } catch (error: Throwable) {
        ImmediateToolCallOutcome(
            result = createErrorToolResult(error.message ?: error.toString()),
            isError = true,
        )
    }
}

/**
 * Executes a prepared tool call and finalizes it. Ported from pi's
 * executePreparedToolCall (agent-loop.ts:651-691) composed with
 * finalizeExecutedToolCall (agent-loop.ts:693-736), which is the identity
 * here (no afterToolCall hook).
 *
 * Update handling (pi's `acceptingUpdates` gate + awaiting pushed
 * emit-promises before the end event) is adapted to coroutines: a per-
 * execution unbounded [Channel] feeds a collector coroutine owned by the
 * current coroutine's scope. The non-suspending [AgentToolUpdateCallback]
 * `trySend`s into the channel (drops once closed, so post-settlement calls
 * are ignored); the collector emits `tool_execution_update`s in callback
 * order. After `execute` settles — success or failure — the channel is
 * closed and the collector joined, so accepted updates drain before the
 * end event is emitted. On cancellation the collector dies with its scope.
 *
 * Divergence: pi catches every exception from `execute` as a tool failure;
 * this port rethrows [CancellationException] so cancellation propagates to
 * the [Agent] facade, which synthesizes the terminal ABORTED message (pi
 * uses a cooperative AbortSignal checked by the tools themselves instead).
 */
private suspend fun executeAndFinalizePreparedToolCall(
    prepared: PreparedToolCall,
    emit: suspend (AgentEvent) -> Unit,
): FinalizedToolCallOutcome {
    val updates = Channel<AgentToolResult>(Channel.UNLIMITED)
    val collector = CoroutineScope(currentCoroutineContext()).launch {
        for (partialResult in updates) {
            emit(
                AgentEvent.ToolExecutionUpdate(
                    toolCallId = prepared.toolCall.id,
                    toolName = prepared.toolCall.name,
                    // Pi passes the tool call's own (parsed) arguments; this port
                    // uses the validated parsed object (raw-string adaptation).
                    arguments = prepared.arguments,
                    partialResult = partialResult,
                ),
            )
        }
    }

    val executed = try {
        val result = prepared.tool.execute(prepared.toolCall.id, prepared.arguments) { partialResult ->
            updates.trySend(partialResult)
        }
        updates.close()
        collector.join()
        ExecutedToolCallOutcome(result, isError = false)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        updates.close()
        collector.join()
        ExecutedToolCallOutcome(
            result = createErrorToolResult(error.message ?: error.toString()),
            isError = true,
        )
    }
    return FinalizedToolCallOutcome(
        toolCall = prepared.toolCall,
        result = executed.result,
        isError = executed.isError,
    )
}

/** Ported from pi's createErrorToolResult (agent-loop.ts:738-743): text content plus `details: {}`. */
private fun createErrorToolResult(message: String): AgentToolResult =
    AgentToolResult(
        content = listOf(TextContent(message)),
        // Pi's `details: {}` ported faithfully — an empty object, not null.
        details = JsonObject(emptyMap()),
    )

/** Ported from pi's emitToolExecutionEnd (agent-loop.ts:745-755). */
private suspend fun emitToolExecutionEnd(
    finalized: FinalizedToolCallOutcome,
    emit: suspend (AgentEvent) -> Unit,
) {
    emit(
        AgentEvent.ToolExecutionEnd(
            toolCallId = finalized.toolCall.id,
            toolName = finalized.toolCall.name,
            result = finalized.result,
            isError = finalized.isError,
        ),
    )
}

/**
 * Ported from pi's createToolResultMessage (agent-loop.ts:757-772). Pi's
 * `content ?? []` null-guard is unnecessary in Kotlin —
 * [AgentToolResult.content] is a non-null list.
 */
private fun createToolResultMessage(finalized: FinalizedToolCallOutcome): ToolResultMessage =
    ToolResultMessage(
        toolCallId = finalized.toolCall.id,
        toolName = finalized.toolCall.name,
        content = finalized.result.content,
        details = finalized.result.details,
        usage = finalized.result.usage,
        addedToolNames = finalized.result.addedToolNames,
        isError = finalized.isError,
        timestamp = System.currentTimeMillis(),
    )

/** Ported from pi's emitToolResultMessage (agent-loop.ts:774-778). */
private suspend fun emitToolResultMessage(
    toolResultMessage: ToolResultMessage,
    emit: suspend (AgentEvent) -> Unit,
) {
    emit(AgentEvent.MessageStart(toolResultMessage))
    emit(AgentEvent.MessageEnd(toolResultMessage))
}

/** Emits the tool-execution start with the raw parsed arguments (pi: the call's parsed arguments object). */
private suspend fun emitToolExecutionStart(
    toolCall: ToolCall,
    emit: suspend (AgentEvent) -> Unit,
) {
    emit(
        AgentEvent.ToolExecutionStart(
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            arguments = parseRawArguments(toolCall.arguments) ?: JsonObject(emptyMap()),
        ),
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
