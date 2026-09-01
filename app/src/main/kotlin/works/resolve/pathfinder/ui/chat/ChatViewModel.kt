package works.resolve.pathfinder.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentSession
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.providers.AuthPrompt
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.agent.AgentFactory
import works.resolve.pathfinder.agent.LaneReductionInput
import works.resolve.pathfinder.agent.LaneRecovery
import works.resolve.pathfinder.agent.OperationLifecycleRecorder
import works.resolve.pathfinder.agent.RecordLogCorruption
import works.resolve.pathfinder.agent.RecordLogCorruptionReason
import works.resolve.pathfinder.agent.reduceLaneState
import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt as AuthInteractionPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.auth.oauth.AppForegroundGate
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.data.sessions.CompactionEntry
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.LaneRecord
import works.resolve.pathfinder.data.sessions.RecordQuery
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.Session
import works.resolve.pathfinder.data.sessions.SessionEntry
import works.resolve.pathfinder.data.sessions.SessionState
import works.resolve.pathfinder.data.sessions.SessionRepository
import works.resolve.pathfinder.data.sessions.SessionSummary
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_CONTEXT
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.TelemetryContext
import works.resolve.pathfinder.telemetry.attr
import works.resolve.pathfinder.telemetry.automaticErrorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat screen controller. Owns configuration, sessions, and the active
 * [Agent]; projects everything into an immutable [ChatUiState] (UDF).
 *
 * The provider/model pair is user-selectable from the injected generated
 * catalog; credentials are managed per provider (pi's /login semantics:
 * one credential per provider, removed per provider), while model settings
 * persist the selected provider+model+base-URL override.
 *
 * Provider auth status (`configured`/`unconfigured`) is derived live from
 * the credential store — never persisted in settings — and the model picker
 * only lists models of configured providers (pi's model-selector rule),
 * narrowed per provider by pi's credential-based `filterModels` (GitHub
 * Copilot's `availableModelIds` extra; pi's getAvailable).
 * A persisted selection that the credential's filtered set no longer
 * contains is never treated as runnable: initialization projects a
 * re-selection state (forced model-settings step plus a safe error),
 * matching pi dropping the model from getAvailable.
 *
 * The agent itself is created through the injected [AgentFactory]
 * (see [works.resolve.pathfinder.agent]); the production implementation wires the native
 * Z.AI runtime. The factory returns the [AgentSession] facade (pi's
 * agent-session), which owns the session tree, the retry budget, and (in
 * later waves) compaction; this ViewModel only projects its state/events
 * and persists tree snapshots.
 *
 * Transcript persistence runs through a single latest-snapshot pipeline: at
 * most one save per session is in flight, superseded snapshots are coalesced,
 * and session switches wait for pending saves so transcripts always stay with
 * the session they belong to. The persisted unit is the conversation tree
 * itself (entries + leafId), so branch structure survives saves; snapshots
 * are taken from the immutable [Conversation] the ViewModel owns alongside
 * the active session. Saves are append-only (pi's JSONL v4 mutation log,
 * P0-2): [SessionRepository.save] syncs the snapshot by appending the
 * entries not yet in the log — a lane mutation first when the tree branched
 * — then the lane's move to the snapshot leaf and the title fact, so
 * re-syncing a snapshot is a no-op and a partially-failed save can simply
 * be retried. A failed save surfaces an error and blocks
 * session/config switches; the blocked intent explicitly retries the latest
 * snapshot and only proceeds once it is saved, so an unsaved transcript is
 * never silently abandoned. Snapshot writes themselves are non-cancellable
 * and the save loop drains whatever it accepted, so ViewModel teardown can
 * never abandon an accepted snapshot either.
 *
 * Tree navigation (pi's navigateTree, reduced to no summarization) is a
 * state change on the same conversation: navigating to an assistant entry
 * moves the leaf there (the active path becomes root..that entry);
 * navigating to a user entry implements re-edit semantics — the leaf moves
 * to the entry's parent (or resets to root) and the message text lands in
 * the draft, so the next send appends a sibling of the original message.
 *
 * Navigation is state, not effects: an unconfigured app pins
 * [ChatUiState.startKey] to [ProvidersNavKey] (first-run step 1: pick a
 * provider and sign in), or directly to [ModelSettingsNavKey] when a
 * complete provider credential already exists (restoration); after a
 * credential save that does not complete configuration it moves to
 * [ModelSettingsNavKey] with an epoch bump (step 2: pick a model —
 * configured models are immediately selectable). Every
 * intent that should return the user to the chat (adopting a session, saving
 * configuration) sets [ChatUiState.startKey] to [ChatNavKey] and bumps
 * [ChatUiState.navigationEpoch] atomically with the rest of the state.
 * The UI layer owns the Nav3 back stack and resets it to
 * [ChatUiState.startKey] whenever either field changes, so the forced
 * first-run steps are single-entry dead ends until configuration completes.
 */
class ChatViewModel(
    private val settingsRepository: SettingsStore,
    private val catalog: ProviderCatalog,
    private val authService: ProviderAuthService,
    private val sessionStore: SessionRepository,
    private val agentFactory: AgentFactory,
    /** Diagnostic span backend for the UI error boundary (pi threads TelemetryContext the same way). */
    private val telemetryContext: TelemetryContext = NOOP_TELEMETRY_CONTEXT,
    /**
     * Process-wide foreground state (Android platform glue; pi has no
     * foreground concept). MainActivity's onResume/onPause drive this via
     * [onAppForegrounded]/[onAppBackgrounded]; the OAuth flows gate loopback
     * waits and network work on it while the app is backgrounded.
     */
    private val appForegroundGate: AppForegroundGate = AppForegroundGate(),
) : ViewModel() {

    // Catalog-driven provider/model surface: option lists are computed from
    // live credential state (see refreshOptions).
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Current committed configuration; updated on init and successful save. */
    private var currentSettings: ModelSettings = ModelSettings()

    private var agent: AgentSession? = null
    private var agentStateJob: Job? = null
    private var agentEventsJob: Job? = null
    private var activeSession: Session? = null

    /**
     * The conversation tree of [activeSession]: the transcript source of
     * truth for persistence and tree navigation. Owned by the bound
     * [AgentSession] (pi's agent-session owns the session manager); this
     * ViewModel reads it for projection and persistence, and navigates it
     * through [AgentSession.replaceConversation].
     */
    private val activeConversation: Conversation
        get() = agent?.conversation ?: Conversation(emptyList(), null)

    /**
     * Watermark of active-session entries already appended to the session's
     * mutation log (pi's seq watermark equivalent: entries are append-only
     * and consume one consecutive seq each, so the persisted-entry count is
     * the log's entry frontier). Tree growth beyond it schedules a persist;
     * leaf-only moves (navigation) persist without moving it.
     */
    private var persistedEntryCount: Int = 0

    /** Latest unsaved conversation snapshot for its owning session. */
    private var pendingPersist: Pair<Session, Conversation>? = null
    private var persistJob: Job? = null

    /** Agent-sourced error last projected into the UI, to detect agent-side clearing. */
    private var lastAgentError: String? = null

    init {
        viewModelScope.launch { initialize() }
    }

    // ---- intents ----

    fun onDraftChange(text: String) {
        updateState { it.copy(draft = text) }
    }

    /** Clears the surfaced error without touching anything else. */
    fun dismissError() {
        updateState { it.copy(error = null) }
    }

    /**
     * Persists the selected provider+model and (re)builds the agent.
     * Requires a stored credential for [providerId].
     */
    fun saveModelSelection(providerId: String, modelId: String) {
        viewModelScope.launch { saveModelSelectionInternal(providerId, modelId) }
    }

    /**
     * Saves a fresh credential for [providerId] (pi's /login semantics):
     * every prompt's input is its value and a complete save replaces the
     * stored credential wholesale — pi's logins (e.g. `envApiKeyAuth`,
     * `cloudflareAIGatewayAuth`) re-prompt everything and never merge with
     * stored values. Blank/missing required values are rejected with an
     * error naming the missing prompts.
     *
     * Note the deliberate divergence from pi's
     * `completeProviderAuthentication`, which also auto-selects a default
     * model after login: pathfinder instead forces the model picker step.
     *
     * Not busy-rejected: the agent resolves the credential once per request
     * (inside its stream flow), so changing it mid-stream is safe — like pi's
     * mid-conversation /login — and only affects the next request.
     */
    fun saveProviderCredential(providerId: String, apiKeyInput: String, envInputs: Map<String, String>) {
        viewModelScope.launch { saveProviderCredentialInternal(providerId, apiKeyInput, envInputs) }
    }

    /**
     * Forgets the credential for [providerId]. Never tears down sessions or
     * the agent (credentials are read per request); only the derived status
     * surfaces (provider/model options and `configured`) are refreshed.
     *
     * Not busy-rejected: safe mid-stream for the same reason as
     * [saveProviderCredential] — credentials are read per request.
     */
    fun removeProviderCredential(providerId: String) {
        viewModelScope.launch {
            try {
                authService.logout(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_CREDENTIAL_SAVE, e)
                return@launch
            }
            refreshOptions()
        }
    }

    /**
     * Re-reads credentials and recomputes the derived provider/model surfaces.
     *
     * Not busy-rejected: credentials are read per request by the agent, so
     * this cannot race a stream into an inconsistent state.
     */
    fun refreshProviderStatus() {
        viewModelScope.launch {
            try {
                refreshOptions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_CREDENTIAL_SAVE, e)
            }
        }
    }

    /**
     * UI-safe auth prompts for a provider's credential form, in catalog order:
     * the first prompt is the API key (secret); later prompts fill env slots.
     * Only envKey/message/secret cross the boundary — never stored values.
     */
    fun providerAuthPrompts(providerId: String): List<ProviderAuthPrompt> =
        catalog.getProvider(providerId)
            ?.auth
            ?.prompts
            ?.map { ProviderAuthPrompt(it.envKey, it.message, it.secret) }
            .orEmpty()

    /** Persists the show-thinking display preference; safe mid-stream (display-only). */
    fun setShowThinking(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setShowThinking(enabled)
                currentSettings = currentSettings.copy(showThinking = enabled)
                updateState { it.copy(showThinking = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SETTINGS_SAVE, e)
            }
        }
    }

    fun send() {
        viewModelScope.launch { sendInternal() }
    }

    fun stop() {
        agent?.abort()
    }

    /**
     * Navigates the conversation tree to [id] (pi's navigateTree). 
     * Busy-rejected while streaming; selecting the current leaf is a no-op
     * error; unknown ids are a safe error. A user message target re-edits:
     * the leaf moves to its parent (or resets to root) and its text is
     * restored into the draft, so the next send forks as a sibling. Any
     * other target moves the leaf to that entry.
     */
    fun navigateToTreeEntry(id: String) {
        navigateToTreeEntry(id, summarize = false)
    }

    /**
     * Navigation with branch summarization: when [summarize] is set (pi's
     * tree-selector "Summarize" choice), the abandoned branch segment is
     * summarized and a branch-summary entry is appended at the target
     * position, both wrapped in a durable navigation operation record
     * (see [AgentSession.navigateTree]).
     */
    fun navigateToTreeEntry(id: String, summarize: Boolean) {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            if (id == activeConversation.leafId) {
                setError(ERROR_ALREADY_AT_POINT)
                return@launch
            }
            val session = agent ?: return@launch
            val result = try {
                session.navigateTree(id, AgentSession.NavigateTreeOptions(summarize = summarize))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                setError(ERROR_BUSY)
                return@launch
            } catch (e: IllegalArgumentException) {
                setError(ERROR_ENTRY_MISSING)
                return@launch
            }
            if (result.cancelled) return@launch
            val updated = session.conversation
            // pi's reducer folds branch configuration root→leaf; the agent
            // runs on the folded model. Divergence: an agent rebuild only
            // happens when the fold changes the model (pi swaps state
            // in-place on the live agent, which pathfinder's factory-built
            // agents do not support), and unknown folded models keep the
            // running agent rather than failing navigation.
            val seeded = settingsSeededFromFold(currentSettings, updated)
            val active: AgentSession = if (
                session.model.provider == seeded.providerId && session.model.id == seeded.modelId
            ) {
                session
            } else {
                val newAgent = tryCreateAgent(seeded, activeSession!!.id, updated) ?: return@launch
                bindAgent(newAgent)
                newAgent
            }
            updateState {
                it.copy(
                    // pi's navigateTree loads the re-edit text into the
                    // editor only when it is empty; a typed draft is never
                    // clobbered by navigation.
                    draft = if (it.draft.isBlank()) result.editorText ?: it.draft else it.draft,
                    messages = projectCommitted(active.agent.state.value.messages, updated),
                    treeRows = buildTreeRows(updated, it.treeFilter),
                )
            }
            enqueuePersist()
        }
    }

    /** Switches the tree-panel filter (in-memory only) and re-projects the rows. */
    fun setTreeFilter(filter: TreeFilter) {
        updateState {
            it.copy(treeFilter = filter, treeRows = buildTreeRows(activeConversation, filter))
        }
    }

    fun newSession() {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            try {
                if (!awaitPersistence()) {
                    setError(ERROR_SESSION_SAVE)
                    return@launch
                }
                val session = sessionStore.create(DEFAULT_SESSION_TITLE)
                // pi seeds every new session with the initial model selection
                // (coding-agent sdk.ts: appendModelChange before first use),
                // so the branch's configuration fold restores it on resume.
                val conversation = Conversation(emptyList(), null)
                    .appendModelChange(currentSettings.providerId, currentSettings.modelId)
                val newAgent = tryCreateAgent(currentSettings, session.id, conversation) ?: return@launch
                if (!activateSession(session, newAgent)) return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_CREATE, e)
            }
        }
    }

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            try {
                val session = sessionStore.load(sessionId)
                if (session == null) {
                    setError(ERROR_SESSION_MISSING)
                } else {
                    if (!awaitPersistence()) {
                        setError(ERROR_SESSION_SAVE)
                        return@launch
                    }
                    val conversation = Conversation(session.entries, session.leafId)
                    val newAgent = tryCreateAgent(
                        settingsSeededFromFold(currentSettings, conversation),
                        session.id,
                        conversation,
                    ) ?: return@launch
                    if (!activateSession(session, newAgent)) return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_LOAD, e)
            }
        }
    }

    // ---- initialization ----

    private suspend fun initialize() {
        try {
            val settings = settingsRepository.currentSettings()
            val summaries = sessionStore.summaries()

            if (!isConfigured(settings)) {
                currentSettings = settings
                refreshOptions()
                // First-run vs restoration: when at least one provider
                // credential is already complete, the model form is the
                // useful forced root (the user can pick a model right away);
                // only a fresh install forces the providers step first.
                val hasConfiguredProvider = _uiState.value.modelOptions.isNotEmpty()
                // Re-selection projection: the persisted model exists in the
                // static provider catalog but is not in its credential-filtered
                // set (pi's getAvailable would drop it) — surface a safe error
                // instead of running or sending with it; the forced
                // model-settings step collects the replacement. A corrupt or
                // unknown model id is NOT "no longer available for this
                // account": no error is added for it.
                val providerConfigured = try {
                    catalog.getProvider(settings.providerId)
                        ?.model(settings.modelId) != null &&
                        authService.isConfigured(settings.providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recordDegradation("init_provider_configured", e)
                    false
                }
                updateState {
                    it.copy(
                        status = ChatStatus.NeedsConfiguration,
                        startKey = if (hasConfiguredProvider) ModelSettingsNavKey else ProvidersNavKey,
                        showThinking = settings.showThinking,
                        sessionSummaries = summaries,
                        error = if (providerConfigured) ERROR_MODEL_UNAVAILABLE else it.error,
                    )
                }
                return
            }

            val session = resolveSession(settings, summaries)
            // Build the agent before committing any state: a factory failure
            // must never leave a Ready UI or persisted active-session id.
            val (seeded, conversation) = seededSettingsFor(session, settings)
            val newAgent = tryCreateAgent(
                seeded,
                session.id,
                conversation,
            )
            if (newAgent == null) {
                updateState {
                    it.copy(
                        status = ChatStatus.Failed,
                        sessionSummaries = summaries,
                        error = ERROR_CONFIG_INVALID,
                    )
                }
                return
            }
            currentSettings = settings
            if (!activateSession(session, newAgent)) {
                // The active-id write failed: a safe settings error is already
                // surfaced; never report Ready with nothing bound.
                updateState { it.copy(status = ChatStatus.Failed) }
                return
            }
            // activateSession already projected the session and fresh
            // summaries; only the configuration surfaces remain.
            refreshOptions()
            updateState {
                it.copy(
                    status = ChatStatus.Ready,
                    showThinking = settings.showThinking,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_INIT, e)
            updateState { it.copy(status = ChatStatus.Failed) }
        }
    }

    /** Requested active session, else the newest existing, else a new one. */
    private suspend fun resolveSession(settings: ModelSettings, summaries: List<SessionSummary>): Session {
        settings.activeSessionId?.let { id ->
            sessionStore.load(id)?.let { return it }
        }
        summaries.firstOrNull()?.let { summary ->
            sessionStore.load(summary.id)?.let { return it }
        }
        return sessionStore.create(DEFAULT_SESSION_TITLE)
    }

    // ---- session / agent lifecycle ----

    /**
     * Makes [session] active with a prebuilt [agent]: persists the active id,
     * binds the agent, and returns to the chat surface with a refreshed UI.
     * Only called after the factory accepted the settings. Returns false when
     * persisting the active id fails; in that case nothing is committed.
     */
    private suspend fun activateSession(session: Session, agent: AgentSession): Boolean {
        try {
            settingsRepository.setActiveSessionId(session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SETTINGS_SAVE, e)
            return false
        }
        // Commit the session before binding: state collection starts
        // immediately, and the new agent's transcript must never be observed
        // against the previous session (which could cross-write saves).
        activeSession = session
        persistedEntryCount = session.entries.size
        bindAgent(agent)
        val conversation = agent.conversation
        val summaries = sessionStore.summaries()
        // Load-time lane recovery (pi's findOpenOperations limit-2 contract,
        // reduced by the full reducer — see [laneRecoveryFor]): an
        // interrupted run stays distinguishable from a finished one, and
        // record-log corruption is classified rather than silently resumed.
        val laneRecovery = laneRecoveryFor(session)
        updateState {
            it.copy(
                activeSessionId = session.id,
                startKey = ChatNavKey,
                navigationEpoch = it.navigationEpoch + 1,
                messages = projectCommitted(agent.state.value.messages, conversation),
                streamingMessage = null,
                treeRows = buildTreeRows(conversation, it.treeFilter),
                sessionSummaries = summaries,
                laneRecovery = laneRecovery,
            )
        }
        // Initial-configuration seeding (pi's sdk.ts new-session path) may
        // have appended a model_change to an entry-less session; flush it
        // like any other appended entry. Loaded sessions carry no new
        // entries, so this never saves on a plain load or switch.
        if (activeConversation.entries.size > persistedEntryCount) enqueuePersist()
        return true
    }

    /**
     * Restores the main lane's recovery classification by running the full
     * reducer over the session's durable record log (pi's restore contract:
     * [reduceLaneState] validating the lane's recovery slice, seeded by
     * findOpenOperations' `limit: 2` contract). The slice is the whole
     * main-lane record log plus the persisted entries — Pathfinder is
     * single-lane and sessions are small, so the bounded slice upstream's
     * caller would assemble is the entire lane. Validation failures map to
     * [LaneRecovery.Corrupt]; a failing read degrades to
     * [LaneRecovery.Idle] with telemetry — classification is advisory UI
     * state, never a load blocker.
     */
    private suspend fun laneRecoveryFor(session: Session): LaneRecovery = try {
        val openOperations = sessionStore.openOperations(session.id, SessionState.LANE_MAIN, limit = 2)
        if (openOperations.size > 1) {
            LaneRecovery.Corrupt(RecordLogCorruptionReason.MULTIPLE_OPEN_OPERATIONS)
        } else {
            val started = openOperations.singleOrNull()
            val result = reduceLaneState(
                LaneReductionInput(
                    lane = SessionState.LANE_MAIN,
                    openOperations = openOperations,
                    records = sessionStore.findRecords(session.id, RecordQuery(lane = SessionState.LANE_MAIN)),
                    // Operation-owned entries: everything appended after the
                    // open operation's start (single writer, single lane).
                    ownEntries = started?.let { op -> session.entries.filter { it.seq > op.seq } } ?: emptyList(),
                    entries = session.entries,
                    // Bounded configuration lookups at the operation anchor
                    // (sourceLeafId) or the idle leaf, oldest first.
                    configurationEntries = configurationEntriesFor(session, started?.sourceLeafId),
                    leafId = session.leafId,
                ),
            )
            val operation = result.laneState.operation
            if (operation == null) LaneRecovery.Idle else LaneRecovery.Suspended(operation.kind)
        }
    } catch (e: RecordLogCorruption) {
        LaneRecovery.Corrupt(e.reason)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        recordDegradation("lane_recovery", e)
        LaneRecovery.Idle
    }

    /**
     * The anchor's root path, oldest first; falls back to the persisted leaf
     * when the anchor is an unpersisted buffered entry (legal per pi's
     * record-log invariants — records may precede the entries they name).
     */
    private fun configurationEntriesFor(session: Session, anchorId: String?): List<SessionEntry> {
        var cursor: String? = anchorId?.takeIf { id -> session.entries.any { it.id == id } } ?: session.leafId ?: return emptyList()
        val byId = session.entries.associateBy { it.id }
        val path = ArrayList<SessionEntry>()
        while (cursor != null && byId.containsKey(cursor)) {
            val entry = byId.getValue(cursor)
            path.add(entry)
            cursor = entry.parentId
        }
        return path.asReversed()
    }

    /**
     * Resolves the session and builds an agent for it: validation happens
     * before anything is committed. Returns null on failure (safe error set).
     */
    private suspend fun prepareAdoption(settings: ModelSettings): Pair<Session, AgentSession>? {
        val session = try {
            resolveSession(settings, sessionStore.summaries())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SESSION_CREATE, e)
            return null
        }
        val (seeded, conversation) = seededSettingsFor(session, settings)
        val newAgent = tryCreateAgent(
            seeded,
            session.id,
            conversation,
        ) ?: return null
        return session to newAgent
    }

    /**
     * Adopts [session] with fold-seeded settings: the active branch's
     * configuration fold ([Conversation.effectiveConfiguration], pi's
     * deriveSessionContextState) overrides the provider/model when it
     * recorded one; new (entry-less) sessions are seeded with the initial
     * model selection (pi's sdk.ts new-session path) so the fold can
     * restore it on resume. Non-empty sessions record nothing on load,
     * matching pi (which only appends a missing thinking level, a concept
     * pathfinder has no selection point for).
     */
    private fun seededSettingsFor(session: Session, settings: ModelSettings): Pair<ModelSettings, Conversation> {
        var conversation = Conversation(session.entries, session.leafId)
        if (conversation.entries.isEmpty() && settings.providerId.isNotBlank() && settings.modelId.isNotBlank()) {
            conversation = conversation.appendModelChange(settings.providerId, settings.modelId)
        }
        return settingsSeededFromFold(settings, conversation) to conversation
    }

    /**
     * Seeds the provider/model from the conversation's configuration fold
     * (pi's deriveSessionContextState / reducer deriveEffectiveConfiguration):
     * a branch that recorded a different model via model_change or its
     * assistant messages runs on that model, overriding the global
     * defaults. Divergence: pi resolves any recorded pair, while pathfinder's
     * agent factory only builds generated-catalog models, so a pair missing
     * from the catalog (or with an unsupported API) falls back to [settings].
     */
    private fun settingsSeededFromFold(settings: ModelSettings, conversation: Conversation): ModelSettings {
        val model = conversation.effectiveConfiguration().model ?: return settings
        if (model.provider == settings.providerId && model.modelId == settings.modelId) return settings
        val catalogModel = catalog.getProvider(model.provider)?.model(model.modelId)
            ?: return settings
        return if (ChatApiRegistry.isSupported(catalogModel.api)) {
            settings.copy(providerId = model.provider, modelId = model.modelId)
        } else {
            settings
        }
    }

    /** Builds an agent or null (with a safe error surfaced) when the factory rejects the settings. */
    private fun tryCreateAgent(
        settings: ModelSettings,
        sessionId: String,
        conversation: Conversation,
    ): AgentSession? =
        try {
            agentFactory.create(settings, sessionId, conversation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CONFIG_INVALID, e)
            null
        }

    private fun bindAgent(newAgent: AgentSession) {
        agentStateJob?.cancel()
        agentEventsJob?.cancel()
        agent = newAgent
        lastAgentError = null
        // Durable operation-lifecycle records (P0-3): the recorder resolves
        // the session at call time (mid-run session switches are blocked).
        newAgent.operationRecorder = operationRecorder
        agentStateJob = viewModelScope.launch { newAgent.state.collect { state -> onAgentState(state) } }
        // Session-level status (retry, and later compaction) and persistence
        // points are event-driven (pi's auto_retry_start/end and message_end
        // persistence reach the UI as agent-session events); zero-replay
        // flow, so the subscriber must be bound before any prompt starts.
        agentEventsJob = viewModelScope.launch { newAgent.events.collect { event -> onAgentEvent(event) } }
    }

    /** Projects session lifecycle events into transient UI surfaces and persistence. */
    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AutoRetryStart -> updateState {
                it.copy(retryStatus = AutoRetryStatus(event.attempt, event.maxAttempts))
            }
            is AgentEvent.AutoRetryEnd -> updateState { it.copy(retryStatus = null) }
            is AgentEvent.CompactionStart -> updateState { it.copy(isCompacting = true) }
            is AgentEvent.CompactionEnd -> {
                updateState { it.copy(isCompacting = false, treeRows = buildTreeRows(activeConversation, it.treeFilter)) }
                // The compaction entry was appended to the tree before the
                // end event; persist the grown tree.
                if (activeConversation.entries.size > persistedEntryCount) {
                    enqueuePersist()
                }
            }
            // Summarization-retry telemetry is not surfaced in the UI yet
            // (pi shows it in the spinner tooltip).
            is AgentEvent.SummarizationRetryScheduled,
            is AgentEvent.SummarizationRetryAttemptStart,
            is AgentEvent.SummarizationRetryFinished,
            -> Unit
            // The session tree appends on message_end (AgentSession, pi's
            // sessionManager.appendMessage); re-project rows and persist on
            // tree growth, not agent-transcript growth — an auto-retry or
            // overflow recovery removes the error message from agent state
            // while the append-only tree keeps it.
            is AgentEvent.MessageEnd -> {
                updateState { it.copy(treeRows = buildTreeRows(activeConversation, it.treeFilter)) }
                if (activeConversation.entries.size > persistedEntryCount) {
                    enqueuePersist()
                }
            }
            else -> Unit
        }
    }

    private fun onAgentState(state: AgentState) {
        val agentError = state.errorMessage
        updateState {
            it.copy(
                messages = projectCommitted(state.messages, activeConversation),
                streamingMessage = (state.streamingMessage as? AssistantMessage)?.let(::projectStreaming),
                isStreaming = state.isStreaming,
                error = agentError ?: it.error?.takeIf { e -> e != lastAgentError },
            )
        }
        lastAgentError = agentError
    }

    // ---- persistence pipeline ----

    /**
     * Bridge from [AgentSession]'s operation lifecycle to the session's
     * mutation log (pi's Session.appendRecord; audit P0-3 producers).
     *
     * Appends dispatch asynchronously onto [viewModelScope] (Main.immediate)
     * in call order and serialize through the store's mutex: the run loop
     * never blocks on record durability, and abort_requested still lands
     * before the cancellation handler's operation_finished (the recorder
     * call order). Record appends never flush buffered conversation entries
     * — pi's log invariant permits records to precede the entries they
     * reference (see [LaneRecord]). A failed append degrades durability
     * only: the error surfaces, the run continues.
     */
    private val operationRecorder = object : OperationLifecycleRecorder {
        override suspend fun append(record: LaneRecord) {
            dispatchAppend(record)
        }

        override fun appendBestEffort(record: LaneRecord) {
            dispatchAppend(record)
        }

        private fun dispatchAppend(record: LaneRecord) {
            val sessionId = activeSession?.id ?: return
            viewModelScope.launch {
                try {
                    sessionStore.appendRecord(sessionId, record)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setError(ERROR_SESSION_SAVE, e)
                }
            }
        }
    }

    /**
     * Schedules the current conversation snapshot for persistence against
     * the active session. At most one save runs at a time; while a save is
     * in flight, newer snapshots coalesce (only the latest is written).
     */
    private fun enqueuePersist() {
        val session = activeSession ?: return
        pendingPersist = session to activeConversation
        if (persistJob?.isActive == true) return
        persistJob = viewModelScope.launch {
            // [persistSnapshot] is non-cancellable, so once a snapshot is
            // dequeued its write always completes; on scope teardown the loop
            // keeps draining until no accepted snapshot remains (new enqueues
            // stop with the cancelled state collector) and then exits.
            while (true) {
                val next = pendingPersist ?: break
                pendingPersist = null
                persistSnapshot(next.first, next.second)
            }
        }
    }

    /**
     * Writes one snapshot. The append-only sync always reaches the file (the
     * transcript stays with its session even if the user switched away
     * meanwhile); UI/active state is only updated when that session is still
     * active.
     */
    private suspend fun persistSnapshot(session: Session, conversation: Conversation) {
        // Non-cancellable: an accepted snapshot must reach the file even when
        // the ViewModel scope is torn down mid-write; without this, cancelling
        // the save coroutine between dequeue and write would silently drop the
        // transcript. UI bookkeeping still only targets a still-active session.
        withContext(NonCancellable) {
            try {
                val activeMessages = conversation.activeMessages()
                val title = if (session.title == DEFAULT_SESSION_TITLE) {
                    deriveTitle(activeMessages) ?: DEFAULT_SESSION_TITLE
                } else {
                    session.title
                }
                // Persist the tree itself (entry appends + lane move): branch
                // structure survives saves via the mutation log; withMessages
                // stays a flat-transcript bridge only.
                val saved = sessionStore.save(
                    session.copy(entries = conversation.entries, leafId = conversation.leafId, title = title),
                )
                if (activeSession?.id == session.id) {
                    activeSession = saved
                    persistedEntryCount = saved.entries.size
                }
                val summaries = sessionStore.summaries()
                if (activeSession?.id == session.id || _uiState.value.activeSessionId == session.id) {
                    updateState { it.copy(sessionSummaries = summaries) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_SAVE, e)
            }
        }
    }

    /**
     * Ensures the latest conversation of the active session is fully saved,
     * retrying once from the current tree if a previous save failed.
     * Returns false when the latest snapshot remains unsaved: callers must
     * then keep the current session (and surface the save error) so an
     * unsaved transcript is never abandoned.
     */
    private suspend fun awaitPersistence(): Boolean {
        retryUnsavedSnapshot()
        persistJob?.join()
        return pendingPersist == null &&
            activeConversation.entries.size <= persistedEntryCount
    }

    /** Explicitly re-enqueues the latest conversation tree when it is unsaved. */
    private fun retryUnsavedSnapshot() {
        if (activeSession != null && activeConversation.entries.size > persistedEntryCount) {
            enqueuePersist()
        }
    }

    // ---- intent internals ----

    private suspend fun saveModelSelectionInternal(providerId: String, modelId: String) {
        val trimmedModelId = modelId.trim()
        val provider = catalog.getProvider(providerId)
        // Static catalog existence first: an unknown provider or an id the
        // catalog has never carried is always ERROR_UNKNOWN_MODEL, never a
        // credential error or an availability message.
        if (provider == null || provider.model(trimmedModelId) == null) {
            setError(ERROR_UNKNOWN_MODEL)
            return
        }
        // Then the credential-filtered set (pi's getAvailable / filterModels
        // rule): a GitHub Copilot OAuth credential can narrow the selectable
        // models to its availableModelIds, so a static catalog id the account
        // cannot use is rejected exactly like an unknown one.
        val selectable = try {
            authService.availableModels(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE, e)
            return
        }
        if (selectable.none { it.id == trimmedModelId }) {
            setError(ERROR_UNKNOWN_MODEL)
            return
        }
        if (rejectWhileBusy()) return

        // Credential-completeness gate (pi's rule: a provider is configured
        // only when its stored credential resolves — every API-key prompt
        // has a value, or a stored OAuth credential has a registered flow).
        val providerConfigured = try {
            provider != null && authService.isConfigured(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE, e)
            return
        }
        if (!providerConfigured) {
            setError(ERROR_CREDENTIAL_INCOMPLETE)
            return
        }

        val candidate = ModelSettings(
            providerId = providerId,
            modelId = trimmedModelId,
            activeSessionId = activeSession?.id,
            // The display preference is owned by setShowThinking; preserve it
            // so agent rebuilds never drift from what the user picked.
            showThinking = currentSettings.showThinking,
        )

        val wasReady = _uiState.value.status == ChatStatus.Ready && activeSession != null
        if (wasReady) {
            // Any failure (factory, settings write, or an unsaved transcript)
            // keeps the previous agent, session, and persisted settings.
            if (!awaitPersistence()) {
                setError(ERROR_SESSION_SAVE)
                return
            }
            // pi's setModel records the switch on the session tree before
            // persisting defaults (agent-session.ts ~1665).
            val updated = activeConversation.appendModelChange(providerId, trimmedModelId)
            val newAgent = tryCreateAgent(candidate, activeSession!!.id, updated) ?: return
            if (!persistSettings(candidate)) return
            bindAgent(newAgent)
            currentSettings = candidate
            enqueuePersist()
        } else {
            // Initial configuration: validate the session and agent first,
            // then persist the settings, then activate. A failure at any step
            // leaves the ViewModel unconfigured (never falsely Ready).
            val prepared = prepareAdoption(candidate) ?: return
            if (!persistSettings(candidate)) return
            if (!activateSession(prepared.first, prepared.second)) return
            currentSettings = candidate
        }

        refreshOptions()
        updateState {
            it.copy(
                status = ChatStatus.Ready,
                startKey = ChatNavKey,
                navigationEpoch = it.navigationEpoch + 1,
            )
        }
    }

    private suspend fun saveProviderCredentialInternal(
        providerId: String,
        apiKeyInput: String,
        envInputs: Map<String, String>,
    ) {
        val provider = catalog.getProvider(providerId) ?: run {
            setError(ERROR_UNKNOWN_PROVIDER)
            return
        }
        // One auth flow at a time: a key save must not race an in-flight
        // account login (pi serializes logins through the credential store).
        if (isAuthProviderBusy()) {
            setError(ERROR_AUTH_IN_PROGRESS)
            return
        }
        // The first auth prompt is the API key (stored in credential.key);
        // every other prompt fills its env slot. Each input is the value —
        // a complete save replaces the stored credential wholesale (pi's
        // login semantics: nothing survives from the previous credential).
        // A still-incomplete credential is rejected rather than persisted
        // (pi's cloudflare auth resolution: unconfigured unless every required
        // value exists). The error names the missing prompts — never values.
        val newKey = apiKeyInput.trim()
        val env = buildMap<String, String> {
            provider.auth.prompts.drop(1).forEach { prompt ->
                val value = envInputs[prompt.envKey]?.trim()
                if (!value.isNullOrEmpty()) put(prompt.envKey, value)
            }
        }
        val missing = provider.missingAuthPrompts(newKey.ifEmpty { null }, env)
        if (newKey.isEmpty() || missing.isNotEmpty()) {
            setError(missingCredentialError(missing))
            return
        }
        // Save through the provider-neutral login orchestration: the form's
        // values answer the catalog's own prompts (in order) through an
        // in-memory interaction, and the credential is persisted only after
        // the login succeeds — an unconditional replacement of whatever was
        // stored (account↔key switches replace the credential type). The
        // answers live only in this local interaction, never in UI state.
        val answers = buildList {
            provider.auth.prompts.forEachIndexed { index, prompt ->
                add(if (index == 0) newKey else env[prompt.envKey].orEmpty())
            }
        }
        try {
            authService.login(providerId, AuthType.API_KEY, FormAuthInteraction(answers))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE, e)
            return
        }
        onCredentialStored()
    }

    /**
     * Shared post-login success path (pi's `completeProviderAuthentication`):
     * bumps the credential-success epoch so the UI closes the auth screen
     * only after confirmed persistence, refreshes every credential-derived
     * surface, and — while still in the forced first-run flow — completes
     * configuration when the persisted model settings are now usable, else
     * advances to the model-settings step.
     */
    private suspend fun onCredentialStored() {
        // Success signal for the UI layer: only a confirmed persistence
        // closes the credential form (it pops one ProviderAuth entry when
        // this epoch changes); a failed or incomplete save above returns
        // without bumping it, so the form and its typed inputs survive.
        updateState { it.copy(credentialSuccessEpoch = it.credentialSuccessEpoch + 1) }

        val nowConfigured = isConfigured(currentSettings)
        refreshOptions(nowConfigured)
        if (_uiState.value.status == ChatStatus.NeedsConfiguration) {
            if (nowConfigured) {
                val prepared = prepareAdoption(currentSettings) ?: return
                if (!activateSession(prepared.first, prepared.second)) return
            }
            updateState {
                it.copy(
                    status = if (nowConfigured) ChatStatus.Ready else it.status,
                    startKey = if (nowConfigured) ChatNavKey else ModelSettingsNavKey,
                    navigationEpoch = it.navigationEpoch + 1,
                )
            }
        }
    }

    // ---- app foreground tracking (Android platform glue for OAuth gating) ----

    /** Whether the app's activity is currently resumed. */
    val appForegrounded: StateFlow<Boolean> get() = appForegroundGate.foreground

    /** Forwarded from MainActivity.onResume. */
    fun onAppForegrounded() = appForegroundGate.onAppForegrounded()

    /** Forwarded from MainActivity.onPause. */
    fun onAppBackgrounded() = appForegroundGate.onAppBackgrounded()

    // ---- interactive provider login (OAuth/account flows) ----

    /** The in-flight login coroutine, if any (cancelled by user cancel or ViewModel clear). */
    private var authJob: Job? = null

    /** Reply channel of the currently suspended login prompt, if any. */
    private var pendingPromptReply: CompletableDeferred<String>? = null

    private fun isAuthProviderBusy(): Boolean =
        _uiState.value.authFlow != null || authJob?.isActive == true

    /**
     * Starts the selected method's login flow (pi's `startProviderLogin`).
     * Events accumulate as non-secret projections; a suspended prompt is
     * exposed as the single pending prompt and answered via
     * [submitAuthPrompt]; only [AuthType.API_KEY] with a sole method is
     * normally started through the all-fields form instead. Concurrent
     * flows are rejected: one login at a time, exactly like pi's modal
     * login dialog.
     */
    fun beginProviderAuthLogin(providerId: String, method: AuthMethodInfo) {
        if (isAuthProviderBusy()) {
            setError(ERROR_AUTH_IN_PROGRESS)
            return
        }
        if (providerAuthMethods(providerId).none { it.type == method.type }) {
            setError(ERROR_UNKNOWN_PROVIDER)
            return
        }
        authJob = viewModelScope.launch {
            updateState { it.copy(authFlow = ProviderAuthFlow(providerId, method)) }
            try {
                authService.login(providerId, method.type, UiAuthInteraction())
            } catch (e: CancellationException) {
                // User cancel or ViewModel teardown: no credential was
                // mutated (login persists only on success); clear and stop.
                updateState { it.copy(authFlow = null) }
                return@launch
            } catch (e: Exception) {
                updateState { it.copy(authFlow = null) }
                setError(ERROR_AUTH_LOGIN, e)
                return@launch
            }
            updateState { it.copy(authFlow = null) }
            onCredentialStored()
        }
    }

    /**
     * Answers the pending login prompt. The answer crosses straight into
     * the suspended login coroutine; it is never stored in UI state, saved,
     * or logged. A no-op when no prompt is pending.
     */
    fun submitAuthPrompt(answer: String) {
        pendingPromptReply?.complete(answer)
    }

    /**
     * Cancels the in-flight login (pi's dialog cancel): the login coroutine
     * and any pending prompt are cancelled, no credential is mutated, and
     * the flow state clears. A no-op when no flow is active.
     */
    fun cancelProviderAuthLogin() {
        pendingPromptReply?.cancel(CancellationException("Login cancelled"))
        pendingPromptReply = null
        authJob?.cancel()
        // Belt-and-braces for a flow suspended outside a prompt: the login
        // coroutine's cancellation handler clears the state above.
        if (authJob?.isActive != true) {
            updateState { it.copy(authFlow = null) }
        }
    }

    /**
     * Bridges the ported [AuthInteraction] onto UI state: `notify` appends
     * the non-secret event projection; `prompt` exposes one pending prompt
     * and suspends on a [CompletableDeferred] — cancellation (user cancel,
     * ViewModel teardown) aborts the whole login, per pi's AbortSignal.
     */
    private inner class UiAuthInteraction : AuthInteraction {
        override suspend fun prompt(prompt: AuthInteractionPrompt): String {
            val reply = CompletableDeferred<String>()
            pendingPromptReply = reply
            updateState { state ->
                state.copy(authFlow = state.authFlow?.copy(pendingPrompt = projectAuthPrompt(prompt)))
            }
            try {
                return reply.await()
            } finally {
                pendingPromptReply = null
                updateState { state ->
                    state.copy(authFlow = state.authFlow?.copy(pendingPrompt = null))
                }
            }
        }

        override suspend fun notify(event: AuthEvent) {
            updateState { state ->
                state.copy(authFlow = state.authFlow?.copy(events = state.authFlow.events + event))
            }
        }
    }

    /** In-memory [AuthInteraction] answering fixed form values in order. */
    private class FormAuthInteraction(
        answers: List<String>,
    ) : AuthInteraction {
        private val remaining = ArrayDeque(answers)

        override suspend fun prompt(prompt: AuthInteractionPrompt): String = remaining.removeFirst()

        override suspend fun notify(event: AuthEvent) {}
    }

    /**
     * The provider's selectable auth methods (pi's login menu entries), or
     * an empty list for an unknown provider. Never touches credentials.
     */
    fun providerAuthMethods(providerId: String): List<AuthMethodInfo> =
        try {
            authService.authMethods(providerId)
        } catch (e: ModelsError) {
            emptyList()
        }

    /**
     * True iff settings name a catalog provider+model the stored credential
     * can still use (the model must be in the credential-filtered set, pi's
     * getAvailable) AND the provider's stored credential resolves
     * (API-key completeness or a registered OAuth flow for a stored OAuth
     * credential).
     */
    private suspend fun isConfigured(settings: ModelSettings): Boolean {
        val provider = catalog.getProvider(settings.providerId) ?: return false
        val selectable = try {
            authService.availableModels(provider.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordDegradation("available_models", e)
            return false
        }
        if (selectable.none { it.id == settings.modelId }) return false
        return try {
            authService.isConfigured(provider.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordDegradation("is_configured", e)
            false
        }
    }

    /**
     * Recomputes every credential-derived surface (providers screen rows,
     * model picker options, selection projection, `configured`). Called on
     * init, after every credential mutation, and from [refreshProviderStatus].
     */
    private suspend fun refreshOptions(configuredOverride: Boolean? = null) {
        val providerOptions = try {
            catalog.providers
                .map { provider ->
                    ProviderOption(
                        id = provider.id,
                        name = provider.name,
                        configured = authService.isConfigured(provider.id),
                    )
                }
                .sortedBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE, e)
            return
        }
        val configured = configuredOverride ?: try {
            isConfigured(currentSettings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE, e)
            return
        }
        val configuredIds = providerOptions.filter { it.configured }.map { it.id }.toSet()
        // Pi's model-selector rule: only models from configured providers,
        // and only the credential-filtered set each provider exposes
        // (pi's getAvailable: filterModels over the static list — GitHub
        // Copilot's availableModelIds).
        val modelOptions = catalog.providers
            .filter { it.id in configuredIds }
            .flatMap { provider ->
                val available = try {
                    authService.availableModels(provider.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setError(ERROR_CREDENTIAL_SAVE, e)
                    return
                }
                available.map { model ->
                    ModelOption(
                        providerId = provider.id,
                        providerName = provider.name,
                        modelId = model.id,
                        name = model.name,
                    )
                }
            }
            .sortedWith(compareBy({ it.providerName }, { it.name }))
        val selectedModel = selectedModelProjection(currentSettings)
        updateState {
            it.copy(
                providerOptions = providerOptions,
                modelOptions = modelOptions,
                selectedModel = selectedModel,
                configured = configured,
            )
        }
    }

    private suspend fun selectedModelProjection(settings: ModelSettings): SelectedModel? {
        val provider = catalog.getProvider(settings.providerId) ?: return null
        // Same credential-filtered set as the picker (pi's getAvailable): a
        // persisted selection the account can no longer use projects away.
        val available = try {
            authService.availableModels(provider.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordDegradation("selected_model", e)
            null
        }
        val model = available?.firstOrNull { it.id == settings.modelId } ?: return null
        return SelectedModel(
            providerId = provider.id,
            providerName = provider.name,
            modelId = model.id,
            modelName = model.name,
        )
    }

    /** Persists the validated configuration; false (with a safe error) on failure. */
    private suspend fun persistSettings(settings: ModelSettings): Boolean {
        try {
            settingsRepository.setProviderId(settings.providerId)
            settingsRepository.setModelId(settings.modelId)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SETTINGS_SAVE, e)
            return false
        }
    }

    private suspend fun sendInternal() {
        val state = _uiState.value
        if (state.status != ChatStatus.Ready || state.isStreaming) return
        val text = state.draft.trim()
        val currentAgent = agent
        if (text.isEmpty() || currentAgent == null) return

        updateState { it.copy(draft = "") }
        try {
            currentAgent.prompt(text)
        } catch (e: CancellationException) {
            // Abort (or ViewModel teardown): the agent already committed its
            // terminal state, which the state observer persists.
            throw e
        } catch (e: IllegalStateException) {
            setError(ERROR_ALREADY_STREAMING)
        }
    }

    /** True (and sets an error) when a session/config-changing intent arrives mid-stream. */
    private fun rejectWhileBusy(): Boolean {
        if (_uiState.value.isStreaming) {
            setError(ERROR_BUSY)
            return true
        }
        return false
    }

    // ---- helpers ----

    /**
     * Records a `pf.chat.degraded` telemetry span: a failure the ViewModel
     * deliberately absorbs into degraded UI state ("unconfigured", "no
     * selection") instead of an error — pi's degradation semantics, kept,
     * but no longer invisible on-device (the credential store failing to
     * read must be distinguishable from an actually-missing credential).
     */
    private suspend fun recordDegradation(operation: String, cause: Throwable) {
        telemetryContext.startSpan(
            SpanOptions(name = SPAN_DEGRADED, attributes = mapOf(ATTR_OPERATION to attr(operation))),
        ) { span ->
            span.setStatus(automaticErrorStatus(cause))
        }
    }

    /**
     * Surfaces [message] as the UI error. This is the UI's single error
     * boundary, so when a [cause] exception is available it is also recorded
     * as a `pf.chat.error` telemetry span — the only place otherwise-invisible
     * failures become diagnosable on-device (the ported OAuth flows build
     * redacted exception messages by construction, which is what makes the
     * detail safe to record). The generic [message] itself is a static UI
     * string and carries no secrets.
     */
    private fun setError(message: String, cause: Throwable? = null) {
        updateState { it.copy(error = message) }
        if (cause == null) return
        viewModelScope.launch {
            telemetryContext.startSpan(
                SpanOptions(name = SPAN_ERROR, attributes = mapOf(ATTR_UI_ERROR to attr(message))),
            ) { span ->
                span.setStatus(automaticErrorStatus(cause))
            }
        }
    }

    private fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _uiState.update { current ->
            val next = transform(current)
            next.copy(canSend = next.status == ChatStatus.Ready && !next.isStreaming && next.draft.isNotBlank())
        }
    }

    private companion object {
        const val DEFAULT_SESSION_TITLE = "New chat"

        /** App-owned span vocabulary (pi packages define `pi.*` schemas; Pathfinder's are `pf.*`). */
        const val SPAN_ERROR = "pf.chat.error"
        const val ATTR_UI_ERROR = "pf.error.ui_message"
        const val SPAN_DEGRADED = "pf.chat.degraded"
        const val ATTR_OPERATION = "pf.degraded.operation"
        const val TITLE_MAX_LENGTH = 48

        const val ERROR_INIT = "Could not load chat data"
        const val ERROR_CREDENTIAL_INCOMPLETE =
            "Complete this provider's sign-in values before using its models"
        const val ERROR_UNKNOWN_MODEL = "Unknown model"
        const val ERROR_MODEL_UNAVAILABLE =
            "That model is no longer available for this account — pick another model"
        const val ERROR_UNKNOWN_PROVIDER = "Unknown provider"
        const val ERROR_CREDENTIAL_SAVE = "Could not store the API key"
        const val ERROR_SETTINGS_SAVE = "Could not save the configuration"
        const val ERROR_CONFIG_INVALID = "Invalid configuration"
        const val ERROR_SESSION_CREATE = "Could not create a new chat"
        const val ERROR_SESSION_LOAD = "Could not open the chat"
        const val ERROR_SESSION_MISSING = "That chat no longer exists"
        const val ERROR_SESSION_SAVE = "Could not save the chat"
        const val ERROR_BUSY = "Wait for the response to finish first"
        const val ERROR_AUTH_IN_PROGRESS = "A sign-in is already in progress"
        const val ERROR_AUTH_LOGIN = "Could not complete sign-in"
        const val ERROR_ALREADY_STREAMING = "A response is already streaming"
        const val ERROR_ALREADY_AT_POINT = "Already at this point"
        const val ERROR_ENTRY_MISSING = "Message not found"

        /** Actionable, secret-free message naming the still-missing auth prompts. */
        fun missingCredentialError(missing: List<AuthPrompt>): String =
            "Sign-in values are still needed: " +
                missing.joinToString(", ") { prompt -> prompt.message.ifEmpty { prompt.envKey } }

        /** Single-line, bounded title from the first user prompt. */
        fun deriveTitle(messages: List<Message>): String? {
            val firstText = messages.asSequence()
                .filterIsInstance<UserMessage>()
                .firstOrNull()
                ?.content
                ?.asSequence()
                ?.filterIsInstance<TextContent>()
                ?.firstOrNull()
                ?.text
                ?: return null
            return firstText.lineSequence()
                .firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(TITLE_MAX_LENGTH)
        }
    }
}

