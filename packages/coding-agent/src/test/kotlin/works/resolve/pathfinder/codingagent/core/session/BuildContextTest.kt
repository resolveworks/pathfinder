package works.resolve.pathfinder.codingagent.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.codingagent.core.compaction.buildContextEntries
import works.resolve.pathfinder.codingagent.core.compaction.buildSessionContext

/**
 * Port of pi's build-context.test.ts over the pure projection functions.
 * The Kotlin [buildSessionContext] returns only messages; the
 * thinkingLevel/model assertions use Conversation.effectiveConfiguration
 * over the same path, which folds the same fields pi returns alongside.
 * The custom-entry case is adapted without custom entries (not ported).
 */
class BuildContextTest {

    private fun msg(id: String, parentId: String?, role: String, text: String): MessageEntry {
        val message: Message = if (role == "user") {
            UserMessage.ofText(text, 1L)
        } else {
            AssistantMessage(
                content = listOf(TextContent(text)),
                api = "anthropic-messages",
                provider = "anthropic",
                model = "claude-test",
                usage = Usage(1, 1, totalTokens = 2, cost = Cost()),
                stopReason = StopReason.STOP,
                timestamp = 1L
            )
        }
        return MessageEntry(id, parentId, 1L, message)
    }

    private fun compaction(
        id: String,
        parentId: String?,
        summary: String,
        firstKeptEntryId: String
    ) = CompactionEntry(id, parentId, 1L, summary, firstKeptEntryId, 1000)

    private fun branchSummary(id: String, parentId: String?, summary: String, fromId: String) =
        BranchSummaryEntry(id, parentId, 1L, fromId, summary)

    private fun thinkingLevel(id: String, parentId: String?, level: String) =
        ThinkingLevelEntry(id, parentId, 1L, level)

    private fun modelChange(id: String, parentId: String?, provider: String, modelId: String) =
        ModelChangeEntry(id, parentId, 1L, provider, modelId)

    private fun context(
        entries: List<SessionEntry>,
        leafId: String? = entries.lastOrNull()?.id
    ): List<Message> = buildSessionContext(Conversation(entries, leafId).activeEntries())

    private fun settings(entries: List<SessionEntry>, leafId: String? = entries.lastOrNull()?.id) =
        Conversation(entries, leafId).effectiveConfiguration()

    private fun text(message: Message): String = when (message) {
        is UserMessage -> (message.content.single() as TextContent).text
        is AssistantMessage -> (message.content.single() as TextContent).text
        else -> ""
    }

    @Test
    fun `empty entries returns empty context`() {
        assertTrue(context(emptyList()).isEmpty())
        val configuration = settings(emptyList())
        assertEquals("off", configuration.thinkingLevel)
        assertNull(configuration.model)
    }

    @Test
    fun `single user message`() {
        val messages = context(listOf(msg("1", null, "user", "hello")))
        assertEquals(1, messages.size)
        assertIs<UserMessage>(messages[0])
    }

    @Test
    fun `simple conversation`() {
        val entries = listOf(
            msg("1", null, "user", "hello"),
            msg("2", "1", "assistant", "hi there"),
            msg("3", "2", "user", "how are you"),
            msg("4", "3", "assistant", "great")
        )
        val messages = context(entries, "4")
        assertEquals(4, messages.size)
        assertEquals(
            listOf(
                works.resolve.pathfinder.ai.MessageRole.USER,
                works.resolve.pathfinder.ai.MessageRole.ASSISTANT,
                works.resolve.pathfinder.ai.MessageRole.USER,
                works.resolve.pathfinder.ai.MessageRole.ASSISTANT
            ),
            messages.map { it.role }
        )
    }

    @Test
    fun `tracks thinking level changes`() {
        val entries = listOf(
            msg("1", null, "user", "hello"),
            thinkingLevel("2", "1", "high"),
            msg("3", "2", "assistant", "thinking hard")
        )
        assertEquals("high", settings(entries, "3").thinkingLevel)
        assertEquals(2, context(entries, "3").size)
    }

    @Test
    fun `tracks model from assistant message`() {
        val entries = listOf(
            msg("1", null, "user", "hello"),
            msg("2", "1", "assistant", "hi")
        )
        val model = settings(entries, "2").model!!
        assertEquals("anthropic", model.provider)
        assertEquals("claude-test", model.modelId)
    }

    @Test
    fun `tracks model from model change entry`() {
        val entries = listOf(
            msg("1", null, "user", "hello"),
            modelChange("2", "1", "openai", "gpt-4"),
            msg("3", "2", "assistant", "hi")
        )
        // Assistant message overwrites model change
        val model = settings(entries, "3").model!!
        assertEquals("anthropic", model.provider)
        assertEquals("claude-test", model.modelId)
    }

    @Test
    fun `includes summary before kept messages`() {
        val entries = listOf(
            msg("1", null, "user", "first"),
            msg("2", "1", "assistant", "response1"),
            msg("3", "2", "user", "second"),
            msg("4", "3", "assistant", "response2"),
            compaction("5", "4", "Summary of first two turns", "3"),
            msg("6", "5", "user", "third"),
            msg("7", "6", "assistant", "response3")
        )
        val messages = context(entries, "7")

        // summary + kept (3,4) + after (6,7) = 5 messages
        assertEquals(5, messages.size)
        assertTrue("Summary of first two turns" in text(messages[0]))
        assertEquals("second", text(messages[1]))
        assertEquals("response2", text(messages[2]))
        assertEquals("third", text(messages[3]))
        assertEquals("response3", text(messages[4]))
    }

