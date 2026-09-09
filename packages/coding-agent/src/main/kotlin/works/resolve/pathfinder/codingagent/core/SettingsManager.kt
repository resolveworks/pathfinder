package works.resolve.pathfinder.codingagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.modelThinkingLevelFromWire
import works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings
import works.resolve.pathfinder.codingagent.core.compaction.DEFAULT_COMPACTION_SETTINGS
import works.resolve.pathfinder.codingagent.core.utils.stripBom

/**
 * Divergences from pi's `Settings`: one global scope only (no project scope,
 * trust, or migration of legacy fields), and just the fields pathfinder uses.
 * Fields pi owns that this type doesn't model stay untouched in storage.
 */
data class Settings(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: ModelThinkingLevel? = null,
    /** Per-model thinking overrides keyed by "provider/modelId". */
    val modelThinkingLevels: Map<String, ModelThinkingLevel>? = null,
    val enabledModels: List<String>? = null,
    val compaction: CompactionSettings? = null,
    val retry: RetrySettings? = null
)

/**
 * Atomic read-modify-write access to persisted settings content.
 *
 * [withLock] reads the current raw content (null when nothing is stored),
 * applies [transform], and writes the returned content back only when it is
 * non-null. The whole operation is serialized, so a transform always observes
 * and replaces the latest stored value. Divergence from pi's synchronous
 * `SettingsStorage`: locking is suspendable instead of a blocking file lock;
 * cancellation propagates unchanged.
 */
interface SettingsStorage {
    suspend fun withLock(transform: (current: String?) -> String?)
}

class InMemorySettingsStorage(private var content: String? = null) : SettingsStorage {
    private val mutex = Mutex()

    override suspend fun withLock(transform: (String?) -> String?) = mutex.withLock {
        val next = transform(content)
        if (next != null) {
            content = next
        }
    }
}

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

private fun encodeSettings(settings: Settings): JsonObject = JsonObject(
    buildMap {
        settings.defaultProvider?.let { put("defaultProvider", JsonPrimitive(it)) }
        settings.defaultModel?.let { put("defaultModel", JsonPrimitive(it)) }
        settings.defaultThinkingLevel?.let { put("defaultThinkingLevel", JsonPrimitive(it.wire)) }
        settings.modelThinkingLevels?.let { levels ->
            put(
                "modelThinkingLevels",
                JsonObject(
                    levels.mapValues { (_, level) ->
                        JsonPrimitive(level.wire)
                    }
                )
            )
        }
        settings.enabledModels?.let { put("enabledModels", JsonArray(it.map(::JsonPrimitive))) }
        settings.compaction?.let { put("compaction", encodeCompaction(it)) }
        settings.retry?.let { put("retry", encodeRetry(it)) }
    }
)

private fun encodeCompaction(compaction: CompactionSettings): JsonObject = JsonObject(
    mapOf(
        "enabled" to JsonPrimitive(compaction.enabled),
        "reserveTokens" to JsonPrimitive(compaction.reserveTokens),
        "keepRecentTokens" to JsonPrimitive(compaction.keepRecentTokens)
    )
)

private fun encodeRetry(retry: RetrySettings): JsonObject = JsonObject(
    mapOf(
        "enabled" to JsonPrimitive(retry.enabled),
        "maxRetries" to JsonPrimitive(retry.maxRetries),
        "baseDelayMs" to JsonPrimitive(retry.baseDelayMs)
    )
)

/**
 * Tolerant decode: unknown fields and non-object roots yield defaults, and an
 * invalid thinking-level value is ignored rather than rejecting the file —
 * pi stores thinking levels as unvalidated strings, so no other field may be
 * lost to one bad value.
 */
