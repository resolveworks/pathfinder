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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import works.resolve.pathfinder.ai.Message
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
import works.resolve.pathfinder.ai.utils.uuidv7
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
import works.resolve.pathfinder.codingagent.core.session.LaneRecord
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.OperationIntent
import works.resolve.pathfinder.codingagent.core.session.OperationOutcome
import works.resolve.pathfinder.codingagent.core.session.RecordError
import works.resolve.pathfinder.codingagent.core.session.SessionState

/**
 * Durable sink for operation-lifecycle lane records. [AgentSession]
 * produces the lifecycle trio — operation_started, abort_requested,
 * operation_finished — and the owning app layer persists them.
 *
 * Ordering contract: implementations serialize appends in call order —
 * abort_requested precedes the cancellation handler's operation_finished —
 * but may dispatch them asynchronously (durability must not block the run
 * loop), and must not throw: a failed record append degrades durability,
 * never the run.
 */
interface OperationLifecycleRecorder {
    /** Enqueues the record, suspending only to await durability. */
    suspend fun append(record: LaneRecord)

    /** Enqueues the record without suspending (abort path). */
    fun appendBestEffort(record: LaneRecord)
}

/**
 * Prompt-orchestration facade over [Agent]: owns the session tree
 * ([Conversation]), creates user messages from prompt text, and runs the
 * agent plus the post-run continuation loop, including turn auto-retry and
 * automatic compaction.
 *
 * Layering mirrors pi: [Agent] keeps only the single-run
 * prompt/continue/abort primitives, and everything session-scoped — retry
 * counter lifetime across continues, event emission, tree persistence
 * points — lives here.
 */
