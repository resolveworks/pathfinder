package works.resolve.pathfinder.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig

/** Connection setup failed; [detail] names the stage without secret material. */
class SshConnectionException(message: String, val detail: Detail) : Exception(message) {
    enum class Detail { UNKNOWN_HOST, CONNECT, HOST_KEY_REJECTED, AUTH, NO_KEY }
}

/**
 * One authenticated SSH connection. The holder must call [close] when
 * done — cbssh exposes no pooling or reconnect.
 */
class SshConnection internal constructor(val host: SshHost, val client: SshClient) {
    suspend fun close() {
        withContext(Dispatchers.IO) { client.disconnect() }
    }
}

/**
 * Establishes SSH connections from stored host configs. Publickey is the
 * only authentication ever wired: no password or keyboard-interactive path
 * exists here.
 *
 * Main-safe by ownership: cbssh's Ktor transport dials on the caller's
 * dispatcher (only DNS is confined internally), so this seam owns
 * [Dispatchers.IO] for dial and disconnect. Every connection path in the
 * app — the host-form test on Main, tool dials via the provider — goes
 * through here; callers may dial from any dispatcher.
 */
class SshConnectionHelper(private val store: SshHostStore) {

    /**
     * Connects to [hostId] and authenticates with the host's stored key.
     * The unknown-host decision is delegated to [onUnknownHostKey]
     * (fail-closed by default). On any failure the client is disconnected
     * and nothing is returned.
     */
    suspend fun connect(
        hostId: String,
        onUnknownHostKey: UnknownHostKeyCallback = UnknownHostKeyCallback.REFUSE
    ): SshConnection = withContext(Dispatchers.IO) {
        val host =
            store.host(hostId)
                ?: throw SshConnectionException(
                    "Unknown SSH host",
                    SshConnectionException.Detail.UNKNOWN_HOST
                )
        val key =
            store.privateKey(hostId)
                ?: throw SshConnectionException(
                    "No stored key for SSH host",
                    SshConnectionException.Detail.NO_KEY
                )

        val verifier = TofuHostKeyVerifier(store, hostId, onUnknownHostKey)
        val client =
            SshClient(
                SshClientConfig {
                    this.host = host.address
                    this.port = host.port
                    this.hostKeyVerifier = verifier
                    autoDisconnectOnLastChannelClose = false
                }
            )
        try {
            when (val result = client.connect()) {
                is ConnectResult.Success -> {}

                is ConnectResult.HostKeyRejected ->
                    throw SshConnectionException(
                        "Host key rejected for ${host.address}",
                        SshConnectionException.Detail.HOST_KEY_REJECTED
                    )

                else ->
                    throw SshConnectionException(
                        "Could not connect to ${host.address}:${host.port}: $result",
                        SshConnectionException.Detail.CONNECT
                    )
            }

            when (val auth = client.authenticatePublicKey(host.username, key.pem, null)) {
                is AuthResult.Success -> {}

                is AuthResult.Failure ->
                    throw SshConnectionException(
                        "Public key authentication failed for ${host.username}@${host.address}",
                        SshConnectionException.Detail.AUTH
                    )

                is AuthResult.Error ->
                    throw SshConnectionException(
                        "Authentication error: ${auth.message}",
                        SshConnectionException.Detail.AUTH
                    )
            }

            SshConnection(host = host, client = client)
        } catch (error: Exception) {
            client.disconnect()
            throw error
        }
    }
}
