package works.resolve.pathfinder.codingagent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings

/**
 * Characterization of upstream `test/settings-manager.test.ts`, restricted to
 * the ported single-scope surface.
 */
class SettingsManagerTest {
    private fun parse(content: String): JsonObject = Json.parseToJsonElement(content).jsonObject

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

        val saved = parse(readStorage(storage)!!)
        assertEquals("high", saved["defaultThinkingLevel"]!!.jsonPrimitive.content)
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
    fun invalidValuesOnLoadAreIgnoredWithoutLosingOtherFields() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """
            {
              "defaultModel": "claude-sonnet",
              "defaultThinkingLevel": "bogus",
              "modelThinkingLevels": {"openai/gpt-5.2": "high", "openai/o3": "nope"}
            }
            """.trimIndent()
        )

        val manager = SettingsManager.fromStorage(storage)

        assertEquals("claude-sonnet", manager.getDefaultModel())
        assertNull(manager.getDefaultThinkingLevel())
        assertEquals(
            mapOf("openai/gpt-5.2" to ModelThinkingLevel.HIGH),
            manager.getAllModelThinkingLevels()
        )
        assertTrue(manager.drainErrors().isEmpty())
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
            CompactionSettings(enabled = true, reserveTokens = 16384, keepRecentTokens = 20000),
            manager.getCompactionSettings()
        )
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
              "compaction": {"enabled": false},
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
        // Partial compaction object fills remaining fields from defaults.
        assertEquals(
            CompactionSettings(enabled = false, reserveTokens = 16384, keepRecentTokens = 20000),
            manager.getCompactionSettings()
        )
        assertEquals(
            RetrySettings(enabled = false, maxRetries = 1, baseDelayMs = 500),
            manager.getRetrySettings()
        )
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
    fun setCompactionAndRetryPersistWholeObjects() = runTest {
        val manager = SettingsManager.inMemory()

        manager.setCompactionSettings(
            CompactionSettings(enabled = false, reserveTokens = 1000, keepRecentTokens = 2000)
        )
        manager.setRetrySettings(RetrySettings(enabled = false, maxRetries = 0, baseDelayMs = 100))

        assertEquals(
            CompactionSettings(enabled = false, reserveTokens = 1000, keepRecentTokens = 2000),
            manager.getCompactionSettings()
        )
        assertEquals(
            RetrySettings(enabled = false, maxRetries = 0, baseDelayMs = 100),
            manager.getRetrySettings()
        )
    }

    @Test
    fun savingCompactionOrRetryPreservesUnknownNestedKeys() = runTest {
        val storage = InMemorySettingsStorage()
        writeStorage(
            storage,
            """
            {
              "retry": {"enabled": true, "provider": {"timeoutMs": 30000, "maxRetryDelayMs": 1000}},
              "compaction": {"enabled": true, "customFutureKey": 7}
            }
            """.trimIndent()
        )
        val manager = SettingsManager.fromStorage(storage)

        manager.setRetrySettings(RetrySettings(enabled = false, maxRetries = 5, baseDelayMs = 3000))
        manager.setCompactionSettings(
            CompactionSettings(enabled = false, reserveTokens = 512, keepRecentTokens = 1024)
        )

        val saved = parse(readStorage(storage)!!)
        val retry = saved["retry"]!!.jsonObject
        assertEquals(false, retry["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(5, retry["maxRetries"]!!.jsonPrimitive.content.toInt())
        assertEquals(3000, retry["baseDelayMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            30000,
            retry["provider"]!!.jsonObject["timeoutMs"]!!.jsonPrimitive.content.toInt()
        )
        assertEquals(
            1000,
            retry["provider"]!!.jsonObject["maxRetryDelayMs"]!!.jsonPrimitive.content.toInt()
        )
        assertEquals(
            7,
            saved["compaction"]!!.jsonObject["customFutureKey"]!!.jsonPrimitive.content.toInt()
        )
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
    fun getSettingsReturnsDefensiveCopies() = runTest {
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
