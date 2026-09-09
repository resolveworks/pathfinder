package works.resolve.pathfinder.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * The process-wide SSH connection owner: resolves the effective host
 * selection, dials lazily on demand, and caches one authenticated
 * connection: a host switch closes the previous. A cached connection that
 * is no longer authenticated is closed and redialed.
 */
class SshConnectionProvider(
    private val store: SshHostStore,
    private val helper: SshConnectionHelper,
    private val hostKeyConfirmer: TofuHostKeyConfirmer
) {
    private val selectedHostId = MutableStateFlow<String?>(null)

    private val mutex = Mutex()

    private var cached: Pair<String, SshConnection>? = null

    private companion object {
        private val logger = LoggerFactory.getLogger(SshConnectionProvider::class.java)
    }

    /** Sets the process-wide host selection (null = unset; falls back to sole/first host). */
    fun select(hostId: String?) {
        selectedHostId.value = hostId
    }

    /**
     * The effective selection, resolved cold: the selected id if it still
     * exists, else the sole host, else the first host, else null.
     */
    private suspend fun effectiveHostId(): String? {
        val hosts = store.hosts.first()
        val selected = selectedHostId.value
        if (selected != null && hosts.any { it.id == selected }) return selected
        return hosts.singleOrNull()?.id ?: hosts.firstOrNull()?.id
    }

    /** The host tool calls dial; null when no host is configured. */
    suspend fun currentHost(): SshHost? = effectiveHostId()?.let { store.host(it) }

    /**
     * The current host's live connection, dialing when needed. A dead cache
     * entry is closed and redialed; a dial failure propagates as
     * [SshConnectionException] to the caller (the agent loop turns it into
     * an error tool result).
     */
    suspend fun connection(): SshConnection = mutex.withLock {
        val hostId =
            effectiveHostId()
                ?: throw SshConnectionException(
                    "No SSH host configured",
                    SshConnectionException.Detail.UNKNOWN_HOST
                )
        val host =
            store.host(hostId)
                ?: throw SshConnectionException(
                    "Unknown SSH host",
                    SshConnectionException.Detail.UNKNOWN_HOST
                )
        val existing = cached
        if (existing != null && existing.first == hostId &&
            existing.second.client.isAuthenticated
        ) {
            return existing.second
        }
        if (existing != null) {
            if (existing.first == hostId) {
                logger.info("ssh_cache_dead_closing: hostId={}", existing.first)
            } else {
                logger.info("ssh_cache_host_changed_closing: hostId={}", existing.first)
            }
            existing.second.close()
        }
        logger.info("ssh_dial_start: hostId={}", hostId)
        val connection = helper.connect(hostId) { fingerprint, keyType ->
            hostKeyConfirmer.confirm(host, fingerprint, keyType)
        }
        logger.info("ssh_dial_success: hostId={}", hostId)
        cached = hostId to connection
        return connection
    }

    /** Closes and drops the cached connection for [hostId], if any. */
    suspend fun evict(hostId: String) {
        mutex.withLock {
            val existing = cached
            if (existing != null && existing.first == hostId) {
                logger.info("ssh_evict_closing: hostId={}", hostId)
                cached = null
                existing.second.close()
            }
        }
    }
}
