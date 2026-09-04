package works.resolve.pathfinder.codingagent.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.Usage

/**
 * One append-only mutation of a session's persisted log: an appended
 * [entry] (optionally lane-addressed), a lane [record] mutation, a [lane]
 * pointer move, or a global [fact] (session name / entry label). Every
 * persisted mutation consumes exactly one storage-assigned seq.
 */
sealed class SessionMutation {

    /**
     * An appended entry. [lane] is the appending lane (pi encodes it on
     * every storage-produced entry mutation; decoded lines may omit it, in
     * which case the mutation is not lane-addressed and replay does not
     * chain it to a lane leaf).
     */
    data class Entry(val lane: String?, val entry: SessionEntry) : SessionMutation()

    /** A lane record mutation (`kind: "record"`). */
    data class Record(val record: LaneRecord) : SessionMutation()

    /** A lane pointer mutation (`kind: "lane"`). */
    data class Lane(val seq: Long, val lane: String, val leafId: String?) : SessionMutation()

    /** A global fact mutation (`kind: "fact"`); latest wins. */
    sealed class Fact : SessionMutation() {
        abstract val seq: Long

        /** The session display name (`fact: "name"`); null clears it. */
        data class Name(override val seq: Long, val name: String?) : Fact()

        /** An entry label (`fact: "label"`); null clears it. */
        data class Label(override val seq: Long, val targetId: String, val label: String?) : Fact()
    }
}

/**
 * One replayed log item per mutation: the shape returned by
 * [SessionState.getLog] for incremental tail reads.
 */
sealed class LogItem {
    abstract val seq: Long

    data class Entry(override val seq: Long, val entry: SessionEntry) : LogItem()

    data class Record(override val seq: Long, val record: LaneRecord) : LogItem()

    data class Lane(override val seq: Long, val lane: String, val leafId: String?) : LogItem()

    /** The `fact: "name"` log item; null clears the name. */
    data class FactName(override val seq: Long, val name: String?) : LogItem()

    /** The `fact: "label"` log item; null clears the label. */
    data class FactLabel(override val seq: Long, val targetId: String, val label: String?) :
        LogItem()
}

/**
 * Records may legally precede, in seq order, the entries they reference:
 * upstream validates a record only for lane existence and id uniqueness,
 * and producers append records to the log immediately while entries buffer
 * in the live [Conversation] until save() diff-syncs them — an
 * operation_started may name a sourceLeafId whose entry mutation carries a
 * later seq. A crash in that window leaves a record referencing an entry
 * that never persisted, which the reducer classifies like any other
 * interrupted operation.
 *
 * Producers build records without seq/timestamp (the 0 defaults); the
 * storage's append assigns both and returns the stored copy via
 * [withAssigned].
 */
sealed class LaneRecord {
    abstract val id: String
    abstract val lane: String
    abstract val seq: Long
    abstract val timestamp: Long

    abstract fun withAssigned(seq: Long, timestamp: Long): LaneRecord

    /** [id] is the operation's runId. */
    data class OperationStartedRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        /** The lane leaf the operation started from; may name an entry that persists later (see class KDoc). */
        val sourceLeafId: String? = null,
        val intent: OperationIntent
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) =
            copy(seq = seq, timestamp = timestamp)
    }

    data class AbortRequestedRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        /** The operation_started record's id. */
        val runId: String
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) =
            copy(seq = seq, timestamp = timestamp)
    }

    data class OperationFinishedRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        /** The operation_started record's id. */
        val runId: String,
        val outcome: OperationOutcome,
        val error: RecordError? = null
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) =
            copy(seq = seq, timestamp = timestamp)
    }

    /**
     * The [usage] payload is typed ([SessionState]'s stats fold consumes
     * it); the cause discriminant and its remaining fields stay opaque in
     * [fields], which the reducer reads by key.
     */
    data class UsageRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        val usage: Usage,
        val fields: JsonObject
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) =
            copy(seq = seq, timestamp = timestamp)
    }

    /**
     * Catch-all for the remaining upstream record kinds (step_attempt,
     * tool_started, queue_enqueued, queue_cancelled, write_deferred): only
     * the [type] discriminant is validated; the payload stays untyped in
     * [fields], which the reducer reads by key where upstream reads typed
     * members.
     */
    data class DeferredRecord(
        override val id: String,
        override val lane: String,
        override val seq: Long = 0L,
        override val timestamp: Long = 0L,
        val type: String,
        val fields: JsonObject
    ) : LaneRecord() {
        override fun withAssigned(seq: Long, timestamp: Long) =
            copy(seq = seq, timestamp = timestamp)
    }
}

/**
 * The validated `kind` discriminant plus the full intent [payload]:
 * upstream intent objects are preserved verbatim (fields pathfinder never
 * writes included), while pathfinder's producers write only the minimal
 * shape they can honor.
 */
data class OperationIntent(
    val kind: Kind,
    /** The complete intent object, including its `kind` member. */
    val payload: JsonObject
) {
    enum class Kind(val wire: String) {
        RUN("run"),
        COMPACTION("compaction"),
        NAVIGATION("navigation")
    }

    companion object {
        fun run(): OperationIntent = OperationIntent(
            kind = Kind.RUN,
            payload = buildJsonObject { put("kind", "run") }
        )

        fun compaction(resultEntryId: String): OperationIntent = OperationIntent(
            kind = Kind.COMPACTION,
            payload = buildJsonObject {
                put("kind", "compaction")
                put("resultEntryId", resultEntryId)
            }
        )
    }
}

enum class OperationOutcome(val wire: String) {
    COMPLETED("completed"),
    ABORTED("aborted"),
    FAILED("failed"),
    DECLINED("declined")
}

/** The error payload of an [OperationFinishedRecord]. */
data class RecordError(val code: String, val message: String)
