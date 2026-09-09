package works.resolve.pathfinder.ssh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Live per-session SSH connections: one connection per session, owned by the
 * session that requested it. [AgentSession][works.resolve.pathfinder.codingagent.core.AgentSession]
 * has no dispose seam, so closing is driven from the app layer at session
 * replacement (ChatViewModel) — this registry is that narrow seam. No
 * pooling, reconnect, or keepalive.
 */
class SshSessionConnections {

    private companion object {
        private val logger = LoggerFactory.getLogger(SshSessionConnections::class.java)
    }

    private val mutex = Mutex()
    private val connections = mutableMapOf<String, SshConnection>()

    /**
     * Registers [connection] for [sessionId], closing any connection already
     * registered for it (a session re-created on switch-back reconnects).
     */
    suspend fun register(sessionId: String, connection: SshConnection): SshConnection =
        mutex.withLock {
            connections.remove(sessionId)?.let {
                logger.info("closing replaced connection for session {}", sessionId)
                it.close()
            }
            connections[sessionId] = connection
            connection
        }

    suspend fun close(sessionId: String) {
        val connection = mutex.withLock { connections.remove(sessionId) } ?: return
        logger.info("closing connection for session {}", sessionId)
        connection.close()
    }
}
