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
import works.resolve.pathfinder.ssh.Machine
import works.resolve.pathfinder.ssh.MachineStore
import works.resolve.pathfinder.ssh.SshConnectionException
import works.resolve.pathfinder.ssh.SshConnectionHelper
import works.resolve.pathfinder.ssh.TofuHostKeyConfirmer

/**
 * The machines screen's model: a live view over [MachineStore], the
 * add/edit/remove intents, and the machine form's connection test. The store
 * owns persistence and key material; only validation and safe errors live
 * here.
 */
internal class MachinesController(
    private val scope: CoroutineScope,
    private val machineStore: MachineStore,
    private val connectionHelper: SshConnectionHelper,
    private val hostKeyConfirmer: TofuHostKeyConfirmer,
    private val onError: (message: UiString, cause: Throwable?) -> Unit
) {

    /** The source of truth [ChatUiState.machines] mirrors. */
    val machines: StateFlow<List<Machine>> =
        machineStore.machines.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _machineTest = MutableStateFlow<MachineTestState?>(null)

    /** Latest connection-test status, keyed by machine; the machine form shows only its own machine's. */
    val machineTest: StateFlow<MachineTestState?> = _machineTest.asStateFlow()

    /**
     * Dials [machineId] exactly like a tool-call connect (TOFU prompt included),
     * closes the connection right away, and publishes progress plus a safe
     * result. One test at a time; taps while running are ignored.
     */
    fun testMachineConnection(machineId: String) {
        if (_machineTest.value?.running == true) return
        scope.launch {
            _machineTest.value = MachineTestState(machineId = machineId, running = true)
            _machineTest.value = try {
                val machine =
                    machineStore.machine(machineId)
                        ?: throw SshConnectionException(
                            "Unknown machine",
                            SshConnectionException.Detail.UNKNOWN_MACHINE
                        )
                val connection = connectionHelper.connect(machineId) { fingerprint, keyType ->
                    hostKeyConfirmer.confirm(machine, fingerprint, keyType)
                }
                try {
                    connection.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The test already succeeded; a close failure is noise.
                }
                MachineTestState(
                    machineId = machineId,
                    running = false,
                    success = true,
                    message = UiString(
                        R.string.machine_test_success,
                        listOf(machine.cwd)
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SshConnectionException) {
                MachineTestState(
                    machineId = machineId,
                    running = false,
                    message = connectionError(e, machineLabel(machineId))
                )
            } catch (e: Exception) {
                logger.warn("machine_test", e)
                MachineTestState(
                    machineId = machineId,
                    running = false,
                    message = machineLabel(machineId)?.let {
                        UiString(R.string.ssh_error_connect, listOf(it))
                    } ?: UiString(R.string.ssh_error_connect_generic)
                )
            }
        }
    }

    /**
     * Creates a machine (with its freshly generated keypair, see
     * [MachineStore.addMachine]). Invalid input or a storage failure surfaces
     * a safe error and stores nothing.
     */
    fun addMachine(address: String, port: Int, username: String, cwd: String) {
        scope.launch {
            if (!valid(address, port, username, cwd)) {
                onError(UiString(R.string.error_machine_invalid), null)
                return@launch
            }
            try {
                machineStore.addMachine(address.trim(), port, username.trim(), cwd.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(UiString(R.string.error_machine_save), e)
            }
        }
    }

    /** Persists edited connection fields; the key material never changes. */
    fun updateMachine(machine: Machine) {
        scope.launch {
            if (!valid(machine.address, machine.port, machine.username, machine.cwd)) {
                onError(UiString(R.string.error_machine_invalid), null)
                return@launch
            }
            try {
                machineStore.updateMachine(machine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(UiString(R.string.error_machine_save), e)
            }
        }
    }

    /** Deletes a machine and its keypair; a failure surfaces a safe error. */
    fun removeMachine(id: String) {
        scope.launch {
            try {
                machineStore.removeMachine(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("machine_remove", e)
                onError(UiString(R.string.error_machine_remove), e)
            }
        }
    }

    private suspend fun machineLabel(machineId: String?): String? =
        machineId?.let { machineStore.machine(it) }?.let { "${it.username}@${it.address}" }

    /** Safe, actionable message for a failed test connect, naming the machine. */
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

            SshConnectionException.Detail.UNKNOWN_MACHINE ->
                UiString(R.string.ssh_error_unknown_machine)

            SshConnectionException.Detail.NO_KEY -> UiString(R.string.ssh_error_no_key)
        }
    }

    private fun valid(address: String, port: Int, username: String, cwd: String): Boolean =
        address.isNotBlank() && username.isNotBlank() && cwd.trim().startsWith("/") &&
            port in 1..65535

    private companion object {
        private val logger = LoggerFactory.getLogger(MachinesController::class.java)
    }
}
