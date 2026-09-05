package works.resolve.pathfinder.codingagent.core.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.codingagent.core.compaction.buildSessionContext

/**
 * Port of pi's tree-traversal.test.ts. The appendCustomEntry case and the
 * whole createBranchedSession section are skipped: custom entries and
 * forking are not ported. The usage-roundtrip case under createBranchedSession
 * is portable in spirit and lives here as a reload test.
 */
class TreeTraversalTest {

    private val clock = FakeClock()
    private var sessionCounter = 0
    private var entryCounter = 0

    private fun user(text: String) = UserMessage.ofText(text, clock.now().toEpochMilliseconds())

    private fun assistant(text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "claude-test",
        usage = Usage(1, 1, totalTokens = 2, cost = Cost()),
        stopReason = StopReason.STOP,
        timestamp = clock.now().toEpochMilliseconds()
    )

    private val usage = Usage(
        input = 10,
        output = 20,
        cacheRead = 30,
        cacheWrite = 40,
        totalTokens = 100,
        cost = Cost(0.1, 0.2, 0.3, 0.4, 1.0)
    )

    private suspend fun manager(dir: File): SessionManager = SessionManager.create(
        dir = dir,
        clock = clock,
        idFactory = { "sess-${sessionCounter++}" },
        ioDispatcher = Dispatchers.Unconfined,
        entryIdFactory = { "e${entryCounter++}" }
    )

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("tree-traversal-test").toFile()

