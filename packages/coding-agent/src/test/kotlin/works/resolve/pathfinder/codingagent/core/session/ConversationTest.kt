package works.resolve.pathfinder.codingagent.core.session

import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock

class ConversationTest {

    private var nextId = 0

    private fun newConversation(): Conversation = Conversation(
        entries = emptyList(),
        leafId = null,
        idGenerator = { "e${nextId++}" },
        clock = FakeClock()
    )

    private fun msg(text: String) = UserMessage.ofText(text)

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

        val root = branchB.tree().single()
        assertEquals(base.leafId, root.entry.id)
        assertEquals(2, root.children.size)

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
    fun orphanBecomesRoot() {
        val orphan = MessageEntry("orphan", 0L, "missing", 3L, msg("x"))
        val root = MessageEntry("root", 0L, null, 1L, msg("y"))
        val c = Conversation(listOf(orphan, root), "orphan")

        assertEquals(listOf(root, orphan), c.tree().map { it.entry })
    }

    @Test
    fun treeChildrenSortedOldestFirst() {
        val root = MessageEntry("root", 0L, null, 0L, msg("r"))
        val young = MessageEntry("young", 0L, "root", 30L, msg("y"))
        val old = MessageEntry("old", 0L, "root", 10L, msg("o"))
        val middle = MessageEntry("middle", 0L, "root", 20L, msg("m"))
        val c = Conversation(listOf(young, middle, root, old), "young")

        assertEquals(
            listOf("old", "middle", "young"),
            c.tree().single().children.map {
                it.entry.id
            }
        )
    }

    @Test
    fun selfParentTreatedAsRoot() {
        val weird = MessageEntry("weird", 0L, "weird", 1L, msg("w"))
        val c = Conversation(listOf(weird), "weird")
        assertEquals(listOf(weird), c.tree().map { it.entry })
    }

    @Test
    fun entryLookupAndUnknownBranchRejected() {
        val c = newConversation().append(msg("a"))
        assertEquals("e0", c.entry("e0")!!.id)
        assertNull(c.entry("nope"))
        assertFailsWith<IllegalArgumentException> { c.branch("nope") }
    }

    @Test
    fun fromMessagesChainsEntries() {
        val c = Conversation.fromMessages(listOf(msg("a"), msg("b")))
        assertNull(c.entries.first().parentId)
        assertEquals(c.entries[0].id, c.entries[1].parentId)
        assertEquals(c.entries.last().id, c.leafId)
        assertEquals(listOf("a", "b"), c.activeMessages().texts())

        assertEquals(0, Conversation.fromMessages(emptyList()).entries.size)
        assertNull(Conversation.fromMessages(emptyList()).leafId)
    }

    @Test
    fun deepLinearConversationDoesNotOverflowStack() {
        var next = 0
        var conversation = Conversation(
            emptyList(),
            null,
            idGenerator = { "d${next++}" },
            clock = FakeClock()
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

    @Test
    fun appendModelAndThinkingLevelChangesAdvanceTheLeaf() {
        val c = newConversation()
            .append(msg("a"))
            .appendModelChange(provider = "zai", modelId = "glm-4.7")
            .appendThinkingLevelChange("high")

        assertEquals(listOf("e0", "e1", "e2"), c.entries.map { it.id })
        val modelChange = c.entries[1] as ModelChangeEntry
        assertEquals("zai" to "glm-4.7", modelChange.provider to modelChange.modelId)
        assertEquals("e0", modelChange.parentId)
        val thinking = c.entries[2] as ThinkingLevelEntry
        assertEquals("high", thinking.thinkingLevel)
        assertEquals("e2", c.leafId)
    }

    @Test
    fun effectiveConfigurationFoldsRootToLeaf() {
        val c = newConversation()
        assertEquals(Conversation.EffectiveConfiguration(), c.effectiveConfiguration())

        val assistant = works.resolve.pathfinder.ai.AssistantMessage(
            content = emptyList(),
            api = "openai-completions",
            provider = "zai",
            model = "glm-4.6",
            usage = works.resolve.pathfinder.ai.Usage(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                works.resolve.pathfinder.ai.Cost(0.0, 0.0, 0.0, 0.0, 0.0)
            ),
            stopReason = works.resolve.pathfinder.ai.StopReason.STOP,
            timestamp = 0L
        )

        var conversation = c
            .appendModelChange("zai", "glm-4.7")
            .append(msg("hello"))
            .appendModelChange("zai", "glm-5.3")
            .appendThinkingLevelChange("high")
        conversation =
            Conversation(conversation.entries, conversation.leafId, { "assistant" }, FakeClock())
                .append(assistant)

        val folded = conversation.effectiveConfiguration()
        assertEquals("zai" to "glm-4.6", folded.model!!.provider to folded.model!!.modelId)
        assertEquals("high", folded.thinkingLevel)
        assertNull(folded.activeToolNames)

        // Assistant messages carry the model that actually ran, so they win
        // over an earlier model_change; a later model_change wins back.
        val afterSwitch = conversation.appendModelChange("zai", "glm-4.7")
        assertEquals("glm-4.7", afterSwitch.effectiveConfiguration().model!!.modelId)

        val withTools = afterSwitch.appendActiveTools()
        assertEquals(listOf("read"), withTools.effectiveConfiguration().activeToolNames)
        val rewound = withTools.branch(afterSwitch.leafId!!)
        assertNull(rewound.effectiveConfiguration().activeToolNames)
        assertEquals("glm-4.7", rewound.effectiveConfiguration().model!!.modelId)
    }

    private fun Conversation.appendActiveTools(): Conversation {
        val entry = ActiveToolsEntry(
            id = "tools-${entries.size}",
            parentId = leafId,
            timestamp = entries.size.toLong(),
            activeToolNames = listOf("read")
        )
        return Conversation(entries + entry, entry.id)
    }

    private fun List<works.resolve.pathfinder.ai.Message>.texts(): List<String> = map {
        (it as UserMessage).content.single().let {
            (it as works.resolve.pathfinder.ai.TextContent).text
        }
    }
}
