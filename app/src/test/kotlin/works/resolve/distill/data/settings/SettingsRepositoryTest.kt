package works.resolve.distill.data.settings

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        repository = SettingsRepository(
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
        assertNull(settings.activeSessionId)
        assertFalse(settings.showThinking)
    }

    @Test
    fun setters_persistAndRoundTrip() = runTest {
        repository.setProviderId("anthropic")
        repository.setModelId("claude-sonnet-4-5")
        repository.setActiveSessionId("session-1")

        val settings = repository.settings.first()
        assertEquals("anthropic", settings.providerId)
        assertEquals("claude-sonnet-4-5", settings.modelId)
        assertEquals("session-1", settings.activeSessionId)
    }

    @Test
    fun updates_areFocused() = runTest {
        repository.setProviderId("openai")
        repository.setModelId("gpt-x")

        repository.setModelId("gpt-y")

        val settings = repository.settings.first()
        assertEquals("openai", settings.providerId)
        assertEquals("gpt-y", settings.modelId)
    }

    @Test
    fun clearingOptionalValues() = runTest {
        repository.setActiveSessionId("session-1")

        repository.setActiveSessionId(null)

        val settings = repository.settings.first()
        assertNull(settings.activeSessionId)
    }

    @Test
    fun showThinking_defaultsFalse_andRoundTrips() = runTest {
        assertFalse(repository.settings.first().showThinking)
        assertFalse(repository.currentSettings().showThinking)

        repository.setShowThinking(true)

        assertTrue(repository.settings.first().showThinking)
        assertTrue(repository.currentSettings().showThinking)

        repository.setShowThinking(false)
        assertFalse(repository.settings.first().showThinking)
    }

    @Test
    fun survivesRestart() = runTest {
        repository.setProviderId("openai")
        val file = File(tmpFolder.root, "settings.preferences_pb")
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val second = SettingsRepository(
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
