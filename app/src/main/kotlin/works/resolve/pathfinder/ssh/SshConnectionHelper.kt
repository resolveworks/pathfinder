package works.resolve.pathfinder.ssh

import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshClient

/** Connection setup failed; [detail] names the stage without secret material. */
class SshConnectionException(message: String, val detail: Detail) : Exception(message) {
    enum class Detail { UNKNOWN_HOST, CONNECT, HOST_KEY_REJECTED, AUTH, SFTP, NO_KEY }
}

/**
 * One authenticated SSH connection, owned by exactly one session. The holder
 * must call [close] (once the session ends) — cbssh exposes no pooling or
 * reconnect, and the underlying [client] must not be shared.
 */
class SshConnection
internal constructor(
    val host: SshHost,
    val client: SshClient,
    /** Remote working directory at connect time (`sftp.realpath(".")`). */
    val initialWorkingDirectory: String
) {
    suspend fun close() {
        client.disconnect()
    }
}

/**
 * Establishes per-session SSH connections from stored host configs.
 * Publickey is the only authentication ever wired: no password or
 * keyboard-interactive path exists here.
 */
class SshConnectionHelper(private val store: SshHostStore) {

    /**
     * Connects to [hostId], authenticates with the host's stored key, and
     * resolves the initial remote working directory. The unknown-host decision
     * is delegated to [onUnknownHostKey] (fail-closed by default). On any
     * failure the client is disconnected and nothing is returned.
     */
    suspend fun connect(
        hostId: String,
        onUnknownHostKey: UnknownHostKeyCallback = UnknownHostKeyCallback.REFUSE
    ): SshConnection {
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
        val client = SshClient(host = host.address, hostKeyVerifier = verifier, port = host.port)
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

            val cwd = resolveWorkingDirectory(client, host)
            return SshConnection(host = host, client = client, initialWorkingDirectory = cwd)
        } catch (error: Exception) {
            client.disconnect()
            throw error
        }
    }

    private suspend fun resolveWorkingDirectory(client: SshClient, host: SshHost): String {
        val sftp =
            when (val result = client.openSftp()) {
                is SftpResult.Success -> result.value

                is SftpResult.IoError ->
                    throw SshConnectionException(
                        "SFTP error: ${result.cause.message}",
                        SshConnectionException.Detail.SFTP
                    )

                else ->
                    throw SshConnectionException(
                        "Server does not provide SFTP",
                        SshConnectionException.Detail.SFTP
                    )
            }
        sftp.use {
            return when (val path = it.realpath(".")) {
                is SftpResult.Success -> path.value

                else ->
                    throw SshConnectionException(
                        "Could not resolve remote working directory",
                        SshConnectionException.Detail.SFTP
                    )
            }
        }
    }
}
