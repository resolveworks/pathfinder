package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.utils.Retry
import works.resolve.pathfinder.ai.utils.RetryCallbacks
import works.resolve.pathfinder.ai.utils.RetryPolicy
import works.resolve.pathfinder.ai.utils.calculateContextTokens
import works.resolve.pathfinder.ai.utils.estimateMessageTokens
import works.resolve.pathfinder.ai.utils.isContextOverflow
import works.resolve.pathfinder.ai.utils.isRecoverableLength
import works.resolve.pathfinder.agent.compaction.CompactionErrorCode
import works.resolve.pathfinder.agent.compaction.CompactionSettings
import works.resolve.pathfinder.agent.compaction.CompactionResult as CompactionOutcome
import works.resolve.pathfinder.agent.compaction.DEFAULT_COMPACTION_SETTINGS
import works.resolve.pathfinder.agent.compaction.buildSessionContext
import works.resolve.pathfinder.agent.compaction.compact
import works.resolve.pathfinder.agent.compaction.estimateContextTokens
import works.resolve.pathfinder.agent.compaction.getLatestCompactionEntry
import works.resolve.pathfinder.agent.compaction.prepareCompaction
import works.resolve.pathfinder.agent.compaction.shouldCompact
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.LaneRecord
import works.resolve.pathfinder.data.sessions.OperationIntent
import works.resolve.pathfinder.data.sessions.OperationOutcome
import works.resolve.pathfinder.data.sessions.RecordError
import works.resolve.pathfinder.data.sessions.SessionState
import works.resolve.pathfinder.ai.utils.uuidv7
import works.resolve.pathfinder.data.settings.RetrySettings
import kotlin.time.Clock
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
 * Durable sink for operation-lifecycle lane records (pi's
 * Session.appendRecord; audit P0-3). [AgentSession] produces the lifecycle
 * trio — operation_started (run/compaction intent), abort_requested,
 * operation_finished — and the owning app layer persists them.
 *
 * Ordering contract: implementations serialize appends in call order —
 * abort_requested precedes the cancellation handler's operation_finished —
 * but may dispatch them asynchronously (durability must not block the run
 * loop), and must not throw: a failed record append degrades durability,
 * never the run.
 */
interface OperationLifecycleRecorder {
    /** Enqueues the record; suspends only if the implementation chooses to await durability. */
    suspend fun append(record: LaneRecord)

    /** Enqueues the record from a non-suspending caller (the abort path). */
    fun appendBestEffort(record: LaneRecord)
}

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
 * templates/skills, manual compaction (`/compact` → `AgentSession.compact`),
 * branch summarization, and the `session_before_compact`/`session_compact`/
 * `session_compact_failed` extension events (pathfinder has no extension
 * runner; only the default summarization path is ported).
 */
