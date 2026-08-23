package works.resolve.aletheia.agent

import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.UserMessage
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Immutable public state of an [Agent], exposed as a [StateFlow].
 *
 * [messages] is the committed transcript; [streamingMessage] is the partial
 * assistant message currently being streamed, if any.
 */
data class AgentState(
    val model: Model,
    val messages: List<Message> = emptyList(),
    val streamingMessage: AssistantMessage? = null,
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Stateful wrapper around the low-level agent loop, ported from pi's Agent
 * class reduced to the state/lifecycle/prompt/abort subset needed by a
 * no-tools chat: it owns the transcript, reduces loop events into
 * [AgentState] before notifying [events] observers, and synthesizes terminal
 * lifecycle when a run fails at this boundary.
 *
 * Queues, hooks, tools, images, and persistence are deliberately absent.
 */
class Agent(
    val model: Model,
    val systemPrompt: String? = null,
    val streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
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

    private val _state = MutableStateFlow(AgentState(model = model))
    val state: StateFlow<AgentState> = _state.asStateFlow()

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
     * Start a new prompt from [text]. Creates a timestamped user message and
     * runs the loop against a snapshot of the committed transcript.
     *
     * @throws IllegalStateException when a prompt is already running.
     * @throws CancellationException when aborted or when the caller is cancelled;
     *   in either case a synthetic ABORTED assistant message and full terminal
     *   lifecycle are committed first (in a non-cancellable context) so the
     *   transcript and UI cannot remain stuck.
     */
    suspend fun prompt(text: String) {
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
            val prompt = UserMessage.ofText(text, System.currentTimeMillis())
            val contextSnapshot = Context(
                systemPrompt = systemPrompt,
                messages = _state.value.messages.toList(),
            )
            val config = AgentLoopConfig(model = model, options = streamOptions, streamFn = streamFn)

            coroutineScope {
                // Lazily started so that activeJob is published before the
                // job can run anything: an abort() that fires as soon as the
                // state reports isStreaming is guaranteed to reach this job.
                val job = launch(start = CoroutineStart.LAZY) {
                    runAgentLoop(listOf(prompt), contextSnapshot, config) { event -> processEvent(event) }
                }
                activeJob = job
                reduce { it.copy(isStreaming = true, errorMessage = null) }
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
            reduce { it.copy(isStreaming = false, streamingMessage = null) }
            synchronized(lock) { active = false }
        }
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
     * @throws IllegalStateException when a prompt is running.
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
     * @throws IllegalStateException when a prompt is running.
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
            timestamp = System.currentTimeMillis(),
        )
        processEvent(AgentEvent.MessageStart(failure))
        processEvent(AgentEvent.MessageEnd(failure))
        processEvent(AgentEvent.TurnEnd(failure))
        processEvent(AgentEvent.AgentEnd(listOf(failure)))
    }

    /**
     * Reduce internal state for a loop event, then emit the event to
     * observers, mirroring pi's processEvents (no-tools subset).
     */
    private suspend fun processEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AgentStart,
            is AgentEvent.TurnStart,
            -> Unit

            is AgentEvent.MessageStart -> reduce { it.copy(streamingMessage = event.message as? AssistantMessage) }

            is AgentEvent.MessageUpdate -> reduce { it.copy(streamingMessage = event.message) }

            is AgentEvent.MessageEnd -> reduce {
                it.copy(messages = it.messages + event.message, streamingMessage = null)
            }

            is AgentEvent.TurnEnd -> {
                val message = event.message.errorMessage
                if (message != null) reduce { it.copy(errorMessage = message) }
            }

            is AgentEvent.AgentEnd -> {
                sawAgentEnd = true
                reduce { it.copy(streamingMessage = null) }
            }
        }
        _events.emit(event)
    }

    private fun reduce(reducer: (AgentState) -> AgentState) {
        _state.value = reducer(_state.value)
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
