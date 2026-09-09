package works.resolve.pathfinder.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * The process-wide SSH connection owner: the persisted machine selection
 * is the single source of truth for the effective machine, which is
 * resolved from one snapshot per use. Connections dial lazily on demand
 * and one authenticated connection is cached: a machine switch closes the
 * previous. A cached connection that is no longer authenticated is closed
 * and redialed.
 */
class SshConnectionProvider(
    private val store: MachineStore,
    private val helper: SshConnectionHelper,
    private val hostKeyConfirmer: TofuHostKeyConfirmer,
    selectedMachineId: Flow<String?>
) {
    private val mutex = Mutex()

    private var cached: Pair<String, SshConnection>? = null

    private companion object {
        private val logger = LoggerFactory.getLogger(SshConnectionProvider::class.java)
    }

    /** The machine tool calls dial and the UI shows: the selection if it still
     *  exists, else the sole machine, else the first. */
    val machine: Flow<Machine?> =
        combine(store.machines, selectedMachineId) { machines, id ->
            machines.firstOrNull { it.id == id } ?: machines.singleOrNull()
                ?: machines.firstOrNull()
        }.distinctUntilChanged()

    /** The machine tool calls dial; null when no machine is configured. */
    suspend fun currentMachine(): Machine? = machine.first()

    /**
     * The current machine's live connection, dialing when needed. A dead cache
     * entry is closed and redialed; a dial failure propagates as
     * [SshConnectionException] to the caller (the agent loop turns it into
     * an error tool result).
     */
    suspend fun connection(): SshConnection = mutex.withLock {
        val machine =
            currentMachine()
                ?: throw SshConnectionException(
                    "No machine configured",
                    SshConnectionException.Detail.UNKNOWN_MACHINE
                )
        val existing = cached
        if (existing != null && existing.first == machine.id &&
            existing.second.client.isAuthenticated
        ) {
            return existing.second
        }
        if (existing != null) {
            if (existing.first == machine.id) {
                logger.info("ssh_cache_dead_closing: machineId={}", existing.first)
            } else {
                logger.info("ssh_cache_machine_changed_closing: machineId={}", existing.first)
            }
            existing.second.close()
        }
        logger.info("ssh_dial_start: machineId={}", machine.id)
        val connection = helper.connect(machine.id) { fingerprint, keyType ->
            hostKeyConfirmer.confirm(machine, fingerprint, keyType)
        }
        logger.info("ssh_dial_success: machineId={}", machine.id)
        cached = machine.id to connection
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
