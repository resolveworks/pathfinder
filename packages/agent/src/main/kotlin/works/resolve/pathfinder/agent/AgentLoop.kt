package works.resolve.pathfinder.agent

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
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
 * executor objects are never exposed to provider serialization. Coroutine
 * cancellation propagates as [CancellationException] without a synthetic
 * error or [AgentEvent.AgentEnd].
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
    context: AgentContext,
    llmMessages: MutableList<Message>,
    newMessages: MutableList<Message>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
) {
    while (true) {
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
            emit(AgentEvent.TurnEnd(message))
            emit(AgentEvent.AgentEnd(newMessages.toList()))
            return
        }

        val toolCalls = message.content.filterIsInstance<ToolCall>()
        val toolResults = mutableListOf<ToolResultMessage>()
        var terminate = false
        if (toolCalls.isNotEmpty()) {
            // A "length" stop means the output was cut off by the token limit, so
            // every tool call in the message may carry truncated arguments — fail
            // them all rather than execute potentially broken calls.
            val executedToolBatch =
                if (message.stopReason == StopReason.LENGTH) {
                    failToolCallsFromTruncatedMessage(toolCalls, config.clock, emit)
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
 * lifecycle events. The provider stream is created and collected exactly
 * once per turn.
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

    // Collected in a child job cancelled as soon as the first terminal
    // Done/Error event is observed: a provider that hangs after emitting its
    // terminal event cannot stall the agent, and only the first terminal
    // event is observed. External cancellation propagates normally out of
    // coroutineScope without synthetic events.
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
                errorMessage = "Provider stream completed without a terminal event"
            )
        } else {
            unsupportedErrorMessage(config.model, config.clock.now().toEpochMilliseconds())
        }
        if (!started) {
            emit(AgentEvent.MessageStart(message))
        }
    }
    emit(AgentEvent.MessageEnd(message))
    return message
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
    clock: Clock,
    emit: suspend (AgentEvent) -> Unit
): ExecutedToolCallBatch {
    val messages = mutableListOf<ToolResultMessage>()
    for (toolCall in toolCalls) {
        emit(
            AgentEvent.ToolExecutionStart(
                toolCallId = toolCall.id,
                toolName = toolCall.name,
                arguments = parseRawArguments(toolCall.arguments) ?: JsonObject(emptyMap())
            )
        )
        val finalized = FinalizedToolCallOutcome(
            toolCall = toolCall,
            result = createErrorToolResult(
                "Tool call \"${toolCall.name}\" was not executed: the response hit the " +
                    "output token limit, so its arguments may be truncated. Re-issue the tool " +
                    "call with complete arguments."
            ),
            isError = true
        )
        emitToolExecutionEnd(finalized, emit)
        val toolResultMessage = createToolResultMessage(finalized, clock)
        emitToolResultMessage(toolResultMessage, emit)
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
    emit: suspend (AgentEvent) -> Unit
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
 * Each tool call is started, prepared, executed, and finalized (including
 * its tool-result message pair) before the next one starts.
 *
 * Divergence: pi breaks the loop when its abort signal fires after a call;
 * cancellation here is exceptional, so it is re-checked between calls
 * ([ensureActiveBetweenCalls]) and the next suspension throws — the run
 * propagates cancellation instead of returning a partial batch.
 */
private suspend fun executeToolCallsSequential(
    context: AgentContext,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
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
        val toolResultMessage = createToolResultMessage(finalized, config.clock)
        emitToolResultMessage(toolResultMessage, emit)
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
 * - pi's abort-signal preflight break is a cancellation re-check
 *   ([ensureActiveBetweenCalls]); the next suspension throws instead.
 * - `emit` is invoked concurrently from the async jobs; the production
 *   [Agent] facade serializes emissions.
 */
private suspend fun executeToolCallsParallel(
    context: AgentContext,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: suspend (AgentEvent) -> Unit
): ExecutedToolCallBatch {
    val finalizedEntries = mutableListOf<suspend () -> FinalizedToolCallOutcome>()

    for (toolCall in toolCalls) {
        emitToolExecutionStart(toolCall, emit)

        val preparation = prepareToolCall(context, toolCall)
        if (preparation is ImmediateToolCallOutcome) {
            val finalized =
                FinalizedToolCallOutcome(toolCall, preparation.result, preparation.isError)
            emitToolExecutionEnd(finalized, emit)
            finalizedEntries.add { finalized }
            ensureActiveBetweenCalls()
            continue
        }
        check(preparation is PreparedToolCall)
        finalizedEntries.add {
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
        val toolResultMessage = createToolResultMessage(finalized, config.clock)
        emitToolResultMessage(toolResultMessage, emit)
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

/** Always false: [AgentToolResult] has no `terminate` field. */
private fun shouldTerminateToolBatch(finalizedCalls: List<FinalizedToolCallOutcome>): Boolean =
    false

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
 * [CancellationException] is rethrown so cancellation reaches the [Agent]
 * facade, which synthesizes the terminal ABORTED message.
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

private suspend fun emitToolExecutionEnd(
    finalized: FinalizedToolCallOutcome,
    emit: suspend (AgentEvent) -> Unit
) {
    emit(
        AgentEvent.ToolExecutionEnd(
            toolCallId = finalized.toolCall.id,
            toolName = finalized.toolCall.name,
            result = finalized.result,
            isError = finalized.isError
        )
    )
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
    emit: suspend (AgentEvent) -> Unit
) {
    emit(AgentEvent.MessageStart(toolResultMessage))
    emit(AgentEvent.MessageEnd(toolResultMessage))
}

private suspend fun emitToolExecutionStart(toolCall: ToolCall, emit: suspend (AgentEvent) -> Unit) {
    emit(
        AgentEvent.ToolExecutionStart(
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            arguments = parseRawArguments(toolCall.arguments) ?: JsonObject(emptyMap())
        )
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
        timestamp = timestamp
    )
