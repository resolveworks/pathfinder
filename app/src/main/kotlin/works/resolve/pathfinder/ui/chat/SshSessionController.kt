package works.resolve.pathfinder.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ssh.HostKeyRequest
import works.resolve.pathfinder.ssh.SshConnectionException
import works.resolve.pathfinder.ssh.SshConnectionHelper
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
    private val connectionHelper: SshConnectionHelper
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

    private val _hostTest = MutableStateFlow<HostTestState?>(null)

    /** Latest connection-test status, keyed by host; the host form shows only its own host's. */
    val hostTest: StateFlow<HostTestState?> = _hostTest.asStateFlow()

    /**
     * Dials [hostId] exactly like a session connect (TOFU prompt included),
     * closes the connection right away, and publishes progress plus a safe
     * result. One test at a time; taps while running are ignored.
     */
    fun testHostConnection(hostId: String) {
        if (_hostTest.value?.running == true) return
        scope.launch {
            _hostTest.value = HostTestState(hostId = hostId, running = true)
            _hostTest.value = try {
                val connection = withHostConnectContext(hostId) {
                    connectionHelper.connect(hostId, hostKeyConfirmer)
                }
                try {
                    connection.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The test already succeeded; a close failure is noise.
                }
                HostTestState(
                    hostId = hostId,
                    running = false,
                    success = true,
                    message = UiString(
                        R.string.ssh_host_test_success,
                        listOf(connection.initialWorkingDirectory)
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SshConnectionException) {
                HostTestState(
                    hostId = hostId,
                    running = false,
                    message = connectionErrorForHost(e, hostId)
                )
            } catch (e: Exception) {
                logger.warn("ssh_host_test", e)
                HostTestState(
                    hostId = hostId,
                    running = false,
                    message = hostLabel(hostId)?.let {
                        UiString(R.string.ssh_error_connect, listOf(it))
                    } ?: UiString(R.string.ssh_error_connect_generic)
                )
            }
        }
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
     * Runs [block] with the TOFU prompt's host context set, for connects
     * that have no session (the host form's connection test).
     */
    private suspend fun <T> withHostConnectContext(hostId: String, block: suspend () -> T): T {
        hostKeyConfirmer.setHostContext(hostId)
        try {
            return block()
        } finally {
            hostKeyConfirmer.setHostContext(null)
        }
    }

    /**
     * Safe, actionable message for a failed session connect, naming the
     * session's host; a pinned host-key mismatch is a hard stop — there is
     * deliberately no bypass UI for it.
     */
    suspend fun connectionError(error: SshConnectionException, sessionId: String): UiString =
        connectionError(error, hostLabel(sessionHosts.hostId(sessionId)))

    /** Same mapping for a connect outside any session (the host form's test). */
    private suspend fun connectionErrorForHost(
        error: SshConnectionException,
        hostId: String
    ): UiString = connectionError(error, hostLabel(hostId))

    private suspend fun hostLabel(hostId: String?): String? =
        hostId?.let { hostStore.host(it) }?.let { "${it.username}@${it.address}" }

    private fun connectionError(error: SshConnectionException, label: String?): UiString {
        logger.warn("ssh_connection_failed: {}", error.detail, error)
        return when (error.detail) {
            SshConnectionException.Detail.HOST_KEY_REJECTED ->
                if (label != null) {
                    UiString(R.string.ssh_error_host_key_changed, listOf(label))
                } else {
                    UiString(R.string.ssh_error_host_key_changed_generic)
                }

            SshConnectionException.Detail.CONNECT ->
                if (label != null) {
                    UiString(R.string.ssh_error_connect, listOf(label))
                } else {
                    UiString(R.string.ssh_error_connect_generic)
                }

            SshConnectionException.Detail.AUTH ->
                if (label != null) {
                    UiString(R.string.ssh_error_auth, listOf(label))
                } else {
                    UiString(R.string.ssh_error_auth_generic)
                }

            SshConnectionException.Detail.SFTP ->
                if (label != null) {
                    UiString(R.string.ssh_error_sftp, listOf(label))
                } else {
                    UiString(R.string.ssh_error_sftp_generic)
                }

            SshConnectionException.Detail.UNKNOWN_HOST ->
                UiString(R.string.ssh_error_unknown_host)

            SshConnectionException.Detail.NO_KEY -> UiString(R.string.ssh_error_no_key)
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SshSessionController::class.java)
    }
}
