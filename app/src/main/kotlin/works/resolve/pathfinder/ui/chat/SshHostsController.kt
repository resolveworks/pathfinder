package works.resolve.pathfinder.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ssh.SshConnectionException
import works.resolve.pathfinder.ssh.SshConnectionHelper
import works.resolve.pathfinder.ssh.SshHost
import works.resolve.pathfinder.ssh.SshHostStore
import works.resolve.pathfinder.ssh.TofuHostKeyConfirmer

/**
 * The SSH hosts screen's model: a live view over [SshHostStore], the
 * add/edit/remove intents, and the host form's connection test. The store
 * owns persistence and key material; only validation and safe errors live
 * here.
 */
internal class SshHostsController(
    private val scope: CoroutineScope,
    private val hostStore: SshHostStore,
    private val connectionHelper: SshConnectionHelper,
    private val hostKeyConfirmer: TofuHostKeyConfirmer,
    private val onError: (message: UiString, cause: Throwable?) -> Unit
) {

    /** The source of truth [ChatUiState.sshHosts] mirrors. */
    val hosts: StateFlow<List<SshHost>> =
        hostStore.hosts.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _hostTest = MutableStateFlow<HostTestState?>(null)

    /** Latest connection-test status, keyed by host; the host form shows only its own host's. */
    val hostTest: StateFlow<HostTestState?> = _hostTest.asStateFlow()

    /**
     * Dials [hostId] exactly like a tool-call connect (TOFU prompt included),
     * closes the connection right away, and publishes progress plus a safe
     * result. One test at a time; taps while running are ignored.
     */
    fun testHostConnection(hostId: String) {
        if (_hostTest.value?.running == true) return
        scope.launch {
            _hostTest.value = HostTestState(hostId = hostId, running = true)
            _hostTest.value = try {
                val host =
                    hostStore.host(hostId)
                        ?: throw SshConnectionException(
                            "Unknown SSH host",
                            SshConnectionException.Detail.UNKNOWN_HOST
                        )
                val connection = connectionHelper.connect(hostId) { fingerprint, keyType ->
                    hostKeyConfirmer.confirm(host, fingerprint, keyType)
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
                        listOf(host.cwd)
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SshConnectionException) {
                HostTestState(
                    hostId = hostId,
                    running = false,
                    message = connectionError(e, hostLabel(hostId))
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
     * Creates a host (with its freshly generated keypair, see
     * [SshHostStore.addHost]). Invalid input or a storage failure surfaces
     * a safe error and stores nothing.
     */
    fun addHost(address: String, port: Int, username: String, cwd: String) {
        scope.launch {
            if (!valid(address, port, username, cwd)) {
                onError(UiString(R.string.error_ssh_host_invalid), null)
                return@launch
            }
            try {
                hostStore.addHost(address.trim(), port, username.trim(), cwd.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(UiString(R.string.error_ssh_host_save), e)
            }
        }
    }

    /** Persists edited connection fields; the key material never changes. */
    fun updateHost(host: SshHost) {
        scope.launch {
            if (!valid(host.address, host.port, host.username, host.cwd)) {
                onError(UiString(R.string.error_ssh_host_invalid), null)
                return@launch
            }
            try {
                hostStore.updateHost(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(UiString(R.string.error_ssh_host_save), e)
            }
        }
    }

    /** Deletes a host and its keypair; a failure surfaces a safe error. */
    fun removeHost(id: String) {
        scope.launch {
            try {
                hostStore.removeHost(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("ssh_host_remove", e)
                onError(UiString(R.string.error_ssh_host_remove), e)
            }
        }
    }

    private suspend fun hostLabel(hostId: String?): String? =
        hostId?.let { hostStore.host(it) }?.let { "${it.username}@${it.address}" }

    /** Safe, actionable message for a failed test connect, naming the host. */
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

            SshConnectionException.Detail.UNKNOWN_HOST ->
                UiString(R.string.ssh_error_unknown_host)

            SshConnectionException.Detail.NO_KEY -> UiString(R.string.ssh_error_no_key)

            SshConnectionException.Detail.SFTP ->
                throw IllegalStateException("SFTP failure is not reachable from a test connect")
        }
    }

    private fun valid(address: String, port: Int, username: String, cwd: String): Boolean =
        address.isNotBlank() && username.isNotBlank() && cwd.trim().startsWith("/") &&
            port in 1..65535

    private companion object {
        private val logger = LoggerFactory.getLogger(SshHostsController::class.java)
    }
}
