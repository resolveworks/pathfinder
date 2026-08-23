package com.aletheia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aletheia.agent.Agent
import com.aletheia.agent.AgentState
import com.aletheia.ai.core.AssistantMessage
import com.aletheia.ai.core.Content
import com.aletheia.ai.core.Message
import com.aletheia.ai.core.TextContent
import com.aletheia.ai.core.UserMessage
import com.aletheia.ai.providers.ZaiModels
import com.aletheia.data.credentials.ApiKeyStore
import com.aletheia.data.settings.ModelSettings
import com.aletheia.data.settings.SettingsStore
import com.aletheia.data.settings.SettingsRepository
import com.aletheia.data.sessions.Session
import com.aletheia.data.sessions.SessionRepository
import com.aletheia.data.sessions.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Builds the [Agent] for a session from the persisted configuration. The real
 * implementation (next composition chunk) constructs Models/ZaiProvider with
 * OkHttp, resolves the API key from [ApiKeyStore], and normalizes/validates
 * the base URL; tests script a fake [Agent] with a fake stream function.
 *
 * [settings.baseUrl] is already trimmed by the ViewModel; implementations
 * validate it (e.g. reject blank) by throwing [IllegalArgumentException].
 */
fun interface AgentFactory {
    fun create(settings: ModelSettings, sessionId: String, initialTranscript: List<Message>): Agent
}

/**
 * Chat screen controller. Owns configuration, sessions, and the active
 * [Agent]; projects everything into an immutable [ChatUiState] (UDF).
 *
 * The provider is fixed to Z.AI for the MVP. The API key never enters
 * [ChatUiState] (only a boolean presence flag) and is never logged.
 *
 * Transcript persistence runs through a single latest-snapshot pipeline: at
 * most one save per session is in flight, superseded snapshots are coalesced,
 * and session switches wait for pending saves so transcripts always stay with
 * the session they belong to. A failed save surfaces an error and blocks
 * session/config switches; the blocked intent explicitly retries the latest
 * snapshot and only proceeds once it is saved, so an unsaved transcript is
 * never silently abandoned.
 */
