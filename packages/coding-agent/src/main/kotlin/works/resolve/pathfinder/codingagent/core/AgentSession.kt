package works.resolve.pathfinder.codingagent.core

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
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
import works.resolve.pathfinder.agent.AgentContext
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentLoopTurnUpdate
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.PrepareNextTurnContext
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
import works.resolve.pathfinder.codingagent.core.compaction.BranchSummaryCallResult
import works.resolve.pathfinder.codingagent.core.compaction.BranchSummaryErrorCode
import works.resolve.pathfinder.codingagent.core.compaction.BranchSummaryResult
import works.resolve.pathfinder.codingagent.core.compaction.CompactionErrorCode
import works.resolve.pathfinder.codingagent.core.compaction.CompactionPreparation
import works.resolve.pathfinder.codingagent.core.compaction.CompactionResult as CompactionOutcome
import works.resolve.pathfinder.codingagent.core.compaction.GenerateBranchSummaryOptions
import works.resolve.pathfinder.codingagent.core.compaction.collectEntriesForBranchSummary
import works.resolve.pathfinder.codingagent.core.compaction.compact
import works.resolve.pathfinder.codingagent.core.compaction.estimateContextTokens
import works.resolve.pathfinder.codingagent.core.compaction.generateBranchSummary
import works.resolve.pathfinder.codingagent.core.compaction.prepareCompaction
import works.resolve.pathfinder.codingagent.core.compaction.shouldCompact

