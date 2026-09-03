package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.toThinkingLevelOrNull
import works.resolve.pathfinder.data.settings.RetrySettings
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

/**
 * Stateful wrapper around the low-level agent loop: owns the agent
 * transcript, reduces loop events into [AgentState] before notifying
 * [events] observers, and synthesizes terminal lifecycle when a run fails at
 * this boundary. Post-run orchestration — auto-retry ([RetrySettings]),
 * compaction — is an [AgentSession] concern, not an Agent capability.
 */
class Agent(
    model: Model,
    systemPrompt: String? = null,
    val streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
    tools: List<AgentTool> = emptyList(),
    private val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    private val clock: Clock = Clock.System,
    private val streamFn: StreamFn,
) {
    /** Guards [active] and transcript mutations; critical sections stay brief and non-suspending. */
    private val lock = Any()

    private var active = false

    /** Current run's loop job, cancelled by [abort]; volatile because abort may come from any coroutine. */
    @Volatile
    private var activeJob: Job? = null

    private var sawAgentEnd = false

    /**
     * Event sink installed by the owning [AgentSession]. Invoked synchronously
     * from [processEvent] after the event has been reduced into [state] and
     * emitted to [events], so a session sees the already-reduced state and
     * full source-ordered events. Only one session may own an Agent.
     */
    internal var eventSink: suspend (AgentEvent) -> Unit = {}

    private val _state = MutableStateFlow(
        AgentState(model = model, tools = tools.toList(), systemPrompt = systemPrompt),
    )
    val state: StateFlow<AgentState> = _state.asStateFlow()

    val model: Model get() = _state.value.model

    val thinkingLevel: ModelThinkingLevel get() = _state.value.thinkingLevel

    val systemPrompt: String? get() = _state.value.systemPrompt

    /**
     * Select the model for subsequent runs. Safe during an in-flight run:
     * [prompt] snapshots the model at run start, so the active run keeps its
     * original model and the next prompt uses the new one. Validation and the
     * session-tree model_change record are [AgentSession.setModel]'s job.
     */
    fun setModel(model: Model) {
        reduce { it.copy(model = model) }
    }

    /**
     * Select the thinking level for subsequent runs. Safe during an in-flight
     * run: [prompt] snapshots the level at run start, so the active run keeps
     * its start-of-run level and the next prompt uses the new one. Clamping
     * and the session-tree thinking_level_change record are
     * [AgentSession.setThinkingLevel]'s job.
     */
    fun setThinkingLevel(level: ModelThinkingLevel) {
        reduce { it.copy(thinkingLevel = level) }
    }

    /**
     * Assign the tools for subsequent runs. Safe during an in-flight run:
     * [prompt] snapshots the tools at run start, so the active run keeps its
     * start-of-run tool set and the next prompt uses the new ones.
     */
    fun setTools(tools: List<AgentTool>) {
        reduce { it.copy(tools = tools.toList()) }
    }

    /**
     * Assign the system prompt for subsequent runs. Safe during an in-flight
     * run: [prompt] snapshots it at run start, so the active run keeps its
     * start-of-run prompt and the next prompt uses the new one.
     */
    fun setSystemPrompt(value: String?) {
        reduce { it.copy(systemPrompt = value) }
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
     * Zero-replay, zero-buffer: a value emitted with no subscribers is
     * dropped immediately, and with subscribers present `emit` suspends only
     * until the value has been handed to every collector — not until
     * subscribers finish processing it. Observers must subscribe before
     * starting a run to observe all of its events; the already-reduced
     * [state] is always complete regardless of subscription timing.
     */
    private val _events = MutableSharedFlow<AgentEvent>()
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * Run one agent loop over [messages], appending them to the committed
     * transcript and streaming one assistant response from the resulting
     * snapshot.
     *
     * @throws IllegalStateException when a run is already active.
     * @throws CancellationException when aborted or when the caller is cancelled;
     *   in either case a synthetic ABORTED assistant message and full terminal
     *   lifecycle are committed first (in a non-cancellable context) so the
     *   transcript and UI cannot remain stuck.
     */
    suspend fun prompt(messages: List<Message>) {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException(
                    "Agent is already processing a prompt. Wait for completion or abort it.",
                )
            }
            active = true
        }

        sawAgentEnd = false

        try {
            // Start-of-run snapshot: setter calls during the run affect only
            // later runs.
            val runModel = _state.value.model
            val runOptions = streamOptions.copy(
                reasoning = _state.value.thinkingLevel.toThinkingLevelOrNull(),
            )
            val contextSnapshot = AgentContext(
                systemPrompt = _state.value.systemPrompt,
                messages = _state.value.messages.toList(),
                tools = _state.value.tools.toList(),
            )
            val config = AgentLoopConfig(
                model = runModel,
                options = runOptions,
                streamFn = streamFn,
                toolExecution = toolExecution,
                clock = clock,
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
            withContext(NonCancellable) { handleRunFailure(aborted = true) }
            throw e
        } catch (e: Exception) {
            // Ordinary failures are reduced into state rather than rethrown;
            // the run resolves normally.
            withContext(NonCancellable) { handleRunFailure(aborted = false, cause = e) }
        } finally {
            activeJob = null
            reduce { it.copy(isStreaming = false, streamingMessage = null, pendingToolCalls = emptySet()) }
            synchronized(lock) { active = false }
        }
    }

    /** Continue from the committed transcript without new prompts. */
    suspend fun continueRun() {
        prompt(emptyList())
    }

    /**
     * Abort the active prompt, if any; a no-op while idle. May be called from
     * any coroutine: once [AgentState.isStreaming] is observable, the run's
     * job is already published, so this never races the run's start.
     */
    fun abort() {
        activeJob?.cancel()
    }

    /** Replace the committed transcript with a copy of [messages]; only valid while idle. */
    fun replaceTranscript(messages: List<Message>) {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException("Cannot replace the transcript while a prompt is running")
            }
            val copy = messages.toList()
            reduce { it.copy(messages = copy) }
        }
    }

    /** Clear the committed transcript and any error; only valid while idle. */
    fun resetTranscript() {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException("Cannot reset the transcript while a prompt is running")
            }
            reduce { it.copy(messages = emptyList(), errorMessage = null) }
        }
    }

    /**
     * Synthesize the terminal lifecycle for a run that failed at this
     * boundary: one ABORTED/ERROR assistant message carried through
     * message_start/end, turn_end, and agent_end. Skipped when the low-level
     * loop already emitted AgentEnd, so no final message is duplicated.
     *
     * The synthesized message carries the live selected model — a mid-run
     * switch relabels the failure even though the failed run itself used its
     * start-of-run snapshot.
     */
    private suspend fun handleRunFailure(aborted: Boolean, cause: Throwable? = null) {
        if (sawAgentEnd) return

        val failure = AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = if (aborted) StopReason.ABORTED else StopReason.ERROR,
            errorMessage = if (aborted) ABORT_ERROR_MESSAGE else safeErrorMessage(cause),
            timestamp = clock.now().toEpochMilliseconds(),
        )
        processEvent(AgentEvent.MessageStart(failure))
        processEvent(AgentEvent.MessageEnd(failure))
        processEvent(AgentEvent.TurnEnd(failure))
        processEvent(AgentEvent.AgentEnd(listOf(failure)))
    }

    /**
     * Reduce internal state for a loop event, then emit the event to
     * observers. Internal rather than private so reduction semantics stay
     * testable; the public event surface is [events].
     */
    internal suspend fun processEvent(event: AgentEvent) = eventMutex.withLock {
        when (event) {
            is AgentEvent.AgentStart,
            is AgentEvent.TurnStart,
            is AgentEvent.AutoRetryStart,
            is AgentEvent.AutoRetryEnd,
            is AgentEvent.CompactionStart,
            is AgentEvent.CompactionEnd,
            is AgentEvent.SummarizationRetryScheduled,
            is AgentEvent.SummarizationRetryAttemptStart,
            AgentEvent.SummarizationRetryFinished,
            is AgentEvent.ToolExecutionUpdate,
            -> Unit

            is AgentEvent.MessageStart -> reduce { it.copy(streamingMessage = event.message) }

            is AgentEvent.MessageUpdate -> reduce { it.copy(streamingMessage = event.message) }

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
