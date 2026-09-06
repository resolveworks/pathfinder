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
        assertNull(settings.activeSessionId)
        assertFalse(settings.showThinking)
        assertNull(repository.currentSettings().activeSessionId)
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
    fun appPreferences_surviveRestart() = runTest {
        repository.setActiveSessionId("session-1")
        repository.setShowThinking(true)
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
            assertEquals("session-1", second.settings.first().activeSessionId)
            assertTrue(second.settings.first().showThinking)
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
