package works.resolve.pathfinder.ui.chat

import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.ModelChangeEntry
import works.resolve.pathfinder.data.sessions.assistantMessage
import works.resolve.pathfinder.data.sessions.reasoningPart
import works.resolve.pathfinder.data.sessions.textPart
import works.resolve.pathfinder.data.sessions.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [buildTreeRows] projection semantics: sibling ordering (active subtree
 * first), indentation, connectors and gutters, the two filters with
 * re-parenting over hidden entries, and preview normalization.
 */
class TreeProjectionTest {

    private fun user(id: String, parentId: String? = null, ts: Long = 0) =
        MessageEntry(id, parentId, ts, userMessage(id))

    private fun assistant(id: String, parentId: String? = null, ts: Long = 0) =
        MessageEntry(id, parentId, ts, assistantMessage(textPart(id), epochMs = ts))

    @Test
    fun emptyConversationYieldsNoRows() {
        assertEquals(emptyList(), buildTreeRows(Conversation(emptyList(), null), TreeFilter.DEFAULT))
    }

    @Test
    fun linearChainStaysAtRootIndentWithoutConnectors() {
        val conversation = Conversation(
            listOf(user("u", ts = 1), assistant("a", "u", 2), user("u2", "a", 3)),
            leafId = "u2",
        )

        val rows = buildTreeRows(conversation, TreeFilter.DEFAULT)

        assertEquals(listOf("u", "a", "u2"), rows.map { it.id })
        rows.forEach {
            assertEquals(0, it.indent)
            assertEquals(TreeConnector.NONE, it.connector)
            assertEquals(emptyList(), it.gutters)
            assertEquals(true, it.isOnActivePath)
        }
        assertEquals(listOf(false, false, true), rows.map { it.isCurrentLeaf })
        // Only the root starts a segment; single-child continuations are not foldable.
        assertEquals(listOf(true, false, false), rows.map { it.isFoldable })
    }

    @Test
    fun branchSiblingsOrderActiveSubtreeFirstWithTeeThenElbow() {
        val conversation = Conversation(
            listOf(user("m0", ts = 1), user("m1", "m0", 2), user("m2", "m0", 3)),
            leafId = "m2",
        )

        val rows = buildTreeRows(conversation, TreeFilter.DEFAULT)

        // The active sibling renders before its older abandoned sibling.
        assertEquals(listOf("m0", "m2", "m1"), rows.map { it.id })
        assertEquals(listOf(0, 1, 1), rows.map { it.indent })
        assertEquals(
            listOf(TreeConnector.NONE, TreeConnector.TEE, TreeConnector.ELBOW),
            rows.map { it.connector },
        )
        assertEquals(listOf(true, true, false), rows.map { it.isOnActivePath })
        assertEquals(listOf(false, true, false), rows.map { it.isCurrentLeaf })
        // Paths carry ancestors root-first, ending with the row's own id.
        assertEquals(listOf("m0", "m2"), rows[1].path)
    }

    @Test
    fun descendantsOfTeeKeepGuideWhileLaterSiblingsFollow() {
        val conversation = Conversation(
            listOf(
                user("r", ts = 1),
                user("a", "r", 2),
                user("a1", "a", 3),
                user("b", "r", 4),
                user("b1", "b", 5),
            ),
            leafId = "b1",
        )

        val rows = buildTreeRows(conversation, TreeFilter.DEFAULT)

        // Active path first: r, b, b1 read as an unbroken run, then a, a1.
        assertEquals(listOf("r", "b", "b1", "a", "a1"), rows.map { it.id })
        assertEquals(listOf(0, 1, 2, 1, 2), rows.map { it.indent })
        assertEquals(
            listOf(TreeConnector.NONE, TreeConnector.TEE, TreeConnector.NONE, TreeConnector.ELBOW, TreeConnector.NONE),
            rows.map { it.connector },
        )
        // Only b1 keeps a │ guide: its ancestor b is a non-last (├─) sibling.
        assertEquals(listOf(emptyList(), emptyList(), listOf(0), emptyList(), emptyList()), rows.map { it.gutters })
        assertEquals(listOf(true, true, true, false, false), rows.map { it.isOnActivePath })
        // Roots and first-generation branch children start foldable segments.
        assertEquals(listOf(true, true, false, true, false), rows.map { it.isFoldable })
    }

