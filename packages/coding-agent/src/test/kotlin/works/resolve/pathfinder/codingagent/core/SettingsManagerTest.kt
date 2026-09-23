package works.resolve.pathfinder.codingagent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings as ResolvedCompactionSettings

/**
 * Characterization of upstream `test/settings-manager.test.ts`, restricted to
 * the ported single-scope surface.
 */
class SettingsManagerTest {
    private fun parse(content: String): JsonObject = Json.parseToJsonElement(content).jsonObject

    private fun parseField(content: String?, field: String): JsonObject =
        parse(content!!)[field]!!.jsonObject

    private fun storedString(content: String?, field: String): String =
        parse(content!!)[field]!!.jsonPrimitive.content

    private suspend fun readStorage(storage: SettingsStorage): String? {
        var content: String? = null
        storage.withLock { current ->
            content = current
            null
        }
        return content
    }

    private suspend fun writeStorage(storage: SettingsStorage, content: String) {
        storage.withLock { content }
    }

    private val model = Model(
        id = "claude-sonnet",
        name = "Claude Sonnet",
        api = "anthropic",
        provider = "anthropic",
        baseUrl = "http://localhost:0"
    )

    @Test
    fun emptyStoredContentLoadsAsDefaultsWithoutError() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, "")
        val manager = SettingsManager.fromStorage(storage)

        assertEquals(Settings(), manager.getSettings())
        assertTrue(manager.drainErrors().isEmpty())

        manager.setDefaultThinkingLevel(ModelThinkingLevel.HIGH)
        assertTrue(manager.drainErrors().isEmpty())
        assertEquals("high", storedString(readStorage(storage), "defaultThinkingLevel"))
    }

    @Test
    fun preservesExternallyAddedSettingsWhenChangingThinkingLevel() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, """{"theme":"dark","defaultModel":"claude-sonnet"}""")
        val manager = SettingsManager.fromStorage(storage)

        // Simulate the user editing settings.json externally to add enabledModels.
        writeStorage(
            storage,
            """{"theme":"dark","defaultModel":"claude-sonnet","enabledModels":["claude-opus-4-5","gpt-5.2-codex"]}"""
        )

        manager.setDefaultThinkingLevel(ModelThinkingLevel.HIGH)

        val saved = parse(readStorage(storage)!!)
        assertEquals(
            listOf("claude-opus-4-5", "gpt-5.2-codex"),
            saved["enabledModels"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals("high", saved["defaultThinkingLevel"]!!.jsonPrimitive.content)
        assertEquals("dark", saved["theme"]!!.jsonPrimitive.content)
        assertEquals("claude-sonnet", saved["defaultModel"]!!.jsonPrimitive.content)
    }

    @Test
    fun inMemoryChangesOverrideFileChangesForSameKey() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, """{"theme":"dark"}""")
        val manager = SettingsManager.fromStorage(storage)

        writeStorage(storage, """{"theme":"dark","defaultThinkingLevel":"low"}""")

        manager.setDefaultThinkingLevel(ModelThinkingLevel.HIGH)

        assertEquals("high", storedString(readStorage(storage), "defaultThinkingLevel"))
    }

    @Test
    fun reloadReplacesSettingsFromStorage() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, """{"defaultModel":"before","enabledModels":["a"]}""")
        val manager = SettingsManager.fromStorage(storage)

        writeStorage(storage, """{"defaultModel":"after","defaultProvider":"anthropic"}""")
        manager.reload()

        assertEquals("after", manager.getDefaultModel())
        assertEquals("anthropic", manager.getDefaultProvider())
        assertNull(manager.getEnabledModels())
        assertTrue(manager.drainErrors().isEmpty())
    }

    @Test
    fun reloadKeepsPreviousSettingsAndReportsErrorWhenStorageContentIsInvalid() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, """{"defaultModel":"before"}""")
        val manager = SettingsManager.fromStorage(storage)

        writeStorage(storage, "{ invalid json")
        manager.reload()

        assertEquals("before", manager.getDefaultModel())
        assertEquals(1, manager.drainErrors().size)
        assertTrue(manager.drainErrors().isEmpty())
    }

    @Test
    fun collectAndClearLoadErrorsViaDrainErrors() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, "{ invalid json")

        val manager = SettingsManager.fromStorage(storage)

        assertEquals(1, manager.drainErrors().size)
        assertTrue(manager.drainErrors().isEmpty())
    }

    @Test
    fun loadErrorBlocksSavesUntilReloadSucceeds() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, "{ invalid json")
        val manager = SettingsManager.fromStorage(storage)

        manager.setDefaultThinkingLevel(ModelThinkingLevel.HIGH)
        assertEquals("{ invalid json", readStorage(storage))

        writeStorage(storage, """{"defaultModel":"claude-sonnet"}""")
        manager.reload()
        manager.setDefaultThinkingLevel(ModelThinkingLevel.HIGH)

        val saved = parse(readStorage(storage)!!)
        assertEquals("claude-sonnet", saved["defaultModel"]!!.jsonPrimitive.content)
        assertEquals("high", saved["defaultThinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidCompactionTokensFailAtReadNotLoad() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """{"defaultModel":"claude-sonnet","compaction":{"reserveTokens":-5}}"""
        )
        val manager = SettingsManager.fromStorage(storage)

        // The rest of the file loads, stays readable, and keeps saving.
        assertEquals("claude-sonnet", manager.getDefaultModel())
        assertTrue(manager.drainErrors().isEmpty())
        manager.setDefaultThinkingLevel(ModelThinkingLevel.HIGH)
        assertEquals("high", storedString(readStorage(storage), "defaultThinkingLevel"))

        val error = assertFailsWith<IllegalStateException> {
            manager.getCompactionReserveTokens()
        }
        assertEquals(
            "Invalid compaction.reserveTokens setting: -5. Expected a non-negative safe integer.",
            error.message
        )
        assertFailsWith<IllegalStateException> { manager.getCompactionSettings() }
    }

    @Test
    fun compactionTokensBeyondTheSafeIntegerRangeThrowAtRead() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """{"compaction":{"reserveTokens":9007199254740993}}"""
        )
        val manager = SettingsManager.fromStorage(storage)

        assertTrue(manager.drainErrors().isEmpty())
        val error = assertFailsWith<IllegalStateException> {
            manager.getCompactionReserveTokens()
        }
        assertEquals(
            "Invalid compaction.reserveTokens setting: 9007199254740993. " +
                "Expected a non-negative safe integer.",
            error.message
        )
    }

    @Test
    fun modelOverrideTokensValidateLazilyWithPisMessage() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """
            {
              "compaction": {
                "keepRecentTokens": 20000,
                "modelOverrides": {"anthropic/claude-sonnet": {"keepRecentTokens": 9007199254740993}}
              }
            }
            """.trimIndent()
        )
        val manager = SettingsManager.fromStorage(storage)

        assertTrue(manager.drainErrors().isEmpty())
        assertEquals(20000L, manager.getCompactionKeepRecentTokens())

        val error = assertFailsWith<IllegalStateException> {
            manager.getCompactionKeepRecentTokens(model)
        }
        assertEquals(
            "Invalid compaction.modelOverrides[\"anthropic/claude-sonnet\"].keepRecentTokens " +
                "setting: 9007199254740993. Expected a non-negative safe integer.",
            error.message
        )
    }

    @Test
    fun invalidOrdinaryCompactionTokenThrowsEvenWhenOverrideIsPresent() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """
            {
              "compaction": {
                "reserveTokens": -5,
                "modelOverrides": {"anthropic/claude-sonnet": {"reserveTokens": 5000}}
              }
            }
            """.trimIndent()
        )
        val manager = SettingsManager.fromStorage(storage)

        val error = assertFailsWith<IllegalStateException> {
            manager.getCompactionReserveTokens(model)
        }
        assertEquals(
            "Invalid compaction.reserveTokens setting: -5. Expected a non-negative safe integer.",
            error.message
        )
    }

    @Test
    fun longWideCompactionTokensAreAccepted() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """{"compaction":{"reserveTokens":3000000000,"keepRecentTokens":20000}}"""
        )
        val manager = SettingsManager.fromStorage(storage)

        assertEquals(3000000000L, manager.getCompactionReserveTokens())
        assertEquals(20000L, manager.getCompactionKeepRecentTokens())

        // Documented divergence: the resolved budgets are Int (the compaction
        // arithmetic is), so a value beyond Int range throws when resolved.
        assertFailsWith<IllegalStateException> { manager.getCompactionSettings() }
    }

    @Test
    fun unrepresentableCompactionValuesFailTheLoad() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, """{"compaction":{"reserveTokens":1.5}}""")
        val unparseable = SettingsManager.fromStorage(storage)
        assertEquals(1, unparseable.drainErrors().size)

        val overrides = InMemorySettingsStorage()
        writeStorage(
            overrides,
            """{"compaction":{"modelOverrides":{"anthropic/claude-sonnet":7}}}"""
        )
        val nonObject = SettingsManager.fromStorage(overrides)
        assertEquals(1, nonObject.drainErrors().size)
    }

    @Test
    fun invalidThinkingLevelValuesRoundTripThroughSaves() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """
            {
              "defaultThinkingLevel": "bogus",
              "modelThinkingLevels": {"openai/gpt-5.2": "high", "openai/o3": "nope"}
            }
            """.trimIndent()
        )
        val manager = SettingsManager.fromStorage(storage)
        assertTrue(manager.drainErrors().isEmpty())

        // Typed view decodes: unknown values read as absent (pi returns them raw).
        assertNull(manager.getDefaultThinkingLevel())
        assertEquals(
            mapOf("openai/gpt-5.2" to ModelThinkingLevel.HIGH),
            manager.getAllModelThinkingLevels()
        )

        // Saving an unrelated field leaves the stored values untouched.
        manager.setEnabledModels(listOf("claude-*"))
        assertEquals("bogus", storedString(readStorage(storage), "defaultThinkingLevel"))
        val levels = parseField(readStorage(storage), "modelThinkingLevels")
        assertEquals("high", levels["openai/gpt-5.2"]!!.jsonPrimitive.content)
        assertEquals("nope", levels["openai/o3"]!!.jsonPrimitive.content)

        // Saving one entry preserves the invalid sibling entry.
        manager.setModelThinkingLevel("openai", "gpt-5.2", ModelThinkingLevel.LOW)
        val updated = parseField(readStorage(storage), "modelThinkingLevels")
        assertEquals("low", updated["openai/gpt-5.2"]!!.jsonPrimitive.content)
        assertEquals("nope", updated["openai/o3"]!!.jsonPrimitive.content)

        // Removing an entry drops only that entry; the invalid sibling survives.
        manager.removeModelThinkingLevel("openai", "gpt-5.2")
        val pruned = parseField(readStorage(storage), "modelThinkingLevels")
        assertNull(pruned["openai/gpt-5.2"])
        assertEquals("nope", pruned["openai/o3"]!!.jsonPrimitive.content)
    }

    @Test
    fun defaultsWhenNothingStored() = runTest {
        val manager = SettingsManager.fromStorage(InMemorySettingsStorage())

        assertNull(manager.getDefaultProvider())
        assertNull(manager.getDefaultModel())
        assertNull(manager.getDefaultThinkingLevel())
        assertTrue(manager.getAllModelThinkingLevels().isEmpty())
        assertNull(manager.getEnabledModels())
        assertEquals(
            ResolvedCompactionSettings(
                enabled = true,
                reserveTokens = 16384,
                keepRecentTokens = 20000
            ),
            manager.getCompactionSettings()
        )
        assertEquals(
            ResolvedBranchSummarySettings(reserveTokens = 16384L, skipPrompt = false),
            manager.getBranchSummarySettings()
        )
        assertFalse(manager.getBranchSummarySkipPrompt())
        assertEquals(
            RetrySettings(enabled = true, maxRetries = 3, baseDelayMs = 2000),
            manager.getRetrySettings()
        )
    }

    @Test
    fun loadsAllFieldsFromStorage() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """
            {
              "defaultProvider": "anthropic",
              "defaultModel": "claude-sonnet",
              "defaultThinkingLevel": "medium",
              "modelThinkingLevels": {"openai/gpt-5.2": "high"},
              "enabledModels": ["claude-*"],
              "compaction": {
                "enabled": false,
                "reserveTokens": 1000,
                "keepRecentTokens": 2000,
                "modelOverrides": {"anthropic/claude-sonnet": {"reserveTokens": 7}}
              },
              "branchSummary": {"reserveTokens": 2048, "skipPrompt": true},
              "retry": {"enabled": false, "maxRetries": 1, "baseDelayMs": 500}
            }
            """.trimIndent()
        )

        val manager = SettingsManager.fromStorage(storage)

        assertEquals("anthropic", manager.getDefaultProvider())
        assertEquals("claude-sonnet", manager.getDefaultModel())
        assertEquals(ModelThinkingLevel.MEDIUM, manager.getDefaultThinkingLevel())
        assertEquals(
            mapOf("openai/gpt-5.2" to ModelThinkingLevel.HIGH),
            manager.getAllModelThinkingLevels()
        )
        assertEquals(listOf("claude-*"), manager.getEnabledModels())
        assertEquals(
            ResolvedCompactionSettings(
                enabled = false,
                reserveTokens = 1000,
                keepRecentTokens = 2000
            ),
            manager.getCompactionSettings()
        )
        assertEquals(7L, manager.getCompactionReserveTokens(model))
        assertEquals(
            ResolvedBranchSummarySettings(reserveTokens = 2048L, skipPrompt = true),
            manager.getBranchSummarySettings()
        )
        assertTrue(manager.getBranchSummarySkipPrompt())
        assertEquals(
            RetrySettings(enabled = false, maxRetries = 1, baseDelayMs = 500),
            manager.getRetrySettings()
        )
    }

    @Test
    fun retryDefaultsApplyOnlyAtRead() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, """{"retry":{"enabled":false}}""")
        val manager = SettingsManager.fromStorage(storage)

        assertEquals(
            RetrySettings(enabled = false, maxRetries = 3, baseDelayMs = 2000),
            manager.getRetrySettings()
        )

        manager.setRetryEnabled(true)

        // Only the modified nested key is written; absent keys stay absent.
        val retry = parseField(readStorage(storage), "retry")
        assertEquals(setOf("enabled"), retry.keys)
        assertEquals(true, retry["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun unguardedReadsAcceptQuotedNumeralsWhileTokenBudgetsRejectThem() = runTest {
        // Fields pi reads through TS typing with no runtime guard coerce
        // like JS would (kotlinx lenient reads accept quoted numerals and
        // booleans); the compaction token budgets pi guards with
        // `typeof`/`Number.isSafeInteger` reject a quoted numeral.
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """{"retry":{"enabled":"true","maxRetries":"1","baseDelayMs":"500"},""" +
                """"branchSummary":{"reserveTokens":"5"}}"""
        )
        val manager = SettingsManager.fromStorage(storage)
        assertTrue(manager.drainErrors().isEmpty())
        assertEquals(
            RetrySettings(enabled = true, maxRetries = 1, baseDelayMs = 500),
            manager.getRetrySettings()
        )
        assertEquals(5L, manager.getBranchSummarySettings().reserveTokens)

        writeStorage(storage, """{"compaction":{"reserveTokens":"100"}}"""")
        manager.reload()
        assertEquals(1, manager.drainErrors().size)
    }

    @Test
    fun setAndGetModelThinkingLevels() = runTest {
        val manager = SettingsManager.inMemory()

        manager.setModelThinkingLevel("openai", "gpt-5.2", ModelThinkingLevel.HIGH)
        manager.setModelThinkingLevel("anthropic", "claude-sonnet", ModelThinkingLevel.LOW)

        assertEquals(ModelThinkingLevel.HIGH, manager.getModelThinkingLevel("openai", "gpt-5.2"))
        assertEquals(
            ModelThinkingLevel.LOW,
            manager.getModelThinkingLevel("anthropic", "claude-sonnet")
        )
        assertNull(manager.getModelThinkingLevel("unknown", "model"))
        assertEquals(
            mapOf(
                "openai/gpt-5.2" to ModelThinkingLevel.HIGH,
                "anthropic/claude-sonnet" to ModelThinkingLevel.LOW
            ),
            manager.getAllModelThinkingLevels()
        )
    }

    @Test
    fun removeModelThinkingLevelPrunesEmptyMapFromStorage() = runTest {
        val storage = InMemorySettingsStorage()
        val manager = SettingsManager.fromStorage(storage)
        manager.setModelThinkingLevel("openai", "gpt-5.2", ModelThinkingLevel.HIGH)

        manager.removeModelThinkingLevel("openai", "gpt-5.2")

        assertTrue(manager.getAllModelThinkingLevels().isEmpty())
        assertNull(parse(readStorage(storage)!!)["modelThinkingLevels"])
    }

    @Test
    fun removeModelThinkingLevelWithoutMapIsANoOp() = runTest {
        val manager = SettingsManager.inMemory()
        manager.removeModelThinkingLevel("openai", "gpt-5.2")
        assertTrue(manager.getAllModelThinkingLevels().isEmpty())
    }

    @Test
    fun setEnabledModelsAndClearIt() = runTest {
        val manager = SettingsManager.inMemory()

        manager.setEnabledModels(listOf("claude-*", "gpt-*"))
        assertEquals(listOf("claude-*", "gpt-*"), manager.getEnabledModels())

        manager.setEnabledModels(null)
        assertNull(manager.getEnabledModels())
    }

    @Test
    fun setDefaultModelAndProviderPersistsBothAtomically() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(storage, """{"theme":"dark"}""")
        val manager = SettingsManager.fromStorage(storage)

        manager.setDefaultModelAndProvider("anthropic", "claude-sonnet")

        assertEquals("anthropic", manager.getDefaultProvider())
        assertEquals("claude-sonnet", manager.getDefaultModel())
        val saved = parse(readStorage(storage)!!)
        assertEquals("anthropic", saved["defaultProvider"]!!.jsonPrimitive.content)
        assertEquals("claude-sonnet", saved["defaultModel"]!!.jsonPrimitive.content)
        assertEquals("dark", saved["theme"]!!.jsonPrimitive.content)
    }

    @Test
    fun setCompactionEnabledWritesOnlyThatNestedKey() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """
            {
              "compaction": {"enabled": true, "reserveTokens": 100, "customFutureKey": 7}
            }
            """.trimIndent()
        )
        val manager = SettingsManager.fromStorage(storage)

        manager.setCompactionEnabled(false)

        val compaction = parseField(readStorage(storage), "compaction")
        assertEquals(false, compaction["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(100, compaction["reserveTokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, compaction["customFutureKey"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            ResolvedCompactionSettings(
                enabled = false,
                reserveTokens = 100,
                keepRecentTokens = 20000
            ),
            manager.getCompactionSettings()
        )
    }

    @Test
    fun setRetryEnabledWritesOnlyThatNestedKey() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """
            {
              "retry": {
                "enabled": true,
                "maxRetries": 9,
                "baseDelayMs": 500,
                "provider": {"timeoutMs": 30000, "maxRetryDelayMs": 1000}
              }
            }
            """.trimIndent()
        )
        val manager = SettingsManager.fromStorage(storage)

        manager.setRetryEnabled(false)

        val retry = parseField(readStorage(storage), "retry")
        assertEquals(false, retry["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(9, retry["maxRetries"]!!.jsonPrimitive.content.toInt())
        assertEquals(500, retry["baseDelayMs"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            30000,
            retry["provider"]!!.jsonObject["timeoutMs"]!!.jsonPrimitive.content.toInt()
        )
        assertEquals(
            1000,
            retry["provider"]!!.jsonObject["maxRetryDelayMs"]!!.jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun reloadCannotRevertACommittedSetter() = runTest {
        val inner = InMemorySettingsStorage("""{"defaultModel":"before"}""")
        val storage = object : SettingsStorage {
            override suspend fun withLock(transform: (current: String?) -> String?) {
                delay(10)
                inner.withLock(transform)
            }
        }
        val manager = SettingsManager.fromStorage(storage)

        val setter = launch { manager.setDefaultModel("after") }
        val reloader = launch { manager.reload() }
        setter.join()
        reloader.join()

        assertEquals("after", manager.getDefaultModel())
        assertEquals("after", storedString(readStorage(inner), "defaultModel"))
    }

    @Test
    fun inMemorySeedsSettingsThroughItsStorage() = runTest {
        val manager = SettingsManager.inMemory(
            Settings(
                defaultProvider = "openai",
                defaultModel = "gpt-5.2",
                defaultThinkingLevel = ModelThinkingLevel.OFF,
                enabledModels = listOf("gpt-*")
            )
        )

        assertEquals("openai", manager.getDefaultProvider())
        assertEquals("gpt-5.2", manager.getDefaultModel())
        assertEquals(ModelThinkingLevel.OFF, manager.getDefaultThinkingLevel())
        assertEquals(listOf("gpt-*"), manager.getEnabledModels())
    }

    @Test
    fun getSettingsSnapshotSurvivesLaterMutations() = runTest {
        val manager = SettingsManager.inMemory(
            Settings(
                enabledModels = listOf("a"),
                modelThinkingLevels = mapOf("p/m" to ModelThinkingLevel.LOW)
            )
        )

        val snapshot = manager.getSettings()
        manager.setEnabledModels(listOf("b"))
        manager.setModelThinkingLevel("p", "m2", ModelThinkingLevel.HIGH)

        assertEquals(listOf("a"), snapshot.enabledModels)
        assertEquals(mapOf("p/m" to ModelThinkingLevel.LOW), snapshot.modelThinkingLevels)
    }
}
