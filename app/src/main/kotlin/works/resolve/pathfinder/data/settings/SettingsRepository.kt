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
import works.resolve.pathfinder.tools.websearch.SearchProviderService

/**
 * App-owned preferences plus the storage backend for the runtime settings
 * JSON. The runtime JSON is mutated only through SettingsManager — this
 * class's [withLock] is the storage mechanism, not a write API.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsStorage {

    private object Keys {
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val SHOW_THINKING = booleanPreferencesKey("show_thinking")
        val CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
        val ACTIVE_SEARCH_PROVIDER_ID = stringPreferencesKey("active_search_provider_id")

        /** Raw pi settings JSON; owned by `SettingsManager`, not typed here. */
        val SETTINGS_JSON = stringPreferencesKey("settings_json")

        /** Stable per-install device ID (pi's `LoginOptions.getDeviceId`). */
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            activeSessionId = prefs[Keys.ACTIVE_SESSION_ID]?.takeIf { it.isNotBlank() },
            showThinking = prefs[Keys.SHOW_THINKING] ?: false,
            customPrompt = prefs[Keys.CUSTOM_PROMPT] ?: "",
            activeSearchProviderId =
                prefs[Keys.ACTIVE_SEARCH_PROVIDER_ID] ?: SearchProviderService.DEFAULT_PROVIDER_ID
        )
    }

    suspend fun setActiveSessionId(sessionId: String?) {
        dataStore.edit { prefs ->
            if (sessionId == null) {
                prefs.remove(Keys.ACTIVE_SESSION_ID)
            } else {
                prefs[Keys.ACTIVE_SESSION_ID] = sessionId
            }
        }
    }

    suspend fun setActiveSearchProviderId(providerId: String) {
        dataStore.edit { it[Keys.ACTIVE_SEARCH_PROVIDER_ID] = providerId }
    }

    suspend fun setShowThinking(showThinking: Boolean) {
        dataStore.edit { it[Keys.SHOW_THINKING] = showThinking }
    }

    suspend fun setCustomPrompt(customPrompt: String) {
        dataStore.edit { it[Keys.CUSTOM_PROMPT] = customPrompt }
    }

    /**
     * The stable ID of this app installation, generated on first use and
     * returned unchanged afterwards; logins send it as the agent host ID.
     * DataStore's serialized `edit` calls keep concurrent first reads from
     * producing two IDs.
     */
    suspend fun deviceId(): String {
        val existing = dataStore.data.first()[Keys.DEVICE_ID]
        if (existing != null) return existing
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            prefs[Keys.DEVICE_ID] = prefs[Keys.DEVICE_ID] ?: generated
        }
        return dataStore.data.first()[Keys.DEVICE_ID]
            ?: throw IllegalStateException("device id missing after write")
    }

    suspend fun currentSettings(): AppSettings = settings.first()

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
