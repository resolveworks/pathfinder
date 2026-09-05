package works.resolve.pathfinder.data.sessions

import java.io.File
import works.resolve.pathfinder.codingagent.core.session.SessionError
import works.resolve.pathfinder.codingagent.core.session.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.session.SessionInfo
import works.resolve.pathfinder.codingagent.core.session.SessionManager

/**
 * The app's seam over a sessions directory: create/open/list via
 * [SessionManager]'s companion functions. All appends happen through the
 * returned managers themselves.
 */
interface SessionSource {
    /** New memory-only session; nothing touches disk until its first assistant message commits. */
    suspend fun create(): SessionManager

    /**
     * Opens the session with [id], or null when no such session exists. A
     * session file the user cannot open (invalid contents) behaves as
     * missing — it must never block startup; genuine [SessionErrorCode.STORAGE]
     * failures surface.
     */
    suspend fun open(id: String): SessionManager?

    /** Sessions sorted by most recent activity; excludes never-flushed sessions. */
    suspend fun list(): List<SessionInfo>
}

class DirectorySessionSource(private val dir: File) : SessionSource {
    override suspend fun create(): SessionManager = SessionManager.create(dir)

    override suspend fun open(id: String): SessionManager? = try {
        SessionManager.openById(dir, id)
    } catch (e: SessionError) {
        when (e.code) {
            SessionErrorCode.STORAGE -> throw e
            else -> null
        }
    }

    override suspend fun list(): List<SessionInfo> = SessionManager.list(dir)
}
