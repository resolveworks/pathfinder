package works.resolve.pathfinder.codingagent.core.session

/**
 * Narrow session boundary used by UI-layer code, kept separate from
 * [SessionStore] so JVM tests can substitute a failing store.
 *
 * [save] is an append-only sync: the store appends the snapshot's
 * unpersisted entries, the lane move to its leaf, and the title fact —
 * never rewriting the file. Saving an unchanged snapshot is a no-op, so
 * failed saves are safely retryable.
 *
 * [appendRecord]/[openOperations]/[stats] are the lane-record surface:
 * producers append operation-lifecycle records immediately — a record's
 * seq may precede the entries it references (see [LaneRecord]) — and
 * recovery reads unfinished operations with the `limit: 2` contract.
 */
interface SessionRepository {
    suspend fun create(title: String = "New chat"): Session

    suspend fun summaries(): List<SessionSummary>

    suspend fun load(id: String): Session?

    suspend fun save(session: Session): Session

    /** Appends [record] to the session's log; storage assigns seq/timestamp. */
    suspend fun appendRecord(sessionId: String, record: LaneRecord): LaneRecord

    /** Unfinished operation starts, newest first (pi's findOpenOperations). */
    suspend fun openOperations(
        sessionId: String,
        lane: String,
        limit: Int?,
    ): List<LaneRecord.OperationStartedRecord>

    suspend fun findRecords(sessionId: String, query: RecordQuery = RecordQuery()): List<LaneRecord>

    /** The incremental message/usage fold of the log (pi's getStats). */
    suspend fun stats(sessionId: String): SessionStats

    /** A new session whose log is the source's fork mutation batch, with the source id as its parent (pi's SessionRepo.fork). */
    suspend fun fork(sourceId: String, options: ForkOptions): Session
}
