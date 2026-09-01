package works.resolve.pathfinder.data.sessions

import kotlinx.serialization.json.JsonObject

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

    /**
     * A lane record mutation. Decode-only for now: pi's full LaneRecord
     * union (operation lifecycle, steps, tools, queues, usage) has no
     * Pathfinder producers yet — the codec decodes and replay structurally
     * applies record lines (seq/id/lane validation), but no record-specific
     * state (open operations, usage stats) is folded. That lands with the
     * P0-3 record port.
     */
    data class Record(val record: SessionRecord) : SessionMutation()

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
 * A decoded lane-record mutation line, pi's LaneRecord
 * (packages/agent/src/harness/session/types.ts RecordBase + union). The
 * codec validates the shared RecordBase fields and the record-type
 * discriminant (plus operation_started/operation_finished payload checks);
 * the union's per-type payloads stay provider-opaque [fields] until the
 * P0-3 record port gives them typed shapes.
 */
data class SessionRecord(
    val id: String,
    val seq: Long,
    val lane: String,
    val timestamp: Long,
    /** pi's LaneRecord `type` discriminant. */
    val type: String,
    /** The record line's payload fields, minus the `kind` marker. */
    val fields: JsonObject,
)
