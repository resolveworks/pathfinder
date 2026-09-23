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
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ThinkingLevel
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.toThinkingLevelOrNull
import works.resolve.pathfinder.ai.utils.ToolStateChanges
import works.resolve.pathfinder.ai.utils.getCurrentTools
import works.resolve.pathfinder.ai.utils.getToolStateChanges
import works.resolve.pathfinder.ai.utils.normalizeContext
import works.resolve.pathfinder.ai.utils.parseStreamingJson
import works.resolve.pathfinder.ai.utils.toToolDeclaration
import works.resolve.pathfinder.ai.utils.validateToolArguments

/**
 * Runs the agent loop: streams assistant turns and executes each response's
 * tool calls (sequentially or in parallel per [AgentLoopConfig.toolExecution]
 * and the tools' `executionMode`) until a response carries no tool calls or
 * its stop reason is `ERROR`/`ABORTED`. Returns the run's new messages in
 * source order.
 *
 * [context] is treated as an immutable snapshot; it is never mutated. The
 * transcript is normalized for every provider request: the system prompt and
 * tool declarations ride its system messages, never a separate context
 * field, so executor objects are never exposed to provider serialization.
 *
 * Divergence: pi's `prompt()` resolves normally after an abort; coroutine
 * cancellation cannot be swallowed without corrupting structured concurrency,
 * so the loop first terminates exactly like pi — partial assistant output
 * finalized with stopReason ABORTED, started tool calls finalized with error
 * results, the aborted batch's follow-up turn with its abort-finalized
 * assistant message, turn_end/agent_end emitted — and then rethrows the
 * [CancellationException] to the caller.
 */
