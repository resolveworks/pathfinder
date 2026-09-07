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
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FauxProvider

class AgentSessionPromptTest {

    @Test
    fun `throws when prompting without configured auth`() = runTest {
        val faux = FauxProvider(configuredAuth = false)
        val session = AgentSession(
            agent = Agent(faux.model, streamFn = StreamFn(faux.models::stream)),
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
                agent = Agent(faux.model, streamFn = StreamFn(faux.models::stream)),
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