    @Test
    fun `handles compaction keeping from first message`() {
        val entries = listOf(
            msg("1", null, "user", "first"),
            msg("2", "1", "assistant", "response"),
            compaction("3", "2", "Empty summary", "1"),
            msg("4", "3", "user", "second")
        )
        val messages = context(entries, "4")

        // summary + all messages (1,2,4)
        assertEquals(4, messages.size)
        assertTrue("Empty summary" in text(messages[0]))
    }

    @Test
    fun `multiple compactions uses latest`() {
        val entries = listOf(
            msg("1", null, "user", "a"),
            msg("2", "1", "assistant", "b"),
            compaction("3", "2", "First summary", "1"),
            msg("4", "3", "user", "c"),
            msg("5", "4", "assistant", "d"),
            compaction("6", "5", "Second summary", "4"),
            msg("7", "6", "user", "e")
        )
        val messages = context(entries, "7")

        // second summary, keep from 4
        assertEquals(4, messages.size)
        assertTrue("Second summary" in text(messages[0]))
    }

    @Test
    fun `buildContextEntries returns compaction-aware entries`() {
        // pi's case interleaves custom entries (not ported); the remaining
        // entry kinds keep the same compaction cut shape.
        val entries = listOf(
            msg("1", null, "user", "first"),
            modelChange("2", "1", "openai", "gpt-4"),
            msg("3", "2", "assistant", "response1"),
            msg("4", "3", "user", "second"),
            compaction("5", "4", "Summary", "4"),
            thinkingLevel("6", "5", "high"),
            msg("7", "6", "assistant", "response2")
        )

        val path = Conversation(entries, "7").activeEntries()
        assertEquals(
            listOf("5", "4", "6", "7"),
            buildContextEntries(path).map { it.id }
        )
        val messages = buildSessionContext(path)
        assertEquals(3, messages.size)
        assertTrue("Summary" in text(messages[0]))
        assertIs<UserMessage>(messages[1])
        assertIs<AssistantMessage>(messages[2])
    }

    @Test
    fun `keeps settings from the full path after compaction`() {
        val entries = listOf(
            msg("1", null, "user", "first"),
            thinkingLevel("2", "1", "high"),
            msg("3", "2", "assistant", "response1"),
            msg("4", "3", "user", "second"),
            compaction("5", "4", "Summary", "4")
        )

        val path = Conversation(entries, "5").activeEntries()
        assertEquals("high", Conversation(entries, "5").effectiveConfiguration().thinkingLevel)
        val messages = buildSessionContext(path)
        assertEquals(2, messages.size)
        assertTrue("Summary" in text(messages[0]))
        assertEquals("second", text(messages[1]))
    }

    @Test
    fun `follows path to specified leaf`() {
        // Tree:
        //   1 -> 2 -> 3 (branch A)
        //         \-> 4 (branch B)
        val entries = listOf(
            msg("1", null, "user", "start"),
            msg("2", "1", "assistant", "response"),
            msg("3", "2", "user", "branch A"),
            msg("4", "2", "user", "branch B")
        )

        val contextA = context(entries, "3")
        assertEquals(3, contextA.size)
        assertEquals("branch A", text(contextA[2]))

        val contextB = context(entries, "4")
        assertEquals(3, contextB.size)
        assertEquals("branch B", text(contextB[2]))
    }

    @Test
    fun `includes branch summary in path`() {
        val entries = listOf(
            msg("1", null, "user", "start"),
            msg("2", "1", "assistant", "response"),
            msg("3", "2", "user", "abandoned path"),
            branchSummary("4", "2", "Summary of abandoned work", "3"),
            msg("5", "4", "user", "new direction")
        )
        val messages = context(entries, "5")

        assertEquals(4, messages.size)
        assertTrue("Summary of abandoned work" in text(messages[2]))
        assertEquals("new direction", text(messages[3]))
    }

    @Test
    fun `complex tree with multiple branches and compaction`() {
        val entries = listOf(
            msg("1", null, "user", "start"),
            msg("2", "1", "assistant", "r1"),
            msg("3", "2", "user", "q2"),
            msg("4", "3", "assistant", "r2"),
            compaction("5", "4", "Compacted history", "3"),
            msg("6", "5", "user", "q3"),
            msg("7", "6", "assistant", "r3"),
            // abandoned branch from 3
            msg("8", "3", "user", "wrong path"),
            msg("9", "8", "assistant", "wrong response"),
            // branch summary resuming from 3
            branchSummary("10", "3", "Tried wrong approach", "9"),
            msg("11", "10", "user", "better approach")
        )

        // Main path to 7: summary + kept(3,4) + after(6,7)
        val ctxMain = context(entries, "7")
        assertEquals(5, ctxMain.size)
        assertTrue("Compacted history" in text(ctxMain[0]))
        assertEquals("q2", text(ctxMain[1]))
        assertEquals("r2", text(ctxMain[2]))
        assertEquals("q3", text(ctxMain[3]))
        assertEquals("r3", text(ctxMain[4]))

        // Branch path to 11: 1,2,3 + branch_summary + 11
        val ctxBranch = context(entries, "11")
        assertEquals(5, ctxBranch.size)
        assertEquals("start", text(ctxBranch[0]))
        assertEquals("r1", text(ctxBranch[1]))
        assertEquals("q2", text(ctxBranch[2]))
        assertTrue("Tried wrong approach" in text(ctxBranch[3]))
        assertEquals("better approach", text(ctxBranch[4]))
    }

    @Test
    fun `handles orphaned entries gracefully`() {
        val entries = listOf(
            msg("1", null, "user", "hello"),
            msg("2", "missing", "assistant", "orphan") // parent doesn't exist
        )
        // Only the orphan: the parent chain is broken
        assertEquals(1, context(entries, "2").size)
    }
}
