package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.ai.core.Cost
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import java.io.File
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The lane-record surface of [SessionStore] (audit P0-3): appendRecord
 * persistence with storage-assigned seq/timestamp, the single-open-operation
 * invariant, recovery of open operations across reload (the limit-2
 * contract), the stats fold, and the entry/record ordering — a record may
 * precede, in seq order, the entry its sourceLeafId names.
 */
class SessionStoreRecordsTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val clock = FakeClock(1_000)
    private var nextId = 0
    private lateinit var root: File

    private fun newStore(): SessionStore {
        if (!::root.isInitialized) root = tmpFolder.newFolder("sessions")
        return SessionStore(root = root, clock = clock, idFactory = { "sess-${nextId++}" })
    }

    private fun userEntry(id: String, parentId: String?, text: String) =
        MessageEntry(id = id, parentId = parentId, timestamp = 1L, message = UserMessage(listOf(TextContent(text)), 1L))

    @Test
    fun `appendRecord assigns seq and timestamp and persists for replay`() = runTest {
        val store = newStore()
        val session = store.create("t")

        val started = store.appendRecord(
            session.id,
            LaneRecord.OperationStartedRecord(id = "op1", lane = "main", sourceLeafId = null, intent = OperationIntent.run()),
        )
        assertEquals(2L, started.seq) // header consumes nothing; name fact is seq 1
        assertTrue(started.timestamp >= 1_000)

        val aborted = store.appendRecord(
            session.id,
            LaneRecord.AbortRequestedRecord(id = "a1", lane = "main", runId = "op1"),
        )
        assertEquals(3L, aborted.seq)
        val finished = store.appendRecord(
            session.id,
            LaneRecord.OperationFinishedRecord(id = "f1", lane = "main", runId = "op1", outcome = OperationOutcome.ABORTED),
        )
        assertEquals(4L, finished.seq)

        // A fresh store (process death) replays the records; the lane is idle again.
        val reopened = newStore()
        assertEquals(emptyList<LaneRecord.OperationStartedRecord>(), reopened.openOperations(session.id, "main", null))
        assertEquals(0, reopened.load(session.id)!!.entries.size)
    }

    @Test
    fun `open operations survive reload - limit 2 recovery contract`() = runTest {
        val store = newStore()
        val session = store.create("t")

        // An interrupted run: operation_started with no finish. A fresh
        // store (process death) reads it back — the recovery contract.
        store.appendRecord(
            session.id,
            LaneRecord.OperationStartedRecord(id = "op1", lane = "main", sourceLeafId = null, intent = OperationIntent.run()),
        )
        val reopened = newStore()
        assertEquals(listOf("op1"), reopened.openOperations(session.id, "main", 2).map { it.id })

        // Finishing the operation clears it, durably.
        store.appendRecord(
            session.id,
            LaneRecord.OperationFinishedRecord(id = "f1", lane = "main", runId = "op1", outcome = OperationOutcome.COMPLETED),
        )
        assertEquals(emptyList<LaneRecord.OperationStartedRecord>(), newStore().openOperations(session.id, "main", 2))
    }

    @Test
    fun `a lane rejects a second open operation`() = runTest {
        val store = newStore()
        val session = store.create("t")
        store.appendRecord(
            session.id,
            LaneRecord.OperationStartedRecord(id = "op1", lane = "main", sourceLeafId = null, intent = OperationIntent.run()),
        )
        val error = assertFailsWith<SessionDataException> {
            store.appendRecord(
                session.id,
                LaneRecord.OperationStartedRecord(id = "op2", lane = "main", sourceLeafId = null, intent = OperationIntent.run()),
            )
        }
        assertTrue(error.message!!.contains("already has an open operation"))
        // The rejected start never persisted; the lane still holds exactly
        // the one open operation.
        assertEquals(1, store.openOperations(session.id, "main", 2).size)
    }

    @Test
    fun `record may precede the buffered entry its sourceLeafId names`() = runTest {
        val store = newStore()
        val session = store.create("t")

        // The conversation holds an entry that has not been saved yet; the
        // producer records operation_started naming it as sourceLeafId
        // immediately (pi's invariants permit forward references).
        val conversation = Conversation(listOf(userEntry("u1", null, "hi")), "u1")
        store.appendRecord(
            session.id,
            LaneRecord.OperationStartedRecord(id = "op1", lane = "main", sourceLeafId = "u1", intent = OperationIntent.run()),
        )
        // The buffered entry syncs afterwards and takes a later seq.
        val saved = store.save(session.copy(entries = conversation.entries, leafId = conversation.leafId))
        assertEquals(1, saved.entries.size)

        // Replay validates both orderings: the record's seq precedes the
        // entry's, and no validation rejects the forward reference.
        val reopened = newStore()
        val reloaded = reopened.load(session.id)!!
        assertEquals("u1", reloaded.leafId)
        val open = reopened.openOperations(session.id, "main", null).single()
        assertEquals("u1", open.sourceLeafId)
        // Idempotent re-save stays a no-op.
        store.save(session.copy(entries = conversation.entries, leafId = conversation.leafId, title = reloaded.title))
        assertEquals(1, newStore().load(session.id)!!.entries.size)
    }

    @Test
    fun `stats fold message entries and usage records across reload`() = runTest {
        val store = newStore()
        val session = store.create("t")
        val conversation = Conversation(listOf(userEntry("u1", null, "hi")), "u1")
        store.save(session.copy(entries = conversation.entries, leafId = conversation.leafId))

        val usage = Usage(
            input = 10, output = 5, cacheRead = 100, cacheWrite = 20, reasoning = 0, totalTokens = 135,
            cost = Cost(0.1, 0.2, 0.0, 0.0, 0.3),
        )
        store.appendRecord(
            session.id,
            LaneRecord.UsageRecord(
                id = "u1r", lane = "main", usage = usage,
                fields = kotlinx.serialization.json.JsonObject(mapOf("cause" to kotlinx.serialization.json.JsonPrimitive("assistant"))),
            ),
        )

        val reopened = newStore()
        val stats = reopened.stats(session.id)
        assertEquals(1, stats.messageCount)
        assertEquals(100L, stats.cachedTokens)
        assertEquals(30L, stats.uncachedTokens)
        assertEquals(135L, stats.totalTokens)
        assertEquals(0.3, stats.costTotal, 1e-9)
        assertNotNull(reopened.load(session.id))
    }
}
