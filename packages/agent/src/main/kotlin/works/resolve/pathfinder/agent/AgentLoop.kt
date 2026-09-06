package works.resolve.pathfinder.agent

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.toThinkingLevelOrNull
import works.resolve.pathfinder.ai.utils.lenientJson

/**
 * Runs the agent loop: streams assistant turns and executes each response's
 * tool calls (sequentially or in parallel per [AgentLoopConfig.toolExecution]
 * and the tools' `executionMode`) until a response carries no tool calls or
 * its stop reason is `ERROR`/`ABORTED`. Returns the run's new messages in
 * source order.
 *
 * [context] is treated as an immutable snapshot; it is never mutated. A fresh
 * [Context] (tool definitions only) is projected for every provider request —
 * executor objects are never exposed to provider serialization.
 *
 * Divergence: pi's `prompt()` resolves normally after an abort; coroutine
 * cancellation cannot be swallowed without corrupting structured concurrency,
 * so the loop first terminates exactly like pi — partial assistant output
 * finalized with stopReason ABORTED, started tool calls finalized with error
 * results, turn_end/agent_end emitted — and then rethrows the
 * [CancellationException] to the caller.
 */
suspend fun runAgentLoop(
    prompts: List<Message>,
    context: AgentContext,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
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

private class ExecutedToolCallBatch(val messages: List<ToolResultMessage>, val terminate: Boolean)

private sealed interface ToolCallPreparation

private class PreparedToolCall(
    val toolCall: ToolCall,
    val tool: AgentTool,
    /** Validated arguments used for execution. */
    val arguments: JsonObject
) : ToolCallPreparation

private class ImmediateToolCallOutcome(val result: AgentToolResult, val isError: Boolean) :
    ToolCallPreparation

private class FinalizedToolCallOutcome(
    val toolCall: ToolCall,
    val result: AgentToolResult,
    val isError: Boolean
)

private class ExecutedToolCallOutcome(val result: AgentToolResult, val isError: Boolean)

/**
 * Turn loop: streams one assistant turn, executes its tool batch, appends
 * the results, and repeats until the batch ends the run. A `turn_start`
 * precedes every assistant turn exactly once.
 *
 * Divergence: pi pushes streaming partials into its context and replaces
 * them with the final message; this port appends only the final message
 * (nothing observes partials without steering/hooks).
 */
private suspend fun runLoop(
    initialContext: AgentContext,
    initialLlmMessages: MutableList<Message>,
    newMessages: MutableList<Message>,
    initialConfig: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
) {
    var context = initialContext
    var llmMessages = initialLlmMessages
    var config = initialConfig
    var lastCompletedTurn: PrepareNextTurnContext? = null
    while (true) {
        if (lastCompletedTurn != null) {
            val update = config.prepareNextTurn?.invoke(lastCompletedTurn)
            if (update != null) {
                update.context?.let { refreshed ->
                    context = refreshed
                    llmMessages = refreshed.messages.toMutableList()
                }
                val nextModel = update.model
                val nextThinkingLevel = update.thinkingLevel
                if (nextModel != null || nextThinkingLevel != null) {
                    config = config.copy(
                        model = nextModel ?: config.model,
                        options = config.options.copy(
                            reasoning = nextThinkingLevel?.toThinkingLevelOrNull()
                                ?: config.options.reasoning
                        )
                    )
                }
            }
            emit(AgentEvent.TurnStart)
        }

        val message = streamAssistantResponse(
            llmContext = Context(
                systemPrompt = context.systemPrompt,
                messages = llmMessages.toList(),
                tools = context.tools.map { it.definition }
            ),
            config = config,
            emit = emit
        )
        newMessages.add(message)
        llmMessages.add(message)

        if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
            // The ABORTED case runs in an already-cancelled coroutine; the
            // terminal events must still be delivered.
            withContext(NonCancellable) {
                emit(AgentEvent.TurnEnd(message))
                emit(AgentEvent.AgentEnd(newMessages.toList()))
            }
            return
        }

        val toolCalls = message.content.filterIsInstance<ToolCall>()
        val toolResults = mutableListOf<ToolResultMessage>()
        var terminate = false
        if (toolCalls.isNotEmpty()) {
            val progress = ToolBatchProgress()
            try {
                // A "length" stop means the output was cut off by the token
                // limit, so every tool call in the message may carry truncated
                // arguments — fail them all rather than execute potentially
                // broken calls.
                val executedToolBatch =
                    if (message.stopReason == StopReason.LENGTH) {
                        failToolCallsFromTruncatedMessage(toolCalls, config, emit, progress)
                    } else {
                        executeToolCalls(context, toolCalls, config, emit, progress)
                    }
                toolResults.addAll(executedToolBatch.messages)
                terminate = executedToolBatch.terminate
            } catch (cancellation: CancellationException) {
                // pi on abort: every started call finalizes — completed results
                // are kept, in-flight and queued ones become "Operation aborted"
                // error results — and the turn still ends with turn_end carrying
                // whatever settled.
                toolResults.addAll(
                    withContext(NonCancellable) {
                        recoverAbortedToolBatch(toolCalls, progress, config, emit)
                    }
                )
                for (result in toolResults) {
                    llmMessages.add(result)
                    newMessages.add(result)
                }
                withContext(NonCancellable) {
                    emit(AgentEvent.TurnEnd(message, toolResults.toList()))
                    emit(AgentEvent.AgentEnd(newMessages.toList()))
                }
                throw cancellation
            }
            for (result in toolResults) {
                llmMessages.add(result)
                newMessages.add(result)
            }
        }

        emit(AgentEvent.TurnEnd(message, toolResults.toList()))

        lastCompletedTurn = PrepareNextTurnContext(
            message = message,
            toolResults = toolResults.toList(),
            context = context.copy(messages = llmMessages.toList()),
            newMessages = newMessages.toList()
        )

        if (toolCalls.isEmpty() || terminate) {
            emit(AgentEvent.AgentEnd(newMessages.toList()))
            return
        }
    }
}

