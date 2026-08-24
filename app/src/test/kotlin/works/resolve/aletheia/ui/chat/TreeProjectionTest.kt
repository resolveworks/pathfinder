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

/**
 * Tests for [buildTreeRows], mirroring pi's tree-selector flattening
 * (tree-selector.test.ts "branch navigation and folding" tree shape):
 *
 * user-1 / asst-1 / user-2 / asst-2 (branch point)
 * ├─ user-3a (active)   │  asst-3a … (gutter)
 * └─ user-3b            asst-3b … (no gutter)
 */
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
    fun `linear chain is flat - no connectors, all on active path`() {
        val u1 = entry("u1", null, user("first"))
        val a1 = entry("a1", "u1", assistant("answer one"))
        val u2 = entry("u2", "a1", user("second"))
        val a2 = entry("a2", "u2", assistant("answer two"))
        val conversation = Conversation(listOf(u1, a1, u2, a2), "a2")

        val result = rows(conversation)
        assertEquals(listOf("u1", "a1", "u2", "a2"), result.map { it.id })
        result.forEach {
            assertEquals(0, it.indent)
            assertEquals(TreeConnector.NONE, it.connector)
            assertTrue(it.gutters.isEmpty())
            assertTrue(it.isOnActivePath)
        }
        // Only the root starts a segment, so only it is foldable.
        assertEquals(listOf(true, false, false, false), result.map { it.isFoldable })
        assertEquals("a2", result.last().id)
        assertTrue(result.last().isCurrentLeaf)
        assertFalse(result.first().isCurrentLeaf)
        assertEquals(listOf("u1"), result[0].path)
        assertEquals(listOf("u1", "a1", "u2", "a2"), result[3].path)
        assertEquals(listOf("You", "Assistant", "You", "Assistant"), result.map { it.preview.substringBefore(":") })
    }

    @Test
    fun `fork after branch - active branch first with tee, old branch last with elbow`() {
        val u1 = entry("u1", null, user("hello"))
        val a1 = entry("a1", "u1", assistant("old answer"))
        val u2 = entry("u2", "a1", user("follow-up"))
        // New branch forked from u1 (re-edit): sibling of a1.
        val a2 = entry("a2", "u1", assistant("new answer"))
        val conversation = Conversation(listOf(u1, a1, u2, a2), "a2")

        val result = rows(conversation)
        // Active subtree (a2) first among siblings, despite being younger.
        assertEquals(listOf("u1", "a2", "a1", "u2"), result.map { it.id })
        // u1 branches: children get indent 1 with connectors.
        assertEquals(0, result[0].indent)
        assertTrue(result[0].isFoldable)
        assertEquals(1, result[1].indent)
        assertEquals(TreeConnector.TEE, result[1].connector)
        assertEquals(1, result[2].indent)
        assertEquals(TreeConnector.ELBOW, result[2].connector)
        // u2 is the first generation below the branch: indent once more for
        // visual grouping, no connector (single child), no gutter below the
        // elbow (last sibling).
        assertEquals(2, result[3].indent)
        assertEquals(TreeConnector.NONE, result[3].connector)
        assertTrue(result[3].gutters.isEmpty())
        // Active path flags.
        assertEquals(setOf("u1", "a2"), result.filter { it.isOnActivePath }.map { it.id }.toSet())
        assertTrue(result[1].isCurrentLeaf)
        assertFalse(result[1].isFoldable)
        // Paths include full ancestor chains.
        assertEquals(listOf("u1", "a1", "u2"), result[3].path)
    }

    @Test
    fun `first generation below a branch indents, later chains stay flat`() {
        // r branches into b1 (active) and b2; b1 has a single child c1 with
        // its own single child d1.
        val r = entry("r", null, user("root"))
        val b2 = entry("b2", "r", assistant("dead end"))
        val b1 = entry("b1", "r", assistant("active"))
        val c1 = entry("c1", "b1", assistant("continuation"))
        val d1 = entry("d1", "c1", assistant("more"))
        val conversation = Conversation(listOf(r, b2, b1, c1, d1), "d1")

        val result = rows(conversation)
        assertEquals(listOf("r", "b1", "c1", "d1", "b2"), result.map { it.id })
        // b1's subtree keeps a │ gutter at the branch level (b2 follows).
        assertEquals(TreeConnector.TEE, result[1].connector)
        assertEquals(1, result[1].indent)
        assertEquals(listOf(0), result[2].gutters)
        // c1 and d1: first generation below the branch indents once more,
        // then the chain stays flat.
        assertEquals(2, result[2].indent)
        assertEquals(2, result[3].indent)
        assertEquals(TreeConnector.NONE, result[2].connector)
        assertEquals(TreeConnector.NONE, result[3].connector)
        // The dead-end branch renders last with the elbow.
        assertEquals(1, result[4].indent)
        assertEquals(TreeConnector.ELBOW, result[4].connector)
        // Foldable: the root and the segment starts below the branch point
        // (b2 itself is childless, so despite starting a segment it cannot fold).
        assertEquals(
            listOf(true, true, false, false, false),
            result.map { it.isFoldable },
        )
    }

    @Test
    fun `user_only filter keeps user rows and recomputes the visual structure`() {
        val u1 = entry("u1", null, user("first"))
        val a1 = entry("a1", "u1", assistant("one"))
        val u2 = entry("u2", "a1", user("second"))
        val a2 = entry("a2", "u2", assistant("two"))
        // Re-edit fork at u1: u2b is a sibling of a1 under u1 (active leaf path).
        val u2b = entry("u2b", "u1", user("first-edited"))
        val conversation = Conversation(listOf(u1, a1, u2, a2, u2b), "u2b")

        val all = rows(conversation, TreeFilter.DEFAULT)
        assertEquals(listOf("u1", "u2b", "a1", "u2", "a2"), all.map { it.id })
        assertEquals(1, all.first { it.id == "u2b" }.indent)
        assertEquals(TreeConnector.TEE, all.first { it.id == "u2b" }.connector)

        val filtered = rows(conversation, TreeFilter.USER_ONLY)
        // Assistant rows hidden; u2 re-parents to its nearest visible ancestor u1.
        assertEquals(listOf("u1", "u2b", "u2"), filtered.map { it.id })
        assertEquals(listOf("You: first", "You: first-edited", "You: second"), filtered.map { it.preview })
        // u1 branches over visible children (u2b active-first, then u2).
        assertEquals(0, filtered[0].indent)
        assertTrue(filtered[0].isFoldable)
        assertEquals(1, filtered[1].indent)
        assertEquals(TreeConnector.TEE, filtered[1].connector)
        assertEquals(1, filtered[2].indent)
        assertEquals(TreeConnector.ELBOW, filtered[2].connector)
        assertTrue(filtered[1].isCurrentLeaf)
        // Paths skip hidden ancestors.
        assertEquals(listOf("u1", "u2"), filtered[2].path)
    }

    @Test
    fun `multiple roots behave as children of a virtual branching root`() {
        // Two roots (re-edit fork at the root); the second is active.
        val r1 = entry("r1", null, user("hello"))
        val a1 = entry("a1", "r1", assistant("world"))
        val r2 = entry("r2", null, user("hello edited"))
        val a2 = entry("a2", "r2", assistant("rewritten"))
        val conversation = Conversation(listOf(r1, a1, r2, a2), "a2")

        val result = rows(conversation)
        // Active root's subtree first.
        assertEquals(listOf("r2", "a2", "r1", "a1"), result.map { it.id })
        // Roots render unshifted without connectors; their descendants still
        // indent one level (pi's virtual branching root), no gutters.
        result.forEach { assertTrue(it.gutters.isEmpty()) }
        assertEquals(listOf(0, 1, 0, 1), result.map { it.indent })
        assertEquals(
            listOf(TreeConnector.NONE, TreeConnector.NONE, TreeConnector.NONE, TreeConnector.NONE),
            result.map { it.connector },
        )
        // Both roots are foldable segment starts with children.
        assertEquals(listOf(true, false, true, false), result.map { it.isFoldable })
        assertEquals(setOf("r2", "a2"), result.filter { it.isOnActivePath }.map { it.id }.toSet())
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
        result.forEach {
            assertEquals(0, it.indent)
            assertEquals(TreeConnector.NONE, it.connector)
        }
        assertTrue(result.first { it.id == "orphan" }.path == listOf("orphan"))
    }
}
