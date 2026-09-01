package works.resolve.pathfinder.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import works.resolve.pathfinder.runtime.ChatRuntime
import works.resolve.pathfinder.runtime.ChatRuntimeSession
import works.resolve.pathfinder.runtime.ChatRuntimeState
import works.resolve.pathfinder.runtime.ProviderDescriptors
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
 * The provider/model pair is user-selectable from the app-owned provider
 * descriptors (models enumerated from Koog's model definitions); credentials
 * are per-provider API keys stored in the Keystore-backed credential store
 * (one credential per provider, replaced wholesale, removed per provider).
 * Provider auth status (`configured`) is derived live from the credential
 * store — never persisted in settings — and the model picker only lists
 * models of configured providers.
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
 * [ChatUiState.startKey] to [ProvidersNavKey] (first-run step 1: pick a
 * provider and enter its API key), or directly to [ModelSettingsNavKey] when
 * a credential already exists (restoration); every intent that should return
 * the user to the chat sets [ChatUiState.startKey] to [ChatNavKey] and bumps
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
     * Persists the selected provider+model and (re)builds the runtime session.
     * Requires a stored credential for the option's provider.
     */
    fun saveModelSelection(option: ModelOption) {
        viewModelScope.launch { saveModelSelectionInternal(option) }
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
                val redirectUrl = listener.awaitRedirect()
                updateBrowserSignIn { it.copy(completing = true) }
                // A full-screen Custom Tab stops Pathfinder's activity. Modern
                // Android can then block this UID's public network access even
                // though the browser can still reach the loopback listener.
                // Exchange only after the activity resumes and networking is
                // restored; the short-lived authorization code remains solely
                // in this coroutine meanwhile.
                appForegrounded.first { it }
                val tokens = codexOAuthClient.completeBrowserLogin(auth, redirectUrl)
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
                    val newSession = tryCreateSession(
                        currentSettings,
                        loaded.id,
                        Conversation(loaded.entries, loaded.leafId),
                    ) ?: return@launch
                    if (!activateSession(loaded, newSession)) return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_LOAD)
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
                updateState {
                    it.copy(
                        status = ChatStatus.NeedsConfiguration,
                        // First-run vs restoration: when at least one provider
                        // credential is already complete, the model form is
                        // the useful forced root; only a fresh install forces
                        // the providers step first.
                        startKey = if (it.modelOptions.isNotEmpty()) ModelSettingsNavKey else ProvidersNavKey,
                        showThinking = settings.showThinking,
                        sessionSummaries = summaries,
                    )
                }
                return
            }

            val resolved = resolveSession(settings, summaries)
            // Build the runtime session before committing any state: a
            // failure must never leave a Ready UI or persisted active id.
            val newSession = tryCreateSession(
                settings,
                resolved.id,
                Conversation(resolved.entries, resolved.leafId),
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
            currentSettings = settings
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

    private suspend fun saveModelSelectionInternal(option: ModelOption) {
        if (rejectWhileBusy()) return

        // Credential gate: a provider is configured only when a credential is
        // stored for it.
        val providerConfigured = try {
            credentials.read(option.providerId) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE)
            return
        }
        if (!providerConfigured) {
            setError(ERROR_CREDENTIAL_INCOMPLETE)
            return
        }

        val candidate = ModelSettings(
            providerId = option.providerId,
            modelId = option.modelId,
            activeSessionId = activeSession?.id,
            // The display preference is owned by setShowThinking; preserve it
            // so session rebuilds never drift from what the user picked.
            showThinking = currentSettings.showThinking,
        )

        val wasReady = _uiState.value.status == ChatStatus.Ready && activeSession != null
        if (wasReady) {
            // Any failure (runtime, settings write, or an unsaved transcript)
            // keeps the previous session and persisted settings.
            if (!awaitPersistence()) {
                setError(ERROR_SESSION_SAVE)
                return
            }
            val newSession = tryCreateSession(candidate, activeSession!!.id, activeConversation) ?: return
            if (!persistSettings(candidate)) return
            bindSession(newSession)
            currentSettings = candidate
        } else {
            // Initial configuration: validate the session first, then persist
            // the settings, then activate. A failure at any step leaves the
            // ViewModel unconfigured (never falsely Ready).
            val resolved = try {
                resolveSession(candidate, sessionStore.summaries())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_CREATE)
                return
            }
            val newSession = tryCreateSession(
                candidate,
                resolved.id,
                Conversation(resolved.entries, resolved.leafId),
            ) ?: return
            if (!persistSettings(candidate)) return
            if (!activateSession(resolved, newSession)) return
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
     * Post-save success path: bumps the credential-success epoch so the UI
     * closes the credential form only after confirmed persistence, refreshes
     * every credential-derived surface, and — while still in the forced
     * first-run flow — completes configuration when the persisted model
     * settings are now usable, else advances to the model-settings step.
     */
    private suspend fun onCredentialStored() {
        updateState { it.copy(credentialSuccessEpoch = it.credentialSuccessEpoch + 1) }

        val nowConfigured = isConfigured(currentSettings)
        refreshOptions(nowConfigured)
        if (_uiState.value.status == ChatStatus.NeedsConfiguration) {
            if (nowConfigured) {
                val resolved = try {
                    resolveSession(currentSettings, sessionStore.summaries())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setError(ERROR_SESSION_CREATE)
                    return
                }
                val newSession = tryCreateSession(
                    currentSettings,
                    resolved.id,
                    Conversation(resolved.entries, resolved.leafId),
                ) ?: return
                if (!activateSession(resolved, newSession)) return
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

    /**
     * True iff settings name a descriptor provider+model AND a credential is
     * stored for that provider.
     */
    private suspend fun isConfigured(settings: ModelSettings): Boolean {
        val provider = ProviderDescriptors.byId(settings.providerId) ?: return false
        if (provider.model(settings.modelId) == null) return false
        return try {
            credentials.read(provider.id) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failing read degrades to "not configured" rather than
            // crashing configuration refresh.
            false
        }
    }

    /**
     * Recomputes every credential-derived surface (providers screen rows,
     * model picker options, selection projection, `configured`). Called on
     * init, after every credential mutation, and from [refreshProviderStatus].
     */
    private suspend fun refreshOptions(configuredOverride: Boolean? = null) {
        val configuredIds = try {
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
        val configured = configuredOverride ?: isConfigured(currentSettings)
        // Only models from configured providers are selectable.
        val modelOptions = ProviderDescriptors.all
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

    /** Persists the validated configuration; false (with a safe error) on failure. */
    private suspend fun persistSettings(settings: ModelSettings): Boolean {
        try {
            settingsRepository.setProviderId(settings.providerId)
            settingsRepository.setModelId(settings.modelId)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.failure(DiagnosticEvent.UI_SETTINGS_WRITE_FAILED, e)
            setError(ERROR_SETTINGS_SAVE)
            return false
        }
    }

    private suspend fun sendInternal() {
        val state = _uiState.value
        if (state.status != ChatStatus.Ready || state.isStreaming) return
        val text = state.draft.trim()
        val currentSession = session
        if (text.isEmpty() || currentSession == null) return

        updateState { it.copy(draft = "") }
        try {
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