/**
 * Streams one assistant response, folding provider events into message
 * lifecycle events. The provider stream is created and collected exactly
 * once per turn.
 *
 * Like pi — whose loop returns via `response.result()` at the terminal event
 * without draining the source — collection stops at the first terminal
 * Done/Error event. Cancellation mid-stream finalizes the accumulated
 * partial the way pi's providers do (their catch block pushes the finalized
 * output as the terminal error event): stopReason ABORTED, errorMessage set,
 * partial content preserved.
 */
private suspend fun streamAssistantResponse(
    llmContext: Context,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
): AssistantMessage {
    val response = config.streamFn.stream(config.model, llmContext, config.options)

    var started = false
    var latestPartial: AssistantMessage? = null
    var finalMessage: AssistantMessage? = null

    try {
        coroutineScope {
            launch {
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
                        is AssistantMessageEvent.ToolCallEnd
                        -> {
                            if (started) {
                                latestPartial = event.partial
                                emit(AgentEvent.MessageUpdate(event.partial, event))
                            }
                        }

                        is AssistantMessageEvent.Done,
                        is AssistantMessageEvent.Error
                        -> {
                            finalMessage = when (event) {
                                is AssistantMessageEvent.Done -> event.message
                                is AssistantMessageEvent.Error -> event.error
                            }
                            this@launch.cancel()
                        }
                    }
                }
            }.join()
        }
    } catch (cancellation: CancellationException) {
        // pi's providers finalize the accumulated output on abort; with no
        // events received the output is an empty message with the run's model
        // metadata, exactly like pi's pre-allocated assistant output.
        val aborted = (latestPartial ?: config.emptyAssistantMessage()).copy(
            stopReason = StopReason.ABORTED,
            errorMessage = ABORT_ERROR_MESSAGE
        )
        withContext(NonCancellable) {
            if (!started) {
                emit(AgentEvent.MessageStart(aborted))
            }
            emit(AgentEvent.MessageEnd(aborted))
        }
        return aborted
    }
    var message = finalMessage
        // The StreamFn contract guarantees a terminal Done/Error event; a
        // stream that completes without one is a contract violation.
        ?: throw IllegalStateException("Provider stream completed without a terminal event")
    if (!started) {
        // Setup/auth failures can arrive before any Start event; the message
        // still needs a message_start before message_end.
        emit(AgentEvent.MessageStart(message))
    }
    emit(AgentEvent.MessageEnd(message))
    return message
}

