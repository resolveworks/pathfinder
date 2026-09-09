package works.resolve.pathfinder.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ssh.SshHost
import works.resolve.pathfinder.ssh.SshHostStore

/**
 * The SSH hosts screen's model: a live view over [SshHostStore] plus the
 * add/edit/remove intents. The store owns persistence and key material;
 * only validation and safe errors live here.
 */
internal class SshHostsController(
    private val scope: CoroutineScope,
    private val hostStore: SshHostStore,
    private val onError: (message: UiString, cause: Throwable?) -> Unit
) {

    /** The source of truth [ChatUiState.sshHosts] mirrors. */
    val hosts: StateFlow<List<SshHost>> =
        hostStore.hosts.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Creates a host (with its freshly generated keypair, see
     * [SshHostStore.addHost]). Invalid input or a storage failure surfaces
     * a safe error and stores nothing.
     */
    fun addHost(address: String, port: Int, username: String) {
        scope.launch {
            if (!valid(address, port, username)) {
                onError(UiString(R.string.error_ssh_host_invalid), null)
                return@launch
            }
            try {
                hostStore.addHost(address.trim(), port, username.trim())
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
            if (!valid(host.address, host.port, host.username)) {
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

    private fun valid(address: String, port: Int, username: String): Boolean =
        address.isNotBlank() && username.isNotBlank() && port in 1..65535

    private companion object {
        private val logger = LoggerFactory.getLogger(SshHostsController::class.java)
    }
}
