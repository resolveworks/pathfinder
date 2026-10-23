package works.resolve.pathfinder.codingagent.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.codingagent.core.compaction.CompactionPreparation
import works.resolve.pathfinder.codingagent.core.compaction.CompactionResult
import works.resolve.pathfinder.codingagent.core.compaction.DEFAULT_COMPACTION_SETTINGS
import works.resolve.pathfinder.codingagent.core.compaction.prepareCompaction

/** Port of pi's session-context-edit.test.ts cases for the persistent mechanism. */
class SessionContextEditTest {

    private val clock = FakeClock()
    private var sessionCounter = 0
    private var entryCounter = 0

    private fun assistant(text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "faux",
        provider = "faux",
        model = "faux",
        usage = Usage(
            input = 10,
            output = 1,
            totalTokens = 11,
            cost = Cost(0.0, 0.0, 0.0, 0.0, 0.0)
        ),
        stopReason = StopReason.STOP,
        timestamp = clock.now().toEpochMilliseconds()
    )

    private fun user(text: String) = UserMessage.ofText(text, clock.now().toEpochMilliseconds())

    private fun toolResult(text: String) = ToolResultMessage(
        toolCallId = "call-1",
        toolName = "read",
        content = listOf(TextContent(text)),
        isError = false,
        timestamp = clock.now().toEpochMilliseconds()
    )

    private suspend fun manager(dir: File): SessionManager = SessionManager.create(
        dir = dir,
        clock = clock,
        idFactory = { "sess-${sessionCounter++}" },
        ioDispatcher = Dispatchers.Unconfined,
        entryIdFactory = { "e${entryCounter++}" }
    )

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("session-context-edit-test").toFile()

    private fun text(message: Message): String = when (message) {
        is UserMessage -> message.content.joinToString("") { (it as TextContent).text }
        is AssistantMessage -> message.content.joinToString("") { (it as TextContent).text }
        is ToolResultMessage -> message.content.joinToString("") { (it as TextContent).text }
        else -> ""
    }

    @Test
    fun `omits a target only from model projection`() = runTest {
        val session = manager(createTempDirectory())
        session.appendMessage(user("request"))
        session.appendMessage(assistant("partial"))
        val assistantId = session.getLeafId()!!
        session.appendMessage(toolResult("raw output"))
        val resultId = session.getLeafId()!!
        session.appendContextEdit(assistantId, null)
        session.appendContextEdit(resultId, null)

        assertEquals(
            3,
            session.getBranch().count { it is MessageEntry }
        )
        assertEquals(
            listOf(works.resolve.pathfinder.ai.MessageRole.USER),
            session.buildSessionProjection().messages.map { it.role }
        )
        assertIs<AssistantMessage>(
            (session.getEntry(assistantId) as MessageEntry).message
        )
    }

    @Test
    fun `replaces only content and lets the latest edit win`() = runTest {
        val session = manager(createTempDirectory())
        session.appendMessage(assistant("original"))
        val targetId = session.getLeafId()!!
        session.appendContextEdit(
            targetId,
            ContextEditReplacement(ContextEditableContent.Blocks(listOf(TextContent("first"))))
        )
        session.appendContextEdit(targetId, null)
        session.appendContextEdit(
            targetId,
            ContextEditReplacement(ContextEditableContent.Blocks(listOf(TextContent("restored"))))
        )

        val projected = assertIs<AssistantMessage>(
            session.buildSessionProjection().messages.single()
        )
        assertEquals("restored", text(projected))
        assertEquals(11, projected.usage.totalTokens)
        assertEquals(
            "original",
            text((session.getEntry(targetId) as MessageEntry).message)
        )
    }

    @Test
    fun `normalizes string replacements for array-only assistant and tool-result roles`() =
        runTest {
            val session = manager(createTempDirectory())
            session.appendMessage(assistant("original"))
            val assistantId = session.getLeafId()!!
            session.appendMessage(toolResult("original result"))
            val resultId = session.getLeafId()!!
            val assistantEditId = session.appendContextEdit(
                assistantId,
                ContextEditReplacement(ContextEditableContent.Text("assistant replacement"))
            )
            val resultEditId = session.appendContextEdit(
                resultId,
                ContextEditReplacement(ContextEditableContent.Text("result replacement"))
            )

            val blocks = { text: String ->
                ContextEditReplacement(ContextEditableContent.Blocks(listOf(TextContent(text))))
            }
            assertEquals(
                blocks("assistant replacement"),
                (session.getEntry(assistantEditId) as ContextEditEntry).replacement
            )
            assertEquals(
                blocks("result replacement"),
                (session.getEntry(resultEditId) as ContextEditEntry).replacement
            )
            val projected = session.buildSessionProjection().messages
            assertEquals("assistant replacement", text(projected[0]))
            assertEquals("result replacement", text(projected[1]))
        }

    @Test
    fun `normalizes imported string replacements while projecting array-only roles`() {
        val target = MessageEntry("m1", null, 1L, assistant("original"))
        val edit = ContextEditEntry(
            id = "e1",
            parentId = target.id,
            timestamp = 2L,
            targetId = target.id,
            replacement = ContextEditReplacement(
                ContextEditableContent.Text("imported replacement")
            )
        )

        val projected = assertIs<AssistantMessage>(
            buildSessionProjection(listOf<SessionEntry>(target, edit), edit.id).messages.single()
        )
        assertEquals(listOf(TextContent("imported replacement")), projected.content)
    }

