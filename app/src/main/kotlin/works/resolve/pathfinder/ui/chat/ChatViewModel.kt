package works.resolve.pathfinder.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mikepenz.markdown.model.State
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import works.resolve.pathfinder.R
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.auth.oauth.AppForegroundGate
import works.resolve.pathfinder.ai.getSupportedThinkingLevels
import works.resolve.pathfinder.ai.providers.AuthPrompt
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.utils.uuidv7
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.ReadonlySessionManager
import works.resolve.pathfinder.codingagent.core.SessionError
import works.resolve.pathfinder.codingagent.core.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.SessionManager
import works.resolve.pathfinder.codingagent.core.SettingsManager
import works.resolve.pathfinder.codingagent.core.StreamingBehavior
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.runtime.AgentFactory
import works.resolve.pathfinder.ssh.Machine
import works.resolve.pathfinder.ssh.MachineStore
import works.resolve.pathfinder.ssh.SshConnectionHelper
import works.resolve.pathfinder.ssh.SshConnectionProvider
import works.resolve.pathfinder.ssh.TofuHostKeyConfirmer
import works.resolve.pathfinder.tools.websearch.SearchProviderService

/**
 * Chat screen controller. Owns configuration, sessions, and the active
 * [AgentSession]; projects everything into an immutable [ChatUiState] (UDF),
 * except the per-chunk streaming surfaces that live in [streamingState].
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
    private val settingsStore: SettingsRepository,
    /** Shared process-wide settings manager: the only writer of runtime settings fields. */
    private val settingsManager: SettingsManager,
    private val catalog: ProviderCatalog,
    private val authService: ProviderAuthService,
    private val sessionsDir: File,
    /** New session ids; pi's uuidv7 (SessionManager.create's default). */
    private val sessionIdFactory: () -> String = ::uuidv7,
    /** Where session manager IO runs; the test harness keeps it on virtual time. */
    private val sessionIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
    private val machineStore: MachineStore,
    /** Interactive TOFU host-key decisions; surfaced through the pending-host-key prompt. */
    private val hostKeyConfirmer: TofuHostKeyConfirmer,
    /** Process-wide SSH connections; the machines controller tests dials and machine deletion evicts. */
    private val sshConnectionProvider: SshConnectionProvider,
    /** Where bulk transcript parses run; the test harness keeps them on virtual time. */
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    /**
     * Per-chunk streaming projection (see [StreamingUiState]): a StateFlow
     * conflates like the old whole-state republish did, and only the
     * transcript content observes it, so a token updates the streaming row
     * alone.
     */
    private val _streamingState = MutableStateFlow(StreamingUiState())

    val streamingState: StateFlow<StreamingUiState> = _streamingState.asStateFlow()

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

    private val machinesController = MachinesController(
        viewModelScope,
        machineStore,
        SshConnectionHelper(machineStore),
        hostKeyConfirmer,
        ::setError
    )

    val uiState: StateFlow<ChatUiState> =
        combine(
            combine(
                _uiState,
                loginController.flow,
                searchProviders.state,
                sessionSearch.state,
                combine(machinesController.machines, sshConnectionProvider.machine) {
                        machines,
                        selected
                    ->
                    machines to selected
                }
            ) { base, authFlow, searchProviders, sessionSearch, (machines, selectedMachine) ->
                base.copy(
                    authFlow = authFlow,
                    searchProviderOptions = searchProviders.options,
                    machines = machines,
                    selectedMachine = selectedMachine,
                    sessionSearchQuery = sessionSearch.query,
                    sessionSearchSort = sessionSearch.sort,
                    sessionSearchResults = sessionSearch.results
                )
            },
            hostKeyConfirmer.pending,
            machinesController.machineTest,
            providerCredentials.state,
            modelSettings.state
        ) { base, pendingHostKey, machineTest, credentials, modelSettings ->
            base.copy(
                pendingHostKey = pendingHostKey,
                machineTest = machineTest,
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
    private var agentRunStateJob: Job? = null

    /** Read view over the bound session's tree (pi's ReadonlySessionManager); null while none is bound. */
    private val activeSession: ReadonlySessionManager?
        get() = agent?.sessionManager

    /** Agent transcript instance used for the latest committed-message projection. */
    private var observedAgentMessages: List<Message>? = null

    /** Agent model instance behind the latest [ChatUiState.selectedModel] projection. */
    private var observedAgentModel: Model? = null

    /** Transcript parse cache (see [TranscriptMarkdown]). */
    private val transcriptMarkdown = TranscriptMarkdown()

    /**
     * Delta fold of the streaming assistant message (null while none
     * streams); see [StreamingMessageFold].
     */
    private var streamingFold: StreamingMessageFold? = null

    /**
     * Conflated signal that the streaming tail grew. The collector
     * materializes the tail text once per main-loop pass — the per-frame
     * ceiling — instead of once per delta; the fold's appends stay O(delta).
     */
    private val tailGrew = MutableStateFlow(0L)

    /**
     * Unsent input per session, synced only at [activateSession] boundaries:
     * the outgoing draft is stashed under its session, the incoming session's
     * draft is loaded. Divergence from pi, whose single process-global editor
     * survives session switches: drafts must stay with their conversation —
     * especially tree re-edit text, which belongs to the node it came from.
     */
    private val sessionDrafts = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            tailGrew.collect {
                val fold = streamingFold ?: return@collect
                _streamingState.update { state -> state.copy(streaming = fold.snapshot()) }
            }
        }
        viewModelScope.launch { initialize() }
        // The one-time summary build runs beside startup: Ready publishes
        // without waiting on it.
        viewModelScope.launch { buildSessionSummaries() }
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

    // ---- Machines (Settings ▸ Machines) ----

    /**
     * Persists the machine selection. Not busy-rejected:
     * like a model pick, it applies to the next tool call. A store failure
     * surfaces a settings-save error while the persisted selection keeps
     * its previous value.
     */
    fun selectMachine(machineId: String) {
        viewModelScope.launch {
            try {
                settingsStore.setSelectedMachineId(machineId)
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

    /** Creates a machine with a freshly generated keypair (see [MachinesController.addMachine]). */
    fun addMachine(address: String, port: Int, username: String, cwd: String) =
        machinesController.addMachine(address, port, username, cwd)

    /** Persists edited connection fields of a machine. */
    fun updateMachine(machine: Machine) = machinesController.updateMachine(machine)

    /** Deletes a machine and its keypair, plus its cached connection. */
    fun removeMachine(id: String) {
        machinesController.removeMachine(id)
        viewModelScope.launch { sshConnectionProvider.evict(id) }
    }

    /**
     * Runs a connection test against [machineId] from the machine form; progress
     * and the result land in [ChatUiState.machineTest].
     */
    fun testMachineConnection(machineId: String) =
        machinesController.testMachineConnection(machineId)

    fun send() {
        viewModelScope.launch { sendInternal() }
    }

    fun stop() {
        viewModelScope.launch { agent?.abort() }
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
            // between them and the publish below. The branch's parses
            // prefetch off the main thread, like session activation.
            //
            // Navigation never changes the running model: pi's navigateTree
            // rebuilds only the transcript. A branch's folded model re-applies
            // at the next session load, not on navigation.
            val rows = withContext(defaultDispatcher) {
                projectCommitted(
                    session.agent.state.value.messages,
                    manager.getBranch(),
                    transcriptMarkdown::parse
                )
            }
            _uiState.update {
                it.copy(
                    // A typed draft is never clobbered by navigation; the
                    // re-edit text lands only in an empty draft.
                    draft = if (it.draft.isBlank()) result.editorText ?: it.draft else it.draft,
                    messages = rows,
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
                val manager = SessionManager.create(
                    sessionsDir,
                    idFactory = sessionIdFactory,
                    ioDispatcher = sessionIoDispatcher
                )
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
                val manager = openSession(file)
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
            val runtime = settingsManager.getSettings()
            refreshCredentialSurfaces()

            // NeedsConfiguration means exactly "no configured provider at
            // all"; once any provider credential resolves, the app enters
            // the chat directly with a derived initial model.
            if (providerCredentials.state.value.modelOptions.isEmpty()) {
                _uiState.update {
                    it.copy(
                        status = ChatStatus.NeedsConfiguration,
                        startKey = ProvidersNavKey,
                        showThinking = appSettings.showThinking
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

            val manager = resolveSession(appSettings.activeSessionId)
            // Build the agent before committing any state: a factory failure
            // must never leave a Ready UI or persisted active-session id.
            val newAgent = tryCreateAgent(manager)
            if (newAgent == null) {
                _uiState.update {
                    it.copy(
                        status = ChatStatus.Failed,
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
                    showThinking = appSettings.showThinking
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
     * One-time background build of the drawer's summary list — the only
     * decode of every session file (Ready publishes without waiting on it,
     * and [patchActiveSessionSummary] keeps the list current from then on).
     * Sets [ChatUiState.sessionSummariesLoaded] even when the list is empty.
     */
    private suspend fun buildSessionSummaries() {
        val summaries = SessionManager.list(sessionsDir, ioDispatcher = sessionIoDispatcher)
        _uiState.update {
            it.copy(sessionSummaries = summaries, sessionSummariesLoaded = true)
        }
        sessionSearch.onSummariesChanged(summaries)
    }

    /**
     * pi's continueRecent: the stored active session, else the most recently
     * modified session file, else a new one. The most-recent fallback ranks
     * by file mtime — pi's continueRecent shape — not the message-derived
     * `modified` the drawer rows sort by. The stored id can point at a
     * never-flushed session (process death before any assistant committed)
     * — no file matches and the flow falls through exactly as for any other
     * missing session.
     */
    private suspend fun resolveSession(activeSessionId: String?): SessionManager {
        if (activeSessionId != null) {
            SessionManager.findSessionById(sessionsDir, activeSessionId, sessionIoDispatcher)
                ?.let { file -> openSession(file)?.let { return it } }
        }
        SessionManager.findMostRecentSession(sessionsDir, sessionIoDispatcher)
            ?.let { file -> openSession(file)?.let { return it } }
        return SessionManager.create(
            sessionsDir,
            idFactory = sessionIdFactory,
            ioDispatcher = sessionIoDispatcher
        )
    }

    /**
     * Opens [file], or null when it is not a session (a file the user
     * cannot open must never block startup); [SessionErrorCode.STORAGE]
     * failures surface.
     */
    private suspend fun openSession(file: File): SessionManager? = try {
        SessionManager.open(file, idFactory = sessionIdFactory, ioDispatcher = sessionIoDispatcher)
    } catch (e: SessionError) {
        when (e.code) {
            SessionErrorCode.STORAGE -> throw e

            else -> {
                logger.warn("session_open_skipped: file={}", file.name, e)
                null
            }
        }
    }

    // ---- session / agent lifecycle ----

    /**
     * Makes [session] active with a prebuilt [agent]: persists the active id,
     * binds the agent, and returns to the chat surface. Only called after
     * the factory accepted the settings. Returns false when persisting the
     * active id fails; in that case nothing is committed.
     *
     * Summaries are not touched here: activation changes no session file;
     * [AgentEvent.MessageEnd] patches the active row when one changes.
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
        // Prefetch the incoming conversation's parses off the main thread
        // (dropping the outgoing session's cache) before anything binds, so
        // the first Ready frame renders fully. This suspension stays ahead
        // of the binding, keeping the no-suspend window below intact.
        transcriptMarkdown.clear()
        withContext(defaultDispatcher) {
            projectCommitted(
                agent.state.value.messages,
                conversation.getBranch(),
                transcriptMarkdown::parse
            )
        }
        // Do not suspend between binding and publishing the session id:
        // collection can start immediately, and a frame must never render
        // incoming messages with the outgoing session's scroll state.
        bindAgent(agent)
        streamingFold = null
        _streamingState.value = StreamingUiState()
        _uiState.update {
            it.copy(
                activeSessionId = manager.getSessionId(),
                startKey = ChatNavKey,
                navigationEpoch = it.navigationEpoch + 1,
                messages = projectCommitted(
                    agent.state.value.messages,
                    conversation.getBranch(),
                    transcriptMarkdown::parse
                ),
                treeRows = buildTreeRows(
                    conversation.getTree(),
                    conversation.getLeafId(),
                    it.treeFilter
                ),
                queued = QueuedMessagesUi(),
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
            resolveSession(settingsStore.currentSettings().activeSessionId)
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
        agentRunStateJob?.cancel()
        agent = newAgent
        observedAgentMessages = null
        observedAgentModel = null
        streamingFold = null
        agentStateJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            newAgent.state.collect { state -> onAgentState(state) }
        }
        // Session run-active state (pi's session isStreaming) spans the
        // whole prompt cycle — retry backoff and inter-run compaction
        // included, where the agent's own per-run flag reports idle — so the
        // busy projection is driven from here rather than
        // [AgentState.isStreaming]. StateFlow replay also lands a binding
        // made mid-run in the busy state.
        agentRunStateJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            newAgent.isStreaming.collect { runActive ->
                _uiState.update { it.copy(isStreaming = runActive) }
                if (agentRunActive && !runActive) scheduleSummaryPatch()
                agentRunActive = runActive
            }
        }
        // Events are zero-replay flow: the subscriber must be bound before
        // any prompt starts. MessageUpdate is the per-chunk hot path: it
        // feeds the streaming fold directly and touches nothing else, so
        // every handled event stays on Main.immediate. The agent reduces
        // state before emitting each event, and both collectors run on
        // Main.immediate, so onAgentState and onAgentEvent observe agent
        // emissions in FIFO order.
        agentEventsJob = viewModelScope.launch {
            newAgent.events.collect { event -> onAgentEvent(event) }
        }
    }

    /** Projects session lifecycle events into transient UI surfaces. */
    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AutoRetryStart -> _uiState.update {
                it.copy(retryStatus = AutoRetryStatus(event.attempt, event.maxAttempts))
            }

            is AgentEvent.AutoRetryEnd -> _uiState.update { it.copy(retryStatus = null) }

            is AgentEvent.QueueUpdate -> _uiState.update {
                it.copy(queued = QueuedMessagesUi(event.steering, event.followUp))
            }

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
                val partial = event.partialResult.content.textContent()
                if (partial.isNotEmpty()) {
                    _streamingState.update {
                        it.copy(toolPartials = it.toolPartials + (event.toolCallId to partial))
                    }
                }
            }

            is AgentEvent.ToolExecutionEnd -> _streamingState.update {
                if (event.toolCallId in it.toolPartials) {
                    it.copy(toolPartials = it.toolPartials - event.toolCallId)
                } else {
                    it
                }
            }

            // The streaming fold is driven by the assistant message
            // lifecycle: the start's boundary partial seeds it, MessageUpdate
            // feeds boundaries and deltas into it.
            is AgentEvent.MessageStart -> {
                val message = event.message
                if (message is AssistantMessage) {
                    streamingFold = StreamingMessageFold(transcriptMarkdown::parse)
                        .apply { onBoundary(message) }
                    publishStreamingFold()
                }
            }

            is AgentEvent.MessageUpdate -> when (val streamEvent = event.assistantMessageEvent) {
                is AssistantMessageEvent.Boundary -> {
                    streamingFold?.onBoundary(streamEvent.partial)
                    publishStreamingFold()
                }

                is AssistantMessageEvent.TextDelta -> {
                    streamingFold?.appendText(streamEvent.contentIndex, streamEvent.delta)
                    tailGrew.update { it + 1L }
                }

                is AssistantMessageEvent.ThinkingDelta -> {
                    streamingFold?.appendThinking(streamEvent.contentIndex, streamEvent.delta)
                    tailGrew.update { it + 1L }
                }

                is AssistantMessageEvent.ToolCallDelta -> Unit

                is AssistantMessageEvent.Start,
                is AssistantMessageEvent.Done,
                is AssistantMessageEvent.Error -> Unit
            }

            // Re-project on tree growth, not agent-transcript growth: an
            // auto-retry or overflow recovery removes the error message from
            // agent state while the append-only tree keeps it. A message may
            // also create the session file (or land in an existing one), so
            // the active session's drawer row is patched here —
            // model/thinking appends do not change any observable summary
            // field. This is also where a retained streaming row hands off to
            // its committed row (see [onAgentState]): the two flow updates run
            // in one synchronous Main block with no suspension between them,
            // so Compose observes the committed row and the cleared streaming
            // row in a single recomposition.
            is AgentEvent.MessageEnd -> {
                _uiState.update {
                    it.copy(
                        messages = projectCommittedFromLiveState(),
                        treeRows = treeRows(it.treeFilter)
                    )
                }
                // Lands the committed row and drops the streaming fold in
                // the same synchronous Main block, so Compose observes the
                // committed row and the cleared streaming row in a single
                // recomposition.
                streamingFold = null
                _streamingState.update { it.copy(streaming = null) }
                scheduleSummaryPatch()
            }

            // The session emits message_end before appending to the tree
            // (pi's order), so a message_end projection may still see the
            // branch without its newest message (rendered live-keyed in the
            // meantime); turn_end follows with the append settled, which
            // re-keys the row onto its entry id.
            is AgentEvent.TurnEnd -> _uiState.update {
                it.copy(
                    messages = projectCommittedFromLiveState(),
                    treeRows = treeRows(it.treeFilter)
                )
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
     * session re-emits the session event, but the session appends to the
     * conversation only after that re-emission (pi's order), so a
     * message_end projection can see a tree without the newest message —
     * projected live-keyed — and the follow-up turn_end projection re-keys
     * it once the append has settled.
     */
    private fun projectCommittedFromLiveState(): List<TranscriptRow> = projectCommitted(
        agent?.state?.value?.messages.orEmpty(),
        activeSession?.getBranch().orEmpty(),
        transcriptMarkdown::parse
    )

    private fun onAgentState(state: AgentState) {
        // AgentState uses copy-on-write transcript lists. Streaming chunks
        // never touch this projection (they flow through the MessageUpdate
        // fold in [onAgentEvent]), so it rebuilds only when the committed
        // transcript or model actually changes.
        val committedProjection = if (state.messages === observedAgentMessages) {
            null
        } else {
            projectCommitted(
                state.messages,
                activeSession?.getBranch().orEmpty(),
                transcriptMarkdown::parse
            )
        }
        observedAgentMessages = state.messages
        // Same reference-stability trick for the model chip: the model
        // instance changes only on setModel, so neither the catalog
        // projection nor the thinking-level lookup runs per token.
        val modelChanged = state.model !== observedAgentModel
        val modelProjection = if (modelChanged) modelSettings.modelOption(state.model) else null
        val thinkingLevels = if (modelChanged) getSupportedThinkingLevels(state.model) else null
        observedAgentModel = state.model
        _uiState.update {
            it.copy(
                messages = committedProjection ?: it.messages,
                selectedModel = modelProjection ?: it.selectedModel,
                thinkingLevel = state.thinkingLevel,
                availableThinkingLevels = thinkingLevels ?: it.availableThinkingLevels
            )
        }
    }

    /** Publishes the streaming fold's current projection, boundaries and deltas alike. */
    private fun publishStreamingFold() {
        val fold = streamingFold ?: return
        _streamingState.update { it.copy(streaming = fold.snapshot()) }
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
        if (state.status != ChatStatus.Ready) return
        val text = state.draft.trim()
        val currentAgent = agent
        if (text.isEmpty() || currentAgent == null) return

        _uiState.update { it.copy(draft = "") }
        // Like pi's editor submit during streaming: route through prompt()
        // with steering behavior, which enqueues for delivery before the
        // next provider request.
        val streamingBehavior = if (state.isStreaming) StreamingBehavior.STEER else null
        try {
            currentAgent.prompt(text, streamingBehavior)
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

    /** True while the bound session's prompt cycle is active; the settle transition triggers a patch. */
    private var agentRunActive = false

    /**
     * Patches the active session's drawer row with one single-file read.
     * MessageEnds mid-run return (the run-idle transition in [onAgentState]
     * patches); one landing when already idle patches immediately —
     * whichever fires after the final file append reads the complete file.
     * Overlapping reads at run boundaries race harmlessly: they read the
     * same file, and the next trigger re-lands the row.
     */
    private fun scheduleSummaryPatch() {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch { patchActiveSessionSummary() }
    }

    /**
     * Re-reads only the active session's file and insert-or-replaces its
     * drawer row. The sessions dir is app-private and only the active
     * session's manager writes to it, so no other row can have changed
     * since the one-time [buildSessionSummaries]; an unflushed session
     * (null session file) or an unreadable file leaves the list as-is.
     */
    private suspend fun patchActiveSessionSummary() {
        val file = activeSession?.getSessionFile() ?: return
        val info = SessionManager.buildSessionInfo(file, sessionIoDispatcher) ?: return
        _uiState.update { state ->
            state.copy(
                sessionSummaries = (state.sessionSummaries.filterNot { it.id == info.id } + info)
                    .sortedByDescending { it.modified }
            )
        }
        sessionSearch.onSummariesChanged(_uiState.value.sessionSummaries)
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

/**
 * UI fold of one streaming assistant message, driven by the agent's
 * message lifecycle: boundary events (message and part start/end) publish
 * from their accurate snapshots — finalized parts commit into
 * [StreamingMessageUi.blocks] and an opening tail re-seeds from its
 * scaffold — while deltas append O(delta) into the tail buffer and publish
 * per main-loop pass via the [ChatViewModel] tail pump. Deltas for a block
 * that is not the renderable tail are dropped, matching the renderer's
 * last-part-only tail (a text block resumed after a tool call started
 * becomes visible at its end boundary).
 */
private class StreamingMessageFold(private val parse: (String) -> State) {
    private var blocks: List<MarkdownBlock> = emptyList()
    private var tailIndex = -1
    private var tailThinking = false
    private val tailText = StringBuilder()
    private var hasBody = false
    private var errorMessage: String? = null

    fun onBoundary(partial: AssistantMessage) {
        val content = partial.content
        val tail = growingTailIndex(content)
        blocks = buildMarkdownBlocks(
            (if (tail >= 0) content.subList(0, tail) else content).toList(),
            parse
        )
        tailIndex = tail
        tailThinking = tail >= 0 && content[tail] is ThinkingContent
        tailText.clear()
        when (val open = content.getOrNull(tail)) {
            is TextContent -> tailText.append(open.text)
            is ThinkingContent -> tailText.append(open.thinking)
            else -> {}
        }
        hasBody = content.any {
            it is ThinkingContent || (it is TextContent && it.text.isNotBlank())
        }
        errorMessage = partial.errorMessage
    }

    fun appendText(contentIndex: Int, delta: String) {
        if (contentIndex != tailIndex) return
        tailText.append(delta)
        if (delta.any { !it.isWhitespace() }) hasBody = true
    }

    fun appendThinking(contentIndex: Int, delta: String) {
        if (contentIndex != tailIndex) return
        tailText.append(delta)
        hasBody = true
    }

    fun snapshot(): StreamingMessageUi = StreamingMessageUi(
        blocks = blocks,
        tail = if (tailIndex >= 0) {
            StreamingTailUi(tailIndex, tailThinking, tailText.toString())
        } else {
            null
        },
        hasBody = hasBody,
        errorMessage = errorMessage
    )
}