/**
 * Progress of one tool batch, shared with the run loop so an aborted batch
 * can be finalized the way pi's abort path does: calls that never started
 * get nothing, completed results are kept, and started-but-unsettled calls
 * become "Operation aborted" error results. Accessed from concurrent
 * parallel entries.
 */
private class ToolBatchProgress {
    private val lock = Any()

    private val startedIds = LinkedHashSet<String>()
    private val outcomes = HashMap<String, FinalizedToolCallOutcome>()
    private val endedIds = HashSet<String>()
    private val resultMessages = HashMap<String, ToolResultMessage>()

    fun recordStarted(toolCallId: String) = synchronized(lock) { startedIds.add(toolCallId) }

    fun recordOutcome(finalized: FinalizedToolCallOutcome) = synchronized(lock) {
        outcomes[finalized.toolCall.id] = finalized
    }

    fun recordEnded(toolCallId: String) = synchronized(lock) { endedIds.add(toolCallId) }

    fun recordResultMessage(message: ToolResultMessage) = synchronized(lock) {
        resultMessages[message.toolCallId] = message
    }

    fun startedToolCalls(toolCalls: List<ToolCall>): List<ToolCall> = synchronized(lock) {
        toolCalls.filter { it.id in startedIds }
    }

    fun outcome(toolCallId: String): FinalizedToolCallOutcome? = synchronized(lock) {
        outcomes[toolCallId]
    }

    fun isEnded(toolCallId: String): Boolean = synchronized(lock) { toolCallId in endedIds }

    fun resultMessage(toolCallId: String): ToolResultMessage? = synchronized(lock) {
        resultMessages[toolCallId]
    }
}

/**
 * Finalizes an aborted tool batch: emits the missing tool_execution_end events
 * ("Operation aborted" for calls that never settled) and the tool-result
 * message pairs in source order, returning the full result list for the
 * terminal turn_end.
 */
private suspend fun recoverAbortedToolBatch(
    toolCalls: List<ToolCall>,
    progress: ToolBatchProgress,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
): List<ToolResultMessage> {
    val messages = mutableListOf<ToolResultMessage>()
    for (toolCall in progress.startedToolCalls(toolCalls)) {
        val outcome = progress.outcome(toolCall.id)
            ?: FinalizedToolCallOutcome(
                toolCall = toolCall,
                result = createErrorToolResult(OPERATION_ABORTED),
                isError = true
            )
        if (!progress.isEnded(toolCall.id)) {
            emitToolExecutionEnd(outcome, emit, progress)
        }
        val toolResultMessage = progress.resultMessage(toolCall.id)
            ?: createToolResultMessage(outcome, config.clock).also {
                emitToolResultMessage(it, emit, progress)
            }
        messages.add(toolResultMessage)
    }
    return messages
}

/**
 * Fails every tool call from a message truncated by the output token limit:
 * none are safe to execute, so each is reported as an error the model can
 * re-issue, in source order.
 *
 * Divergence: pi's tool calls carry parsed argument objects; here
 * [ToolCall.arguments] is a raw JSON string, parsed best-effort (empty
 * object when it does not parse) for the `tool_execution_start` event.
 */
