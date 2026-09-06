package works.resolve.pathfinder.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import works.resolve.pathfinder.ai.utils.lenientJson

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
                produceFile = { File(tmpFolder.root, "settings.preferences_pb") }
            )
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
    fun enabledModels_defaultNull_andRoundTripsPreservingOrder() = runTest {
        assertNull(repository.settings.first().enabledModels)

        repository.setEnabledModels(
            listOf("anthropic/claude-opus-4-8", "gpt-5.5", "gemini-3.1-pro-preview")
        )

        assertEquals(
            listOf("anthropic/claude-opus-4-8", "gpt-5.5", "gemini-3.1-pro-preview"),
            repository.settings.first().enabledModels
        )
        repository.setEnabledModels(listOf("gpt-5.5", "anthropic/claude-opus-4-8"))
        assertEquals(
            listOf("gpt-5.5", "anthropic/claude-opus-4-8"),
            repository.settings.first().enabledModels
        )
    }

    @Test
    fun enabledModels_emptyList_roundTripsAsEmpty() = runTest {
        repository.setEnabledModels(emptyList())

        assertEquals(emptyList<String>(), repository.settings.first().enabledModels)
    }

    @Test
    fun enabledModels_nullClearsScope_andDoesNotTouchOtherFields() = runTest {
        repository.setProviderId("anthropic")
        repository.setEnabledModels(listOf("anthropic/claude-opus-4-8"))

        repository.setEnabledModels(null)

        val settings = repository.settings.first()
        assertNull(settings.enabledModels)
        assertEquals("anthropic", settings.providerId)
    }

    @Test
    fun enabledModels_malformedStoredData_isRejected() = runTest {
        for (malformed in listOf(
            "{not json",
            "[\"a\",42]",
            "{\"k\":\"v\"}",
            "\"just a string\"",
            ""
        )) {
            try {
                repository.decodeEnabledModels(malformed)
                org.junit.Assert.fail("Expected rejection of malformed enabled_models: $malformed")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun enabledModels_survivesRestart() = runTest {
        repository.setEnabledModels(listOf("b", "a"))
        val file = java.io.File(tmpFolder.root, "settings.preferences_pb")
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val second = SettingsRepository(
                PreferenceDataStoreFactory.create(
                    scope = secondScope,
                    produceFile = { file }
                )
            )
            assertEquals(listOf("b", "a"), second.settings.first().enabledModels)
        } finally {
            secondScope.cancel()
        }
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
                    produceFile = { file }
                )
            )
            assertEquals("openai", second.settings.first().providerId)
        } finally {
            secondScope.cancel()
        }
    }

    private suspend fun storedSettingsJson(): String? {
        var content: String? = null
        repository.withLock { current ->
            content = current
            null
        }
        return content
    }

    @Test
    fun withLock_observesLatestContent_andPersistsNonNullResult() = runTest {
        assertNull(storedSettingsJson())

        repository.withLock { """{"defaultModel":"a"}""" }

        assertEquals("""{"defaultModel":"a"}""", storedSettingsJson())

        repository.withLock { current ->
            val obj = lenientJson.parseToJsonElement(current!!) as
                kotlinx.serialization.json.JsonObject
            JsonObject(obj + ("defaultProvider" to JsonPrimitive("p"))).toString()
        }

        assertEquals(
            """{"defaultModel":"a","defaultProvider":"p"}""",
            storedSettingsJson()
        )
    }

    @Test
    fun withLock_nullResult_performsNoWrite() = runTest {
        repository.withLock { "{\"a\":1}" }

        repository.withLock { null }

        assertEquals("{\"a\":1}", storedSettingsJson())
    }

    private fun countFromJson(content: String): Int {
        val obj = lenientJson.parseToJsonElement(content) as kotlinx.serialization.json.JsonObject
        return (obj["count"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
    }

    @Test
    fun withLock_concurrentCalls_areSerialized_withoutLostUpdates() = runBlocking {
        repository.withLock { """{"count":0}""" }

        withTimeout(30_000) {
            (1..50).map {
                async(Dispatchers.IO) {
                    repository.withLock { current ->
                        """{"count":${countFromJson(current!!) + 1}}"""
                    }
                }
            }.awaitAll()
        }

        assertEquals(50, countFromJson(storedSettingsJson()!!))
    }

    @Test
    fun withLock_cancellationPropagates() = runTest {
        val jobDeferred = CompletableDeferred<Job>()
        val job = launch {
            val self = coroutineContext[Job]!!
            jobDeferred.complete(self)
            repository.withLock { _ ->
                while (self.isActive) {
                    Thread.yield()
                }
                throw CancellationException("cancelled during transform")
            }
        }
        jobDeferred.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull(storedSettingsJson())
    }
}
