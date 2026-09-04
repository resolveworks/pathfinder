package works.resolve.pathfinder.codingagent.core.session

import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Usage

class SessionStateRecordsTest {

    private fun started(seq: Long, id: String, lane: String = "main") = SessionMutation.Record(
        LaneRecord.OperationStartedRecord(
            id = id,
            lane = lane,
            seq = seq,
            timestamp = seq,
            intent = OperationIntent.run()
        )
    )

    private fun finished(
        seq: Long,
        runId: String,
        outcome: OperationOutcome = OperationOutcome.COMPLETED
    ) = SessionMutation.Record(
        LaneRecord.OperationFinishedRecord(
            id = "f$seq",
            lane = "main",
            seq = seq,
            timestamp = seq,
            runId = runId,
            outcome = outcome
        )
    )

    private fun usageRecord(seq: Long, usage: Usage) = SessionMutation.Record(
        LaneRecord.UsageRecord(
            id = "u$seq",
            lane = "main",
            seq = seq,
            timestamp = seq,
            usage = usage,
            fields = kotlinx.serialization.json.JsonObject(
                mapOf("cause" to kotlinx.serialization.json.JsonPrimitive("assistant"))
            )
        )
    )

    private fun usage(
        input: Int = 10,
        cacheRead: Int = 100,
        cacheWrite: Int = 20,
        totalTokens: Int,
        costTotal: Double
    ) = Usage(
        input = input,
        output = 5,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        reasoning = 0,
        totalTokens = totalTokens,
        cost = Cost(0.0, 0.0, 0.0, 0.0, costTotal)
    )

    @Test
    fun `open operations track started and finished by runId`() {
        val state = SessionState()
        state.applyMutation(started(1, "op1"))
        assertEquals(listOf("op1"), state.findOpenOperations("main").map { it.id })

        state.applyMutation(
            SessionMutation.Record(
                LaneRecord.AbortRequestedRecord(
                    id = "a2",
                    lane = "main",
                    seq = 2,
                    timestamp = 2,
                    runId = "op1"
                )
            )
        )
        assertEquals(listOf("op1"), state.findOpenOperations("main").map { it.id })

        state.applyMutation(finished(3, "op1"))
        assertEquals(
            emptyList<LaneRecord.OperationStartedRecord>(),
            state.findOpenOperations("main")
        )

        state.applyMutation(finished(4, "ghost"))
        assertEquals(4, state.records().size)
    }

    @Test
    fun `findOpenOperations returns newest first and slices to limit`() {
        val state = SessionState()
        state.applyMutation(started(1, "op1"))
        state.applyMutation(started(2, "op2"))
        state.applyMutation(started(3, "op3"))
        state.applyMutation(finished(4, "op3"))
        // Two open operations is the corruption signal.
        assertEquals(listOf("op2", "op1"), state.findOpenOperations("main").map { it.id })
        assertEquals(
            listOf("op2", "op1"),
            state.findOpenOperations("main", limit = 2).map {
                it.id
            }
        )
        assertEquals(listOf("op2"), state.findOpenOperations("main", limit = 1).map { it.id })
        assertFailsWith<SessionError> {
            state.findOpenOperations("main", limit = 0)
            Unit
        }
        assertFailsWith<SessionError> {
            state.findOpenOperations("main", limit = -1)
            Unit
        }
        assertEquals(
            emptyList<LaneRecord.OperationStartedRecord>(),
            state.findOpenOperations("ghost")
        )
    }

    @Test
    fun `usage records accumulate session stats incrementally`() {
        val state = SessionState()
        state.applyMutation(
            SessionMutation.Entry(
                lane = "main",
                entry = MessageEntry(
                    id = "m1",
                    seq = 1,
                    parentId = null,
                    timestamp = 1,
                    message = works.resolve.pathfinder.ai.UserMessage.ofText("hi", 1)
                )
            )
        )
        state.applyMutation(
            usageRecord(
                2,
                usage(
                    cacheRead = 100,
                    cacheWrite = 20,
                    input = 10,
                    totalTokens = 130,
                    costTotal = 0.1
                )
            )
        )
        state.applyMutation(
            usageRecord(
                3,
                usage(cacheRead = 1, cacheWrite = 2, input = 3, totalTokens = 6, costTotal = 0.2)
            )
        )

        // cacheRead folds into cachedTokens; input + cacheWrite into uncachedTokens.
        assertEquals(
            SessionStats(
                messageCount = 1,
                cachedTokens = 101,
                uncachedTokens = 35,
                totalTokens = 136,
                costTotal = 0.30000000000000004
            ),
            state.stats()
        )
        assertEquals(1, state.messageCount())
        state.applyMutation(started(4, "op1"))
        assertEquals(136L, state.stats().totalTokens)
    }

    @Test
    fun `operation records fold intent and sourceLeafId verbatim`() {
        val state = SessionState()
        val record = LaneRecord.OperationStartedRecord(
            id = "op1",
            lane = "main",
            seq = 1,
            timestamp = 1,
            sourceLeafId = "later-entry",
            intent = OperationIntent.compaction("e9")
        )
        state.applyMutation(SessionMutation.Record(record))
        val open = state.findOpenOperations("main").single()
        assertEquals("later-entry", open.sourceLeafId)
        assertEquals(OperationIntent.Kind.COMPACTION, open.intent.kind)
        assertTrue(open.intent.payload["resultEntryId"]!!.toString().contains("e9"))
        // "later-entry" does not exist (yet); the fold never validates record payload references.
        assertEquals(record, state.records().single())
    }
}
