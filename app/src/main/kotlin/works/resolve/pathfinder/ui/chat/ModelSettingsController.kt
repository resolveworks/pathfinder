package works.resolve.pathfinder.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.SessionError
import works.resolve.pathfinder.codingagent.core.SettingsManager
import works.resolve.pathfinder.codingagent.core.resolveModelScope

/**
 * Model/thinking-settings ownership: the startup default, thinking default,
 * and model scope, plus the live session's model/thinking switches (pi's
 * picker gestures). Runs in [scope]; failures surface through [onError].
 */
internal class ModelSettingsController(
    private val scope: CoroutineScope,
    private val settingsManager: SettingsManager,
    private val catalog: ProviderCatalog,
    /** Resolves a provider/model pair to the effective request model; throwing input is surfaced as a safe unknown-model error. */
    private val modelResolver: (providerId: String, modelId: String) -> Model,
    /** The bound session; null while none is bound. */
    private val agent: () -> AgentSession?,
    /** The credential-derived surfaces scope curation resolves against (the credentials controller's live snapshot). */
    private val modelOptions: () -> List<ModelOption>,
    private val availableModels: () -> List<Model>,
    /** Re-projects tree rows after a session-applied model/thinking change. */
    private val onSessionApplied: () -> Unit,
    private val onError: (message: UiString, cause: Throwable?) -> Unit
) {
    /** Persisted model settings projection ([ChatUiState.defaultModel] and friends mirror this). */
    data class State(
        val defaultModel: ModelOption? = null,
        val defaultThinkingLevel: ModelThinkingLevel? = null,
        val enabledModels: List<String>? = null
    )

    private val _state = MutableStateFlow(State())

    val state: StateFlow<State> = _state.asStateFlow()

    fun selectModel(providerId: String, modelId: String) {
        scope.launch { selectModelInternal(providerId, modelId) }
    }

    fun saveStartupDefault(providerId: String, modelId: String) {
        scope.launch { saveStartupDefaultInternal(providerId, modelId) }
    }

    fun toggleModelScope(providerId: String, modelId: String, checked: Boolean) {
        scope.launch { toggleModelScopeInternal(providerId, modelId, checked) }
    }

    /**
     * Switches the live session's thinking level. Not busy-rejected: like
     * pi, a mid-stream pick is safe — the active run keeps its start-of-run
     * level and the switch applies to the next prompt. Does NOT persist the
     * default; that lives in [setThinkingLevelDefault].
     */
    fun selectThinkingLevel(level: ModelThinkingLevel) {
        scope.launch {
            val session = agent() ?: return@launch
            try {
                session.setThinkingLevel(level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionError) {
                onError(UiString(R.string.error_session_save), e)
                return@launch
            } catch (e: Exception) {
                onError(UiString(R.string.error_thinking_switch), e)
                return@launch
            }
            onSessionApplied()
        }
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
        scope.launch {
            val session = agent()
            if (session != null) {
                try {
                    session.setThinkingLevel(level, persist = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SessionError) {
                    onError(UiString(R.string.error_session_save), e)
                    return@launch
                } catch (e: Exception) {
                    onError(UiString(R.string.error_thinking_switch), e)
                    return@launch
                }
            } else {
                try {
                    settingsManager.setDefaultThinkingLevel(level)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(UiString(R.string.error_settings_save), e)
                    return@launch
                }
            }
            surfaceSettingsErrors()
            projectSettings()
            if (session != null) {
                onSessionApplied()
            }
        }
    }

    /** Catalog display projection of a live session's model; null when unknown. */
    fun modelOption(model: Model): ModelOption? = selectedModelProjection(model)

    private suspend fun selectModelInternal(providerId: String, modelId: String) {
        val session = agent()
        if (session == null) {
            onError(UiString(R.string.error_config_invalid), null)
            return
        }
        val model = try {
            modelResolver(providerId, modelId.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(UiString(R.string.error_unknown_model), e)
            return
        }
        // No availability pre-check: the picker only offers
        // credential-filtered models (pi's split), and setModel validates
        // auth itself.
        try {
            // pi's picker gesture: one call both switches the live session
            // and persists the startup default (setModel with persist, which
            // also appends to a non-empty resolved scope).
            session.setModel(model, persist = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionError) {
            // A failed model_change append is a save failure, not a switch
            // failure — the agent already switched in memory.
            onError(UiString(R.string.error_session_save), e)
            return
        } catch (e: Exception) {
            onError(UiString(R.string.error_model_switch), e)
            return
        }
        surfaceSettingsErrors()
        // The chip follows the agent's state emission from setModel above;
        // only the tree needs re-projecting here. The new model's thinking
        // level is re-applied inside setModel (pi's rule) from the shared
        // settings manager's default.
        onSessionApplied()
        projectSettings()
    }

    private suspend fun saveStartupDefaultInternal(providerId: String, modelId: String) {
        // Deliberately NOT the scope-append path: that lives in
        // AgentSession's setModel persist gesture; pi's settings-file edit
        // (this action's analog) does not touch the model scope either.
        try {
            settingsManager.setDefaultModelAndProvider(providerId, modelId.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(UiString(R.string.error_settings_save), e)
            return
        }
        surfaceSettingsErrors()
        projectSettings()
    }

    private suspend fun toggleModelScopeInternal(
        providerId: String,
        modelId: String,
        checked: Boolean
    ) {
        val modelOptions = modelOptions()
        val reference = "$providerId/$modelId"
        // The curated list is written in display order; an absent scope
        // materializes as "everything currently offered" on first edit.
        val displayOrder = modelOptions.map(ModelOption::key)
        val storedList = settingsManager.getEnabledModels()
        val current = storedList?.toSet() ?: displayOrder.toSet()
        val next = if (checked) current + reference else current - reference
        val stored = storedList.orEmpty()
        // Preserve stored references not currently displayed (e.g. of a
        // provider whose credential was removed) in their stored order.
        val ordered =
            displayOrder.filter { it in next } + stored.filter { it !in displayOrder && it in next }
        // As in pi: a FULL selection persists as the unset scope, but an
        // EMPTY selection persists as an empty list (which behaves as no
        // scope downstream). Fullness is set equality — preserved stale
        // references keep the list materialized.
        val availableKeys = displayOrder.toSet()
        val fullSelection = availableKeys.isNotEmpty() &&
            next.size == availableKeys.size && next.all { it in availableKeys }
        val scope = if (fullSelection) null else ordered
        try {
            settingsManager.setEnabledModels(scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(UiString(R.string.error_settings_save), e)
            return
        }
        surfaceSettingsErrors()
        // pi's updateSessionModels: resolve the stored patterns against the
        // currently available models and update the live session's scoped
        // models; a null/all-enabled or match-free selection clears them.
        val scoped = if (scope != null && scope.any { it in availableKeys }) {
            resolveModelScope(scope, availableModels()).scopedModels
        } else {
            emptyList()
        }
        agent()?.setScopedModels(scoped)
        projectSettings()
    }

    /** Re-projects persisted settings into the controller state after a write. */
    fun projectSettings() {
        val settings = settingsManager.getSettings()
        val defaultModel = settings
            .takeIf { !it.defaultProvider.isNullOrBlank() && !it.defaultModel.isNullOrBlank() }
            ?.let { selectedModelProjection(it.defaultProvider!!, it.defaultModel!!) }
        _state.update {
            State(
                defaultModel = defaultModel,
                defaultThinkingLevel = settings.defaultThinkingLevel,
                enabledModels = settings.enabledModels
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

    /**
     * The shared settings manager records write failures instead of
     * throwing (pi's SettingsManager.save); drain them after mutations and
     * surface each as a safe settings error.
     */
    private fun surfaceSettingsErrors() {
        for (error in settingsManager.drainErrors()) {
            onError(UiString(R.string.error_settings_save), error)
        }
    }
}