private suspend fun failToolCallsFromTruncatedMessage(
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
    progress: ToolBatchProgress
): ExecutedToolCallBatch {
    val messages = mutableListOf<ToolResultMessage>()
    for (toolCall in toolCalls) {
        emitToolExecutionStart(toolCall, emit, progress)
        val finalized = FinalizedToolCallOutcome(
            toolCall = toolCall,
            result = createErrorToolResult(
                "Tool call \"${toolCall.name}\" was not executed: the response hit the " +
                    "output token limit, so its arguments may be truncated. Re-issue the tool " +
                    "call with complete arguments."
            ),
            isError = true
        )
        emitToolExecutionEnd(finalized, emit, progress)
        val toolResultMessage = createToolResultMessage(finalized, config.clock)
        emitToolResultMessage(toolResultMessage, emit, progress)
        messages.add(toolResultMessage)
    }
    return ExecutedToolCallBatch(messages = messages, terminate = false)
}

/**
 * Sequential when the configured mode is sequential or any call targets a
 * *known* tool whose `executionMode` is sequential (unknown tools never
 * change the mode); otherwise parallel.
 */
private suspend fun executeToolCalls(
    context: AgentContext,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
    progress: ToolBatchProgress
): ExecutedToolCallBatch {
    val hasSequentialToolCall = toolCalls.any { toolCall ->
        context.tools.firstOrNull { it.definition.name == toolCall.name }
            ?.executionMode == ToolExecutionMode.SEQUENTIAL
    }
    return if (config.toolExecution == ToolExecutionMode.SEQUENTIAL || hasSequentialToolCall) {
        executeToolCallsSequential(context, toolCalls, config, emit, progress)
    } else {
        executeToolCallsParallel(context, toolCalls, config, emit, progress)
    }
}

/**
 * Each tool call is started, prepared, executed, and finalized (including
 * its tool-result message pair) before the next one starts.
 *
 * Divergence: pi breaks the loop when its abort signal fires after a call;
 * cancellation here is exceptional, so it propagates and the run loop
 * finalizes the batch (remaining calls get nothing, exactly like pi's
 * post-break calls).
 */
private suspend fun executeToolCallsSequential(
    context: AgentContext,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
    progress: ToolBatchProgress
): ExecutedToolCallBatch {
    val finalizedCalls = mutableListOf<FinalizedToolCallOutcome>()
    val messages = mutableListOf<ToolResultMessage>()

    for (toolCall in toolCalls) {
        emitToolExecutionStart(toolCall, emit, progress)

        val preparation = prepareToolCall(context, toolCall)
        val finalized = when (preparation) {
            is ImmediateToolCallOutcome ->
                FinalizedToolCallOutcome(toolCall, preparation.result, preparation.isError)

            is PreparedToolCall ->
                executeAndFinalizePreparedToolCall(preparation, emit)
        }

        emitToolExecutionEnd(finalized, emit, progress)
        val toolResultMessage = createToolResultMessage(finalized, config.clock)
        emitToolResultMessage(toolResultMessage, emit, progress)
        finalizedCalls.add(finalized)
        messages.add(toolResultMessage)

        ensureActiveBetweenCalls()
    }

    return ExecutedToolCallBatch(
        messages = messages,
        terminate = shouldTerminateToolBatch(finalizedCalls)
    )
}

/**
 * Start events and lookup/validation run sequentially in source order
 * (preflight); immediate failures emit their end event during preflight.
 * Prepared calls then run concurrently, each emitting its end event on
 * completion (completion order); the tool-result message pairs are emitted
 * in source order afterwards.
 *
 * Divergences:
 * - pi's abort-signal preflight break and per-entry abort check are
 *   replaced by coroutine cancellation: entries still running are cancelled
 *   and the run loop finalizes them as "Operation aborted" results.
 * - `emit` is invoked concurrently from the async jobs; the production
 *   [Agent] facade serializes emissions.
 */