class ChatViewModel(
    private val settingsRepository: SettingsStore,
    private val credentials: ApiKeyStore,
    private val sessionStore: SessionRepository,
    private val agentFactory: AgentFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(modelOptions = CATALOG_OPTIONS))
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
     * Persists the configuration and (re)builds the agent. A blank
     * [apiKeyInput] retains an existing stored key, but the initial
     * configuration requires one.
     */
    fun saveConfiguration(modelId: String, baseUrl: String?, apiKeyInput: String) {
        viewModelScope.launch { saveConfigurationInternal(modelId, baseUrl, apiKeyInput) }
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
                    activateSession(session, newAgent)
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
            val hasKey = !credentials.getApiKey(PROVIDER_ID).isNullOrBlank()
            val summaries = sessionStore.summaries()

            val configured = settings.providerId == PROVIDER_ID &&
                settings.modelId in CATALOG_IDS &&
                hasKey

            if (!configured) {
                currentSettings = settings
                updateState {
                    it.copy(
                        status = ChatStatus.NeedsConfiguration,
                        hasApiKey = hasKey,
                        selectedModelId = settings.modelId.takeIf { m -> m in CATALOG_IDS },
                        baseUrl = settings.baseUrl,
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
                        hasApiKey = true,
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
            // summaries; only the configuration fields remain.
            updateState {
                it.copy(
                    status = ChatStatus.Ready,
                    hasApiKey = true,
                    selectedModelId = settings.modelId,
                    baseUrl = settings.baseUrl,
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
     * binds the agent, and refreshes the UI. Only called after the factory
     * accepted the settings. Returns false when persisting the active id
     * fails; in that case nothing is committed.
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
        val summaries = sessionStore.summaries()
        val session = resolveSession(settings, summaries)
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

    private suspend fun saveConfigurationInternal(modelId: String, baseUrl: String?, apiKeyInput: String) {
        val trimmedModelId = modelId.trim()
        if (trimmedModelId !in CATALOG_IDS) {
            setError(ERROR_UNKNOWN_MODEL)
            return
        }
        if (rejectWhileBusy()) return

        val keyInput = apiKeyInput.trim()
        if (keyInput.isNotEmpty()) {
            try {
                credentials.setApiKey(PROVIDER_ID, keyInput)
                // The stored key now exists even if a later step fails; keep
                // the safe boolean in sync so a blank-key retry retains it.
                updateState { it.copy(hasApiKey = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_CREDENTIAL_SAVE)
                return
            }
        } else if (!_uiState.value.hasApiKey) {
            setError(ERROR_KEY_REQUIRED)
            return
        }

        val normalizedBaseUrl = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
        val candidate = ModelSettings(
            providerId = PROVIDER_ID,
            modelId = trimmedModelId,
            baseUrl = normalizedBaseUrl,
            activeSessionId = activeSession?.id,
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
            val prepared = try {
                prepareAdoption(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(ERROR_SESSION_CREATE)
                return
            } ?: return
            if (!persistSettings(candidate)) return
            if (!activateSession(prepared.first, prepared.second)) return
            currentSettings = candidate
        }

        updateState {
            it.copy(
                status = ChatStatus.Ready,
                hasApiKey = true,
                selectedModelId = candidate.modelId,
                baseUrl = candidate.baseUrl,
            )
        }
    }

    /** Persists the validated configuration; false (with a safe error) on failure. */
    private suspend fun persistSettings(settings: ModelSettings): Boolean {
        try {
            settingsRepository.setProviderId(settings.providerId)
            settingsRepository.setModelId(settings.modelId)
            settingsRepository.setBaseUrl(settings.baseUrl)
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
        const val PROVIDER_ID = ZaiModels.PROVIDER_ID
        const val DEFAULT_SESSION_TITLE = "New chat"
        const val TITLE_MAX_LENGTH = 48

        val CATALOG_OPTIONS: List<ChatModelOption> =
            ZaiModels.ALL.map { ChatModelOption(id = it.id, name = it.name) }
        val CATALOG_IDS: Set<String> = ZaiModels.ALL.map { it.id }.toSet()

        const val ERROR_INIT = "Could not load chat data"
        const val ERROR_KEY_REQUIRED = "An API key is required for the initial configuration"
        const val ERROR_UNKNOWN_MODEL = "Unknown model"
        const val ERROR_CREDENTIAL_SAVE = "Could not store the API key"
        const val ERROR_SETTINGS_SAVE = "Could not save the configuration"
        const val ERROR_CONFIG_INVALID = "Invalid configuration"
        const val ERROR_SESSION_CREATE = "Could not create a new chat"
        const val ERROR_SESSION_LOAD = "Could not open the chat"
        const val ERROR_SESSION_MISSING = "That chat no longer exists"
        const val ERROR_SESSION_SAVE = "Could not save the chat"
        const val ERROR_BUSY = "Wait for the response to finish first"
        const val ERROR_ALREADY_STREAMING = "A response is already streaming"

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
 * UI projection of the committed transcript: text (and error) only; thinking
 * is dropped. Keys are stable per committed index+role+timestamp so that
 * same-millisecond user/assistant messages can never collide.
 */
private fun projectCommitted(messages: List<Message>): List<ChatMessage> =
    messages.mapIndexed { index, message ->
        when (message) {
            is UserMessage -> ChatMessage(
                id = "msg-$index-${message.timestamp}",
                role = ChatRole.User,
                text = message.content.textParts(),
            )
            is AssistantMessage -> ChatMessage(
                id = "msg-$index-${message.timestamp}",
                role = ChatRole.Assistant,
                text = message.content.textParts(),
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
        text = message.content.textParts(),
        error = message.errorMessage,
    )

private fun List<Content>.textParts(): String =
    asSequence().filterIsInstance<TextContent>().joinToString(separator = "") { it.text }
