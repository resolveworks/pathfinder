package works.resolve.pathfinder.codingagent.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage

class ConversationTest {

    private fun msg(text: String) = UserMessage.ofText(text)

    private fun assistant(model: String = "glm-4.6") = AssistantMessage(
        content = emptyList(),
        api = "openai-completions",
        provider = "zai",
        model = model,
        usage = Usage(0, 0, 0, 0, 0, 0, 0, Cost(0.0, 0.0, 0.0, 0.0, 0.0)),
        stopReason = StopReason.STOP,
        timestamp = 0L
    )

    private fun List<Message>.texts(): List<String> = map {
        (it as UserMessage).content.single().let {
            (it as TextContent).text
        }
    }

    @Test
    fun activeEntriesWalksLeafToRoot() {
        val e0 = MessageEntry("e0", null, 0L, msg("a"))
        val e1 = MessageEntry("e1", "e0", 1L, msg("b"))
        val e2 = MessageEntry("e2", "e1", 2L, msg("c"))
        val c = Conversation(listOf(e0, e1, e2), "e1")

        assertEquals(listOf("a", "b"), c.activeMessages().texts())
        assertEquals(listOf("e0", "e1"), c.activeEntries().map { it.id })
        assertEquals(listOf("a", "b", "c"), Conversation(c.entries, "e2").activeMessages().texts())
        assertTrue(Conversation(c.entries, null).activeEntries().isEmpty())
    }

    @Test
    fun orphanBecomesRoot() {
        val orphan = MessageEntry("orphan", "missing", 3L, msg("x"))
        val root = MessageEntry("root", null, 1L, msg("y"))
        val c = Conversation(listOf(orphan, root), "orphan")

        assertEquals(listOf(root, orphan), c.tree().map { it.entry })
    }

    @Test
    fun treeChildrenSortedOldestFirst() {
        val root = MessageEntry("root", null, 0L, msg("r"))
        val young = MessageEntry("young", "root", 30L, msg("y"))
        val old = MessageEntry("old", "root", 10L, msg("o"))
        val middle = MessageEntry("middle", "root", 20L, msg("m"))
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
        val weird = MessageEntry("weird", "weird", 1L, msg("w"))
        val c = Conversation(listOf(weird), "weird")
        assertEquals(listOf(weird), c.tree().map { it.entry })
    }

    @Test
    fun entryLookup() {
        val e0 = MessageEntry("e0", null, 0L, msg("a"))
        val c = Conversation(listOf(e0), "e0")
        assertEquals("e0", c.entry("e0")!!.id)
        assertNull(c.entry("nope"))
    }

    @Test
    fun deepLinearConversationDoesNotOverflowStack() {
        val entries = ArrayList<SessionEntry>(20_000)
        var parent: String? = null
        repeat(20_000) { i ->
            val entry = MessageEntry("d$i", parent, i.toLong(), msg("m$i"))
            entries.add(entry)
            parent = entry.id
        }
        val conversation = Conversation(entries, parent)

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
    fun effectiveConfigurationFoldsRootToLeaf() {
        assertEquals(
            Conversation.EffectiveConfiguration(),
            Conversation(emptyList(), null).effectiveConfiguration()
        )

        val user = MessageEntry("u", "t", 4L, msg("hello"))
        val conversation = Conversation(
            listOf(
                ModelChangeEntry("m1", null, 1L, provider = "zai", modelId = "glm-4.7"),
                user,
                ModelChangeEntry("m2", user.id, 3L, provider = "zai", modelId = "glm-5.3"),
                ThinkingLevelEntry("t", "m2", 4L, thinkingLevel = "high"),
                MessageEntry("a", "t", 5L, assistant())
            ),
            "a"
        )

        val folded = conversation.effectiveConfiguration()
        // Assistant messages carry the model that actually ran, so they win
        // over an earlier model_change; a later model_change wins back.
        assertEquals("zai" to "glm-4.6", folded.model!!.provider to folded.model!!.modelId)
        assertEquals("high", folded.thinkingLevel)

        val afterSwitch = Conversation(
            conversation.entries + ModelChangeEntry("m3", "a", 6L, "zai", "glm-4.7"),
            "m3"
        )
        assertEquals("glm-4.7", afterSwitch.effectiveConfiguration().model!!.modelId)
    }
}
