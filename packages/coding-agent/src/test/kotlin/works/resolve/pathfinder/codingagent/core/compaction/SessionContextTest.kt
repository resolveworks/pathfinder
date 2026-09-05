package works.resolve.pathfinder.codingagent.core.compaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.session.SessionEntry
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry

/**
 * Pins the entry-selection behavior of [buildSessionContext] against pi's
 * session-manager: the latest compaction wins, kept entries start at
 * `firstKeptEntryId`, only deferred assistants drop, config entries project
 * nothing, and the branch-state fold
 * ([Conversation.effectiveConfiguration]) sees the full pre-compaction path.
 */
class SessionContextTest {

    private var nextId = 0

    private fun createId(): String = "entry-${nextId++}"

    private val now: Long get() = nextId.toLong()

    private fun user(text: String) = UserMessage.ofText(text, timestamp = now)

    private fun assistant(text: String): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "claude-sonnet-4-5",
        usage = Usage(
            input = 1,
            output = 1,
            reasoning = 0,
            totalTokens = 2,
            cost = Cost(0.0, 0.0, 0.0, 0.0, 0.0)
        ),
        stopReason = StopReason.STOP,
        timestamp = now
    )

    private fun messageEntry(message: Message, parentId: String? = null) =
        MessageEntry(id = createId(), parentId = parentId, timestamp = now, message = message)

    private fun compactionEntry(summary: String, parentId: String?, firstKeptEntryId: String) =
        CompactionEntry(
            id = createId(),
            parentId = parentId,
            timestamp = now,
            summary = summary,
            firstKeptEntryId = firstKeptEntryId,
            tokensBefore = 100
        )

    private fun modelChangeEntry(parentId: String?, provider: String, modelId: String) =
        ModelChangeEntry(
            id = createId(),
            parentId = parentId,
            timestamp = now,
            provider = provider,
            modelId = modelId
        )

    private fun thinkingLevelEntry(parentId: String?, thinkingLevel: String) = ThinkingLevelEntry(
        id = createId(),
        parentId = parentId,
        timestamp = now,
        thinkingLevel = thinkingLevel
    )

    private fun textOf(message: Message): String = (message as UserMessage).let {
        (it.content[0] as TextContent).text
    }

    @Test
    fun `context starts at the latest of several compaction entries`() {
        val u1 = messageEntry(user("old"))
        val first = compactionEntry("first summary", u1.id, firstKeptEntryId = u1.id)
        val u2 = messageEntry(user("middle"), first.id)
        val latest = compactionEntry("latest summary", u2.id, firstKeptEntryId = u2.id)
        val u3 = messageEntry(user("tail"), latest.id)

        val messages = buildSessionContext(listOf<SessionEntry>(u1, first, u2, latest, u3))

        // The latest compaction's summary + the kept entries (from its
        // firstKeptEntryId through just before it) + everything after it;
        // the earlier compaction and its surroundings are already summarized.
        assertEquals(3, messages.size)
        assertEquals(
            COMPACTION_SUMMARY_PREFIX + "latest summary" + COMPACTION_SUMMARY_SUFFIX,
            textOf(messages[0])
        )
        assertEquals("middle", textOf(messages[1]))
        assertEquals("tail", textOf(messages[2]))

        assertEquals(latest, getLatestCompactionEntry(listOf(u1, first, u2, latest, u3)))
        assertNull(getLatestCompactionEntry(listOf(u1, u2)))
    }

    @Test
    fun `without compaction the whole path projects in order and config entries project nothing`() {
        val u1 = messageEntry(user("one"))
        val model = modelChangeEntry(u1.id, "openai", "gpt-5")
        val thinking = thinkingLevelEntry(model.id, "high")
        val u2 = messageEntry(user("two"), thinking.id)

        val messages = buildSessionContext(listOf<SessionEntry>(u1, model, thinking, u2))

        assertEquals(listOf("one", "two"), messages.map(::textOf))
    }

    @Test
    fun `error and aborted assistant messages stay in context at the pin`() {
        // Post-pin pi drops error/aborted assistants via isContextMessage; at
        // the pin — and here — only deferred assistants drop.
        val u = messageEntry(user("question"))
        val errored = messageEntry(
            assistant("boom").copy(stopReason = StopReason.ERROR, errorMessage = "overloaded"),
            u.id
        )
        val aborted =
            messageEntry(assistant("cancelled").copy(stopReason = StopReason.ABORTED), errored.id)

        val messages = buildSessionContext(listOf<SessionEntry>(u, errored, aborted))

        assertEquals(
            listOf("question", "boom", "cancelled"),
            messages.map {
                textOfUserOrAssistant(it)
            }
        )
    }

    @Test
    fun `kept entries pass through verbatim including failed assistants`() {
        val u1 = messageEntry(user("dropped"))
        val keptUser = messageEntry(user("kept"), u1.id)
        val keptFailed = messageEntry(
            assistant("failed").copy(stopReason = StopReason.ERROR, errorMessage = "overloaded"),
            keptUser.id
        )
        val compaction = compactionEntry("summary", keptFailed.id, firstKeptEntryId = keptUser.id)
        val after = messageEntry(user("after"), compaction.id)

        val messages = buildSessionContext(
            listOf<SessionEntry>(u1, keptUser, keptFailed, compaction, after)
        )

        assertEquals(4, messages.size)
        assertEquals(
            COMPACTION_SUMMARY_PREFIX + "summary" + COMPACTION_SUMMARY_SUFFIX,
            textOf(messages[0])
        )
        assertEquals(keptUser.message, messages[1])
        assertEquals(keptFailed.message, messages[2])
        assertEquals(after.message, messages[3])
    }

    @Test
    fun `effective configuration folds the full path while context starts at compaction`() {
        // pi's deriveSessionContextState(pathEntries) reads the ORIGINAL
        // path, not the post-compaction entries; pathfinder splits that fold
        // into Conversation.effectiveConfiguration over the same active path.
        val u1 = messageEntry(user("old"))
        val model = modelChangeEntry(u1.id, "openai", "gpt-5")
        val thinking = thinkingLevelEntry(model.id, "high")
        val kept = messageEntry(user("kept"), thinking.id)
        val compaction = compactionEntry("summary", kept.id, firstKeptEntryId = kept.id)

        val conversation =
            Conversation(listOf<SessionEntry>(u1, model, thinking, kept, compaction), compaction.id)

        val messages = buildSessionContext(conversation.activeEntries())
        assertEquals(
            listOf(COMPACTION_SUMMARY_PREFIX + "summary" + COMPACTION_SUMMARY_SUFFIX, "kept"),
            messages.map(::textOf)
        )

        val configuration = conversation.effectiveConfiguration()
        assertEquals(Conversation.SessionModelSelection("openai", "gpt-5"), configuration.model)
        assertEquals("high", configuration.thinkingLevel)
    }

    private fun textOfUserOrAssistant(message: Message): String = when (message) {
        is UserMessage -> (message.content[0] as TextContent).text
        is AssistantMessage -> (message.content[0] as TextContent).text
        else -> error("unexpected message in context")
    }
}
