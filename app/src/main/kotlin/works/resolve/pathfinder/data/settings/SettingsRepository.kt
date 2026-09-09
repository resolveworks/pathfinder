package works.resolve.pathfinder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import works.resolve.pathfinder.codingagent.core.SettingsStorage

/**
 * App-owned preferences plus the storage backend for the runtime settings
 * JSON. The runtime JSON is mutated only through SettingsManager — this
 * class's [withLock] is the storage mechanism, not a write API.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) :
    SettingsStore,
    SettingsStorage {

    private object Keys {
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val SHOW_THINKING = booleanPreferencesKey("show_thinking")
        val SELECTED_SSH_HOST_ID = stringPreferencesKey("selected_ssh_host_id")

        /** Raw pi settings JSON; owned by `SettingsManager`, not typed here. */
        val SETTINGS_JSON = stringPreferencesKey("settings_json")
    }

    private val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            activeSessionId = prefs[Keys.ACTIVE_SESSION_ID]?.takeIf { it.isNotBlank() },
            showThinking = prefs[Keys.SHOW_THINKING] ?: false,
            selectedSshHostId =
                prefs[Keys.SELECTED_SSH_HOST_ID]?.takeIf { it.isNotBlank() }
        )
    }

    override suspend fun setActiveSessionId(sessionId: String?) {
        dataStore.edit { prefs ->
            if (sessionId == null) {
                prefs.remove(Keys.ACTIVE_SESSION_ID)
            } else {
                prefs[Keys.ACTIVE_SESSION_ID] = sessionId
            }
        }
    }

    override suspend fun setShowThinking(showThinking: Boolean) {
        dataStore.edit { it[Keys.SHOW_THINKING] = showThinking }
    }

    override suspend fun setSelectedSshHostId(hostId: String?) {
        dataStore.edit { prefs ->
            if (hostId == null) {
                prefs.remove(Keys.SELECTED_SSH_HOST_ID)
            } else {
                prefs[Keys.SELECTED_SSH_HOST_ID] = hostId
            }
        }
    }

    override suspend fun currentSettings(): AppSettings = settings.first()

    /**
     * Preferences DataStore serializes `edit` calls, giving the atomic
     * read-modify-write the contract requires. A null transform result means
     * no write; cancellation propagates unchanged.
     */
    override suspend fun withLock(transform: (current: String?) -> String?) {
        dataStore.edit { prefs ->
            val next = transform(prefs[Keys.SETTINGS_JSON])
            if (next != null) {
                prefs[Keys.SETTINGS_JSON] = next
            }
        }
    }
}