    @Test
    fun `appendMessage creates entry with correct parentId chain`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("first"))
        val id1 = m.leafId!!
        m.appendMessage(assistant("second"))
        val id2 = m.leafId!!
        m.appendMessage(user("third"))
        val id3 = m.leafId!!

        val entries = m.entries
        assertEquals(3, entries.size)

        val e1 = assertIs<MessageEntry>(entries[0])
        assertEquals(id1, e1.id)
        assertNull(e1.parentId)

        assertEquals(id2, entries[1].id)
        assertEquals(id1, entries[1].parentId)

        assertEquals(id3, entries[2].id)
        assertEquals(id2, entries[2].parentId)
    }

    @Test
    fun `appendThinkingLevelChange integrates into tree`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("hello"))
        val msgId = m.leafId!!
        m.appendThinkingLevelChange("high")
        val thinkingId = m.leafId!!
        m.appendMessage(assistant("response"))

        val entries = m.entries
        assertEquals(3, entries.size)

        val thinking = assertIs<ThinkingLevelEntry>(m.conversation.entry(thinkingId))
        assertEquals("high", thinking.thinkingLevel)
        assertEquals(msgId, thinking.parentId)
        assertEquals(thinkingId, entries[2].parentId)
    }

    @Test
    fun `appendModelChange integrates into tree`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("hello"))
        val msgId = m.leafId!!
        m.appendModelChange("openai", "gpt-4")
        val modelChangeId = m.leafId!!
        m.appendMessage(assistant("response"))

        val modelChange = assertIs<ModelChangeEntry>(m.conversation.entry(modelChangeId))
        assertEquals("openai", modelChange.provider)
        assertEquals("gpt-4", modelChange.modelId)
        assertEquals(msgId, modelChange.parentId)
        assertEquals(modelChangeId, m.entries[2].parentId)
    }

    @Test
    fun `appendCompaction integrates into tree`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("1"))
        val id1 = m.leafId!!
        m.appendMessage(assistant("2"))
        val id2 = m.leafId!!
        m.appendCompaction("summary", id1, 1000, null, usage)
        val compactionId = m.leafId!!
        m.appendMessage(user("3"))

        val compaction = assertIs<CompactionEntry>(m.conversation.entry(compactionId))
        assertEquals(id2, compaction.parentId)
        assertEquals("summary", compaction.summary)
        assertEquals(id1, compaction.firstKeptEntryId)
        assertEquals(1000, compaction.tokensBefore)
        assertEquals(usage, compaction.usage)
        assertEquals(compactionId, m.entries[3].parentId)
    }

    @Test
    fun `leaf pointer advances after each append`() = runTest {
        val m = manager(createTempDirectory())

        assertNull(m.leafId)

        m.appendMessage(user("1"))
        val id1 = m.leafId!!
        assertEquals(id1, m.leafId)

        m.appendMessage(assistant("2"))
        val id2 = m.leafId!!
        assertEquals(id2, m.leafId)

        m.appendThinkingLevelChange("high")
        val id3 = m.leafId!!
        assertEquals(id3, m.leafId)
    }

    @Test
    fun `activeEntries returns empty list for empty session`() = runTest {
        val m = manager(createTempDirectory())
        assertTrue(m.conversation.activeEntries().isEmpty())
    }

    @Test
    fun `activeEntries returns single entry path`() = runTest {
        val m = manager(createTempDirectory())
        m.appendMessage(user("hello"))
        val id = m.leafId!!

        val path = m.conversation.activeEntries()
        assertEquals(1, path.size)
        assertEquals(id, path[0].id)
    }

    @Test
    fun `activeEntries returns full path from root to leaf`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("1"))
        val id1 = m.leafId!!
        m.appendMessage(assistant("2"))
        val id2 = m.leafId!!
        m.appendThinkingLevelChange("high")
        val id3 = m.leafId!!
        m.appendMessage(user("3"))
        val id4 = m.leafId!!

        assertEquals(
            listOf(id1, id2, id3, id4),
            m.conversation.activeEntries().map { it.id }
        )
    }

    @Test
    fun `path from a specified leaf to root uses the entries list`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("1"))
        val id1 = m.leafId!!
        m.appendMessage(assistant("2"))
        val id2 = m.leafId!!
        m.appendMessage(user("3"))
        m.appendMessage(assistant("4"))

        // pi's getBranch(entryId): the Kotlin projection is Conversation
        // over the same entries with an explicit leaf.
        val path = Conversation(m.entries, id2).activeEntries()
        assertEquals(listOf(id1, id2), path.map { it.id })
    }

    @Test
    fun `tree returns empty list for empty session`() = runTest {
        val m = manager(createTempDirectory())
        assertTrue(m.conversation.tree().isEmpty())
    }

    @Test
    fun `tree returns single root for linear session`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("1"))
        val id1 = m.leafId!!
        m.appendMessage(assistant("2"))
        val id2 = m.leafId!!
        m.appendMessage(user("3"))
        val id3 = m.leafId!!

        val tree = m.conversation.tree()
        assertEquals(1, tree.size)

        val root = tree[0]
        assertEquals(id1, root.entry.id)
        assertEquals(1, root.children.size)
        assertEquals(id2, root.children[0].entry.id)
        assertEquals(1, root.children[0].children.size)
        assertEquals(id3, root.children[0].children[0].entry.id)
        assertTrue(root.children[0].children[0].children.isEmpty())
    }

    @Test
    fun `tree returns branches after branch`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("1"))
        m.appendMessage(assistant("2"))
        val id2 = m.leafId!!
        m.appendMessage(user("3"))
        val id3 = m.leafId!!

        m.branch(id2)
        m.appendMessage(user("4-branch"))
        val id4 = m.leafId!!

        val tree = m.conversation.tree()
        assertEquals(1, tree.size)

        val node2 = tree[0].children.single()
        assertEquals(id2, node2.entry.id)
        assertEquals(2, node2.children.size)
        assertEquals(listOf(id3, id4), node2.children.map { it.entry.id }.sorted())
    }

    @Test
    fun `tree handles multiple branches at same point`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("root"))
        m.appendMessage(assistant("response"))
        val id2 = m.leafId!!

        m.branch(id2)
        m.appendMessage(user("branch-A"))
        val idA = m.leafId!!
        m.branch(id2)
        m.appendMessage(user("branch-B"))
        val idB = m.leafId!!
        m.branch(id2)
        m.appendMessage(user("branch-C"))
        val idC = m.leafId!!

        val node2 = m.conversation.tree()[0].children.single()
        assertEquals(id2, node2.entry.id)
        assertEquals(3, node2.children.size)
        assertEquals(listOf(idA, idB, idC), node2.children.map { it.entry.id }.sorted())
    }

    @Test
    fun `tree handles deep branching`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("1"))
        m.appendMessage(assistant("2"))
        val id2 = m.leafId!!
        m.appendMessage(user("3"))
        val id3 = m.leafId!!
        m.appendMessage(assistant("4"))

        m.branch(id2)
        m.appendMessage(user("5"))
        val id5 = m.leafId!!
        m.appendMessage(assistant("6"))

        m.branch(id5)
        m.appendMessage(user("7"))

        val tree = m.conversation.tree()

        val node2 = tree[0].children.single()
        assertEquals(2, node2.children.size)

        val node5 = node2.children.first { it.entry.id == id5 }
        assertEquals(2, node5.children.size)

        val node3 = node2.children.first { it.entry.id == id3 }
        assertEquals(1, node3.children.size)
    }

    @Test
    fun `branch moves leaf pointer to specified entry`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("1"))
        val id1 = m.leafId!!
        m.appendMessage(assistant("2"))
        m.appendMessage(user("3"))
        val id3 = m.leafId!!

        assertEquals(id3, m.leafId)
        m.branch(id1)
        assertEquals(id1, m.leafId)
    }

    @Test
    fun `branch throws for non-existent entry`() = runTest {
        val m = manager(createTempDirectory())
        m.appendMessage(user("hello"))

        assertFailsWith<SessionError> { m.branch("nonexistent") }.let {
            assertEquals(SessionErrorCode.NOT_FOUND, it.code)
            assertEquals("Entry nonexistent not found", it.message)
        }
    }

    @Test
    fun `new appends become children of branch point`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("1"))
        val id1 = m.leafId!!
        m.appendMessage(assistant("2"))

        m.branch(id1)
        m.appendMessage(user("branched"))
        val id3 = m.leafId!!

        assertEquals(id1, m.conversation.entry(id3)!!.parentId)
    }

    @Test
    fun `branchWithSummary inserts summary with source and destination and advances leaf`() =
        runTest {
            val m = manager(createTempDirectory())

            m.appendMessage(user("1"))
            val id1 = m.leafId!!
            m.appendMessage(assistant("2"))
            m.appendMessage(user("3"))
            val id3 = m.leafId!!

            val summaryId = m.branchWithSummary(id1, "Summary of abandoned work", null, usage)

            assertEquals(summaryId, m.leafId)

            val summary = assertIs<BranchSummaryEntry>(m.conversation.entry(summaryId))
            assertEquals(id1, summary.parentId)
            assertEquals(id3, summary.fromId)
            assertEquals("Summary of abandoned work", summary.summary)
            assertEquals(usage, summary.usage)
        }

    @Test
    fun `branchWithSummary throws for non-existent entry`() = runTest {
        val m = manager(createTempDirectory())
        m.appendMessage(user("hello"))

        assertFailsWith<SessionError> { m.branchWithSummary("nonexistent", "summary", null, null) }
            .let {
                assertEquals(SessionErrorCode.NOT_FOUND, it.code)
                assertEquals("Entry nonexistent not found", it.message)
            }
    }

    @Test
    fun `leaf entry is null for empty session and the current leaf otherwise`() = runTest {
        val m = manager(createTempDirectory())
        assertNull(m.leafId?.let { m.conversation.entry(it) })

        m.appendMessage(user("1"))
        m.appendMessage(assistant("2"))
        val id2 = m.leafId!!

        assertEquals(id2, m.conversation.entry(m.leafId!!)!!.id)
    }

    @Test
    fun `entry lookup returns null for non-existent id and the entry otherwise`() = runTest {
        val m = manager(createTempDirectory())
        assertNull(m.conversation.entry("nonexistent"))

        m.appendMessage(user("first"))
        val id1 = m.leafId!!
        m.appendMessage(assistant("second"))
        val id2 = m.leafId!!

        val entry1 = assertIs<MessageEntry>(m.conversation.entry(id1))
        assertEquals(
            "first",
            (entry1.message as UserMessage).content.single().let {
                it as TextContent
            }.text
        )

        val entry2 = assertIs<MessageEntry>(m.conversation.entry(id2))
        val assistant = assertIs<AssistantMessage>(entry2.message)
        assertEquals("second", (assistant.content.single() as TextContent).text)
    }

    @Test
    fun `buildSessionContext returns messages from current branch only`() = runTest {
        val m = manager(createTempDirectory())

        m.appendMessage(user("msg1"))
        m.appendMessage(assistant("msg2"))
        val id2 = m.leafId!!
        m.appendMessage(user("msg3"))

        m.branch(id2)
        m.appendMessage(assistant("msg4-branch"))

        val messages = buildSessionContext(m.conversation.activeEntries())
        assertEquals(3, messages.size)

        assertEquals(
            "msg1",
            (messages[0] as UserMessage).content.single().let {
                it as TextContent
            }.text
        )
        assertEquals(
            "msg2",
            (assertIs<AssistantMessage>(messages[1]).content.single() as TextContent).text
        )
        assertEquals(
            "msg4-branch",
            (assertIs<AssistantMessage>(messages[2]).content.single() as TextContent).text
        )
    }

    @Test
    fun `preserves tool and summary usage across a file-backed reload`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)

        m.appendMessage(user("question"))
        val rootId = m.leafId!!
        m.appendMessage(assistant("answer"))
        m.appendMessage(
            ToolResultMessage(
                toolCallId = "call-1",
                toolName = "nested-model",
                content = listOf(TextContent("result")),
                usage = usage,
                isError = false,
                timestamp = clock.now().toEpochMilliseconds()
            )
        )
        m.appendCompaction("summary", rootId, 100, null, usage)
        m.branchWithSummary(rootId, "branch summary", null, usage)

        val file = dir.listFiles { f: File -> f.name.endsWith(".jsonl") }!!.single()
        val reopened = SessionManager.open(file, clock, ioDispatcher = Dispatchers.Unconfined)

        val compaction = assertIs<CompactionEntry>(reopened.entries.first { it is CompactionEntry })
        assertEquals(usage, compaction.usage)
        val branchSummary =
            assertIs<BranchSummaryEntry>(reopened.entries.first { it is BranchSummaryEntry })
        assertEquals(usage, branchSummary.usage)
        val toolResult = reopened.entries
            .filterIsInstance<MessageEntry>()
            .map { it.message }
            .filterIsInstance<ToolResultMessage>()
            .single()
        assertEquals(usage, toolResult.usage)
    }
}
