package works.resolve.pathfinder.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig

/** Connection setup failed; [detail] names the stage without secret material. */
class SshConnectionException(message: String, val detail: Detail) : Exception(message) {
    enum class Detail { UNKNOWN_MACHINE, CONNECT, HOST_KEY_REJECTED, AUTH, NO_KEY }
}

/**
 * One authenticated SSH connection. The holder must call [close] when
 * done — cbssh exposes no pooling or reconnect.
 */
class SshConnection internal constructor(val machine: Machine, val client: SshClient) {
    suspend fun close() {
        withContext(Dispatchers.IO) { client.disconnect() }
    }
}

/**
 * Establishes SSH connections from stored machine configs. Publickey is the
 * only authentication ever wired: no password or keyboard-interactive path
 * exists here.
 *
 * Main-safe by ownership: cbssh's Ktor transport dials on the caller's
 * dispatcher (only DNS is confined internally), so this seam owns
 * [Dispatchers.IO] for dial and disconnect. Every connection path in the
 * app — the machine-form test on Main, tool dials via the provider — goes
 * through here; callers may dial from any dispatcher.
 */
class SshConnectionHelper(private val store: MachineStore) {

    /**
     * Connects to [machineId] and authenticates with the machine's stored key.
     * The unknown-host decision is delegated to [onUnknownHostKey]
     * (fail-closed by default). On any failure the client is disconnected
     * and nothing is returned.
     */
    suspend fun connect(
        machineId: String,
        onUnknownHostKey: UnknownHostKeyCallback = UnknownHostKeyCallback.REFUSE
    ): SshConnection = withContext(Dispatchers.IO) {
        val machine =
            store.machine(machineId)
                ?: throw SshConnectionException(
                    "Unknown machine",
                    SshConnectionException.Detail.UNKNOWN_MACHINE
                )
        val key =
            store.privateKey(machineId)
                ?: throw SshConnectionException(
                    "No stored key for machine",
                    SshConnectionException.Detail.NO_KEY
                )

        val verifier = TofuHostKeyVerifier(store, machineId, onUnknownHostKey)
        val client =
            SshClient(
                SshClientConfig {
                    this.host = machine.address
                    this.port = machine.port
                    this.hostKeyVerifier = verifier
                    autoDisconnectOnLastChannelClose = false
                }
            )
        try {
            when (val result = client.connect()) {
                is ConnectResult.Success -> {}

                is ConnectResult.HostKeyRejected ->
                    throw SshConnectionException(
                        "Host key rejected for ${machine.address}",
                        SshConnectionException.Detail.HOST_KEY_REJECTED
                    )

                else ->
                    throw SshConnectionException(
                        "Could not connect to ${machine.address}:${machine.port}: $result",
                        SshConnectionException.Detail.CONNECT
                    )
            }

            when (val auth = client.authenticatePublicKey(machine.username, key.pem, null)) {
                is AuthResult.Success -> {}

                is AuthResult.Failure ->
                    throw SshConnectionException(
                        "Public key authentication failed for ${machine.username}@${machine.address}",
                        SshConnectionException.Detail.AUTH
                    )

                is AuthResult.Error ->
                    throw SshConnectionException(
                        "Authentication error: ${auth.message}",
                        SshConnectionException.Detail.AUTH
                    )
            }

            SshConnection(machine = machine, client = client)
        } catch (error: Exception) {
            client.disconnect()
            throw error
        }
    }
}
