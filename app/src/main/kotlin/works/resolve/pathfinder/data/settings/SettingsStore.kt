package works.resolve.pathfinder.data.settings

import works.resolve.pathfinder.ai.core.ModelThinkingLevel

/**
 * Settings boundary for UI-layer code, kept separate from
 * [SettingsRepository] so JVM tests can substitute a failing store.
 */
interface SettingsStore {
    suspend fun currentSettings(): ModelSettings

    suspend fun setProviderId(providerId: String)

    suspend fun setModelId(modelId: String)

    suspend fun setActiveSessionId(sessionId: String?)

    suspend fun setShowThinking(showThinking: Boolean)

    /** `null` clears the setting so pi's default ("medium") applies. */
    suspend fun setDefaultThinkingLevel(level: ModelThinkingLevel?)

    suspend fun setRetrySettings(settings: RetrySettings)

    suspend fun setCompactionSettings(settings: works.resolve.pathfinder.agent.compaction.CompactionSettings)

    /** `null` clears the model scope so all models are available. */
    suspend fun setEnabledModels(models: List<String>?)
}
