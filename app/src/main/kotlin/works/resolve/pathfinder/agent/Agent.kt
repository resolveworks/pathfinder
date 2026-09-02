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
 * Immutable public state of an [Agent], exposed as a [StateFlow]. Ported from
 * pi's AgentState (packages/agent/src/types.ts) reduced to pathfinder's
 * surface: [messages] is the committed transcript, [tools] the copied tool
 * snapshot (pi's copying accessor in `createMutableAgentState`, agent.ts:68),
 * [streamingMessage] the partial message currently being streamed — of any
 * role, since user and tool-result `message_start`s transiently occupy it
 * too (pi types it `AgentMessage | undefined`) — and [pendingToolCalls] the
 * ids of tool calls whose execution has started but not ended.
 * [thinkingLevel] is pi's AgentState.thinkingLevel (agent.ts:77), default
 * `"off"`; the run loop snapshots it into its request options (see
 * [Agent.prompt]).
 */
data class AgentState(
    val model: Model,
    val messages: List<Message> = emptyList(),
    val tools: List<AgentTool> = emptyList(),
    val streamingMessage: Message? = null,
    val pendingToolCalls: Set<String> = emptySet(),
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    val thinkingLevel: ModelThinkingLevel = ModelThinkingLevel.OFF,
)

/**
 * Stateful wrapper around the low-level agent loop, ported from pi's Agent
 * class reduced to the state/lifecycle/prompt/continue/abort subset needed
 * by a no-tools chat: it owns the agent transcript, reduces loop events into
 * [AgentState] before notifying [events] observers, and synthesizes terminal
 * lifecycle when a run fails at this boundary.
 *
 * One [prompt] (or [continueRun]) is exactly one run of the agent loop: the
 * post-run orchestration pi layers above the Agent in agent-session
 * (auto-retry, compaction) lives in [AgentSession] here, exactly like pi's
 * layering (coding-agent `agent-session.ts` over `packages/agent/src/agent.ts`).
 *
 * Queues, hooks, tools, images, and persistence are deliberately absent.
 * Auto-retry settings ([RetrySettings]) are an [AgentSession] concern; this
 * class has no retry behavior.
 */
class Agent(
    /** Initial model (pi's AgentOptions.initialState.model); mutable via [setModel]. */
    model: Model,
    val systemPrompt: String? = null,
    val streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
    /** Tools available to every run (pi's AgentOptions.initialState.tools). Copied into state. */
    tools: List<AgentTool> = emptyList(),
    /** Tool execution mode (pi's AgentOptions.toolExecution, default "parallel"). */
    private val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    /** Wall clock for minting message timestamps (TS→Kotlin timing rule). */
    private val clock: Clock = Clock.System,
    private val streamFn: StreamFn,
) {
    /** Guards [active] and transcript mutations; all critical sections are brief and non-suspending. */
    private val lock = Any()

    /** True while a prompt is running; guarded by [lock]. */
    private var active = false

    /** Job of the current run's loop, cancelled by [abort]; volatile: abort may come from any coroutine. */
    @Volatile
    private var activeJob: Job? = null

    /** True once the low-level loop emitted AgentEnd for the current run. */
    private var sawAgentEnd = false

    /**
     * Event sink installed by the owning [AgentSession] (pi's agent-session
     * registers an agent event listener). Invoked synchronously from
     * [processEvent] after the event has been reduced into [state] and
     * emitted to [events], so a session sees the already-reduced state and
     * full source-ordered events without flow-subscription races. Only one
     * session may own an Agent.
     */
    internal var eventSink: suspend (AgentEvent) -> Unit = {}

    private val _state = MutableStateFlow(AgentState(model = model, tools = tools.toList()))
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /**
     * The currently selected model — state, not identity (pi keeps `model`
     * inside AgentState, agent.ts:76; coding-agent's setModel assigns
     * `agent.state.model`, agent-session.ts:1671). Reassigned, never mutated.
     */
    val model: Model get() = _state.value.model

    /**
     * The currently selected thinking level (pi's `agent.state.thinkingLevel`
     * accessor, agent-session.ts:916). Reassigned, never mutated.
     */
    val thinkingLevel: ModelThinkingLevel get() = _state.value.thinkingLevel

    /**
     * Select the model for subsequent runs (pi's harness `setModel`,
     * agent-harness.ts:425, and coding-agent's state assignment). Safe during
     * an in-flight run: [prompt] snapshots the model into its loop config at
     * run start (pi's createLoopConfig reads `_state.model`, agent.ts:515),
     * so the active run keeps streaming from its original model and the next
     * prompt uses the new one. Pure state assignment — validation and the
     * session-tree model_change record are [AgentSession.setModel]'s job,
     * exactly like pi's layering.
     */
    fun setModel(model: Model) {
        reduce { it.copy(model = model) }
    }

    /**
     * Select the thinking level for subsequent runs (pi's agent-session
     * assigning `agent.state.thinkingLevel`, agent-session.ts:1800). Safe
     * during an in-flight run: [prompt] snapshots the level into its loop
     * config's request options at run start (pi's createLoopConfig reads
     * `this._state.thinkingLevel`, agent.ts:450), so the active run keeps its
     * start-of-run level and the next prompt uses the new one. Pure state
     * assignment — clamping and the session-tree thinking_level_change record
     * are [AgentSession.setThinkingLevel]'s job, exactly like pi's layering.
     */
    fun setThinkingLevel(level: ModelThinkingLevel) {
        reduce { it.copy(thinkingLevel = level) }
    }

    /**
     * Serializes [processEvent] critical sections. Pi's processEvents is
     * effectively single-threaded (JS); under parallel tool execution,
     * tool-execution events can arrive concurrently with message events, so
     * reduction + emission + sink run under this mutex to keep the
     * already-reduced-state contract and prevent lost pending-call updates
     * (copy-on-write sets alone cannot fix a read-modify-write race).
     */
    private val eventMutex = Mutex()

    /**
     * Lifecycle events in source order. Internal state is reduced before an
     * event is emitted, so observers always see the already-reduced state.
     *
     * Contract of this zero-replay, zero-buffer [MutableSharedFlow]: a value
     * emitted with no subscribers is dropped immediately (emit does not await
     * subscribers appearing), and with subscribers present `emit` suspends
     * only until the value has been handed to every subscriber's collector —
     * not until subscribers finish processing it. Observers must subscribe
     * before starting a run to observe all of its events; the already-reduced
     * [state] is always complete regardless of subscription timing.
     */
    private val _events = MutableSharedFlow<AgentEvent>()
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * Run one agent loop over [messages] (pi's `Agent.prompt(messages)`),
     * appending them to the committed transcript and streaming one assistant
     * response from the resulting snapshot.
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
            // Snapshot the selected model and thinking level for this run
            // (pi's createLoopConfig builds the loop config — including
            // `this._state.model` and `reasoning: this._state.thinkingLevel
            // === "off" ? undefined : this._state.thinkingLevel`
            // (agent.ts:450-453) — once per run, agent.ts:509-515): a
            // setModel/setThinkingLevel during the run changes only later
            // runs.
            val runModel = _state.value.model
            val runOptions = streamOptions.copy(
                reasoning = _state.value.thinkingLevel.toThinkingLevelOrNull(),
            )
            val contextSnapshot = AgentContext(
                systemPrompt = systemPrompt,
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
                // Pi's runWithLifecycle clears streamingMessage and
                // errorMessage when a run starts (agent.ts:496-498);
                // pendingToolCalls is only cleared by finishRun at run end.
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
            // Ordinary failures are reduced into state (mirroring pi's
            // handleRunFailure) rather than rethrown; the run resolves normally.
            withContext(NonCancellable) { handleRunFailure(aborted = false, cause = e) }
        } finally {
            activeJob = null
            // Pi's finishRun (agent.ts:529-534): runtime-owned state is
            // cleared on every exit path, aborts included.
            reduce { it.copy(isStreaming = false, streamingMessage = null, pendingToolCalls = emptySet()) }
            synchronized(lock) { active = false }
        }
    }

    /**
     * Continue the agent with no new prompts, pi's `agent.continue()` (pi
     * packages/agent/src/agent.ts): a full run whose streams resume from the
     * committed transcript unchanged.
     */
    suspend fun continueRun() {
        prompt(emptyList())
    }

    /** Abort the active prompt, if any. May be called from any coroutine.
     *
     * Safe to call at any moment from outside: once [AgentState.isStreaming]
     * is observable, the run's job is already published, so this never races
     * the run's start. Calling while idle is a no-op.
     */
    fun abort() {
        activeJob?.cancel()
    }

    /**
     * Replace the committed transcript with a copy of [messages]. Only valid
     * while idle.
     *
     * @throws IllegalStateException when a run is active.
     */
    fun replaceTranscript(messages: List<Message>) {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException("Cannot replace the transcript while a prompt is running")
            }
            val copy = messages.toList()
            reduce { it.copy(messages = copy) }
        }
    }

    /**
     * Clear the committed transcript and any error. Only valid while idle.
     *
     * @throws IllegalStateException when a run is active.
     */
    fun resetTranscript() {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException("Cannot reset the transcript while a prompt is running")
            }
            reduce { it.copy(messages = emptyList(), errorMessage = null) }
        }
    }

    /**
     * Synthesize the terminal lifecycle for a run that failed at this facade
     * boundary: one ABORTED/ERROR assistant message carried through
     * message_start/end, turn_end, and agent_end. Skipped when the low-level
     * loop already terminated normally, so no final message is duplicated.
     *
     * The synthesized message carries the *live* selected model, exactly
     * like pi's handleRunFailure reading `this._state.model` (agent.ts:515)
     * — a mid-run switch relabels the failure even though the failed run
     * itself used its start-of-run snapshot.
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
     * observers, mirroring pi's processEvents (agent.ts:544-591).
     *
     * Divergence: upstream `processEvents` is private; this port marks it
     * `internal` so reduction semantics (pending tool calls, generic
     * streamingMessage) stay testable at the facade before tool execution
     * lands in the loop — the frozen events contract is public.
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
                // Copy-on-write set, exactly pi's tool_execution_start case
                // (agent.ts:559-564).
                reduce { it.copy(pendingToolCalls = it.pendingToolCalls + event.toolCallId) }
            }

            is AgentEvent.ToolExecutionEnd -> {
                reduce { it.copy(pendingToolCalls = it.pendingToolCalls - event.toolCallId) }
            }

            is AgentEvent.TurnEnd -> {
                // Pi guards `event.message.role === "assistant" && errorMessage`
                // (agent.ts:569-572); TurnEnd always carries an assistant
                // message in this contract, so only the null check remains.
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
