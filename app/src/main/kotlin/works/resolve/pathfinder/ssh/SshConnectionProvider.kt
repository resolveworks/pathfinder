package works.resolve.pathfinder.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * The process-wide SSH connection owner: resolves the effective machine
 * selection, dials lazily on demand, and caches one authenticated
 * connection: a machine switch closes the previous. A cached connection that
 * is no longer authenticated is closed and redialed.
 */
class SshConnectionProvider(
    private val store: MachineStore,
    private val helper: SshConnectionHelper,
    private val hostKeyConfirmer: TofuHostKeyConfirmer
) {
    private val selectedMachineId = MutableStateFlow<String?>(null)

    private val mutex = Mutex()

    private var cached: Pair<String, SshConnection>? = null

    private companion object {
        private val logger = LoggerFactory.getLogger(SshConnectionProvider::class.java)
    }

    /** Sets the process-wide machine selection (null = unset; falls back to sole/first machine). */
    fun select(machineId: String?) {
        selectedMachineId.value = machineId
    }

    /**
     * The effective selection, resolved cold: the selected id if it still
     * exists, else the sole machine, else the first machine, else null.
     */
    private suspend fun effectiveMachineId(): String? {
        val machines = store.machines.first()
        val selected = selectedMachineId.value
        if (selected != null && machines.any { it.id == selected }) return selected
        return machines.singleOrNull()?.id ?: machines.firstOrNull()?.id
    }

    /** The machine tool calls dial; null when no machine is configured. */
    suspend fun currentMachine(): Machine? = effectiveMachineId()?.let { store.machine(it) }

    /**
     * The current machine's live connection, dialing when needed. A dead cache
     * entry is closed and redialed; a dial failure propagates as
     * [SshConnectionException] to the caller (the agent loop turns it into
     * an error tool result).
     */
    suspend fun connection(): SshConnection = mutex.withLock {
        val machineId =
            effectiveMachineId()
                ?: throw SshConnectionException(
                    "No machine configured",
                    SshConnectionException.Detail.UNKNOWN_MACHINE
                )
        val machine =
            store.machine(machineId)
                ?: throw SshConnectionException(
                    "Unknown machine",
                    SshConnectionException.Detail.UNKNOWN_MACHINE
                )
        val existing = cached
        if (existing != null && existing.first == machineId &&
            existing.second.client.isAuthenticated
        ) {
            return existing.second
        }
        if (existing != null) {
            if (existing.first == machineId) {
                logger.info("ssh_cache_dead_closing: machineId={}", existing.first)
            } else {
                logger.info("ssh_cache_machine_changed_closing: machineId={}", existing.first)
            }
            existing.second.close()
        }
        logger.info("ssh_dial_start: machineId={}", machineId)
        val connection = helper.connect(machineId) { fingerprint, keyType ->
            hostKeyConfirmer.confirm(machine, fingerprint, keyType)
        }
        logger.info("ssh_dial_success: machineId={}", machineId)
        cached = machineId to connection
        return connection
    }

    /** Closes and drops the cached connection for [machineId], if any. */
    suspend fun evict(machineId: String) {
        mutex.withLock {
            val existing = cached
            if (existing != null && existing.first == machineId) {
                logger.info("ssh_evict_closing: machineId={}", machineId)
                cached = null
                existing.second.close()
            }
        }
    }
}