private fun decodeSettings(content: String): Settings {
    val obj = Json.parseToJsonElement(stripBom(content)) as? JsonObject ?: return Settings()
    return Settings(
        defaultProvider = obj.stringField("defaultProvider"),
        defaultModel = obj.stringField("defaultModel"),
        defaultThinkingLevel = obj.stringField(
            "defaultThinkingLevel"
        )?.let(::modelThinkingLevelFromWire),
        modelThinkingLevels = (obj["modelThinkingLevels"] as? JsonObject)?.let { levels ->
            buildMap {
                for ((key, value) in levels) {
                    val level = (value as? JsonPrimitive)?.content?.let(
                        ::modelThinkingLevelFromWire
                    )
                    if (level != null) {
                        put(key, level)
                    }
                }
            }
        },
        enabledModels = (obj["enabledModels"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content
        },
        compaction = (obj["compaction"] as? JsonObject)?.let(::decodeCompaction),
        retry = (obj["retry"] as? JsonObject)?.let(::decodeRetry)
    )
}

private fun decodeCompaction(obj: JsonObject) = CompactionSettings(
    enabled = obj.booleanField("enabled") ?: DEFAULT_COMPACTION_SETTINGS.enabled,
    reserveTokens = obj.intField("reserveTokens") ?: DEFAULT_COMPACTION_SETTINGS.reserveTokens,
    keepRecentTokens =
        obj.intField("keepRecentTokens") ?: DEFAULT_COMPACTION_SETTINGS.keepRecentTokens
)

private fun decodeRetry(obj: JsonObject) = RetrySettings(
    enabled = obj.booleanField("enabled") ?: true,
    maxRetries = obj.intField("maxRetries") ?: 3,
    baseDelayMs = obj.longField("baseDelayMs") ?: 2000
)

private fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.content

private fun JsonObject.booleanField(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun JsonObject.intField(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.longField(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private val nestedFields = setOf("compaction", "retry")

/**
 * pi's `SettingsManager`, reduced to a single global scope. Divergences:
 * suspendable setters perform the read-modify-write synchronously (no write
 * queue or `flush`), and only fields modified during the session are merged
 * into stored content, so externally stored fields are preserved.
 */
class SettingsManager private constructor(
    private val storage: SettingsStorage,
    initialSettings: Settings,
    initialError: Throwable?
) {
    private var settings = initialSettings
    private var loadError = initialError
    private val errors = mutableListOf<Throwable>().apply { initialError?.let(::add) }
    private val writeMutex = Mutex()

    companion object {
        suspend fun fromStorage(storage: SettingsStorage): SettingsManager {
            val (loaded, error) = tryLoad(storage)
            return SettingsManager(storage, loaded, error)
        }

        suspend fun inMemory(settings: Settings = Settings()): SettingsManager {
            val storage = InMemorySettingsStorage()
            storage.withLock { encodeSettings(settings).toString() }
            return fromStorage(storage)
        }

        private suspend fun tryLoad(storage: SettingsStorage): Pair<Settings, Throwable?> {
            // pi's loadFromStorage treats empty content like nothing stored.
            val content = readContent(storage)
            if (content == null || content.isEmpty()) {
                return Settings() to null
            }
            return try {
                decodeSettings(content) to null
            } catch (e: Exception) {
                Settings() to e
            }
        }

        private suspend fun readContent(storage: SettingsStorage): String? {
            var content: String? = null
            storage.withLock { current ->
                content = current
                null
            }
            return content
        }
    }

    suspend fun reload() {
        writeMutex.withLock {
            val (loaded, error) = tryLoad(storage)
            if (error == null) {
                settings = loaded
                loadError = null
            } else {
                loadError = error
                errors.add(error)
            }
        }
    }

    fun drainErrors(): List<Throwable> {
        val drained = errors.toList()
        errors.clear()
        return drained
    }

    fun getSettings(): Settings = settings

    fun getDefaultProvider(): String? = settings.defaultProvider

    fun getDefaultModel(): String? = settings.defaultModel

    suspend fun setDefaultProvider(provider: String) {
        settings = settings.copy(defaultProvider = provider)
        save("defaultProvider")
    }

    suspend fun setDefaultModel(modelId: String) {
        settings = settings.copy(defaultModel = modelId)
        save("defaultModel")
    }

    /** Atomic: provider and model are persisted in a single read-modify-write. */
    suspend fun setDefaultModelAndProvider(provider: String, modelId: String) {
        settings = settings.copy(defaultProvider = provider, defaultModel = modelId)
        save("defaultProvider", "defaultModel")
    }

    fun getDefaultThinkingLevel(): ModelThinkingLevel? = settings.defaultThinkingLevel

    suspend fun setDefaultThinkingLevel(level: ModelThinkingLevel) {
        settings = settings.copy(defaultThinkingLevel = level)
        save("defaultThinkingLevel")
    }

    fun getModelThinkingLevel(provider: String, modelId: String): ModelThinkingLevel? =
        settings.modelThinkingLevels?.get("$provider/$modelId")

    fun getAllModelThinkingLevels(): Map<String, ModelThinkingLevel> =
        settings.modelThinkingLevels?.toMap() ?: emptyMap()

    suspend fun setModelThinkingLevel(
        provider: String,
        modelId: String,
        level: ModelThinkingLevel
    ) {
        settings = settings.copy(
            modelThinkingLevels = (settings.modelThinkingLevels ?: emptyMap()) +
                ("$provider/$modelId" to level)
        )
        save("modelThinkingLevels")
    }

    suspend fun removeModelThinkingLevel(provider: String, modelId: String) {
        val current = settings.modelThinkingLevels ?: return
        settings =
            settings.copy(modelThinkingLevels = (current - "$provider/$modelId").ifEmpty { null })
        save("modelThinkingLevels")
    }

    fun getEnabledModels(): List<String>? = settings.enabledModels?.toList()

    suspend fun setEnabledModels(patterns: List<String>?) {
        settings = settings.copy(enabledModels = patterns?.toList())
        save("enabledModels")
    }

    fun getCompactionSettings(): CompactionSettings =
        settings.compaction ?: DEFAULT_COMPACTION_SETTINGS

    suspend fun setCompactionSettings(compaction: CompactionSettings) {
        settings = settings.copy(compaction = compaction)
        save("compaction")
    }

    fun getRetrySettings(): RetrySettings = settings.retry ?: RetrySettings()

    suspend fun setRetrySettings(retry: RetrySettings) {
        settings = settings.copy(retry = retry)
        save("retry")
    }

    /**
     * Merge only [fields] into the currently stored content, so externally
     * added or edited fields are preserved and in-memory values win for the
     * modified fields. A field whose value is null is removed from storage.
     * Nested fields (compaction, retry) merge into the stored object so
     * unknown nested keys survive, but unlike pi — which persists only the
     * individually modified nested keys — the fully resolved objects are
     * written, materializing defaults for absent known keys.
     *
     * Like pi's `save()`, nothing is written while the last load failed, so
     * an unreadable settings file is never clobbered.
     */
    private suspend fun save(vararg fields: String) {
        if (loadError != null) {
            return
        }
        writeMutex.withLock {
            try {
                storage.withLock { current ->
                    // pi's persistScopedSettings treats empty stored content
                    // as an empty object rather than failing to parse.
                    val base = current?.takeIf { it.isNotEmpty() }?.let {
                        Json.parseToJsonElement(stripBom(it)) as? JsonObject
                    } ?: JsonObject(emptyMap())
                    val merged = base.toMutableMap()
                    val encoded = encodeSettings(settings)
                    for (field in fields) {
                        val value = encoded[field]
                        if (value == null) {
                            merged.remove(field)
                        } else if (field in nestedFields) {
                            val stored = (base[field] as? JsonObject) ?: JsonObject(emptyMap())
                            merged[field] = JsonObject(stored + (value as JsonObject))
                        } else {
                            merged[field] = value
                        }
                    }
                    prettyJson.encodeToString(JsonObject(merged))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add(e)
            }
        }
    }
}
