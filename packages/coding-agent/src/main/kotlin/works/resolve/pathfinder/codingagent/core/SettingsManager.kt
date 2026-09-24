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
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.modelThinkingLevelFromWire
import works.resolve.pathfinder.ai.utils.DEFAULT_MAX_AGENT_RETRY_DELAY_MS
import works.resolve.pathfinder.ai.utils.MAX_SAFE_INTEGER
import works.resolve.pathfinder.ai.utils.boolean
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.long
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strictLong
import works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings as ResolvedCompactionSettings
import works.resolve.pathfinder.codingagent.core.compaction.DEFAULT_COMPACTION_SETTINGS
import works.resolve.pathfinder.codingagent.core.utils.stripBom

/**
 * Divergences from pi's `Settings`: one global scope only (no project scope,
 * trust, or migration of legacy fields), and just the fields pathfinder uses.
 * Fields pi owns that this type doesn't model stay untouched in storage.
 *
 * Thinking levels are typed, so an unknown stored value reads as absent from
 * the typed getters where pi returns the raw string; because only modified
 * fields (and, for `modelThinkingLevels`, only modified entries) are ever
 * persisted, unknown stored values still round-trip until explicitly
 * overwritten. `retry` is held resolved (absent stored keys default at read,
 * like pi); per-key persistence keeps the stored optionals untouched.
 */
data class Settings(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: ModelThinkingLevel? = null,
    /** Per-model thinking overrides keyed by "provider/modelId". */
    val modelThinkingLevels: Map<String, ModelThinkingLevel>? = null,
    val enabledModels: List<String>? = null,
    val compaction: CompactionSettings? = null,
    val branchSummary: BranchSummarySettings? = null,
    val retry: RetrySettings? = null
)

/** pi's CompactionModelOverride: per-model token budgets keyed by "provider/modelId". */
data class CompactionModelOverride(
    val reserveTokens: Long? = null,
    val keepRecentTokens: Long? = null
)

/** pi's settings-manager `CompactionSettings`: the raw optional stored shape. */
data class CompactionSettings(
    val enabled: Boolean? = null,
    val reserveTokens: Long? = null,
    val keepRecentTokens: Long? = null,
    val modelOverrides: Map<String, CompactionModelOverride>? = null
)

/** pi's `BranchSummarySettings`: the raw optional stored shape. */
data class BranchSummarySettings(val reserveTokens: Long? = null, val skipPrompt: Boolean? = null)

/** pi's `getBranchSummarySettings` return shape: budgets with defaults applied. */
data class ResolvedBranchSummarySettings(val reserveTokens: Long, val skipPrompt: Boolean)

/** pi's `DEFAULT_COMPACTION_TOKEN_SETTINGS` (per-field built-in defaults). */
private val DEFAULT_COMPACTION_TOKEN_SETTINGS = mapOf(
    "reserveTokens" to 16384L,
    "keepRecentTokens" to 20000L
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
        settings.branchSummary?.let { put("branchSummary", encodeBranchSummary(it)) }
        settings.retry?.let { put("retry", encodeRetry(it)) }
    }
)

/** The stored shape of one top-level field; null when the field is absent. */
private fun encodeField(settings: Settings, field: String): JsonElement? = when (field) {
    "defaultProvider" -> settings.defaultProvider?.let(::JsonPrimitive)

    "defaultModel" -> settings.defaultModel?.let(::JsonPrimitive)

    "defaultThinkingLevel" -> settings.defaultThinkingLevel?.let { JsonPrimitive(it.wire) }

    "modelThinkingLevels" -> settings.modelThinkingLevels?.let { levels ->
        JsonObject(levels.mapValues { (_, level) -> JsonPrimitive(level.wire) })
    }

    "enabledModels" -> settings.enabledModels?.let { JsonArray(it.map(::JsonPrimitive)) }

    "compaction" -> settings.compaction?.let(::encodeCompaction)

    "branchSummary" -> settings.branchSummary?.let(::encodeBranchSummary)

    "retry" -> settings.retry?.let(::encodeRetry)

    else -> throw IllegalStateException("Unknown settings field: $field")
}

