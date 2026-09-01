package works.resolve.pathfinder.data.settings

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
     * Persists the scoped model set. `null` restores the uncurated default
     * (every model of configured providers).
     */
    suspend fun setEnabledModels(models: Set<String>?)

    /** Persists the thinking preference of one `provider/model` ref. */
    suspend fun setThinkingPref(modelRef: String, label: String)
}
