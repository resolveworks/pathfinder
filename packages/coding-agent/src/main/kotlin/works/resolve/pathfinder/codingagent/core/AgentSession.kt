package works.resolve.pathfinder.codingagent.core

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.clampThinkingLevel
import works.resolve.pathfinder.ai.getSupportedThinkingLevels
import works.resolve.pathfinder.ai.modelThinkingLevelFromWire
import works.resolve.pathfinder.ai.utils.Retry
import works.resolve.pathfinder.ai.utils.RetryCallbacks
import works.resolve.pathfinder.ai.utils.RetryPolicy
import works.resolve.pathfinder.ai.utils.calculateContextTokens
import works.resolve.pathfinder.ai.utils.estimateMessageTokens
import works.resolve.pathfinder.ai.utils.isContextOverflow
import works.resolve.pathfinder.ai.utils.isRecoverableLength
import works.resolve.pathfinder.codingagent.core.RetrySettings
import works.resolve.pathfinder.codingagent.core.compaction.BranchSummaryCallResult
import works.resolve.pathfinder.codingagent.core.compaction.BranchSummaryErrorCode
import works.resolve.pathfinder.codingagent.core.compaction.BranchSummaryResult
import works.resolve.pathfinder.codingagent.core.compaction.CompactionErrorCode
import works.resolve.pathfinder.codingagent.core.compaction.CompactionResult as CompactionOutcome
import works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings
import works.resolve.pathfinder.codingagent.core.compaction.DEFAULT_COMPACTION_SETTINGS
import works.resolve.pathfinder.codingagent.core.compaction.GenerateBranchSummaryOptions
import works.resolve.pathfinder.codingagent.core.compaction.buildSessionContext
import works.resolve.pathfinder.codingagent.core.compaction.collectEntriesForBranchSummary
import works.resolve.pathfinder.codingagent.core.compaction.compact
import works.resolve.pathfinder.codingagent.core.compaction.estimateContextTokens
import works.resolve.pathfinder.codingagent.core.compaction.generateBranchSummary
import works.resolve.pathfinder.codingagent.core.compaction.getLatestCompactionEntry
import works.resolve.pathfinder.codingagent.core.compaction.prepareCompaction
import works.resolve.pathfinder.codingagent.core.compaction.shouldCompact
import works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.SessionManager

/**
 * Prompt-orchestration facade over [Agent]: owns the session tree via
 * [sessionManager] (the single tree + persistence owner), creates user
 * messages from prompt text, and runs the agent plus the post-run
 * continuation loop, including turn auto-retry and automatic compaction.
 *
 * Layering mirrors pi: [Agent] keeps only the single-run
 * prompt/continue/abort primitives, and everything session-scoped — retry
 * counter lifetime across continues, event emission, tree persistence
 * points — lives here. Like pi, a storage failure propagates out of the
 * event sink and fails the run: the [Agent] awaits the sink inline, so a
 * failed append surfaces from [prompt].
 *
 * Tree mutation is serialized by the manager's internal mutex (pi relies on
 * JS single-threadedness); no additional conversation lock exists here.
 */
