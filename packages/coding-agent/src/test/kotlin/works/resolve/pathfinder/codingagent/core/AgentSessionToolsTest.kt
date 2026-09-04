package works.resolve.pathfinder.codingagent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.agent.AgentToolUpdateCallback
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.codingagent.core.session.ActiveToolsEntry
import works.resolve.pathfinder.codingagent.core.session.Conversation

class AgentSessionToolsTest {

    private val model = Model(
        id = "model-a",
        name = "A",
        api = "openai-completions",
        provider = "provider-a",
        baseUrl = "https://a.example.invalid"
    )

    private class FakeTool(
        override val definition: Tool,
        override val promptSnippet: String? = null,
        override val promptGuidelines: List<String> = emptyList()
    ) : AgentTool {
        override val label: String = definition.name
        override fun validateArguments(arguments: JsonObject) = arguments
        override suspend fun execute(
            toolCallId: String,
            arguments: JsonObject,
            onUpdate: AgentToolUpdateCallback
        ): AgentToolResult = AgentToolResult(content = emptyList())
    }

    private fun tool(name: String, snippet: String? = "does $name") = FakeTool(
        Tool(name, "tool $name", JsonObject(emptyMap())),
        promptSnippet = snippet
    )

    private fun assistant(text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.STOP,
        timestamp = 42L
    )

    private fun okStream(): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant("")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant("ok"))
    )

    private fun provider(): Provider = Provider(
        id = model.provider,
        name = model.provider,
        baseUrl = model.baseUrl,
        authResolver = { _, _ -> ResolvedAuth(apiKey = "k") },
        models = listOf(model),
        apis = emptyMap()
    )

    private fun session(
        conversation: Conversation = Conversation(emptyList(), null),
        tools: List<AgentTool> = emptyList(),
        streamFn: (Model, Context, SimpleStreamOptions) -> Flow<AssistantMessageEvent> =
            { _, _, _ -> okStream() }
    ): AgentSession = AgentSession(
        agent = Agent(model = model, streamFn = StreamFn(streamFn)),
        conversation = conversation,
        models = Models(listOf(provider())),
        tools = tools
    )

    @Test
    fun `setActiveToolsByName applies registry-filtered tools and rebuilds the prompt`() = runTest {
        val webSearch = tool("web_search")
        val webFetch = tool("web_fetch")
        val contexts = CopyOnWriteArrayList<Context>()
        val s = session(tools = listOf(webSearch, webFetch)) { _, context, _ ->
            contexts.add(context)
            okStream()
        }

        assertEquals(
            "registry default: all tools active, prompt built",
            listOf("web_search", "web_fetch"),
            s.getActiveToolNames()
        )
        assertEquals(
            buildSystemPrompt(listOf(webSearch, webFetch)),
            s.agent.state.value.systemPrompt
        )

        s.setActiveToolsByName(listOf("web_fetch", "nope", "web_search"))

        assertEquals(
            "unknown names dropped, request order preserved",
            listOf("web_fetch", "web_search"),
            s.getActiveToolNames()
        )
        assertEquals(
            listOf(webFetch, webSearch),
            s.agent.state.value.tools
        )
        assertEquals(
            buildSystemPrompt(listOf(webFetch, webSearch)),
            s.agent.state.value.systemPrompt
        )
        assertEquals("no session entry is appended", 0, s.conversation.entries.size)

        s.prompt("go")
        assertEquals(listOf("web_fetch", "web_search"), contexts.single().tools.map { it.name })
        assertEquals(buildSystemPrompt(listOf(webFetch, webSearch)), contexts.single().systemPrompt)
    }

    @Test
    fun `duplicate names are not deduped`() {
        val webSearch = tool("web_search")
        val s = session(tools = listOf(webSearch))

        s.setActiveToolsByName(listOf("web_search", "web_search"))

        assertEquals(listOf("web_search", "web_search"), s.getActiveToolNames())
        assertEquals(listOf(webSearch, webSearch), s.agent.state.value.tools)
    }

    @Test
    fun `an empty selection yields a null system prompt`() {
        val s = session(tools = listOf(tool("web_search")))

        s.setActiveToolsByName(emptyList())

        assertEquals(emptyList<String>(), s.getActiveToolNames())
        assertNull(s.agent.state.value.systemPrompt)
    }

    @Test
    fun `adoption seeds the folded active set from the branch`() {
        val webSearch = tool("web_search")
        val webFetch = tool("web_fetch")
        val conversation = Conversation(emptyList(), null).let {
            Conversation(
                it.entries + ActiveToolsEntry(
                    id = "e1",
                    parentId = null,
                    timestamp = 1L,
                    activeToolNames = listOf("web_fetch")
                ),
                "e1"
            )
        }

        val s = session(conversation = conversation, tools = listOf(webSearch, webFetch))

        assertEquals(listOf("web_fetch"), s.getActiveToolNames())
        assertEquals(buildSystemPrompt(listOf(webFetch)), s.agent.state.value.systemPrompt)
        assertEquals("adoption does not append", 1, s.conversation.entries.size)
    }

    @Test
    fun `the last active_tools_change on the path wins`() {
        val webSearch = tool("web_search")
        val webFetch = tool("web_fetch")
        val first =
            ActiveToolsEntry(
                "e1",
                parentId = null,
                timestamp = 1L,
                activeToolNames = listOf("web_search")
            )
        val second =
            ActiveToolsEntry(
                "e2",
                parentId = "e1",
                timestamp = 2L,
                activeToolNames = listOf("web_fetch")
            )
        val conversation = Conversation(listOf(first, second), "e2")

        val s = session(conversation = conversation, tools = listOf(webSearch, webFetch))

        assertEquals(listOf("web_fetch"), s.getActiveToolNames())
    }

    @Test
    fun `a branch without an entry activates all registry tools`() {
        val webSearch = tool("web_search")
        val webFetch = tool("web_fetch")

        val s = session(tools = listOf(webSearch, webFetch))

        assertEquals(listOf("web_search", "web_fetch"), s.getActiveToolNames())
        assertEquals(
            buildSystemPrompt(listOf(webSearch, webFetch)),
            s.agent.state.value.systemPrompt
        )
    }

    @Test
    fun `an empty registry keeps everything inert`() {
        val s = session()

        assertEquals(emptyList<String>(), s.getActiveToolNames())
        assertNull(s.agent.state.value.systemPrompt)

        s.setActiveToolsByName(listOf("web_search"))
        assertEquals(
            "unknown names in an empty registry are ignored",
            emptyList<String>(),
            s.getActiveToolNames()
        )
        assertNull(s.agent.state.value.systemPrompt)
    }
}
