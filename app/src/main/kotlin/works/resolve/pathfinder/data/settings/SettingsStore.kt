package works.resolve.pathfinder.data.settings

import works.resolve.pathfinder.ai.core.ModelThinkingLevel

/**
 * Narrow settings boundary used by UI-layer code: read the current settings
 * and write individual fields. Keeping this interface separate from
 * [SettingsRepository] lets JVM tests substitute a failing store.
 */
interface SettingsStore {
    suspend fun currentSettings(): ModelSettings

    suspend fun setProviderId(providerId: String)

    suspend fun setModelId(modelId: String)

    suspend fun setActiveSessionId(sessionId: String?)

    suspend fun setShowThinking(showThinking: Boolean)

    /**
     * Persists the default thinking level (pi's `defaultThinkingLevel`
     * setting). `null` clears it so the pi default ("medium") applies.
     */
    suspend fun setDefaultThinkingLevel(level: ModelThinkingLevel?)

    /** Persists the agent auto-retry settings wholesale (pi's settings.retry). */
    suspend fun setRetrySettings(settings: RetrySettings)

    /** Persists the compaction thresholds wholesale (pi's settings compaction object). */
    suspend fun setCompactionSettings(settings: works.resolve.pathfinder.agent.compaction.CompactionSettings)

    /**
     * Persists the ordered scoped-model references wholesale (pi's `enabledModels`).
     * `null` clears the scope so all models are available.
     */
    suspend fun setEnabledModels(models: List<String>?)
}
