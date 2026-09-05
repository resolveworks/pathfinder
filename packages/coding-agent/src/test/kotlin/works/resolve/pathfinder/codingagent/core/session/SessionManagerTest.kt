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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock

class SessionManagerTest {

    private val clock = FakeClock()
    private var sessionCounter = 0
    private var entryCounter = 0

    private fun user(text: String) = UserMessage.ofText(text, clock.now().toEpochMilliseconds())

    private fun assistant(stopReason: StopReason = StopReason.STOP, text: String = "hi") =
        AssistantMessage(
            content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
            api = "openai-completions",
            provider = "zai",
            model = "glm-4.6",
            usage = Usage(0, 0, 0, 0, 0, 0, 0, Cost(0.0, 0.0, 0.0, 0.0, 0.0)),
            stopReason = stopReason,
            timestamp = clock.now().toEpochMilliseconds()
        )

    private fun testDispatcher() = Dispatchers.Unconfined

    private suspend fun manager(dir: File): SessionManager = SessionManager.create(
        dir = dir,
        clock = clock,
        idFactory = { "sess-${sessionCounter++}" },
        ioDispatcher = testDispatcher(),
        entryIdFactory = { "e${entryCounter++}" }
    )

    private fun jsonlFiles(dir: File): List<File> =
        dir.listFiles { f: File -> f.name.endsWith(".jsonl") }!!.toList()

