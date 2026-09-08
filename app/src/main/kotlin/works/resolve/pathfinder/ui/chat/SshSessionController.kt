package works.resolve.pathfinder.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import works.resolve.pathfinder.ssh.HostKeyRequest
import works.resolve.pathfinder.ssh.SshConnectionException
import works.resolve.pathfinder.ssh.SshHostStore
import works.resolve.pathfinder.ssh.SshSessionConnections
import works.resolve.pathfinder.ssh.SshSessionHostStore
import works.resolve.pathfinder.ssh.TofuHostKeyConfirmer

/**
 * The SSH session-connection lifecycle: the session↔host mapping (bind,
 * rollback, host-deletion cascade), the TOFU prompt context and answers,
 * connection close at session replacement, and the safe message mapping
 * for a failed connect.
 */
internal class SshSessionController(
    private val scope: CoroutineScope,
    private val hostStore: SshHostStore,
    private val sessionHosts: SshSessionHostStore,
    private val connections: SshSessionConnections,
    private val hostKeyConfirmer: TofuHostKeyConfirmer,
    private val onError: (message: String, cause: Throwable?) -> Unit
) {

    /** The TOFU request awaiting a Trust/Refuse answer, or null when none. */
    val pendingHostKey: StateFlow<HostKeyRequest?> =
        hostKeyConfirmer.pending

    /** Trusts the pending unknown-host-key request (TOFU first connect). */
    fun trustHostKey() = hostKeyConfirmer.answer(trust = true)

    /** Refuses the pending unknown-host-key request. */
    fun refuseHostKey() = hostKeyConfirmer.answer(trust = false)

    /** Records the session→host mapping before the session's agent is created. */
    suspend fun bindHostToSession(sessionId: String, hostId: String) {
        sessionHosts.setHost(sessionId, hostId)
    }

    /** Drops a failed session's mapping and its possibly established connection. */
    suspend fun rollbackSession(sessionId: String) {
        sessionHosts.clear(sessionId)
        connections.close(sessionId)
    }

    /** Deletes a host's session bindings (host deletion cascade). */
    fun clearHost(hostId: String) {
        scope.launch { sessionHosts.clearHost(hostId) }
    }

    /** Closes the outgoing session's SSH connection at session replacement. */
    fun closeConnection(sessionId: String) {
        scope.launch { connections.close(sessionId) }
    }

    /**
     * Runs [block] with the TOFU prompt's session context set, so the
     * prompt names the host the factory is about to dial.
     */
    suspend fun <T> withConnectContext(sessionId: String, block: suspend () -> T): T {
        hostKeyConfirmer.setSessionContext(sessionId)
        try {
            return block()
        } finally {
            hostKeyConfirmer.setSessionContext(null)
        }
    }

    /**
     * Safe, actionable message for a failed session connect, naming the
     * session's host; a pinned host-key mismatch is a hard stop — there is
     * deliberately no bypass UI for it.
     */
    suspend fun connectionError(error: SshConnectionException, sessionId: String): String {
        val label = sessionHosts.hostId(sessionId)
            ?.let { hostStore.host(it) }
            ?.let { "${it.username}@${it.address}" }
        return when (error.detail) {
            SshConnectionException.Detail.HOST_KEY_REJECTED ->
                if (label != null) {
                    "SSH host key for $label changed — the connection was refused for your " +
                        "safety. Verify the server before trusting it again."
                } else {
                    ERROR_SSH_HOST_KEY_CHANGED
                }

            SshConnectionException.Detail.CONNECT ->
                if (label != null) {
                    "Could not reach $label — check the address and that the host is up"
                } else {
                    ERROR_SSH_CONNECT
                }

            SshConnectionException.Detail.AUTH ->
                if (label != null) {
                    "$label refused the app's key — add the app's public key to " +
                        "authorized_keys on the server"
                } else {
                    ERROR_SSH_AUTH
                }

            SshConnectionException.Detail.SFTP ->
                if (label != null) {
                    "$label does not offer SFTP, which Pathfinder needs for remote files"
                } else {
                    ERROR_SSH_SFTP
                }

            SshConnectionException.Detail.UNKNOWN_HOST -> ERROR_SSH_UNKNOWN_HOST

            SshConnectionException.Detail.NO_KEY -> ERROR_SSH_NO_KEY
        }
    }

    private companion object {
        private const val ERROR_SSH_CONNECT = "Could not connect to the SSH host"
        private const val ERROR_SSH_AUTH = "The SSH host refused the app's key"
        private const val ERROR_SSH_SFTP = "The SSH host does not offer SFTP"
        private const val ERROR_SSH_UNKNOWN_HOST = "That SSH host is no longer configured"
        private const val ERROR_SSH_NO_KEY = "No SSH key is stored for that host"
        private const val ERROR_SSH_HOST_KEY_CHANGED =
            "The SSH host key changed — the connection was refused for your safety"
    }
}