suspend fun runAgentLoop(
    prompts: List<Message>,
    context: AgentContext,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
): List<Message> {
    val initialMessages = declareToolChanges(context, prompts, config.clock)
    val newMessages = initialMessages.toMutableList()
    val llmMessages = (context.messages + initialMessages).toMutableList()

    emit(AgentEvent.AgentStart)
    emit(AgentEvent.TurnStart)
    for (message in initialMessages) {
        emit(AgentEvent.MessageStart(message))
        emit(AgentEvent.MessageEnd(message))
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
    // Check for steering messages at start (user may have typed while waiting)
    var pendingMessages = config.getSteeringMessages?.invoke() ?: emptyList()

    // Outer loop: continues when queued follow-up messages arrive after the
    // agent would otherwise stop.
    while (true) {
        var hasMoreToolCalls = true

        // Inner loop: process tool calls and steering messages.
        while (hasMoreToolCalls || pendingMessages.isNotEmpty()) {
            var preparedMessages: List<Message> = emptyList()
            if (lastCompletedTurn != null) {
                val update = config.prepareNextTurn?.invoke(lastCompletedTurn)
                if (update != null) {
                    update.context?.let { refreshed ->
                        context = refreshed
                        llmMessages = refreshed.messages.toMutableList()
                    }
                    preparedMessages = update.messages ?: emptyList()
                    val nextModel = update.model
                    val nextThinkingLevel = update.thinkingLevel
                    if (nextModel != null || nextThinkingLevel != null) {
                        config = config.copy(
                            model = nextModel ?: config.model,
                            options = config.options.copy(
                                reasoning = nextTurnReasoning(
                                    config.options.reasoning,
                                    nextThinkingLevel
                                )
                            )
                        )
                    }
                }
                // Preparation can be long-running (for example, compaction).
                // Pick up steering queued while it ran. Only poll again if the
                // earlier poll returned nothing; otherwise one-at-a-time mode
                // would deliver two messages in this turn.
                if (pendingMessages.isEmpty()) {
                    pendingMessages = config.getSteeringMessages?.invoke() ?: emptyList()
                }
                emit(AgentEvent.TurnStart)
            }

            // pi keeps one live transcript on the context; this port syncs the
            // snapshot's messages from the working list before each request.
            context = context.copy(messages = llmMessages.toList())

            // Process prepared and queued messages before the next assistant
            // response.
            for (message in declareToolChanges(
                context,
                preparedMessages + pendingMessages,
                config.clock
            )) {
                emit(AgentEvent.MessageStart(message))
                emit(AgentEvent.MessageEnd(message))
                llmMessages.add(message)
                newMessages.add(message)
            }
            pendingMessages = emptyList()

            val message = streamAssistantResponse(
                llmContext = normalizeContext(Context(messages = llmMessages.toList())),
                config = config,
                emit = emit
            )
            newMessages.add(message)
            llmMessages.add(message)

            if (message.stopReason == StopReason.ERROR ||
                message.stopReason == StopReason.ABORTED
            ) {
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
            hasMoreToolCalls = false
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
                    hasMoreToolCalls = !executedToolBatch.terminate
                } catch (cancellation: CancellationException) {
                    // pi on abort: every started call finalizes — completed results
                    // are kept, in-flight and queued ones become "Operation aborted"
                    // error results — and the turn still ends with turn_end carrying
                    // whatever settled. pi then runs one more loop iteration whose
                    // provider request fails immediately on the aborted signal; see
                    // [emitAbortedFinalTurn].
                    val recoveredBatch =
                        withContext(NonCancellable) {
                            recoverAbortedToolBatch(toolCalls, progress, config, emit)
                        }
                    toolResults.addAll(recoveredBatch.messages)
                    for (result in toolResults) {
                        llmMessages.add(result)
                        newMessages.add(result)
                    }
                    withContext(NonCancellable) {
                        emit(AgentEvent.TurnEnd(message, toolResults.toList()))
                        if (!recoveredBatch.terminate) {
                            emitAbortedFinalTurn(
                                completedTurn = PrepareNextTurnContext(
                                    message = message,
                                    toolResults = toolResults.toList(),
                                    context = context.copy(messages = llmMessages.toList()),
                                    newMessages = newMessages.toList()
                                ),
                                config = config,
                                newMessages = newMessages,
                                emit = emit
                            )
                        }
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

            pendingMessages = config.getSteeringMessages?.invoke() ?: emptyList()
        }

        // The agent would stop here. Check for follow-up messages.
        val followUpMessages = config.getFollowUpMessages?.invoke() ?: emptyList()
        if (followUpMessages.isNotEmpty()) {
            // Set as pending so the inner loop processes them.
            pendingMessages = followUpMessages
            continue
        }

        // No more messages, exit.
        break
    }

    emit(AgentEvent.AgentEnd(newMessages.toList()))
}

/**
 * pi's prepareNextTurn reasoning mapping: an absent level keeps the configured
 * one, while "off" clears it.
 */
private fun nextTurnReasoning(current: ThinkingLevel?, level: ModelThinkingLevel?): ThinkingLevel? =
    when (level) {
        null -> current
        ModelThinkingLevel.OFF -> null
        else -> level.toThinkingLevelOrNull()
    }

/**
 * The final turn pi runs after an aborted tool batch: the next iteration's
 * provider request fails immediately on the aborted signal, and its finalized
 * output arrives as a terminal error event — empty content, stopReason
 * ABORTED, errorMessage "Request was aborted" — so the run ends through the
 * loop's normal aborted-message branch and the transcript keeps that message.
 *
 * pi invokes prepareNextTurn first (with the aborted signal) and applies its
 * update — including a model switch, which the provider's pre-allocated
 * output inherits — before emitting turn_start and any prepared messages.
 *
 * Adaptation: pi's loop keeps running synchronously after the abort signal
 * fires, so its hook merely observes the signal; coroutine cancellation
 * cannot be merely observed, so the caller runs this tail under
 * [NonCancellable] — the hook is invoked exactly once more and runs to
 * completion, consulting the owning session's abort state itself the way
 * pi's hooks consult the signal rather than coroutine cancellation. The
 * tail never reaches a provider, so the aborted message is materialized the
 * same way an aborted stream materializes its fold: an empty assistant
 * message copied to stopReason ABORTED with the abort error message.
 */
private suspend fun emitAbortedFinalTurn(
    completedTurn: PrepareNextTurnContext,
    config: AgentLoopConfig,
    newMessages: MutableList<Message>,
    emit: suspend (AgentEvent) -> Unit
) {
    var context = completedTurn.context
    var llmMessages = context.messages.toMutableList()
    var preparedMessages: List<Message> = emptyList()
    var turnConfig = config
    val update = config.prepareNextTurn?.invoke(completedTurn)
    if (update != null) {
        update.context?.let { refreshed ->
            context = refreshed
            llmMessages = refreshed.messages.toMutableList()
        }
        preparedMessages = update.messages ?: emptyList()
        val nextModel = update.model
        val nextThinkingLevel = update.thinkingLevel
        if (nextModel != null || nextThinkingLevel != null) {
            turnConfig = config.copy(
                model = nextModel ?: config.model,
                options = config.options.copy(
                    reasoning = nextTurnReasoning(config.options.reasoning, nextThinkingLevel)
                )
            )
        }
    }

    emit(AgentEvent.TurnStart)
    context = context.copy(messages = llmMessages.toList())
    for (message in declareToolChanges(context, preparedMessages, turnConfig.clock)) {
        emit(AgentEvent.MessageStart(message))
        emit(AgentEvent.MessageEnd(message))
        llmMessages.add(message)
        newMessages.add(message)
    }

    val aborted = turnConfig.emptyAssistantMessage().copy(
        stopReason = StopReason.ABORTED,
        errorMessage = ABORT_ERROR_MESSAGE
    )
    emit(AgentEvent.MessageStart(aborted))
    emit(AgentEvent.MessageEnd(aborted))
    newMessages.add(aborted)
    emit(AgentEvent.TurnEnd(aborted))
}

/**
 * Declare tool loadout changes to the model.
 *
 * [AgentContext.tools] is what the runtime can execute; the transcript's
 * system messages declare what the model may call. Before each request the
 * difference becomes `toolsAdded` and `toolsRemoved` on a system message.
 * When a pending system message exists, its tool fields are treated as
 * intent and replaced with the delta between the committed transcript and
 * the executable set, so replay always yields exactly the executable tools.
 * Otherwise a new system message is inserted before the first non-system
 * pending message.
 */
private fun declareToolChanges(
    context: AgentContext,
    pendingMessages: List<Message>,
    clock: Clock
): List<Message> {
    val systemIndex = pendingMessages.indexOfLast { it is SystemMessage }
    val pending = pendingMessages.getOrNull(systemIndex) as SystemMessage?
    val baseline = if (pending != null) {
        pendingMessages.mapIndexed { index, message ->
            if (index == systemIndex) withToolChanges(pending, NO_CHANGES) else message
        }
    } else {
        pendingMessages
    }
    val changes = getToolStateChanges(
        getCurrentTools(context.messages + baseline),
        context.tools.map { toToolDeclaration(it.definition) }
    )
    val unchanged = changes.toolsAdded.isEmpty() && changes.toolsRemoved.isEmpty()

    if (pending != null) {
        // Keep the caller's message when it already declares no tool changes.
        if (unchanged && pending.toolsAdded.isNullOrEmpty() &&
            pending.toolsRemoved.isNullOrEmpty()
        ) {
            return pendingMessages
        }
        return baseline.mapIndexed { index, message ->
            if (index == systemIndex) withToolChanges(pending, changes) else message
        }
    }
    if (unchanged) return pendingMessages
    val update = withToolChanges(
        SystemMessage(content = emptyList(), timestamp = clock.now().toEpochMilliseconds()),
        changes
    )
    val insertIndex = pendingMessages.indexOfFirst { it !is SystemMessage }
    val index = if (insertIndex == -1) pendingMessages.size else insertIndex
    return pendingMessages.subList(0, index) + update +
        pendingMessages.subList(index, pendingMessages.size)
}

private val NO_CHANGES = ToolStateChanges(toolsAdded = emptyList(), toolsRemoved = emptyList())

/** Copy a system message with its tool fields replaced by [changes]; empty lists omit the field. */
private fun withToolChanges(message: SystemMessage, changes: ToolStateChanges): SystemMessage =
    message.copy(
        toolsAdded = changes.toolsAdded.ifEmpty { null },
        toolsRemoved = changes.toolsRemoved.ifEmpty { null }
    )

/**
 * Streams one assistant response, folding provider events into message
 * lifecycle events. The provider stream is created and collected exactly
 * once per turn.
 *
 * Like pi — whose loop returns via `response.result()` at the terminal event
 * without draining the source — collection stops at the first terminal
 * Done/Error event. Cancellation mid-stream finalizes the accumulated
 * partial the way pi's providers do (their catch block pushes the finalized
 * output as the terminal error event; here the provider flow rethrows the
 * cancellation, so the loop folds the deltas since the last boundary
 * snapshot and finalizes the partial itself): stopReason ABORTED,
 * errorMessage set, partial content preserved.
 *
 * The terminal break cancels a child collector rather than using a
 * truncation operator: the break must carry the terminal message out of
 * the fold, and cancelling — unlike operator truncation, which aborts the
 * source mid-emission — lets the provider flow observe cancellation at its
 * next suspension point, the pinned upstream contract.
 */
private suspend fun streamAssistantResponse(
    llmContext: TranscriptContext,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
): AssistantMessage {
    val response = config.streamFn.stream(config.model, llmContext, config.options)

    var started = false
    val fold = AssistantStreamFold()
    var finalMessage: AssistantMessage? = null

    try {
        coroutineScope {
            launch {
                response.collect { event ->
                    when (event) {
                        is AssistantMessageEvent.Start -> {
                            started = true
                            fold.onBoundary(event.partial)
                            emit(AgentEvent.MessageStart(event.partial))
                        }

                        is AssistantMessageEvent.Boundary -> {
                            if (started) {
                                fold.onBoundary(event.partial)
                                emit(AgentEvent.MessageUpdate(event))
                            }
                        }

                        is AssistantMessageEvent.TextDelta -> {
                            if (started) {
                                fold.appendText(event.contentIndex, event.delta)
                                emit(AgentEvent.MessageUpdate(event))
                            }
                        }

                        is AssistantMessageEvent.ThinkingDelta -> {
                            if (started) {
                                fold.appendThinking(event.contentIndex, event.delta)
                                emit(AgentEvent.MessageUpdate(event))
                            }
                        }

                        is AssistantMessageEvent.ToolCallDelta -> {
                            if (started) {
                                fold.appendToolArguments(event.contentIndex, event.delta)
                                emit(AgentEvent.MessageUpdate(event))
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
        val aborted = (fold.materialize() ?: config.emptyAssistantMessage()).copy(
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
    // pi falls back to `response.result()` when the source ends without a
    // terminal event; the fold's materialized state is that result here, and
    // a stream that never started finalizes the pre-allocated empty output.
    val message = finalMessage ?: fold.materialize() ?: config.emptyAssistantMessage()
    if (!started) {
        // Setup/auth failures can arrive before any Start event; the message
        // still needs a message_start before message_end.
        emit(AgentEvent.MessageStart(message))
    }
    emit(AgentEvent.MessageEnd(message))
    return message
}

/**
 * Folds a provider stream so an aborted run can still finalize the
 * accumulated partial (pi's providers do this themselves by pushing the
 * live output as the terminal error event; here cancellation propagates
 * out of the provider flow, so the loop materializes at its own boundary).
 *
 * Boundary snapshots are accurate as emitted: text/thinking content is the
 * base plus the deltas appended since the last boundary (providers append
 * those incrementally). Tool-call argument deltas are not plain text —
 * Google emits the complete arguments as a single redundant delta after a
 * complete start scaffold, while other providers stream JSON fragments — so
 * at materialize the joined fragments are salvaged into the arguments
 * object through the streaming parser, the same parse the providers run at
 * every fragment, and replace the scaffold's arguments whenever any
 * arrived.
 */
private class AssistantStreamFold {
    private var boundary: AssistantMessage? = null
    private val textSinceBoundary = mutableMapOf<Int, StringBuilder>()
    private val thinkingSinceBoundary = mutableMapOf<Int, StringBuilder>()
    private val toolArguments = mutableMapOf<Int, StringBuilder>()

    fun onBoundary(partial: AssistantMessage) {
        boundary = partial
        textSinceBoundary.clear()
        thinkingSinceBoundary.clear()
    }

    fun appendText(contentIndex: Int, delta: String) {
        textSinceBoundary.getOrPut(contentIndex) { StringBuilder() }.append(delta)
    }

    fun appendThinking(contentIndex: Int, delta: String) {
        thinkingSinceBoundary.getOrPut(contentIndex) { StringBuilder() }.append(delta)
    }

    fun appendToolArguments(contentIndex: Int, delta: String) {
        toolArguments.getOrPut(contentIndex) { StringBuilder() }.append(delta)
    }

    fun materialize(): AssistantMessage? {
        val base = boundary ?: return null
        return base.copy(
            content = base.content.mapIndexed { index, block ->
                when (block) {
                    is TextContent ->
                        textSinceBoundary[index]
                            ?.let { block.copy(text = block.text + it.toString()) }
                            ?: block

                    is ThinkingContent ->
                        thinkingSinceBoundary[index]
                            ?.let { block.copy(thinking = block.thinking + it.toString()) }
                            ?: block

                    is ToolCall ->
                        toolArguments[index]
                            ?.let { args ->
                                block.copy(arguments = parseStreamingJson(args.toString()))
                            }
                            ?: block

                    else -> block
                }
            }
        )
    }
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
 * message pairs in source order, returning the settled results for the
 * terminal turn_end together with the batch's terminate verdict — pi's
 * post-abort batch still ends the run when every settled result terminates.
 */
private suspend fun recoverAbortedToolBatch(
    toolCalls: List<ToolCall>,
    progress: ToolBatchProgress,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
): ExecutedToolCallBatch {
    val messages = mutableListOf<ToolResultMessage>()
    val finalizedCalls = mutableListOf<FinalizedToolCallOutcome>()
    for (toolCall in progress.startedToolCalls(toolCalls)) {
        val outcome = progress.outcome(toolCall.id)
            ?: FinalizedToolCallOutcome(
                toolCall = toolCall,
                result = createErrorToolResult(OPERATION_ABORTED),
                isError = true
            )
        finalizedCalls.add(outcome)
        if (!progress.isEnded(toolCall.id)) {
            emitToolExecutionEnd(outcome, emit, progress)
        }
        val toolResultMessage = progress.resultMessage(toolCall.id)
            ?: createToolResultMessage(outcome, config.clock).also {
                emitToolResultMessage(it, emit, progress)
            }
        messages.add(toolResultMessage)
    }
    return ExecutedToolCallBatch(
        messages = messages,
        terminate = shouldTerminateToolBatch(finalizedCalls)
    )
}

/**
 * Fails every tool call from a message truncated by the output token limit:
 * none are safe to execute, so each is reported as an error the model can
 * re-issue, in source order.
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

/**
 * Finds the tool by exact name, applies its optional `prepareArguments`
 * shim, and validates the arguments against the tool's schema — the port of
 * pi's `validateToolArguments` (agent-loop.ts parity). Any failure — missing
 * tool, or a throw from either hook — becomes an immediate error result.
 *
 * The validated map is a fresh object, so a tool cannot mutate
 * transcript-owned values.
 */
private fun prepareToolCall(context: AgentContext, toolCall: ToolCall): ToolCallPreparation {
    val tool = context.tools.firstOrNull { it.definition.name == toolCall.name }
        ?: return ImmediateToolCallOutcome(
            result = createErrorToolResult("Tool ${toolCall.name} not found"),
            isError = true
        )

    return try {
        val preparedArguments =
            tool.prepareArguments?.invoke(toolCall.arguments) ?: toolCall.arguments
        val validated = validateToolArguments(
            tool.definition,
            toolCall.copy(arguments = preparedArguments)
        )
        PreparedToolCall(
            toolCall = toolCall,
            tool = tool,
            arguments = validated
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
                    arguments = prepared.toolCall.arguments,
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
            arguments = toolCall.arguments
        )
    )
    progress.recordStarted(toolCall.id)
}