class AgentSession(
    val agent: Agent,
    /** Session tree and persistence owner. */
    val sessionManager: SessionManager,
    /** Auto-retry budget for failed runs. */
    val retrySettings: RetrySettings = RetrySettings(),
    /** Compaction thresholds. */
    val compactionSettings: CompactionSettings = DEFAULT_COMPACTION_SETTINGS,
    /** Tools available for per-session activation. */
    private val tools: List<AgentTool> = emptyList(),
    /** Provider stack for compaction summarization; null disables automatic compaction. */
    private val models: Models? = null,
    /** Injectable backoff sleep so tests never wait. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Wall clock for minting message timestamps. */
    private val clock: Clock = Clock.System
) {
    /** The session tree snapshot; only [sessionManager] mutates it. */
    val conversation: Conversation get() = sessionManager.conversation

    val model: Model get() = agent.model

    val thinkingLevel: ModelThinkingLevel get() = agent.thinkingLevel

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
     * 1-indexed auto-retry attempt counter, reset only on a successful
     * assistant response, final failure, or cancelled backoff — it survives
     * continuation runs.
     */
    private var retryAttempt = 0

    /** Last assistant message of the current run, consumed by post-run handling. */
    private var lastAssistantMessage: AssistantMessage? = null

    /** Stateful classifier for transient provider errors. */
    private val retryClassifier = Retry()

    /**
     * True once an overflow compact-and-retry has been attempted for the
     * current turn; reset when a user message starts or a non-error/
     * non-length assistant message completes.
     */
    private var overflowRecoveryAttempted = false

    /** True while automatic compaction runs; guards prompt submission. */
    @Volatile
    private var compactionInProgress = false

    /** Name→tool registry over the constructor list. */
    private val toolRegistry: Map<String, AgentTool> = tools.associateBy { it.definition.name }

    private val _events = MutableSharedFlow<AgentEvent>()

    /**
     * Session lifecycle events in emission order: the agent's loop events
     * re-emitted, interleaved with session-level events (auto-retry,
     * compaction). Internal state is reduced before an event is emitted, so
     * observers always see the already-reduced state.
     *
     * Same zero-replay, zero-buffer contract as [Agent.events]: an event
     * emitted with no subscribers is dropped, so observers must subscribe
     * before starting a prompt to observe all of its events; the
     * already-reduced [state] is complete regardless of subscription timing.
     */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    init {
        // A synchronous sink avoids flow-subscription races with prompt().
        agent.attachEventSink { event -> processEvent(event) }
        if (conversation.entries.isNotEmpty()) {
            agent.replaceTranscript(conversation.activeMessages())
        }
        // Seed the thinking level from the branch's configuration fold; a
        // branch without a thinking entry folds "off" (the app layer seeds a
        // default-level entry before adoption).
        agent.setThinkingLevel(
            clampThinkingLevel(
                agent.model,
                modelThinkingLevelFromWire(conversation.effectiveConfiguration().thinkingLevel)
                    ?: ModelThinkingLevel.OFF
            )
        )
        // There is no persisted active-tools fold (pi has no such entry
        // either): tools resolve to the full registry, and the app layer
        // narrows the set per session via setActiveToolsByName.
        if (tools.isNotEmpty()) {
            val activeTools = resolveTools(tools.map { it.definition.name })
            agent.setTools(activeTools)
            agent.setSystemPrompt(buildSystemPrompt(activeTools))
        }
    }

    fun getActiveToolNames(): List<String> = agent.state.value.tools.map { it.definition.name }

    /**
     * Set active tools by name: only tools in the registry can be enabled,
     * unknown names are ignored, and a name appearing twice resolves to two
     * entries (no dedupe). Takes effect on the next run — the agent
     * snapshots tools and system prompt per run (see [Agent.setTools]).
     *
     * Not persisted: no session entry is appended, so the set is re-derived
     * from the full registry on reload.
     */
    fun setActiveToolsByName(toolNames: List<String>) {
        val validTools = toolNames.mapNotNull(toolRegistry::get)
        agent.setTools(validTools)
        agent.setSystemPrompt(buildSystemPrompt(validTools))
    }

    private fun resolveTools(toolNames: List<String>): List<AgentTool> =
        toolNames.mapNotNull(toolRegistry::get)

    /**
     * Submit one prompt: the user message is created here (persisted to the
     * session tree on its message_end), and the agent run plus post-run
     * continuation loop execute in a single job so [abort] cancels runs and
     * backoff alike.
     *
     * @throws IllegalStateException when a prompt is already running.
     * @throws CancellationException when aborted or the caller is cancelled;
     *   the agent has committed its terminal state either way.
     */
    suspend fun prompt(text: String) {
        synchronized(lock) {
            if (compactionInProgress) {
                throw IllegalStateException(COMPACTION_IN_PROGRESS)
            }
            if (active) {
                throw IllegalStateException(
                    "Agent is already processing a prompt. Wait for completion or abort it."
                )
            }
            active = true
        }

        try {
            val promptMessage = UserMessage.ofText(text, clock.now().toEpochMilliseconds())
            coroutineScope {
                // Lazily started so promptJob is published before the job
                // can run anything (abort guarantee).
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
     * Select the model for subsequent prompts: auth for the target provider
     * is validated first, then the agent's model is swapped and a
     * `model_change` entry is appended as a child of the current leaf — so a
     * switch between prompts splits the tree like any other entry, and the
     * active-path projection restores the model on reload.
     *
     * There is no idle guard: the active run keeps its start-of-run model
     * (the agent snapshots it per prompt, see [Agent.setModel]) and the
     * model_change lands wherever the leaf is when the switch happens.
     *
     * Divergence: pi re-applies a thinking level for the new model inside
     * setModel; here that is the app layer's job after a successful switch,
     * because the default it consults is app-owned settings this facade does
     * not hold. A session without a [models] stack (previews) cannot switch.
     *
     * @throws IllegalStateException when the provider is unregistered or
     *   unauthenticated.
     */
    suspend fun setModel(model: Model) {
        val models = this.models
            ?: throw IllegalStateException("No model stack available for setModel")
        if (!models.checkAuth(model.provider)) {
            throw IllegalStateException("No API key for ${model.provider}/${model.id}")
        }
        agent.setModel(model)
        sessionManager.appendModelChange(model.provider, model.id)
    }

    /**
     * Select the thinking level for subsequent prompts: the requested level
     * is clamped to what the current model supports, and a
     * `thinking_level_change` entry is appended (child of the current leaf,
     * like a model_change) only when the effective level actually changes.
     *
     * There is no idle guard: the active run keeps its start-of-run level
     * (the agent snapshots it per prompt, see [Agent.setThinkingLevel]).
     */
    suspend fun setThinkingLevel(level: ModelThinkingLevel) {
        val available = getSupportedThinkingLevels(agent.model)
        val effective = if (available.contains(
                level
            )
        ) {
            level
        } else {
            clampThinkingLevel(agent.model, level)
        }
        val previous = agent.thinkingLevel
        agent.setThinkingLevel(effective)
        if (effective != previous) {
            sessionManager.appendThinkingLevelChange(effective.wire)
        }
    }

    // ---- tree navigation ----

    /** Options of [navigateTree]. */
    data class NavigateTreeOptions(
        /** Whether the user wants the abandoned branch summarized. */
        val summarize: Boolean = false,
        /** Custom instructions appended to (or replacing) the default prompt. */
        val customInstructions: String? = null,
        /** Replace the default prompt with [customInstructions] instead of appending. */
        val replaceInstructions: Boolean = false
    )

    /** Result of [navigateTree]. */
    data class NavigationResult(
        /** Re-edit text for a user-message target (goes to the editor only when empty). */
        val editorText: String? = null,
        val cancelled: Boolean = false,
        val aborted: Boolean = false,
        /** The appended branch-summary entry, when one was generated. */
        val summaryEntry: BranchSummaryEntry? = null
    )

    /**
     * Navigate to a different node in the session tree, staying in the same
     * session (unlike fork). Idle-only; abort is coroutine cancellation.
     *
     * A user-message target re-edits instead of moving the leaf onto it:
     * the leaf moves to the target's parent (or root) and the text is
     * returned as [NavigationResult.editorText] — including when the
     * target is the current leaf (an interrupted run can leave a user
     * message as the leaf). Only non-user targets treat leaf == target as
     * a recordless no-op. When summarizing, the [BranchSummaryEntry] is
     * appended at the navigation target position (the abandoned leaf is
     * recorded as its fromId inside the manager), and the rebuilt context
     * projects branch summaries via [buildSessionContext].
     *
     * @throws IllegalStateException when a prompt/compaction is running or
     *   summarization was requested without a provider stack.
     * @throws IllegalArgumentException when [targetId] does not exist.
     */
    suspend fun navigateTree(
        targetId: String,
        options: NavigateTreeOptions = NavigateTreeOptions()
    ): NavigationResult {
        synchronized(lock) {
            if (active || compactionInProgress) {
                throw IllegalStateException(
                    "Wait for the current response to finish before navigating the session tree."
                )
            }
        }

        val oldLeafId = conversation.leafId
        val targetEntry = conversation.entry(targetId)
            ?: throw IllegalArgumentException("Entry $targetId not found")
        val userMessage = (targetEntry as? MessageEntry)?.message as? UserMessage
        if (targetId == oldLeafId && userMessage == null) {
            return NavigationResult(cancelled = false)
        }

        val summarizationModels = models
        if (options.summarize && summarizationModels == null) {
            throw IllegalStateException("No model available for summarization")
        }

        // Entries to summarize: from the old leaf to the common ancestor.
        val collected = collectEntriesForBranchSummary(conversation, oldLeafId, targetId)

        try {
            var summary: BranchSummaryResult? = null
            if (options.summarize && collected.entries.isNotEmpty()) {
                when (
                    val outcome = generateBranchSummary(
                        collected.entries,
                        GenerateBranchSummaryOptions(
                            models = summarizationModels!!,
                            model = model,
                            customInstructions = options.customInstructions,
                            replaceInstructions = options.replaceInstructions,
                            retry = RetryPolicy(
                                enabled = retrySettings.enabled,
                                maxRetries = retrySettings.maxRetries,
                                baseDelayMs = retrySettings.baseDelayMs
                            ),
                            callbacks = summarizationRetryCallbacks(
                                AgentEvent.SummarizationSource.BranchSummary
                            ),
                            clock = clock
                        )
                    )
                ) {
                    is BranchSummaryCallResult.Err -> {
                        if (outcome.error.code == BranchSummaryErrorCode.ABORTED) {
                            return NavigationResult(cancelled = true, aborted = true)
                        }
                        throw outcome.error
                    }

                    is BranchSummaryCallResult.Ok -> summary = outcome.value
                }
            }

            // Summary is attached at the navigation target position, not the
            // old branch.
            val newLeafId: String? = if (userMessage != null) targetEntry.parentId else targetId
            val editorText = userMessage
                ?.content
                ?.filterIsInstance<TextContent>()
                ?.joinToString("") { it.text }

            var summaryEntry: BranchSummaryEntry? = null
            if (summary != null) {
                val entryId = sessionManager.branchWithSummary(
                    branchFromId = newLeafId,
                    summary = summary.summary,
                    details = buildJsonObject {
                        put("readFiles", JsonArray(summary.readFiles.map(::JsonPrimitive)))
                        put("modifiedFiles", JsonArray(summary.modifiedFiles.map(::JsonPrimitive)))
                    },
                    usage = summary.usage
                )
                summaryEntry = conversation.entry(entryId) as BranchSummaryEntry
            } else if (newLeafId == null) {
                sessionManager.resetLeaf()
            } else {
                sessionManager.branch(newLeafId)
            }

            agent.replaceTranscript(buildSessionContext(conversation.activeEntries()))

            return NavigationResult(
                editorText = editorText,
                cancelled = false,
                summaryEntry = summaryEntry
            )
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Reduce a loop event into session state, re-emit it to [events], and run
     * session-level tracking: message persistence, last-assistant-message
     * capture for post-run handling, and the mid-run retry success reset.
     */
    private suspend fun processEvent(event: AgentEvent) {
        when (event) {
            // A user message starting a turn clears the one-shot overflow
            // recovery budget.
            is AgentEvent.MessageStart -> {
                if (event.message is UserMessage) overflowRecoveryAttempted = false
            }

            is AgentEvent.MessageEnd -> {
                // The tree is the persistence unit and is append-only, so
                // messages later removed from agent state (auto-retry,
                // overflow recovery) stay in history. A storage failure here
                // fails the run (pi parity).
                sessionManager.appendMessage(event.message)
                val assistant = event.message as? AssistantMessage
                if (assistant != null) {
                    lastAssistantMessage = assistant
                    if (assistant.stopReason != StopReason.ERROR &&
                        assistant.stopReason != StopReason.LENGTH
                    ) {
                        overflowRecoveryAttempted = false
                    }
                    // Reset the retry counter at the successful message's
                    // completion, not at post-run.
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

    // ---- post-run handling ----

    /**
     * Post-run handling: consumes [lastAssistantMessage]; when the final
     * assistant message is a retryable error and the retry can be prepared,
     * returns true so the caller continues the agent. Otherwise, when the
     * run still errored after retries, emits `auto_retry_end{success:false}`
     * with the final error and resets the counter, then dispatches automatic
     * compaction.
     */
    private suspend fun handlePostAgentRun(): Boolean {
        val msg = lastAssistantMessage
        lastAssistantMessage = null
        if (msg == null) return false

        if (isRetryableError(msg) && prepareRetry(msg)) return true

        if (msg.stopReason == StopReason.ERROR && retryAttempt > 0) {
            _events.emit(
                AgentEvent.AutoRetryEnd(
                    success = false,
                    attempt = retryAttempt,
                    finalError = msg.errorMessage
                )
            )
            retryAttempt = 0
        }

        if (checkCompaction(msg)) {
            return true
        }

        // pi continues for queued steer/follow-up messages; there are no
        // queues here.
        return false
    }

    // ---- automatic compaction ----

    /**
     * Dispatch automatic compaction after a run.
     *
     * Cases: (1) overflow with retry — a context-overflow error or
     * recoverable length stop is removed from agent state, compacted, and
     * the turn retried once; (2) overflow without retry — a successful
     * response exceeded the context window; (3) threshold — direct or
     * estimated context usage crossed the configured threshold. Cases 2 and
     * 3 compact without retry.
     *
     * @return Whether the post-run loop should continue the agent (overflow
     *   recovery).
     */
    private suspend fun checkCompaction(assistantMessage: AssistantMessage): Boolean {
        if (!compactionSettings.enabled) return false

        if (assistantMessage.stopReason == StopReason.ABORTED) return false

        if (models == null) return false

        val contextWindow = model.contextWindow

        // Skip overflow checks when the message came from a different model:
        // an overflow error from a model the user switched away from must
        // not compact for the new model.
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
                        }
                    )
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
     * Execute threshold or overflow compaction.
     *
     * Divergence: pi signals abort through AbortControllers; here compaction
     * runs inside the prompt coroutine, so abort is plain cancellation and
     * the aborted `compaction_end` is emitted under [NonCancellable] before
     * rethrowing.
     *
     * @return Whether the post-run loop should continue the agent.
     */
    private suspend fun runAutoCompaction(
        reason: AgentEvent.CompactionReason,
        willRetry: Boolean
    ): Boolean {
        val summarizationModels = models ?: return false
        var started = false
        compactionInProgress = true
        try {
            val pathEntries = conversation.activeEntries()
            val preparation = when (
                val outcome = prepareCompaction(pathEntries, compactionSettings)
            ) {
                is CompactionOutcome.Err -> return false
                is CompactionOutcome.Ok -> outcome.value ?: return false
            }

            _events.emit(AgentEvent.CompactionStart(reason))
            started = true

            val compactResult = when (
                val outcome = compact(
                    preparation,
                    summarizationModels,
                    model,
                    // The summary request reasons at the level the user
                    // selected, when the summarization model supports it.
                    thinkingLevel = agent.thinkingLevel,
                    retry = RetryPolicy(
                        enabled = retrySettings.enabled,
                        maxRetries = retrySettings.maxRetries,
                        baseDelayMs = retrySettings.baseDelayMs
                    ),
                    callbacks = summarizationRetryCallbacks(
                        AgentEvent.SummarizationSource.Compaction(reason)
                    ),
                    clock = clock
                )
            ) {
                is CompactionOutcome.Err -> {
                    if (outcome.error.code == CompactionErrorCode.ABORTED) {
                        _events.emit(
                            AgentEvent.CompactionEnd(
                                reason = reason,
                                aborted = true,
                                willRetry = false
                            )
                        )
                        return false
                    }
                    _events.emit(
                        AgentEvent.CompactionEnd(
                            reason = reason,
                            aborted = false,
                            willRetry = false,
                            errorMessage = compactionFailureMessage(
                                reason,
                                outcome.error.message ?: "compaction failed"
                            )
                        )
                    )
                    return false
                }

                is CompactionOutcome.Ok -> outcome.value
            }

            // Single append point: the tree either gains the compaction entry
            // or does not — an abort mid-summarization leaves it untouched.
            sessionManager.appendCompaction(
                summary = compactResult.summary,
                firstKeptEntryId = compactResult.firstKeptEntryId,
                tokensBefore = compactResult.tokensBefore,
                details = compactResult.details,
                usage = compactResult.usage
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
                        details = compactResult.details
                    ),
                    aborted = false,
                    willRetry = willRetry
                )
            )

            if (willRetry) {
                // The overflow response was persisted on message_end before
                // checkCompaction removed it from agent state; rebuilding
                // from the new compaction can restore it as the trailing
                // message, and continueRun would send from it — remove the
                // retriable response again before continuing the turn.
                val messages = agent.state.value.messages
                val lastMsg = messages.lastOrNull()
                if (lastMsg is AssistantMessage &&
                    (
                        lastMsg.stopReason == StopReason.ERROR ||
                            lastMsg.stopReason == StopReason.LENGTH
                        )
                ) {
                    agent.replaceTranscript(messages.dropLast(1))
                }
                return true
            }

            // pi continues for steer/follow-up messages queued during
            // compaction; there are no queues here.
            return false
        } catch (e: CancellationException) {
            if (started) {
                withContext(NonCancellable) {
                    _events.emit(
                        AgentEvent.CompactionEnd(reason = reason, aborted = true, willRetry = false)
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            if (started) {
                _events.emit(
                    AgentEvent.CompactionEnd(
                        reason = reason,
                        aborted = false,
                        willRetry = false,
                        errorMessage = compactionFailureMessage(
                            reason,
                            e.message ?: "compaction failed"
                        )
                    )
                )
            }
            return false
        } finally {
            compactionInProgress = false
        }
    }

    /**
     * Retry callbacks for the summary LLM calls: shared schedule/finish
     * events plus the per-source attempt-start event.
     */
    private fun summarizationRetryCallbacks(
        source: AgentEvent.SummarizationSource
    ): RetryCallbacks = RetryCallbacks(
        onRetryScheduled = { attempt, maxAttempts, delayMs, errorMessage ->
            _events.emit(
                AgentEvent.SummarizationRetryScheduled(
                    attempt,
                    maxAttempts,
                    delayMs,
                    errorMessage
                )
            )
        },
        onRetryAttemptStart = {
            _events.emit(AgentEvent.SummarizationRetryAttemptStart(source))
        },
        onRetryFinished = { _, _, _ ->
            _events.emit(AgentEvent.SummarizationRetryFinished)
        }
    )

    private fun compactionFailureMessage(
        reason: AgentEvent.CompactionReason,
        message: String
    ): String = if (reason == AgentEvent.CompactionReason.OVERFLOW) {
        "Context overflow recovery failed: $message"
    } else {
        "Auto-compaction failed: $message"
    }

    /**
     * Context overflow errors are not retryable (compaction's job); every
     * other retryable assistant error is.
     */
    private fun isRetryableError(message: AssistantMessage): Boolean {
        if (isContextOverflow(message, model.contextWindow)) return false
        return retryClassifier.isRetryableAssistantError(message)
    }

    /**
     * Prepare a retry of [message] with exponential backoff. Returns true
     * when the caller should continue the agent.
     *
     * Divergence: pi sleeps through a dedicated retry AbortController; here
     * abort is plain cancellation of the prompt coroutine, so the sleep's
     * [CancellationException] is caught to emit the terminal
     * `auto_retry_end{success:false, "Retry cancelled"}` under
     * [NonCancellable] before rethrowing. The error message is removed from
     * agent state only — it stays in the append-only session tree.
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
                errorMessage = message.errorMessage ?: "Unknown error"
            )
        )

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
                _events.emit(
                    AgentEvent.AutoRetryEnd(
                        success = false,
                        attempt = attempt,
                        finalError = RETRY_CANCELLED
                    )
                )
            }
            throw e
        }

        return true
    }

    private companion object {
        const val RETRY_CANCELLED = "Retry cancelled"

        const val COMPACTION_IN_PROGRESS =
            "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."

        const val OVERFLOW_RECOVERY_FAILED =
            "Context overflow recovery failed after one compact-and-retry attempt. Try reducing context or switching to a larger-context model."
        const val TRUNCATED_RECOVERY_FAILED =
            "Truncated response recovery failed after one compact-and-retry attempt."
    }
}
