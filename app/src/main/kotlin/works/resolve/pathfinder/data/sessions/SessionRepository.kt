package works.resolve.pathfinder.data.sessions

/**
 * Narrow session boundary used by UI-layer code. Keeping this interface
 * separate from [SessionStore] lets JVM tests substitute a failing store.
 *
 * [save] is an append-only sync (pi's JSONL v4 mutation log): the store
 * appends the snapshot's unpersisted entries, the lane move to its leaf,
 * and the title fact — never rewriting the file. Saving an unchanged
 * snapshot is a no-op, so failed saves are safely retryable.
 *
 * [appendRecord]/[openOperations]/[stats] are the lane-record surface
 * (pi's Session.appendRecord/findOpenOperations/getStats): producers
 * append operation-lifecycle records immediately — a record's seq may
 * precede the entries it references (see [LaneRecord]) — and recovery
 * reads unfinished operations with the `limit: 2` contract.
 */
interface SessionRepository {
    suspend fun create(title: String = "New chat"): Session

    suspend fun summaries(): List<SessionSummary>

    suspend fun load(id: String): Session?

    suspend fun save(session: Session): Session

    /** Appends [record] to the session's log; storage assigns seq/timestamp. */
    suspend fun appendRecord(sessionId: String, record: LaneRecord): LaneRecord

    /** pi's findOpenOperations: unfinished operation starts, newest first. */
    suspend fun openOperations(
        sessionId: String,
        lane: String,
        limit: Int?,
    ): List<LaneRecord.OperationStartedRecord>

    /** pi's getStats: the incremental message/usage fold of the log. */
    suspend fun stats(sessionId: String): SessionStats
}
