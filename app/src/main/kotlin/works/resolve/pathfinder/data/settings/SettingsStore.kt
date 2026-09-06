package works.resolve.pathfinder.data.settings

/**
 * App-owned preference boundary, kept separate from
 * [SettingsRepository] so JVM tests can substitute a failing store.
 * Runtime model/thinking/retry/compaction/scope values live in the runtime
 * settings JSON and mutate only through
 * [works.resolve.pathfinder.codingagent.core.SettingsManager].
 */
interface SettingsStore {
    suspend fun currentSettings(): AppSettings

    suspend fun setActiveSessionId(sessionId: String?)

    suspend fun setShowThinking(showThinking: Boolean)
}
