package works.resolve.pathfinder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persistence boundary for model settings, backed by Preferences DataStore.
 *
 * [ModelSettings.enabledModels] is stored as a plain string set (absent key
 * = null = uncurated); [ModelSettings.thinkingPrefs] as a string set of
 * `provider/model=label` entries. Refs never contain '=' or newlines (they
 * are catalog ids), so the encoding is unambiguous.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val MODEL_ID = stringPreferencesKey("model_id")
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val SHOW_THINKING = booleanPreferencesKey("show_thinking")
        val ENABLED_MODELS = stringSetPreferencesKey("enabled_models")
        val THINKING_PREFS = stringSetPreferencesKey("thinking_prefs")
    }

    val settings: Flow<ModelSettings> = dataStore.data.map { prefs ->
        ModelSettings(
            providerId = prefs[Keys.PROVIDER_ID] ?: "",
            modelId = prefs[Keys.MODEL_ID] ?: "",
            activeSessionId = prefs[Keys.ACTIVE_SESSION_ID],
            showThinking = prefs[Keys.SHOW_THINKING] ?: false,
            enabledModels = prefs[Keys.ENABLED_MODELS],
            thinkingPrefs = (prefs[Keys.THINKING_PREFS] ?: emptySet()).associate(::parseThinkingPref),
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

    override suspend fun setEnabledModels(models: Set<String>?) {
        dataStore.edit { prefs ->
            if (models == null) prefs.remove(Keys.ENABLED_MODELS)
            else prefs[Keys.ENABLED_MODELS] = models
        }
    }

    override suspend fun setThinkingPref(modelRef: String, label: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.THINKING_PREFS] ?: emptySet()
            prefs[Keys.THINKING_PREFS] = (
                current.filterNot { parseThinkingPref(it).first == modelRef } + "$modelRef=$label"
                ).toSet()
        }
    }

    /** One-shot read; convenient for non-reactive callers. */
    override suspend fun currentSettings(): ModelSettings = settings.first()

    private fun parseThinkingPref(entry: String): Pair<String, String> {
        val separator = entry.indexOf('=')
        require(separator > 0) { "Malformed thinking preference: $entry" }
        return entry.substring(0, separator) to entry.substring(separator + 1)
    }
}