private suspend fun executeToolCallsParallel(
    context: AgentContext,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit,
    progress: ToolBatchProgress
): ExecutedToolCallBatch {
    val finalizedEntries = mutableListOf<suspend () -> FinalizedToolCallOutcome>()

    for (toolCall in toolCalls) {
        emitToolExecutionStart(toolCall, emit, progress)

        val preparation = prepareToolCall(context, toolCall)
        if (preparation is ImmediateToolCallOutcome) {
            val finalized =
                FinalizedToolCallOutcome(toolCall, preparation.result, preparation.isError)
            emitToolExecutionEnd(finalized, emit, progress)
            finalizedEntries.add { finalized }
            ensureActiveBetweenCalls()
            continue
        }
        check(preparation is PreparedToolCall)
        finalizedEntries.add {
            val finalized = executeAndFinalizePreparedToolCall(preparation, emit)
            emitToolExecutionEnd(finalized, emit, progress)
            finalized
        }
        ensureActiveBetweenCalls()
    }

    val orderedFinalizedCalls = coroutineScope {
        finalizedEntries.map { entry -> async { entry() } }.awaitAll()
    }
    val messages = mutableListOf<ToolResultMessage>()
    for (finalized in orderedFinalizedCalls) {
        val toolResultMessage = createToolResultMessage(finalized, config.clock)
        emitToolResultMessage(toolResultMessage, emit, progress)
        messages.add(toolResultMessage)
    }

    return ExecutedToolCallBatch(
        messages = messages,
        terminate = shouldTerminateToolBatch(orderedFinalizedCalls)
    )
}

/** Cancellation re-check standing in for pi's abort-signal loop break. */
private suspend fun ensureActiveBetweenCalls() {
    currentCoroutineContext().ensureActive()
}

/** pi's rule: terminate only when the batch is non-empty and every result sets it. */
private fun shouldTerminateToolBatch(finalizedCalls: List<FinalizedToolCallOutcome>): Boolean =
    finalizedCalls.isNotEmpty() && finalizedCalls.all { it.result.terminate == true }

private val toolArgumentsJson = lenientJson

/**
 * Parses the provider's raw JSON arguments string into a [JsonObject], or
 * null when the string is malformed or not a JSON object.
 */
