package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentToolTest {

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
    fun `update callback is non-suspending and compiles as a plain lambda`() {
        val updates = mutableListOf<AgentToolResult>()
        val onUpdate: AgentToolUpdateCallback = { updates.add(it) }
        onUpdate(AgentToolResult(content = listOf(TextContent("partial"))))
        assertEquals(1, updates.size)
        assertEquals(listOf(TextContent("partial")), updates.single().content)
    }
}
