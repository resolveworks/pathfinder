package works.resolve.pathfinder.data.settings

/**
 * App-owned preference values persisted outside the runtime settings JSON
 * (owned by [works.resolve.pathfinder.codingagent.core.SettingsManager]).
 */
data class AppSettings(
    val activeSessionId: String? = null,
    val showThinking: Boolean = false,
    val selectedMachineId: String? = null
)
