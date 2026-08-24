package works.resolve.aletheia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import works.resolve.aletheia.agent.Agent
import works.resolve.aletheia.agent.AgentState
import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.Content
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ThinkingContent
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.providers.AuthPrompt
import works.resolve.aletheia.ai.providers.ProviderCatalog
import works.resolve.aletheia.agent.AgentFactory
import works.resolve.aletheia.data.credentials.ApiKeyCredential
import works.resolve.aletheia.data.credentials.ApiKeyStore
import works.resolve.aletheia.data.settings.ModelSettings
import works.resolve.aletheia.data.settings.SettingsStore
import works.resolve.aletheia.data.settings.SettingsRepository
import works.resolve.aletheia.data.sessions.Session
import works.resolve.aletheia.data.sessions.SessionRepository
import works.resolve.aletheia.data.sessions.SessionSummary
import kotlinx.coroutines.CancellationException
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
 * only lists models of configured providers (pi's model-selector rule).
 *
 * The agent itself is created through the injected [AgentFactory]
 * (see [works.resolve.aletheia.agent]); the production implementation wires the native
 * Z.AI runtime.
 *
 * Transcript persistence runs through a single latest-snapshot pipeline: at
 * most one save per session is in flight, superseded snapshots are coalesced,
 * and session switches wait for pending saves so transcripts always stay with
 * the session they belong to. A failed save surfaces an error and blocks
 * session/config switches; the blocked intent explicitly retries the latest
 * snapshot and only proceeds once it is saved, so an unsaved transcript is
 * never silently abandoned. Snapshot writes themselves are non-cancellable
 * and the save loop drains whatever it accepted, so ViewModel teardown can
 * never abandon an accepted snapshot either.
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
    private val credentials: ApiKeyStore,
    private val catalog: ProviderCatalog,
    private val sessionStore: SessionRepository,
    private val agentFactory: AgentFactory,
) : ViewModel() {

    // Catalog-driven provider/model surface: option lists are computed from
    // live credential state (see refreshOptions).
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Current committed configuration; updated on init and successful save. */
    private var currentSettings: ModelSettings = ModelSettings()

    private var agent: Agent? = null
    private var agentStateJob: Job? = null
    private var activeSession: Session? = null

    /** Count of transcript messages already persisted for [activeSession]. */
    private var persistedMessageCount: Int = 0

    /** Latest unsaved transcript snapshot for its owning session. */
    private var pendingPersist: Pair<Session, List<Message>>? = null
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
     * model after login: aletheia instead forces the model picker step.
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
                credentials.deleteCredential(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_CREDENTIAL_SAVE)
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
                setError(ERROR_CREDENTIAL_SAVE)
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
                setError(ERROR_SETTINGS_SAVE)
            }
        }
    }

    fun send() {
        viewModelScope.launch { sendInternal() }
    }

    fun stop() {
        agent?.abort()
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
                val newAgent = tryCreateAgent(currentSettings, session.id, emptyList()) ?: return@launch
                if (!activateSession(session, newAgent)) return@launch
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
                val session = sessionStore.load(sessionId)
                if (session == null) {
                    setError(ERROR_SESSION_MISSING)
                } else {
                    if (!awaitPersistence()) {
                        setError(ERROR_SESSION_SAVE)
                        return@launch
                    }
                    val newAgent = tryCreateAgent(currentSettings, session.id, session.messages) ?: return@launch
                    if (!activateSession(session, newAgent)) return@launch
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
                // First-run vs restoration: when at least one provider
                // credential is already complete, the model form is the
                // useful forced root (the user can pick a model right away);
                // only a fresh install forces the providers step first.
                val hasConfiguredProvider = _uiState.value.modelOptions.isNotEmpty()
                updateState {
                    it.copy(
                        status = ChatStatus.NeedsConfiguration,
                        startKey = if (hasConfiguredProvider) ModelSettingsNavKey else ProvidersNavKey,
                        showThinking = settings.showThinking,
                        sessionSummaries = summaries,
                    )
                }
                return
            }

            val session = resolveSession(settings, summaries)
            // Build the agent before committing any state: a factory failure
            // must never leave a Ready UI or persisted active-session id.
            val newAgent = tryCreateAgent(settings, session.id, session.messages)
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
            updateState { it.copy(status = ChatStatus.Failed, error = ERROR_INIT) }
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
    private suspend fun activateSession(session: Session, agent: Agent): Boolean {
        try {
            settingsRepository.setActiveSessionId(session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SETTINGS_SAVE)
            return false
        }
        // Commit the session before binding: state collection starts
        // immediately, and the new agent's transcript must never be observed
        // against the previous session (which could cross-write saves).
        activeSession = session
        persistedMessageCount = session.messages.size
        bindAgent(agent)
        val summaries = sessionStore.summaries()
        updateState {
            it.copy(
                activeSessionId = session.id,
                startKey = ChatNavKey,
                navigationEpoch = it.navigationEpoch + 1,
                messages = projectCommitted(session.messages),
                streamingMessage = null,
                sessionSummaries = summaries,
            )
        }
        return true
    }

    /**
     * Resolves the session and builds an agent for it: validation happens
     * before anything is committed. Returns null on failure (safe error set).
     */
    private suspend fun prepareAdoption(settings: ModelSettings): Pair<Session, Agent>? {
        val session = try {
            resolveSession(settings, sessionStore.summaries())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_SESSION_CREATE)
            return null
        }
        val newAgent = tryCreateAgent(settings, session.id, session.messages) ?: return null
        return session to newAgent
    }

    /** Builds an agent or null (with a safe error surfaced) when the factory rejects the settings. */
    private fun tryCreateAgent(settings: ModelSettings, sessionId: String, transcript: List<Message>): Agent? =
        try {
            agentFactory.create(settings, sessionId, transcript)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CONFIG_INVALID)
            null
        }

    private fun bindAgent(newAgent: Agent) {
        agentStateJob?.cancel()
        agent = newAgent
        lastAgentError = null
        agentStateJob = viewModelScope.launch { newAgent.state.collect { state -> onAgentState(state) } }
    }

    private fun onAgentState(state: AgentState) {
        val agentError = state.errorMessage
        updateState {
            it.copy(
                messages = projectCommitted(state.messages),
                streamingMessage = state.streamingMessage?.let(::projectStreaming),
                isStreaming = state.isStreaming,
                error = agentError ?: it.error?.takeIf { e -> e != lastAgentError },
            )
        }
        lastAgentError = agentError

        if (state.messages.size > persistedMessageCount) {
            enqueuePersist(state.messages)
        }
    }

    // ---- persistence pipeline ----

    /**
     * Schedules [messages] for persistence against the currently active
     * session snapshot. At most one save runs at a time; while a save is in
     * flight, newer snapshots coalesce (only the latest is written).
     */
    private fun enqueuePersist(messages: List<Message>) {
        val session = activeSession ?: return
        pendingPersist = session to messages
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
     * Writes one snapshot. The file is always written (the transcript stays
     * with its session even if the user switched away meanwhile); UI/active
     * state is only updated when that session is still active.
     */
    private suspend fun persistSnapshot(session: Session, messages: List<Message>) {
        // Non-cancellable: an accepted snapshot must reach the file even when
        // the ViewModel scope is torn down mid-write; without this, cancelling
        // the save coroutine between dequeue and write would silently drop the
        // transcript. UI bookkeeping still only targets a still-active session.
        withContext(NonCancellable) {
            try {
                val title = if (session.title == DEFAULT_SESSION_TITLE) {
                    deriveTitle(messages) ?: DEFAULT_SESSION_TITLE
                } else {
                    session.title
                }
                val saved = sessionStore.save(session.copy(title = title, messages = messages))
                if (activeSession?.id == session.id) {
                    activeSession = saved
                    persistedMessageCount = saved.messages.size
                }
                val summaries = sessionStore.summaries()
                if (activeSession?.id == session.id || _uiState.value.activeSessionId == session.id) {
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
     * Ensures the latest transcript of the active session is fully saved,
     * retrying once from the current agent snapshot if a previous save
     * failed. Returns false when the latest snapshot remains unsaved:
     * callers must then keep the current session (and surface the save
     * error) so an unsaved transcript is never abandoned.
     */
    private suspend fun awaitPersistence(): Boolean {
        retryUnsavedSnapshot()
        persistJob?.join()
        val agentMessages = agent?.state?.value?.messages
        return pendingPersist == null &&
            (agentMessages == null || agentMessages.size <= persistedMessageCount)
    }

    /** Explicitly re-enqueues the latest agent transcript when it is unsaved. */
    private fun retryUnsavedSnapshot() {
        val snapshot = agent?.state?.value ?: return
        if (activeSession != null && snapshot.messages.size > persistedMessageCount) {
            enqueuePersist(snapshot.messages)
        }
    }

    // ---- intent internals ----

    private suspend fun saveModelSelectionInternal(providerId: String, modelId: String) {
        val trimmedModelId = modelId.trim()
        if (catalog.getModel(providerId, trimmedModelId) == null) {
            setError(ERROR_UNKNOWN_MODEL)
            return
        }
        if (rejectWhileBusy()) return

        val provider = catalog.getProvider(providerId)
        val credential = try {
            credentials.getCredential(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE)
            return
        }
        // Credential-completeness gate (pi's rule: a provider is configured
        // only when every auth prompt has a value — e.g. Cloudflare also
        // needs account/gateway ids, not just the API key).
        if (provider == null || !provider.isCredentialComplete(credential?.key, credential?.env ?: emptyMap())) {
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
            val transcript = agent?.state?.value?.messages ?: activeSession!!.messages
            val newAgent = tryCreateAgent(candidate, activeSession!!.id, transcript) ?: return
            if (!persistSettings(candidate)) return
            bindAgent(newAgent)
            currentSettings = candidate
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
        try {
            credentials.setCredential(providerId, ApiKeyCredential(newKey, env))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE)
            return
        }

        // Success signal for the UI layer: only a confirmed persistence
        // closes the credential form (it pops one ProviderAuth entry when
        // this epoch changes); a failed or incomplete save above returns
        // without bumping it, so the form and its typed inputs survive.
        updateState { it.copy(credentialSuccessEpoch = it.credentialSuccessEpoch + 1) }

        // Partly mirrors pi's completeProviderAuthentication: logging in
        // completes configuration (direct transition to the chat) when valid
        // model settings are already selected; otherwise the forced first-run flow
        // advances to its second step — the model settings form — with an
        // epoch bump so configured models are immediately selectable.
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

    /**
     * True iff settings name a catalog provider+model AND the provider's
     * credential is complete (every auth prompt has a value).
     */
    private suspend fun isConfigured(settings: ModelSettings): Boolean {
        val provider = catalog.getProvider(settings.providerId) ?: return false
        if (provider.model(settings.modelId) == null) return false
        val credential = credentials.getCredential(settings.providerId)
        return provider.isCredentialComplete(credential?.key, credential?.env ?: emptyMap())
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
                    val credential = credentials.getCredential(provider.id)
                    ProviderOption(
                        id = provider.id,
                        name = provider.name,
                        configured = provider.isCredentialComplete(credential?.key, credential?.env ?: emptyMap()),
                    )
                }
                .sortedBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE)
            return
        }
        val configured = configuredOverride ?: try {
            isConfigured(currentSettings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(ERROR_CREDENTIAL_SAVE)
            return
        }
        val configuredIds = providerOptions.filter { it.configured }.map { it.id }.toSet()
        // Pi's model-selector rule: only models from configured providers.
        val modelOptions = catalog.providers
            .filter { it.id in configuredIds }
            .flatMap { provider ->
                provider.models.map { model ->
                    ModelOption(
                        providerId = provider.id,
                        providerName = provider.name,
                        modelId = model.id,
                        name = model.name,
                    )
                }
            }
            .sortedWith(compareBy({ it.providerName }, { it.name }))
        updateState {
            it.copy(
                providerOptions = providerOptions,
                modelOptions = modelOptions,
                selectedModel = selectedModelProjection(currentSettings),
                configured = configured,
            )
        }
    }

    private fun selectedModelProjection(settings: ModelSettings): SelectedModel? {
        val provider = catalog.getProvider(settings.providerId) ?: return null
        val model = provider.model(settings.modelId) ?: return null
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
            setError(ERROR_SETTINGS_SAVE)
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

    private fun setError(message: String) {
        updateState { it.copy(error = message) }
    }

    private fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _uiState.update { current ->
            val next = transform(current)
            next.copy(canSend = next.status == ChatStatus.Ready && !next.isStreaming && next.draft.isNotBlank())
        }
    }

    private companion object {
        const val DEFAULT_SESSION_TITLE = "New chat"
        const val TITLE_MAX_LENGTH = 48

        const val ERROR_INIT = "Could not load chat data"
        const val ERROR_CREDENTIAL_INCOMPLETE =
            "Complete this provider's sign-in values before using its models"
        const val ERROR_UNKNOWN_MODEL = "Unknown model"
        const val ERROR_UNKNOWN_PROVIDER = "Unknown provider"
        const val ERROR_CREDENTIAL_SAVE = "Could not store the API key"
        const val ERROR_SETTINGS_SAVE = "Could not save the configuration"
        const val ERROR_CONFIG_INVALID = "Invalid configuration"
        const val ERROR_SESSION_CREATE = "Could not create a new chat"
        const val ERROR_SESSION_LOAD = "Could not open the chat"
        const val ERROR_SESSION_MISSING = "That chat no longer exists"
        const val ERROR_SESSION_SAVE = "Could not save the chat"
        const val ERROR_BUSY = "Wait for the response to finish first"
        const val ERROR_ALREADY_STREAMING = "A response is already streaming"

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
 * UI projection of the committed transcript as ordered blocks: text parts
 * stay separate, runs of consecutive thinking parts merge into one block
 * (pi's assistant-message semantics), and blank parts drop. Keys are stable
 * per committed index+role+timestamp so that same-millisecond
 * user/assistant messages can never collide.
 */
private fun projectCommitted(messages: List<Message>): List<ChatMessage> =
    messages.mapIndexed { index, message ->
        when (message) {
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
    }.filterNotNull()

/** UI projection of the in-flight partial; distinct key namespace from committed messages. */
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
