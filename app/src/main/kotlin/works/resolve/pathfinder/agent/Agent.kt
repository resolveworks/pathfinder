package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.utils.Retry
import works.resolve.pathfinder.ai.utils.isContextOverflow
import works.resolve.pathfinder.data.settings.RetrySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    /** Auto-retry budget for failed runs (pi's settings.retry, agent-session auto-retry). */
    val retrySettings: RetrySettings = RetrySettings(),
    /** Injectable backoff sleep so tests never wait. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
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
     * 1-indexed auto-retry attempt counter, pi's `_retryAttempt`
     * (agent-session.ts). Reset on a successful assistant response, final
     * failure, or cancelled backoff.
     */
    private var retryAttempt = 0

    /**
     * Last assistant message of the current run, pi's `_lastAssistantMessage`
     * (agent-session.ts): consumed (nulled) by post-run handling.
     */
    private var lastAssistantMessage: AssistantMessage? = null

    /** Stateful classifier for transient provider errors (pi's isRetryableAssistantError). */
    private val retryClassifier = Retry()

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
                    // Post-run auto-retry, pi's _runAgentPrompt loop
                    // (agent-session.ts): after each completed run, retry a
                    // retryable final error by continuing the agent with no
                    // new prompts. The whole sequence lives in this one job so
                    // abort() cancels both the active run and the backoff sleep.
                    while (handlePostAgentRun()) {
                        runContinue(config)
                    }
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
            is AgentEvent.AutoRetryStart,
            is AgentEvent.AutoRetryEnd,
            -> Unit

            is AgentEvent.MessageStart -> reduce { it.copy(streamingMessage = event.message as? AssistantMessage) }

            is AgentEvent.MessageUpdate -> reduce { it.copy(streamingMessage = event.message) }

            is AgentEvent.MessageEnd -> {
                reduce { it.copy(messages = it.messages + event.message, streamingMessage = null) }
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

        // Pi's agent-session tracks the run's last assistant message per
        // append and resets the retry counter mid-run when a retried run
        // finally produces a non-error assistant response (agent-session.ts
        // ~684): auto_retry_end{success:true} fires at that message's
        // completion, not at post-run. Mirrored here inside event reduction.
        val assistant = (event as? AgentEvent.MessageEnd)?.message as? AssistantMessage
        if (assistant != null) {
            lastAssistantMessage = assistant
            if (assistant.stopReason != StopReason.ERROR && retryAttempt > 0) {
                val attempt = retryAttempt
                retryAttempt = 0
                _events.emit(AgentEvent.AutoRetryEnd(success = true, attempt = attempt))
            }
        }
    }

    // ---- auto-retry (pi agent-session.ts) ----

    /**
     * One agent continuation, pi's `agent.continue()`: a run with no new
     * prompts that streams from the committed transcript unchanged.
     * [sawAgentEnd] is reset so a failure of this run still synthesizes
     * terminal lifecycle at the facade boundary, and [AgentState.errorMessage]
     * is cleared like pi's Agent.run does before every run.
     */
    private suspend fun runContinue(config: AgentLoopConfig) {
        sawAgentEnd = false
        reduce { it.copy(errorMessage = null) }
        val snapshot = Context(systemPrompt = systemPrompt, messages = _state.value.messages.toList())
        runAgentLoop(emptyList(), snapshot, config) { event -> processEvent(event) }
    }

    /**
     * Post-run handling, ported from pi's `_handlePostAgentRun`
     * (agent-session.ts ~1101) reduced to the auto-retry branch (compaction
     * and queued-message continuation are out of scope for this port):
     * consumes [lastAssistantMessage]; when its final assistant message is a
     * retryable error and the retry can be prepared, returns true so the
     * caller continues the agent. Otherwise, when the run still errored after
     * retries, emits `auto_retry_end{success:false}` with the final error and
     * resets the counter.
     */
    private suspend fun handlePostAgentRun(): Boolean {
        val msg = lastAssistantMessage
        lastAssistantMessage = null
        if (msg == null) return false

        if (isRetryableError(msg) && prepareRetry(msg)) return true

        if (msg.stopReason == StopReason.ERROR && retryAttempt > 0) {
            _events.emit(
                AgentEvent.AutoRetryEnd(success = false, attempt = retryAttempt, finalError = msg.errorMessage),
            )
            retryAttempt = 0
        }
        return false
    }

    /**
     * Ported from pi's `_isRetryableError` (agent-session.ts ~2825): context
     * overflow errors are NOT retryable (compaction's job upstream); every
     * other retryable assistant error is.
     */
    private fun isRetryableError(message: AssistantMessage): Boolean {
        if (isContextOverflow(message, model.contextWindow)) return false
        return retryClassifier.isRetryableAssistantError(message)
    }

    /**
     * Prepare a retry of [message] with exponential backoff, ported from pi's
     * `_prepareRetry` (agent-session.ts ~2866). Returns true when the caller
     * should continue the agent.
     *
     * Divergence (precedented in `ai/utils/Retry.kt`): pi sleeps through a
     * dedicated retry AbortController; here abort is plain cancellation of
     * the prompt coroutine, so the sleep's [CancellationException] is caught
     * to emit the terminal `auto_retry_end{success:false, "Retry cancelled"}`
     * under [NonCancellable] before rethrowing (prompt's existing abort
     * contract). The error message is removed from AGENT STATE only — it
     * stays in the persisted session/history, whose tree is append-only and
     * synced from state growth, so a removal never retracts persisted entries.
     */
    private suspend fun prepareRetry(message: AssistantMessage): Boolean {
        if (!retrySettings.enabled) return false

        retryAttempt++
        if (retryAttempt > retrySettings.maxRetries) {
            // Preserve the completed attempt count so post-run handling can
            // emit the final failure.
            retryAttempt--
            return false
        }

        val delayMs = retrySettings.baseDelayMs * (1L shl (retryAttempt - 1))

        _events.emit(
            AgentEvent.AutoRetryStart(
                attempt = retryAttempt,
                maxAttempts = retrySettings.maxRetries,
                delayMs = delayMs,
                errorMessage = message.errorMessage ?: "Unknown error",
            ),
        )

        // Remove the error message from agent state (it stays in the
        // session/history): only when the trailing message is an assistant
        // message, exactly like pi.
        val messages = _state.value.messages
        if (messages.isNotEmpty() && messages.last() is AssistantMessage) {
            val retained = messages.dropLast(1)
            reduce { it.copy(messages = retained) }
        }

        try {
            sleep(delayMs)
        } catch (e: CancellationException) {
            val attempt = retryAttempt
            retryAttempt = 0
            withContext(NonCancellable) {
                _events.emit(AgentEvent.AutoRetryEnd(success = false, attempt = attempt, finalError = RETRY_CANCELLED))
            }
            throw e
        }

        return true
    }

    private fun reduce(reducer: (AgentState) -> AgentState) {
        _state.value = reducer(_state.value)
    }

    private companion object {
        const val ABORT_ERROR_MESSAGE = "Run aborted"

        /** Pi's literal backoff-cancel message (agent-session.ts _prepareRetry). */
        const val RETRY_CANCELLED = "Retry cancelled"

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
