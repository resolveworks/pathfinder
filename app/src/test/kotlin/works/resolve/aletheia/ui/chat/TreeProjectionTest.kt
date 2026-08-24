package works.resolve.aletheia.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.data.sessions.Conversation
import works.resolve.aletheia.data.sessions.MessageEntry

class TreeProjectionTest {

    private var clock = 0L

    private fun user(text: String) = UserMessage.ofText(text, clock++)
    private fun assistant(
        text: String,
        error: String? = null,
    ) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = "openai-completions",
        provider = "zai",
        model = "glm-4.7",
        stopReason = StopReason.STOP,
        errorMessage = error,
        timestamp = clock++,
    )

    private fun entry(id: String, parent: String?, message: works.resolve.aletheia.ai.core.Message) =
        MessageEntry(id, parent, clock++, message)

    private fun rows(conversation: Conversation, filter: TreeFilter = TreeFilter.DEFAULT) =
        buildTreeRows(conversation, filter)

    @Test
    fun `empty conversation yields no rows`() {
        assertTrue(rows(Conversation(emptyList(), null)).isEmpty())
    }

    @Test
    fun `linear chain is flat - no branch points, all on active path`() {
        val u1 = entry("u1", null, user("first"))
        val a1 = entry("a1", "u1", assistant("answer one"))
        val u2 = entry("u2", "a1", user("second"))
        val a2 = entry("a2", "u2", assistant("answer two"))
        val conversation = Conversation(listOf(u1, a1, u2, a2), "a2")

        val result = rows(conversation)
        assertEquals(listOf("u1", "a1", "u2", "a2"), result.map { it.id })
        result.forEach {
            assertEquals(0, it.depth)
            assertTrue(it.isOnActivePath)
            assertFalse(it.isBranchPoint)
        }
        assertEquals("a2", result.last().id)
        assertTrue(result.last().isCurrentLeaf)
        assertFalse(result.first().isCurrentLeaf)
        assertEquals(listOf("u1"), result[0].path)
        assertEquals(listOf("u1", "a1", "u2", "a2"), result[3].path)
        assertEquals(listOf(true, false, true, false), result.map { it.isUser })
    }

    @Test
    fun `fork after branch - branch point at parent, active branch first`() {
        // u1 -> a1 -> u2 (active) ; u1 -> a1x (old branch, same parent a1... no:
        // fork at u1: children a1 (old) and a2 (new active path).
        val u1 = entry("u1", null, user("hello"))
        val a1 = entry("a1", "u1", assistant("old answer"))
        val u2 = entry("u2", "a1", user("follow-up"))
        // New branch forked from u1 (re-edit): sibling of a1.
        val a2 = entry("a2", "u1", assistant("new answer"))
        val conversation = Conversation(listOf(u1, a1, u2, a2), "a2")

        val result = rows(conversation)
        // Active subtree (a2) first among siblings, despite being younger.
        assertEquals(listOf("u1", "a2", "a1", "u2"), result.map { it.id })
        // u1 branches: children get depth 1; single-child chain u2 stays flat.
        assertEquals(0, result[0].depth)
        assertTrue(result[0].isBranchPoint)
        assertEquals(1, result[1].depth)
        assertEquals(1, result[2].depth)
        assertEquals(1, result[3].depth)
        // Active path flags.
        assertEquals(setOf("u1", "a2"), result.filter { it.isOnActivePath }.map { it.id }.toSet())
        assertTrue(result[1].isCurrentLeaf)
        assertFalse(result[1].isBranchPoint)
        // Paths include full ancestor chains.
        assertEquals(listOf("u1", "a1", "u2"), result[3].path)
    }

    @Test
    fun `single child chains stay flat, grandchildren of branches keep depth`() {
        // r branches into b1 (active) and b2; b1 has a single child c1.
        val r = entry("r", null, user("root"))
        val b2 = entry("b2", "r", assistant("dead end"))
        val b1 = entry("b1", "r", assistant("active"))
        val c1 = entry("c1", "b1", assistant("continuation"))
        val conversation = Conversation(listOf(r, b2, b1, c1), "c1")

        val result = rows(conversation)
        assertEquals(listOf("r", "b1", "c1", "b2"), result.map { it.id })
        assertEquals(0, result[0].depth)
        assertEquals(1, result[1].depth)
        // c1 is a single child of b1: stays at b1's depth.
        assertEquals(1, result[2].depth)
        assertEquals(1, result[3].depth)
        assertTrue(result[0].isBranchPoint)
        assertFalse(result[1].isBranchPoint)
    }

    @Test
    fun `user_only filter keeps user rows and recomputes branch points`() {
        val u1 = entry("u1", null, user("first"))
        val a1 = entry("a1", "u1", assistant("one"))
        val u2 = entry("u2", "a1", user("second"))
        val a2 = entry("a2", "u2", assistant("two"))
        // Re-edit fork at u1: u2b is a sibling of a1 under u1 (active leaf path).
        val u2b = entry("u2b", "u1", user("first-edited"))
        val conversation = Conversation(listOf(u1, a1, u2, a2, u2b), "u2b")

        val all = rows(conversation, TreeFilter.DEFAULT)
        assertEquals(listOf("u1", "u2b", "a1", "u2", "a2"), all.map { it.id })
        assertEquals(1, all.first { it.id == "u2b" }.depth)

        val filtered = rows(conversation, TreeFilter.USER_ONLY)
        // Assistant rows hidden; u2 re-parents to its nearest visible ancestor u1.
        assertEquals(listOf("u1", "u2b", "u2"), filtered.map { it.id })
        assertEquals(listOf(true, true, true), filtered.map { it.isUser })
        // u1 branches over visible children (u2b active-first, then u2).
        assertEquals(0, filtered[0].depth)
        assertTrue(filtered[0].isBranchPoint)
        assertEquals(1, filtered[1].depth)
        assertEquals(1, filtered[2].depth)
        assertTrue(filtered[1].isCurrentLeaf)
        // Paths skip hidden ancestors.
        assertEquals(listOf("u1", "u2"), filtered[2].path)
    }

    @Test
    fun `previews - role prefix, normalization, bounding, empty and error`() {
        val multiline = entry("m", null, user("  line one\n   line two  \n\nline three  "))
        val long = entry("l", "m", user("x".repeat(300)))
        val empty = entry("e", "l", assistant(""))
        val failed = entry("f", "e", assistant("ignored", error = "boom happened"))
        val conversation = Conversation(listOf(multiline, long, empty, failed), "f")

        val result = rows(conversation)
        assertEquals("You: line one line two line three", result[0].preview)
        assertEquals("You: " + "x".repeat(120), result[1].preview)
        assertEquals("Assistant: (no content)", result[2].preview)
        assertEquals("Assistant: boom happened", result[3].preview)
    }

    @Test
    fun `orphan entries become roots`() {
        val o = entry("orphan", "missing", user("orphaned"))
        val r = entry("r", null, user("root"))
        val conversation = Conversation(listOf(r, o), "r")

        val result = rows(conversation)
        assertEquals(setOf("r", "orphan"), result.map { it.id }.toSet())
        result.forEach { assertEquals(0, it.depth) }
        assertTrue(result.first { it.id == "orphan" }.path == listOf("orphan"))
    }
}