    @Test
    fun `new session buffers until the first assistant message`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)

        m.appendModelChange("zai", "glm-4.6")
        m.appendThinkingLevelChange("off")
        m.appendMessage(user("hello"))

        assertTrue(jsonlFiles(dir).isEmpty())
        assertEquals(3, m.entries.size)
        assertEquals("e2", m.leafId)
        assertEquals("e2", m.conversation.activeEntries().last().id)
    }

    @Test
    fun `first assistant commit writes the full buffered prefix exclusively`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)

        m.appendModelChange("zai", "glm-4.6")
        m.appendMessage(user("hello"))
        clock.advanceMillis(10)
        m.appendMessage(assistant())

        val file = jsonlFiles(dir).single()
        val lines = file.readText().trimEnd().split("\n")
        assertEquals(4, lines.size)
        assertIs<JsonlCodec.Line.Header>(JsonlCodec.parseLine(lines[0]))
        assertEquals(
            listOf("e0", "e1", "e2"),
            lines.drop(1).map {
                assertIs<JsonlCodec.Line.Entry>(JsonlCodec.parseLine(it)).entry.id
            }
        )

        // Appends after the flush are single appended lines.
        clock.advanceMillis(10)
        m.appendMessage(user("again"))
        assertEquals(5, file.readText().trimEnd().split("\n").size)
    }

    @Test
    fun `aborted empty assistant message commits a complete resumable file`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)

        m.appendModelChange("zai", "glm-4.6")
        m.appendMessage(user("hello"))
        clock.advanceMillis(10)
        // An instant abort is a committed empty-content assistant message.
        m.appendMessage(assistant(stopReason = StopReason.ABORTED, text = ""))

        val file = jsonlFiles(dir).single()
        val reopened = SessionManager.open(file, clock, ioDispatcher = testDispatcher())
        assertEquals(m.sessionId, reopened.sessionId)
        assertContentEquals(m.entries.map { it.id }, reopened.entries.map { it.id })
        assertEquals(m.leafId, reopened.leafId)

        clock.advanceMillis(10)
        reopened.appendMessage(user("continue"))
        assertEquals(5, file.readText().trimEnd().split("\n").size)
    }

    @Test
    fun `open repairs a torn tail`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)
        m.appendMessage(user("hello"))
        m.appendMessage(assistant())
        val file = jsonlFiles(dir).single()

        // Simulate a torn final append: last line without its newline.
        val text = file.readText()
        file.writeText(text.trimEnd())

        val reopened = SessionManager.open(file, clock, ioDispatcher = testDispatcher())
        assertEquals(2, reopened.entries.size)
        assertTrue(file.readText().endsWith("\n"))
    }

    @Test
    fun `open rejects non-session content and initializes empty files`() = runTest {
        val dir = createTempDirectory()
        val garbage = File(dir, "garbage.jsonl").apply { writeText("not a session\n") }
        assertFailsWith<SessionError> {
            SessionManager.open(garbage, clock, ioDispatcher = testDispatcher())
        }.let { assertEquals(SessionErrorCode.INVALID_ENTRY, it.code) }

        // A v4 header line is likewise unreadable.
        val v4 = File(dir, "v4.jsonl")
            .apply { writeText("""{"kind":"header","version":4,"id":"a","createdAt":0}""" + "\n") }
        assertFailsWith<SessionError> {
            SessionManager.open(v4, clock, ioDispatcher = testDispatcher())
        }

        val empty = File(dir, "empty.jsonl").apply { writeText("") }
        val initialized = SessionManager.open(empty, clock, ioDispatcher = testDispatcher())
        assertEquals(1, empty.readText().trimEnd().split("\n").size)
        assertTrue(initialized.entries.isEmpty())
    }

    @Test
    fun `branchWithSummary records the abandoned leaf and lands at the target`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)
        m.appendMessage(user("a"))
        m.appendMessage(assistant())
        val firstUserId = m.entries.first().id
        val oldLeaf = m.leafId

        val summaryId = m.branchWithSummary(
            firstUserId,
            summary = "went elsewhere",
            details = null,
            usage = null
        )

        val summary = assertIs<BranchSummaryEntry>(m.conversation.entry(summaryId))
        assertEquals(oldLeaf, summary.fromId)
        assertEquals(firstUserId, summary.parentId)
        assertEquals(summaryId, m.leafId)
        // The summary is persisted too.
        val file = jsonlFiles(dir).single()
        assertTrue("went elsewhere" in file.readText())

        assertFailsWith<SessionError> {
            m.branchWithSummary("nope", "s", null, null)
        }.let { assertEquals(SessionErrorCode.NOT_FOUND, it.code) }
    }

    @Test
    fun `branch and resetLeaf move the leaf`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)
        m.appendMessage(user("a"))
        m.appendMessage(assistant())
        val first = m.entries.first().id

        m.branch(first)
        assertEquals(first, m.leafId)
        clock.advanceMillis(10)
        m.appendMessage(user("a2"))
        assertEquals(
            listOf("a", "a2"),
            m.conversation.activeMessages().map {
                (it as UserMessage).content.single().let { c -> (c as TextContent).text }
            }
        )

        assertFailsWith<SessionError> { m.branch("nope") }
            .let { assertEquals(SessionErrorCode.NOT_FOUND, it.code) }

        m.resetLeaf()
        assertNull(m.leafId)
        assertTrue(m.conversation.activeEntries().isEmpty())
    }

    @Test
    fun `openById scans headers`() = runTest {
        val dir = createTempDirectory()
        val a = manager(dir)
        a.appendMessage(user("one"))
        a.appendMessage(assistant())
        val b = manager(dir)
        b.appendMessage(user("two"))
        b.appendMessage(assistant())

        val found = SessionManager.openById(
            dir,
            b.sessionId,
            clock,
            ioDispatcher = testDispatcher()
        )!!
        assertEquals(b.sessionId, found.sessionId)
        assertEquals(
            "two",
            (found.entries[0] as MessageEntry).let {
                (it.message as UserMessage).content.single().let { c -> (c as TextContent).text }
            }
        )
        assertNull(SessionManager.openById(dir, "missing", clock, ioDispatcher = testDispatcher()))
    }

    @Test
    fun `list derives modified from message timestamps and sorts descending`() = runTest {
        val dir = createTempDirectory()
        val older = manager(dir)
        older.appendMessage(user("old"))
        older.appendMessage(assistant())

        clock.advanceMillis(5_000)
        val newer = manager(dir)
        newer.appendMessage(user("new"))
        clock.advanceMillis(10)
        newer.appendMessage(assistant())

        val infos = SessionManager.list(dir, ioDispatcher = testDispatcher())
        assertEquals(listOf(newer.sessionId, older.sessionId), infos.map { it.id })
        val info = infos.first()
        assertEquals(2, info.messageCount)
        assertEquals("new", info.firstMessage)
        assertEquals("new hi", info.allMessagesText)
        assertEquals(newer.entries[1].timestamp, info.modified)
        assertTrue(info.createdAt <= info.modified)
    }

    @Test
    fun `header-only file lists with the no-messages sentinel`() = runTest {
        val dir = createTempDirectory()
        val file = File(dir, "empty.jsonl").apply { writeText("") }
        val m = SessionManager.open(file, clock, ioDispatcher = testDispatcher())

        val info = SessionManager.list(dir, ioDispatcher = testDispatcher()).single()
        assertEquals(m.sessionId, info.id)
        assertEquals("(no messages)", info.firstMessage)
        assertEquals(0, info.messageCount)
        assertEquals(info.createdAt, info.modified)
    }

    @Test
    fun `appends dispatch file io to the injected dispatcher`() = runTest {
        val io = DispatcherSpy()
        val dir = createTempDirectory()
        val m = SessionManager.create(dir, clock, ioDispatcher = io, entryIdFactory = { "e0" })

        m.appendMessage(user("hello"))
        clock.advanceMillis(10)
        m.appendMessage(assistant())

        assertTrue(io.dispatched)
        assertTrue(jsonlFiles(dir).isNotEmpty())
    }

    @Test
    fun `open on a missing file stays in memory until the first assistant`() = runTest {
        val dir = createTempDirectory()
        val file = File(dir, "explicit.jsonl")
        val m = SessionManager.open(file, clock, ioDispatcher = testDispatcher(), entryIdFactory = {
            "e0"
        })

        assertTrue(!file.exists())
        assertEquals(0, m.entries.size)

        m.appendMessage(user("hello"))
        clock.advanceMillis(10)
        m.appendMessage(assistant())

        // Written to the explicit path, header + both entries.
        assertEquals(3, file.readText().trimEnd().split("\n").size)
        val reopened = SessionManager.open(file, clock, ioDispatcher = testDispatcher())
        assertEquals(2, reopened.entries.size)
    }

    @Test
    fun `a file whose first valid line is not the header is rejected`() = runTest {
        val dir = createTempDirectory()
        val entryLine = JsonlCodec.encodeEntryLine(
            MessageEntry("m", null, 1L, user("x"))
        ).trimEnd()
        val headerLine = JsonlCodec.encodeHeaderLine(
            JsonlCodec.SessionHeader("sess-x", 0L)
        ).trimEnd()
        val bad = File(dir, "entry-first.jsonl")
            .apply { writeText(entryLine + "\n" + headerLine + "\n") }

        assertFailsWith<SessionError> {
            SessionManager.open(bad, clock, ioDispatcher = testDispatcher())
        }.let { assertEquals(SessionErrorCode.INVALID_ENTRY, it.code) }
        // Listing skips the invalid file rather than exposing it.
        assertTrue(SessionManager.list(dir, ioDispatcher = testDispatcher()).isEmpty())
    }

    private class DispatcherSpy : kotlinx.coroutines.CoroutineDispatcher() {
        var dispatched = false
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatched = true
            block.run()
        }
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("session-manager-test").toFile()
}
