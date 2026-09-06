package works.resolve.pathfinder.codingagent.core

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.testing.FauxProvider

/**
 * Ports pi's `model-resolver.test.ts` "persisted default model scoping"
 * block: setModel(persist) appends the default to a non-empty session scope
 * and the stored enabled-models list, and never creates a scope when the
 * session is unscoped.
 */
class AgentSessionModelScopeTest {

    private fun reference(model: Model) = "${model.provider}/${model.id}"

    @Test
    fun `adds a persisted default to an existing scoped model list`() = runTest {
        val faux = FauxProvider()
        val sonnet = faux.model
        val opus = sonnet.copy(id = "faux-opus")
        val settingsManager = SettingsManager.inMemory(
            Settings(enabledModels = listOf(reference(sonnet)))
        )
        val provider = FauxProvider(sonnet, additionalModels = listOf(opus))
        val session = AgentSession(
            agent = Agent(provider.model, streamFn = StreamFn(provider.models::stream)),
            manager = SessionManager.create(
                createTempDirectory("model-scope-test").toFile(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            settingsManager = settingsManager,
            scopedModels = listOf(ScopedModel(sonnet)),
            models = provider.models
        )

        session.setModel(opus, persist = true)

        assertEquals(opus.provider, settingsManager.getDefaultProvider())
        assertEquals(opus.id, settingsManager.getDefaultModel())
        assertEquals(
            listOf(reference(sonnet), reference(opus)),
            session.scopedModels.map { reference(it.model) }
        )
        assertEquals(
            listOf(reference(sonnet), reference(opus)),
            settingsManager.getEnabledModels()
        )
    }

    @Test
    fun `does not create a scoped model list when all models are available`() = runTest {
        val faux = FauxProvider()
        val opus = faux.model.copy(id = "faux-opus")
        val provider = FauxProvider(faux.model, additionalModels = listOf(opus))
        val settingsManager = SettingsManager.inMemory()
        val session = AgentSession(
            agent = Agent(provider.model, streamFn = StreamFn(provider.models::stream)),
            manager = SessionManager.create(
                createTempDirectory("model-scope-test").toFile(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            settingsManager = settingsManager,
            scopedModels = emptyList(),
            models = provider.models
        )

        session.setModel(opus, persist = true)

        assertEquals(emptyList<ScopedModel>(), session.scopedModels)
        assertNull(settingsManager.getEnabledModels())
    }

    @Test
    fun `keeps session-only model changes out of scope`() = runTest {
        val faux = FauxProvider()
        val sonnet = faux.model
        val opus = sonnet.copy(id = "faux-opus")
        val settingsManager = SettingsManager.inMemory(
            Settings(enabledModels = listOf(reference(sonnet)))
        )
        val provider = FauxProvider(sonnet, additionalModels = listOf(opus))
        val session = AgentSession(
            agent = Agent(provider.model, streamFn = StreamFn(provider.models::stream)),
            manager = SessionManager.create(
                createTempDirectory("model-scope-test").toFile(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            settingsManager = settingsManager,
            scopedModels = listOf(ScopedModel(sonnet)),
            models = provider.models
        )

        session.setModel(opus, persist = false)

        assertEquals(listOf(reference(sonnet)), session.scopedModels.map { reference(it.model) })
        assertEquals(listOf(reference(sonnet)), settingsManager.getEnabledModels())
    }
}
