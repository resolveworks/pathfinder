package works.resolve.pathfinder.codingagent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.AuthResult
import works.resolve.pathfinder.ai.auth.InMemoryCredentialStore
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.testing.FauxProvider
import works.resolve.pathfinder.ai.testing.testApiKeyAuth
import works.resolve.pathfinder.ai.testing.testProviderAuth

class AgentSessionPromptTest {

    @Test
    fun `throws when prompting without configured auth`() = runTest {
        val faux = FauxProvider(configuredAuth = false)
        val session = AgentSession(
            agent = Agent(faux.model, streamFn = modelsStreamFn(faux.models)),
            manager = SessionManager.create(
                createTempDirectory("prompt-test").toFile(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            settingsManager = SettingsManager.inMemory(),
            models = faux.models
        )

        val error = runCatching { session.prompt("hi") }.exceptionOrNull()
        assertEquals("No API key found for ${faux.model.provider}.", error?.message)
    }

    @Test
    fun `throws when a stored api key is incomplete`() = runTest {
        val faux = FauxProvider(configuredAuth = false)
        val store = InMemoryCredentialStore()
        store.modify(faux.model.provider) { ApiKeyCredential(key = "k") }
        val models = Models(
            listOf(
                Provider(
                    id = faux.model.provider,
                    name = faux.model.provider,
                    baseUrl = faux.model.baseUrl,
                    auth = testProviderAuth(
                        apiKey = testApiKeyAuth { _, credential ->
                            credential?.env?.get("ACCOUNT")?.let {
                                AuthResult(ModelAuth(apiKey = credential.key))
                            }
                        }
                    ),
                    models = listOf(faux.model),
                    apis = emptyMap()
                )
            ),
            credentials = store
        )
        val session = AgentSession(
            agent = Agent(faux.model, streamFn = modelsStreamFn(models)),
            manager = SessionManager.create(
                createTempDirectory("prompt-test").toFile(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            settingsManager = SettingsManager.inMemory(),
            models = models
        )

        val error = runCatching { session.prompt("hi") }.exceptionOrNull()
        assertEquals("No API key found for ${faux.model.provider}.", error?.message)
    }

    @Test
    fun `configured auth presence short-circuits resolution`() = runTest {
        val faux = FauxProvider(configuredAuth = false).apply { setResponses(fauxAssistant()) }
        val session = AgentSession(
            agent = Agent(faux.model, streamFn = modelsStreamFn(faux.models)),
            manager = SessionManager.create(
                createTempDirectory("prompt-test").toFile(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            settingsManager = SettingsManager.inMemory(),
            models = faux.models,
            hasConfiguredAuth = { true }
        )

        session.prompt("hi")

        assertEquals(3, session.state.value.messages.size)
    }

    @Test
    fun `prompt runs the loop off the caller dispatcher`() = runTest {
        val faux = FauxProvider().apply { setResponses(fauxAssistant()) }
        val callerThread = Thread.currentThread()
        val streamThread = CompletableDeferred<Thread>()
        val stream: Flow<AssistantMessageEvent> = flow {
            streamThread.complete(Thread.currentThread())
            emit(AssistantMessageEvent.Done(StopReason.STOP, fauxAssistant()))
        }
        val session = AgentSession(
            agent = Agent(faux.model, streamFn = StreamFn { _, _, _ -> stream }),
            manager = SessionManager.create(
                createTempDirectory("prompt-test").toFile(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            settingsManager = SettingsManager.inMemory(),
            models = faux.models
        )

        session.prompt("hi")

        assertNotEquals(callerThread, streamThread.await())
        assertFalse(session.state.value.isStreaming)
    }

    /**
     * Buffered session events: a collector stuck mid-processing neither
     * blocks the prompt loop nor loses or reorders events relative to a
     * fast collector observing the same run.
     */
    @Test
    fun `slow collectors receive every session event in order without blocking the prompt`() =
        runTest {
            val faux = FauxProvider().apply { setResponses(fauxAssistant()) }
            val session = AgentSession(
                agent = Agent(faux.model, streamFn = modelsStreamFn(faux.models)),
                manager = SessionManager.create(
                    createTempDirectory("prompt-test").toFile(),
                    ioDispatcher = Dispatchers.Unconfined
                ),
                settingsManager = SettingsManager.inMemory(),
                models = faux.models
            )

            val fast = CopyOnWriteArrayList<AgentEvent>()
            val slow = CopyOnWriteArrayList<AgentEvent>()
            val gate = CompletableDeferred<Unit>()
            val slowDone = CompletableDeferred<Unit>()
            val fastCollector = launch { session.events.collect { fast.add(it) } }
            val slowCollector = launch {
                session.events.collect { event ->
                    slow.add(event)
                    if (event is AgentEvent.AgentStart) gate.await()
                    if (event is AgentEvent.AgentEnd) slowDone.complete(Unit)
                }
            }
            yield() // subscribe before the run starts

            session.prompt("hi")
            assertFalse(session.state.value.isStreaming)
            assertEquals(1, slow.size)

            gate.complete(Unit)
            slowDone.await()
            slowCollector.cancelAndJoin()
            fastCollector.cancelAndJoin()
            assertEquals(fast.map { it::class.simpleName }, slow.map { it::class.simpleName })
            assertFalse(fast.isEmpty())
        }

    private fun fauxAssistant() = AssistantMessage(
        content = listOf(TextContent("hello")),
        api = FauxProvider().model.api,
        provider = FauxProvider().model.provider,
        model = FauxProvider().model.id,
        stopReason = StopReason.STOP,
        timestamp = 42L
    )
}
