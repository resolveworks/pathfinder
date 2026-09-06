package works.resolve.pathfinder.codingagent.core

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.testing.FauxProvider

class AgentSessionModelExtensionTest {

    private suspend fun session(faux: FauxProvider) = AgentSession(
        agent = Agent(faux.model, streamFn = StreamFn(faux.models::stream)),
        manager = SessionManager.create(
            createTempDirectory("model-extension-test").toFile(),
            ioDispatcher = Dispatchers.Unconfined
        ),
        settingsManager = SettingsManager.inMemory(),
        models = faux.models
    )

    @Test
    fun `setModel saves the model to the session`() = runTest {
        val modelOne = FauxProvider().model.copy(name = "One", reasoning = true)
        val modelTwo = modelOne.copy(id = "faux-2", name = "Two")
        val faux = FauxProvider(modelOne, additionalModels = listOf(modelTwo))
        val session = session(faux)

        session.setModel(modelTwo)

        assertEquals("faux-2", session.model.id)
        assertEquals(
            listOf("${modelTwo.provider}/${modelTwo.id}"),
            session.sessionManager.getEntries()
                .filterIsInstance<ModelChangeEntry>()
                .map { "${it.provider}/${it.modelId}" }
        )
    }

    @Test
    fun `falls back to current session thinking level when no per-model or global default is configured`() =
        runTest {
            val modelOne = FauxProvider().model.copy(name = "One", reasoning = true)
            val modelTwo = modelOne.copy(id = "faux-2", name = "Two")
            val faux = FauxProvider(modelOne, additionalModels = listOf(modelTwo))
            val session = session(faux)

            session.setThinkingLevel(ModelThinkingLevel.HIGH)
            session.setModel(modelTwo)

            assertEquals(ModelThinkingLevel.HIGH, session.thinkingLevel)
        }

    @Test
    fun `throws when setModel is called without configured auth`() = runTest {
        val modelOne = FauxProvider().model.copy(name = "One", reasoning = true)
        val modelTwo = modelOne.copy(id = "faux-2", name = "Two")
        val faux = FauxProvider(
            modelOne,
            additionalModels = listOf(modelTwo),
            configuredAuth = false
        )
        val session = session(faux)

        val error = runCatching { session.setModel(modelTwo) }.exceptionOrNull()

        assertEquals("No API key for ${modelOne.provider}/faux-2", error?.message)
    }
}
