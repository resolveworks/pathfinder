package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.ai.core.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One append-only mutation of a session's persisted log, porting pi's
 * SessionMutation union (packages/agent/src/harness/session/state.ts): an
 * appended [entry] (optionally lane-addressed), a lane [record] mutation, a
 * [lane] pointer move, or a global [fact] (session name / entry label).
 * Every persisted mutation consumes exactly one storage-assigned seq.
 */
sealed class SessionMutation {

    /**
     * An appended entry. [lane] is the appending lane (pi encodes it on
     * every storage-produced entry mutation; decoded lines may omit it, in
     * which case the mutation is not lane-addressed and replay does not
     * chain it to a lane leaf).
     */
    data class Entry(val lane: String?, val entry: SessionEntry) : SessionMutation()

    /** A lane record mutation (pi's `kind: "record"`). */
    data class Record(val record: LaneRecord) : SessionMutation()

    /** A lane pointer mutation (pi's `kind: "lane"`). */
    data class Lane(val seq: Long, val lane: String, val leafId: String?) : SessionMutation()

    /** A global fact mutation; latest wins (pi's `kind: "fact"`). */
    sealed class Fact : SessionMutation() {
        abstract val seq: Long

        /** The session display name (pi's `fact: "name"`); null clears it. */
        data class Name(override val seq: Long, val name: String?) : Fact()

        /** An entry label (pi's `fact: "label"`); null clears it. */
        data class Label(override val seq: Long, val targetId: String, val label: String?) : Fact()
    }
}

/**
 * A lane record, porting pi's LaneRecord union
 * (packages/agent/src/harness/session/types.ts RecordBase + union): the
 * operation lifecycle trio ([OperationStartedRecord], [AbortRequestedRecord],
 * [OperationFinishedRecord]) plus [UsageRecord]; the remaining upstream kinds
 * (step_attempt, tool_started, queue_enqueued, queue_cancelled,
 * write_deferred) decode as structurally-applied [DeferredRecord]s until
 * their producers land (audit P1-5 rider — the reducer is their consumer).
 *
 * Like pi's NewRecord, producers build records without seq/timestamp (the
 * [seq]/[timestamp] defaults); the storage's appendRecord assigns both and
 * returns the stored copy via [withAssigned].
 *
 * RECORD/ENTRY ORDERING (pi's actual invariant): state.ts's applyMutation
 * validates only that a record's lane exists and its id is unused — it never
 * validates payload references such as sourceLeafId, and pi's own compaction
 * intent names a resultEntryId whose entry is appended only when the
 * operation succeeds. Records may therefore legally precede, in seq order,
 * the entries they reference. Pathfinder's producers rely on exactly that:
 * records append to the log immediately (storage-assigned seq) while entries
 * buffer in the live Conversation until save() diff-syncs them, so an
 * operation_started may name a sourceLeafId whose entry mutation carries a
 * later seq. No flush is required or performed; a crash in that window
 * leaves a record referencing an entry that never persisted, which the
 * future reducer (P1-5) classifies like any other interrupted operation.
 */
sealed class LaneRecord {
    /** pi's RecordBase. */
    abstract val id: String
    abstract val lane: String
    abstract val seq: Long
    abstract val timestamp: Long

    /** Storage assignment (pi's appendRecord spreading `seq`/`timestamp`). */
    abstract fun withAssigned(seq: Long, timestamp: Long): LaneRecord

    /** pi's OperationStartedRecord; [id] is the operation's runId. */
    data class OperationStartedRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        /** The lane leaf the operation started from; may name an entry that persists later (see class KDoc). */
        val sourceLeafId: String? = null,
        val intent: OperationIntent,
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) = copy(seq = seq, timestamp = timestamp)
    }

    /** pi's AbortRequestedRecord. */
    data class AbortRequestedRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        /** The operation_started record's id. */
        val runId: String,
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) = copy(seq = seq, timestamp = timestamp)
    }

    /** pi's OperationFinishedRecord. */
    data class OperationFinishedRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        /** The operation_started record's id. */
        val runId: String,
        val outcome: OperationOutcome,
        val error: RecordError? = null,
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) = copy(seq = seq, timestamp = timestamp)
    }

    /**
     * pi's UsageRecord. The [usage] payload is typed (SessionState's stats
     * fold consumes it); the cause discriminant and its fields (cause,
     * runId, entryId, attempt, stopReason, toolCallId, details, …) stay
     * opaque in [fields] until the reducer (P1-5) gives them typed shapes.
     */
    data class UsageRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        val usage: Usage,
        val fields: JsonObject,
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) = copy(seq = seq, timestamp = timestamp)
    }

    /**
     * The deferred LaneRecord kinds (step_attempt, tool_started,
     * queue_enqueued, queue_cancelled, write_deferred): validated like pi's
     * codec (type discriminant only) and structurally applied on replay, but
     * payload-typed only when their producers land (P1-5's reducer consumes
     * them; queue kinds wait on steer/follow-up queues).
     */
    data class DeferredRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        /** pi's LaneRecord `type` discriminant. */
        val type: String,
        /** The record line's payload fields, minus the `kind` marker. */
        val fields: JsonObject,
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) = copy(seq = seq, timestamp = timestamp)
    }
}

/**
 * pi's OperationStartedRecord intent union (run/compaction/navigation),
 * kept as the validated `kind` discriminant plus the full intent object:
 * upstream intent payloads (run's originalPrompt/initialMessages/
 * systemPromptOverride/resumeData, navigation's summarize flag and friends)
 * are preserved verbatim for the future reducer, and Pathfinder's producers
 * currently write only the minimal shape they can honor.
 */
data class OperationIntent(
    val kind: Kind,
    /** The complete intent object, including its `kind` member. */
    val payload: JsonObject,
) {
    enum class Kind(val wire: String) {
        RUN("run"),
        COMPACTION("compaction"),
        NAVIGATION("navigation"),
    }

    companion object {
        /** Producer helper: the minimal run intent (see class KDoc). */
        fun run(): OperationIntent = OperationIntent(
            kind = Kind.RUN,
            payload = buildJsonObject { put("kind", "run") },
        )

        /** Producer helper: the compaction intent naming its [resultEntryId]. */
        fun compaction(resultEntryId: String): OperationIntent = OperationIntent(
            kind = Kind.COMPACTION,
            payload = buildJsonObject {
                put("kind", "compaction")
                put("resultEntryId", resultEntryId)
            },
        )
    }
}

/** pi's OperationFinishedRecord outcome union. */
enum class OperationOutcome(val wire: String) {
    COMPLETED("completed"),
    ABORTED("aborted"),
    FAILED("failed"),
    DECLINED("declined"),
}

/** pi's OperationFinishedRecord error shape. */
data class RecordError(val code: String, val message: String)
