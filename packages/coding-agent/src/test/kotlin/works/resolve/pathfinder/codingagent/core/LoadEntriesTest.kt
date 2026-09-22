package works.resolve.pathfinder.codingagent.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock

/**
 * Port of pi's load-entries.test.ts. That file targets the inMemory
 * constructor with preloaded entries, which is not ported; the equivalent
 * load path here is [SessionManager.open] on a persisted file, built by a
 * source manager. Cases about inMemory header options, header-among-entries
 * adoption, v2 migration, labels, and staying off the filesystem have no
 * counterpart and are skipped.
 */
class LoadEntriesTest {

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

    private suspend fun manager(dir: File): SessionManager = SessionManager.create(
        dir = dir,
        clock = clock,
        idFactory = { "sess-${sessionCounter++}" },
        ioDispatcher = Dispatchers.Unconfined,
        entryIdFactory = { "e${entryCounter++}" }
    )

    private fun jsonlFiles(dir: File): List<File> =
        dir.listFiles { f: File -> f.name.endsWith(".jsonl") }!!.toList()

    /** Build a persisted session, reopen it, and hand both to the check. */
    private suspend fun loadedPair(
        dir: File,
        build: suspend (SessionManager) -> Unit
    ): Pair<SessionManager, SessionManager> {
        val source = manager(dir)
        build(source)
        // Lazy persistence: force the file to exist before reopening.
        source.appendMessage(assistant("flush"))
        val file = jsonlFiles(dir).single()
        return source to SessionManager.open(
            file,
            clock,
            ioDispatcher = Dispatchers.Unconfined,
            entryIdFactory = { "z${entryCounter++}" }
        )
    }

    @Test
    fun `adopts entries verbatim`() = runTest {
        val dir = createTempDirectory()
        val (source, loaded) = loadedPair(dir) {
            it.appendMessage(user("hello"))
            it.appendModelChange("anthropic", "claude-opus-4-5")
            it.appendMessage(user("again"))
        }

        assertEquals(source.getEntries().size, loaded.getEntries().size)
        assertContentEquals(source.getEntries().map { it.id }, loaded.getEntries().map { it.id })
        assertIs<ModelChangeEntry>(loaded.getEntries()[1])
    }

    @Test
    fun `keeps the loaded leaf so appends continue the conversation`() = runTest {
        val dir = createTempDirectory()
        val (_, loaded) = loadedPair(dir) {
            it.appendMessage(user("hello"))
            it.appendMessage(user("again"))
        }
        val lastId = loaded.getEntries().last().id

        loaded.appendMessage(user("continued"))
        val appendedId = loaded.getLeafId()!!

        assertEquals(appendedId, loaded.getLeafId())
        assertEquals(lastId, loaded.getEntry(appendedId)!!.parentId)
    }

    @Test
    fun `never mints an id that collides with a loaded entry`() = runTest {
        val dir = createTempDirectory()
        val source = manager(dir)
        repeat(50) { source.appendMessage(user("message $it")) }
        source.appendMessage(assistant("flush"))
        val loadedIds = source.getEntries().map { it.id }
        val file = jsonlFiles(dir).single()

        // The factory offers colliding ids first, then a fresh one.
        val offers = ArrayDeque(loadedIds + "fresh")
        val loaded = SessionManager.open(
            file,
            clock,
            ioDispatcher = Dispatchers.Unconfined,
            entryIdFactory = { offers.removeFirst() }
        )

        loaded.appendMessage(user("continued"))
        val appendedId = loaded.getLeafId()!!
        assertFalse(loadedIds.contains(appendedId))
        assertEquals("fresh", appendedId)
    }

    @Test
    fun `rebuilds the branch structure rather than a flat chain`() = runTest {
        val dir = createTempDirectory()
        val (_, loaded) = loadedPair(dir) {
            it.appendMessage(user("hello"))
            val first = it.getEntries().last().id
            it.appendMessage(user("abandoned"))
            it.branch(first)
            it.appendMessage(user("kept"))
        }

        val roots = loaded.getTree()
        assertEquals(1, roots.size)
        assertEquals(2, roots[0].children.size)
    }

