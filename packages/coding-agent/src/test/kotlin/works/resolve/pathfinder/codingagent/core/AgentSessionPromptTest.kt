package works.resolve.pathfinder.codingagent.core

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.StreamFn
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
}
