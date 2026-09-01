package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.testing.FakeClock
import ai.koog.prompt.message.Message
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    private var nextId = 0

    private fun newConversation(): Conversation =
        Conversation(
            entries = emptyList(),
            leafId = null,
            idGenerator = { "e${nextId++}" },
            clock = FakeClock(),
        )

    private fun msg(text: String) = userMessage(text)

    @Test
    fun appendChainsFromEmpty() {
        val c = newConversation()
            .append(msg("a"))
            .append(msg("b"))

        assertEquals(listOf("e0", "e1"), c.entries.map { it.id })
        assertNull(c.entries.first().parentId)
        assertEquals("e0", c.entries[1].parentId)
        assertEquals("e1", c.leafId)
        assertEquals(listOf("a", "b"), c.activeMessages().texts())
    }

    @Test
    fun appendAfterBranchCreatesSiblingFork() {
        val base = newConversation().append(msg("a"))
        val branchA = base.append(msg("b1"))
        val branchB = branchA.branch(base.leafId!!).append(msg("b2"))

        // Both siblings are children of the root entry in the tree.
        val root = branchB.tree().single()
        assertEquals(base.leafId, root.entry.id)
        assertEquals(2, root.children.size)

        // The active path excludes the abandoned branch.
        assertEquals(listOf("a", "b2"), branchB.activeMessages().texts())
        assertEquals(listOf("a", "b1"), branchA.activeMessages().texts())
    }

    @Test
    fun branchToEarlierPointThenAppend() {
        val c = newConversation()
            .append(msg("a"))
            .append(msg("b"))
            .append(msg("c"))
        val first = c.entries.first()

        val rewound = c.branch(first.id).append(msg("a2"))

        assertEquals(first.id, rewound.entries.last().parentId)
        assertEquals(listOf("a", "a2"), rewound.activeMessages().texts())
        // Original branch still exists in the tree.
        assertEquals(2, rewound.tree().single().children.size)
    }

    @Test
    fun resetLeafMakesNextAppendARoot() {
        val c = newConversation().append(msg("a")).append(msg("b"))
        val reset = c.resetLeaf()

        assertNull(reset.leafId)
        assertTrue(reset.activeEntries().isEmpty())

        val after = reset.append(msg("c"))
        assertNull(after.entries.last().parentId)
        assertEquals(listOf("c"), after.activeMessages().texts())
        assertEquals(2, after.tree().size)
    }

    @Test
    fun treeChildrenSortedOldestFirst() {
        val root = MessageEntry("root", null, 0L, msg("r"))
        val young = MessageEntry("young", "root", 30L, msg("y"))
        val old = MessageEntry("old", "root", 10L, msg("o"))
        val middle = MessageEntry("middle", "root", 20L, msg("m"))
        val c = Conversation(listOf(young, middle, root, old), "young")

        assertEquals(listOf("old", "middle", "young"), c.tree().single().children.map { it.entry.id })
    }

    @Test
    fun appendModelChangeAdvancesLeafAndFoldsToLastEntry() {
        val c = newConversation()
            .appendModelChange("anthropic", "claude-a")
            .append(msg("a"))
            .appendModelChange("openai", "gpt-b")

        val change = c.entries.last()
        assertEquals("gpt-b", (change as ModelChangeEntry).modelId)
        assertEquals(c.leafId, change.id)
        // Fold: last ModelChangeEntry on the active path wins; messages skip it.
        assertEquals(Conversation.ModelRef("openai", "gpt-b"), c.activeModelRef())
        assertEquals(listOf("a"), c.activeMessages().texts())
    }

    @Test
    fun foldRespectsBranchMoves() {
        val withChange = newConversation().appendModelChange("openai", "gpt-b")
        val base = withChange.append(msg("a"))
        val branched = base.appendModelChange("anthropic", "claude-a").append(msg("b"))

        assertEquals(Conversation.ModelRef("anthropic", "claude-a"), branched.activeModelRef())

        // Rewind to the first model-change entry's child: that branch's fold
        // is the older entry.
        val rewound = branched.branch(base.leafId!!)
        assertEquals(Conversation.ModelRef("openai", "gpt-b"), rewound.activeModelRef())
        // Rewind to the root's parent chain without any entry: fold is null.
        assertNull(withChange.resetLeaf().activeModelRef())
    }

    @Test
    fun foldIsNullWhenPathHasNoModelChange() {
        assertNull(newConversation().activeModelRef())
        assertNull(newConversation().append(msg("a")).activeModelRef())
    }

    @Test
    fun entryLookupAndUnknownBranchRejected() {
        val c = newConversation().append(msg("a"))
        assertEquals("e0", c.entry("e0")!!.id)
        assertNull(c.entry("nope"))
        assertFailsWith<IllegalArgumentException> { c.branch("nope") }
    }

    @Test
    fun deepLinearConversationDoesNotOverflowStack() {
        var next = 0
        var conversation = Conversation(
            emptyList(), null,
            idGenerator = { "d${next++}" },
            clock = FakeClock(),
        )
        repeat(20_000) { conversation = conversation.append(msg("m$it")) }

        var node = conversation.tree().single()
        var height = 1
        while (node.children.isNotEmpty()) {
            node = node.children.single()
            height++
        }
        assertEquals(20_000, height)
        assertEquals(conversation.leafId, node.entry.id)
    }

    private fun List<ai.koog.prompt.message.Message>.texts(): List<String> =
        map { (it as Message.User).textContent() }
}