    @Test
    fun `resolves a compaction against the entry it was written against`() = runTest {
        val dir = createTempDirectory()
        val (source, loaded) = loadedPair(dir) {
            it.appendMessage(user("dropped"))
            it.appendMessage(user("kept"))
            val keptId = it.getEntries().last().id
            it.appendMessage(assistant("answer"))
            it.appendCompaction("summary so far", keptId, 1000, null, null)
        }

        val keptId = source.getEntries()[1].id
        val context = buildContextEntries(loaded.getEntries(), loaded.getLeafId())
        assertTrue(context.any { it.id == keptId })
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("load-entries-test").toFile()

    private fun piHeader() = """{"type":"session","version":3,"id":"pi-1",""" +
        """"timestamp":"2026-09-05T19:03:40.386Z","cwd":"/home/u/p"}"""

    private fun piUserLine() = """{"type":"message","id":"m1","parentId":null,""" +
        """"timestamp":"2026-09-05T19:03:41.000Z",""" +
        """"message":{"role":"user","timestamp":1788635021000,""" +
        """"content":[{"type":"text","text":"list the files"}]}}"""

    @Test
    fun `a pi-written tool-call turn loads`() = runTest {
        val file = File(createTempDirectory(), "pi.jsonl")
        file.writeText(
            piHeader() + "\n" + piUserLine() + "\n" +
                """{"type":"message","id":"m2","parentId":"m1",""" +
                """"timestamp":"2026-09-05T19:03:42.000Z",""" +
                """"message":{"role":"assistant","timestamp":1788635022000,""" +
                """"content":[{"type":"toolCall","id":"call_1","name":"bash",""" +
                """"arguments":{"command":"ls"}}],""" +
                """"api":"anthropic-messages","provider":"anthropic",""" +
                """"model":"claude-sonnet-4",""" +
                """"usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,""" +
                """"totalTokens":15,"cost":{"input":0,"output":0,"cacheRead":0,""" +
                """"cacheWrite":0,"total":0}},"stopReason":"toolUse"}}""" + "\n" +
                """{"type":"message","id":"m3","parentId":"m2",""" +
                """"timestamp":"2026-09-05T19:03:43.000Z",""" +
                """"message":{"role":"toolResult","timestamp":1788635023000,""" +
                """"toolCallId":"call_1","toolName":"bash",""" +
                """"content":[{"type":"text","text":"a.txt"}],"isError":false}}""" + "\n"
        )

        val loaded = SessionManager.open(file, clock, ioDispatcher = Dispatchers.Unconfined)

        assertEquals(listOf("m1", "m2", "m3"), loaded.getEntries().map { it.id })
        assertEquals("m3", loaded.getLeafId())
        val assistant =
            assertIs<AssistantMessage>((loaded.getEntry("m2") as MessageEntry).message)
        assertEquals(StopReason.TOOL_USE, assistant.stopReason)
        val call = assertIs<ToolCall>(assistant.content.single())
        assertEquals(buildJsonObject { put("command", "ls") }, call.arguments)
    }

    @Test
    fun `no-millis and offset timestamps load`() = runTest {
        val file = File(createTempDirectory(), "lenient.jsonl")
        file.writeText(
            """{"type":"session","version":3,"id":"s",""" +
                """"timestamp":"2026-09-05T19:03:40Z","cwd":""}""" + "\n" +
                """{"type":"message","id":"a","parentId":null,""" +
                """"timestamp":"2026-09-05T21:03:40+0200",""" +
                """"message":{"role":"user","timestamp":1788635020000,"content":[]}}""" + "\n" +
                """{"type":"message","id":"b","parentId":"a",""" +
                """"timestamp":"2026-09-05T19:03Z",""" +
                """"message":{"role":"assistant","timestamp":1788634980000,"content":[],""" +
                """"api":"anthropic-messages","provider":"anthropic","model":"claude",""" +
                """"usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,""" +
                """"totalTokens":2,"cost":{"input":0,"output":0,"cacheRead":0,""" +
                """"cacheWrite":0,"total":0}},"stopReason":"stop"}}""" + "\n"
        )

        val loaded = SessionManager.open(file, clock, ioDispatcher = Dispatchers.Unconfined)

        assertEquals(2, loaded.getEntries().size)
        // The colonless +0200 offset resolves to the same instant as
        // 19:03:40Z; the minute-only timestamp is a whole minute earlier.
        assertEquals(1788635020000L, loaded.getEntries()[0].timestamp)
        assertEquals(1788634980000L, loaded.getEntries()[1].timestamp)
    }

    @Test
    fun `unknown entry types are retained in the tree and roundtrip`() = runTest {
        val usageLine =
            """{"type":"usage","id":"u1","parentId":"m2",""" +
                """"timestamp":"2026-09-05T19:03:44.000Z","kind":"cache_warm",""" +
                """"provider":"anthropic","model":"claude",""" +
                """"usage":{"input":1},"note":"warmed"}"""
        val file = File(createTempDirectory(), "raw.jsonl")
        file.writeText(
            """{"type":"session","version":3,"id":"s",""" +
                """"timestamp":"2026-09-05T19:03:40.000Z","cwd":""}""" + "\n" +
                piUserLine() + "\n" +
                """{"type":"message","id":"m2","parentId":"m1",""" +
                """"timestamp":"2026-09-05T19:03:42.000Z",""" +
                """"message":{"role":"assistant","timestamp":1788635022000,""" +
                """"content":[{"type":"text","text":"hello"}],""" +
                """"api":"anthropic-messages","provider":"anthropic","model":"claude",""" +
                """"usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,""" +
                """"totalTokens":2,"cost":{"input":0,"output":0,"cacheRead":0,""" +
                """"cacheWrite":0,"total":0}},"stopReason":"stop"}}""" + "\n" +
                usageLine + "\n"
        )
        val loaded = SessionManager.open(file, clock, ioDispatcher = Dispatchers.Unconfined)

        assertEquals(listOf("m1", "m2", "u1"), loaded.getEntries().map { it.id })
        val raw = assertIs<RawEntry>(loaded.getEntry("u1"))
        assertEquals("m2", raw.parentId)
        // The raw entry claims the leaf — and the unknown-leaf fallback —
        // like pi's last raw entry.
        assertEquals("u1", loaded.getLeafId())
        assertEquals(
            listOf("m1", "m2", "u1"),
            buildSessionPath(loaded.getEntries(), "missing").map { it.id }
        )
        // It chains in the tree and projects nothing into the LLM context.
        assertEquals(
            listOf("u1"),
            loaded.getTree().single().children.single().children.map { it.entry.id }
        )
        assertEquals(2, loaded.buildSessionContext().messages.size)

        loaded.appendMessage(user("more"))
        val lines = file.readText().split('\n').filter { it.isNotEmpty() }
        assertEquals(5, lines.size)
        assertEquals(usageLine, lines[3])
    }
}
