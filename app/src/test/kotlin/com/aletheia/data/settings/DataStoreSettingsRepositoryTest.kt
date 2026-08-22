package com.aletheia.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: DataStoreSettingsRepository

    @Before
    fun setUp() {
        repository = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(tmpFolder.root, "settings.preferences_pb") },
            ),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaults_areEmpty() = runTest {
        val settings = repository.settings.first()
        assertEquals("", settings.providerId)
        assertEquals("", settings.modelId)
        assertNull(settings.baseUrl)
        assertNull(settings.activeSessionId)
    }

    @Test
    fun setters_persistAndRoundTrip() = runTest {
        repository.setProviderId("anthropic")
        repository.setModelId("claude-sonnet-4-5")
        repository.setBaseUrl("https://api.example.com/v1")
        repository.setActiveSessionId("session-1")

        val settings = repository.settings.first()
        assertEquals("anthropic", settings.providerId)
        assertEquals("claude-sonnet-4-5", settings.modelId)
        assertEquals("https://api.example.com/v1", settings.baseUrl)
        assertEquals("session-1", settings.activeSessionId)
    }

    @Test
    fun updates_areFocused() = runTest {
        repository.setProviderId("openai")
        repository.setModelId("gpt-x")
        repository.setBaseUrl("https://x.example")

        repository.setModelId("gpt-y")

        val settings = repository.settings.first()
        assertEquals("openai", settings.providerId)
        assertEquals("gpt-y", settings.modelId)
        assertEquals("https://x.example", settings.baseUrl)
    }

    @Test
    fun baseUrl_blank_isTreatedAsAbsent() = runTest {
        repository.setBaseUrl("  ")
        assertNull(repository.settings.first().baseUrl)
    }

    @Test
    fun clearingOptionalValues() = runTest {
        repository.setBaseUrl("https://x.example")
        repository.setActiveSessionId("session-1")

        repository.setBaseUrl(null)
        repository.setActiveSessionId(null)

        val settings = repository.settings.first()
        assertNull(settings.baseUrl)
        assertNull(settings.activeSessionId)
    }

    @Test
    fun survivesRestart() = runTest {
        repository.setProviderId("openai")
        val file = File(tmpFolder.root, "settings.preferences_pb")
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val second = DataStoreSettingsRepository(
                PreferenceDataStoreFactory.create(
                    scope = secondScope,
                    produceFile = { file },
                ),
            )
            assertEquals("openai", second.settings.first().providerId)
        } finally {
            secondScope.cancel()
        }
    }
}
