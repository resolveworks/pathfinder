package works.resolve.pathfinder.ui.chat

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import works.resolve.pathfinder.R
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
    private val app: Application,
    private val hostStore: SshHostStore,
    private val sessionHosts: SshSessionHostStore,
    private val connections: SshSessionConnections,
    private val hostKeyConfirmer: TofuHostKeyConfirmer
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
                    app.getString(R.string.ssh_error_host_key_changed, label)
                } else {
                    app.getString(R.string.ssh_error_host_key_changed_generic)
                }

            SshConnectionException.Detail.CONNECT ->
                if (label != null) {
                    app.getString(R.string.ssh_error_connect, label)
                } else {
                    app.getString(R.string.ssh_error_connect_generic)
                }

            SshConnectionException.Detail.AUTH ->
                if (label != null) {
                    app.getString(R.string.ssh_error_auth, label)
                } else {
                    app.getString(R.string.ssh_error_auth_generic)
                }

            SshConnectionException.Detail.SFTP ->
                if (label != null) {
                    app.getString(R.string.ssh_error_sftp, label)
                } else {
                    app.getString(R.string.ssh_error_sftp_generic)
                }

            SshConnectionException.Detail.UNKNOWN_HOST ->
                app.getString(R.string.ssh_error_unknown_host)

            SshConnectionException.Detail.NO_KEY -> app.getString(R.string.ssh_error_no_key)
        }
    }
}
