package works.resolve.pathfinder.data.sessions

import kotlinx.serialization.json.JsonElement

/**
 * Query and fork surface of a session's replayed state, porting pi's
 * session query types (packages/agent/src/harness/session/types.ts:
 * EntryOrder, EntryCursor, EntryQuery, BranchBounds, RecordQuery,
 * ForkOptions, LanePointer).
 *
 * Product boundary (audit P1-1): Android's UI is single-lane today; these
 * types exist so the storage model carries pi's lane semantics and
 * pi-produced logs replay correctly, and so future paging/reducer work can
 * query with pi's exact shapes.
 */

/** pi's EntryOrder (types.ts): scan direction; default newestFirst. */
enum class EntryOrder(val wire: String) {
    NEWEST_FIRST("newestFirst"),
    OLDEST_FIRST("oldestFirst"),
}

/** pi's EntryCursor (types.ts): exclusive seq bound, direction-aware. */
data class EntryCursor(val afterSeq: Long)

/**
 * pi's EntryQuery (types.ts): type/customType filters, order, limit, and a
 * seq cursor. All fields optional; null is TS `undefined`.
 */
data class EntryQuery(
    val type: EntryType? = null,
    /** Only meaningful with [type] [EntryType.CUSTOM] (pi's customType). */
    val customType: String? = null,
    val order: EntryOrder? = null,
    val limit: Int? = null,
    val cursor: EntryCursor? = null,
)

/**
 * Bounds of a branch scan (pi's BranchBounds, types.ts). Default: the whole
 * path, leaf to root. [start] is required at the storage/state layer; the
 * session layer defaults it to the view's lane leaf.
 */
data class BranchBounds(
    val start: String? = null,
    /** Scan ends after the first match, inclusive. */
    val stopAtType: EntryType? = null,
    val stopAtId: String? = null,
)

/**
 * pi's RecordQuery (types.ts): exact lane/type matches, operation identity
 * (runId), intent kind, exclusive seq lower bound, order, and limit.
 * [afterSeq] is `seq > afterSeq` regardless of order.
 */
data class RecordQuery(
    val lane: String? = null,
    val type: RecordType? = null,
    val runId: String? = null,
    /** Valid only with [type] [RecordType.OPERATION_STARTED] (session layer enforces). */
    val operationKind: OperationIntent.Kind? = null,
    val afterSeq: Long? = null,
    val order: EntryOrder? = null,
    val limit: Int? = null,
)

/** pi's LanePointer (types.ts): a lane's name and its current leaf. */
data class LanePointer(val lane: String, val leafId: String?)

/**
 * pi's `EntryQuery & BranchBounds & { start: string }` — the storage/state-
 * level branch query ([SessionState.findEntriesOnBranch]'s signature,
 * types.ts SessionStorage.findEntriesOnBranch). The session-level surface
 * ([LaneView]) defaults [start] to the view lane's leaf.
 */
data class BranchEntryQuery(
    val start: String,
    val stopAtType: EntryType? = null,
    val stopAtId: String? = null,
    val type: EntryType? = null,
    val customType: String? = null,
    val order: EntryOrder? = null,
    val limit: Int? = null,
    val cursor: EntryCursor? = null,
) {
    fun toEntryQuery(): EntryQuery = EntryQuery(type, customType, order, limit, cursor)
}

/**
 * pi's ForkOptions (types.ts: `{ scope?: "branch"; entryId?; position?:
 * "before" | "at" } | { scope: "tree" }`): the discriminated scope union
 * ported as a sealed type. [Branch] is the default scope (TS `scope?
 * = "branch"`); its entryId defaults to the source's "main" lane leaf and
 * its position to "at" when entryId is omitted, "before" otherwise
 * (state.ts createForkMutations).
 */
sealed class ForkOptions {
    data class Branch(
        val entryId: String? = null,
        val position: Position? = null,
    ) : ForkOptions() {
        /** pi's `"before" | "at"` position union. */
        enum class Position(val wire: String) { BEFORE("before"), AT("at") }
    }

    data object Tree : ForkOptions()
}

/** pi's Entry["type"] discriminant (session/types.ts), for query filters. */
enum class EntryType(val wire: String) {
    MESSAGE("message"),
    MODEL_CHANGE("model_change"),
    THINKING_LEVEL_CHANGE("thinking_level_change"),
    ACTIVE_TOOLS_CHANGE("active_tools_change"),
    COMPACTION("compaction"),
    BRANCH_SUMMARY("branch_summary"),
    CUSTOM("custom"),
}

/** The wire `type` of an entry (pi's Entry["type"]). */
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

/** pi's LaneRecord["type"] discriminant, for query filters. */
enum class RecordType(val wire: String) {
    OPERATION_STARTED("operation_started"),
    ABORT_REQUESTED("abort_requested"),
    OPERATION_FINISHED("operation_finished"),
    STEP_ATTEMPT("step_attempt"),
    TOOL_STARTED("tool_started"),
    QUEUE_ENQUEUED("queue_enqueued"),
    QUEUE_CANCELLED("queue_cancelled"),
    WRITE_DEFERRED("write_deferred"),
    USAGE("usage"),
}

/** The wire `type` of a record (pi's LaneRecord["type"]). */
val LaneRecord.recordType: RecordType
    get() = when (this) {
        is LaneRecord.OperationStartedRecord -> RecordType.OPERATION_STARTED
        is LaneRecord.AbortRequestedRecord -> RecordType.ABORT_REQUESTED
        is LaneRecord.OperationFinishedRecord -> RecordType.OPERATION_FINISHED
        is LaneRecord.UsageRecord -> RecordType.USAGE
        is LaneRecord.DeferredRecord -> RecordType.entries.first { it.wire == type }
    }
