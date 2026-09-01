package works.resolve.pathfinder.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import works.resolve.pathfinder.runtime.ChatRuntime
import works.resolve.pathfinder.runtime.ChatRuntimeSession
import works.resolve.pathfinder.runtime.ChatRuntimeState
import works.resolve.pathfinder.runtime.ModelDescriptor
import works.resolve.pathfinder.runtime.ProviderDescriptors
import works.resolve.pathfinder.runtime.ThinkingOption
import works.resolve.pathfinder.runtime.ThinkingOptions
import works.resolve.pathfinder.runtime.CodexOAuthClient
import works.resolve.pathfinder.runtime.CodexLoopbackServer
import works.resolve.pathfinder.runtime.CodexOAuthException
import works.resolve.pathfinder.data.credentials.Credential
import works.resolve.pathfinder.data.credentials.CredentialStore
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.Session
import works.resolve.pathfinder.data.sessions.SessionRepository
import works.resolve.pathfinder.data.sessions.SessionSummary
import works.resolve.pathfinder.diagnostics.DiagnosticEvent
import works.resolve.pathfinder.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat screen controller. Owns configuration, sessions, and the active
 * [ChatRuntimeSession]; projects everything into an immutable [ChatUiState]
 * (UDF).
 *
 * The chat's model is chosen from a picker above the composer over the
 * scoped model set (pi's scoped models): every model of configured
 * providers until curated in Settings, then the explicit set. Selecting a
 * model swaps it on the live session (transcript untouched) and persists
 * it as the startup default; an explicit pick is the only way persisted
 * model settings are ever written. When no usable default exists, the
 * first model of a configured provider is derived for the session.
 * Thinking is configured
 * per model with Koog's own provider parameter values (see
 * [works.resolve.pathfinder.runtime.ThinkingOptions]) and the last choice
 * is persisted per model. Credentials are per-provider API keys stored in
 * the Keystore-backed credential store (one credential per provider,
 * replaced wholesale, removed per provider). Provider auth status
 * is derived live from the credential store — never persisted in
 * settings — and only models of configured providers are ever offered.
 *
 * Transcript persistence runs through a single latest-snapshot pipeline: at
 * most one save per session is in flight, superseded snapshots are coalesced,
 * and session switches wait for pending saves so transcripts always stay with
 * the session they belong to. The persisted unit is the conversation tree
 * itself (entries + leafId), so branch structure survives saves. A failed
 * save surfaces an error and blocks session/config switches; the blocked
 * intent explicitly retries the latest snapshot and only proceeds once it is
 * saved. Snapshot writes are non-cancellable, so ViewModel teardown can
 * never abandon an accepted snapshot.
 *
 * Tree navigation (pi's navigateTree, reduced to no summarization) is a
 * state change on the same conversation: navigating to an assistant entry
 * moves the leaf there; navigating to a user entry implements re-edit
 * semantics — the leaf moves to the entry's parent (or resets to root) and
 * the message text lands in the draft, so the next send appends a sibling.
 *
 * Navigation is state, not effects: an unconfigured app pins
 * [ChatUiState.startKey] to [ProvidersNavKey] (pick a provider and complete
 * its credential); storing the first credential replays initialization,
 * which derives a model and enters the chat. Every intent that should
 * return the user to the chat
 * sets [ChatUiState.startKey] to [ChatNavKey] and bumps
 * [ChatUiState.navigationEpoch] atomically with the rest of the state.
 */
class ChatViewModel(
    private val settingsRepository: SettingsStore,
    private val credentials: CredentialStore,
    private val sessionStore: SessionRepository,
    private val runtime: ChatRuntime,
    private val codexOAuthClient: CodexOAuthClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Current committed configuration; updated on init and successful save. */
    private var currentSettings: ModelSettings = ModelSettings()

    /** Thinking option applied to the current model (see [setThinking]). */
    private var currentThinking: ThinkingOption = ThinkingOption.Default

    private var session: ChatRuntimeSession? = null
    private var sessionStateJob: Job? = null
    private var activeSession: Session? = null

    /**
     * The conversation tree of [activeSession]: the transcript source of
     * truth for persistence and tree navigation, owned by the bound runtime
     * session.
     */
    private val activeConversation: Conversation
        get() = session?.conversation ?: Conversation(emptyList(), null)

    /** Count of active-session entries already persisted. */
    private var persistedEntryCount: Int = 0

    /** Latest unsaved conversation snapshot for its owning session. */
    private var pendingPersist: Pair<Session, Conversation>? = null
    private var persistJob: Job? = null

    /** Runtime-sourced error last projected into the UI, to detect runtime clearing. */
    private var lastRuntimeError: String? = null

    /** In-flight ChatGPT sign-in (device-code poll or browser exchange), if any. */
    private var codexSignInJob: Job? = null

    /** Activity-resumed state; gates browser OAuth networking on modern Android. */
    private val appForegrounded = MutableStateFlow(false)

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

    /** Reports that the app can perform foreground-only network work. */
    fun onAppForegrounded() {
        appForegrounded.value = true
    }

    /** Prevents OAuth network work after another activity covers Pathfinder. */
    fun onAppBackgrounded() {
        appForegrounded.value = false
    }

    /**
     * Swaps the chat's model: applied to the live session immediately and
     * persisted as the startup default. The transcript is untouched and an
     * in-flight response is unaffected — the next prompt executes against
     * the new model, so a pick while streaming is never dropped.
     */
    fun selectModel(option: ModelOption) {
        viewModelScope.launch { selectModelInternal(option) }
    }

    /**
     * Applies a thinking option to the current model: live session plus a
     * per-model persisted preference. An in-flight response is unaffected
     * (same rule as [selectModel]).
     */
    fun setThinking(option: ThinkingOption) {
        viewModelScope.launch { setThinkingInternal(option) }
    }

    /**
     * Adds/removes one model in the scoped picker set. The uncurated default
     * (all configured models shown) materializes into an explicit set on
     * the first edit. Scope edits never touch the live session and are
     * allowed while streaming.
     */
    fun toggleModelScope(option: ModelOption, enabled: Boolean) {
        viewModelScope.launch { toggleModelScopeInternal(option, enabled) }
    }

    /**
     * Stores a fresh API key for the given provider: replaces the stored
     * credential wholesale; a blank key is rejected. The typed key lives only
     * in ephemeral UI memory and is never logged.
     */
    fun saveProviderCredential(option: ProviderOption, apiKey: String) {
        viewModelScope.launch { saveProviderCredentialInternal(option, apiKey) }
    }

    /**
     * Forgets the credential for [providerId]. Never tears down sessions
     * (credentials are read per request); only the derived surfaces refresh.
     */
    fun removeProviderCredential(providerId: String) {
        viewModelScope.launch {
            try {
                credentials.delete(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_CREDENTIAL_SAVE)
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
                setError(ERROR_CREDENTIAL_SAVE)
            }
        }
    }

    /**
     * Starts the ChatGPT device-code sign-in for a ChatGptSignIn provider:
     * requests the device code, shows it, and polls until approved, then
     * stores the token set and reuses the shared credential-success path.
     * The user code lives only in ephemeral UI state and is never logged.
     * Cancellation (see [cancelCodexSignIn]) writes no credential.
     */
    fun beginCodexDeviceSignIn(provider: ProviderOption) {
        // Real race, not a wiring bug: a second tap can land while the first
        // sign-in is still between button press and codexSignIn state.
        if (codexSignInJob?.isActive == true) return
        codexSignInJob = viewModelScope.launch {
            val device = try {
                codexOAuthClient.beginDeviceLogin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CodexOAuthException) {
                setError(e.message ?: ERROR_CODEX_SIGN_IN)
                return@launch
            } catch (e: Exception) {
                Diagnostics.failure(DiagnosticEvent.CODEX_SIGN_IN_UNEXPECTED_FAILURE, e)
                setError(ERROR_CODEX_SIGN_IN)
                return@launch
            }
            updateState { it.copy(codexSignIn = CodexSignInState.Device(device.userCode, device.verificationUri)) }
            try {
                val tokens = codexOAuthClient.awaitDeviceAuthorization(device)
                credentials.set(
                    provider.id,
                    Credential.ChatGptOAuth(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAtEpochMillis = tokens.expiresAtEpochMillis,
                        accountId = tokens.accountId,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: CodexOAuthException) {
                // codexSignIn is non-null here: it was set above, and the only
                // other clearers are this coroutine (which returns on error)
                // and cancelCodexSignIn (which cancels the job first, taking
                // the CancellationException path instead).
                updateDeviceSignIn { it.copy(error = e.message) }
                return@launch
            } catch (e: Exception) {
                Diagnostics.failure(DiagnosticEvent.CODEX_SIGN_IN_UNEXPECTED_FAILURE, e)
                updateDeviceSignIn { it.copy(error = ERROR_CODEX_SIGN_IN) }
                return@launch
            }
            updateState { it.copy(codexSignIn = null) }
            onCredentialStored()
        }
    }

    /**
     * Starts the ChatGPT browser sign-in for a ChatGptSignIn provider (pi's
     * `loginOpenAICodex`): builds the PKCE authorize URL locally (no network
     * yet), binds the loopback redirect listener, and publishes the URL for
     * the UI to open in a browser-backed Custom Tab — the browser shares the
     * user's real login session, so an existing ChatGPT login carries over.
     * The browser's redirect lands on the listener, the code is exchanged,
     * the token set stored, and the shared credential-success path runs.
     * The PKCE verifier lives only in this coroutine and is never logged.
     * Cancellation (see [cancelCodexSignIn]) writes no credential.
     */
    fun beginCodexBrowserSignIn(provider: ProviderOption) {
        // Real race, not a wiring bug: a second tap can land while the first
        // sign-in is still active.
        if (codexSignInJob?.isActive == true) return
        codexSignInJob = viewModelScope.launch {
            val auth = try {
                codexOAuthClient.beginBrowserLogin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.failure(DiagnosticEvent.CODEX_SIGN_IN_UNEXPECTED_FAILURE, e)
                setError(ERROR_CODEX_SIGN_IN)
                return@launch
            }
            val listener = CodexLoopbackServer(auth.state)
            try {
                // Bind before publishing the URL: the browser's redirect can
                // then only ever arrive on a listening socket (pi binds its
                // server before opening the browser, too).
                listener.bind()
                updateState { it.copy(codexSignIn = CodexSignInState.Browser(auth.authorizeUrl)) }
                val redirect = listener.awaitRedirect()
                updateBrowserSignIn { it.copy(completing = true) }
                // A full-screen Custom Tab stops Pathfinder's activity. Modern
                // Android can then block this UID's public network access even
                // though the browser can still reach the loopback listener.
                // Exchange only after the activity resumes and networking is
                // restored; the short-lived authorization code remains solely
                // in this coroutine meanwhile.
                appForegrounded.first { it }
                val tokens = codexOAuthClient.completeBrowserLogin(auth, redirect)
                credentials.set(
                    provider.id,
                    Credential.ChatGptOAuth(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAtEpochMillis = tokens.expiresAtEpochMillis,
                        accountId = tokens.accountId,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: CodexOAuthException) {
                updateBrowserSignIn { it.copy(completing = false, error = e.message ?: ERROR_CODEX_SIGN_IN) }
                return@launch
            } catch (e: Exception) {
                Diagnostics.failure(DiagnosticEvent.CODEX_SIGN_IN_UNEXPECTED_FAILURE, e)
                updateBrowserSignIn { it.copy(completing = false, error = ERROR_CODEX_SIGN_IN) }
                return@launch
            } finally {
                listener.close()
            }
            updateState { it.copy(codexSignIn = null) }
            onCredentialStored()
        }
    }

    /** Cancels an in-flight ChatGPT sign-in and clears its state; writes no credential. */
    fun cancelCodexSignIn() {
        codexSignInJob?.cancel()
        codexSignInJob = null
        updateState { it.copy(codexSignIn = null) }
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
                Diagnostics.failure(DiagnosticEvent.UI_SETTINGS_WRITE_FAILED, e)
                setError(ERROR_SETTINGS_SAVE)
            }
        }
    }

    fun send() {
        viewModelScope.launch { sendInternal() }
    }

    fun stop() {
        session?.abort()
    }

    /**
     * Navigates the conversation tree to [id] (pi's navigateTree, reduced:
     * no summarization). Busy-rejected while streaming; selecting the
     * current leaf is a no-op error; unknown ids are a safe error. A user
     * message target re-edits: the leaf moves to its parent (or resets to
     * root) and its text is restored into the draft, so the next send forks
     * as a sibling. Any other target moves the leaf to that entry.
     */
    fun navigateToTreeEntry(id: String) {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            val conversation = activeConversation
            if (id == conversation.leafId) {
                setError(ERROR_ALREADY_AT_POINT)
                return@launch
            }
            val entry = conversation.entry(id)
            if (entry == null) {
                setError(ERROR_ENTRY_MISSING)
                return@launch
            }
            val userMessage = (entry as? MessageEntry)?.message as? Message.User
            val updated = if (userMessage != null) {
                // Re-edit: the next append lands as a sibling of the target.
                val parent = entry.parentId?.let { pid -> conversation.entry(pid) }
                if (parent != null) conversation.branch(parent.id) else conversation.resetLeaf()
            } else {
                conversation.branch(id)
            }
            val reeditText = userMessage?.textContent()
            val currentSession = requireNotNull(session) {
                "Tree navigation requires a bound runtime session"
            }
            currentSession.replaceConversation(updated)
            updateState {
                it.copy(
                    // pi's navigateTree loads the re-edit text into the
                    // editor only when it is empty; a typed draft is never
                    // clobbered by navigation.
                    draft = if (it.draft.isBlank()) reeditText ?: it.draft else it.draft,
                    messages = projectCommitted(currentSession.state.value.committedMessages, updated),
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
                val created = sessionStore.create(DEFAULT_SESSION_TITLE)
                val newSession = tryCreateSession(currentSettings, created.id, Conversation(emptyList(), null))
                    ?: return@launch
                if (!activateSession(created, newSession)) return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_CREATE)
            }
        }
    }

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            if (rejectWhileBusy()) return@launch
            try {
                val loaded = sessionStore.load(sessionId)
                if (loaded == null) {
                    setError(ERROR_SESSION_MISSING)
                } else {
                    if (!awaitPersistence()) {
                        setError(ERROR_SESSION_SAVE)
                        return@launch
                    }
                    val loadedConversation = Conversation(loaded.entries, loaded.leafId)
                    val settings = modelSettingsFor(currentSettings, loadedConversation)
                    if (settings == null) {
                        // The branch records a model the current catalog no
                        // longer offers; the chat stays closed rather than
                        // silently continuing on the device default.
                        setError(ERROR_CONFIG_INVALID)
                        return@launch
                    }
                    val modelChanged = settings != currentSettings
                    if (modelChanged) {
                        currentSettings = settings
                        syncThinkingFromSettings()
                    }
                    val newSession = tryCreateSession(
                        settings,
                        loaded.id,
                        loadedConversation,
                    ) ?: return@launch
                    if (!activateSession(loaded, newSession)) return@launch
                    if (modelChanged) refreshOptions()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_LOAD)
            }
        }
    }

    // ---- initialization ----

    /**
     * The single configuration/session establishment flow: validates
     * persisted refs, derives a usable model when the stored default has
     * none to run, resolves and activates the session (restoring its
     * recorded model via the branch fold), and enters the chat. Runs at
     * startup and is replayed by [onCredentialStored] whenever a stored
     * credential unblocks configuration.
     */
    private suspend fun initialize() {
        try {
            val stored = settingsRepository.currentSettings()
            val summaries = sessionStore.summaries()
            val configuredIds = configuredProviderIds()

            var settings = stored
            if (configuredIds == null) {
                setError(ERROR_INIT)
                updateState { it.copy(status = ChatStatus.Failed, sessionSummaries = summaries) }
                return
            }
            // Persisted refs must name current catalog entries: a scope ref or
            // thinking preference from an older catalog is legacy data and
            // rejects here instead of silently dropping out of the picker or
            // defaulting.
            if (!hasResolvableRefs(stored)) {
                setError(ERROR_CONFIG_INVALID)
                updateState { it.copy(status = ChatStatus.Failed, sessionSummaries = summaries) }
                return
            }
            // With no usable stored model (never chosen, or its provider lost
            // its credential), the first model of a configured provider is
            // derived for this session only — never written back, so a stale
            // stored default is not silently rewritten. Only a
            // credential-less install stays unconfigured, pinned to the
            // providers step.
            if (!hasUsableModel(settings, configuredIds)) {
                val option = modelOptionsFor(configuredIds).firstOrNull()
                if (option == null) {
                    // No configured provider has any catalog model: the
                    // first-run providers step is the only useful surface.
                    currentSettings = stored
                    refreshOptions(configuredIds)
                    updateState {
                        it.copy(
                            status = ChatStatus.NeedsConfiguration,
                            startKey = ProvidersNavKey,
                            showThinking = stored.showThinking,
                            sessionSummaries = summaries,
                        )
                    }
                    return
                }
                settings = stored.copy(providerId = option.providerId, modelId = option.modelId)
            }

            val resolved = resolveSession(settings, summaries)
            // The restored branch's recorded model (last ModelChangeEntry on
            // the root→leaf path) wins over the device default; the fold is
            // conversation state, so the startup default is not rewritten.
            val conversation = Conversation(resolved.entries, resolved.leafId)
            val folded = modelSettingsFor(settings, conversation)
            if (folded == null) {
                // The branch records a model the current catalog no longer
                // offers; stale session data rejects rather than silently
                // continuing on the device default.
                setError(ERROR_CONFIG_INVALID)
                updateState { it.copy(status = ChatStatus.Failed, sessionSummaries = summaries) }
                return
            }
            settings = folded
            currentSettings = settings
            syncThinkingFromSettings()
            // Build the runtime session before committing any state: a
            // failure must never leave a Ready UI or persisted active id.
            val newSession = tryCreateSession(
                settings,
                resolved.id,
                conversation,
            )
            if (newSession == null) {
                updateState {
                    it.copy(
                        status = ChatStatus.Failed,
                        sessionSummaries = summaries,
                        error = ERROR_CONFIG_INVALID,
                    )
                }
                return
            }
            if (!activateSession(resolved, newSession)) {
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
            Diagnostics.failure(DiagnosticEvent.UI_INIT_FAILED, e)
            setError(ERROR_INIT)
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

    // ---- session lifecycle ----

    /**
     * Makes [newActiveSession] active with a prebuilt runtime session:
     * persists the active id, binds the session, and returns to the chat
     * surface with a refreshed UI. Returns false when persisting the active
     * id fails; in that case nothing is committed.
     */
    private suspend fun activateSession(active: Session, newSession: ChatRuntimeSession): Boolean {
        try {
            settingsRepository.setActiveSessionId(active.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.failure(DiagnosticEvent.UI_SETTINGS_WRITE_FAILED, e)
            setError(ERROR_SETTINGS_SAVE)
            return false
        }
        activeSession = active
        persistedEntryCount = active.entries.size
        bindSession(newSession)
        val conversation = newSession.conversation
        val summaries = sessionStore.summaries()
        updateState {
            it.copy(
                activeSessionId = active.id,
                startKey = ChatNavKey,
                navigationEpoch = it.navigationEpoch + 1,
                messages = projectCommitted(newSession.state.value.committedMessages, conversation),
                streamingMessage = null,
                treeRows = buildTreeRows(conversation, it.treeFilter),
                sessionSummaries = summaries,
            )
        }
        return true
    }

    /** Builds a runtime session or null (with a safe error surfaced) on failure. */
    private fun tryCreateSession(
        settings: ModelSettings,
        sessionId: String,
        conversation: Conversation,
    ): ChatRuntimeSession? =
        try {
            runtime.createSession(settings, sessionId, conversation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.failure(DiagnosticEvent.UI_CONFIG_INVALID, e)
            setError(ERROR_CONFIG_INVALID)
            null
        }

    private fun bindSession(newSession: ChatRuntimeSession) {
        sessionStateJob?.cancel()
        session = newSession
        lastRuntimeError = null
        sessionStateJob = viewModelScope.launch { newSession.state.collect { state -> onRuntimeState(state) } }
    }

    private fun onRuntimeState(state: ChatRuntimeState) {
        val runtimeError = state.error
        val conversation = activeConversation
        updateState {
            it.copy(
                messages = projectCommitted(state.committedMessages, conversation),
                streamingMessage = state.streamingMessage?.let(::projectStreaming),
                isStreaming = state.isStreaming,
                treeRows = buildTreeRows(conversation, it.treeFilter),
                error = runtimeError ?: it.error?.takeIf { e -> e != lastRuntimeError },
            )
        }
        lastRuntimeError = runtimeError
        // Persistence point: the runtime committed something onto the tree.
        if (conversation.entries.size > persistedEntryCount) {
            enqueuePersist()
        }
    }

    // ---- persistence pipeline ----

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
            // keeps draining until no accepted snapshot remains and exits.
            while (true) {
                val next = pendingPersist ?: break
                pendingPersist = null
                persistSnapshot(next.first, next.second)
            }
        }
    }

    /**
     * Writes one snapshot. The file is always written (the transcript stays
     * with its session even if the user switched away meanwhile); UI/active
     * state is only updated when that session is still active.
     */
    private suspend fun persistSnapshot(session: Session, conversation: Conversation) {
        // Non-cancellable: an accepted snapshot must reach the file even when
        // the ViewModel scope is torn down mid-write.
        withContext(NonCancellable) {
            try {
                val activeMessages = conversation.activeMessages()
                val title = if (session.title == DEFAULT_SESSION_TITLE) {
                    deriveTitle(activeMessages) ?: DEFAULT_SESSION_TITLE
                } else {
                    session.title
                }
                // Persist the tree itself (entries + leafId): branch
                // structure must survive saves.
                val saved = sessionStore.save(
                    session.copy(entries = conversation.entries, leafId = conversation.leafId, title = title),
                )
                if (activeSession?.id == session.id) {
                    activeSession = saved
                    persistedEntryCount = saved.entries.size
                }
                val summaries = sessionStore.summaries()
                // activeSession and uiState.activeSessionId are set together
                // in activateSession and never diverge, so one check suffices.
                if (activeSession?.id == session.id) {
                    updateState { it.copy(sessionSummaries = summaries) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_SAVE)
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

    /**
     * Applies a model pick: live session swap first (nothing about the
     * transcript or sessions changes), then the settings write. A failed
     * write surfaces an error but keeps the swapped session — the next
     * launch falls back to the previously persisted model.
     */
    private suspend fun selectModelInternal(option: ModelOption) {
        val currentSession = session ?: return
        val provider = ProviderDescriptors.byId(option.providerId) ?: return
        val model = provider.model(option.modelId) ?: return
        val modelRef = "${option.providerId}/${option.modelId}"
        val thinking = ThinkingOptions.parse(
            provider.id,
            model.model,
            currentSettings.thinkingPrefs[modelRef],
        )
        currentSession.selectModel(model, thinking)
        try {
            settingsRepository.setProviderId(option.providerId)
            settingsRepository.setModelId(option.modelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.failure(DiagnosticEvent.UI_SETTINGS_WRITE_FAILED, e)
            setError(ERROR_SETTINGS_SAVE)
        }
        currentSettings = currentSettings.copy(
            providerId = option.providerId,
            modelId = option.modelId,
        )
        currentThinking = thinking
        refreshOptions()
        if (_uiState.value.isStreaming) {
            // Tree mutation is illegal while streaming (the in-flight
            // response owns the leaf); the pick is captured in
            // [currentSettings] now and the entry is appended at the next
            // prompt, after the current response commits.
            return
        }
        recordModelChange(option.providerId, option.modelId)
    }

    /** Appends the effective model switch to the tree and persists it. */
    private fun recordModelChange(providerId: String, modelId: String) {
        val currentSession = session ?: return
        val updated = currentSession.conversation.appendModelChange(providerId, modelId)
        currentSession.replaceConversation(updated)
        enqueuePersist()
    }

    /**
     * Makes the session self-describing before a prompt: when the active
     * path records no model yet (or a different one, e.g. a pick deferred
     * while streaming), the effective initial model is appended first.
     */
    private fun recordModelChangeForPrompt() {
        val providerId = currentSettings.providerId
        val modelId = currentSettings.modelId
        if (providerId.isEmpty() || modelId.isEmpty()) return
        val ref = Conversation.ModelRef(providerId, modelId)
        if (activeConversation.activeModelRef() == ref) return
        recordModelChange(providerId, modelId)
    }

    /** Applies a thinking pick to the live session and persists it per model. */
    private suspend fun setThinkingInternal(option: ThinkingOption) {
        val currentSession = session ?: return
        currentSession.setThinking(option)
        val modelRef = "${currentSettings.providerId}/${currentSettings.modelId}"
        try {
            settingsRepository.setThinkingPref(modelRef, option.label)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.failure(DiagnosticEvent.UI_SETTINGS_WRITE_FAILED, e)
            setError(ERROR_SETTINGS_SAVE)
        }
        currentSettings = currentSettings.copy(
            thinkingPrefs = currentSettings.thinkingPrefs + (modelRef to option.label),
        )
        currentThinking = option
        updateState { it.copy(thinkingOption = option) }
    }

    /**
     * Adds/removes one model in the scoped picker set, materializing the
     * uncurated "all models" default into an explicit set on first edit.
     */
    private suspend fun toggleModelScopeInternal(option: ModelOption, enabled: Boolean) {
        val current = currentSettings.enabledModels
            ?: _uiState.value.modelOptions.map { "${it.providerId}/${it.modelId}" }.toSet()
        val modelRef = "${option.providerId}/${option.modelId}"
        val next = if (enabled) current + modelRef else current - modelRef
        try {
            settingsRepository.setEnabledModels(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.failure(DiagnosticEvent.UI_SETTINGS_WRITE_FAILED, e)
            setError(ERROR_SETTINGS_SAVE)
            return
        }
        currentSettings = currentSettings.copy(enabledModels = next)
        updateState {
            it.copy(
                modelScope = next,
                scopedModels = it.modelOptions.filter { m -> "${m.providerId}/${m.modelId}" in next },
            )
        }
    }

    private suspend fun saveProviderCredentialInternal(provider: ProviderOption, apiKey: String) {
        val newKey = apiKey.trim()
        if (newKey.isEmpty()) {
            setError(ERROR_CREDENTIAL_INCOMPLETE)
            return
        }
        try {
            credentials.set(provider.id, Credential.ApiKey(newKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE)
            return
        }
        onCredentialStored()
    }

    /**
     * Post-save success path, shared by every credential store: bumps the
     * credential-success epoch so the UI closes the credential form only
     * after confirmed persistence. Once the chat is live it only refreshes
     * the credential-derived surfaces — credentials are read per request,
     * so a live session (including one mid-stream) is never touched.
     * Before that, [initialize] is replayed: it owns the single
     * NeedsConfiguration→Ready transition, so first-run completion runs
     * the same model derivation, session resolution, and branch-fold
     * restore as startup (Failed and NeedsConfiguration never have a
     * bound session, making the replay a fresh start).
     */
    private suspend fun onCredentialStored() {
        updateState { it.copy(credentialSuccessEpoch = it.credentialSuccessEpoch + 1) }
        if (_uiState.value.status == ChatStatus.Ready) {
            refreshOptions()
            return
        }
        initialize()
    }

    /**
     * Settings with the session branch's recorded model folded in: the last
     * [works.resolve.pathfinder.data.sessions.ModelChangeEntry] on the active
     * root→leaf path names the model the session continues with, and must
     * still resolve in the current catalog — a ref that no longer does is
     * stale session data and yields null. A path that records no model
     * change keeps the device default (not a fallback: a fold over no
     * entries is simply a default-model session).
     */
    private fun modelSettingsFor(settings: ModelSettings, conversation: Conversation): ModelSettings? {
        val ref = conversation.activeModelRef() ?: return settings
        val provider = ProviderDescriptors.byId(ref.providerId) ?: return null
        if (provider.model(ref.modelId) == null) return null
        return settings.copy(providerId = ref.providerId, modelId = ref.modelId)
    }

    /**
     * True iff every persisted provider/model ref names a current catalog
     * model: the curated scope ([ModelSettings.enabledModels]) and the
     * [ModelSettings.thinkingPrefs] keys, each with a label still offered
     * for its model. Refs written against an older catalog are legacy data
     * and reject initialization instead of silently degrading.
     */
    private fun hasResolvableRefs(settings: ModelSettings): Boolean {
        settings.enabledModels?.forEach { ref ->
            if (catalogModel(ref) == null) return false
        }
        settings.thinkingPrefs.forEach { (ref, label) ->
            val model = catalogModel(ref) ?: return false
            if (!ThinkingOptions.isValidLabel(model.providerId, model.model, label)) return false
        }
        return true
    }

    /**
     * The catalog model named by a `provider/model` ref (model ids may
     * themselves contain '/'), or null when the ref names no current
     * catalog entry.
     */
    private fun catalogModel(ref: String): ModelDescriptor? {
        val provider = ProviderDescriptors.all.firstOrNull { p -> ref.startsWith("${p.id}/") }
            ?: return null
        return provider.model(ref.removePrefix("${provider.id}/"))
    }

    /**
     * True iff settings name a descriptor provider+model of a provider in
     * [configuredIds].
     */
    private fun hasUsableModel(settings: ModelSettings, configuredIds: Set<String>): Boolean {
        val provider = ProviderDescriptors.byId(settings.providerId) ?: return false
        if (provider.model(settings.modelId) == null) return false
        return provider.id in configuredIds
    }

    /** Every model of the configured providers, provider-then-model sorted. */
    private fun modelOptionsFor(configuredIds: Set<String>): List<ModelOption> =
        ProviderDescriptors.all
            .filter { it.id in configuredIds }
            .flatMap { provider ->
                provider.models.map { model ->
                    ModelOption(
                        providerId = provider.id,
                        providerName = provider.displayName,
                        modelId = model.id,
                        name = model.displayName,
                    )
                }
            }
            .sortedWith(compareBy({ it.providerName }, { it.name }))

    /**
     * The model picker's list: the explicit scope when curated (refs whose
     * provider lost its credential drop out via [modelOptionsFor]), else
     * every model of configured providers (pi's "all enabled" default).
     */
    private fun resolveScopedModels(
        enabledModels: Set<String>?,
        modelOptions: List<ModelOption>,
    ): List<ModelOption> =
        enabledModels?.let { refs ->
            modelOptions.filter { "${it.providerId}/${it.modelId}" in refs }
        } ?: modelOptions

    /** Thinking options for the current model, plus the applied option. */
    private fun thinkingProjection(): Pair<List<ThinkingOption>, ThinkingOption> {
        val provider = ProviderDescriptors.byId(currentSettings.providerId)
            ?: return listOf(ThinkingOption.Default) to ThinkingOption.Default
        val model = provider.model(currentSettings.modelId)
            ?: return listOf(ThinkingOption.Default) to ThinkingOption.Default
        val options = ThinkingOptions.forModel(provider.id, model.model)
        // currentThinking is always set for the current model (every
        // settings change re-syncs it), so it must be among the options.
        return options to options.first { it == currentThinking }
    }

    /** Mirrors the persisted thinking preference of the current model. */
    private fun syncThinkingFromSettings() {
        val provider = ProviderDescriptors.byId(currentSettings.providerId)
        val model = provider?.model(currentSettings.modelId)
        currentThinking = if (provider != null && model != null) {
            ThinkingOptions.parse(
                provider.id,
                model.model,
                currentSettings.thinkingPrefs["${provider.id}/${model.id}"],
            )
        } else {
            ThinkingOption.Default
        }
    }

    /** The configured-provider ids, or null when the credential store fails. */
    private suspend fun configuredProviderIds(): Set<String>? = try {
        credentials.list().toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Recomputes every credential-derived surface (providers screen rows,
     * scope editor rows, scoped picker options, selection and thinking
     * projections). Called on init, after every credential mutation, and
     * from [refreshProviderStatus].
     */
    private suspend fun refreshOptions(configuredIdsOverride: Set<String>? = null) {
        val configuredIds = configuredIdsOverride ?: try {
            credentials.list().toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE)
            return
        }
        val providerOptions = ProviderDescriptors.all
            .map { provider ->
                ProviderOption(
                    id = provider.id,
                    name = provider.displayName,
                    authKind = provider.authKind,
                    configured = provider.id in configuredIds,
                )
            }
            .sortedBy { it.name }
        val modelOptions = modelOptionsFor(configuredIds)
        val scopedModels = resolveScopedModels(currentSettings.enabledModels, modelOptions)
        val selectedModel = selectedModelProjection(currentSettings)
        val thinking = thinkingProjection()
        updateState {
            it.copy(
                providerOptions = providerOptions,
                modelOptions = modelOptions,
                modelScope = currentSettings.enabledModels,
                scopedModels = scopedModels,
                selectedModel = selectedModel,
                thinkingOptions = thinking.first,
                thinkingOption = thinking.second,
            )
        }
    }

    private fun selectedModelProjection(settings: ModelSettings): SelectedModel? {
        val provider = ProviderDescriptors.byId(settings.providerId) ?: return null
        val model = provider.model(settings.modelId) ?: return null
        return SelectedModel(
            providerId = provider.id,
            providerName = provider.displayName,
            modelId = model.id,
            modelName = model.displayName,
        )
    }

    private suspend fun sendInternal() {
        val state = _uiState.value
        if (state.status != ChatStatus.Ready || state.isStreaming) return
        val text = state.draft.trim()
        val currentSession = session
        if (text.isEmpty() || currentSession == null) return

        updateState { it.copy(draft = "") }
        try {
            recordModelChangeForPrompt()
            currentSession.prompt(text)
        } catch (e: CancellationException) {
            // Abort (or ViewModel teardown): the runtime already committed its
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
     * Surfaces [message] as the UI error. This is the UI's single error
     * boundary; messages are fixed user-safe strings and carry no provider
     * or transport detail.
     */
    private fun setError(message: String) {
        updateState { it.copy(error = message) }
    }

    private fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _uiState.update { current ->
            val next = transform(current)
            next.copy(canSend = next.status == ChatStatus.Ready && !next.isStreaming && next.draft.isNotBlank())
        }
    }

    /**
     * Updates the device sign-in projection. Null-tolerant: a cancelled flow
     * clears `codexSignIn` concurrently with the sign-in job's error paths.
     */
    private fun updateDeviceSignIn(transform: (CodexSignInState.Device) -> CodexSignInState.Device) {
        updateState { state ->
            val signIn = state.codexSignIn as? CodexSignInState.Device
            if (signIn == null) state else state.copy(codexSignIn = transform(signIn))
        }
    }

    /**
     * Updates the browser sign-in projection. Null-tolerant: a cancelled flow
     * clears `codexSignIn` concurrently with the sign-in job's error paths.
     */
    private fun updateBrowserSignIn(transform: (CodexSignInState.Browser) -> CodexSignInState.Browser) {
        updateState { state ->
            val signIn = state.codexSignIn as? CodexSignInState.Browser
            if (signIn == null) state else state.copy(codexSignIn = transform(signIn))
        }
    }

    private companion object {
        const val DEFAULT_SESSION_TITLE = "New chat"

        const val TITLE_MAX_LENGTH = 48

        const val ERROR_INIT = "Could not load chat data"
        const val ERROR_CREDENTIAL_INCOMPLETE = "Enter this provider's API key before using its models"
        const val ERROR_CREDENTIAL_SAVE = "Could not store the API key"
        const val ERROR_SETTINGS_SAVE = "Could not save the configuration"
        const val ERROR_CONFIG_INVALID = "Invalid configuration"
        const val ERROR_SESSION_CREATE = "Could not create a new chat"
        const val ERROR_SESSION_LOAD = "Could not open the chat"
        const val ERROR_SESSION_MISSING = "That chat no longer exists"
        const val ERROR_SESSION_SAVE = "Could not save the chat"
        const val ERROR_BUSY = "Wait for the response to finish first"
        const val ERROR_ALREADY_STREAMING = "A response is already streaming"
        const val ERROR_ALREADY_AT_POINT = "Already at this point"
        const val ERROR_ENTRY_MISSING = "Message not found"
        const val ERROR_CODEX_SIGN_IN = "Sign-in failed. Try again."

        /** Single-line, bounded title from the first user prompt. */
        fun deriveTitle(messages: List<Message>): String? {
            val firstText = messages.asSequence()
                .filterIsInstance<Message.User>()
                .firstOrNull()
                ?.textContent()
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
 * entries still live in the runtime transcript render — a retried or
 * recovered failure removes messages from the live transcript while the
 * append-only tree keeps them in history, exactly like pi's UI. Text parts
 * stay separate, runs of consecutive reasoning parts merge into one block,
 * and blank parts drop. Keys are stable per path index+role+timestamp so
 * that same-millisecond user/assistant messages can never collide.
 */
private fun projectCommitted(liveMessages: List<Message>, conversation: Conversation): List<ChatMessage> {
    val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Message, Boolean>())
    live.addAll(liveMessages)
    val projected = mutableListOf<ChatMessage>()
    conversation.activeEntries().forEachIndexed { index, entry ->
        if (entry !is MessageEntry || !live.contains(entry.message)) return@forEachIndexed
        val message = entry.message
        val chat = when (message) {
            is Message.User -> ChatMessage(
                id = "msg-$index-${message.metaInfo.timestamp}",
                role = ChatRole.User,
                blocks = message.parts.toChatBlocks(),
            )
            is Message.Assistant -> ChatMessage(
                id = "msg-$index-${message.metaInfo.timestamp}",
                role = ChatRole.Assistant,
                blocks = message.parts.toChatBlocks(),
            )
            else -> null // System messages are not part of the visible transcript.
        }
        chat?.let { projected.add(it) }
    }
    return projected
}

/**
 * UI projection of the in-flight partial; distinct key namespace from
 * committed messages. The streaming contract stays assistant-only, so
 * non-assistant partials project to nothing at the call site.
 */
private fun projectStreaming(message: Message.Assistant): ChatMessage =
    ChatMessage(
        id = "streaming-${message.metaInfo.timestamp}",
        role = ChatRole.Assistant,
        blocks = message.parts.toChatBlocks(),
    )

/**
 * Projects message parts into ordered blocks: each non-blank
 * [MessagePart.Text] becomes its own [ChatBlock.Text]; runs of consecutive
 * [MessagePart.Reasoning] merge into one [ChatBlock.Thinking] joined with
 * "\n\n" and trimmed (dropped when the merged result is blank). Raw content
 * is preferred but hosted reasoning models (e.g. the ChatGPT Codex backend)
 * stream summaries only, so a blank content falls back to the part's
 * summary. Every other part (attachments, tool calls/results) is omitted —
 * no speculative UI.
 */
private fun List<ai.koog.prompt.message.MessagePart>.toChatBlocks(): List<ChatBlock> {
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
            is MessagePart.Reasoning -> {
                val display = part.content.filter { it.isNotBlank() }.ifEmpty { part.summary.orEmpty() }
                (thinkingRun ?: mutableListOf<String>().also { thinkingRun = it }).addAll(display)
            }
            is MessagePart.Text -> {
                flushThinking()
                part.text.takeIf { it.isNotBlank() }?.let { blocks.add(ChatBlock.Text(it)) }
            }
            else -> flushThinking()
        }
    }
    flushThinking()
    return blocks
}
