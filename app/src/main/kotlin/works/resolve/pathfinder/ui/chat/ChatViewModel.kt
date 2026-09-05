package works.resolve.pathfinder.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt as AuthInteractionPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.CredentialType
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.auth.oauth.AppForegroundGate
import works.resolve.pathfinder.ai.clampThinkingLevel
import works.resolve.pathfinder.ai.getSupportedThinkingLevels
import works.resolve.pathfinder.ai.providers.AuthPrompt
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.SessionError
import works.resolve.pathfinder.codingagent.core.session.SessionInfo
import works.resolve.pathfinder.codingagent.core.session.SessionManager
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry
import works.resolve.pathfinder.data.sessions.SessionSource
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.data.settings.SettingsStore
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
    /** Web-search credential management (Brave only, Scry parity). */
    private val searchProviderService: SearchProviderService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Interactive provider-login state machine; [ProviderLoginController.flow] is the source of truth [ChatUiState.authFlow] mirrors. */
    private val loginController = ProviderLoginController(
        scope = viewModelScope,
        authService = authService,
        onLoginSucceeded = { onCredentialStored() },
        onLoginFailed = { cause -> setError(ERROR_AUTH_LOGIN, cause) }
    )

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

    /** The conversation tree of the bound session's manager; read here for projection. */
    private val activeConversation: Conversation
        get() = agent?.conversation ?: Conversation(emptyList(), null)

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
            } catch (e: SessionError) {
                setError(ERROR_SESSION_SAVE, e)
                return@launch
            } catch (e: Exception) {
                setError(ERROR_THINKING_SWITCH, e)
                return@launch
            }
            updateState { it.copy(treeRows = buildTreeRows(session.conversation, it.treeFilter)) }
        }
    }

    /**
     * Persists the default thinking level. Applies to the live session
     * first and persists after (pi's order), so a failed settings write
     * leaves the session switched. The stored default seeds sessions
     * without a recorded branch level ([seedSession]) and is
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
            currentSettings = currentSettings.copy(defaultThinkingLevel = level)
            updateState { it.copy(defaultThinkingLevel = level) }
            if (session != null) {
                updateState {
                    it.copy(treeRows = buildTreeRows(session.conversation, it.treeFilter))
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
     * safe error. A user message target re-edits — including when it is
     * the current leaf: the leaf moves to its parent (or resets to root)
     * and its text is restored into the draft, so the next send forks as a
     * sibling. Any other target moves the leaf to that entry.
     */
    fun navigateToTreeEntry(id: String) {
        navigateToTreeEntry(id, summarize = false)
    }

    /**
     * Navigation with branch summarization: when [summarize] is set, the
     * abandoned branch segment is summarized and a branch-summary entry is
     * appended at the target position (see [AgentSession.navigateTree]).
     */
    fun navigateToTreeEntry(id: String, summarize: Boolean) {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            // A user-message target is a re-edit even when it is the current
            // leaf (a run that never committed an assistant entry can leave
            // one); only non-user targets are a true no-op at their leaf.
            val reEditTarget =
                (activeConversation.entry(id) as? MessageEntry)?.message is UserMessage
            if (id == activeConversation.leafId && !reEditTarget) {
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
            } catch (e: SessionError) {
                setError(ERROR_SESSION_SAVE, e)
                return@launch
            }
            if (result.cancelled) return@launch
            val updated = session.conversation
            // Navigation never changes the running model: pi's navigateTree
            // rebuilds only the transcript. A branch's folded model re-applies
            // at the next session load, not on navigation.
            updateState {
                it.copy(
                    // A typed draft is never clobbered by navigation; the
                    // re-edit text lands only in an empty draft.
                    draft = if (it.draft.isBlank()) result.editorText ?: it.draft else it.draft,
                    messages = projectCommitted(session.agent.state.value.messages, updated),
                    treeRows = buildTreeRows(updated, it.treeFilter)
                )
            }
        }
    }

    /** Switches the tree-panel filter (in-memory only) and re-projects the rows. */
    fun setTreeFilter(filter: TreeFilter) {
        updateState {
            it.copy(treeFilter = filter, treeRows = buildTreeRows(activeConversation, filter))
        }
    }

    /**
     * Searchable-text corpus snapshot, held only while a query is active
     * (memory bound; pi holds it only while the selector is open).
     * Snapshot-at-activation: list churn reuses it, never rescans.
     */
    private var sessionSearchCorpus: Map<String, String>? = null

    private var sessionSearchScanJob: Job? = null

    /**
     * Updates the drawer session-search query: blank drops the corpus and
     * results; non-blank filters synchronously against the loaded corpus or
     * triggers the single scan that loads it. A scan failure degrades to
     * an empty corpus (results stay empty, no error surfaced).
     */
    fun onSessionSearchQueryChange(query: String) {
        updateState { it.copy(sessionSearchQuery = query) }
        if (query.isBlank()) {
            sessionSearchScanJob?.cancel()
            sessionSearchScanJob = null
            sessionSearchCorpus = null
            updateState {
                it.copy(sessionSearchResults = emptyList(), isSessionSearching = false)
            }
            return
        }
        if (sessionSearchCorpus != null) {
            applySessionSearchFilter()
            return
        }
        if (sessionSearchScanJob?.isActive == true) return
        updateState { it.copy(isSessionSearching = true) }
        sessionSearchScanJob = viewModelScope.launch {
            val corpus = try {
                sessionSource.list().associate { info ->
                    info.id to "${info.id} ${info.allMessagesText}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordDegradation("session_search", e)
                emptyMap()
            }
            sessionSearchCorpus =
                if (_uiState.value.sessionSearchQuery.isBlank()) null else corpus
            updateState { it.copy(isSessionSearching = false) }
            applySessionSearchFilter()
        }
    }

    /** Switches the drawer search sort and re-filters when a query is active. */
    fun setSessionSearchSort(sort: SessionSearchSort) {
        updateState { it.copy(sessionSearchSort = sort) }
        if (_uiState.value.sessionSearchQuery.isNotBlank() && sessionSearchCorpus != null) {
            applySessionSearchFilter()
        }
    }

    /**
     * Sessions absent from the corpus drop out under a query (pi: unscanned
     * sessions don't appear in selector results).
     */
    private fun applySessionSearchFilter() {
        val corpus = sessionSearchCorpus ?: return
        val state = _uiState.value
        if (state.sessionSearchQuery.isBlank()) return
        val entries = state.sessionSummaries.map { summary ->
            SessionSearchEntry(summary.id, summary.modified, corpus[summary.id].orEmpty())
        }
        val matched = filterAndSortSessions(
            entries,
            state.sessionSearchQuery,
            state.sessionSearchSort
        )
        val byId = state.sessionSummaries.associateBy { it.id }
        updateState {
            it.copy(sessionSearchResults = matched.mapNotNull { entry -> byId[entry.id] })
        }
    }

    /** Reapplies the search filter after a summaries refresh, if a query is active against a loaded corpus. */
    private fun refreshSessionSearchResults() {
        if (sessionSearchCorpus != null && _uiState.value.sessionSearchQuery.isNotBlank()) {
            applySessionSearchFilter()
        }
    }

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

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            try {
                val manager = sessionSource.open(sessionId)
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
                updateState {
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
                updateState { it.copy(status = ChatStatus.Failed) }
                return
            }
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

    /**
     * pi's continueRecent: the requested active session, else the most
     * recently modified listed one, else a new one. The stored id can point
     * at a never-flushed session (process death before any assistant
     * committed) — open returns null and the flow falls through exactly as
     * for any other missing session.
     */
    private suspend fun resolveSession(
        settings: ModelSettings,
        summaries: List<SessionInfo>
    ): SessionManager {
        settings.activeSessionId?.let { id ->
            sessionSource.open(id)?.let { return it }
        }
        summaries.firstOrNull()?.let { info ->
            sessionSource.open(info.id)?.let { return it }
        }
        return sessionSource.create()
    }

    // ---- session / agent lifecycle ----

    /**
     * Makes [session] active with a prebuilt [agent]: persists the active id,
     * binds the agent, and returns to the chat surface with a refreshed UI.
     * Only called after the factory accepted the settings. Returns false when
     * persisting the active id fails; in that case nothing is committed.
     */
    private suspend fun activateSession(manager: SessionManager, agent: AgentSession): Boolean {
        try {
            settingsRepository.setActiveSessionId(manager.sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SETTINGS_SAVE, e)
            return false
        }
        val conversation = agent.conversation
        val summaries = refreshSessionSummaries()
        val outgoing = _uiState.value
        outgoing.activeSessionId?.let { id ->
            if (outgoing.draft.isBlank()) {
                sessionDrafts.remove(id)
            } else {
                sessionDrafts[id] =
                    outgoing.draft
            }
        }
        val draft = sessionDrafts[manager.sessionId].orEmpty()
        // Do not suspend between binding and publishing the session id:
        // collection can start immediately, and a frame must never render
        // incoming messages with the outgoing session's scroll state.
        bindAgent(agent)
        updateState {
            it.copy(
                activeSessionId = manager.sessionId,
                startKey = ChatNavKey,
                navigationEpoch = it.navigationEpoch + 1,
                messages = projectCommitted(agent.state.value.messages, conversation),
                streamingMessage = null,
                treeRows = buildTreeRows(conversation, it.treeFilter),
                sessionSummaries = summaries,
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

    /**
     * pi's sdk.ts session-init: resolves the startup model and seeds the
     * session's configuration entries through the manager. [initialModelSettings]
     * resolves the model (the active branch's folded model_change when it
     * carries messages, else findInitialModel's
     * scope/default/first-available order); a fresh session additionally
     * gets a model_change entry recording the resolved model; a session
     * without a thinking_level_change entry on its active path gets the
     * stored default level, else "medium", clamped to the effective model —
     * so both restore on resume. Because any opened session contains an
     * assistant message, the fresh-session branch can only run for created
     * sessions; the seeds buffer harmlessly there until the first assistant
     * commit writes the file.
     */
    private suspend fun seedSession(manager: SessionManager): ModelSettings {
        val conversation = manager.conversation
        val hasExistingSession = conversation.activeMessages().isNotEmpty()
        val base = initialModelSettings(isContinuing = hasExistingSession)
        val seeded = settingsSeededFromFold(base, conversation)
        if (!hasExistingSession && seeded.providerId.isNotBlank() && seeded.modelId.isNotBlank()) {
            manager.appendModelChange(seeded.providerId, seeded.modelId)
        }
        if (conversation.activeEntries().none { it is ThinkingLevelEntry } &&
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
                manager.appendThinkingLevelChange(seededLevel.wire)
            }
        }
        return seeded
    }

    /**
     * pi's findInitialModel order (model-resolver.ts), minus the CLI step:
     * a fresh session takes the first available scoped model, else the saved
     * default while the credential-filtered options still admit it, else the
     * first available model; a continuing session skips the scope step (its
     * branch fold, when present, wins in [settingsSeededFromFold]).
     * Availability is [ChatUiState.modelOptions].
     */
    private fun initialModelSettings(isContinuing: Boolean): ModelSettings {
        val options = _uiState.value.modelOptions
        if (options.isEmpty()) return currentSettings
        if (!isContinuing) {
            val scoped = currentSettings.enabledModels.orEmpty().firstNotNullOfOrNull { ref ->
                options.firstOrNull { option ->
                    "${option.providerId}/${option.modelId}".equals(ref, ignoreCase = true)
                }
            }
            if (scoped != null) {
                return currentSettings.copy(
                    providerId = scoped.providerId,
                    modelId = scoped.modelId
                )
            }
        }
        val defaultAvailable = options.any {
            it.providerId == currentSettings.providerId && it.modelId == currentSettings.modelId
        }
        if (defaultAvailable) return currentSettings
        val first = options.first()
        return currentSettings.copy(providerId = first.providerId, modelId = first.modelId)
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
        sessionManager: SessionManager
    ): AgentSession? = try {
        agentFactory.create(settings, sessionManager)
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
            // not change any observable summary field.
            is AgentEvent.MessageEnd -> {
                updateState {
                    it.copy(
                        messages = projectCommittedAfterSessionMessageEnd(),
                        treeRows = buildTreeRows(activeConversation, it.treeFilter)
                    )
                }
                viewModelScope.launch { refreshSessionSummaries() }
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
        // AgentState uses copy-on-write transcript lists. Streaming chunks
        // change only streamingMessage, so retain the existing projection
        // instead of rebuilding and structurally comparing every committed
        // message (including potentially large tool outputs) for every token.
        val committedProjection = if (state.messages === observedAgentMessages) {
            null
        } else {
            projectCommitted(state.messages, activeConversation)
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
        updateState {
            it.copy(
                messages = committedProjection ?: it.messages,
                selectedModel = modelProjection ?: it.selectedModel,
                pendingTools = pendingToolExecutions(state),
                streamingMessage = (state.streamingMessage as? AssistantMessage)?.let(
                    ::projectStreaming
                ),
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
        } catch (e: SessionError) {
            // A failed model_change append is a save failure, not a switch
            // failure — the agent already switched in memory.
            setError(ERROR_SESSION_SAVE, e)
            return
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
        try {
            session.setThinkingLevel(currentSettings.defaultThinkingLevel ?: session.thinkingLevel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionError) {
            setError(ERROR_SESSION_SAVE, e)
            return
        } catch (e: Exception) {
            setError(ERROR_THINKING_SWITCH, e)
            return
        }
        // The chip follows the agent's state emission from setModel above;
        // only the tree needs re-projecting here.
        updateState {
            it.copy(treeRows = buildTreeRows(session.conversation, it.treeFilter))
        }
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
     * Shared post-login success path: bumps the credential-success epoch so
     * the UI closes the auth screen only after confirmed persistence,
     * refreshes every credential-derived surface, and — while still
     * unconfigured — completes configuration with the resolved initial
     * model and enters the chat directly.
     */
    private suspend fun onCredentialStored() {
        // Only a confirmed persistence bumps this epoch, so the credential
        // form and its typed inputs survive a failed save above.
        updateState { it.copy(credentialSuccessEpoch = it.credentialSuccessEpoch + 1) }

        refreshOptions()
        if (_uiState.value.status == ChatStatus.NeedsConfiguration &&
            _uiState.value.modelOptions.isNotEmpty()
        ) {
            val prepared = prepareAdoption() ?: return
            if (!activateSession(prepared.first, prepared.second)) return
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
        refreshSearchStatus()
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
        updateState {
            it.copy(
                providerOptions = providerOptions,
                modelOptions = modelOptions,
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
            // which the manager appended inline.
            throw e
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
     * Re-reads the session list into [ChatUiState.sessionSummaries]. A read
     * failure degrades to the previous list (the drawer is advisory state);
     * search results refresh when a query is active.
     */
    private suspend fun refreshSessionSummaries(): List<SessionInfo> = try {
        val summaries = sessionSource.list()
        updateState { it.copy(sessionSummaries = summaries) }
        refreshSessionSearchResults()
        summaries
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        recordDegradation("session_summaries", e)
        _uiState.value.sessionSummaries
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
        updateState { it.copy(error = message) }
        Log.e(TAG, message, cause)
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
        private const val TAG = "Pathfinder"

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
    }
}
