package works.resolve.pathfinder.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage

/** Pure-function tests over an entry list: pi's session-manager module fns. */
class SessionTreeTest {

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
    fun buildSessionPathWalksLeafToRoot() {
        val e0 = MessageEntry("e0", null, 0L, msg("a"))
        val e1 = MessageEntry("e1", "e0", 1L, msg("b"))
        val e2 = MessageEntry("e2", "e1", 2L, msg("c"))
        val entries = listOf(e0, e1, e2)

        assertEquals(listOf("a", "b"), buildSessionContext(entries, "e1").messages.texts())
        assertEquals(listOf("e0", "e1"), buildSessionPath(entries, "e1").map { it.id })
        assertEquals(
            listOf("a", "b", "c"),
            buildSessionContext(entries, "e2").messages.texts()
        )
        assertTrue(buildSessionPath(entries, null).isEmpty())
    }

    @Test
    fun buildSessionPathUnknownLeafFallsBackToLastEntry() {
        val e0 = MessageEntry("e0", null, 0L, msg("a"))
        val e1 = MessageEntry("e1", "e0", 1L, msg("b"))

        assertEquals(listOf("e0", "e1"), buildSessionPath(listOf(e0, e1), "missing").map { it.id })
    }

    @Test
    fun sessionContextSettingsFoldRootToLeaf() {
        assertEquals(
            SessionContextSettings(),
            getSessionContextSettings(emptyList())
        )

        val user = MessageEntry("u", "m1", 4L, msg("hello"))
        val entries = listOf(
            ModelChangeEntry("m1", null, 1L, provider = "zai", modelId = "glm-4.7"),
            user,
            ModelChangeEntry("m2", user.id, 3L, provider = "zai", modelId = "glm-5.3"),
            ThinkingLevelEntry("t", "m2", 4L, thinkingLevel = "high"),
            MessageEntry("a", "t", 5L, assistant())
        )

        val folded = getSessionContextSettings(buildSessionPath(entries, "a"))
        // Assistant messages carry the model that actually ran, so they win
        // over an earlier model_change; a later model_change wins back.
        assertEquals("zai" to "glm-4.6", folded.model!!.provider to folded.model!!.modelId)
        assertEquals("high", folded.thinkingLevel)

        val afterSwitch = entries + ModelChangeEntry("m3", "a", 6L, "zai", "glm-4.7")
        assertEquals(
            "glm-4.7",
            getSessionContextSettings(buildSessionPath(afterSwitch, "m3")).model!!.modelId
        )
    }
}
