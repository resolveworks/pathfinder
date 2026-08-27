package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.utils.Retry
import works.resolve.pathfinder.ai.utils.isContextOverflow
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.RetrySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prompt-orchestration facade over [Agent], ported from pi's AgentSession
 * (coding-agent `src/core/agent-session.ts`) reduced to what pathfinder's
 * no-tools chat needs: it owns the session tree ([Conversation], pi's
 * sessionManager ownership), creates user messages from prompt text, runs
 * the agent plus the post-run continuation loop
 * (pi's `_runAgentPrompt`: `agent.prompt(...)` then
 * `while (_handlePostAgentRun()) agent.continue()`), and owns turn
 * auto-retry (pi's `_prepareRetry`/`_retryAttempt`).
 *
 * Layering mirrors pi: [Agent] keeps only the single-run
 * prompt/continue/abort primitives (pi packages/agent/src/agent.ts has no
 * retry), and everything session-scoped — retry counter lifetime across
 * continues, event emission, tree persistence points — lives here.
 *
 * Deliberate exclusions (documented at each boundary): pi's steer/follow-up
 * queues and queued-message continuation (`_queueSteer`/`_queueFollowUp`,
 * `agent.hasQueuedMessages()`), extension commands and hooks, prompt
 * templates/skills, and compaction (added in this port's later waves).
 */
class AgentSession(
    /** The single-run agent this session orchestrates. */
    val agent: Agent,
    /** Initial session tree (pi's sessionManager state); adopted as-is. */
    conversation: Conversation = Conversation(emptyList(), null),
    /** Auto-retry budget for failed runs (pi's settings.retry, agent-session auto-retry). */
    val retrySettings: RetrySettings = RetrySettings(),
    /** Injectable backoff sleep so tests never wait. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The session tree; only this class appends to it during prompts. */
    var conversation: Conversation = conversation
        private set

    val model: Model get() = agent.model

    val state: StateFlow<AgentState> get() = agent.state

    /** Guards [active]; all critical sections are brief and non-suspending. */
    private val lock = Any()

    /** True while this session's prompt loop is running; guarded by [lock]. */
    private var active = false

    /**
     * Job of the current prompt loop (agent runs plus backoff sleeps),
     * cancelled by [abort]; volatile: abort may come from any coroutine.
     */
    @Volatile
    private var promptJob: Job? = null

    /**
     * 1-indexed auto-retry attempt counter, pi's `_retryAttempt`
     * (agent-session.ts): session-scoped and reset only on a successful
     * assistant response, final failure, or cancelled backoff — it survives
     * continuation runs.
     */
    private var retryAttempt = 0

    /**
     * Last assistant message of the current run, pi's `_lastAssistantMessage`
     * (agent-session.ts): consumed (nulled) by post-run handling.
     */
    private var lastAssistantMessage: AssistantMessage? = null

    /** Stateful classifier for transient provider errors (pi's isRetryableAssistantError). */
    private val retryClassifier = Retry()

    private val _events = MutableSharedFlow<AgentEvent>()

    /**
     * Session lifecycle events in source order: the agent's loop events
     * re-emitted, interleaved with session-level events (auto-retry, and
     * later compaction). Internal state is reduced before an event is
     * emitted, so observers always see the already-reduced state.
     *
     * Same zero-replay, zero-buffer contract as [Agent.events]: a value
     * emitted with no subscribers is dropped immediately, and observers must
     * subscribe before starting a prompt to observe all of its events; the
     * already-reduced [state] is always complete regardless of subscription
     * timing.
     */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    init {
        // Pi's agent-session subscribes to agent events once at construction;
        // the synchronous sink avoids flow-subscription races with prompt().
        agent.eventSink = { event -> processEvent(event) }
        // Seed the agent transcript from the adopted tree's active path (pi's
        // buildSessionContext projection of the session manager's branch).
        if (conversation.entries.isNotEmpty()) {
            agent.replaceTranscript(conversation.activeMessages())
        }
    }

    /**
     * Submit one prompt, pi's `AgentSession.prompt(text)` reduced: the user
     * message is created here, persisted to the session tree on its
     * message_end, and the agent run plus post-run continuation loop execute
     * in a single job so [abort] cancels runs and backoff alike.
     *
     * @throws IllegalStateException when a prompt is already running.
     * @throws CancellationException when aborted or when the caller is
     *   cancelled; the agent already committed its terminal state either way.
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

        try {
            val promptMessage = UserMessage.ofText(text, System.currentTimeMillis())
            coroutineScope {
                // Lazily started so promptJob is published before the job can
                // run anything, mirroring Agent.prompt's abort guarantee.
                val job = launch(start = CoroutineStart.LAZY) {
                    agent.prompt(listOf(promptMessage))
                    while (handlePostAgentRun()) {
                        agent.continueRun()
                    }
                }
                promptJob = job
                job.start()

                job.join()
                if (job.isCancelled) {
                    throw CancellationException("Prompt aborted")
                }
            }
        } finally {
            promptJob = null
            synchronized(lock) { active = false }
        }
    }

    /** Abort the active prompt, if any. May be called from any coroutine. */
    fun abort() {
        promptJob?.cancel()
    }

    /**
     * Replace the session tree (pi's navigateTree mutating the session
     * manager's branch) and rebuild the agent transcript from the new active
     * path. Only valid while idle; the tree itself is append-only so this is
     * the sole reparenting entry point (leaf moves).
     *
     * @throws IllegalStateException when a prompt is running.
     */
    fun replaceConversation(updated: Conversation) {
        synchronized(lock) {
            if (active) {
                throw IllegalStateException("Cannot navigate the session while a prompt is running")
            }
            // Tree first: the transcript rebuild's synchronous state
            // emission is observed against the already-updated tree.
            conversation = updated
            agent.replaceTranscript(updated.activeMessages())
        }
    }

    /**
     * Reduce a loop event into session state, re-emit it to [events], and run
     * the session-level tracking pi does in `_handleAgentEvent`
     * (agent-session.ts ~631): message persistence points, the
     * last-assistant-message tracking for post-run handling, and the
     * mid-run retry success reset.
     */
    private suspend fun processEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageEnd -> {
                // Pi's sessionManager.appendMessage on message_end: the tree
                // is the persistence unit and is append-only, so removed
                // agent-state messages (auto-retry, overflow recovery) stay
                // in history exactly like pi.
                conversation = conversation.append(event.message)
                val assistant = event.message as? AssistantMessage
                if (assistant != null) {
                    lastAssistantMessage = assistant
                    // Reset the retry counter immediately on a successful
                    // assistant response (agent-session.ts ~684): fires at
                    // that message's completion, not at post-run.
                    if (assistant.stopReason != StopReason.ERROR && retryAttempt > 0) {
                        val attempt = retryAttempt
                        retryAttempt = 0
                        _events.emit(AgentEvent.AutoRetryEnd(success = true, attempt = attempt))
                    }
                }
            }
            else -> Unit
        }
        _events.emit(event)
    }

    // ---- post-run handling (pi agent-session.ts ~1101) ----

    /**
     * Post-run handling, ported from pi's `_handlePostAgentRun`: consumes
     * [lastAssistantMessage]; when its final assistant message is a
     * retryable error and the retry can be prepared, returns true so the
     * caller continues the agent. Otherwise, when the run still errored after
     * retries, emits `auto_retry_end{success:false}` with the final error and
     * resets the counter.
     *
     * Exclusions: pi then runs `_checkCompaction` (ported with the compaction
     * wave) and continues for queued messages — pathfinder has no
     * steer/follow-up queues, so the loop ends here.
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
     * stays in the session tree, which is append-only and already holds it.
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
        val messages = agent.state.value.messages
        if (messages.isNotEmpty() && messages.last() is AssistantMessage) {
            agent.replaceTranscript(messages.dropLast(1))
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

    private companion object {
        /** Pi's literal backoff-cancel message (agent-session.ts _prepareRetry). */
        const val RETRY_CANCELLED = "Retry cancelled"
    }
}