class AgentSession(
    /** The single-run agent this session orchestrates. */
    val agent: Agent,
    /** Initial session tree (pi's sessionManager state); adopted as-is. */
    conversation: Conversation = Conversation(emptyList(), null),
    /** Auto-retry budget for failed runs (pi's settings.retry, agent-session auto-retry). */
    val retrySettings: RetrySettings = RetrySettings(),
    /** Compaction thresholds (pi's settings compaction object, `DEFAULT_COMPACTION_SETTINGS`). */
    val compactionSettings: CompactionSettings = DEFAULT_COMPACTION_SETTINGS,
    /**
     * Provider stack used for compaction summarization (pi's
     * `_getSummarizationRequestAuth` + `this.agent.streamFunction`). Null
     * disables automatic compaction — the trigger checks then decline
     * without events, which is how sessions without a usable provider ride.
     */
    private val models: Models? = null,
    /** Injectable backoff sleep so tests never wait. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Wall clock for minting message timestamps (TS→Kotlin timing rule). */
    private val clock: Clock = Clock.System,
) {
    /**
     * Durable operation-lifecycle recorder (pi's session record appenders,
     * first landed here — upstream defines the LaneRecord shapes and the
     * recovery contract but its run loop does not append records yet). Set
     * before the first prompt; null disables recording (tests, previews).
     *
     * One open operation per lane (pi's appendRecord invariant) drives the
     * sequencing: the prompt's run operation spans its whole loop; an
     * embedded auto-compaction finishes the run operation first, opens a
     * compaction operation naming its pre-minted resultEntryId, and a
     * compact-and-retry continuation opens a fresh run operation.
     */
    var operationRecorder: OperationLifecycleRecorder? = null

    /** Id of the lane's open operation (pi's openOperationsByLane mirror); null while idle. */
    @Volatile
    private var currentOperationId: String? = null
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

    /**
     * True once an overflow compact-and-retry has been attempted for the
     * current turn, pi's `_overflowRecoveryAttempted` (agent-session.ts:336):
     * reset when a user message starts or a non-error/non-length assistant
     * message completes.
     */
    private var overflowRecoveryAttempted = false

    /** True while automatic compaction runs; guards prompt submission (pi's `_compactionAbortController` window). */
    @Volatile
    private var compactionInProgress = false

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
            if (compactionInProgress) {
                throw IllegalStateException(COMPACTION_IN_PROGRESS)
            }
            if (active) {
                throw IllegalStateException(
                    "Agent is already processing a prompt. Wait for completion or abort it.",
                )
            }
            active = true
        }

        try {
            val promptMessage = UserMessage.ofText(text, clock.now().toEpochMilliseconds())
            beginOperation(OperationIntent.run())
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
            finishOperation(OperationOutcome.COMPLETED)
        } catch (e: CancellationException) {
            finishOperation(OperationOutcome.ABORTED)
            throw e
        } catch (e: Exception) {
            finishOperation(
                OperationOutcome.FAILED,
                RecordError(
                    code = e::class.simpleName ?: "error",
                    message = e.message ?: "operation failed",
                ),
            )
            throw e
        } finally {
            promptJob = null
            synchronized(lock) { active = false }
        }
    }

    /** Abort the active prompt, if any. May be called from any coroutine. */
    fun abort() {
        // abort_requested first, in call order, so the serialized recorder
        // persists it before the cancellation handler's operation_finished
        // (recovery distinguishes a requested abort from a crash that way).
        val operationId = currentOperationId
        if (operationId != null) {
            operationRecorder?.appendBestEffort(
                LaneRecord.AbortRequestedRecord(id = uuidv7(), lane = SessionState.LANE_MAIN, runId = operationId),
            )
        }
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

    // ---- operation lifecycle records (pi's LaneRecord trio; audit P0-3) ----

    /**
     * Opens the lane's operation: appends operation_started with the current
     * leaf as sourceLeafId. The sourceLeafId may name an entry that is still
     * buffered (unpersisted) — legal per pi's invariants (see
     * [LaneRecord]).
     */
    private suspend fun beginOperation(intent: OperationIntent): String {
        val id = uuidv7()
        recordAppend(
            LaneRecord.OperationStartedRecord(
                id = id,
                lane = SessionState.LANE_MAIN,
                sourceLeafId = conversation.leafId,
                intent = intent,
            ),
        )
        currentOperationId = id
        return id
    }

    /** Closes the open operation (no-op when none is open) with [outcome]. */
    private suspend fun finishOperation(outcome: OperationOutcome, error: RecordError? = null) {
        val id = currentOperationId ?: return
        currentOperationId = null
        withContext(NonCancellable) {
            recordAppend(
                LaneRecord.OperationFinishedRecord(
                    id = uuidv7(),
                    lane = SessionState.LANE_MAIN,
                    runId = id,
                    outcome = outcome,
                    error = error,
                ),
            )
        }
    }

    private suspend fun recordAppend(record: LaneRecord) {
        operationRecorder?.append(record)
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
            // A user message starting a turn clears the one-shot overflow
            // recovery budget (pi's _handleAgentEvent, agent-session.ts ~627).
            is AgentEvent.MessageStart -> {
                if (event.message is UserMessage) overflowRecoveryAttempted = false
            }
            is AgentEvent.MessageEnd -> {
                // Pi's sessionManager.appendMessage on message_end: the tree
                // is the persistence unit and is append-only, so removed
                // agent-state messages (auto-retry, overflow recovery) stay
                // in history exactly like pi.
                conversation = conversation.append(event.message)
                val assistant = event.message as? AssistantMessage
                if (assistant != null) {
                    lastAssistantMessage = assistant
                    if (assistant.stopReason != StopReason.ERROR && assistant.stopReason != StopReason.LENGTH) {
                        overflowRecoveryAttempted = false
                    }
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

        if (checkCompaction(msg)) {
            return true
        }

        // Pi continues once more for queued steer/follow-up messages
        // (`agent.hasQueuedMessages()`); pathfinder has no queues, so the
        // post-run loop ends here.
        return false
    }

    // ---- automatic compaction (pi agent-session.ts ~2104) ----

    /**
     * Dispatch automatic compaction after a run, ported from pi's
     * `_checkCompaction` (agent-session.ts ~2104).
     *
     * Cases: (1) overflow with retry — a context-overflow error or
     * recoverable length stop is removed from agent state, compacted, and
     * the turn retried once; (2) overflow without retry — a successful
     * response exceeded the context window, compacted without retry;
     * (3) threshold — direct or estimated context usage crossed the
     * configured threshold, compacted without retry.
     *
     * Divergence: pi's `skipAbortedCheck = false` parameter exists for its
     * pre-prompt compaction dispatch (`compactIfNeeded before submission) —
     * pathfinder dispatches compaction only post-run (there are no queued
     * prompts that could overflow between turns), so the parameter has no
     * call site and is not ported; aborted messages always skip here.
     *
     * @return Whether the post-run loop should continue the agent (overflow
     *   recovery).
     */
    private suspend fun checkCompaction(assistantMessage: AssistantMessage): Boolean {
        if (!compactionSettings.enabled) return false

        // Skip if the message was aborted (user cancelled).
        if (assistantMessage.stopReason == StopReason.ABORTED) return false

        // No summarization provider: nothing compaction can do.
        if (models == null) return false

        val contextWindow = model.contextWindow

        // Skip overflow checks when the message came from a different model:
        // an overflow error from a model the user switched away from must not
        // compact for the new model (pi's sameModel guard).
        val sameModel =
            assistantMessage.provider == model.provider && assistantMessage.model == model.id

        // Skip when this assistant message predates the latest compaction
        // boundary: stale pre-compaction usage/errors must not retrigger
        // compaction on the first prompt after one just finished.
        val compactionEntry = getLatestCompactionEntry(conversation.activeEntries())
        if (compactionEntry != null && assistantMessage.timestamp <= compactionEntry.timestamp) {
            return false
        }

        // Cases 1 and 2: context overflow.
        val contextOverflow = sameModel && isContextOverflow(assistantMessage, contextWindow)
        val recoverableLength = sameModel && isRecoverableLength(assistantMessage, model.maxTokens)
        if (contextOverflow || recoverableLength) {
            val willRetry = assistantMessage.stopReason != StopReason.STOP

            // Case 2: the response completed successfully. Compact, but do
            // not retry — agent.continue() cannot continue from a completed
            // assistant response.
            if (!willRetry) {
                return runAutoCompaction(AgentEvent.CompactionReason.OVERFLOW, willRetry = false)
            }

            if (overflowRecoveryAttempted) {
                _events.emit(
                    AgentEvent.CompactionEnd(
                        reason = AgentEvent.CompactionReason.OVERFLOW,
                        aborted = false,
                        willRetry = false,
                        errorMessage = if (contextOverflow) {
                            OVERFLOW_RECOVERY_FAILED
                        } else {
                            TRUNCATED_RECOVERY_FAILED
                        },
                    ),
                )
                return false
            }

            // Case 1: remove the failed or truncated message from agent
            // state, compact, and retry once. The message remains in the
            // session tree but is excluded from the retry context.
            overflowRecoveryAttempted = true
            val messages = agent.state.value.messages
            if (messages.isNotEmpty() && messages.last() is AssistantMessage) {
                agent.replaceTranscript(messages.dropLast(1))
            }
            return runAutoCompaction(AgentEvent.CompactionReason.OVERFLOW, willRetry)
        }

        // Case 3: threshold compaction without retry. For error messages or
        // all-zero usage, estimate from message sizes; usage-backed
        // estimates additionally verify the usage source is post-compaction
        // (kept pre-compaction messages carry stale, larger usage).
        val directContextTokens = calculateContextTokens(assistantMessage.usage)
        val contextTokens: Int
        if (assistantMessage.stopReason == StopReason.ERROR || directContextTokens == 0) {
            val messages = agent.state.value.messages
            val estimate = estimateContextTokens(messages)
            if (estimate.lastUsageIndex != null) {
                val usageMsg = messages[estimate.lastUsageIndex!!]
                if (
                    compactionEntry != null &&
                    usageMsg is AssistantMessage &&
                    usageMsg.timestamp <= compactionEntry.timestamp
                ) {
                    return false
                }
            }
            contextTokens = estimate.tokens
        } else {
            contextTokens = directContextTokens
        }
        if (shouldCompact(contextTokens, contextWindow, compactionSettings)) {
            return runAutoCompaction(AgentEvent.CompactionReason.THRESHOLD, willRetry = false)
        }
        return false
    }

    /**
     * Execute threshold or overflow compaction, ported from pi's
     * `_runAutoCompaction` (agent-session.ts ~1960).
     *
     * Exclusions: the `session_before_compact` extension hook (cancel or
     * extension-supplied compaction) and the `session_compact`/
     * `session_compact_failed` extension events have no extension runner in
     * pathfinder, so only the default summary generator runs. Manual
     * compaction (pi's `compact()`) enters through the same helper upstream
     * but has no entry point here.
     *
     * Divergence: upstream signals abort through AbortControllers and emits
     * `compaction_end{aborted:true}` only for its explicit signal checks;
     * here compaction runs inside the prompt coroutine, so abort is plain
     * cancellation and the aborted `compaction_end` is emitted under
     * [NonCancellable] before rethrowing (the retry-end precedent).
     *
     * @return Whether the post-run loop should continue the agent.
     */
    private suspend fun runAutoCompaction(
        reason: AgentEvent.CompactionReason,
        willRetry: Boolean,
    ): Boolean {
        val summarizationModels = models ?: return false
        var started = false
        compactionInProgress = true
        try {
            // The triggering run's operation ends before compaction opens its
            // own (one open operation per lane, pi's appendRecord invariant).
            finishOperation(OperationOutcome.COMPLETED)
            val pathEntries = conversation.activeEntries()
            val preparation = when (
                val outcome = prepareCompaction(pathEntries, compactionSettings)
            ) {
                is CompactionOutcome.Err -> return false
                is CompactionOutcome.Ok -> outcome.value ?: return false
            }

            // The compaction entry id is minted up front so the operation
            // record can name its resultEntryId (pi's compaction intent).
            val resultEntryId = uuidv7()
            beginOperation(OperationIntent.compaction(resultEntryId))

            _events.emit(AgentEvent.CompactionStart(reason))
            started = true

            val compactResult = when (
                val outcome = compact(
                    preparation,
                    summarizationModels,
                    model,
                    retry = RetryPolicy(
                        enabled = retrySettings.enabled,
                        maxRetries = retrySettings.maxRetries,
                        baseDelayMs = retrySettings.baseDelayMs,
                    ),
                    callbacks = summarizationRetryCallbacks(reason),
                    clock = clock,
                )
            ) {
                is CompactionOutcome.Err -> {
                    if (outcome.error.code == CompactionErrorCode.ABORTED) {
                        _events.emit(
                            AgentEvent.CompactionEnd(reason = reason, aborted = true, willRetry = false),
                        )
                        finishOperation(OperationOutcome.ABORTED)
                        return false
                    }
                    _events.emit(
                        AgentEvent.CompactionEnd(
                            reason = reason,
                            aborted = false,
                            willRetry = false,
                            errorMessage = compactionFailureMessage(reason, outcome.error.message ?: "compaction failed"),
                        ),
                    )
                    finishOperation(
                        OperationOutcome.FAILED,
                        RecordError(
                            code = outcome.error.code.name,
                            message = outcome.error.message ?: "compaction failed",
                        ),
                    )
                    return false
                }
                is CompactionOutcome.Ok -> outcome.value
            }

            // Single append point: the tree either gains the compaction entry
            // or does not — an abort mid-summarization leaves it untouched.
            conversation = conversation.appendCompaction(
                summary = compactResult.summary,
                retainedTail = compactResult.retainedTail,
                tokensBefore = compactResult.tokensBefore,
                details = compactResult.details,
                usage = compactResult.usage,
                id = resultEntryId,
            )
            val sessionContext = buildSessionContext(conversation.activeEntries())
            agent.replaceTranscript(sessionContext)
            val estimatedTokensAfter = sessionContext.sumOf { estimateMessageTokens(it) }

            _events.emit(
                AgentEvent.CompactionEnd(
                    reason = reason,
                    result = AgentEvent.CompactionResult(
                        summary = compactResult.summary,
                        tokensBefore = compactResult.tokensBefore,
                        estimatedTokensAfter = estimatedTokensAfter,
                        usage = compactResult.usage,
                        details = compactResult.details,
                    ),
                    aborted = false,
                    willRetry = willRetry,
                ),
            )

            finishOperation(OperationOutcome.COMPLETED)

            if (willRetry) {
                // The overflow retry continues as a fresh run operation (the
                // pre-compaction one finished above).
                beginOperation(OperationIntent.run())
                // The overflow response was persisted on message_end before
                // checkCompaction removed it from agent state; rebuilding
                // from the new compaction can restore that kept entry as the
                // trailing message. agent.continue() (a plain run) would send
                // from it, so remove the retriable error or truncated-length
                // response again before continuing the interrupted turn
                // (agent-session.ts ~2355).
                val messages = agent.state.value.messages
                val lastMsg = messages.lastOrNull()
                if (lastMsg is AssistantMessage &&
                    (lastMsg.stopReason == StopReason.ERROR || lastMsg.stopReason == StopReason.LENGTH)
                ) {
                    agent.replaceTranscript(messages.dropLast(1))
                }
                return true
            }

            // Pi continues once when steer/follow-up messages queued during
            // compaction; no queues exist here.
            return false
        } catch (e: CancellationException) {
            if (started) {
                withContext(NonCancellable) {
                    _events.emit(AgentEvent.CompactionEnd(reason = reason, aborted = true, willRetry = false))
                }
            }
            finishOperation(OperationOutcome.ABORTED)
            throw e
        } catch (e: Exception) {
            if (started) {
                _events.emit(
                    AgentEvent.CompactionEnd(
                        reason = reason,
                        aborted = false,
                        willRetry = false,
                        errorMessage = compactionFailureMessage(reason, e.message ?: "compaction failed"),
                    ),
                )
            }
            finishOperation(
                OperationOutcome.FAILED,
                RecordError(code = e::class.simpleName ?: "error", message = e.message ?: "compaction failed"),
            )
            return false
        } finally {
            compactionInProgress = false
        }
    }

    /**
     * Retry callbacks for the summary LLM calls, pi's
     * `_summarizationRetryCallbacks` (agent-session.ts ~2837) reduced to the
     * compaction source (branchSummary excluded).
     */
    private fun summarizationRetryCallbacks(reason: AgentEvent.CompactionReason): RetryCallbacks =
        RetryCallbacks(
            onRetryScheduled = { attempt, maxAttempts, delayMs, errorMessage ->
                _events.emit(
                    AgentEvent.SummarizationRetryScheduled(attempt, maxAttempts, delayMs, errorMessage),
                )
            },
            onRetryAttemptStart = {
                _events.emit(AgentEvent.SummarizationRetryAttemptStart(reason))
            },
            onRetryFinished = { _, _, _ ->
                _events.emit(AgentEvent.SummarizationRetryFinished)
            },
        )

    /** Pi's failure formatting for thrown/auto-compaction errors (agent-session.ts ~2365). */
    private fun compactionFailureMessage(reason: AgentEvent.CompactionReason, message: String): String =
        if (reason == AgentEvent.CompactionReason.OVERFLOW) {
            "Context overflow recovery failed: $message"
        } else {
            "Auto-compaction failed: $message"
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

        /** Pi's prompt-during-compaction rejection (agent-session.ts prompt). */
        const val COMPACTION_IN_PROGRESS =
            "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."

        /** Pi's one-shot overflow recovery failures (agent-session.ts _checkCompaction), verbatim. */
        const val OVERFLOW_RECOVERY_FAILED =
            "Context overflow recovery failed after one compact-and-retry attempt. Try reducing context or switching to a larger-context model."
        const val TRUNCATED_RECOVERY_FAILED =
            "Truncated response recovery failed after one compact-and-retry attempt."
    }
}
