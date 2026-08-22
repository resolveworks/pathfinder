package com.aletheia.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Persistence boundary for model settings, backed by Preferences DataStore. */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val MODEL_ID = stringPreferencesKey("model_id")
        val BASE_URL = stringPreferencesKey("base_url")
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
    }

    val settings: Flow<ModelSettings> = dataStore.data.map { prefs ->
        ModelSettings(
            providerId = prefs[Keys.PROVIDER_ID] ?: "",
            modelId = prefs[Keys.MODEL_ID] ?: "",
            baseUrl = prefs[Keys.BASE_URL]?.takeIf { it.isNotBlank() },
            activeSessionId = prefs[Keys.ACTIVE_SESSION_ID]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setProviderId(providerId: String) {
        dataStore.edit { it[Keys.PROVIDER_ID] = providerId }
    }

    suspend fun setModelId(modelId: String) {
        dataStore.edit { it[Keys.MODEL_ID] = modelId }
    }

    suspend fun setBaseUrl(baseUrl: String?) {
        dataStore.edit { prefs ->
            val value = baseUrl?.trim().orEmpty()
            if (value.isEmpty()) prefs.remove(Keys.BASE_URL) else prefs[Keys.BASE_URL] = value
        }
    }

    suspend fun setActiveSessionId(sessionId: String?) {
        dataStore.edit { prefs ->
            if (sessionId == null) prefs.remove(Keys.ACTIVE_SESSION_ID)
            else prefs[Keys.ACTIVE_SESSION_ID] = sessionId
        }
    }

    /** One-shot read; convenient for non-reactive callers. */
    suspend fun currentSettings(): ModelSettings = settings.first()
}