    @Test
    fun `keeps edits branch-relative`() = runTest {
        val session = manager(createTempDirectory())
        session.appendMessage(user("original"))
        val targetId = session.getLeafId()!!
        session.appendContextEdit(
            targetId,
            ContextEditReplacement(ContextEditableContent.Text("edited"))
        )
        assertEquals("edited", text(session.buildSessionProjection().messages.single()))

        session.branch(targetId)
        assertEquals("original", text(session.buildSessionProjection().messages.single()))
    }

    private fun preparationValue(
        result: CompactionResult<CompactionPreparation?>
    ): CompactionPreparation? = when (result) {
        is CompactionResult.Ok -> result.value
        is CompactionResult.Err -> error(result.error.message ?: "compaction failed")
    }

    private fun compactionSummaryText(message: Message): String? {
        val content = text(message)
        if (!content.startsWith(COMPACTION_SUMMARY_PREFIX)) return null
        return content.removePrefix(
            COMPACTION_SUMMARY_PREFIX
        ).removeSuffix(COMPACTION_SUMMARY_SUFFIX)
    }

    @Test
    fun `uses a self-referencing compaction to retain no preceding entries`() {
        val discarded = MessageEntry("u0", null, 1L, user("discarded"))
        val compaction = CompactionEntry(
            id = "c0",
            parentId = discarded.id,
            timestamp = 2L,
            summary = "exact handoff",
            firstKeptEntryId = "c0",
            tokensBefore = 100
        )
        val after = MessageEntry("u1", compaction.id, 3L, user("after"))

        val messages = buildSessionProjection(
            listOf<SessionEntry>(discarded, compaction, after),
            after.id
        ).messages

        assertEquals("c0", compaction.firstKeptEntryId)
        assertEquals(2, messages.size)
        assertEquals("exact handoff", compactionSummaryText(messages[0]))
        assertEquals("after", text(messages[1]))
    }

    @Test
    fun `applies post-compaction edits to retained pre-compaction entries`() = runTest {
        val session = manager(createTempDirectory())
        session.appendMessage(user("summarized"))
        session.appendMessage(user("original retained"))
        val retainedId = session.getLeafId()!!
        session.appendCompaction("summary", retainedId, 100, null)
        session.appendContextEdit(
            retainedId,
            ContextEditReplacement(ContextEditableContent.Text("edited retained"))
        )

        val messages = session.buildSessionProjection().messages
        assertEquals("summary", compactionSummaryText(messages[0]))
        assertEquals("edited retained", text(messages[1]))
    }

    @Test
    fun `uses only the newest summary when a repeated compaction retains entries before the older compaction`() =
        runTest {
            val session = manager(createTempDirectory())
            session.appendMessage(user("summarized first"))
            session.appendMessage(user("retained"))
            val retainedId = session.getLeafId()!!
            session.appendCompaction("first summary", retainedId, 100, null)
            session.appendMessage(assistant("after first compaction"))
            session.appendCompaction("second summary", retainedId, 80, null)
            session.appendMessage(user("new tail ".repeat(100)))

            val summaries = session.buildSessionProjection().messages
                .mapNotNull(::compactionSummaryText)
            assertEquals(listOf("second summary"), summaries)

            val preparation = preparationValue(
                prepareCompaction(
                    session.getBranch(),
                    DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 1)
                )
            )
            assertEquals("second summary", preparation?.previousSummary)
        }

    @Test
    fun `supports repeated retain-none compactions`() {
        val discarded = MessageEntry("u0", null, 1L, user("discarded"))
        val first = CompactionEntry(
            id = "c1",
            parentId = discarded.id,
            timestamp = 2L,
            summary = "first handoff",
            firstKeptEntryId = "c1",
            tokensBefore = 100
        )
        val alsoDiscarded = MessageEntry("u1", first.id, 3L, user("also discarded"))
        val second = CompactionEntry(
            id = "c2",
            parentId = alsoDiscarded.id,
            timestamp = 4L,
            summary = "second handoff",
            firstKeptEntryId = "c2",
            tokensBefore = 50
        )

        val summaries = buildSessionProjection(
            listOf<SessionEntry>(discarded, first, alsoDiscarded, second),
            second.id
        ).messages.mapNotNull(::compactionSummaryText)

        assertEquals("c2", second.firstKeptEntryId)
        assertEquals(listOf("second handoff"), summaries)
    }

    @Test
    fun `rejects an unknown target`() = runTest {
        val session = manager(createTempDirectory())
        val error = assertFailsWith<SessionError> {
            session.appendContextEdit("missing", null)
        }
        assertEquals(SessionErrorCode.NOT_FOUND, error.code)
        assertEquals("Entry missing not found", error.message)
    }

    @Test
    fun `rejects a target outside the active branch`() = runTest {
        val session = manager(createTempDirectory())
        session.appendMessage(user("root"))
        val rootId = session.getLeafId()!!
        session.appendMessage(assistant("abandoned"))
        val abandonedId = session.getLeafId()!!
        session.branch(rootId)
        session.appendMessage(user("active"))

        val error = assertFailsWith<SessionError> {
            session.appendContextEdit(abandonedId, null)
        }
        assertEquals(SessionErrorCode.INVALID_ENTRY, error.code)
        assertEquals("Entry $abandonedId is not on the active branch", error.message)
    }

    @Test
    fun `rejects an entry that contributes no editable content`() = runTest {
        val session = manager(createTempDirectory())
        session.appendMessage(
            SystemMessage(listOf(TextContent("prompt")), timestamp = 1L)
        )
        val systemId = session.getLeafId()!!
        val error = assertFailsWith<SessionError> {
            session.appendContextEdit(systemId, null)
        }
        assertEquals(SessionErrorCode.INVALID_ENTRY, error.code)
        assertEquals(
            "Entry $systemId does not contribute editable model content",
            error.message
        )
    }
}
