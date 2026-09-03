package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.UserMessage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentToolTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid",
    )

    private fun toolUseMessage(vararg calls: ToolCall): AssistantMessage = AssistantMessage(
        content = calls.toList(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.TOOL_USE,
    )

    private fun textMessage(text: String): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.STOP,
    )

    /** One scripted provider response per stream call, like AgentLoopTest's scriptedStream. */
    private fun scriptedStream(vararg messages: AssistantMessage): StreamFn {
        var call = 0
        return StreamFn { _, _, _ ->
            val message = messages.getOrElse(call++) { error("unexpected provider call #${call - 1}") }
            flowOf(AssistantMessageEvent.Done(message.stopReason, message))
        }
    }

    @Test
    fun `result accepts text and image content`() {
        val result = AgentToolResult(
            content = listOf(TextContent("hello"), ImageContent("aGk=", "image/png")),
        )
        assertEquals(2, result.content.size)
    }

    @Test
    fun `result rejects thinking content`() {
        try {
            AgentToolResult(content = listOf(ThinkingContent("hmm")))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("only TextContent or ImageContent"))
        }
    }

    @Test
    fun `result rejects toolCall content`() {
        try {
            AgentToolResult(content = listOf(ToolCall("t1", "web_search", "{}")))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("only TextContent or ImageContent"))
        }
    }

    @Test
    fun `defaults for details usage and addedToolNames`() {
        val result = AgentToolResult(content = emptyList())
        assertNull(result.details)
        assertNull(result.usage)
        assertTrue(result.addedToolNames.isEmpty())
    }

    @Test
    fun `optional interface members default to run-level mode and no prompt contributions`() {
        val tool = object : AgentTool {
            override val definition = Tool("t", "d", JsonPrimitive("object"))
            override val label = "t"
            override fun validateArguments(arguments: JsonObject) = arguments
            override suspend fun execute(toolCallId: String, arguments: JsonObject, onUpdate: AgentToolUpdateCallback) =
                AgentToolResult(content = emptyList())
        }
        assertNull(tool.executionMode)
        assertNull(tool.promptSnippet)
        assertTrue(tool.promptGuidelines.isEmpty())
    }

    @Test
    fun `update callback is non-suspending and compiles as a plain lambda`() {
        val updates = mutableListOf<AgentToolResult>()
        val onUpdate: AgentToolUpdateCallback = { updates.add(it) }
        onUpdate(AgentToolResult(content = listOf(TextContent("partial"))))
        assertEquals(1, updates.size)
        assertEquals(listOf(TextContent("partial")), updates.single().content)
    }

    @Test
    fun `update callback streams partial results with details and drops calls after settlement`() = runTest {
        lateinit var captured: AgentToolUpdateCallback
        val tool = object : AgentTool {
            override val definition = Tool("progress_tool", "reports progress", JsonPrimitive("object"))
            override val label = "progress_tool"
            override fun validateArguments(arguments: JsonObject) = arguments
            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback,
            ): AgentToolResult {
                captured = onUpdate
                onUpdate(
                    AgentToolResult(
                        content = listOf(TextContent("running")),
                        details = buildJsonObject { put("status", "running") },
                    ),
                )
                return AgentToolResult(
                    content = listOf(TextContent("done")),
                    details = buildJsonObject { put("status", "done") },
                )
            }
        }

        val events = mutableListOf<AgentEvent>()
        runAgentLoop(
            listOf(UserMessage.ofText("run")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(
                model,
                streamFn = scriptedStream(
                    toolUseMessage(ToolCall("call-1", "progress_tool", "{}")),
                    textMessage("finished"),
                ),
            ),
        ) { events.add(it) }

        val update = events.filterIsInstance<AgentEvent.ToolExecutionUpdate>().single()
        assertEquals("call-1", update.toolCallId)
        assertEquals("progress_tool", update.toolName)
        assertEquals(listOf(TextContent("running")), update.partialResult.content)
        assertEquals(buildJsonObject { put("status", "running") }, update.partialResult.details)

        // The callback outlives the invocation; late calls are dropped, not thrown.
        captured(AgentToolResult(content = listOf(TextContent("late"))))
        assertEquals(1, events.count { it is AgentEvent.ToolExecutionUpdate })
    }

    @Test
    fun `thrown failure is the error channel and carries pi's empty details object`() = runTest {
        val tool = object : AgentTool {
            override val definition = Tool("shell", "runs a command", JsonPrimitive("object"))
            override val label = "shell"
            override fun validateArguments(arguments: JsonObject) = arguments
            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: AgentToolUpdateCallback,
            ): AgentToolResult = throw IllegalStateException("Command exited with code 7")
        }

        val events = mutableListOf<AgentEvent>()
        runAgentLoop(
            listOf(UserMessage.ofText("run")),
            AgentContext(messages = emptyList(), tools = listOf(tool)),
            AgentLoopConfig(
                model,
                streamFn = scriptedStream(
                    toolUseMessage(ToolCall("call-1", "shell", "{}")),
                    textMessage("handled"),
                ),
            ),
        ) { events.add(it) }

        val end = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single()
        assertTrue(end.isError)
        assertEquals("Command exited with code 7", (end.result.content.single() as TextContent).text)
        assertEquals(JsonObject(emptyMap()), end.result.details)
    }
}
