package works.resolve.pathfinder.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt as AuthInteractionPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.CredentialType
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.auth.oauth.AppForegroundGate
import works.resolve.pathfinder.ai.getSupportedThinkingLevels
import works.resolve.pathfinder.ai.providers.AuthPrompt
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.ReadonlySessionManager
import works.resolve.pathfinder.codingagent.core.SessionError
import works.resolve.pathfinder.codingagent.core.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.SessionInfo
import works.resolve.pathfinder.codingagent.core.SessionManager
import works.resolve.pathfinder.codingagent.core.SessionSeedModel
import works.resolve.pathfinder.codingagent.core.SessionSeedSettings
import works.resolve.pathfinder.codingagent.core.seedSessionConfiguration
import works.resolve.pathfinder.data.sessions.SessionSource
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.runtime.AgentFactory
import works.resolve.pathfinder.tools.websearch.SearchProviderService

/**
 * Chat screen controller. Owns configuration, sessions, and the active
 * [AgentSession]; projects everything into an immutable [ChatUiState] (UDF).
 *
 * Model picking follows pi's gesture: applying a model switches the live
 * session and persists the startup default together (pi's picker
 * Ctrl+S); the Settings screens persist a default without switching.
 *
 * Transcript persistence lives inside the runtime: every append (message,
 * model/thinking change, compaction, navigation) reaches the session file
 * inline at event time through the session manager, and a session file is
 * created lazily — a new chat is absent from the drawer until its first
 * assistant message commits (pi's selector behavior; an aborted first run
 * still commits an empty assistant message, so send+abort is durable).
 * Storage failures fail the run and surface as a save error while the
 * in-memory tree keeps its entries.
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
    private val sessionSource: SessionSource,
    private val agentFactory: AgentFactory,
    /** Resolves a provider/model pair to the effective request model; throwing input is surfaced as a safe unknown-model error. */
    private val modelResolver: (providerId: String, modelId: String) -> Model,
    /**
     * Process-wide foreground state (Android platform glue; pi has no
     * foreground concept), driven from MainActivity lifecycle; the OAuth
     * flows gate loopback waits and network work on it.
     */
    private val appForegroundGate: AppForegroundGate = AppForegroundGate(),
    private val searchProviderService: SearchProviderService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    private val loginController = ProviderLoginController(
        scope = viewModelScope,
        authService = authService,
        onLoginSucceeded = { onCredentialStored() },
        onLoginFailed = { cause -> setError(ERROR_AUTH_LOGIN, cause) }
    )

    private val searchProviders = SearchProviderController(
        scope = viewModelScope,
        service = searchProviderService,
        onError = { message, cause -> setError(message, cause) }
    )

    private val sessionSearch = SessionSearchController()

    val uiState: StateFlow<ChatUiState> = combine(
        _uiState,
        loginController.flow,
        searchProviders.state,
        sessionSearch.state
    ) { base, authFlow, searchProviders, sessionSearch ->
        base.copy(
            authFlow = authFlow,
            searchProviderOptions = searchProviders.options,
            sessionSearchQuery = sessionSearch.query,
            sessionSearchSort = sessionSearch.sort,
            sessionSearchResults = sessionSearch.results
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value)

    /**
     * The persisted settings as last read or written; its provider/model
     * fields are the startup default only. The running model lives on the
     * bound [AgentSession] — its branch fold at load, [selectModelInternal]
     * thereafter — and never drifts into this field.
     */
    private var currentSettings: ModelSettings = ModelSettings()

    private var agent: AgentSession? = null
    private var agentStateJob: Job? = null
    private var agentEventsJob: Job? = null

    /** Read view over the bound session's tree (pi's ReadonlySessionManager); null while none is bound. */
    private val activeSession: ReadonlySessionManager?
        get() = agent?.sessionManager

    /** Agent transcript instance used for the latest committed-message projection. */
    private var observedAgentMessages: List<Message>? = null

    /** Agent model instance behind the latest [ChatUiState.selectedModel] projection. */
    private var observedAgentModel: Model? = null

    /**
     * Unsent input per session, synced only at [activateSession] boundaries:
     * the outgoing draft is stashed under its session, the incoming session's
     * draft is loaded. Divergence from pi, whose single process-global editor
     * survives session switches: drafts must stay with their conversation —
     * especially tree re-edit text, which belongs to the node it came from.
     */
    private val sessionDrafts = mutableMapOf<String, String>()

    init {
        viewModelScope.launch { initialize() }
        viewModelScope.launch {
            searchProviders.state.map { it.braveConfigured }.distinctUntilChanged().collect {
                agent?.let(searchProviders::applyTo)
            }
        }
    }

    // ---- intents ----

    fun onDraftChange(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Switches the live session's model. Not busy-rejected: like pi, a
     * mid-stream pick is safe — the active run keeps its start-of-run model
     * and the switch applies to the next prompt. Also persists the
     * startup default (pi's picker gesture, setModel persist=true);
     * [saveStartupDefault] persists without switching.
     */
    fun selectModel(providerId: String, modelId: String) {
        viewModelScope.launch { selectModelInternal(providerId, modelId) }
    }

    /**
     * Persists the startup default provider+model without switching the
     * live session (pi: editing the settings field directly, not the
     * picker gesture). A non-empty model scope gains the default when
     * missing.
     */
    fun saveStartupDefault(providerId: String, modelId: String) {
        viewModelScope.launch { saveStartupDefaultInternal(providerId, modelId) }
    }

    /**
     * Curates which models the picker offers (all of them while no scope
     * is stored); never touches the running model. A full or empty
     * selection persists as the unset scope, as in pi.
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
            } catch (e: SessionError) {
                setError(ERROR_SESSION_SAVE, e)
                return@launch
            } catch (e: Exception) {
                setError(ERROR_THINKING_SWITCH, e)
                return@launch
            }
            _uiState.update {
                it.copy(treeRows = treeRows(it.treeFilter))
            }
        }
    }

    /**
     * Persists the default thinking level. Applies to the live session
     * first and persists after (pi's order), so a failed settings write
     * leaves the session switched. The stored default seeds sessions
     * without a recorded branch level ([seedSession]) and is re-applied on
     * model switches by the session itself ([AgentSession.setModel]).
     */
    fun setThinkingLevelDefault(level: ModelThinkingLevel) {
        viewModelScope.launch {
            val session = agent
            if (session != null) {
                try {
                    session.setThinkingLevel(level)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SessionError) {
                    setError(ERROR_SESSION_SAVE, e)
                    return@launch
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
            // Temporary wiring: keep the session's snapshot settings manager
            // in sync so later model switches re-apply the default (removed
            // when the app's settings flow moves onto SettingsManager).
            session?.settingsManager?.setDefaultThinkingLevel(level)
            currentSettings = currentSettings.copy(defaultThinkingLevel = level)
            _uiState.update { it.copy(defaultThinkingLevel = level) }
            if (session != null) {
                _uiState.update {
                    it.copy(treeRows = treeRows(it.treeFilter))
                }
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
     * Auth prompts for a provider's credential form, in catalog order: the
     * first prompt is the API key (secret); later prompts fill env slots.
     * Catalog data as-is — only envKey/message/secret exist on it.
     */
    fun providerAuthPrompts(providerId: String): List<AuthPrompt> =
        catalog.getProvider(providerId)?.auth?.prompts.orEmpty()

    /** Auth prompts for a search provider's credential form (only Brave is supported). */
    fun searchProviderAuthPrompts(providerId: String): List<AuthPrompt> =
        searchProviders.authPrompts(providerId)

    /**
     * Stores a web-search provider's API key; only a confirmed non-blank
     * save enables web_search on the bound session for the next run.
     */
    fun saveSearchProviderCredential(providerId: String, apiKeyInput: String) =
        searchProviders.saveCredential(providerId, apiKeyInput)

    /** Deletes a search provider's stored key. */
    fun removeSearchProviderCredential(providerId: String) =
        searchProviders.removeCredential(providerId)

    /**
     * Re-reads search credentials and recomputes the derived surfaces, like
     * [refreshProviderStatus] for LLM providers. A read failure degrades
     * search to unconfigured/disabled with a safe error.
     */
    fun refreshSearchProviderStatus() {
        viewModelScope.launch { searchProviders.refresh() }
    }

    /** Persists the show-thinking display preference; safe mid-stream (display-only). */
    fun setShowThinking(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setShowThinking(enabled)
                currentSettings = currentSettings.copy(showThinking = enabled)
                _uiState.update { it.copy(showThinking = enabled) }
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
     * safe error. A user message target re-edits — including when it is
     * the current leaf: the leaf moves to its parent (or resets to root)
     * and its text is restored into the draft, so the next send forks as a
     * sibling. Any other target moves the leaf to that entry.
     */
    fun navigateToTreeEntry(id: String) {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            val session = agent ?: return@launch
            val result = try {
                session.navigateTree(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                setError(ERROR_BUSY)
                return@launch
            } catch (e: IllegalArgumentException) {
                setError(ERROR_ENTRY_MISSING)
                return@launch
            } catch (e: SessionError) {
                setError(ERROR_SESSION_SAVE, e)
                return@launch
            }
            if (result.cancelled) return@launch
            if (result.outcome == AgentSession.NavigationOutcome.NO_OP) {
                setError(ERROR_ALREADY_AT_POINT)
                return@launch
            }
            val manager = session.sessionManager
            // The manager is a live view, so these read the post-navigation
            // state; navigation requires an idle loop, so nothing mutates
            // between them.
            //
            // Navigation never changes the running model: pi's navigateTree
            // rebuilds only the transcript. A branch's folded model re-applies
            // at the next session load, not on navigation.
            _uiState.update {
                it.copy(
                    // A typed draft is never clobbered by navigation; the
                    // re-edit text lands only in an empty draft.
                    draft = if (it.draft.isBlank()) result.editorText ?: it.draft else it.draft,
                    messages = projectCommitted(
                        session.agent.state.value.messages,
                        manager.getBranch()
                    ),
                    treeRows = buildTreeRows(
                        manager.getTree(),
                        manager.getLeafId(),
                        it.treeFilter
                    )
                )
            }
        }
    }

    /** Switches the tree-panel filter (in-memory only) and re-projects the rows. */
    fun setTreeFilter(filter: TreeFilter) {
        _uiState.update {
            it.copy(treeFilter = filter, treeRows = treeRows(filter))
        }
    }

    /** Filters the current session summaries without re-reading their transcripts. */
    fun onSessionSearchQueryChange(query: String) = sessionSearch.onQueryChange(query)

    /** Switches the drawer search sort and re-filters when a query is active. */
    fun setSessionSearchSort(sort: SessionSearchSort) = sessionSearch.setSort(sort)

    fun newSession() {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            try {
                // Memory-only: nothing touches disk, and the new session is
                // absent from the drawer until its first assistant commit.
                val manager = sessionSource.create()
                // pi's findInitialModel picks the initial model; the seeds
                // let the branch's configuration fold restore it on resume.
                val seeded = seedSession(manager)
                val newAgent = tryCreateAgent(seeded, manager) ?: return@launch
                if (!activateSession(manager, newAgent)) return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_CREATE, e)
            }
        }
    }

    /**
     * Switches to a session listed in the summaries (which carry its file,
     * like pi's picker-to-resume handoff); an unlisted id surfaces a safe
     * error.
     */
    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            try {
                val file = _uiState.value.sessionSummaries
                    .firstOrNull { it.id == sessionId }?.path
                    ?: run {
                        setError(ERROR_SESSION_MISSING)
                        return@launch
                    }
                val manager = sessionSource.open(file)
                if (manager == null) {
                    setError(ERROR_SESSION_MISSING)
                    return@launch
                }
                val seeded = seedSession(manager)
                val newAgent = tryCreateAgent(seeded, manager) ?: return@launch
                if (!activateSession(manager, newAgent)) return@launch
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
            val summaries = try {
                sessionSource.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordDegradation("session_summaries", e)
                emptyList()
            }
            currentSettings = settings
            refreshOptions()

            // NeedsConfiguration means exactly "no configured provider at
            // all"; once any provider credential resolves, the app enters
            // the chat directly with a derived initial model.
            if (_uiState.value.modelOptions.isEmpty()) {
                _uiState.update {
                    it.copy(
                        status = ChatStatus.NeedsConfiguration,
                        startKey = ProvidersNavKey,
                        showThinking = settings.showThinking,
                        sessionSummaries = summaries
                    )
                }
                return
            }

            // A saved default known to the catalog but absent from the
            // credential-filtered set surfaces a safe error while the
            // derived replacement runs; a corrupt/unknown model id is NOT
            // "unavailable" and adds no error.
            val defaultAvailable = _uiState.value.modelOptions.any {
                it.providerId == settings.providerId && it.modelId == settings.modelId
            }
            if (!defaultAvailable && settings.modelId.isNotBlank() &&
                catalog.getProvider(settings.providerId)?.model(settings.modelId) != null
            ) {
                setError(ERROR_MODEL_UNAVAILABLE)
            }

            val manager = resolveSession(settings, summaries)
            // Build the agent before committing any state: a factory failure
            // must never leave a Ready UI or persisted active-session id.
            val seeded = seedSession(manager)
            val newAgent = tryCreateAgent(seeded, manager)
            if (newAgent == null) {
                _uiState.update {
                    it.copy(
                        status = ChatStatus.Failed,
                        sessionSummaries = summaries,
                        error = ERROR_CONFIG_INVALID
                    )
                }
                return
            }
            if (!activateSession(manager, newAgent)) {
                // The active-id write failed: a safe settings error is already
                // surfaced; never report Ready with nothing bound.
                _uiState.update { it.copy(status = ChatStatus.Failed) }
                return
            }
            _uiState.update {
                it.copy(
                    status = ChatStatus.Ready,
                    showThinking = settings.showThinking,
                    sessionSummaries = summaries
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_INIT, e)
            _uiState.update { it.copy(status = ChatStatus.Failed) }
        }
    }

    /**
     * pi's continueRecent: the requested active session, else the most
     * recently modified listed one, else a new one. The stored id can point
     * at a never-flushed session (process death before any assistant
     * committed) — it is absent from the summaries and the flow falls
     * through exactly as for any other missing session.
     */
    private suspend fun resolveSession(
        settings: ModelSettings,
        summaries: List<SessionInfo>
    ): SessionManager {
        settings.activeSessionId?.let { id ->
            summaries.firstOrNull { it.id == id }?.let { info ->
                sessionSource.open(info.path)?.let { return it }
            }
        }
        summaries.firstOrNull()?.let { info ->
            sessionSource.open(info.path)?.let { return it }
        }
        return sessionSource.create()
    }

    // ---- session / agent lifecycle ----

    /**
     * Makes [session] active with a prebuilt [agent]: persists the active id,
     * binds the agent, and returns to the chat surface. Only called after
     * the factory accepted the settings. Returns false when persisting the
     * active id fails; in that case nothing is committed.
     *
     * Summaries are not refreshed here: activation touches no session file;
     * [AgentEvent.MessageEnd] refreshes when one changes.
     */
    private suspend fun activateSession(manager: SessionManager, agent: AgentSession): Boolean {
        try {
            settingsRepository.setActiveSessionId(manager.getSessionId())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SETTINGS_SAVE, e)
            return false
        }
        val conversation = agent.sessionManager
        val outgoing = _uiState.value
        outgoing.activeSessionId?.let { id ->
            if (outgoing.draft.isBlank()) {
                sessionDrafts.remove(id)
            } else {
                sessionDrafts[id] =
                    outgoing.draft
            }
        }
        val draft = sessionDrafts[manager.getSessionId()].orEmpty()
        // Do not suspend between binding and publishing the session id:
        // collection can start immediately, and a frame must never render
        // incoming messages with the outgoing session's scroll state.
        bindAgent(agent)
        _uiState.update {
            it.copy(
                activeSessionId = manager.getSessionId(),
                startKey = ChatNavKey,
                navigationEpoch = it.navigationEpoch + 1,
                messages = projectCommitted(
                    agent.state.value.messages,
                    conversation.getBranch()
                ),
                streamingMessage = null,
                treeRows = buildTreeRows(
                    conversation.getTree(),
                    conversation.getLeafId(),
                    it.treeFilter
                ),
                draft = draft
            )
        }
        return true
    }

    /**
     * Resolves the session and builds an agent for it: validation happens
     * before anything is committed. Returns null on failure (safe error set).
     */
    private suspend fun prepareAdoption(): Pair<SessionManager, AgentSession>? {
        val manager = try {
            resolveSession(currentSettings, sessionSource.list())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SESSION_CREATE, e)
            return null
        }
        val seeded = seedSession(manager)
        val newAgent = tryCreateAgent(seeded, manager) ?: return null
        return manager to newAgent
    }

    /** Binds this ViewModel's resolved settings into [manager] (see [seedSessionConfiguration]). */
    private suspend fun seedSession(manager: SessionManager): ModelSettings {
        val seeded = seedSessionConfiguration(
            manager = manager,
            settings = SessionSeedSettings(
                providerId = currentSettings.providerId,
                modelId = currentSettings.modelId,
                enabledModels = currentSettings.enabledModels,
                defaultThinkingLevel = currentSettings.defaultThinkingLevel
            ),
            modelOptions = _uiState.value.modelOptions.map {
                SessionSeedModel(providerId = it.providerId, modelId = it.modelId)
            },
            modelResolver = modelResolver,
            catalog = catalog
        )
        return currentSettings.copy(providerId = seeded.providerId, modelId = seeded.modelId)
    }

    /** Builds an agent or null (with a safe error surfaced) when the factory rejects the settings. */
    private fun tryCreateAgent(
        settings: ModelSettings,
        sessionManager: SessionManager
    ): AgentSession? = try {
        agentFactory.create(settings, sessionManager) { currentSettings.defaultThinkingLevel }
            // Synchronize web_search against the current Brave credential
            // before anything binds to the session.
            .also(searchProviders::applyTo)
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
        observedAgentMessages = null
        observedAgentModel = null
        agentStateJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            newAgent.state.collect { state -> onAgentState(state) }
        }
        // Events are zero-replay flow: the subscriber must be bound before
        // any prompt starts.
        agentEventsJob =
            viewModelScope.launch { newAgent.events.collect { event -> onAgentEvent(event) } }
    }

    /** Projects session lifecycle events into transient UI surfaces. */
    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AutoRetryStart -> _uiState.update {
                it.copy(retryStatus = AutoRetryStatus(event.attempt, event.maxAttempts))
            }

            is AgentEvent.AutoRetryEnd -> _uiState.update { it.copy(retryStatus = null) }

            is AgentEvent.CompactionStart -> _uiState.update { it.copy(isCompacting = true) }

            is AgentEvent.CompactionEnd -> {
                _uiState.update {
                    it.copy(
                        isCompacting = false,
                        treeRows = treeRows(it.treeFilter)
                    )
                }
            }

            // Summarization-retry events are deliberately unsurfaced.
            is AgentEvent.SummarizationRetryScheduled,
            is AgentEvent.SummarizationRetryAttemptStart,
            is AgentEvent.SummarizationRetryFinished
            -> Unit

            // Re-project on tree growth, not agent-transcript growth: an
            // auto-retry or overflow recovery removes the error message from
            // agent state while the append-only tree keeps it. A message may
            // also create the session file (or land in an existing one), so
            // the drawer summaries refresh here — model/thinking appends do
            // not change any observable summary field. This is also where a
            // retained streaming row hands off to its committed row (see
            // [onAgentState]), in the same update.
            is AgentEvent.MessageEnd -> {
                _uiState.update {
                    it.copy(
                        messages = projectCommittedAfterSessionMessageEnd(),
                        streamingMessage = null,
                        treeRows = treeRows(it.treeFilter)
                    )
                }
                viewModelScope.launch { refreshSessionSummaries() }
            }

            else -> Unit
        }
    }

    /** Tree rows over the bound session's current entries and leaf. */
    private fun treeRows(filter: TreeFilter): List<TreeRow> {
        val manager = activeSession ?: return emptyList()
        return buildTreeRows(manager.getTree(), manager.getLeafId(), filter)
    }

    /**
     * Re-projects from the same state/tree intersection as [onAgentState], so
     * observing both paths is idempotent rather than append-incremental.
     * Ordering: the agent reduces `message_end` into state before the
     * session appends it to the conversation and re-emits the session event,
     * so the first projection sees the old tree — with no follow-up state
     * emission it would otherwise never see the committed message.
     */
    private fun projectCommittedAfterSessionMessageEnd(): List<TranscriptRow> = projectCommitted(
        agent?.state?.value?.messages.orEmpty(),
        activeSession?.getBranch().orEmpty()
    )

    private fun onAgentState(state: AgentState) {
        // AgentState uses copy-on-write transcript lists. Streaming chunks
        // change only streamingMessage, so retain the existing projection
        // instead of rebuilding and structurally comparing every committed
        // message (including potentially large tool outputs) for every token.
        val committedProjection = if (state.messages === observedAgentMessages) {
            null
        } else {
            projectCommitted(state.messages, activeSession?.getBranch().orEmpty())
        }
        observedAgentMessages = state.messages
        // Same reference-stability trick for the model chip: the model
        // instance changes only on setModel, so the catalog projection is
        // not recomputed per token.
        val modelProjection = if (state.model === observedAgentModel) {
            null
        } else {
            selectedModelProjection(state.model)
        }
        observedAgentModel = state.model
        _uiState.update {
            it.copy(
                messages = committedProjection ?: it.messages,
                selectedModel = modelProjection ?: it.selectedModel,
                // message_end commits to agent state (clearing streamingMessage)
                // before the session persists the message and grows the tree, so
                // null here does not mean the row left: retain the projection
                // until the MessageEnd handler lands the committed row, keeping
                // the streaming→committed handoff inside a single uiState
                // update instead of blinking out across the persistence write.
                streamingMessage = state.streamingMessage as? AssistantMessage
                    ?: it.streamingMessage,
                isStreaming = state.isStreaming,
                thinkingLevel = state.thinkingLevel,
                availableThinkingLevels = getSupportedThinkingLevels(state.model)
            )
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
        // No availability pre-check: the picker only offers
        // credential-filtered models (pi's split), and setModel validates
        // auth itself.
        try {
            session.setModel(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionError) {
            // A failed model_change append is a save failure, not a switch
            // failure — the agent already switched in memory.
            setError(ERROR_SESSION_SAVE, e)
            return
        } catch (e: Exception) {
            setError(ERROR_MODEL_SWITCH, e)
            return
        }
        // The chip follows the agent's state emission from setModel above;
        // only the tree needs re-projecting here. The new model's thinking
        // level is re-applied inside setModel (pi's rule) from the
        // app-owned default wired at agent creation.
        _uiState.update {
            it.copy(treeRows = treeRows(it.treeFilter))
        }
        // pi's picker gesture: applying a model both switches the live
        // session and persists it as the startup default (setModel with
        // persist, which also appends to a non-empty scope). The switch is
        // already committed; a failed persist surfaces its own error.
        saveStartupDefaultInternal(model.provider, model.id)
    }

    private suspend fun saveStartupDefaultInternal(providerId: String, modelId: String) {
        val trimmed = modelId.trim()
        val candidate = currentSettings.copy(
            providerId = providerId,
            modelId = trimmed,
            activeSessionId = _uiState.value.activeSessionId
        )
        if (!persistSettings(candidate)) return
        currentSettings = candidate

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
        val displayOrder = state.modelOptions.map(ModelOption::key)
        val current = state.enabledModels?.toSet() ?: displayOrder.toSet()
        val next = if (checked) current + reference else current - reference
        val stored = state.enabledModels.orEmpty()
        // Preserve stored references not currently displayed (e.g. of a
        // provider whose credential was removed) in their stored order.
        val ordered =
            displayOrder.filter { it in next } + stored.filter { it !in displayOrder && it in next }
        // As in pi, a full or empty selection persists as the unset scope
        // (models offered later stay visible); preserved references of
        // unoffered models keep the list materialized.
        val available = displayOrder.toSet()
        val scope = ordered.takeUnless {
            it.isEmpty() || (it.size == available.size && it.all(available::contains))
        }
        try {
            settingsRepository.setEnabledModels(scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SETTINGS_SAVE, e)
            return
        }
        currentSettings = currentSettings.copy(enabledModels = scope)
        _uiState.update { it.copy(enabledModels = scope) }
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
     * Shared post-login success path: refreshes every credential-derived
     * surface — the provider flipping to configured is what closes the
     * credential form — and, while still unconfigured, completes
     * configuration with the resolved initial model and enters the chat
     * directly.
     */
    private suspend fun onCredentialStored() {
        refreshOptions()
        if (_uiState.value.status == ChatStatus.NeedsConfiguration &&
            _uiState.value.modelOptions.isNotEmpty()
        ) {
            val prepared = prepareAdoption() ?: return
            if (!activateSession(prepared.first, prepared.second)) return
            _uiState.update {
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
     * login at a time). API-key credentials are never started here — they
     * go through the all-fields form ([saveProviderCredential]). Returns
     * false (with an error set) when rejected, so callers gate navigation
     * on it.
     */
    fun beginProviderAuthLogin(providerId: String, method: AuthMethodInfo): Boolean {
        if (isAuthProviderBusy()) {
            setError(ERROR_AUTH_IN_PROGRESS)
            return false
        }
        if (providerAuthMethods(providerId).none { it.type == method.type }) {
            setError(ERROR_UNKNOWN_PROVIDER)
            return false
        }
        loginController.begin(providerId, method)
        return true
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
     * Recomputes every credential-derived surface (provider rows, model
     * options, scoped list, default projection). [ChatUiState.selectedModel]
     * is not derived here: it follows the bound agent's state (see
     * [onAgentState]), the same source the next prompt uses.
     */
    private suspend fun refreshOptions() {
        // Search status first: a provider read failure must not leave it
        // stale, and it must be fresh before any agent creation follows.
        // Search credentials never contribute to the LLM first-run
        // configuration below — `search_`-namespaced keys are not catalog
        // provider credentials.
        searchProviders.refresh()
        val providerOptions = try {
            catalog.providers
                .map { provider ->
                    val configured = authService.isConfigured(provider.id)
                    // The stored kind labels the sign-out action ("Log out"
                    // vs "Forget provider"); read only when configured.
                    val authType = if (configured) {
                        authService.authStatus(provider.id).storedType
                    } else {
                        null
                    }
                    ProviderOption(
                        id = provider.id,
                        name = provider.name,
                        configured = configured,
                        authType = when (authType) {
                            CredentialType.API_KEY -> AuthType.API_KEY
                            CredentialType.OAUTH -> AuthType.OAUTH
                            null -> null
                        }
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
        val defaultModel = currentSettings
            .takeIf { it.providerId.isNotBlank() && it.modelId.isNotBlank() }
            ?.let { selectedModelProjection(it.providerId, it.modelId) }
        _uiState.update {
            it.copy(
                providerOptions = providerOptions,
                modelOptions = modelOptions,
                defaultModel = defaultModel,
                defaultThinkingLevel = currentSettings.defaultThinkingLevel,
                enabledModels = currentSettings.enabledModels
            )
        }
    }

    /** Catalog display projection of a provider/model pair; null when unknown. */
    private fun selectedModelProjection(providerId: String, modelId: String): ModelOption? {
        val provider = catalog.getProvider(providerId) ?: return null
        if (modelId.isBlank()) return null
        val model = provider.model(modelId) ?: return null
        return ModelOption(
            providerId = provider.id,
            providerName = provider.name,
            modelId = model.id,
            name = model.name
        )
    }

    private fun selectedModelProjection(model: Model): ModelOption? =
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

        _uiState.update { it.copy(draft = "") }
        try {
            currentAgent.prompt(text)
        } catch (e: CancellationException) {
            // Abort or teardown: the agent committed its terminal state,
            // which the manager appended inline.
            throw e
        } catch (e: SessionError) {
            if (e.code == SessionErrorCode.AUTH) {
                // Preflight rejection: nothing was persisted or sent.
                setError(ERROR_PROMPT_AUTH)
            } else {
                setError(ERROR_SESSION_SAVE, e)
            }
        } catch (e: IllegalStateException) {
            setError(ERROR_ALREADY_STREAMING)
        } catch (e: Exception) {
            // The run already failed and committed its terminal state; a
            // storage failure here is a save failure (the in-memory tree
            // keeps its entries).
            setError(ERROR_SESSION_SAVE, e)
        }
    }

    /**
     * Re-reads the session list — the drawer's refresh point, running when a
     * session file changed. A read failure degrades to the previous list
     * (the drawer is advisory state).
     */
    private suspend fun refreshSessionSummaries() {
        val summaries = try {
            sessionSource.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordDegradation("session_summaries", e)
            return
        }
        _uiState.update { it.copy(sessionSummaries = summaries) }
        sessionSearch.onSummariesChanged(summaries)
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
     * Records a degradation the ViewModel deliberately absorbs into degraded
     * UI state instead of an error — the credential store failing to read
     * must be distinguishable from an actually-missing credential.
     */
    private fun recordDegradation(operation: String, cause: Throwable) {
        Log.w(TAG, operation, cause)
    }

    /**
     * Surfaces [message] as the UI error and logs message plus [cause] at
     * the single error boundary — the only place otherwise-invisible
     * failures become diagnosable on-device. [message] is a static UI
     * string carrying no secrets.
     */
    private fun setError(message: String, cause: Throwable? = null) {
        _uiState.update { it.copy(error = message) }
        Log.e(TAG, message, cause)
    }

    private companion object {
        private const val TAG = "Pathfinder"

        const val ERROR_INIT = "Could not load chat data"
        const val ERROR_UNKNOWN_MODEL = "Unknown model"
        const val ERROR_MODEL_UNAVAILABLE =
            "That model is no longer available for this account — pick another model"
        const val ERROR_MODEL_SWITCH =
            "Could not switch to that model — check the provider sign-in"
        const val ERROR_PROMPT_AUTH = "Could not send — check the provider sign-in"
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

        /** Actionable, secret-free message naming the still-missing auth prompts. */
        fun missingCredentialError(missing: List<AuthPrompt>): String =
            "Sign-in values are still needed: " +
                missing.joinToString(", ") { prompt -> prompt.message.ifEmpty { prompt.envKey } }
    }
}
