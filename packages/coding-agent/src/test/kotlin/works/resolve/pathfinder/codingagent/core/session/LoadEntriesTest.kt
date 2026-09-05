package works.resolve.pathfinder.codingagent.core.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.codingagent.core.compaction.buildContextEntries

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

        assertEquals(source.entries.size, loaded.entries.size)
        assertContentEquals(source.entries.map { it.id }, loaded.entries.map { it.id })
        assertIs<ModelChangeEntry>(loaded.entries[1])
    }

    @Test
    fun `keeps the loaded leaf so appends continue the conversation`() = runTest {
        val dir = createTempDirectory()
        val (_, loaded) = loadedPair(dir) {
            it.appendMessage(user("hello"))
            it.appendMessage(user("again"))
        }
        val lastId = loaded.entries.last().id

        loaded.appendMessage(user("continued"))
        val appendedId = loaded.leafId!!

        assertEquals(appendedId, loaded.leafId)
        assertEquals(lastId, loaded.conversation.entry(appendedId)!!.parentId)
    }

    @Test
    fun `never mints an id that collides with a loaded entry`() = runTest {
        val dir = createTempDirectory()
        val source = manager(dir)
        repeat(50) { source.appendMessage(user("message $it")) }
        source.appendMessage(assistant("flush"))
        val loadedIds = source.entries.map { it.id }
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
        val appendedId = loaded.leafId!!
        assertFalse(loadedIds.contains(appendedId))
        assertEquals("fresh", appendedId)
    }

    @Test
    fun `rebuilds the branch structure rather than a flat chain`() = runTest {
        val dir = createTempDirectory()
        val (_, loaded) = loadedPair(dir) {
            it.appendMessage(user("hello"))
            val first = it.entries.last().id
            it.appendMessage(user("abandoned"))
            it.branch(first)
            it.appendMessage(user("kept"))
        }

        val roots = loaded.conversation.tree()
        assertEquals(1, roots.size)
        assertEquals(2, roots[0].children.size)
    }

    @Test
    fun `resolves a compaction against the entry it was written against`() = runTest {
        val dir = createTempDirectory()
        val (source, loaded) = loadedPair(dir) {
            it.appendMessage(user("dropped"))
            it.appendMessage(user("kept"))
            val keptId = it.entries.last().id
            it.appendMessage(assistant("answer"))
            it.appendCompaction("summary so far", keptId, 1000, null, null)
        }

        val keptId = source.entries[1].id
        val context = buildContextEntries(loaded.conversation.activeEntries())
        assertTrue(context.any { it.id == keptId })
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("load-entries-test").toFile()
}
