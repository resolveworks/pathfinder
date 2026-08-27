package works.resolve.pathfinder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Persistence boundary for model settings, backed by Preferences DataStore. */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val MODEL_ID = stringPreferencesKey("model_id")
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val SHOW_THINKING = booleanPreferencesKey("show_thinking")
    }

    val settings: Flow<ModelSettings> = dataStore.data.map { prefs ->
        ModelSettings(
            providerId = prefs[Keys.PROVIDER_ID] ?: "",
            modelId = prefs[Keys.MODEL_ID] ?: "",
            activeSessionId = prefs[Keys.ACTIVE_SESSION_ID]?.takeIf { it.isNotBlank() },
            showThinking = prefs[Keys.SHOW_THINKING] ?: false,
        )
    }

    override suspend fun setProviderId(providerId: String) {
        dataStore.edit { it[Keys.PROVIDER_ID] = providerId }
    }

    override suspend fun setModelId(modelId: String) {
        dataStore.edit { it[Keys.MODEL_ID] = modelId }
    }

    override suspend fun setActiveSessionId(sessionId: String?) {
        dataStore.edit { prefs ->
            if (sessionId == null) prefs.remove(Keys.ACTIVE_SESSION_ID)
            else prefs[Keys.ACTIVE_SESSION_ID] = sessionId
        }
    }

    override suspend fun setShowThinking(showThinking: Boolean) {
        dataStore.edit { it[Keys.SHOW_THINKING] = showThinking }
    }

    /** One-shot read; convenient for non-reactive callers. */
    override suspend fun currentSettings(): ModelSettings = settings.first()
}