/**
 * UI projection of the committed transcript as ordered blocks: the active
 * conversation path is the structural source (pi's session branch) but only
 * entries still live in the agent transcript render — auto-retry and
 * overflow recovery remove failed assistant messages from agent state while
 * the append-only tree keeps them in history, exactly like pi's UI.
 * Text parts stay separate, runs of consecutive thinking parts merge into
 * one block (pi's assistant-message semantics), and blank parts drop. Keys
 * are stable per path index+role+timestamp so that same-millisecond
 * user/assistant messages can never collide.
 */
private fun projectCommitted(liveMessages: List<Message>, conversation: Conversation): List<ChatMessage> {
    val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Message, Boolean>())
    live.addAll(liveMessages)
    val projected = mutableListOf<ChatMessage>()
    conversation.activeEntries().forEachIndexed { index, entry ->
        when {
            // A compaction cut renders as a minimal divider marker (pi's UI
            // shows the summary in a collapsible; pathfinder keeps the marker
            // minimal — the summary itself lives in LLM context only).
            entry is CompactionEntry -> projected.add(
                ChatMessage(id = "compacted-${entry.id}", role = ChatRole.Assistant, blocks = emptyList(), isCompactionMarker = true),
            )
            entry !is MessageEntry || !live.contains(entry.message) -> Unit
            else -> {
                val message = entry.message
                val chat = when (message) {
                    is UserMessage -> ChatMessage(
                        id = "msg-$index-${message.timestamp}",
                        role = ChatRole.User,
                        blocks = message.content.toChatBlocks(),
                    )
                    is AssistantMessage -> ChatMessage(
                        id = "msg-$index-${message.timestamp}",
                        role = ChatRole.Assistant,
                        blocks = message.content.toChatBlocks(),
                        error = message.errorMessage,
                    )
                    else -> null
                }
                chat?.let { projected.add(it) }
            }
        }
    }
    return projected
}

