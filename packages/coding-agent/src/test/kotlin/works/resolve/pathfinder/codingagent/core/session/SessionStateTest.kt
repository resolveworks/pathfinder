package works.resolve.pathfinder.codingagent.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.resolve.pathfinder.ai.UserMessage

class SessionStateTest {

    private fun entry(seq: Long, id: String, parentId: String? = null) = MessageEntry(
        id = id,
        seq = seq,
        parentId = parentId,
        timestamp = seq,
        message = UserMessage.ofText(id, seq)
    )

    @Test
    fun `seq must start at one and stay consecutive`() {
        val state = SessionState()
        assertFailsWith<SessionError> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(0, "a")))
        }
        assertFailsWith<SessionError> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(2, "a")))
        }
        state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(1, "a")))
        assertEquals(2L, state.nextSequence)
        assertFailsWith<SessionError> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(3, "b")))
        }
        assertEquals(listOf("a"), state.entries().map { it.id })
    }

    @Test
    fun `duplicate entry ids rejected`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(1, "a")))
        assertFailsWith<SessionError> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(2, "a")))
        }
    }

    @Test
    fun `dangling parent rejected`() {
        val state = SessionState()
        assertFailsWith<SessionError> {
            state.applyMutation(
                SessionMutation.Entry(lane = null, entry = entry(1, "a", parentId = "ghost"))
            )
        }
    }

    @Test
    fun `lane-addressed entry must chain to the lane leaf`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = "main", entry = entry(1, "a")))
        state.applyMutation(
            SessionMutation.Entry(lane = "main", entry = entry(2, "b", parentId = "a"))
        )
        assertFailsWith<SessionError> {
            state.applyMutation(
                SessionMutation.Entry(lane = "main", entry = entry(3, "c", parentId = "a"))
            )
        }
        // Non-lane-addressed entries skip the chaining check.
        state.applyMutation(
            SessionMutation.Entry(lane = null, entry = entry(3, "c", parentId = "a"))
        )
    }

    @Test
    fun `entry on missing lane rejected`() {
        val state = SessionState()
        assertFailsWith<SessionError> {
            state.applyMutation(SessionMutation.Entry(lane = "other", entry = entry(1, "a")))
        }
    }

    @Test
    fun `lane mutation moves and creates pointers`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = "main", entry = entry(1, "a")))
        state.applyMutation(SessionMutation.Lane(seq = 2, lane = "main", leafId = "a"))
        assertEquals("a", state.requireLane("main"))
        assertFailsWith<SessionError> {
            state.applyMutation(SessionMutation.Lane(seq = 3, lane = "main", leafId = "ghost"))
        }
        state.applyMutation(SessionMutation.Lane(seq = 3, lane = "side", leafId = null))
        assertEquals(listOf(LanePointer("main", "a"), LanePointer("side", null)), state.getLanes())
    }

    @Test
    fun `name fact is latest wins and label facts validate targets`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Fact.Name(seq = 1, name = "first"))
        state.applyMutation(SessionMutation.Fact.Name(seq = 2, name = "second"))
        assertEquals("second", state.name)
        state.applyMutation(SessionMutation.Fact.Name(seq = 3, name = null))
        assertNull(state.name)

        // The rejected label does not consume its seq.
        assertFailsWith<SessionError> {
            state.applyMutation(
                SessionMutation.Fact.Label(seq = 4, targetId = "ghost", label = "l")
            )
        }
        state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(4, "a")))
        state.applyMutation(SessionMutation.Fact.Label(seq = 5, targetId = "a", label = "l"))
        assertEquals("l", state.label("a"))
        state.applyMutation(SessionMutation.Fact.Label(seq = 6, targetId = "a", label = null))
        assertNull(state.label("a"))
    }

    @Test
    fun `record on missing lane and duplicate record id rejected`() {
        val state = SessionState()
        val empty = kotlinx.serialization.json.JsonObject(mapOf())
        assertFailsWith<SessionError> {
            state.applyMutation(
                SessionMutation.Record(
                    LaneRecord.DeferredRecord("r0", "ghost", 1, 1L, "usage", empty)
                )
            )
        }
        state.applyMutation(
            SessionMutation.Record(LaneRecord.DeferredRecord("r1", "main", 1, 1L, "usage", empty))
        )
        assertFailsWith<SessionError> {
            state.applyMutation(
                SessionMutation.Record(
                    LaneRecord.DeferredRecord("r1", "main", 2, 2L, "usage", empty)
                )
            )
        }
        assertEquals(1, state.records().size)
    }

    @Test
    fun `message count tracks message entries only`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(1, "a")))
        state.applyMutation(
            SessionMutation.Entry(
                lane = null,
                entry = ModelChangeEntry(
                    id = "m",
                    seq = 2,
                    parentId = "a",
                    timestamp = 2,
                    provider = "p",
                    modelId = "m1"
                )
            )
        )
        assertEquals(1, state.messageCount())
        assertTrue(state.entry("m") is ModelChangeEntry)
    }

    @Test
    fun `getLog returns items since a seq with a limit`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = "main", entry = entry(1, "a")))
        state.applyMutation(SessionMutation.Lane(seq = 2, lane = "main", leafId = "a"))
        state.applyMutation(SessionMutation.Fact.Name(seq = 3, name = "renamed"))
        state.applyMutation(SessionMutation.Fact.Label(seq = 4, targetId = "a", label = "l"))
        state.applyMutation(
            SessionMutation.Entry(lane = "main", entry = entry(5, "b", parentId = "a"))
        )

        val full = state.getLog()
        assertEquals(
            listOf(
                LogItem.Entry::class,
                LogItem.Lane::class,
                LogItem.FactName::class,
                LogItem.FactLabel::class,
                LogItem.Entry::class
            ),
            full.map { it::class }
        )
        assertEquals(1L..5L, full.map { it.seq }.let { it.first()..it.last() })
        assertEquals(3, state.getLog(afterSeq = 2).size)
        assertEquals(listOf(1L, 2L, 3L), state.getLog(limit = 3).map { it.seq })
        assertEquals(listOf(4L, 5L), state.getLog(afterSeq = 3, limit = 5).map { it.seq })

        assertFailsWith<SessionError> { state.getLog(limit = 0) }
        assertFailsWith<SessionError> { state.getLog(afterSeq = -1) }
    }

    @Test
    fun `errors carry pi's typed codes`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = "main", entry = entry(1, "a")))
        assertEquals(
            SessionErrorCode.INVALID_LANE,
            assertFailsWith<SessionError> {
                state.requireLane("ghost")
            }.code
        )
        assertEquals(
            SessionErrorCode.ALREADY_EXISTS,
            assertFailsWith<SessionError> {
                state.validateUnusedId("a")
            }.code
        )
        assertEquals(
            SessionErrorCode.NOT_FOUND,
            assertFailsWith<SessionError> {
                state.validateTarget("ghost")
            }.code
        )
        assertEquals(
            SessionErrorCode.INVALID_QUERY,
            assertFailsWith<SessionError> {
                state.findEntries(EntryQuery(limit = 0))
            }.code
        )
        assertEquals(
            SessionErrorCode.INVALID_FORK_TARGET,
            assertFailsWith<SessionError> {
                // A non-message entry cannot be a branch fork target.
                state.applyMutation(
                    SessionMutation.Entry(
                        lane = "main",
                        entry = works.resolve.pathfinder.codingagent.core.session.CompactionEntry(
                            id = "c",
                            seq = 2,
                            parentId = "a",
                            timestamp = 2,
                            summary = "s",
                            retainedTail = emptyList(),
                            tokensBefore = 0
                        )
                    )
                )
                state.createForkMutations(ForkOptions.Branch(entryId = "c"))
            }.code
        )
    }
}
