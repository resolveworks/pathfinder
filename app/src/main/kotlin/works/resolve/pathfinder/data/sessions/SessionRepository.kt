package works.resolve.pathfinder.data.sessions

/**
 * Narrow session boundary used by UI-layer code. Keeping this interface
 * separate from [SessionStore] lets JVM tests substitute a failing store.
 */
interface SessionRepository {
    suspend fun create(title: String = "New chat"): Session

    suspend fun summaries(): List<SessionSummary>

    suspend fun load(id: String): Session?

    suspend fun save(session: Session): Session
}
