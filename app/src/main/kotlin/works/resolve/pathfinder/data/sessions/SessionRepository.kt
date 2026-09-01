package works.resolve.pathfinder.data.sessions

/**
 * Narrow session boundary used by UI-layer code. Keeping this interface
 * separate from [SessionStore] lets JVM tests substitute a failing store.
 *
 * [save] is an append-only sync (pi's JSONL v4 mutation log): the store
 * appends the snapshot's unpersisted entries, the lane move to its leaf,
 * and the title fact — never rewriting the file. Saving an unchanged
 * snapshot is a no-op, so failed saves are safely retryable.
 */
interface SessionRepository {
    suspend fun create(title: String = "New chat"): Session

    suspend fun summaries(): List<SessionSummary>

    suspend fun load(id: String): Session?

    suspend fun save(session: Session): Session
}
