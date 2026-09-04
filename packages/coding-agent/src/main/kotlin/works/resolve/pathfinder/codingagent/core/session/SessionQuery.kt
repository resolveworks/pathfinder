package works.resolve.pathfinder.codingagent.core.session

import kotlinx.serialization.json.JsonElement

/*
 * Query and fork surface of a session's replayed state, mirroring pi's
 * session query types. Pathfinder's UI is single-lane, but the storage
 * model carries pi's lane semantics so pi-produced logs replay correctly.
 */

/** Scan direction; default newestFirst. */
enum class EntryOrder(val wire: String) {
    NEWEST_FIRST("newestFirst"),
    OLDEST_FIRST("oldestFirst")
}

/** Exclusive seq bound, direction-aware. */
data class EntryCursor(val afterSeq: Long)

data class EntryQuery(
    val type: EntryType? = null,
    /** Only meaningful with [type] [EntryType.CUSTOM]. */
    val customType: String? = null,
    val order: EntryOrder? = null,
    val limit: Int? = null,
    val cursor: EntryCursor? = null
)

/**
 * Bounds of a branch scan; default is the whole path, leaf to root.
 * [start] is optional here but required at the storage/state layer; the
 * session layer ([LaneView]) defaults it to the view's lane leaf.
 */
data class BranchBounds(
    val start: String? = null,
    /** Scan ends after the first match, inclusive. */
    val stopAtType: EntryType? = null,
    val stopAtId: String? = null
)

/** Exclusive chronological lower bound: `seq > afterSeq`, regardless of order. */
data class RecordQuery(
    val lane: String? = null,
    val type: RecordType? = null,
    val runId: String? = null,
    /** Valid only with [type] [RecordType.OPERATION_STARTED] (enforced at the session layer). */
    val operationKind: OperationIntent.Kind? = null,
    val afterSeq: Long? = null,
    val order: EntryOrder? = null,
    val limit: Int? = null
)

/** A lane's name and its current leaf. */
data class LanePointer(val lane: String, val leafId: String?)

/**
 * Branch query at the storage/state level — [EntryQuery] fields plus
 * [BranchBounds] with a required [start]; the parameter shape of
 * [SessionState.findEntriesOnBranch].
 */
data class BranchEntryQuery(
    val start: String,
    val stopAtType: EntryType? = null,
    val stopAtId: String? = null,
    val type: EntryType? = null,
    val customType: String? = null,
    val order: EntryOrder? = null,
    val limit: Int? = null,
    val cursor: EntryCursor? = null
) {
    fun toEntryQuery(): EntryQuery = EntryQuery(type, customType, order, limit, cursor)
}

/**
 * Fork scope. [Branch.entryId] defaults to the source's "main" lane leaf,
 * and position to "at" when entryId is omitted, "before" otherwise.
 */
sealed class ForkOptions {
    data class Branch(val entryId: String? = null, val position: Position? = null) :
        ForkOptions() {
        enum class Position(val wire: String) { BEFORE("before"), AT("at") }
    }

    data object Tree : ForkOptions()
}

/** Entry type discriminant, for query filters. */
enum class EntryType(val wire: String) {
    MESSAGE("message"),
    MODEL_CHANGE("model_change"),
    THINKING_LEVEL_CHANGE("thinking_level_change"),
    ACTIVE_TOOLS_CHANGE("active_tools_change"),
    COMPACTION("compaction"),
    BRANCH_SUMMARY("branch_summary"),
    CUSTOM("custom")
}

/** The wire `type` of an entry. */
val SessionEntry.entryType: EntryType
    get() = when (this) {
        is MessageEntry -> EntryType.MESSAGE
        is ModelChangeEntry -> EntryType.MODEL_CHANGE
        is ThinkingLevelEntry -> EntryType.THINKING_LEVEL_CHANGE
        is ActiveToolsEntry -> EntryType.ACTIVE_TOOLS_CHANGE
        is CompactionEntry -> EntryType.COMPACTION
        is BranchSummaryEntry -> EntryType.BRANCH_SUMMARY
        is CustomEntry -> EntryType.CUSTOM
    }

/** Record type discriminant, for query filters. */
enum class RecordType(val wire: String) {
    OPERATION_STARTED("operation_started"),
    ABORT_REQUESTED("abort_requested"),
    OPERATION_FINISHED("operation_finished"),
    STEP_ATTEMPT("step_attempt"),
    TOOL_STARTED("tool_started"),
    QUEUE_ENQUEUED("queue_enqueued"),
    QUEUE_CANCELLED("queue_cancelled"),
    WRITE_DEFERRED("write_deferred"),
    USAGE("usage")
}

/** The wire `type` of a record. */
val LaneRecord.recordType: RecordType
    get() = when (this) {
        is LaneRecord.OperationStartedRecord -> RecordType.OPERATION_STARTED
        is LaneRecord.AbortRequestedRecord -> RecordType.ABORT_REQUESTED
        is LaneRecord.OperationFinishedRecord -> RecordType.OPERATION_FINISHED
        is LaneRecord.UsageRecord -> RecordType.USAGE
        is LaneRecord.DeferredRecord -> RecordType.entries.first { it.wire == type }
    }