class AgentSession(
    val agent: Agent,
    /** Initial session tree; adopted as-is. */
    conversation: Conversation = Conversation(emptyList(), null),
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
    /**
     * Durable operation-lifecycle recorder. Set before the first prompt;
     * null disables recording (tests, previews).
     *
     * One open operation per lane drives the
     * sequencing: the prompt's run operation spans its whole loop; an
     * embedded auto-compaction finishes the run operation first, opens a
     * compaction operation naming its pre-minted resultEntryId, and a
     * compact-and-retry continuation opens a fresh run operation.
     */
    var operationRecorder: OperationLifecycleRecorder? = null

    /** Id of the lane's open operation; null while idle. */
    @Volatile
    private var currentOperationId: String? = null

    /** The session tree; only this class appends to it during prompts. */
    var conversation: Conversation = conversation
        private set

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

    /**
     * Serializes session-tree mutations: unlike pi's single-threaded JS,
     * [setModel] may append a model_change from any coroutine while a
     * prompt's message_end handler (or an embedded compaction/navigation)
     * appends concurrently — a lost update would silently drop a tree entry.
     * Appends remain order-of-acquisition.
     */
    private val conversationMutex = Mutex()

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
        // Seed the active tool set from the branch's configuration fold, else
        // all registered tools. The fold is applied without persisting:
        // active_tools_change entries are only ever consumed here, never
        // produced. An empty registry leaves the agent untouched.
        if (tools.isNotEmpty()) {
            val foldedToolNames = conversation.effectiveConfiguration().activeToolNames
            val activeTools = resolveTools(foldedToolNames ?: tools.map { it.definition.name })
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
     * Like adoption, this is not persisted: no session entry is appended, so
     * the set is re-derived from the tree's fold on reload.
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
            beginOperation(OperationIntent.run())
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
            finishOperation(OperationOutcome.COMPLETED)
        } catch (e: CancellationException) {
            finishOperation(OperationOutcome.ABORTED)
            throw e
        } catch (e: Exception) {
            finishOperation(
                OperationOutcome.FAILED,
                RecordError(
                    code = e::class.simpleName ?: "error",
                    message = e.message ?: "operation failed"
                )
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
                LaneRecord.AbortRequestedRecord(
                    id = uuidv7(),
                    lane = SessionState.LANE_MAIN,
                    runId = operationId
                )
            )
        }
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
        updateConversation { it.appendModelChange(model.provider, model.id) }
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
            updateConversation { it.appendThinkingLevelChange(effective.wire) }
        }
    }

    /**
     * Replace the session tree and rebuild the agent transcript from the new
     * active path. Only valid while idle; the tree is append-only, so this
     * is the sole reparenting entry point.
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
     * session (unlike fork). Wraps the navigation in a durable operation and
     * is idle-only, honoring the single-open-operation-per-lane invariant;
     * abort is coroutine cancellation.
     *
     * A user-message target re-edits instead of moving the leaf onto it: the
     * leaf moves to the target's parent (or root) and the text is returned
     * as [NavigationResult.editorText]. When summarizing, the
     * [BranchSummaryEntry] is appended at the navigation target position
     * with fromId set to the abandoned leaf, and the rebuilt context
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
        if (targetId == oldLeafId) {
            return NavigationResult(cancelled = false)
        }

        val summarizationModels = models
        if (options.summarize && summarizationModels == null) {
            throw IllegalStateException("No model available for summarization")
        }

        val targetEntry = conversation.entry(targetId)
            ?: throw IllegalArgumentException("Entry $targetId not found")

        // Entries to summarize: from the old leaf to the common ancestor.
        val collected = collectEntriesForBranchSummary(conversation, oldLeafId, targetId)

        // Minted up front so the navigation intent can name its summaryEntryId.
        val summaryEntryId = uuidv7().takeIf { options.summarize }
        beginOperation(
            navigationIntent(
                targetId = targetId,
                summarize = options.summarize,
                customInstructions = options.customInstructions,
                summaryEntryId = summaryEntryId
            )
        )
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
                            finishOperation(OperationOutcome.ABORTED)
                            return NavigationResult(cancelled = true, aborted = true)
                        }
                        throw outcome.error
                    }

                    is BranchSummaryCallResult.Ok -> summary = outcome.value
                }
            }

            val userMessage = (targetEntry as? MessageEntry)?.message as? UserMessage
            val newLeafId: String? = if (userMessage != null) targetEntry.parentId else targetId
            val editorText = userMessage
                ?.content
                ?.filterIsInstance<TextContent>()
                ?.joinToString("") { it.text }

            var summaryEntry: BranchSummaryEntry? = null
            if (summary != null) {
                val entry = BranchSummaryEntry(
                    id = summaryEntryId!!,
                    parentId = newLeafId,
                    timestamp = clock.now().toEpochMilliseconds(),
                    fromId = oldLeafId ?: "root",
                    summary = summary.summary,
                    details = buildJsonObject {
                        put("readFiles", JsonArray(summary.readFiles.map(::JsonPrimitive)))
                        put("modifiedFiles", JsonArray(summary.modifiedFiles.map(::JsonPrimitive)))
                    },
                    usage = summary.usage
                )
                updateConversation { Conversation(it.entries + entry, entry.id) }
                summaryEntry = entry
            } else if (newLeafId == null) {
                updateConversation { it.resetLeaf() }
            } else {
                updateConversation { it.branch(newLeafId) }
            }

            agent.replaceTranscript(buildSessionContext(conversation.activeEntries()))

            finishOperation(OperationOutcome.COMPLETED)
            return NavigationResult(
                editorText = editorText,
                cancelled = false,
                summaryEntry = summaryEntry
            )
        } catch (e: CancellationException) {
            finishOperation(OperationOutcome.ABORTED)
            throw e
        } catch (e: Exception) {
            finishOperation(
                OperationOutcome.FAILED,
                RecordError(
                    code = e::class.simpleName ?: "error",
                    message = e.message ?: "navigation failed"
                )
            )
            throw e
        }
    }

    /** The navigation operation intent payload. No label field: no surface here produces entry labels. */
    private fun navigationIntent(
        targetId: String,
        summarize: Boolean,
        customInstructions: String?,
        summaryEntryId: String?
    ): OperationIntent = OperationIntent(
        kind = OperationIntent.Kind.NAVIGATION,
        payload = buildJsonObject {
            put("kind", "navigation")
            put("targetId", targetId)
            put("summarize", summarize)
            customInstructions?.let { put("customInstructions", it) }
            summaryEntryId?.let { put("summaryEntryId", it) }
        }
    )

    // ---- operation lifecycle records ----

    /**
     * Opens the lane's operation: appends operation_started with the current
     * leaf as sourceLeafId (which may name an entry still buffered,
     * unpersisted — see [LaneRecord]).
     */
    private suspend fun beginOperation(intent: OperationIntent): String {
        val id = uuidv7()
        recordAppend(
            LaneRecord.OperationStartedRecord(
                id = id,
                lane = SessionState.LANE_MAIN,
                sourceLeafId = conversation.leafId,
                intent = intent
            )
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
                    error = error
                )
            )
        }
    }

    private suspend fun recordAppend(record: LaneRecord) {
        operationRecorder?.append(record)
    }

    /** Mutate the session tree under [conversationMutex] (see its KDoc). */
    private suspend fun updateConversation(transform: (Conversation) -> Conversation) {
        conversationMutex.withLock { conversation = transform(conversation) }
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
                // overflow recovery) stay in history.
                updateConversation { it.append(event.message) }
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
            // The triggering run's operation ends before compaction opens
            // its own (one open operation per lane).
            finishOperation(OperationOutcome.COMPLETED)
            val pathEntries = conversation.activeEntries()
            val preparation = when (
                val outcome = prepareCompaction(pathEntries, compactionSettings)
            ) {
                is CompactionOutcome.Err -> return false
                is CompactionOutcome.Ok -> outcome.value ?: return false
            }

            // Minted up front so the operation record can name its resultEntryId.
            val resultEntryId = uuidv7()
            beginOperation(OperationIntent.compaction(resultEntryId))

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
                        finishOperation(OperationOutcome.ABORTED)
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
                    finishOperation(
                        OperationOutcome.FAILED,
                        RecordError(
                            code = outcome.error.code.name,
                            message = outcome.error.message ?: "compaction failed"
                        )
                    )
                    return false
                }

                is CompactionOutcome.Ok -> outcome.value
            }

            // Single append point: the tree either gains the compaction entry
            // or does not — an abort mid-summarization leaves it untouched.
            updateConversation {
                it.appendCompaction(
                    summary = compactResult.summary,
                    retainedTail = compactResult.retainedTail,
                    tokensBefore = compactResult.tokensBefore,
                    details = compactResult.details,
                    usage = compactResult.usage,
                    id = resultEntryId
                )
            }
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

            finishOperation(OperationOutcome.COMPLETED)

            if (willRetry) {
                // The overflow retry continues as a fresh run operation.
                beginOperation(OperationIntent.run())
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
            finishOperation(OperationOutcome.ABORTED)
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
            finishOperation(
                OperationOutcome.FAILED,
                RecordError(
                    code = e::class.simpleName ?: "error",
                    message =
                        e.message ?: "compaction failed"
                )
            )
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
