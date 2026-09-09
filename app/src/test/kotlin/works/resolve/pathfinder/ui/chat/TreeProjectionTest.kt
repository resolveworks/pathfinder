package works.resolve.pathfinder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.codingagent.core.MessageEntry
import works.resolve.pathfinder.codingagent.core.SessionEntry
import works.resolve.pathfinder.codingagent.core.SessionTreeNode

class TreeProjectionTest {

    private var clock = 0L

    private fun user(text: String) = UserMessage.ofText(text, clock++)
    private fun assistant(text: String, error: String? = null) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = "openai-completions",
        provider = "zai",
        model = "glm-4.7",
        stopReason = StopReason.STOP,
        errorMessage = error,
        timestamp = clock++
    )

    private fun assistantCalling(vararg calls: ToolCall) = AssistantMessage(
        content = calls.toList(),
        api = "openai-completions",
        provider = "zai",
        model = "glm-4.7",
        stopReason = StopReason.TOOL_USE,
        timestamp = clock++
    )

    private fun toolResult(id: String, toolName: String) = ToolResultMessage(
        toolCallId = id,
        toolName = toolName,
        content = listOf(TextContent("result text")),
        timestamp = clock++
    )

    private fun entry(id: String, parent: String?, message: works.resolve.pathfinder.ai.Message) =
        MessageEntry(id, parentId = parent, timestamp = clock++, message = message)

    private fun rows(
        tree: List<SessionTreeNode>,
        leafId: String?,
        filter: TreeFilter = TreeFilter.DEFAULT
    ) = buildTreeRows(tree, leafId, filter)

    /** pi's tree-selector.test.ts buildTree: nodes over a flat entry list. */
    private fun buildTree(entries: List<SessionEntry>): List<SessionTreeNode> {
        val byId = entries.associateBy { it.id }
        val children = HashMap<String, MutableList<SessionEntry>>()
        val roots = ArrayList<SessionEntry>()
        for (entry in entries) {
            val parent = entry.parentId?.let(byId::get)
            if (parent == null) {
                roots += entry
            } else {
                children.getOrPut(parent.id) { mutableListOf() } += entry
            }
        }
        fun nodeOf(entry: SessionEntry): SessionTreeNode =
            SessionTreeNode(entry, (children[entry.id] ?: emptyList()).map(::nodeOf))
        return roots.map(::nodeOf)
    }

    @Test
    fun `empty conversation yields no rows`() {
        assertTrue(rows(emptyList(), null).isEmpty())
    }

    @Test
    fun `linear chain keeps order - all rows on active path`() {
        val u1 = entry("u1", null, user("first"))
        val a1 = entry("a1", "u1", assistant("answer one"))
        val u2 = entry("u2", "a1", user("second"))
        val a2 = entry("a2", "u2", assistant("answer two"))
        val conversation = buildTree(listOf(u1, a1, u2, a2)) to "a2"

        val result = rows(conversation.first, conversation.second)
        assertEquals(listOf("u1", "a1", "u2", "a2"), result.map { it.id })
        result.forEach { assertTrue(it.isOnActivePath) }
        assertEquals(listOf(true, false, false, false), result.map { it.isFoldable })
        assertEquals("a2", result.last().id)
        assertTrue(result.last().isCurrentLeaf)
        assertFalse(result.first().isCurrentLeaf)
        assertEquals(listOf("u1"), result[0].path)
        assertEquals(listOf("u1", "a1", "u2", "a2"), result[3].path)
        assertEquals(
            listOf("You", "Assistant", "You", "Assistant"),
            result.map {
                (it.body as TreeRowBody.Text).preview.substringBefore(":")
            }
        )
    }

    @Test
    fun `fork after branch - active branch first with tee, old branch last with elbow`() {
        val u1 = entry("u1", null, user("hello"))
        val a1 = entry("a1", "u1", assistant("old answer"))
        val u2 = entry("u2", "a1", user("follow-up"))
        val a2 = entry("a2", "u1", assistant("new answer"))
        val conversation = buildTree(listOf(u1, a1, u2, a2)) to "a2"

        val result = rows(conversation.first, conversation.second)
        assertEquals(listOf("u1", "a2", "a1", "u2"), result.map { it.id })
        assertTrue(result[0].isFoldable)
        assertTrue(result[1].isCurrentLeaf)
        assertFalse(result[1].isFoldable)
        assertEquals(setOf("u1", "a2"), result.filter { it.isOnActivePath }.map { it.id }.toSet())
        assertEquals(listOf("u1", "a1", "u2"), result[3].path)
    }

    @Test
    fun `forked branches keep the active chain first and dead branches foldable`() {
        val r = entry("r", null, user("root"))
        val b2 = entry("b2", "r", assistant("dead end"))
        val b1 = entry("b1", "r", assistant("active"))
        val c1 = entry("c1", "b1", assistant("continuation"))
        val d1 = entry("d1", "c1", assistant("more"))
        val conversation = buildTree(listOf(r, b2, b1, c1, d1)) to "d1"

        val result = rows(conversation.first, conversation.second)
        assertEquals(listOf("r", "b1", "c1", "d1", "b2"), result.map { it.id })
        assertEquals(
            listOf(true, true, false, false, false),
            result.map { it.isFoldable }
        )
    }

    @Test
    fun `user_only filter keeps user rows and recomputes the visual structure`() {
        val u1 = entry("u1", null, user("first"))
        val a1 = entry("a1", "u1", assistant("one"))
        val u2 = entry("u2", "a1", user("second"))
        val a2 = entry("a2", "u2", assistant("two"))
        val u2b = entry("u2b", "u1", user("first-edited"))
        val conversation = buildTree(listOf(u1, a1, u2, a2, u2b)) to "u2b"

        val all = rows(conversation.first, conversation.second, TreeFilter.DEFAULT)
        assertEquals(listOf("u1", "u2b", "a1", "u2", "a2"), all.map { it.id })

        val filtered = rows(conversation.first, conversation.second, TreeFilter.USER_ONLY)
        assertEquals(listOf("u1", "u2b", "u2"), filtered.map { it.id })
        assertEquals(
            listOf("You: first", "You: first-edited", "You: second"),
            filtered.map {
                (it.body as TreeRowBody.Text).preview
            }
        )
        assertTrue(filtered[0].isFoldable)
        assertTrue(filtered[1].isCurrentLeaf)
        // Paths skip hidden ancestors.
        assertEquals(listOf("u1", "u2"), filtered[2].path)
    }

    @Test
    fun `multiple roots behave as children of a virtual branching root`() {
        val r1 = entry("r1", null, user("hello"))
        val a1 = entry("a1", "r1", assistant("world"))
        val r2 = entry("r2", null, user("hello edited"))
        val a2 = entry("a2", "r2", assistant("rewritten"))
        val conversation = buildTree(listOf(r1, a1, r2, a2)) to "a2"

        val result = rows(conversation.first, conversation.second)
        assertEquals(listOf("r2", "a2", "r1", "a1"), result.map { it.id })
        assertEquals(listOf(true, false, true, false), result.map { it.isFoldable })
        assertEquals(setOf("r2", "a2"), result.filter { it.isOnActivePath }.map { it.id }.toSet())
    }

    @Test
    fun `previews - role prefix, normalization, bounding, empty and error`() {
        val multiline = entry("m", null, user("  line one\n   line two  \n\nline three  "))
        val long = entry("l", "m", user("x".repeat(300)))
        val empty = entry("e", "l", assistant(""))
        val failed = entry("f", "e", assistant("ignored", error = "boom happened"))
        val conversation = buildTree(listOf(multiline, long, empty, failed)) to "f"

        val result = rows(conversation.first, conversation.second)
        fun previewAt(i: Int) = (result[i].body as TreeRowBody.Text).preview
        assertEquals("You: line one line two line three", previewAt(0))
        assertEquals("You: " + "x".repeat(120), previewAt(1))
        assertEquals(TreeRowBody.NoContent, result[2].body)
        assertEquals("Assistant: boom happened", previewAt(3))
    }

    @Test
    fun `tool result rows title from the originating call`() {
        val call = ToolCall(
            id = "t1",
            name = "web_search",
            arguments = """{"query":"kotlin compose"}"""
        )
        val u1 = entry("u1", null, user("look it up"))
        val a1 = entry("a1", "u1", assistantCalling(call))
        val t1 = entry("t1", "a1", toolResult("t1", "web_search"))
        val a2 = entry("a2", "t1", assistant("done"))
        val conversation = buildTree(listOf(u1, a1, t1, a2)) to "a2"

        val result = rows(conversation.first, conversation.second)
        assertEquals(
            TreeRowBody.Tool("web_search", call),
            result.first { it.id == "t1" }.body
        )
        // a1 carries only a tool call: no text preview exists, so its body
        // is the no-content variant rather than an empty Text preview.
        assertEquals(TreeRowBody.NoContent, result.first { it.id == "a1" }.body)
        result
            .filter { it.id != "t1" && it.id != "a1" }
            .forEach { assertTrue(it.body is TreeRowBody.Text) }
    }

    @Test
    fun `tool rows without a usable call keep the bare tool name`() {
        // Known tool, but malformed arguments: no parsed input.
        val badJson = entry(
            "t1",
            "a1",
            toolResult("t1", "web_search").copy(content = emptyList())
        )
        // Unknown tool: no title spec regardless of arguments.
        val unknown = entry("t2", "t1", toolResult("t2", "mystery_tool"))
        // Orphaned result: the originating call never committed.
        val orphan = entry("t3", "t2", toolResult("t9", "web_fetch"))
        val conversation = buildTree(
            listOf(
                entry("u1", null, user("q")),
                entry(
                    "a1",
                    "u1",
                    assistantCalling(
                        ToolCall("t1", "web_search", "not json"),
                        ToolCall("t2", "mystery_tool", """{"x":1}""")
                    )
                ),
                badJson,
                unknown,
                orphan
            )
        ) to "t3"

        val result = rows(conversation.first, conversation.second)
        // Rows carry the originating call as-is (pi's toolCallMap); titles
        // parse from the call at render, falling back to the bare name.
        val t1Body = result.first { it.id == "t1" }.body as TreeRowBody.Tool
        assertEquals("web_search", t1Body.name)
        assertEquals("t1", t1Body.call?.id)
        assertEquals("not json", t1Body.call?.arguments)
        val t2Body = result.first { it.id == "t2" }.body as TreeRowBody.Tool
        assertEquals("mystery_tool", t2Body.name)
        assertEquals("""{"x":1}""", t2Body.call?.arguments)
        // Orphaned result: the originating call never committed.
        assertEquals(TreeRowBody.Tool("web_fetch", null), result.first { it.id == "t3" }.body)
    }

    @Test
    fun `orphan entries become roots`() {
        val o = entry("orphan", "missing", user("orphaned"))
        val r = entry("r", null, user("root"))
        val conversation = buildTree(listOf(r, o)) to "r"

        val result = rows(conversation.first, conversation.second)
        assertEquals(setOf("r", "orphan"), result.map { it.id }.toSet())
        assertTrue(result.first { it.id == "orphan" }.path == listOf("orphan"))
    }
}
