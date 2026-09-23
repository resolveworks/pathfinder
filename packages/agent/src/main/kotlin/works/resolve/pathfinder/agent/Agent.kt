package works.resolve.pathfinder.agent

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.toThinkingLevelOrNull
import works.resolve.pathfinder.ai.utils.createInitialSystemMessage
import works.resolve.pathfinder.ai.utils.getCurrentSystemMessage
import works.resolve.pathfinder.ai.utils.getCurrentSystemPrompt
import works.resolve.pathfinder.ai.utils.toToolDeclaration

/** pi's PendingMessageQueue: mode-aware FIFO of messages awaiting delivery. */
private class PendingMessageQueue(initialMode: QueueMode) {
    var mode: QueueMode = initialMode
    private val messages = mutableListOf<Message>()

    fun enqueue(message: Message) {
        messages.add(message)
    }

    fun hasItems(): Boolean = messages.isNotEmpty()

    fun drain(): List<Message> {
        if (mode == QueueMode.ALL) {
            val drained = messages.toList()
            messages.clear()
            return drained
        }
        val first = messages.firstOrNull() ?: return emptyList()
        messages.removeAt(0)
        return listOf(first)
    }

    fun clear() {
        messages.clear()
    }
}

/**
 * Stateful wrapper around the low-level agent loop: owns the agent
 * transcript, reduces loop events into [AgentState] before notifying
 * [events] observers, and synthesizes terminal lifecycle when a run fails
 * at this boundary. The loop itself already terminates aborts and provider
 * errors with the finalized partial message; this path only covers
 * failures that escape the loop. Post-run orchestration — auto-retry and
 * compaction — belongs to the higher-level coding-agent session, not to
 * the classic Agent.
 */
