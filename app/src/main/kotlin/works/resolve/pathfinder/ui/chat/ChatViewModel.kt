package works.resolve.pathfinder.ui.chat

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
import org.slf4j.LoggerFactory
import works.resolve.pathfinder.R
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
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
import works.resolve.pathfinder.codingagent.core.SettingsManager
import works.resolve.pathfinder.data.sessions.SessionSource
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.runtime.AgentFactory
import works.resolve.pathfinder.ssh.SshConnectionHelper
import works.resolve.pathfinder.ssh.SshConnectionProvider
import works.resolve.pathfinder.ssh.SshHost
import works.resolve.pathfinder.ssh.SshHostStore
import works.resolve.pathfinder.ssh.TofuHostKeyConfirmer
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
    private val settingsStore: SettingsStore,
    /** Shared process-wide settings manager: the only writer of runtime settings fields. */
    private val settingsManager: SettingsManager,
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
    private val appForegroundGate: AppForegroundGate,
    private val searchProviderService: SearchProviderService,
    private val sshHostStore: SshHostStore,
    /** Interactive TOFU host-key decisions; surfaced through the pending-host-key prompt. */
    private val hostKeyConfirmer: TofuHostKeyConfirmer,
    /** Process-wide SSH connections; the hosts controller tests dials and host deletion evicts. */
    private val sshConnectionProvider: SshConnectionProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    /** Mirror of the persisted SSH host selection; the provider applies it to tool dials. */
    private val selectedSshHostId = MutableStateFlow<String?>(null)

    private val loginController = ProviderLoginController(
        scope = viewModelScope,
        authService = authService,
        onLoginSucceeded = { onCredentialStored() },
        onLoginFailed = { cause -> setError(UiString(R.string.error_auth_login), cause) }
    )

    private val providerCredentials = ProviderCredentialsController(
        viewModelScope,
        catalog,
        authService,
        loginController::busy,
        ::onCredentialStored,
        ::setError
    )

    private val modelSettings = ModelSettingsController(
        viewModelScope,
        settingsManager,
        catalog,
        modelResolver,
        { agent },
        { providerCredentials.state.value.modelOptions },
        providerCredentials::availableModels,
        ::reprojectTreeRows,
        ::setError
    )

    private val searchProviders = SearchProviderController(
        scope = viewModelScope,
        service = searchProviderService,
        onError = { message, cause -> setError(message, cause) }
    )

    private val sessionSearch = SessionSearchController()

    private val sshHosts = SshHostsController(
        viewModelScope,
        sshHostStore,
        SshConnectionHelper(sshHostStore),
        hostKeyConfirmer,
        ::setError
    )

    /** The effective host for display, resolved like the provider's effectiveHostId. */
    private val selectedSshHost =
        combine(selectedSshHostId, sshHosts.hosts) { id, hosts ->
            id?.let { selected -> hosts.firstOrNull { it.id == selected } }
                ?: hosts.singleOrNull()
                ?: hosts.firstOrNull()
        }

    val uiState: StateFlow<ChatUiState> =
        combine(
            combine(
                _uiState,
                loginController.flow,
                searchProviders.state,
                sessionSearch.state,
                combine(sshHosts.hosts, selectedSshHost) { hosts, selected -> hosts to selected }
            ) { base, authFlow, searchProviders, sessionSearch, (hosts, selectedHost) ->
                base.copy(
                    authFlow = authFlow,
                    searchProviderOptions = searchProviders.options,
                    sshHosts = hosts,
                    selectedSshHost = selectedHost,
                    sessionSearchQuery = sessionSearch.query,
                    sessionSearchSort = sessionSearch.sort,
                    sessionSearchResults = sessionSearch.results
                )
            },
            hostKeyConfirmer.pending,
            sshHosts.hostTest,
            providerCredentials.state,
            modelSettings.state
        ) { base, pendingHostKey, hostTest, credentials, modelSettings ->
            base.copy(
                pendingHostKey = pendingHostKey,
                hostTest = hostTest,
                providerOptions = credentials.providerOptions,
                modelOptions = credentials.modelOptions,
                defaultModel = modelSettings.defaultModel,
                defaultThinkingLevel = modelSettings.defaultThinkingLevel,
                enabledModels = modelSettings.enabledModels
            )
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value)

    /**
     * Runtime model settings (startup default, thinking default, model
     * scope) live on the shared [settingsManager]; the running model lives
     * on the bound [AgentSession] — its branch fold at load,
     * [ModelSettingsController.selectModel] thereafter. App-owned values (active session id,
     * show-thinking) flow through [settingsStore].
     */
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
        modelSettings.selectModel(providerId, modelId)
    }

    /**
     * Persists the startup default provider+model without switching the
     * live session (pi: editing the settings field directly, not the
     * picker gesture). Never touches the model scope: the scope-append of
     * the picker gesture lives in AgentSession's persist path, which this
     * action deliberately bypasses.
     */
    fun saveStartupDefault(providerId: String, modelId: String) {
        modelSettings.saveStartupDefault(providerId, modelId)
    }

    /**
     * Curates which models the picker offers (all of them while no scope
     * is stored); never touches the running model. A FULL selection
     * persists as the unset scope; an EMPTY selection persists as an empty
     * list (which behaves as no scope downstream), as in pi.
     */
    fun toggleModelScope(providerId: String, modelId: String, checked: Boolean) {
        modelSettings.toggleModelScope(providerId, modelId, checked)
    }

    /**
     * Switches the live session's thinking level. Not busy-rejected: like
     * pi, a mid-stream pick is safe — the active run keeps its start-of-run
     * level and the switch applies to the next prompt. Does NOT persist the
     * default; that lives in [setThinkingLevelDefault].
     */
    fun selectThinkingLevel(level: ModelThinkingLevel) {
        modelSettings.selectThinkingLevel(level)
    }

    /**
     * Persists the default thinking level. With a live session this is one
     * gesture (pi's order): switch the session and persist the REQUESTED
     * level after, so a failed write leaves the session switched and a
     * clamped run still stores what was asked. The stored default seeds
     * sessions without a recorded branch level (the createAgentSession
     * factory) and is re-applied on model switches by the session itself
     * ([AgentSession.setModel]).
     */
    fun setThinkingLevelDefault(level: ModelThinkingLevel) {
        modelSettings.setThinkingLevelDefault(level)
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
    ) = providerCredentials.saveCredential(providerId, apiKeyInput, envInputs)

    /**
     * Forgets the credential for [providerId]. Never tears down sessions or
     * the agent (credentials are read per request); only the derived status
     * surfaces are refreshed.
     */
    fun removeProviderCredential(providerId: String) =
        providerCredentials.removeCredential(providerId)

    /** Re-reads credentials and recomputes the derived provider/model surfaces. */
    fun refreshProviderStatus() {
        viewModelScope.launch {
            try {
                refreshCredentialSurfaces()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(UiString(R.string.error_credential_save), e)
            }
        }
    }

    /**
     * Auth prompts for a provider's credential form, in catalog order: the
     * first prompt is the API key (secret); later prompts fill env slots.
     * Catalog data as-is — only envKey/message/secret exist on it.
     */
    fun providerAuthPrompts(providerId: String): List<AuthPrompt> =
        providerCredentials.providerAuthPrompts(providerId)

    /**
     * The provider's selectable auth methods, or an empty list for an
     * unknown provider. Never touches credentials.
     */
    fun providerAuthMethods(providerId: String): List<AuthMethodInfo> =
        providerCredentials.providerAuthMethods(providerId)

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
                settingsStore.setShowThinking(enabled)
                _uiState.update { it.copy(showThinking = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(UiString(R.string.error_settings_save), e)
            }
        }
    }

    // ---- SSH hosts (Settings ▸ SSH hosts) ----

    /**
     * Switches the process-wide SSH host selection. Not busy-rejected:
     * like a model pick, it applies to the next tool call. A store failure
     * surfaces a settings-save error while the in-memory selection keeps
     * the picked host.
     */
    fun selectSshHost(hostId: String) {
        selectedSshHostId.value = hostId
        sshConnectionProvider.select(hostId)
        viewModelScope.launch {
            try {
                settingsStore.setSelectedSshHostId(hostId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(UiString(R.string.error_settings_save), e)
            }
        }
    }

    /** Trusts the pending unknown-host-key request (TOFU first connect). */
    fun trustHostKey() = hostKeyConfirmer.answer(trust = true)

    /** Refuses the pending unknown-host-key request; also the dialog-dismiss path (fail closed). */
    fun refuseHostKey() = hostKeyConfirmer.answer(trust = false)

    /** Creates an SSH host with a freshly generated keypair (see [SshHostsController.addHost]). */
    fun addSshHost(address: String, port: Int, username: String, cwd: String) =
        sshHosts.addHost(address, port, username, cwd)

    /** Persists edited connection fields of an SSH host. */
    fun updateSshHost(host: SshHost) = sshHosts.updateHost(host)

    /** Deletes an SSH host and its keypair, plus its cached connection. */
    fun removeSshHost(id: String) {
        sshHosts.removeHost(id)
        viewModelScope.launch { sshConnectionProvider.evict(id) }
    }

    /**
     * Runs a connection test against [hostId] from the host form; progress
     * and the result land in [ChatUiState.hostTest].
     */
    fun testSshHostConnection(hostId: String) = sshHosts.testHostConnection(hostId)

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
                setError(UiString(R.string.error_busy))
                return@launch
            } catch (e: IllegalArgumentException) {
                setError(UiString(R.string.error_entry_missing))
                return@launch
            } catch (e: SessionError) {
                setError(UiString(R.string.error_session_save), e)
                return@launch
            }
            if (result.cancelled) return@launch
            if (result.outcome == AgentSession.NavigationOutcome.NO_OP) {
                setError(UiString(R.string.error_already_at_point))
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
                val newAgent = tryCreateAgent(manager) ?: return@launch
                if (!activateSession(manager, newAgent)) return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(UiString(R.string.error_session_create), e)
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
                        setError(UiString(R.string.error_session_missing))
                        return@launch
                    }
                val manager = sessionSource.open(file)
                if (manager == null) {
                    setError(UiString(R.string.error_session_missing))
                    return@launch
                }
                val newAgent = tryCreateAgent(manager) ?: return@launch
                if (!activateSession(manager, newAgent)) return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(UiString(R.string.error_session_load), e)
            }
        }
    }

    // ---- initialization ----

    private suspend fun initialize() {
        try {
            val appSettings = settingsStore.currentSettings()
            selectedSshHostId.value = appSettings.selectedSshHostId
            sshConnectionProvider.select(appSettings.selectedSshHostId)
            val runtime = settingsManager.getSettings()
            val summaries = try {
                sessionSource.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordDegradation("session_summaries", e)
                emptyList()
            }
            refreshCredentialSurfaces()

            // NeedsConfiguration means exactly "no configured provider at
            // all"; once any provider credential resolves, the app enters
            // the chat directly with a derived initial model.
            if (providerCredentials.state.value.modelOptions.isEmpty()) {
                _uiState.update {
                    it.copy(
                        status = ChatStatus.NeedsConfiguration,
                        startKey = ProvidersNavKey,
                        showThinking = appSettings.showThinking,
                        sessionSummaries = summaries
                    )
                }
                return
            }

            // A saved default known to the catalog but absent from the
            // credential-filtered set surfaces a safe error while the
            // derived replacement runs; a corrupt/unknown model id is NOT
            // "unavailable" and adds no error.
            val defaultProvider = runtime.defaultProvider
            val defaultModelId = runtime.defaultModel
            val defaultAvailable = providerCredentials.state.value.modelOptions.any {
                it.providerId == defaultProvider && it.modelId == defaultModelId
            }
            if (!defaultAvailable && !defaultProvider.isNullOrBlank() &&
                !defaultModelId.isNullOrBlank() &&
                catalog.getProvider(defaultProvider!!)?.model(defaultModelId) != null
            ) {
                setError(UiString(R.string.error_model_unavailable))
            }

            val manager = resolveSession(appSettings.activeSessionId, summaries)
            // Build the agent before committing any state: a factory failure
            // must never leave a Ready UI or persisted active-session id.
            val newAgent = tryCreateAgent(manager)
            if (newAgent == null) {
                _uiState.update {
                    it.copy(
                        status = ChatStatus.Failed,
                        sessionSummaries = summaries,
                        error = UiString(R.string.error_config_invalid)
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
                    showThinking = appSettings.showThinking,
                    sessionSummaries = summaries
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(UiString(R.string.error_init), e)
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
        activeSessionId: String?,
        summaries: List<SessionInfo>
    ): SessionManager {
        activeSessionId?.let { id ->
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
            settingsStore.setActiveSessionId(manager.getSessionId())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(UiString(R.string.error_settings_save), e)
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
                toolPartials = emptyMap(),
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
            resolveSession(
                settingsStore.currentSettings().activeSessionId,
                sessionSource.list()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(UiString(R.string.error_session_create), e)
            return null
        }
        val newAgent = tryCreateAgent(manager) ?: return null
        return manager to newAgent
    }

    /**
     * Builds a session through the core createAgentSession factory (which
     * owns model resolution, restoration, and seeding) or null (with a safe
     * error surfaced) when the factory rejects the configuration. A model
     * fallback (the session's saved model could not be restored) surfaces
     * as a safe error while the fallback model runs.
     */
    private suspend fun tryCreateAgent(sessionManager: SessionManager): AgentSession? {
        val result = try {
            agentFactory.create(sessionManager)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(UiString(R.string.error_config_invalid), e)
            return null
        }
        result.modelFallback?.let { fallback ->
            val failed = "${fallback.failedProvider}/${fallback.failedModelId}"
            setError(
                if (fallback.usedProvider != null) {
                    UiString(
                        R.string.error_model_restore_using,
                        listOf(
                            failed,
                            "${fallback.usedProvider}/${fallback.usedModelId}"
                        )
                    )
                } else {
                    UiString(R.string.error_model_restore, listOf(failed))
                }
            )
        }
        // Synchronize web_search against the current Brave credential
        // before anything binds to the session.
        return result.session.also(searchProviders::applyTo)
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

            // Streaming tool output (bash partials): retained until the
            // execution ends; the committed result takes over the row.
            is AgentEvent.ToolExecutionUpdate -> {
                val partial = event.partialResult.content.filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
                if (partial.isNotEmpty()) {
                    _uiState.update {
                        it.copy(toolPartials = it.toolPartials + (event.toolCallId to partial))
                    }
                }
            }

            is AgentEvent.ToolExecutionEnd -> _uiState.update {
                if (event.toolCallId in it.toolPartials) {
                    it.copy(toolPartials = it.toolPartials - event.toolCallId)
                } else {
                    it
                }
            }

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
                scheduleSummariesRefresh()
            }

            else -> Unit
        }
    }

    /** Tree rows over the bound session's current entries and leaf. */
    private fun treeRows(filter: TreeFilter): List<TreeRow> {
        val manager = activeSession ?: return emptyList()
        return buildTreeRows(
            manager.getTree(),
            manager.getLeafId(),
            filter
        )
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
            modelSettings.modelOption(state.model)
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
        // The run went idle: land any summary refresh deferred from mid-run
        // MessageEnds (see [scheduleSummariesRefresh]).
        if (summariesRefreshPending && !state.isStreaming) scheduleSummariesRefresh()
    }

    // ---- intent internals ----

    /** Re-projects tree rows after a session-applied model/thinking change. */
    private fun reprojectTreeRows() {
        _uiState.update { it.copy(treeRows = treeRows(it.treeFilter)) }
    }

    /**
     * Shared post-login success path: refreshes every credential-derived
     * surface — the provider flipping to configured is what closes the
     * credential form — and, while still unconfigured, completes
     * configuration with the resolved initial model and enters the chat
     * directly.
     */
    private suspend fun onCredentialStored() {
        refreshCredentialSurfaces()
        if (_uiState.value.status == ChatStatus.NeedsConfiguration &&
            providerCredentials.state.value.modelOptions.isNotEmpty()
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
            setError(UiString(R.string.error_auth_in_progress))
            return false
        }
        if (providerCredentials.providerAuthMethods(providerId).none { it.type == method.type }) {
            setError(UiString(R.string.error_unknown_provider))
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

    /**
     * Search status first: a provider read failure must not leave it
     * stale, and it must be fresh before any agent creation follows.
     * Search credentials never contribute to the LLM first-run
     * configuration — `search_`-namespaced keys are not catalog
     * provider credentials.
     */
    private suspend fun refreshCredentialSurfaces() {
        searchProviders.refresh()
        providerCredentials.refresh()
        modelSettings.projectSettings()
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
                setError(UiString(R.string.error_prompt_auth))
            } else {
                setError(UiString(R.string.error_session_save), e)
            }
        } catch (e: IllegalStateException) {
            setError(UiString(R.string.error_already_streaming))
        } catch (e: Exception) {
            // The run already failed and committed its terminal state; a
            // storage failure here is a save failure (the in-memory tree
            // keeps its entries).
            setError(UiString(R.string.error_session_save), e)
        }
    }

    private var summariesRefreshJob: Job? = null
    private var summariesRefreshPending = false

    /**
     * Drawer summaries refresh at most once per agent run: MessageEnds
     * while streaming only mark a refresh pending; it runs when the run
     * goes idle (onAgentState) — or immediately when already idle — with at
     * most one refresh in flight and concurrent requests coalesced into a
     * single queued rerun. The heavy part is sessionSource.list(), an
     * O(all session bytes) decode, so per-message refreshes would grow the
     * cost with history.
     */
    private fun scheduleSummariesRefresh() {
        if (_uiState.value.isStreaming || summariesRefreshJob?.isActive == true) {
            summariesRefreshPending = true
            return
        }
        summariesRefreshPending = false
        summariesRefreshJob = viewModelScope.launch { refreshSessionSummaries() }
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
            setError(UiString(R.string.error_busy))
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
        logger.warn(operation, cause)
    }

    /**
     * Surfaces [message] as the UI error and logs message plus [cause] at
     * the single error boundary — the only place otherwise-invisible
     * failures become diagnosable on-device. [message] is a static UI
     * string reference carrying no secrets.
     */
    private fun setError(message: UiString, cause: Throwable? = null) {
        _uiState.update { it.copy(error = message) }
        logger.error(message.toString(), cause)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ChatViewModel::class.java)
    }
}
