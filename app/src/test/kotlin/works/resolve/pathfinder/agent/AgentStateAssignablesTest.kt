package works.resolve.pathfinder.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.UserMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assignable-agent-state seam tests for [Agent.setTools] and
 * [Agent.setSystemPrompt] (pi's copying `tools` setter in
 * createMutableAgentState, agent.ts:68–96, and the `agent.state.tools` /
 * `agent.state.systemPrompt` assignments in coding-agent's
 * setActiveToolsByName, agent-session.ts:971–984): each assignment copies the
 * caller's list, and every run snapshots both from state at start
 * (createContextSnapshot, agent.ts:437–443), so mid-run assignment affects
 * only later runs.
 */
class AgentStateAssignablesTest {

    private val model = Model(
        id = "model-a",
        name = "A",
        api = "openai-completions",
        provider = "provider-a",
        baseUrl = "https://a.example.invalid",
    )

    private fun fakeTool(name: String): AgentTool = object : AgentTool {
        override val definition = Tool(name, "fake $name", JsonPrimitive("object"))
        override val label = name
        override fun validateArguments(arguments: JsonObject) = arguments
        override suspend fun execute(toolCallId: String, arguments: JsonObject, onUpdate: AgentToolUpdateCallback) =
            AgentToolResult(content = listOf(TextContent("done")))
    }

    private fun okStream(): Flow<AssistantMessageEvent> {
        val final = AssistantMessage(
            content = listOf(TextContent("ok")),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = StopReason.STOP,
            timestamp = 42L,
        )
        return flowOf(
            AssistantMessageEvent.Start(final.copy(content = emptyList())),
            AssistantMessageEvent.Done(StopReason.STOP, final),
        )
    }

    @Test
    fun `setTools between runs reaches the next run's provider context`() = runTest {
        val captured = CopyOnWriteArrayList<Context>()
        val agent = Agent(model = model, streamFn = { _, context, _ ->
            captured.add(context)
            okStream()
        })

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertEquals(0, captured[0].tools.size)

        agent.setTools(listOf(fakeTool("a"), fakeTool("b")))
        agent.prompt(listOf(UserMessage.ofText("again")))

        assertEquals(listOf("a", "b"), captured[1].tools.map { it.name })
    }

    @Test
    fun `setTools during a run affects only later runs`() = runTest {
        val captured = CopyOnWriteArrayList<Context>()
        lateinit var agent: Agent
        agent = Agent(model = model, streamFn = { _, context, _ ->
            captured.add(context)
            // Assign mid-run, exactly like setActiveToolsByName (agent-session.ts:971)
            // between turns: the current run must keep its start-of-run snapshot.
            agent.setTools(listOf(fakeTool("late")))
            okStream()
        })

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertEquals(0, captured[0].tools.size)

        agent.prompt(listOf(UserMessage.ofText("again")))
        assertEquals(listOf("late"), captured[1].tools.map { it.name })
    }

    @Test
    fun `setTools copies the caller's list`() = runTest {
        val agent = Agent(model = model, streamFn = { _, _, _ -> okStream() })
        val tools = mutableListOf(fakeTool("a"))
        agent.setTools(tools)
        // Pi's copying setter (`tools = nextTools.slice()`, agent.ts:90-92):
        // later caller mutation never reaches agent state.
        tools.add(fakeTool("b"))
        assertEquals(1, agent.state.value.tools.size)
    }

    @Test
    fun `setSystemPrompt between runs reaches the next run's provider context`() = runTest {
        val captured = CopyOnWriteArrayList<Context>()
        val agent = Agent(
            model = model,
            systemPrompt = "first",
            streamFn = { _, context, _ ->
                captured.add(context)
                okStream()
            },
        )

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertEquals("first", captured[0].systemPrompt)

        agent.setSystemPrompt("second")
        assertEquals("second", agent.systemPrompt)
        agent.prompt(listOf(UserMessage.ofText("again")))
        assertEquals("second", captured[1].systemPrompt)

        agent.setSystemPrompt(null)
        agent.prompt(listOf(UserMessage.ofText("third")))
        assertNull(captured[2].systemPrompt)
    }

    @Test
    fun `setSystemPrompt during a run affects only later runs`() = runTest {
        val captured = CopyOnWriteArrayList<Context>()
        lateinit var agent: Agent
        agent = Agent(
            model = model,
            systemPrompt = "first",
            streamFn = { _, context, _ ->
                captured.add(context)
                agent.setSystemPrompt("mid-run")
                okStream()
            },
        )

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertEquals("first", captured[0].systemPrompt)

        agent.prompt(listOf(UserMessage.ofText("again")))
        assertEquals("mid-run", captured[1].systemPrompt)
    }

    @Test
    fun `prompt snapshots messages and tools together from state`() = runTest {
        // Sanity: the run start snapshot composes — transcript plus tool set
        // both come from AgentState (createContextSnapshot, agent.ts:437-443).
        val captured = CopyOnWriteArrayList<Context>()
        val agent = Agent(model = model, tools = listOf(fakeTool("initial")), streamFn = { _, context, _ ->
            captured.add(context)
            okStream()
        })

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertEquals(listOf("initial"), captured[0].tools.map { it.name })
        assertEquals(1, captured[0].messages.size)
        assertTrue(agent.state.value.messages.size > 1)
    }
}