class Agent(
    model: Model,
    /**
     * Initial system prompt: seeds the transcript's leading system message
     * (together with the initial tools). Read-only afterwards — change the
     * prompt by appending a system message.
     */
    systemPrompt: String? = null,
    val streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
    tools: List<AgentTool> = emptyList(),
    private val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    /** Drain mode of the steering queue (pi's runtime option). */
    steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    /** Drain mode of the follow-up queue (pi's runtime option). */
    followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    private val clock: Clock = Clock.System,
    private val streamFn: StreamFn
) {
    /** Guards [active] and transcript mutations; critical sections stay brief and non-suspending. */
    private val lock = Any()

    private var active = false

    /** Current run's loop job, cancelled by [abort]; volatile because abort may come from any coroutine. */
    @Volatile
    private var activeJob: Job? = null

    /** Set by the AgentEnd reduction; read by [prompt] to skip synthesized lifecycle for aborts the loop already terminated. */
    private var sawAgentEnd = false

    /** pi's steeringQueue: messages injected after the current assistant turn. */
    private val steeringQueue = PendingMessageQueue(steeringMode)

    /** pi's followUpQueue: messages that start the next run once this one would stop. */
    private val followUpQueue = PendingMessageQueue(followUpMode)

    /**
     * pi's per-run abort-signal state: set by [abort] while a run is active,
     * cleared when the next run starts (upstream creates a fresh
     * AbortController per run). Read by the ordinary-exception failure path:
     * an exception escaping after an abort was requested is classified
     * ABORTED, not ERROR.
     */
    @Volatile
    private var abortRequested = false

    /**
     * Event sink installed by the owning coding-agent session. Invoked synchronously
     * from [processEvent] after the event has been reduced into [state] and
     * emitted to [events], so a session sees the already-reduced state and
     * full source-ordered events. Only one session may own an Agent.
     */
    private var eventSink: suspend (AgentEvent) -> Unit = {}

    /**
     * Attaches the higher-level coding-agent session's ordered event sink.
     * Kept explicit rather than module-internal because pi's package boundary
     * is represented by a separate Gradle module in Pathfinder.
     */
    fun attachEventSink(sink: suspend (AgentEvent) -> Unit) {
        eventSink = sink
    }

    private val _state = MutableStateFlow(
        AgentState(
            model = model,
            messages =
                createInitialSystemMessage(
                    systemPrompt,
                    tools.map { toToolDeclaration(it.definition) }
                )?.let(::listOf) ?: emptyList(),
            tools = tools.toList()
        )
    )
    val state: StateFlow<AgentState> = _state.asStateFlow()

    val model: Model get() = _state.value.model

    val thinkingLevel: ModelThinkingLevel get() = _state.value.thinkingLevel

    /** Current system prompt, replayed from the transcript's system messages. */
    val systemPrompt: String get() = _state.value.systemPrompt

    /**
     * Select the model for subsequent runs. Safe during an in-flight run:
     * [prompt] snapshots the model at run start, so the change reaches the
     * next provider request only when a session installs
     * [prepareNextTurnWithContext] (returning the live state, as pi's
     * AgentSession does), otherwise the next run. Validation and the
     * session-tree model-change record are the owning session's job.
     */
    fun setModel(model: Model) {
        reduce { it.copy(model = model) }
    }

    /**
     * Session-installed between-turns hook (pi's same-named Agent callback).
     * Consulted after `turn_end` when the loop will continue, before the next
     * provider request; may return refreshed context/model/thinkingLevel, or
     * messages to append. A session that installs it (returning the live
     * `state` values, like pi's AgentSession) makes mid-run setter calls take
     * effect on the next turn.
     */
    var prepareNextTurnWithContext: (suspend (PrepareNextTurnContext) -> AgentLoopTurnUpdate?)? =
        null

    /**
     * Select the thinking level for subsequent runs. Safe during an in-flight
     * run: like [setModel], the change reaches the next provider request only
     * through [prepareNextTurnWithContext] when a session installs it.
     * Clamping and the session-tree thinking_level_change record are
     * the owning session's job.
     */
    fun setThinkingLevel(level: ModelThinkingLevel) {
        reduce { it.copy(thinkingLevel = level) }
    }

    /**
     * Assign the tools for subsequent runs. Safe during an in-flight run:
     * like [setModel], the change reaches the next provider request only
     * through [prepareNextTurnWithContext] when a session installs it.
     * Differences from the tools declared in the transcript are announced
     * to the model with a system message before the next request.
     */
    fun setTools(tools: List<AgentTool>) {
        reduce { it.copy(tools = tools.toList()) }
    }

    /**
     * Serializes [processEvent] critical sections: under parallel tool
     * execution, tool-execution events can arrive concurrently with message
     * events, so reduction + emission + sink run under this mutex to keep the
     * already-reduced-state contract and prevent lost pending-call updates
     * (copy-on-write sets alone cannot fix a read-modify-write race).
     */
    private val eventMutex = Mutex()

    /**
     * Lifecycle events in source order; state is reduced before each event is
     * emitted, so observers always see the already-reduced state.
     *
     * Zero-replay with a bounded buffer: a value emitted with no subscribers
     * is dropped immediately. Unlike pi, which awaits synchronous listeners
     * inline per event, slow external collectors are decoupled: buffered
     * delivery keeps emission non-suspending until the buffer fills (default
     * SUSPEND overflow — never drop), so the loop is not backpressured by UI
     * collection speed. The session's inline event sink remains awaited
     * synchronously by [processEvent] (pi's listener contract), so ordering
     * between reduce and emit still holds: the mutex serializes
     * reduce+emit+sink per event, and a buffered collector observes events
     * in emission order. Observers must subscribe before starting a run to
     * observe all of its events; the already-reduced [state] is always
     * complete regardless of subscription timing.
     */
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * Run one agent loop over [messages], appending them to the committed
     * transcript and streaming one assistant response from the resulting
     * snapshot.
     *
     * @throws IllegalStateException when a run is already active.
     * @throws CancellationException when aborted or when the caller is
     *   cancelled. Unlike pi, whose `prompt()` resolves normally after an
     *   abort, cancellation is rethrown — but only after the loop has
     *   committed the finalized partial assistant message and the terminal
     *   lifecycle, so the transcript and UI cannot remain stuck.
     */
    suspend fun prompt(messages: List<Message>) {
        beginRun(PROMPT_ACTIVE_MESSAGE)
        runPromptMessages(messages, skipInitialSteeringPoll = false)
    }

    /** Claim the single-run slot and reset the run's abort state (pi's fresh AbortController). */
    private fun beginRun(guardMessage: String) {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException(guardMessage)
            }
            active = true
            abortRequested = false
        }
    }

    fun clearSteeringQueue() {
        synchronized(lock) { steeringQueue.clear() }
    }

    fun clearFollowUpQueue() {
        synchronized(lock) { followUpQueue.clear() }
    }

    fun clearAllQueues() {
        clearSteeringQueue()
        clearFollowUpQueue()
    }

    fun hasQueuedMessages(): Boolean = synchronized(lock) {
        steeringQueue.hasItems() || followUpQueue.hasItems()
    }

    /**
     * Queue a message for injection after the current assistant turn's tool
     * calls, before the next provider request (pi's `steer`). Delivered by
     * the loop's steering polls; safe to call while a run is active or idle
     * (an idle-queued message joins the next run's initial context).
     */
    fun steer(message: Message) {
        synchronized(lock) { steeringQueue.enqueue(message) }
    }

    /**
     * Queue a message to start the next run once the current one would
     * otherwise stop (pi's `followUp`).
     */
    fun followUp(message: Message) {
        synchronized(lock) { followUpQueue.enqueue(message) }
    }

    private suspend fun runPromptMessages(
        messages: List<Message>,
        skipInitialSteeringPoll: Boolean
    ) {
        sawAgentEnd = false

        try {
            // Start-of-run snapshot: setter calls during the run affect only
            // later runs.
            val runModel = _state.value.model
            val runOptions = streamOptions.copy(
                reasoning = _state.value.thinkingLevel.toThinkingLevelOrNull()
            )
            val contextSnapshot = AgentContext(
                messages = _state.value.messages.toList(),
                tools = _state.value.tools.toList()
            )
            // pi's createLoopConfig closure: the initial steering poll is
            // skipped exactly once when continueRun() already drained the
            // steering queue to build this run's prompt.
            var skipSteeringPoll = skipInitialSteeringPoll
            val config = AgentLoopConfig(
                model = runModel,
                options = runOptions,
                streamFn = streamFn,
                toolExecution = toolExecution,
                clock = clock,
                prepareNextTurn = prepareNextTurnWithContext,
                getSteeringMessages = {
                    if (skipSteeringPoll) {
                        skipSteeringPoll = false
                        emptyList()
                    } else {
                        synchronized(lock) { steeringQueue.drain() }
                    }
                },
                getFollowUpMessages = { synchronized(lock) { followUpQueue.drain() } }
            )

            coroutineScope {
                // Lazily started so that activeJob is published before the
                // job can run anything: an abort() that fires as soon as the
                // state reports isStreaming is guaranteed to reach this job.
                val job = launch(start = CoroutineStart.LAZY) {
                    runAgentLoop(messages, contextSnapshot, config) { event -> processEvent(event) }
                }
                activeJob = job
                reduce { it.copy(isStreaming = true, streamingMessage = null, errorMessage = null) }
                job.start()

                job.join()
                if (job.isCancelled) {
                    // Aborted via abort(): surface as cancellation to the caller.
                    throw CancellationException("Prompt aborted")
                }
            }
        } catch (e: CancellationException) {
            // Abort adaptation: pi's loop always completes its terminal
            // lifecycle before an abort becomes visible and `prompt()`
            // resolves; this port rethrows, so the synthesized lifecycle
            // covers only cancellations the loop could not terminate itself
            // (for example during a run's first emits).
            if (!sawAgentEnd) {
                withContext(NonCancellable) { handleRunFailure(aborted = true) }
            }
            throw e
        } catch (e: Exception) {
            // pi awaits its failure handler unconditionally when the loop
            // throws — even after agent_end was emitted, so a listener
            // throwing during agent_end produces a second message lifecycle
            // and a second agent_end — and the run then resolves normally
            // rather than rethrowing. The signal's aborted state (pi's
            // `abortController.signal.aborted`) decides the classification.
            withContext(NonCancellable) { handleRunFailure(aborted = abortRequested, cause = e) }
        } finally {
            activeJob = null
            reduce {
                it.copy(isStreaming = false, streamingMessage = null, pendingToolCalls = emptySet())
            }
            synchronized(lock) { active = false }
        }
    }

    /**
     * Continue from the committed transcript without new prompts. The last
     * committed message must be a user or tool-result message.
     *
     * Mirrors pi's `continue()` guards, in upstream order: reject an
     * in-flight run, then an empty or system-only transcript, then an
     * assistant tail. The assistant-tail branch drains the steering queue
     * first and runs the drained messages as the continuation prompt with
     * [skipInitialSteeringPoll] (they were just drained — the loop's initial
     * poll must not double-drain the queue), then the follow-up queue, and
     * only throws when both are empty. Upstream repeats the tail guards in
     * `runAgentLoopContinue`; this port continues via a prompt with no new
     * messages, so the guards live here only.
     *
     * @throws IllegalStateException when a run is already active, the
     *   transcript is empty or system-only, or its last message is an
     *   assistant message with both queues empty.
     */
    suspend fun continueRun() {
        val messages: List<Message> = synchronized(lock) {
            if (active) {
                throw IllegalStateException(
                    "Agent is already processing. Wait for completion before continuing."
                )
            }
            _state.value.messages
        }
        val lastMessage = messages.lastOrNull()
        if (lastMessage == null || messages.all { it is SystemMessage }) {
            throw IllegalStateException("No messages to continue from")
        }
        if (lastMessage is AssistantMessage) {
            val queuedSteering = synchronized(lock) { steeringQueue.drain() }
            if (queuedSteering.isNotEmpty()) {
                beginRun(CONTINUE_ACTIVE_MESSAGE)
                runPromptMessages(queuedSteering, skipInitialSteeringPoll = true)
                return
            }

            val queuedFollowUps = synchronized(lock) { followUpQueue.drain() }
            if (queuedFollowUps.isNotEmpty()) {
                beginRun(CONTINUE_ACTIVE_MESSAGE)
                runPromptMessages(queuedFollowUps, skipInitialSteeringPoll = false)
                return
            }

            throw IllegalStateException("Cannot continue from message role: assistant")
        }
        beginRun(CONTINUE_ACTIVE_MESSAGE)
        runPromptMessages(emptyList(), skipInitialSteeringPoll = false)
    }

    /**
     * Abort the active prompt, if any; a no-op while idle. May be called from
     * any coroutine: once [AgentState.isStreaming] is observable, the run's
     * job is already published, so this never races the run's start. Marks
     * the run's abort state so an ordinary exception escaping afterwards is
     * classified ABORTED (pi's per-run signal).
     */
    fun abort() {
        synchronized(lock) {
            val job = activeJob ?: return
            abortRequested = true
            job.cancel()
        }
    }

    /** Replace the committed transcript; only valid while idle. */
    fun replaceTranscript(messages: List<Message>) {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException(
                    "Cannot replace the transcript while a prompt is running"
                )
            }
            val copy = messages.toList()
            reduce { it.copy(messages = copy) }
        }
    }

    /**
     * Replace the committed transcript unconditionally. pi's session assigns
     * `agent.state.messages` directly to rebuild context after mid-run
     * compaction; this is that mutation, valid while a run is active (it runs
     * inside the run's own coroutine, between turns).
     */
    fun setMessages(messages: List<Message>) {
        reduce { it.copy(messages = messages.toList()) }
    }

    /**
     * Clear the committed transcript, any error, and both pending-message
     * queues while retaining the replayed prompt/tool baseline; only valid
     * while idle.
     */
    fun resetTranscript() {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException("Cannot reset the transcript while a prompt is running")
            }
            val baseline = getCurrentSystemMessage(_state.value.messages)
            reduce {
                it.copy(
                    messages = if (baseline != null) listOf(baseline) else emptyList(),
                    errorMessage = null
                )
            }
            followUpQueue.clear()
            steeringQueue.clear()
        }
    }

    /**
     * Synthesize the terminal lifecycle for a run that failed at this
     * boundary: one ABORTED/ERROR assistant message carried through
     * message_start/end, turn_end, and agent_end. Runs unconditionally when
     * the loop throws, like pi's failure handler — including after the loop
     * already emitted agent_end.
     *
     * Message shape matches pi (empty text content, zeroed usage); the error
     * text is sanitized because raw exception messages can embed request
     * details such as options or credentials.
     *
     * The synthesized message carries the live selected model — a mid-run
     * switch relabels the failure even though the failed run itself used its
     * start-of-run snapshot.
     */
    private suspend fun handleRunFailure(aborted: Boolean, cause: Throwable? = null) {
        val failure = AssistantMessage(
            content = listOf(TextContent("")),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = Usage(),
            stopReason = if (aborted) StopReason.ABORTED else StopReason.ERROR,
            errorMessage = if (aborted) ABORT_ERROR_MESSAGE else safeErrorMessage(cause),
            timestamp = clock.now().toEpochMilliseconds()
        )
        processEvent(AgentEvent.MessageStart(failure))
        processEvent(AgentEvent.MessageEnd(failure))
        processEvent(AgentEvent.TurnEnd(failure))
        processEvent(AgentEvent.AgentEnd(listOf(failure)))
    }

    /**
     * Reduce internal state for a loop event, then emit the event to
     * observers. Exposed for deterministic host-layer tests that project
     * already-reduced state; normal callers consume [events].
     */
    suspend fun processEvent(event: AgentEvent) = eventMutex.withLock {
        when (event) {
            is AgentEvent.AgentStart,
            is AgentEvent.AgentSettled,
            is AgentEvent.ThinkingLevelChanged,
            is AgentEvent.QueueUpdate,
            is AgentEvent.TurnStart,
            is AgentEvent.AutoRetryStart,
            is AgentEvent.AutoRetryEnd,
            is AgentEvent.CompactionStart,
            is AgentEvent.CompactionEnd,
            is AgentEvent.SummarizationRetryScheduled,
            is AgentEvent.SummarizationRetryAttemptStart,
            AgentEvent.SummarizationRetryFinished,
            is AgentEvent.ToolExecutionUpdate,
            is AgentEvent.MessageUpdate
            -> Unit

            is AgentEvent.MessageStart -> reduce { it.copy(streamingMessage = event.message) }

            is AgentEvent.MessageEnd -> {
                reduce { it.copy(messages = it.messages + event.message, streamingMessage = null) }
            }

            is AgentEvent.ToolExecutionStart -> {
                reduce { it.copy(pendingToolCalls = it.pendingToolCalls + event.toolCallId) }
            }

            is AgentEvent.ToolExecutionEnd -> {
                reduce { it.copy(pendingToolCalls = it.pendingToolCalls - event.toolCallId) }
            }

            is AgentEvent.TurnEnd -> {
                // Upstream also checks for an assistant role; TurnEnd always
                // carries an assistant message in this port's contract.
                val message = event.message.errorMessage
                if (message != null) reduce { it.copy(errorMessage = message) }
            }

            is AgentEvent.AgentEnd -> {
                sawAgentEnd = true
                reduce { it.copy(streamingMessage = null) }
            }
        }
        _events.emit(event)
        eventSink(event)
    }

    private fun reduce(reducer: (AgentState) -> AgentState) {
        _state.update(reducer)
    }

    private companion object {
        /** Bounded emit buffer decoupling slow external collectors from the loop. */
        const val EVENT_BUFFER_CAPACITY = 64

        const val PROMPT_ACTIVE_MESSAGE =
            "Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion."

        const val CONTINUE_ACTIVE_MESSAGE =
            "Agent is already processing. Wait for completion before continuing."

        const val ABORT_ERROR_MESSAGE = "Run aborted"

        /**
         * Bounded, user-facing message for unexpected failures. Only the
         * exception kind is used — raw exception text can embed request
         * details such as options or credentials.
         */
        fun safeErrorMessage(cause: Throwable?): String {
            val kind = cause?.let { it::class.java.simpleName } ?: "UnknownError"
            return "Unexpected error ($kind)".take(200)
        }
    }
}
