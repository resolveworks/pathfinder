package works.resolve.aletheia.data.settings

/**
 * Narrow settings boundary used by UI-layer code: read the current settings
 * and write individual fields. Keeping this interface separate from
 * [SettingsRepository] lets JVM tests substitute a failing store.
 */
interface SettingsStore {
    suspend fun currentSettings(): ModelSettings

    suspend fun setProviderId(providerId: String)

    suspend fun setModelId(modelId: String)

    suspend fun setBaseUrl(baseUrl: String?)

    suspend fun setActiveSessionId(sessionId: String?)

    suspend fun setShowThinking(showThinking: Boolean)
}
