package works.resolve.pathfinder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.utils.lenientJson

/** Persistence boundary for model settings, backed by Preferences DataStore. */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val MODEL_ID = stringPreferencesKey("model_id")
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val SHOW_THINKING = booleanPreferencesKey("show_thinking")
        val RETRY_ENABLED = booleanPreferencesKey("retry_enabled")
        val RETRY_MAX_RETRIES = intPreferencesKey("retry_max_retries")
        val RETRY_BASE_DELAY_MS = longPreferencesKey("retry_base_delay_ms")
        val COMPACTION_ENABLED = booleanPreferencesKey("compaction_enabled")
        val COMPACTION_RESERVE_TOKENS = intPreferencesKey("compaction_reserve_tokens")
        val COMPACTION_KEEP_RECENT_TOKENS = intPreferencesKey("compaction_keep_recent_tokens")

        /**
         * `enabledModels` as a JSON array string; Preferences DataStore has no
         * ordered collection type, so `stringSetPreferencesKey` would lose the
         * order pi relies on for Ctrl+P cycling.
         */
        val ENABLED_MODELS = stringPreferencesKey("enabled_models")
    }

    val settings: Flow<ModelSettings> = dataStore.data.map { prefs ->
        ModelSettings(
            providerId = prefs[Keys.PROVIDER_ID] ?: "",
            modelId = prefs[Keys.MODEL_ID] ?: "",
            activeSessionId = prefs[Keys.ACTIVE_SESSION_ID]?.takeIf { it.isNotBlank() },
            showThinking = prefs[Keys.SHOW_THINKING] ?: false,
            retry = RetrySettings(
                enabled = prefs[Keys.RETRY_ENABLED] ?: true,
                maxRetries = prefs[Keys.RETRY_MAX_RETRIES] ?: 3,
                baseDelayMs = prefs[Keys.RETRY_BASE_DELAY_MS] ?: 2000,
            ),
            compaction = works.resolve.pathfinder.agent.compaction.CompactionSettings(
                enabled = prefs[Keys.COMPACTION_ENABLED] ?: true,
                reserveTokens = prefs[Keys.COMPACTION_RESERVE_TOKENS] ?: 16384,
                keepRecentTokens = prefs[Keys.COMPACTION_KEEP_RECENT_TOKENS] ?: 20000,
            ),
            enabledModels = prefs[Keys.ENABLED_MODELS]?.let(::decodeEnabledModels),
        )
    }

    override suspend fun setRetrySettings(settings: RetrySettings) {
        dataStore.edit { prefs ->
            prefs[Keys.RETRY_ENABLED] = settings.enabled
            prefs[Keys.RETRY_MAX_RETRIES] = settings.maxRetries
            prefs[Keys.RETRY_BASE_DELAY_MS] = settings.baseDelayMs
        }
    }

    override suspend fun setCompactionSettings(settings: works.resolve.pathfinder.agent.compaction.CompactionSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.COMPACTION_ENABLED] = settings.enabled
            prefs[Keys.COMPACTION_RESERVE_TOKENS] = settings.reserveTokens
            prefs[Keys.COMPACTION_KEEP_RECENT_TOKENS] = settings.keepRecentTokens
        }
    }

    /**
     * Decodes the stored `enabledModels` JSON array of string primitives.
     * The stored format is current-only (no legacy migrations): malformed
     * JSON, a non-array value, or a non-string element is rejected with
     * [IllegalArgumentException] rather than degraded. An absent preference
     * key yields no configured scope.
     */
    internal fun decodeEnabledModels(encoded: String): List<String> {
        val array = lenientJson.parseToJsonElement(encoded) as? JsonArray
            ?: throw IllegalArgumentException(
                "Malformed enabled_models setting: expected a JSON array of strings, got: $encoded",
            )
        return array.map { element ->
            (element as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?: throw IllegalArgumentException(
                    "Malformed enabled_models setting: expected string elements, got: $encoded",
                )
        }
    }

    override suspend fun setEnabledModels(models: List<String>?) {
        dataStore.edit { prefs ->
            if (models == null) prefs.remove(Keys.ENABLED_MODELS)
            else prefs[Keys.ENABLED_MODELS] = JsonArray(models.map { JsonPrimitive(it) }).toString()
        }
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