private fun encodeCompaction(compaction: CompactionSettings): JsonObject = JsonObject(
    buildMap {
        compaction.enabled?.let { put("enabled", JsonPrimitive(it)) }
        compaction.reserveTokens?.let { put("reserveTokens", JsonPrimitive(it)) }
        compaction.keepRecentTokens?.let { put("keepRecentTokens", JsonPrimitive(it)) }
        compaction.modelOverrides?.let { overrides ->
            put(
                "modelOverrides",
                JsonObject(overrides.mapValues { (_, override) -> encodeModelOverride(override) })
            )
        }
    }
)

private fun encodeModelOverride(override: CompactionModelOverride): JsonObject = JsonObject(
    buildMap {
        override.reserveTokens?.let { put("reserveTokens", JsonPrimitive(it)) }
        override.keepRecentTokens?.let { put("keepRecentTokens", JsonPrimitive(it)) }
    }
)

private fun encodeBranchSummary(branchSummary: BranchSummarySettings): JsonObject = JsonObject(
    buildMap {
        branchSummary.reserveTokens?.let { put("reserveTokens", JsonPrimitive(it)) }
        branchSummary.skipPrompt?.let { put("skipPrompt", JsonPrimitive(it)) }
    }
)

private fun encodeRetry(retry: RetrySettings): JsonObject = JsonObject(
    mapOf(
        "enabled" to JsonPrimitive(retry.enabled),
        "maxRetries" to JsonPrimitive(retry.maxRetries),
        "baseDelayMs" to JsonPrimitive(retry.baseDelayMs),
        "maxAgentDelayMs" to JsonPrimitive(retry.maxAgentDelayMs)
    )
)

/**
 * Tolerant decode: unknown fields and non-object roots yield defaults, and
 * thinking-level values that don't map to a known level read as absent (the
 * stored strings survive saves). Compaction token budgets are validated
 * lazily when read, like pi's `getCompactionTokenSetting`; decode only
 * requires each present budget to be an integral number a Long can hold.
 * Values pi rejects at read time (negative, beyond the safe-integer range)
 * load fine here, and reading them throws with pi's message. Values no Long
 * can represent (fractions, non-numbers, non-object model-override entries)
 * fail the whole load instead of being surfaced lazily — the typed shape
 * cannot carry them. Branch-summary fields are unvalidated raw optionals, as
 * in pi; retry is decoded resolved (absent stored keys default at read) while
 * per-key persistence keeps the stored optionals untouched.
 */
