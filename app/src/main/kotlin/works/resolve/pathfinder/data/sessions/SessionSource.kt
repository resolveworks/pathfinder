package works.resolve.pathfinder.data.sessions

import java.io.File
import org.slf4j.LoggerFactory
import works.resolve.pathfinder.codingagent.core.SessionError
import works.resolve.pathfinder.codingagent.core.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.SessionInfo
import works.resolve.pathfinder.codingagent.core.SessionManager

/**
 * The app's seam over a sessions directory: create/open/list via
 * [SessionManager]'s companion functions. All appends happen through the
 * returned managers themselves. Opens address a listed [SessionInfo.path]
 * (pi's picker-to-resume handoff) — no id discovery.
 */
interface SessionSource {
    /** New memory-only session; nothing touches disk until its first assistant message commits. */
    suspend fun create(): SessionManager

    /**
     * Opens [file], or null when it is missing or not a session (a file
     * the user cannot open must never block startup);
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

            else -> {
                logger.warn("session_open_skipped: file={}", file.name, e)
                null
            }
        }
    }

    override suspend fun list(): List<SessionInfo> = SessionManager.list(dir)

    private companion object {
        private val logger = LoggerFactory.getLogger(DirectorySessionSource::class.java)
    }
}
