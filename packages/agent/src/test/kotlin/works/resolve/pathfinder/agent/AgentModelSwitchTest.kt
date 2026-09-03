package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.UserMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selected model is agent state: each prompt snapshots it at run start,
 * so a mid-run switch affects only subsequent runs.
 */
class AgentModelSwitchTest {

    private val modelA = Model(
        id = "model-a",
        name = "A",
        api = "openai-completions",
        provider = "provider-a",
        baseUrl = "https://a.example.invalid",
    )

    private val modelB = Model(
        id = "model-b",
        name = "B",
        api = "openai-completions",
        provider = "provider-b",
        baseUrl = "https://b.example.invalid",
    )

    private fun assistant(model: Model, text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.STOP,
        timestamp = 42L,
    )

    private fun okStream(model: Model): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(model, "")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant(model, "ok")),
    )

    @Test
    fun `setModel while idle updates state and preserves the transcript`() = runTest {
        val agent = Agent(model = modelA, streamFn = { m, _, _ -> okStream(m) })

        agent.prompt(listOf(UserMessage.ofText("hi")))

        agent.setModel(modelB)
        assertEquals(modelB, agent.model)
        assertEquals(modelB, agent.state.value.model)
        assertEquals(2, agent.state.value.messages.size)
    }

    @Test
    fun `prompt snapshots the model at run start so a mid-run switch changes only later runs`() = runTest {
        val streamedModels = CopyOnWriteArrayList<Model>()
        lateinit var agent: Agent
        agent = Agent(model = modelA) { requested, _, _ ->
            streamedModels.add(requested)
            agent.setModel(modelB)
            okStream(requested)
        }

        agent.prompt(listOf(UserMessage.ofText("hi")))

        assertEquals(listOf(modelA), streamedModels)
        assertEquals(modelB, agent.model)
        agent.prompt(listOf(UserMessage.ofText("again")))
        assertEquals(listOf(modelA, modelB), streamedModels)
    }

    @Test
    fun `transcript accumulates across a switch`() = runTest {
        val agent = Agent(model = modelA) { m, _, _ -> okStream(m) }

        agent.prompt(listOf(UserMessage.ofText("one")))
        agent.setModel(modelB)
        agent.prompt(listOf(UserMessage.ofText("two")))

        val messages = agent.state.value.messages
        assertEquals(4, messages.size)
        val second = messages[3] as AssistantMessage
        assertEquals(modelB.id, second.model)
        assertEquals(modelB.provider, second.provider)
        val first = messages[1] as AssistantMessage
        assertEquals(modelA.provider, first.provider)
    }

    @Test
    fun `a facade-level failure after a mid-run switch is labeled with the live model`() = runTest {
        // Failure labeling reads the live model at failure time (pi's
        // handleRunFailure), so the mid-run switch relabels the synthesized
        // error message even though the failed run used its snapshot model.
        lateinit var agent: Agent
        agent = Agent(model = modelA) { _, _, _ ->
            agent.setModel(modelB)
            throw RuntimeException("boom")
        }

        agent.prompt(listOf(UserMessage.ofText("hi")))

        val failure = agent.state.value.messages.last() as AssistantMessage
        assertEquals(StopReason.ERROR, failure.stopReason)
        assertEquals(modelB.provider, failure.provider)
        assertEquals(modelB.id, failure.model)
        assertTrue(failure.errorMessage != null)
    }
}
