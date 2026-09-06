package works.resolve.pathfinder.data.sessions

import java.io.File
import works.resolve.pathfinder.codingagent.core.session.SessionError
import works.resolve.pathfinder.codingagent.core.session.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.session.SessionInfo
import works.resolve.pathfinder.codingagent.core.session.SessionManager

/**
 * The app's seam over a sessions directory: create/open/list via
 * [SessionManager]'s companion functions. All appends happen through the
 * returned managers themselves. Like pi's picker-to-resume handoff, opens
 * address a file listed up front ([SessionInfo.path]) — there is no id
 * discovery.
 */
interface SessionSource {
    /** New memory-only session; nothing touches disk until its first assistant message commits. */
    suspend fun create(): SessionManager

    /**
     * Opens the session file at [file] (from a listed [SessionInfo.path]),
     * or null when it no longer exists or its contents are not a session —
     * a file the user cannot open must never block startup. Genuine
     * [SessionErrorCode.STORAGE] failures surface.
     */
    suspend fun open(file: File): SessionManager?

    /** Sessions sorted by most recent activity; excludes never-flushed sessions. */
    suspend fun list(): List<SessionInfo>
}

class DirectorySessionSource(private val dir: File) : SessionSource {
    override suspend fun create(): SessionManager = SessionManager.create(dir)

    override suspend fun open(file: File): SessionManager? = try {
        SessionManager.open(file)
    } catch (e: SessionError) {
        when (e.code) {
            SessionErrorCode.STORAGE -> throw e
            else -> null
        }
    }

    override suspend fun list(): List<SessionInfo> = SessionManager.list(dir)
}
