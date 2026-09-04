package works.resolve.pathfinder.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt as AuthInteractionPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.auth.oauth.AppForegroundGate
import works.resolve.pathfinder.ai.clampThinkingLevel
import works.resolve.pathfinder.ai.getSupportedThinkingLevels
import works.resolve.pathfinder.ai.providers.AuthPrompt
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.OperationLifecycleRecorder
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.LaneRecord
import works.resolve.pathfinder.codingagent.core.session.LaneRecovery
import works.resolve.pathfinder.codingagent.core.session.LaneReductionInput
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.RecordLogCorruption
import works.resolve.pathfinder.codingagent.core.session.RecordLogCorruptionReason
import works.resolve.pathfinder.codingagent.core.session.RecordQuery
import works.resolve.pathfinder.codingagent.core.session.Session
import works.resolve.pathfinder.codingagent.core.session.SessionEntry
import works.resolve.pathfinder.codingagent.core.session.SessionRepository
import works.resolve.pathfinder.codingagent.core.session.SessionState
import works.resolve.pathfinder.codingagent.core.session.SessionSummary
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry
import works.resolve.pathfinder.codingagent.core.session.reduceLaneState
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.logging.PathfinderDiagnostics
import works.resolve.pathfinder.runtime.AgentFactory
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.tools.websearch.SearchProviderService

/**
 * Chat screen controller. Owns configuration, sessions, and the active
 * [AgentSession]; projects everything into an immutable [ChatUiState] (UDF).
 *
 * Divergence from pi (deliberate): pi's picker Ctrl+S applies the highlighted
 * row AND persists it as the startup default in one gesture; Pathfinder keeps
 * the pickers ephemeral and moves default persistence to the Settings
 * screens, so "use it now AND default it" takes two steps.
 *
 * Transcript persistence keeps every transcript with its session: at most
 * one save per session is in flight and newer snapshots coalesce into it,
 * session switches wait for pending saves, snapshot writes are
 * non-cancellable, and a failed save blocks session/config switches until
 * it is retried successfully — neither a failed save nor ViewModel teardown
 * can silently abandon a transcript.
 *
 * Navigation is state, not effects: intents that complete configuration set
 * [ChatUiState.startKey] (and bump [ChatUiState.navigationEpoch]) atomically
 * with the rest of the state, and the UI layer resets its Nav3 back stack to
 * [ChatUiState.startKey] whenever either field changes.
 */
