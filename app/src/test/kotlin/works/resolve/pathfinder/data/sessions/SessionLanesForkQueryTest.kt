package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.ai.core.UserMessage
import java.io.File
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLanesForkQueryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun msg(id: String, parentId: String? = null, seq: Long = 0L) =
        MessageEntry(id = id, seq = seq, parentId = parentId, timestamp = seq, message = UserMessage.ofText(id, seq))

    private fun compaction(id: String, parentId: String?, seq: Long = 0L) =
        CompactionEntry(
            id = id, seq = seq, parentId = parentId, timestamp = seq,
            summary = "s", retainedTail = emptyList(), tokensBefore = 1,
        )

    private fun apply(state: SessionState, mutation: SessionMutation) = state.applyMutation(mutation)

    @Test
    fun `non-main lanes chain and replay`() {
        val state = SessionState()
        apply(state, SessionMutation.Entry("main", msg("a", null, seq = 1)))
        apply(state, SessionMutation.Entry("main", msg("b", "a", seq = 2)))
        apply(state, SessionMutation.Lane(3, "side", "a"))
        apply(state, SessionMutation.Entry("side", msg("c", "a", seq = 4)))
        // Chaining is enforced per lane.
        assertEquals("b", state.requireLane("main"))
        assertEquals("c", state.requireLane("side"))
        assertFailsWith<SessionError> {
            apply(state, SessionMutation.Entry("side", msg("x", "b", seq = 5)))
        }
        assertFailsWith<SessionError> { state.validateNewLane("side") }
        state.validateNewLane("other")
    }

    @Test
    fun `findEntries filters orders limits and cursors`() {
        val state = SessionState()
        apply(state, SessionMutation.Entry(null, msg("a", null, seq = 1)))
        apply(state, SessionMutation.Entry(null, compaction("k", "a", seq = 2)))
        apply(state, SessionMutation.Entry(null, msg("b", "k", seq = 3)))

        assertEquals(listOf("b", "k", "a"), state.findEntries().map { it.id })
        assertEquals(listOf("a", "k", "b"), state.findEntries(EntryQuery(order = EntryOrder.OLDEST_FIRST)).map { it.id })
        assertEquals(listOf("k"), state.findEntries(EntryQuery(type = EntryType.COMPACTION)).map { it.id })
        assertEquals(listOf("b", "a"), state.findEntries(EntryQuery(type = EntryType.MESSAGE)).map { it.id })
        assertEquals(listOf("b"), state.findEntries(EntryQuery(limit = 1)).map { it.id })
        // Cursor: exclusive seq bound, direction-aware.
        assertEquals(
            listOf("k", "a"),
            state.findEntries(EntryQuery(cursor = EntryCursor(afterSeq = 3))).map { it.id },
        )
        assertEquals(
            listOf("b"),
            state.findEntries(
                EntryQuery(order = EntryOrder.OLDEST_FIRST, cursor = EntryCursor(afterSeq = 2)),
            ).map { it.id },
        )
        assertFailsWith<SessionError> { state.findEntries(EntryQuery(limit = 0)) }
        assertFailsWith<SessionError> { state.findEntries(EntryQuery(cursor = EntryCursor(afterSeq = -1))) }
    }

    @Test
    fun `findEntriesOnBranch walks bounds and cursors`() {
        val state = SessionState()
        apply(state, SessionMutation.Entry(null, msg("a", null, seq = 1)))
        apply(state, SessionMutation.Entry(null, msg("b", "a", seq = 2)))
        apply(state, SessionMutation.Entry(null, compaction("k", "b", seq = 3)))
        apply(state, SessionMutation.Entry(null, msg("c", "k", seq = 4)))

        // NewestFirst walks leaf→root; oldestFirst reverses.
        assertEquals(listOf("c", "k", "b", "a"), state.findEntriesOnBranch(BranchEntryQuery(start = "c")).map { it.id })
        assertEquals(
            listOf("a", "b", "k", "c"),
            state.findEntriesOnBranch(BranchEntryQuery(start = "c", order = EntryOrder.OLDEST_FIRST)).map { it.id },
        )
        // stopAtId/stopAtType are inclusive.
        assertEquals(
            listOf("c", "k", "b"),
            state.findEntriesOnBranch(BranchEntryQuery(start = "c", stopAtId = "b")).map { it.id },
        )
        assertEquals(
            listOf("a", "b", "k"),
            state.findEntriesOnBranch(BranchEntryQuery(start = "c", order = EntryOrder.OLDEST_FIRST, stopAtType = EntryType.COMPACTION))
                .map { it.id },
        )
        // Filters apply to the walk's output; they do not bound the walk.
        assertEquals(
            listOf("c", "b", "a"),
            state.findEntriesOnBranch(BranchEntryQuery(start = "c", type = EntryType.MESSAGE)).map { it.id },
        )
        assertEquals(
            listOf("a", "b", "c"),
            state.findEntriesOnBranch(BranchEntryQuery(start = "c", order = EntryOrder.OLDEST_FIRST, type = EntryType.MESSAGE)).map { it.id },
        )
        assertEquals(
            listOf("k", "b", "a"),
            state.findEntriesOnBranch(BranchEntryQuery(start = "c", cursor = EntryCursor(afterSeq = 4))).map { it.id },
        )
        assertFailsWith<SessionError> { state.findEntriesOnBranch(BranchEntryQuery(start = "ghost")) }
    }

    @Test
    fun `findRecords filters by lane type runId and seq`() {
        val state = SessionState()
        state.validateNewLane("side")
        apply(state, SessionMutation.Entry("main", msg("a", null, seq = 1)))
        val started = LaneRecord.OperationStartedRecord(id = "run-1", lane = "main", intent = OperationIntent.run())
        apply(state, SessionMutation.Record(started.withAssigned(seq = 2, timestamp = 2)))
        val finished = LaneRecord.OperationFinishedRecord(
            id = "f-1", lane = "main", runId = "run-1", outcome = OperationOutcome.COMPLETED,
        )
        apply(state, SessionMutation.Record(finished.withAssigned(seq = 3, timestamp = 3)))
        val usage = LaneRecord.UsageRecord(
            id = "u-1", lane = "side",
            usage = works.resolve.pathfinder.ai.core.Usage(),
            fields = kotlinx.serialization.json.buildJsonObject { },
        )
        apply(state, SessionMutation.Lane(4, "side", null))
        apply(state, SessionMutation.Record(usage.withAssigned(seq = 5, timestamp = 5)))

        assertEquals(listOf("u-1", "f-1", "run-1"), state.findRecords().map { it.id })
        assertEquals(listOf("run-1"), state.findRecords(RecordQuery(type = RecordType.OPERATION_STARTED)).map { it.id })
        // runId matches the started record's id and the finished record's runId.
        assertEquals(
            listOf("f-1", "run-1"),
            state.findRecords(RecordQuery(runId = "run-1")).map { it.id },
        )
        assertEquals(listOf("u-1"), state.findRecords(RecordQuery(lane = "side")).map { it.id })
        assertEquals(
            listOf("run-1"),
            state.findRecords(RecordQuery(type = RecordType.OPERATION_STARTED, operationKind = OperationIntent.Kind.RUN)).map { it.id },
        )
        assertEquals(
            listOf("run-1", "f-1", "u-1"),
            state.findRecords(RecordQuery(order = EntryOrder.OLDEST_FIRST)).map { it.id },
        )
        assertEquals(listOf("u-1"), state.findRecords(RecordQuery(afterSeq = 3)).map { it.id })
        assertEquals(listOf("u-1"), state.findRecords(RecordQuery(limit = 1)).map { it.id })
    }

    @Test
    fun `createForkMutations branch scope at target re-seqs from one`() {
        val state = SessionState()
        apply(state, SessionMutation.Entry("main", msg("a", null, seq = 1)))
        apply(state, SessionMutation.Entry("main", msg("b", "a", seq = 2)))
        apply(state, SessionMutation.Entry("main", msg("c", "b", seq = 3)))
        apply(state, SessionMutation.Fact.Name(4, "source"))
        apply(state, SessionMutation.Fact.Label(5, "a", "start"))
        apply(state, SessionMutation.Fact.Label(6, "c", "end"))

        val mutations = state.createForkMutations(ForkOptions.Branch(entryId = "b", position = ForkOptions.Branch.Position.AT))
        val entryMutations = mutations.filterIsInstance<SessionMutation.Entry>()
        assertEquals(listOf("a" to 1L, "b" to 2L), entryMutations.map { it.entry.id to it.entry.seq })
        assertEquals(null, entryMutations[0].entry.parentId)
        // Fork entry mutations are not lane-addressed (pi emits bare entry mutations).
        assertTrue(entryMutations.all { it.lane == null })
        val lane = mutations.filterIsInstance<SessionMutation.Lane>().single()
        assertEquals(LanePointer("main", "b"), LanePointer(lane.lane, lane.leafId))
        assertEquals("source", (mutations.filterIsInstance<SessionMutation.Fact.Name>().single()).name)
        val labels = mutations.filterIsInstance<SessionMutation.Fact.Label>()
        assertEquals(listOf("a"), labels.map { it.targetId })
        assertEquals((1L..mutations.size).toList(), mutations.map { seqOf(it) })
    }

    @Test
    fun `createForkMutations branch defaults and before position`() {
        val state = SessionState()
        apply(state, SessionMutation.Entry("main", msg("a", null, seq = 1)))
        apply(state, SessionMutation.Entry("main", msg("b", "a", seq = 2)))

        val at = state.createForkMutations(ForkOptions.Branch())
        assertEquals(listOf("a", "b"), at.filterIsInstance<SessionMutation.Entry>().map { it.entry.id })
        val before = state.createForkMutations(ForkOptions.Branch(entryId = "b", position = ForkOptions.Branch.Position.BEFORE))
        assertEquals(listOf("a"), before.filterIsInstance<SessionMutation.Entry>().map { it.entry.id })
        assertEquals("a", before.filterIsInstance<SessionMutation.Lane>().single().leafId)
        val empty = SessionState().createForkMutations(ForkOptions.Branch())
        assertTrue(empty.filterIsInstance<SessionMutation.Entry>().isEmpty())
        assertNull(empty.filterIsInstance<SessionMutation.Lane>().single().leafId)
    }

    @Test
    fun `createForkMutations rejects non-message targets with invalid fork target`() {
        val state = SessionState()
        apply(state, SessionMutation.Entry("main", msg("a", null, seq = 1)))
        apply(state, SessionMutation.Entry("main", compaction("k", "a", seq = 2)))
        val e = assertFailsWith<SessionError> {
            state.createForkMutations(ForkOptions.Branch(entryId = "k"))
        }
        assertEquals("Fork target is not a message entry: k", e.message)
        // A missing entry id is the same failure.
        assertFailsWith<SessionError> {
            state.createForkMutations(ForkOptions.Branch(entryId = "ghost"))
        }
    }

    @Test
    fun `createForkMutations tree scope copies entries and all lanes`() {
        val state = SessionState()
        apply(state, SessionMutation.Entry("main", msg("a", null, seq = 1)))
        apply(state, SessionMutation.Entry("main", msg("b", "a", seq = 2)))
        apply(state, SessionMutation.Lane(3, "side", "a"))
        val mutations = state.createForkMutations(ForkOptions.Tree)
        assertEquals(listOf("a", "b"), mutations.filterIsInstance<SessionMutation.Entry>().map { it.entry.id })
        assertEquals(
            listOf(LanePointer("main", "b"), LanePointer("side", "a")),
            mutations.filterIsInstance<SessionMutation.Lane>().map { LanePointer(it.lane, it.leafId) },
        )
    }

    private fun seqOf(mutation: SessionMutation): Long = when (mutation) {
        is SessionMutation.Entry -> mutation.entry.seq
        is SessionMutation.Record -> mutation.record.seq
        is SessionMutation.Lane -> mutation.seq
        is SessionMutation.Fact -> mutation.seq
    }

    private fun newStore(root: File, startId: Int = 0): Pair<SessionStore, () -> String> {
        var next = startId
        return SessionStore(root = root, idFactory = { "sess-fork-${next++}" }) to { "sess-fork-$next" }
    }

    @Test
    fun `fork persists lineage and replays through a fresh store`() = runTest {
        val root = tmpFolder.newFolder("sessions")
        val (store, _) = newStore(root)
        val source = store.create("source")
        val saved = store.save(source.withMessages(listOf(UserMessage.ofText("hi", 1L))))

        val forked = store.fork(saved.id, ForkOptions.Branch())
        assertEquals(saved.id, store.parentSessionId(forked.id))
        assertEquals("source", forked.title)
        assertEquals(saved.messages.size, forked.messages.size)
        // The fork is a distinct log file.
        val fresh = SessionStore(root = root, idFactory = { "fresh" })
        val reloaded = fresh.load(forked.id)!!
        assertEquals(forked.messages, reloaded.messages)
        assertNull(fresh.parentSessionId(saved.id))

        assertFailsWith<SessionError> {
            store.fork(saved.id, ForkOptions.Tree, id = forked.id)
        }
        assertFailsWith<SessionError> { store.fork("missing", ForkOptions.Tree) }
    }

    @Test
    fun `fork of an empty source produces a valid empty session`() = runTest {
        val root = tmpFolder.newFolder("sessions")
        val (store, _) = newStore(root)
        val source = store.create("empty")
        val forked = store.fork(source.id, ForkOptions.Tree)
        assertTrue(forked.entries.isEmpty())
        assertNull(forked.leafId)
        assertEquals(source.id, store.parentSessionId(forked.id))
    }

    @Test
    fun `lanes persist and views project lane-scoped trees`() = runTest {
        val root = tmpFolder.newFolder("sessions")
        val (store, _) = newStore(root)
        val created = store.create("lanes")
        val withMessage = store.save(created.withMessages(listOf(UserMessage.ofText("hello", 1L))))
        val aId = withMessage.entries.single().id

        // Side lane anchored at the first entry; the view scopes appends to it.
        store.createLane(withMessage.id, "side", aId)
        val side = store.view(withMessage.id, "side")
        assertEquals(aId, side.leafId())
        val appended = side.appendMessage(UserMessage.ofText("side note", 2L))
        val sideTexts = side
            .findEntriesOnBranch(EntryQuery(type = EntryType.MESSAGE, order = EntryOrder.OLDEST_FIRST))
            .filterIsInstance<MessageEntry>()
            .map { entry -> (entry.message as works.resolve.pathfinder.ai.core.UserMessage).content }
            .map { contents -> contents.filterIsInstance<works.resolve.pathfinder.ai.core.TextContent>().joinToString { it.text } }
        assertEquals(listOf("hello", "side note"), sideTexts)
        // The main lane is untouched by the side append.
        val main = store.load(withMessage.id)!!
        assertEquals(aId, main.leafId)
        assertEquals(1, main.messages.size)

        val fresh = SessionStore(root = root, idFactory = { "fresh" })
        val reloaded = fresh.load(withMessage.id)!!
        assertEquals(aId, reloaded.leafId)
        val sideAfterRestart = fresh.view(withMessage.id, "side")
        assertEquals(appended, sideAfterRestart.leafId())
        assertEquals(2, sideAfterRestart.findEntriesOnBranch().size)

        assertFailsWith<SessionError> { store.view(withMessage.id, "ghost") }
    }

    @Test
    fun `findRecords at the store enforces the operationKind contract`() = runTest {
        val root = tmpFolder.newFolder("sessions")
        val (store, _) = newStore(root)
        val session = store.create("records")
        store.appendRecord(
            session.id,
            LaneRecord.OperationStartedRecord(id = "run-1", lane = "main", intent = OperationIntent.run()),
        )
        assertEquals(
            listOf("run-1"),
            store.findRecords(session.id, RecordQuery(type = RecordType.OPERATION_STARTED, operationKind = OperationIntent.Kind.RUN))
                .map { it.id },
        )
        val e = assertFailsWith<SessionError> {
            store.findRecords(session.id, RecordQuery(operationKind = OperationIntent.Kind.RUN))
        }
        assertEquals("operationKind requires type \"operation_started\"", e.message)
    }
}