private fun parseRawArguments(raw: String): JsonObject? = try {
    toolArgumentsJson.parseToJsonElement(raw) as? JsonObject
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Finds the tool by exact name, parses [ToolCall.arguments] (a raw JSON
 * string) into a [JsonObject], and validates them. Any failure — missing
 * tool, non-object arguments (given a stable message rather than an
 * unstable serialization exception message), or a throw from
 * `validateArguments` — becomes an immediate error result.
 *
 * The validated map is copied so a tool cannot mutate transcript-owned
 * values.
 */
private fun prepareToolCall(context: AgentContext, toolCall: ToolCall): ToolCallPreparation {
    val tool = context.tools.firstOrNull { it.definition.name == toolCall.name }
        ?: return ImmediateToolCallOutcome(
            result = createErrorToolResult("Tool ${toolCall.name} not found"),
            isError = true
        )

    return try {
        val parsed = parseRawArguments(toolCall.arguments)
            ?: throw IllegalArgumentException(
                "Validation failed for tool \"${toolCall.name}\": arguments are not a JSON object"
            )
        val validated = tool.validateArguments(parsed)
        PreparedToolCall(
            toolCall = toolCall,
            tool = tool,
            arguments = JsonObject(validated)
        )
    } catch (error: Throwable) {
        ImmediateToolCallOutcome(
            result = createErrorToolResult(error.message ?: error.toString()),
            isError = true
        )
    }
}

/**
 * Executes a prepared tool call and finalizes it.
 *
 * Updates flow through a per-execution unbounded [Channel] into a collector
 * coroutine: the non-suspending [AgentToolUpdateCallback] `trySend`s (a
 * send after settlement is dropped) and the collector emits
 * `tool_execution_update`s in callback order. The channel is closed and the
 * collector joined once `execute` settles, so accepted updates always drain
 * before the end event is emitted.
 *
 * Divergence: pi catches every `execute` exception as a tool failure; here
 * [CancellationException] is rethrown so the run loop finalizes the aborted
 * batch with an "Operation aborted" error result, mirroring pi's abort path.
 */
private suspend fun executeAndFinalizePreparedToolCall(
    prepared: PreparedToolCall,
    emit: suspend (AgentEvent) -> Unit
): FinalizedToolCallOutcome {
    val updates = Channel<AgentToolResult>(Channel.UNLIMITED)
    val collector = CoroutineScope(currentCoroutineContext()).launch {
        for (partialResult in updates) {
            emit(
                AgentEvent.ToolExecutionUpdate(
                    toolCallId = prepared.toolCall.id,
                    toolName = prepared.toolCall.name,
                    // Pi passes the call's original arguments; this port passes
                    // the validated object.
                    arguments = prepared.arguments,
                    partialResult = partialResult
                )
            )
        }
    }

    val executed = try {
        val result = prepared.tool.execute(
            prepared.toolCall.id,
            prepared.arguments
        ) { partialResult ->
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
            isError = true
        )
    }
    return FinalizedToolCallOutcome(
        toolCall = prepared.toolCall,
        result = executed.result,
        isError = executed.isError
    )
}

private fun createErrorToolResult(message: String): AgentToolResult = AgentToolResult(
    content = listOf(TextContent(message)),
    // An empty object, not null — mirrors pi's `details: {}`.
    details = JsonObject(emptyMap())
)

private fun AgentLoopConfig.emptyAssistantMessage(): AssistantMessage = AssistantMessage(
    content = emptyList(),
    api = model.api,
    provider = model.provider,
    model = model.id,
    timestamp = clock.now().toEpochMilliseconds()
)

private const val ABORT_ERROR_MESSAGE = "Request was aborted"

/** pi's error result for tool calls that never ran because the run aborted. */
private const val OPERATION_ABORTED = "Operation aborted"

private suspend fun emitToolExecutionEnd(
    finalized: FinalizedToolCallOutcome,
    emit: suspend (AgentEvent) -> Unit,
    progress: ToolBatchProgress
) {
    progress.recordOutcome(finalized)
    emit(
        AgentEvent.ToolExecutionEnd(
            toolCallId = finalized.toolCall.id,
            toolName = finalized.toolCall.name,
            result = finalized.result,
            isError = finalized.isError
        )
    )
    progress.recordEnded(finalized.toolCall.id)
}

private fun createToolResultMessage(
    finalized: FinalizedToolCallOutcome,
    clock: Clock
): ToolResultMessage = ToolResultMessage(
    toolCallId = finalized.toolCall.id,
    toolName = finalized.toolCall.name,
    content = finalized.result.content,
    details = finalized.result.details,
    usage = finalized.result.usage,
    addedToolNames = finalized.result.addedToolNames,
    isError = finalized.isError,
    timestamp = clock.now().toEpochMilliseconds()
)

private suspend fun emitToolResultMessage(
    toolResultMessage: ToolResultMessage,
    emit: suspend (AgentEvent) -> Unit,
    progress: ToolBatchProgress
) {
    emit(AgentEvent.MessageStart(toolResultMessage))
    emit(AgentEvent.MessageEnd(toolResultMessage))
    progress.recordResultMessage(toolResultMessage)
}

private suspend fun emitToolExecutionStart(
    toolCall: ToolCall,
    emit: suspend (AgentEvent) -> Unit,
    progress: ToolBatchProgress
) {
    emit(
        AgentEvent.ToolExecutionStart(
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            arguments = parseRawArguments(toolCall.arguments) ?: JsonObject(emptyMap())
        )
    )
    progress.recordStarted(toolCall.id)
}
