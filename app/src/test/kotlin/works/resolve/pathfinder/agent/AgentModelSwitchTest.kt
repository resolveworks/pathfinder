package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.UserMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model-as-state seam tests for [Agent] (pi agent.ts:76 `_state.model`,
 * createLoopConfig agent.ts:509-515, handleRunFailure agent.ts:515): the
 * selected model is AgentState, each prompt snapshots it at run start, and
 * setModel during a run affects only subsequent runs.
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
        // Transcript untouched by the switch.
        assertEquals(2, agent.state.value.messages.size)
    }

    @Test
    fun `prompt snapshots the model at run start so a mid-run switch changes only later runs`() = runTest {
        val streamedModels = CopyOnWriteArrayList<Model>()
        lateinit var agent: Agent
        agent = Agent(model = modelA) { requested, _, _ ->
            streamedModels.add(requested)
            // Switch mid-run, exactly like a live model switch during a
            // response: the current run must keep streaming from modelA.
            agent.setModel(modelB)
            okStream(requested)
        }

        agent.prompt(listOf(UserMessage.ofText("hi")))

        assertEquals(listOf(modelA), streamedModels)
        // The switch took effect for state and the next run.
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
        // First response still present, from the original model.
        val first = messages[1] as AssistantMessage
        assertEquals(modelA.provider, first.provider)
    }

    @Test
    fun `a facade-level failure after a mid-run switch is labeled with the live model`() = runTest {
        // pi's handleRunFailure reads `this._state.model` (agent.ts:515) at
        // failure time, so a mid-run switch relabels the synthesized error
        // message even though the failed run used its start-of-run snapshot.
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