/** pi's scopedModels entry (the `--models` flag list): a model plus an optional explicit thinking level. */
data class ScopedModel(val model: Model, val thinkingLevel: ModelThinkingLevel? = null)

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
    private val manager: SessionManager,
    /** Live settings source; effective retry/compaction/thinking defaults are read at each decision point. */
    val settingsManager: SettingsManager,
    /** Scoped models for cycling (pi's --models flag); see [scopedModels]. */
    scopedModels: List<ScopedModel> = emptyList(),
    /** Tools available for per-session activation. */
    private val tools: List<AgentTool> = emptyList(),
    /** Provider stack for compaction summarization; null disables automatic compaction. */
    private val models: Models? = null,
    /** Injectable backoff sleep so tests never wait. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Wall clock for minting message timestamps. */
    private val clock: Clock = Clock.System
) {
    /** Read-only session tree view (upstream's ReadonlySessionManager Pick type; unlike upstream, the mutable manager stays private). */
    val sessionManager: ReadonlySessionManager get() = manager

    private var _scopedModels: List<ScopedModel> = scopedModels

    /** pi's scopedModels getter: scoped models for cycling, settable via [setScopedModels]. */
    val scopedModels: List<ScopedModel> get() = _scopedModels

    /** pi's setScopedModels. */
    fun setScopedModels(scopedModels: List<ScopedModel>) {
        _scopedModels = scopedModels
    }

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
        // pi's sdk.ts session restore: transcript and thinking level come
        // from the branch's session context (compaction-aware, like a fresh
        // run after compaction).
        val context = manager.buildSessionContext()
        if (context.messages.isNotEmpty()) {
            agent.replaceTranscript(context.messages)
        }
        // Seed the thinking level from the branch's configuration fold; a
        // branch without a thinking entry folds "off" (the app layer seeds
        // a default-level entry before adoption).
        agent.setThinkingLevel(
            clampThinkingLevel(
                agent.model,
                modelThinkingLevelFromWire(context.thinkingLevel) ?: ModelThinkingLevel.OFF
            )
        )
        // There is no persisted active-tools fold (pi has no such entry
        // either): tools resolve to the full registry, and the app layer
        // narrows the set per session via setActiveToolsByName.
        if (tools.isNotEmpty()) {
            agent.setTools(resolveTools(tools.map { it.definition.name }))
        }
        agent.setSystemPrompt(buildSystemPrompt(agent.state.value.tools.toList()))
        installAgentNextTurnRefresh()
    }

    /**
     * pi's _installAgentNextTurnRefresh: before every assistant response
     * after the first, run the mid-run compaction checkpoint, then refresh
     * context (system prompt, tools, messages) and model/thinkingLevel from
     * the live agent state — this is what makes mid-run setter calls take
     * effect on the next turn.
     */
    private fun installAgentNextTurnRefresh() {
        agent.prepareNextTurnWithContext = { turn ->
            val context = compactBeforeNextAssistantResponse(turn.context)
            AgentLoopTurnUpdate(
                context = context.copy(
                    systemPrompt = agent.state.value.systemPrompt,
                    tools = agent.state.value.tools.toList()
                ),
                model = agent.state.value.model,
                thinkingLevel = agent.state.value.thinkingLevel
            )
        }
    }

    /**
     * pi's _compactBeforeNextAssistantResponse: when the turn's context
     * crosses the compaction threshold, run auto compaction ("threshold"
     * reason) and rebuild the context from the agent transcript.
     */
    private suspend fun compactBeforeNextAssistantResponse(context: AgentContext): AgentContext {
        if (
            model.contextWindow <= 0 ||
            !shouldCompact(
                estimateContextTokens(context.messages).tokens,
                model.contextWindow,
                settingsManager.getCompactionSettings()
            )
        ) {
            return context
        }
        runAutoCompaction(AgentEvent.CompactionReason.THRESHOLD, willRetry = false)
        return context.copy(messages = agent.state.value.messages.toList())
    }

    /** pi's _findLastAssistantMessage: last assistant on the current branch context. */
    private fun findLastAssistantMessage(): AssistantMessage? =
        agent.state.value.messages.lastOrNull { it is AssistantMessage } as AssistantMessage?

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
            // Preflight (pi's prompt ordering: model/auth validation before
            // the compaction check, before any message is built). The model
            // is non-null by construction, so only auth is checked; sessions
            // without a provider stack (no [models]) have no auth seam and
            // are not validated. The `/login` re-authentication hint pi
            // appends has no pathfinder equivalent (the app owns login UX)
            // and is dropped from both messages, as in [Messages.kt].
            models?.let { stack ->
                if (!stack.checkAuth(model.provider)) {
                    val message =
                        if (stack.isUsingOAuth(model.provider)) {
                            "Authentication failed for \"${model.provider}\". " +
                                "Credentials may have expired or network is unavailable."
                        } else {
                            formatNoApiKeyFoundMessage(model.provider)
                        }
                    throw SessionError(SessionErrorCode.AUTH, message)
                }
            }

            // Check if we need to compact before sending (catches aborted
            // responses the post-run check skips). The user's new prompt is
            // sent below, so do not continue the agent here (pi: the result
            // is likewise unused).
            findLastAssistantMessage()?.let { lastAssistant ->
                checkCompaction(lastAssistant, skipAbortedCheck = false)
            }

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
     * There is no idle guard: the active run picks up the switch on its
     * next turn via the installed [Agent.prepareNextTurnWithContext] refresh
     * (pi's mechanism), and the model_change lands wherever the leaf is when
     * the switch happens. A session without a [models] stack (previews)
     * cannot switch.
     *
     * Like pi's setModel, a thinking level is re-applied for the new model:
     * a per-model override, else the global default, else the session's
     * current level, clamped to the new model's capabilities. Persists to
     * global defaults only when [persist] is true; persistence never
     * implicitly persists the resulting thinking level.
     *
     * @throws IllegalStateException when the provider is unregistered or
     *   unauthenticated.
     */
    suspend fun setModel(model: Model, persist: Boolean = false) {
        val models = this.models
            ?: throw IllegalStateException("No model stack available for setModel")
        if (!models.checkAuth(model.provider)) {
            throw IllegalStateException("No API key for ${model.provider}/${model.id}")
        }
        val thinkingLevel = getThinkingLevelForModelSwitch(model)
        agent.setModel(model)
        manager.appendModelChange(model.provider, model.id)
        if (persist) {
            settingsManager.setDefaultModelAndProvider(model.provider, model.id)
            addPersistedDefaultToNonEmptyScope(model)
        }
        // Apply the thinking level for the new model; setThinkingLevel
        // clamps to model capabilities and appends only on change.
        setThinkingLevel(thinkingLevel)
    }

    /** pi's _addPersistedDefaultToNonEmptyScope. */
    private suspend fun addPersistedDefaultToNonEmptyScope(model: Model) {
        if (_scopedModels.isEmpty()) return
        if (_scopedModels.any { Models.modelsAreEqual(it.model, model) }) return

        _scopedModels = _scopedModels + ScopedModel(model)

        val enabledModels = settingsManager.getEnabledModels()
        if (enabledModels.isNullOrEmpty()) return

        val modelReference = "${model.provider}/${model.id}"
        if (enabledModels.any { it.equals(modelReference, ignoreCase = true) }) return
        settingsManager.setEnabledModels(enabledModels + modelReference)
    }

    /**
     * Select the thinking level for subsequent prompts: the requested level
     * is clamped to what the current model supports, and a
     * `thinking_level_change` entry is appended (child of the current leaf,
     * like a model_change) only when the effective level actually changes.
     * Persists the requested (unclamped) level to global defaults only when
     * [persist] is true.
     *
     * There is no idle guard: the active run keeps its start-of-run level
     * (the agent snapshots it per prompt, see [Agent.setThinkingLevel]).
     */
    suspend fun setThinkingLevel(level: ModelThinkingLevel, persist: Boolean = false) {
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
        if (persist) {
            settingsManager.setDefaultThinkingLevel(level)
        }
        if (effective != previous) {
            manager.appendThinkingLevelChange(effective.wire)
        }
    }

    /** pi's _getThinkingLevelForModelSwitch: explicit scoped level, then a
     * per-model override for the target model, then the global default, then
     * the session's current level. */
    private fun getThinkingLevelForModelSwitch(
        targetModel: Model?,
        explicitLevel: ModelThinkingLevel? = null
    ): ModelThinkingLevel {
        if (explicitLevel != null) {
            return explicitLevel
        }
        if (targetModel != null) {
            settingsManager.getModelThinkingLevel(targetModel.provider, targetModel.id)?.let {
                return it
            }
        }
        return settingsManager.getDefaultThinkingLevel() ?: thinkingLevel
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

    /** What [navigateTree] did with the request. */
    enum class NavigationOutcome {
        /** The leaf moved to (or past) the target. */
        NAVIGATED,

        /** A user-message target that is not the leaf: the leaf moved to
         * its parent and the text is returned for re-editing. */
        RE_EDIT,

        /** A target that is already the leaf (any entry type): nothing
         * recorded. */
        NO_OP
    }

    /** Result of [navigateTree]. */
    data class NavigationResult(
        val outcome: NavigationOutcome = NavigationOutcome.NAVIGATED,
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
     * returned as [NavigationResult.editorText]
     * ([NavigationOutcome.RE_EDIT]). A target that is already the leaf is
     * a recordless no-op ([NavigationOutcome.NO_OP]) whatever its type —
     * including a user-message leaf left by an interrupted run, which pi
     * also no-ops. When summarizing, the [BranchSummaryEntry] is
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

        val oldLeafId = manager.getLeafId()
        val targetEntry = manager.getEntry(targetId)
            ?: throw IllegalArgumentException("Entry $targetId not found")
        if (targetId == oldLeafId) {
            return NavigationResult(outcome = NavigationOutcome.NO_OP, cancelled = false)
        }
        val userMessage = (targetEntry as? MessageEntry)?.message as? UserMessage

        val summarizationModels = models
        if (options.summarize && summarizationModels == null) {
            throw IllegalStateException("No model available for summarization")
        }

        // Entries to summarize: from the old leaf to the common ancestor.
        val collected = collectEntriesForBranchSummary(manager, oldLeafId, targetId)

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
                            retry = summarizationRetryPolicy(),
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
                val entryId = manager.branchWithSummary(
                    branchFromId = newLeafId,
                    summary = summary.summary,
                    details = buildJsonObject {
                        put("readFiles", JsonArray(summary.readFiles.map(::JsonPrimitive)))
                        put("modifiedFiles", JsonArray(summary.modifiedFiles.map(::JsonPrimitive)))
                    },
                    usage = summary.usage
                )
                summaryEntry = manager.getEntry(entryId) as BranchSummaryEntry
            } else if (newLeafId == null) {
                manager.resetLeaf()
            } else {
                manager.branch(newLeafId)
            }

            agent.replaceTranscript(manager.buildSessionContext().messages)

            return NavigationResult(
                outcome = if (userMessage != null) {
                    NavigationOutcome.RE_EDIT
                } else {
                    NavigationOutcome.NAVIGATED
                },
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
                manager.appendMessage(event.message)
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
    private suspend fun checkCompaction(
        assistantMessage: AssistantMessage,
        skipAbortedCheck: Boolean = true
    ): Boolean {
        if (!settingsManager.getCompactionSettings().enabled) return false

        // Skip if message was aborted (user cancelled) - unless the pre-prompt
        // check asked for aborted messages too.
        if (skipAbortedCheck && assistantMessage.stopReason == StopReason.ABORTED) return false

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
        val compactionEntry = getLatestCompactionEntry(manager.getBranch())
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
        if (shouldCompact(contextTokens, contextWindow, settingsManager.getCompactionSettings())) {
            return runAutoCompaction(AgentEvent.CompactionReason.THRESHOLD, willRetry = false)
        }
        return false
    }

    /**
     * Execute threshold or overflow compaction (pi's _runAutoCompaction).
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
        if (models == null) return false
        val pathEntries = manager.getBranch()
        val preparation = when (
            val outcome = prepareCompaction(
                pathEntries,
                settingsManager.getCompactionSettings()
            )
        ) {
            is CompactionOutcome.Err -> return false
            is CompactionOutcome.Ok -> outcome.value ?: return false
        }
        _events.emit(AgentEvent.CompactionStart(reason))
        compactionInProgress = true
        val run = runCompactionCore(reason, willRetry, customInstructions = null, preparation)
        if (run !is CompactionRunResult.Success) {
            if (run is CompactionRunResult.Failure && !run.aborted) {
                _events.emit(
                    AgentEvent.CompactionEnd(
                        reason = reason,
                        aborted = false,
                        willRetry = false,
                        errorMessage = compactionFailureMessage(reason, run.error)
                    )
                )
            }
            return false
        }

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
                agent.setMessages(messages.dropLast(1))
            }
            return true
        }

        // pi continues for steer/follow-up messages queued during
        // compaction; there are no queues here.
        return false
    }

    private sealed interface CompactionRunResult {
        data class Success(val result: AgentEvent.CompactionResult) : CompactionRunResult

        /** [aborted] failures already had their `compaction_end` emitted; others did not. */
        data class Failure(val aborted: Boolean, val error: String?) : CompactionRunResult
    }

    /**
     * Shared compaction machinery for the automatic and manual paths (pi's
     * `_runDefaultCompaction` callers): streams the summary, appends the
     * compaction entry, rebuilds the transcript, and emits the success
     * `compaction_end`. Synchronous failures are returned unemitted so each
     * caller formats its own failure event; cancellation emits the aborted
     * `compaction_end` under [NonCancellable] and rethrows.
     */
    private suspend fun runCompactionCore(
        reason: AgentEvent.CompactionReason,
        willRetry: Boolean,
        customInstructions: String?,
        preparation: CompactionPreparation
    ): CompactionRunResult {
        val summarizationModels = models!!
        compactionInProgress = true
        try {
            val compactResult = when (
                val outcome = compact(
                    preparation,
                    summarizationModels,
                    model,
                    customInstructions = customInstructions,
                    // The summary request reasons at the level the user
                    // selected, when the summarization model supports it.
                    thinkingLevel = agent.thinkingLevel,
                    retry = summarizationRetryPolicy(),
                    callbacks = summarizationRetryCallbacks(
                        AgentEvent.SummarizationSource.Compaction(reason)
                    ),
                    clock = clock
                )
            ) {
                is CompactionOutcome.Err -> {
                    val raw = outcome.error.message ?: "compaction failed"
                    if (outcome.error.code == CompactionErrorCode.ABORTED) {
                        _events.emit(
                            AgentEvent.CompactionEnd(
                                reason = reason,
                                aborted = true,
                                willRetry = false
                            )
                        )
                        return CompactionRunResult.Failure(aborted = true, error = null)
                    }
                    return CompactionRunResult.Failure(aborted = false, error = raw)
                }

                is CompactionOutcome.Ok -> outcome.value
            }

            // Single append point: the tree either gains the compaction entry
            // or does not — an abort mid-summarization leaves it untouched.
            manager.appendCompaction(
                summary = compactResult.summary,
                firstKeptEntryId = compactResult.firstKeptEntryId,
                tokensBefore = compactResult.tokensBefore,
                details = compactResult.details,
                usage = compactResult.usage
            )
            val sessionContext = manager.buildSessionContext()
            agent.setMessages(sessionContext.messages)
            val estimatedTokensAfter = sessionContext.messages.sumOf { estimateMessageTokens(it) }

            val result = AgentEvent.CompactionResult(
                summary = compactResult.summary,
                tokensBefore = compactResult.tokensBefore,
                estimatedTokensAfter = estimatedTokensAfter,
                usage = compactResult.usage,
                details = compactResult.details
            )
            _events.emit(
                AgentEvent.CompactionEnd(
                    reason = reason,
                    result = result,
                    aborted = false,
                    willRetry = willRetry
                )
            )
            return CompactionRunResult.Success(result)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                _events.emit(
                    AgentEvent.CompactionEnd(reason = reason, aborted = true, willRetry = false)
                )
            }
            throw e
        } catch (e: Exception) {
            return CompactionRunResult.Failure(
                aborted = false,
                error =
                    e.message ?: "compaction failed"
            )
        } finally {
            compactionInProgress = false
        }
    }

    /**
     * Manually compact the session context (pi's compact: the `/compact`
     * entry point). Aborts a running prompt first; manual compaction never
     * retries or continues the interrupted agent turn.
     *
     * @throws IllegalStateException when no provider stack is configured, the
     *   branch is already compacted or too small to compact, or compaction
     *   fails — after the failure `compaction_end` is emitted.
     */
    suspend fun compact(customInstructions: String? = null): AgentEvent.CompactionResult {
        promptJob?.cancelAndJoin()
        compactionInProgress = true
        try {
            _events.emit(AgentEvent.CompactionStart(AgentEvent.CompactionReason.MANUAL))

            val preparation = try {
                if (models == null) {
                    throw IllegalStateException(formatNoModelSelectedMessage())
                }
                val pathEntries = manager.getBranch()
                when (
                    val outcome =
                        prepareCompaction(pathEntries, settingsManager.getCompactionSettings())
                ) {
                    is CompactionOutcome.Err ->
                        throw IllegalStateException(outcome.error.message ?: "compaction failed")

                    is CompactionOutcome.Ok -> outcome.value
                } ?: run {
                    val lastEntry = pathEntries.lastOrNull()
                    if (lastEntry is CompactionEntry) {
                        throw IllegalStateException("Already compacted")
                    }
                    throw IllegalStateException("Nothing to compact (session too small)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(
                    AgentEvent.CompactionEnd(
                        reason = AgentEvent.CompactionReason.MANUAL,
                        aborted = false,
                        willRetry = false,
                        errorMessage = "Compaction failed: ${e.message}"
                    )
                )
                throw e
            }

            return when (
                val run = runCompactionCore(
                    AgentEvent.CompactionReason.MANUAL,
                    willRetry = false,
                    customInstructions,
                    preparation
                )
            ) {
                is CompactionRunResult.Success -> run.result

                is CompactionRunResult.Failure -> {
                    if (!run.aborted) {
                        _events.emit(
                            AgentEvent.CompactionEnd(
                                reason = AgentEvent.CompactionReason.MANUAL,
                                aborted = false,
                                willRetry = false,
                                errorMessage = "Compaction failed: ${run.error}"
                            )
                        )
                    }
                    throw IllegalStateException(run.error ?: "Compaction cancelled")
                }
            }
        } finally {
            compactionInProgress = false
        }
    }

    /** pi's summarization call sites read the live retry settings per call. */
    private fun summarizationRetryPolicy(): RetryPolicy {
        val settings = settingsManager.getRetrySettings()
        return RetryPolicy(
            enabled = settings.enabled,
            maxRetries = settings.maxRetries,
            baseDelayMs = settings.baseDelayMs
        )
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
        message: String?
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
        val settings = settingsManager.getRetrySettings()
        if (!settings.enabled) return false

        retryAttempt++
        if (retryAttempt > settings.maxRetries) {
            // Preserve the completed attempt count so post-run handling can
            // emit the final failure.
            retryAttempt--
            return false
        }

        val delayMs = settings.baseDelayMs * (1L shl (retryAttempt - 1))

        _events.emit(
            AgentEvent.AutoRetryStart(
                attempt = retryAttempt,
                maxAttempts = settings.maxRetries,
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
