package works.resolve.pathfinder.agent

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.getCurrentTools

class AgentStateAssignablesTest {

    private val model = Model(
        id = "model-a",
        name = "A",
        api = "openai-completions",
        provider = "provider-a",
        baseUrl = "https://a.example.invalid"
    )

    private fun fakeTool(name: String): AgentTool = object : AgentTool {
        override val definition = Tool(name, "fake $name", JsonPrimitive("object"))
        override val label = name
        override fun validateArguments(arguments: JsonObject) = arguments
        override suspend fun execute(
            toolCallId: String,
            arguments: JsonObject,
            onUpdate: AgentToolUpdateCallback
        ) = AgentToolResult(content = listOf(TextContent("done")))
    }

    private fun okStream(): Flow<AssistantMessageEvent> {
        val final = AssistantMessage(
            content = listOf(TextContent("ok")),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = StopReason.STOP,
            timestamp = 42L
        )
        return flowOf(
            AssistantMessageEvent.Start(final.copy(content = emptyList())),
            AssistantMessageEvent.Done(StopReason.STOP, final)
        )
    }

    @Test
    fun `setTools between runs reaches the next run's provider context`() = runTest {
        val captured = CopyOnWriteArrayList<TranscriptContext>()
        val agent = Agent(model = model, streamFn = { _, context, _ ->
            captured.add(context)
            okStream()
        })

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertTrue(getCurrentTools(captured[0].messages).isEmpty())

        agent.setTools(listOf(fakeTool("a"), fakeTool("b")))
        agent.prompt(listOf(UserMessage.ofText("again")))

        assertEquals(listOf("a", "b"), getCurrentTools(captured[1].messages).map { it.name })
    }

    @Test
    fun `setTools during a run affects only later runs`() = runTest {
        val captured = CopyOnWriteArrayList<TranscriptContext>()
        lateinit var agent: Agent
        agent = Agent(model = model, streamFn = { _, context, _ ->
            captured.add(context)
            agent.setTools(listOf(fakeTool("late")))
            okStream()
        })

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertTrue(getCurrentTools(captured[0].messages).isEmpty())

        agent.prompt(listOf(UserMessage.ofText("again")))
        assertEquals(listOf("late"), getCurrentTools(captured[1].messages).map { it.name })
    }

    @Test
    fun `setTools copies the caller's list`() = runTest {
        val agent = Agent(model = model, streamFn = { _, _, _ -> okStream() })
        val tools = mutableListOf(fakeTool("a"))
        agent.setTools(tools)
        tools.add(fakeTool("b"))
        assertEquals(1, agent.state.value.tools.size)
    }

    @Test
    fun `prompt snapshots messages and tools together from state`() = runTest {
        val captured = CopyOnWriteArrayList<TranscriptContext>()
        val agent =
            Agent(model = model, tools = listOf(fakeTool("initial")), streamFn = { _, context, _ ->
                captured.add(context)
                okStream()
            })

        agent.prompt(listOf(UserMessage.ofText("hi")))
        assertEquals(listOf("initial"), getCurrentTools(captured[0].messages).map { it.name })
        assertEquals(2, captured[0].messages.size)
        assertTrue(agent.state.value.messages.size > 1)
    }
}