    @Test
    fun userOnlyFilterHidesAssistantsAndReparentsTheirChildren() {
        val conversation = Conversation(
            listOf(user("u1", ts = 1), assistant("a1", "u1", 2), user("u2", "a1", 3)),
            leafId = "u2",
        )

        assertEquals(listOf("u1", "a1", "u2"), buildTreeRows(conversation, TreeFilter.DEFAULT).map { it.id })

        val users = buildTreeRows(conversation, TreeFilter.USER_ONLY)
        // u2 re-parents to its nearest visible ancestor (u1); the visible
        // chain never branches, so it stays unindented and unconnected.
        assertEquals(listOf("u1", "u2"), users.map { it.id })
        assertEquals(listOf(0, 0), users.map { it.indent })
        assertEquals(listOf(TreeConnector.NONE, TreeConnector.NONE), users.map { it.connector })
        assertEquals(listOf(listOf("u1"), listOf("u1", "u2")), users.map { it.path })
    }

    @Test
    fun modelChangeEntriesNeverRenderButKeepTheirSubtreeAttached() {
        val conversation = Conversation(
            listOf(
                user("u1", ts = 1),
                ModelChangeEntry("c0", "u1", 2, providerId = "openai", modelId = "gpt-x"),
                user("u2", "c0", 3),
            ),
            leafId = "u2",
        )

        val rows = buildTreeRows(conversation, TreeFilter.DEFAULT)

        assertEquals(listOf("u1", "u2"), rows.map { it.id })
        assertEquals(listOf("u1", "u2"), rows[1].path) // u2 re-parented past the hidden change
    }

    @Test
    fun multipleRootsRenderUnshiftedAndIndentTheirDescendants() {
        val conversation = Conversation(
            listOf(user("r1", ts = 1), user("r2", ts = 2), user("c2", "r2", 3)),
            leafId = "c2",
        )

        val rows = buildTreeRows(conversation, TreeFilter.DEFAULT)

        // Roots behave as children of a virtual branching root: no shift, no
        // connector; their descendants indent one level. Active root first.
        assertEquals(listOf("r2", "c2", "r1"), rows.map { it.id })
        assertEquals(listOf(0, 1, 0), rows.map { it.indent })
        assertEquals(
            listOf(TreeConnector.NONE, TreeConnector.NONE, TreeConnector.NONE),
            rows.map { it.connector },
        )
        assertEquals(listOf(true, true, false), rows.map { it.isOnActivePath })
    }

    @Test
    fun previewNormalizesWhitespacePrefixesRoleAndBoundsLength() {
        val conversational = Conversation(
            listOf(
                MessageEntry("u", null, 1, userMessage("  multi\n\nline text ", 1)),
                MessageEntry("a", "u", 2, assistantMessage(textPart("answer"), epochMs = 2)),
            ),
            leafId = "a",
        )

        val rows = buildTreeRows(conversational, TreeFilter.DEFAULT)

        assertEquals("You: multi line text", rows[0].preview)
        assertEquals("Assistant: answer", rows[1].preview)

        // Reasoning-only assistants carry no renderable text.
        val reasoningOnly = Conversation(
            listOf(MessageEntry("r", null, 1, assistantMessage(reasoningPart("hmm"), epochMs = 1))),
            leafId = "r",
        )
        assertEquals("Assistant: (no content)", buildTreeRows(reasoningOnly, TreeFilter.DEFAULT).single().preview)

        // The body is bounded even for huge messages.
        val long = Conversation(
            listOf(MessageEntry("big", null, 1, userMessage("x".repeat(300), 1))),
            leafId = "big",
        )
        assertEquals("You: " + "x".repeat(120), buildTreeRows(long, TreeFilter.DEFAULT).single().preview)
    }
}