private fun decodeSettings(content: String): Settings {
    val obj = Json.parseToJsonElement(stripBom(content)) as? JsonObject ?: return Settings()
    return Settings(
        defaultProvider = obj.str("defaultProvider"),
        defaultModel = obj.str("defaultModel"),
        defaultThinkingLevel = obj.str("defaultThinkingLevel")?.let(::modelThinkingLevelFromWire),
        modelThinkingLevels = (obj["modelThinkingLevels"] as? JsonObject)?.let(
            ::decodeModelThinkingLevels
        ),
        enabledModels = (obj["enabledModels"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content
        },
        compaction = (obj["compaction"] as? JsonObject)?.let(::decodeCompaction),
        branchSummary = (obj["branchSummary"] as? JsonObject)?.let(::decodeBranchSummary),
        retry = (obj["retry"] as? JsonObject)?.let(::decodeRetry)
    )
}

private fun decodeModelThinkingLevels(obj: JsonObject): Map<String, ModelThinkingLevel> = buildMap {
    for ((key, value) in obj) {
        (value as? JsonPrimitive)?.content?.let(::modelThinkingLevelFromWire)?.let { put(key, it) }
    }
}

private fun decodeCompaction(obj: JsonObject) = CompactionSettings(
    enabled = obj.boolean("enabled"),
    reserveTokens = obj.tokenField(setting = "compaction.reserveTokens", key = "reserveTokens"),
    keepRecentTokens = obj.tokenField(
        setting = "compaction.keepRecentTokens",
        key = "keepRecentTokens"
    ),
    modelOverrides = (obj["modelOverrides"] as? JsonObject)?.let(::decodeModelOverrides)
)

private fun decodeModelOverrides(obj: JsonObject): Map<String, CompactionModelOverride> =
    obj.mapValues { (key, element) ->
        val entry = element as? JsonObject
            ?: throw IllegalStateException(
                "Invalid compaction.modelOverrides[\"$key\"] setting: $element. Expected an object."
            )
        CompactionModelOverride(
            reserveTokens = entry.tokenField(
                setting = "compaction.modelOverrides[\"$key\"].reserveTokens",
                key = "reserveTokens"
            ),
            keepRecentTokens = entry.tokenField(
                setting = "compaction.modelOverrides[\"$key\"].keepRecentTokens",
                key = "keepRecentTokens"
            )
        )
    }

private fun decodeBranchSummary(obj: JsonObject) = BranchSummarySettings(
    reserveTokens = obj.long("reserveTokens"),
    skipPrompt = obj.boolean("skipPrompt")
)

private fun decodeRetry(obj: JsonObject) = RetrySettings(
    enabled = obj.boolean("enabled") ?: true,
    maxRetries = obj.int("maxRetries") ?: 3,
    baseDelayMs = obj.long("baseDelayMs") ?: 2000,
    maxAgentDelayMs = obj.long("maxAgentDelayMs") ?: DEFAULT_MAX_AGENT_RETRY_DELAY_MS
)

/** An integral stored number, or null when absent; range semantics validate at read like pi. */
private fun JsonObject.tokenField(setting: String, key: String): Long? {
    if (key !in this) return null
    return strictLong(key) ?: throw IllegalStateException(
        "Invalid $setting setting: ${this[key]}. Expected a non-negative safe integer."
    )
}

/**
 * pi's `SettingsManager`, reduced to a single global scope. Divergences:
 * suspendable setters apply each mutation and its storage write atomically
 * under one mutex (pi mutates in memory synchronously and queues the write;
 * its `reload` drains that queue first, which the mutex provides here), and
 * only fields modified during the session are merged into stored content —
 * for nested objects and `modelThinkingLevels`, only the individually
 * modified entries — so externally stored fields, entries, and unknown keys
 * survive saves. Persisted JSON shapes match pi's: absent optionals are
 * omitted, never materialized.
 */
class SettingsManager private constructor(
    private val storage: SettingsStorage,
    initialSettings: Settings,
    initialError: Throwable?
) {
    private var settings = initialSettings
    private var loadError = initialError
    private val errors = mutableListOf<Throwable>().apply { initialError?.let(::add) }
    private val mutex = Mutex()
    private val modifiedFields = mutableSetOf<String>()
    private val modifiedNestedFields = mutableMapOf<String, MutableSet<String>>()
    private val removedNestedFields = mutableMapOf<String, MutableSet<String>>()

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
        mutex.withLock {
            val (loaded, error) = tryLoad(storage)
            if (error == null) {
                settings = loaded
                loadError = null
            } else {
                loadError = error
                errors.add(error)
            }
            modifiedFields.clear()
            modifiedNestedFields.clear()
            removedNestedFields.clear()
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
        mutateFields("defaultProvider") { it.copy(defaultProvider = provider) }
    }

    suspend fun setDefaultModel(modelId: String) {
        mutateFields("defaultModel") { it.copy(defaultModel = modelId) }
    }

    /** Atomic: provider and model are persisted in a single read-modify-write. */
    suspend fun setDefaultModelAndProvider(provider: String, modelId: String) {
        mutateFields("defaultProvider", "defaultModel") {
            it.copy(defaultProvider = provider, defaultModel = modelId)
        }
    }

    fun getDefaultThinkingLevel(): ModelThinkingLevel? = settings.defaultThinkingLevel

    suspend fun setDefaultThinkingLevel(level: ModelThinkingLevel) {
        mutateFields("defaultThinkingLevel") { it.copy(defaultThinkingLevel = level) }
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
        mutateNestedKey("modelThinkingLevels", "$provider/$modelId") { s ->
            s.copy(
                modelThinkingLevels = (s.modelThinkingLevels ?: emptyMap()) +
                    ("$provider/$modelId" to level)
            )
        }
    }

    suspend fun removeModelThinkingLevel(provider: String, modelId: String) {
        mutex.withLock {
            val current = settings.modelThinkingLevels ?: return@withLock
            settings =
                settings.copy(
                    modelThinkingLevels = (current - "$provider/$modelId").ifEmpty {
                        null
                    }
                )
            markNestedRemoved("modelThinkingLevels", "$provider/$modelId")
            persist()
        }
    }

    fun getEnabledModels(): List<String>? = settings.enabledModels?.toList()

    suspend fun setEnabledModels(patterns: List<String>?) {
        mutateFields("enabledModels") { it.copy(enabledModels = patterns?.toList()) }
    }

    fun getCompactionEnabled(): Boolean = settings.compaction?.enabled ?: true

    suspend fun setCompactionEnabled(enabled: Boolean) {
        mutateNestedKey("compaction", "enabled") { s ->
            s.copy(compaction = (s.compaction ?: CompactionSettings()).copy(enabled = enabled))
        }
    }

    fun getCompactionReserveTokens(model: Model? = null): Long =
        getCompactionTokenSetting("reserveTokens", model)

    fun getCompactionKeepRecentTokens(model: Model? = null): Long =
        getCompactionTokenSetting("keepRecentTokens", model)

    /** pi's `getCompactionTokenSetting`: validate lazily, then override, ordinary, built-in default. */
    private fun getCompactionTokenSetting(field: String, model: Model? = null): Long {
        val compaction = settings.compaction
        val ordinary = when (field) {
            "reserveTokens" -> compaction?.reserveTokens
            else -> compaction?.keepRecentTokens
        }
        if (ordinary != null && (ordinary < 0 || ordinary > MAX_SAFE_INTEGER)) {
            throw IllegalStateException(
                "Invalid compaction.$field setting: $ordinary. Expected a non-negative safe integer."
            )
        }
        val modelKey = model?.let { "${it.provider}/${it.id}" }
        val entry = modelKey?.let { compaction?.modelOverrides?.get(it) }
        val override = entry?.let {
            when (field) {
                "reserveTokens" -> it.reserveTokens
                else -> it.keepRecentTokens
            }
        }
        if (override != null && (override < 0 || override > MAX_SAFE_INTEGER)) {
            throw IllegalStateException(
                "Invalid compaction.modelOverrides[\"$modelKey\"].$field setting: $override. " +
                    "Expected a non-negative safe integer."
            )
        }
        return override ?: ordinary ?: DEFAULT_COMPACTION_TOKEN_SETTINGS.getValue(field)
    }

    fun getCompactionSettings(model: Model? = null): ResolvedCompactionSettings =
        ResolvedCompactionSettings(
            enabled = getCompactionEnabled(),
            reserveTokens = resolvedCompactionBudget(
                "reserveTokens",
                getCompactionReserveTokens(model)
            ),
            keepRecentTokens = resolvedCompactionBudget(
                "keepRecentTokens",
                getCompactionKeepRecentTokens(model)
            )
        )

    /**
     * Divergence: resolved budgets are Int because the compaction arithmetic
     * consuming them is; a pi-valid value beyond Int range throws here rather
     * than truncate ([getCompactionReserveTokens]/[getCompactionKeepRecentTokens]
     * return the full value).
     */
    private fun resolvedCompactionBudget(field: String, value: Long): Int {
        check(value <= Int.MAX_VALUE) {
            "compaction.$field setting $value exceeds the Int range of the resolved compaction budget."
        }
        return value.toInt()
    }

    fun getBranchSummarySettings(): ResolvedBranchSummarySettings = ResolvedBranchSummarySettings(
        reserveTokens = settings.branchSummary?.reserveTokens ?: 16384L,
        skipPrompt = settings.branchSummary?.skipPrompt ?: false
    )

    fun getBranchSummarySkipPrompt(): Boolean = settings.branchSummary?.skipPrompt ?: false

    fun getRetrySettings(): RetrySettings = settings.retry ?: RetrySettings()

    suspend fun setRetryEnabled(enabled: Boolean) {
        mutateNestedKey("retry", "enabled") { s ->
            s.copy(retry = (s.retry ?: RetrySettings()).copy(enabled = enabled))
        }
    }

    private suspend fun mutateFields(vararg fields: String, transform: (Settings) -> Settings) {
        mutex.withLock {
            settings = transform(settings)
            modifiedFields.addAll(fields)
            persist()
        }
    }

    private suspend fun mutateNestedKey(
        field: String,
        nestedKey: String,
        transform: (Settings) -> Settings
    ) {
        mutex.withLock {
            settings = transform(settings)
            markNestedModified(field, nestedKey)
            persist()
        }
    }

    private fun markNestedModified(field: String, nestedKey: String) {
        modifiedFields.add(field)
        modifiedNestedFields.getOrPut(field) { mutableSetOf() }.add(nestedKey)
    }

    private fun markNestedRemoved(field: String, nestedKey: String) {
        modifiedFields.add(field)
        removedNestedFields.getOrPut(field) { mutableSetOf() }.add(nestedKey)
    }

    /**
     * Merge only the fields modified since the last successful persist into
     * the stored content, so externally added or edited fields survive. A
     * whole field whose value is null is removed; a nested field merges only
     * its modified (or removed) entries into the stored object, and an object
     * left empty by removals is dropped. Marks survive a failed write so the
     * next persist retries it (pi retains them when its queued write fails).
     *
     * Like pi's `save()`, nothing is written while the last load failed, so
     * an unreadable settings file is never clobbered.
     */
    private suspend fun persist() {
        if (loadError != null) {
            return
        }
        try {
            storage.withLock { current ->
                // pi's persistScopedSettings treats empty stored content as
                // an empty object rather than failing to parse.
                val base = current?.takeIf { it.isNotEmpty() }?.let {
                    Json.parseToJsonElement(stripBom(it)) as? JsonObject
                } ?: JsonObject(emptyMap())
                val merged = base.toMutableMap()
                for (field in modifiedFields) {
                    val nestedModified = modifiedNestedFields[field]
                    val nestedRemoved = removedNestedFields[field]
                    if (nestedModified == null && nestedRemoved == null) {
                        val value = encodeField(settings, field)
                        if (value == null) {
                            merged.remove(field)
                        } else {
                            merged[field] = value
                        }
                        continue
                    }
                    val nested = (base[field] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                    val encoded = encodeField(settings, field) as? JsonObject
                    for (key in nestedModified.orEmpty()) {
                        val value = encoded?.get(key)
                        if (value == null) {
                            nested.remove(key)
                        } else {
                            nested[key] = value
                        }
                    }
                    for (key in nestedRemoved.orEmpty()) {
                        nested.remove(key)
                    }
                    if (nested.isEmpty()) {
                        merged.remove(field)
                    } else {
                        merged[field] = JsonObject(nested)
                    }
                }
                prettyJson.encodeToString(JsonObject(merged))
            }
            modifiedFields.clear()
            modifiedNestedFields.clear()
            removedNestedFields.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add(e)
        }
    }
}