class ChatViewModel(
    private val settingsRepository: SettingsStore,
    private val catalog: ProviderCatalog,
    private val authService: ProviderAuthService,
    private val sessionStore: SessionRepository,
    private val agentFactory: AgentFactory,
    /** Resolves a provider/model pair to the effective request model; throwing input is surfaced as a safe unknown-model error. */
    private val modelResolver: (providerId: String, modelId: String) -> Model,
    /** App-owned diagnostics boundary for the UI error/degradation spans. */
    private val diagnostics: PathfinderDiagnostics = PathfinderDiagnostics.NOOP,
    /**
     * Process-wide foreground state (Android platform glue; pi has no
     * foreground concept), driven from MainActivity lifecycle; the OAuth
     * flows gate loopback waits and network work on it.
     */
    private val appForegroundGate: AppForegroundGate = AppForegroundGate(),
    /** Web-search credential management (Brave only, Scry parity). */
    private val searchProviderService: SearchProviderService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Interactive provider-login state machine; [ProviderLoginController.flow] is the source of truth [ChatUiState.authFlow] mirrors. */
    private val loginController = ProviderLoginController(
        scope = viewModelScope,
        authService = authService,
        diagnostics = diagnostics,
        onLoginSucceeded = { onCredentialStored() },
        onLoginFailed = { cause -> setError(ERROR_AUTH_LOGIN, cause) }
    )

    /** Current committed configuration; updated on init and successful save. */
    private var currentSettings: ModelSettings = ModelSettings()

    /**
     * The persisted startup default provider/model pair, kept separate from
     * [currentSettings]: initialization may seed currentSettings from a
     * derived/branch-folded model while the stored default stays whatever
     * settings last wrote. Only [saveStartupDefaultInternal] changes this.
     */
    private var defaultModelRef: Pair<String, String> = "" to ""

    private var agent: AgentSession? = null
    private var agentStateJob: Job? = null
    private var agentEventsJob: Job? = null
    private var activeSession: Session? = null

    /** The conversation tree of [activeSession], owned by the bound [AgentSession]; read here for projection and persistence. */
    private val activeConversation: Conversation
        get() = agent?.conversation ?: Conversation(emptyList(), null)

    /**
     * Watermark of active-session entries already appended to the session's
     * mutation log. Tree growth beyond it schedules a persist; leaf-only
     * moves (navigation) persist without moving it.
     */
    private var persistedEntryCount: Int = 0

    /** Latest unsaved conversation snapshot for its owning session. */
    private var pendingPersist: Pair<Session, Conversation>? = null
    private var persistJob: Job? = null

    /** Agent-sourced error last projected into the UI, to detect agent-side clearing. */
    private var lastAgentError: String? = null

    /**
     * Whether a usable Brave Search key is currently stored; every agent
     * created or bound here synchronizes web_search against it. Maintained
     * from live credential reads, never persisted.
     */
    private var searchBraveConfigured: Boolean = false

    init {
        viewModelScope.launch { initialize() }
        viewModelScope.launch {
            loginController.flow.collect { flow -> updateState { it.copy(authFlow = flow) } }
        }
    }

    // ---- intents ----

    fun onDraftChange(text: String) {
        updateState { it.copy(draft = text) }
    }

    fun dismissError() {
        updateState { it.copy(error = null) }
    }

    /**
     * Switches the live session's model. Not busy-rejected: like pi, a
     * mid-stream pick is safe — the active run keeps its start-of-run model
     * and the switch applies to the next prompt. Does NOT persist the
     * startup default; that lives in [saveStartupDefault].
     */
    fun selectModel(providerId: String, modelId: String) {
        viewModelScope.launch { selectModelInternal(providerId, modelId) }
    }

    /**
     * Persists the startup default provider+model. Divergence from pi's
     * Ctrl+S: this does not also switch the live session (see the class
     * KDoc). A non-empty model scope gains the default when missing.
     */
    fun saveStartupDefault(providerId: String, modelId: String) {
        viewModelScope.launch { saveStartupDefaultInternal(providerId, modelId) }
    }

    /**
     * Curates the scoped models — which models the picker offers. The scope
     * is not a hard constraint: curating never touches the running model,
     * and the picker keeps an All view over every available model.
     */
    fun toggleModelScope(providerId: String, modelId: String, checked: Boolean) {
        viewModelScope.launch { toggleModelScopeInternal(providerId, modelId, checked) }
    }

    /**
     * Switches the live session's thinking level. Not busy-rejected: like
     * pi, a mid-stream pick is safe — the active run keeps its start-of-run
     * level and the switch applies to the next prompt. Does NOT persist the
     * default; that lives in [setThinkingLevelDefault].
     */
    fun selectThinkingLevel(level: ModelThinkingLevel) {
        viewModelScope.launch {
            val session = agent ?: return@launch
            try {
                session.setThinkingLevel(level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_THINKING_SWITCH, e)
                return@launch
            }
            updateState { it.copy(treeRows = buildTreeRows(session.conversation, it.treeFilter)) }
            // The thinking_level_change may have grown the tree: persist it.
            enqueuePersist()
        }
    }

    /**
     * Persists the default thinking level. Applies to the live session
     * first and persists after (pi's order), so a failed settings write
     * leaves the session switched. The stored default seeds sessions
     * without a recorded branch level ([seededSettingsFor]) and is
     * re-applied on model switches ([selectModelInternal]).
     */
    fun setThinkingLevelDefault(level: ModelThinkingLevel) {
        viewModelScope.launch {
            val session = agent
            if (session != null) {
                try {
                    session.setThinkingLevel(level)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setError(ERROR_THINKING_SWITCH, e)
                    return@launch
                }
            }
            try {
                settingsRepository.setDefaultThinkingLevel(level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SETTINGS_SAVE, e)
                return@launch
            }
            currentSettings = currentSettings.copy(defaultThinkingLevel = level)
            updateState { it.copy(defaultThinkingLevel = level) }
            if (session != null) {
                updateState {
                    it.copy(treeRows = buildTreeRows(session.conversation, it.treeFilter))
                }
                enqueuePersist()
            }
        }
    }

    /**
     * Saves a fresh credential for [providerId]: every prompt's input is its
     * value and a complete save replaces the stored credential wholesale —
     * values are never merged with what was stored. Blank/missing required
     * values are rejected with an error naming the missing prompts.
     *
     * Not busy-rejected: the agent resolves the credential once per request,
     * so changing it mid-stream only affects the next request.
     */
    fun saveProviderCredential(
        providerId: String,
        apiKeyInput: String,
        envInputs: Map<String, String>
    ) {
        viewModelScope.launch { saveProviderCredentialInternal(providerId, apiKeyInput, envInputs) }
    }

    /**
     * Forgets the credential for [providerId]. Never tears down sessions or
     * the agent (credentials are read per request); only the derived status
     * surfaces are refreshed.
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

    /** Re-reads credentials and recomputes the derived provider/model surfaces. */
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

    /** UI-safe auth prompts for a search provider's credential form (only Brave is supported). */
    fun searchProviderAuthPrompts(providerId: String): List<ProviderAuthPrompt> =
        if (providerId == SearchProviderService.BRAVE_PROVIDER_ID) {
            listOf(
                ProviderAuthPrompt(
                    BRAVE_API_KEY_PROMPT,
                    SEARCH_BRAVE_KEY_PROMPT_MESSAGE,
                    secret = true
                )
            )
        } else {
            emptyList()
        }

    /**
     * Stores a web-search provider's API key. Blank input, an unknown
     * provider, or a storage failure surfaces a static, secret-free error
     * and changes nothing. Only a confirmed non-blank save bumps
     * [ChatUiState.searchCredentialSuccessEpoch] and enables web_search on
     * the bound session for the next run.
     */
    fun saveSearchProviderCredential(providerId: String, apiKeyInput: String) {
        viewModelScope.launch {
            if (providerId != SearchProviderService.BRAVE_PROVIDER_ID) {
                setError(ERROR_SEARCH_CREDENTIAL_SAVE)
                return@launch
            }
            val key = apiKeyInput.trim()
            if (key.isEmpty()) {
                setError(ERROR_SEARCH_CREDENTIAL_SAVE)
                return@launch
            }
            try {
                searchProviderService.saveApiKey(providerId, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SEARCH_CREDENTIAL_SAVE, e)
                return@launch
            }
            updateState {
                it.copy(searchCredentialSuccessEpoch = it.searchCredentialSuccessEpoch + 1)
            }
            refreshSearchStatus()
        }
    }

    /**
     * Deletes a search provider's stored key; a failure surfaces a safe
     * error and changes nothing.
     */
    fun removeSearchProviderCredential(providerId: String) {
        viewModelScope.launch {
            try {
                searchProviderService.remove(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SEARCH_CREDENTIAL_SAVE, e)
                return@launch
            }
            refreshSearchStatus()
        }
    }

    /**
     * Re-reads search credentials and recomputes the derived surfaces, like
     * [refreshProviderStatus] for LLM providers. A read failure degrades
     * search to unconfigured/disabled with a safe error.
     */
    fun refreshSearchProviderStatus() {
        viewModelScope.launch { refreshSearchStatus() }
    }

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
     * Navigates the conversation tree to [id]. Busy-rejected while
     * streaming; selecting the current leaf or an unknown id surfaces a
     * safe error. A user message target re-edits: the leaf moves to its
     * parent (or resets to root) and its text is restored into the draft,
     * so the next send forks as a sibling. Any other target moves the leaf
     * to that entry.
     */
    fun navigateToTreeEntry(id: String) {
        navigateToTreeEntry(id, summarize = false)
    }

    /**
     * Navigation with branch summarization: when [summarize] is set, the
     * abandoned branch segment is summarized and a branch-summary entry is
     * appended at the target position, both wrapped in a durable navigation
     * operation record (see [AgentSession.navigateTree]).
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
            // The agent runs on the branch's folded configuration.
            // Divergence from pi: a rebuild happens only when the fold
            // changes the model (pi swaps state in-place on the live agent,
            // which factory-built agents do not support); an unknown folded
            // model keeps the running agent rather than failing navigation.
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
                    // A typed draft is never clobbered by navigation; the
                    // re-edit text lands only in an empty draft.
                    draft = if (it.draft.isBlank()) result.editorText ?: it.draft else it.draft,
                    messages = projectCommitted(active.agent.state.value.messages, updated),
                    treeRows = buildTreeRows(updated, it.treeFilter)
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
                // Seeding the initial model and thinking level on new
                // sessions lets the branch's configuration fold restore both
                // on resume.
                val (seeded, conversation) = seededSettingsFor(session, currentSettings)
                val newAgent = tryCreateAgent(seeded, session.id, conversation) ?: return@launch
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
                        conversation
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
            currentSettings = settings
            defaultModelRef = settings.providerId to settings.modelId
            refreshOptions()

            // NeedsConfiguration means exactly "no configured provider at
            // all"; once any provider credential resolves, the app enters
            // the chat directly with a derived initial model.
            if (_uiState.value.modelOptions.isEmpty()) {
                updateState {
                    it.copy(
                        status = ChatStatus.NeedsConfiguration,
                        startKey = ProvidersNavKey,
                        showThinking = settings.showThinking,
                        sessionSummaries = summaries
                    )
                }
                return
            }

            // The saved default when usable, else the first available model
            // of a configured provider. A saved model known to the catalog
            // but absent from the credential-filtered set surfaces a safe
            // error while the derived replacement runs; a corrupt/unknown
            // model id is NOT "unavailable" and adds no error.
            val dbgConfigured = isConfigured(settings)
            val candidate = if (dbgConfigured) {
                settings
            } else {
                val savedKnown = catalog.getProvider(settings.providerId)
                    ?.model(settings.modelId) != null
                val first = _uiState.value.modelOptions.first()
                if (savedKnown) setError(ERROR_MODEL_UNAVAILABLE)
                settings.copy(providerId = first.providerId, modelId = first.modelId)
            }

            val session = resolveSession(candidate, summaries)
            // Build the agent before committing any state: a factory failure
            // must never leave a Ready UI or persisted active-session id.
            val (seeded, conversation) = seededSettingsFor(session, candidate)
            val newAgent = tryCreateAgent(
                seeded,
                session.id,
                conversation
            )
            if (newAgent == null) {
                updateState {
                    it.copy(
                        status = ChatStatus.Failed,
                        sessionSummaries = summaries,
                        error = ERROR_CONFIG_INVALID
                    )
                }
                return
            }
            if (!activateSession(session, newAgent)) {
                // The active-id write failed: a safe settings error is already
                // surfaced; never report Ready with nothing bound.
                updateState { it.copy(status = ChatStatus.Failed) }
                return
            }
            // currentSettings follows the effective running model; the
            // stored default stays untouched.
            currentSettings = seeded
            refreshOptions()
            updateState {
                it.copy(
                    status = ChatStatus.Ready,
                    showThinking = settings.showThinking
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
    private suspend fun resolveSession(
        settings: ModelSettings,
        summaries: List<SessionSummary>
    ): Session {
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
                laneRecovery = laneRecovery
            )
        }
        // Initial seeding may have appended entries to an entry-less
        // session; flush them like any other append. Loaded sessions carry
        // no new entries, so a plain load or switch never saves here.
        if (activeConversation.entries.size > persistedEntryCount) enqueuePersist()
        return true
    }

    /**
     * Restores the main lane's recovery classification by running the full
     * reducer over the session's durable record log. Pathfinder is
     * single-lane and sessions are small, so the recovery slice is the
     * entire lane. Validation failures map to [LaneRecovery.Corrupt]; a
     * failing read degrades to [LaneRecovery.Idle] with telemetry —
     * classification is advisory UI state, never a load blocker.
     */
    private suspend fun laneRecoveryFor(session: Session): LaneRecovery = try {
        val openOperations = sessionStore.openOperations(
            session.id,
            SessionState.LANE_MAIN,
            limit = 2
        )
        if (openOperations.size > 1) {
            LaneRecovery.Corrupt(RecordLogCorruptionReason.MULTIPLE_OPEN_OPERATIONS)
        } else {
            val started = openOperations.singleOrNull()
            val result = reduceLaneState(
                LaneReductionInput(
                    lane = SessionState.LANE_MAIN,
                    openOperations = openOperations,
                    records = sessionStore.findRecords(
                        session.id,
                        RecordQuery(lane = SessionState.LANE_MAIN)
                    ),
                    // Operation-owned entries: everything appended after the
                    // open operation's start (single writer, single lane).
                    ownEntries =
                        started?.let { op -> session.entries.filter { it.seq > op.seq } }
                            ?: emptyList(),
                    entries = session.entries,
                    configurationEntries = configurationEntriesFor(session, started?.sourceLeafId),
                    leafId = session.leafId
                )
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
     * when the anchor is an unpersisted buffered entry (records may precede
     * the entries they name).
     */
    private fun configurationEntriesFor(session: Session, anchorId: String?): List<SessionEntry> {
        var cursor: String? =
            anchorId?.takeIf { id -> session.entries.any { it.id == id } } ?: session.leafId
                ?: return emptyList()
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
            conversation
        ) ?: return null
        return session to newAgent
    }

    /**
     * Seeds settings for [session]: the active branch's configuration fold
     * ([Conversation.effectiveConfiguration]) overrides the provider/model
     * when it recorded one; new (entry-less) sessions are seeded with the
     * initial model selection so the fold can restore it on resume. A branch
     * with no thinking_level_change entry is seeded with the stored default
     * thinking level, else "medium", clamped to the effective model.
     */
    private fun seededSettingsFor(
        session: Session,
        settings: ModelSettings
    ): Pair<ModelSettings, Conversation> {
        var conversation = Conversation(session.entries, session.leafId)
        if (conversation.entries.isEmpty() && settings.providerId.isNotBlank() &&
            settings.modelId.isNotBlank()
        ) {
            conversation = conversation.appendModelChange(settings.providerId, settings.modelId)
        }
        val seeded = settingsSeededFromFold(settings, conversation)
        if (conversation.entries.none { it is ThinkingLevelEntry } &&
            seeded.providerId.isNotBlank() && seeded.modelId.isNotBlank()
        ) {
            // Clamped before storing. An unresolvable model fails agent
            // creation anyway, so the tree stays unseeded rather than
            // gaining a second error path.
            val seededLevel = try {
                val model = modelResolver(seeded.providerId, seeded.modelId)
                clampThinkingLevel(model, seeded.defaultThinkingLevel ?: DEFAULT_THINKING_LEVEL)
            } catch (e: Exception) {
                null
            }
            if (seededLevel != null) {
                conversation = conversation.appendThinkingLevelChange(seededLevel.wire)
            }
        }
        return seeded to conversation
    }

    /**
     * Seeds the provider/model from the conversation's configuration fold: a
     * branch that recorded a different model runs on that model, overriding
     * the global defaults. Divergence from pi: only pairs the generated
     * catalog supports are applied; anything else falls back to [settings].
     */
    private fun settingsSeededFromFold(
        settings: ModelSettings,
        conversation: Conversation
    ): ModelSettings {
        val model = conversation.effectiveConfiguration().model ?: return settings
        if (model.provider == settings.providerId &&
            model.modelId == settings.modelId
        ) {
            return settings
        }
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
        conversation: Conversation
    ): AgentSession? = try {
        agentFactory.create(settings, sessionId, conversation)
            // Synchronize web_search against the current Brave credential
            // before anything binds to the session.
            .also(::synchronizeWebSearch)
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
        // The recorder resolves the session at call time (mid-run session
        // switches are blocked).
        newAgent.operationRecorder = operationRecorder
        agentStateJob =
            viewModelScope.launch { newAgent.state.collect { state -> onAgentState(state) } }
        // Events are zero-replay flow: the subscriber must be bound before
        // any prompt starts.
        agentEventsJob =
            viewModelScope.launch { newAgent.events.collect { event -> onAgentEvent(event) } }
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
                updateState {
                    it.copy(
                        isCompacting = false,
                        treeRows = buildTreeRows(activeConversation, it.treeFilter)
                    )
                }
                // The compaction entry landed on the tree before this event.
                if (activeConversation.entries.size > persistedEntryCount) {
                    enqueuePersist()
                }
            }

            // Summarization-retry telemetry is deliberately unsurfaced.
            is AgentEvent.SummarizationRetryScheduled,
            is AgentEvent.SummarizationRetryAttemptStart,
            is AgentEvent.SummarizationRetryFinished
            -> Unit

            // Re-project and persist on tree growth, not agent-transcript
            // growth: an auto-retry or overflow recovery removes the error
            // message from agent state while the append-only tree keeps it.
            is AgentEvent.MessageEnd -> {
                updateState {
                    it.copy(
                        messages = projectCommittedAfterSessionMessageEnd(),
                        treeRows = buildTreeRows(activeConversation, it.treeFilter)
                    )
                }
                if (activeConversation.entries.size > persistedEntryCount) {
                    enqueuePersist()
                }
            }

            else -> Unit
        }
    }

    /**
     * Re-projects from the same state/tree intersection as [onAgentState], so
     * observing both paths is idempotent rather than append-incremental.
     * Ordering: the agent reduces `message_end` into state before the
     * session appends it to the conversation and re-emits the session event,
     * so the first projection sees the old tree — with no follow-up state
     * emission it would otherwise never see the committed message.
     */
    private fun projectCommittedAfterSessionMessageEnd(): List<ChatMessage> =
        projectCommitted(agent?.state?.value?.messages.orEmpty(), activeConversation)

    private fun onAgentState(state: AgentState) {
        val agentError = state.errorMessage
        updateState {
            it.copy(
                messages = projectCommitted(state.messages, activeConversation),
                pendingTools = pendingToolExecutions(state),
                streamingMessage = (state.streamingMessage as? AssistantMessage)?.let(
                    ::projectStreaming
                ),
                isStreaming = state.isStreaming,
                thinkingLevel = state.thinkingLevel,
                availableThinkingLevels = getSupportedThinkingLevels(state.model),
                error = agentError ?: it.error?.takeIf { e -> e != lastAgentError }
            )
        }
        lastAgentError = agentError
    }

    // ---- persistence pipeline ----

    /**
     * Bridge from [AgentSession]'s operation lifecycle to the session's
     * mutation log. Appends dispatch asynchronously onto [viewModelScope]
     * in call order and serialize through the store's mutex: the run loop
     * never blocks on record durability, and abort_requested still lands
     * before the cancellation handler's operation_finished. Record appends
     * never flush buffered conversation entries — the log permits records
     * to precede the entries they reference (see [LaneRecord]). A failed
     * append degrades durability only: the error surfaces, the run continues.
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
            // persistSnapshot is non-cancellable, so a dequeued snapshot
            // always completes; on scope teardown the loop drains accepted
            // snapshots and then exits (new enqueues stop with the
            // cancelled collectors).
            while (true) {
                val next = pendingPersist ?: break
                pendingPersist = null
                persistSnapshot(next.first, next.second)
            }
        }
    }

    /**
     * Writes one snapshot. The append-only sync always reaches the file even
     * if the user switched sessions meanwhile; UI/active state is only
     * updated when that session is still active.
     */
    private suspend fun persistSnapshot(session: Session, conversation: Conversation) {
        // Non-cancellable: an accepted snapshot must reach the file even
        // when the ViewModel scope is torn down mid-write.
        withContext(NonCancellable) {
            try {
                val activeMessages = conversation.activeMessages()
                val title = if (session.title == DEFAULT_SESSION_TITLE) {
                    deriveTitle(activeMessages) ?: DEFAULT_SESSION_TITLE
                } else {
                    session.title
                }
                // Persist the tree itself (entries + leaf): branch structure
                // survives saves.
                val saved = sessionStore.save(
                    session.copy(
                        entries = conversation.entries,
                        leafId = conversation.leafId,
                        title = title
                    )
                )
                if (activeSession?.id == session.id) {
                    activeSession = saved
                    persistedEntryCount = saved.entries.size
                }
                val summaries = sessionStore.summaries()
                if (activeSession?.id == session.id ||
                    _uiState.value.activeSessionId == session.id
                ) {
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
     * re-enqueueing it first if a previous save failed. Returns false when
     * the latest snapshot remains unsaved: callers must keep the current
     * session so an unsaved transcript is never abandoned.
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

    private suspend fun selectModelInternal(providerId: String, modelId: String) {
        val session = agent
        if (session == null) {
            setError(ERROR_CONFIG_INVALID)
            return
        }
        val model = try {
            modelResolver(providerId, modelId.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_UNKNOWN_MODEL, e)
            return
        }
        // A catalog model the account cannot use (credential-filtered
        // availability) is rejected like an unknown one.
        val available = try {
            authService.availableModels(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE, e)
            return
        }
        if (available.none { it.id == model.id }) {
            setError(ERROR_UNKNOWN_MODEL)
            return
        }
        try {
            session.setModel(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_MODEL_SWITCH, e)
            return
        }
        // Like pi's model switch, apply the thinking level for the new
        // model: the stored global default, else the session's current
        // level (per-model overrides are not ported). Applied after the
        // model_change because the default is app-owned settings the
        // session facade holds none of; setThinkingLevel clamps to the new
        // model and appends only on change, so this usually records nothing.
        session.setThinkingLevel(currentSettings.defaultThinkingLevel ?: session.thinkingLevel)
        updateState {
            it.copy(
                selectedModel = selectedModelProjection(model),
                treeRows = buildTreeRows(session.conversation, it.treeFilter)
            )
        }
        // The model_change grew the conversation: persist it.
        enqueuePersist()
    }

    private suspend fun saveStartupDefaultInternal(providerId: String, modelId: String) {
        val trimmed = modelId.trim()
        val candidate = currentSettings.copy(
            providerId = providerId,
            modelId = trimmed,
            activeSessionId = activeSession?.id
        )
        if (!persistSettings(candidate)) return
        currentSettings = candidate
        defaultModelRef = providerId to trimmed

        // A non-empty scope gains the default when missing (order-preserving
        // append; case-insensitive reference match).
        val scope = currentSettings.enabledModels.orEmpty().filter { it.isNotBlank() }
        if (scope.isNotEmpty()) {
            val reference = "$providerId/$trimmed"
            if (scope.none { it.equals(reference, ignoreCase = true) }) {
                val grown = scope + reference
                try {
                    settingsRepository.setEnabledModels(grown)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setError(ERROR_SETTINGS_SAVE, e)
                    return
                }
                currentSettings = currentSettings.copy(enabledModels = grown)
            }
        }
        refreshOptions()
    }

    private suspend fun toggleModelScopeInternal(
        providerId: String,
        modelId: String,
        checked: Boolean
    ) {
        val state = _uiState.value
        val reference = "$providerId/$modelId"
        // The curated list is written in display order; an absent scope
        // materializes as "everything currently offered" on first edit.
        val displayOrder = state.modelOptions.map { "${it.providerId}/${it.modelId}" }
        val current = state.enabledModels?.toSet() ?: displayOrder.toSet()
        val next = if (checked) current + reference else current - reference
        val stored = state.enabledModels.orEmpty()
        // Preserve stored references not currently displayed (e.g. of a
        // provider whose credential was removed) in their stored order.
        val ordered =
            displayOrder.filter { it in next } + stored.filter { it !in displayOrder && it in next }
        try {
            settingsRepository.setEnabledModels(ordered)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SETTINGS_SAVE, e)
            return
        }
        currentSettings = currentSettings.copy(enabledModels = ordered)
        projectScope(state.modelOptions)
    }

    private suspend fun saveProviderCredentialInternal(
        providerId: String,
        apiKeyInput: String,
        envInputs: Map<String, String>
    ) {
        val provider = catalog.getProvider(providerId) ?: run {
            setError(ERROR_UNKNOWN_PROVIDER)
            return
        }
        // A key save must not race an in-flight account login.
        if (isAuthProviderBusy()) {
            setError(ERROR_AUTH_IN_PROGRESS)
            return
        }
        // The first auth prompt is the API key; every other prompt fills its
        // env slot. An incomplete credential is rejected rather than
        // persisted.
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
        // The form's values answer the catalog's own prompts (in order)
        // through an in-memory interaction; the answers live only here,
        // never in UI state.
        val answers = buildList {
            provider.auth.prompts.forEachIndexed { index, prompt ->
                add(if (index == 0) newKey else env[prompt.envKey].orEmpty())
            }
        }
        try {
            diagnostics.authLogin(providerId, AuthType.API_KEY.wire) {
                authService.login(providerId, AuthType.API_KEY, FormAuthInteraction(answers))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE, e)
            return
        }
        onCredentialStored()
    }

    /**
     * Shared post-login success path: bumps the credential-success epoch so
     * the UI closes the auth screen only after confirmed persistence,
     * refreshes every credential-derived surface, and — while still
     * unconfigured — completes configuration with the derived initial model
     * and enters the chat directly.
     */
    private suspend fun onCredentialStored() {
        // Only a confirmed persistence bumps this epoch, so the credential
        // form and its typed inputs survive a failed save above.
        updateState { it.copy(credentialSuccessEpoch = it.credentialSuccessEpoch + 1) }

        refreshOptions()
        if (_uiState.value.status == ChatStatus.NeedsConfiguration &&
            _uiState.value.modelOptions.isNotEmpty()
        ) {
            val candidate = if (isConfigured(currentSettings)) {
                currentSettings
            } else {
                val first = _uiState.value.modelOptions.first()
                currentSettings.copy(providerId = first.providerId, modelId = first.modelId)
            }
            val prepared = prepareAdoption(candidate) ?: return
            if (!activateSession(prepared.first, prepared.second)) return
            currentSettings = candidate
            // The derivation may have changed provider/model after the
            // pre-bind refreshOptions; re-project now that the agent is bound.
            refreshOptions()
            updateState {
                it.copy(
                    status = ChatStatus.Ready,
                    startKey = ChatNavKey,
                    navigationEpoch = it.navigationEpoch + 1
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

    /**
     * Starts the selected method's login flow in [loginController] (one
     * login at a time). Only [AuthType.API_KEY] with a sole method is
     * normally started through the all-fields form instead.
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
        loginController.begin(providerId, method)
    }

    /**
     * Answers the pending login prompt. The answer crosses straight into
     * the suspended login coroutine; it is never stored in UI state, saved,
     * or logged. A no-op when no prompt is pending.
     */
    fun submitAuthPrompt(answer: String) = loginController.submitPrompt(answer)

    /**
     * Cancels the in-flight login: the login coroutine and any pending
     * prompt are cancelled, no credential is mutated. A no-op when no flow
     * is active.
     */
    fun cancelProviderAuthLogin() = loginController.cancel()

    private fun isAuthProviderBusy(): Boolean = loginController.busy

    /** In-memory [AuthInteraction] answering fixed form values in order. */
    private class FormAuthInteraction(answers: List<String>) : AuthInteraction {
        private val remaining = ArrayDeque(answers)

        override suspend fun prompt(prompt: AuthInteractionPrompt): String = remaining.removeFirst()

        override suspend fun notify(event: AuthEvent) {}
    }

    /**
     * The provider's selectable auth methods, or an empty list for an
     * unknown provider. Never touches credentials.
     */
    fun providerAuthMethods(providerId: String): List<AuthMethodInfo> = try {
        authService.authMethods(providerId)
    } catch (e: ModelsError) {
        emptyList()
    }

    /**
     * True iff settings name a catalog provider+model the stored credential
     * can still use (in the credential-filtered set) AND the provider's
     * stored credential resolves.
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
     * Recomputes every credential-derived surface (provider rows, model
     * options, scoped list, selection projection). The live session model —
     * not the persisted settings — projects [ChatUiState.selectedModel].
     */
    private suspend fun refreshOptions() {
        // Search status first: a provider read failure must not leave it
        // stale, and it must be fresh before any agent creation follows.
        // Search credentials never contribute to the LLM first-run
        // configuration below — `search_`-namespaced keys are not catalog
        // provider credentials.
        refreshSearchStatus()
        val providerOptions = try {
            catalog.providers
                .map { provider ->
                    ProviderOption(
                        id = provider.id,
                        name = provider.name,
                        configured = authService.isConfigured(provider.id)
                    )
                }
                .sortedBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordDegradation("provider_status", e)
            setError(ERROR_CREDENTIAL_SAVE, e)
            return
        }
        val configuredIds = providerOptions.filter { it.configured }.map { it.id }.toSet()
        // Only models from configured providers, limited to each provider's
        // credential-filtered set.
        val modelOptions = catalog.providers
            .filter { it.id in configuredIds }
            .flatMap { provider ->
                val available = try {
                    authService.availableModels(provider.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recordDegradation("available_models", e)
                    setError(ERROR_CREDENTIAL_SAVE, e)
                    return
                }
                available.map { model ->
                    ModelOption(
                        providerId = provider.id,
                        providerName = provider.name,
                        modelId = model.id,
                        name = model.name
                    )
                }
            }
            .sortedWith(compareBy({ it.providerName }, { it.name }))
        val selectedModel = agent?.let { selectedModelProjection(it.model) }
            ?: selectedModelProjection(currentSettings.providerId, currentSettings.modelId)
        val defaultModel = defaultModelRef
            .takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
            ?.let { selectedModelProjection(it.first, it.second) }
        updateState {
            it.copy(
                providerOptions = providerOptions,
                modelOptions = modelOptions,
                selectedModel = selectedModel,
                defaultModel = defaultModel,
                defaultThinkingLevel = currentSettings.defaultThinkingLevel
            )
        }
        projectScope(modelOptions)
    }

    /**
     * Recomputes the search-provider surface from live credential reads and
     * updates [searchBraveConfigured]; every bound session's web_search
     * activation follows ([synchronizeWebSearch]). A read failure degrades
     * search to unconfigured/disabled and surfaces a safe error — it never
     * fails an otherwise-valid chat initialization.
     */
    private suspend fun refreshSearchStatus() {
        val options = try {
            searchProviderService.providers
                .map { provider ->
                    ProviderOption(
                        id = provider.id,
                        name = provider.name,
                        configured = searchProviderService.isConfigured(provider.id)
                    )
                }
                .sortedBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordDegradation("search_provider_status", e)
            setError(ERROR_SEARCH_STATUS, e)
            searchBraveConfigured = false
            updateState {
                it.copy(
                    searchProviderOptions = searchProviderService.providers
                        .map { provider ->
                            ProviderOption(provider.id, provider.name, configured = false)
                        }
                        .sortedBy { option -> option.name }
                )
            }
            agent?.let(::synchronizeWebSearch)
            return
        }
        searchBraveConfigured =
            options.firstOrNull { it.id == SearchProviderService.BRAVE_PROVIDER_ID }?.configured ==
            true
        updateState { it.copy(searchProviderOptions = options) }
        agent?.let(::synchronizeWebSearch)
    }

    /**
     * Aligns one session's tool set with the current search credential:
     * web_search is appended (last) only while Brave is configured; the
     * order and activation of every other tool is preserved exactly. Safe
     * mid-stream: the agent snapshots its tool list per run, so an
     * in-flight run keeps its own snapshot and the change lands on the
     * next run. No `active_tools_change` session entry is appended — this
     * runtime-only toggle is never persisted.
     */
    private fun synchronizeWebSearch(session: AgentSession) {
        val names = session.getActiveToolNames().filter { it != BraveWebSearchTool.NAME } +
            listOfNotNull(BraveWebSearchTool.NAME.takeIf { searchBraveConfigured })
        session.setActiveToolsByName(names)
    }

    /**
     * Projects the scope-derived surfaces: the stored-scope mirror and the
     * picker's scoped list (an empty scope means no scoping).
     */
    private fun projectScope(modelOptions: List<ModelOption>) {
        val scope = currentSettings.enabledModels
        val scoped = if (scope.isNullOrEmpty()) {
            modelOptions
        } else {
            // Case-insensitive reference match.
            val enabled = scope.mapTo(mutableSetOf()) { it.lowercase() }
            modelOptions.filter { "${it.providerId}/${it.modelId}".lowercase() in enabled }
        }
        updateState { it.copy(enabledModels = scope, scopedModelOptions = scoped) }
    }

    /** Catalog display projection of a provider/model pair; null when unknown. */
    private fun selectedModelProjection(providerId: String, modelId: String): SelectedModel? {
        val provider = catalog.getProvider(providerId) ?: return null
        if (modelId.isBlank()) return null
        val model = provider.model(modelId) ?: return null
        return SelectedModel(
            providerId = provider.id,
            providerName = provider.name,
            modelId = model.id,
            modelName = model.name
        )
    }

    private fun selectedModelProjection(model: Model): SelectedModel? =
        selectedModelProjection(model.provider, model.id)

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
            // Abort or teardown: the agent committed its terminal state,
            // which the state observer persists.
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
     * Records a `pf.chat.degraded` telemetry span for a failure the
     * ViewModel deliberately absorbs into degraded UI state instead of an
     * error — the credential store failing to read must be distinguishable
     * from an actually-missing credential.
     */
    private suspend fun recordDegradation(operation: String, cause: Throwable) {
        diagnostics.chatDegraded(operation, cause)
    }

    /**
     * Surfaces [message] as the UI error and records a `pf.chat.error`
     * telemetry span for the [cause] — the only place otherwise-invisible
     * failures become diagnosable on-device. Only the cause's exception
     * type is recorded (app diagnostics policy: exception messages are not
     * a guaranteed-safe free-form surface); [message] is a static UI string
     * carrying no secrets.
     */
    private fun setError(message: String, cause: Throwable? = null) {
        updateState { it.copy(error = message) }
        if (cause == null) return
        viewModelScope.launch {
            diagnostics.chatError(message, cause)
        }
    }

    private fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _uiState.update { current ->
            val next = transform(current)
            next.copy(
                canSend =
                    next.status == ChatStatus.Ready && !next.isStreaming && next.draft.isNotBlank()
            )
        }
    }

    private companion object {
        const val DEFAULT_SESSION_TITLE = "New chat"

        const val TITLE_MAX_LENGTH = 48

        const val ERROR_INIT = "Could not load chat data"
        const val ERROR_UNKNOWN_MODEL = "Unknown model"
        const val ERROR_MODEL_UNAVAILABLE =
            "That model is no longer available for this account — pick another model"
        const val ERROR_MODEL_SWITCH =
            "Could not switch to that model — check the provider sign-in"
        const val ERROR_THINKING_SWITCH = "Could not switch the thinking level"
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
        const val ERROR_SEARCH_CREDENTIAL_SAVE = "Could not store the search API key"
        const val ERROR_SEARCH_STATUS = "Could not read the search provider status"

        /** Kept as the prompt's stable id. */
        const val BRAVE_API_KEY_PROMPT = "BRAVE_API_KEY"

        /** Clear, secret-free message for the Brave key prompt. */
        const val SEARCH_BRAVE_KEY_PROMPT_MESSAGE = "Enter your Brave Search API key"

        /** pi's default thinking level: "medium". */
        val DEFAULT_THINKING_LEVEL = ModelThinkingLevel.MEDIUM

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
