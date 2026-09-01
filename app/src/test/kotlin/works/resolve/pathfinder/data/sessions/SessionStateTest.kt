package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.ai.core.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replay validation of SessionState, porting pi's SessionState.applyMutation
 * tests (packages/agent/src/harness/session/state.ts): consecutive seq,
 * duplicate ids, parent existence, lane chaining, lane/fact application.
 */
class SessionStateTest {

    private fun entry(seq: Long, id: String, parentId: String? = null) =
        MessageEntry(id = id, seq = seq, parentId = parentId, timestamp = seq, message = UserMessage.ofText(id, seq))

    @Test
    fun `seq must start at one and stay consecutive`() {
        val state = SessionState()
        // seq 0 (unassigned) is invalid on replay, as is skipping 1.
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(0, "a")))
        }
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(2, "a")))
        }
        state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(1, "a")))
        assertEquals(2L, state.nextSequence)
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(3, "b")))
        }
        assertEquals(listOf("a"), state.entries().map { it.id })
    }

    @Test
    fun `duplicate entry ids rejected`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(1, "a")))
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(2, "a")))
        }
    }

    @Test
    fun `dangling parent rejected`() {
        val state = SessionState()
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(1, "a", parentId = "ghost")))
        }
    }

    @Test
    fun `lane-addressed entry must chain to the lane leaf`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = "main", entry = entry(1, "a")))
        state.applyMutation(SessionMutation.Entry(lane = "main", entry = entry(2, "b", parentId = "a")))
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Entry(lane = "main", entry = entry(3, "c", parentId = "a")))
        }
        // Non-lane-addressed entries skip the chaining check (pi's lane === undefined).
        state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(3, "c", parentId = "a")))
    }

    @Test
    fun `entry on missing lane rejected`() {
        val state = SessionState()
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Entry(lane = "other", entry = entry(1, "a")))
        }
    }

    @Test
    fun `lane mutation moves and creates pointers`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = "main", entry = entry(1, "a")))
        state.applyMutation(SessionMutation.Lane(seq = 2, lane = "main", leafId = "a"))
        assertEquals("a", state.requireLane("main"))
        // Pointer to a missing entry is rejected.
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Lane(seq = 3, lane = "main", leafId = "ghost"))
        }
        // A new lane can be created at null (pi's createLane).
        state.applyMutation(SessionMutation.Lane(seq = 3, lane = "side", leafId = null))
        assertEquals(mapOf("main" to "a", "side" to null), state.lanes())
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
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Fact.Label(seq = 4, targetId = "ghost", label = "l"))
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
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Record(LaneRecord.DeferredRecord("r0", "ghost", 1, 1L, "usage", empty)))
        }
        state.applyMutation(SessionMutation.Record(LaneRecord.DeferredRecord("r1", "main", 1, 1L, "usage", empty)))
        assertFailsWith<SessionDataException> {
            state.applyMutation(SessionMutation.Record(LaneRecord.DeferredRecord("r1", "main", 2, 2L, "usage", empty)))
        }
        assertEquals(1, state.records().size)
    }

    @Test
    fun `message count tracks message entries only`() {
        val state = SessionState()
        state.applyMutation(SessionMutation.Entry(lane = null, entry = entry(1, "a")))
        state.applyMutation(
            SessionMutation.Entry(lane = null, entry = ModelChangeEntry(id = "m", seq = 2, parentId = "a", timestamp = 2, provider = "p", modelId = "m1")),
        )
        assertEquals(1, state.messageCount())
        assertTrue(state.entry("m") is ModelChangeEntry)
    }
}