/** UI projection of the in-flight partial; distinct key namespace from committed messages.
 * Pi's `streamingMessage` is role-generic (user/tool-result message_starts
 * transiently occupy it); the chat's streaming contract stays assistant-only,
 * so non-assistant partials project to nothing at the call site. */
private fun projectStreaming(message: AssistantMessage): ChatMessage =
    ChatMessage(
        id = "streaming-${message.timestamp}",
        role = ChatRole.Assistant,
        blocks = message.content.toChatBlocks(),
        error = message.errorMessage,
    )

/**
 * Projects content into ordered blocks: each non-blank [TextContent] becomes
 * its own [ChatBlock.Text]; runs of consecutive [ThinkingContent] merge into
 * one [ChatBlock.Thinking] joined with "\n\n" and trimmed (dropped when the
 * merged result is blank).
 */
private fun List<Content>.toChatBlocks(): List<ChatBlock> {
    val blocks = mutableListOf<ChatBlock>()
    var thinkingRun: MutableList<String>? = null
    fun flushThinking() {
        thinkingRun?.let { run ->
            run.joinToString("\n\n").trim().takeIf { it.isNotEmpty() }?.let { merged ->
                blocks.add(ChatBlock.Thinking(merged))
            }
        }
        thinkingRun = null
    }
    for (part in this) {
        when (part) {
            is ThinkingContent -> (thinkingRun ?: mutableListOf<String>().also { thinkingRun = it }).add(part.thinking)
            is TextContent -> {
                flushThinking()
                part.text.takeIf { it.isNotBlank() }?.let { blocks.add(ChatBlock.Text(it)) }
            }
            else -> flushThinking()
        }
    }
    flushThinking()
    return blocks
}
