package works.resolve.pathfinder.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import org.connectbot.sshlib.SessionExit
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import works.resolve.pathfinder.codingagent.core.tools.BashOperations
import works.resolve.pathfinder.codingagent.core.tools.EditOperations
import works.resolve.pathfinder.codingagent.core.tools.IMAGE_TYPE_SNIFF_BYTES
import works.resolve.pathfinder.codingagent.core.tools.OperationsException
import works.resolve.pathfinder.codingagent.core.tools.ReadOperations
import works.resolve.pathfinder.codingagent.core.tools.WriteOperations
import works.resolve.pathfinder.codingagent.core.tools.detectSupportedImageMimeType

/** POSIX single-quoting for one shell argument. */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * Throws [OperationsException] with pi's Node error code when the server
 * reports a recognizable status, so the edit tool's "Error code:" path keeps
 * its upstream meaning.
 */
private fun sftpFailure(operation: String, statusCode: SftpStatusCode, message: String): Nothing =
    throw OperationsException(
        "$operation failed: $message",
        when (statusCode) {
            SftpStatusCode.NO_SUCH_FILE -> "ENOENT"
            SftpStatusCode.PERMISSION_DENIED -> "EACCES"
            else -> null
        }
    )

/** Safe stage-naming message for a failed dial; never includes secret material. */
private fun connectionFailureMessage(detail: SshConnectionException.Detail): String =
    when (detail) {
        SshConnectionException.Detail.UNKNOWN_HOST -> "SSH host unavailable: no host configured"
        SshConnectionException.Detail.CONNECT -> "SSH host unavailable: connect failed"
        SshConnectionException.Detail.HOST_KEY_REJECTED -> "SSH host unavailable: host key rejected"
        SshConnectionException.Detail.AUTH -> "SSH host unavailable: auth failed"
        SshConnectionException.Detail.SFTP -> "SSH host unavailable: SFTP failed"
        SshConnectionException.Detail.NO_KEY -> "SSH host unavailable: no stored key"
    }

private suspend fun connect(provider: SshConnectionProvider): SshConnection = try {
    provider.connection()
} catch (e: CancellationException) {
    throw e
} catch (e: SshConnectionException) {
    throw OperationsException(connectionFailureMessage(e.detail))
}

/**
 * Bash operations over the process-wide SSH connection, following pi's
 * ssh.ts extension: the command runs wrapped in `cd {cwd} && {command}`.
 *
 * [timeout] is left to the shell's `withTimeout` (category-3 adaptation):
 * this implementation performs no timeout handling of its own, and the
 * resulting cancellation closes the channel. Unlike pi's local shell, which
 * kills the process tree, remote termination is server-side best effort on
 * channel close.
 */
class RemoteBashOperations(private val provider: SshConnectionProvider) : BashOperations {

    override suspend fun exec(
        command: String,
        cwd: String,
        onData: (ByteArray) -> Unit,
        timeout: Double?
    ): Int? {
        val connection = connect(provider)
        val session = connection.client.openSession()
            ?: throw OperationsException("SSH session channel could not be opened")
        session.use {
            if (!it.requestExec("cd ${shellQuote(cwd)} && $command")) {
                throw OperationsException("SSH server refused command execution")
            }
            // stdout/stderr are rendezvous channels; consume both concurrently
            // or the remote blocks once one window fills.
            coroutineScope {
                var stdoutOpen = true
                var stderrOpen = true
                while (stdoutOpen || stderrOpen) {
                    select<Unit> {
                        if (stdoutOpen) {
                            it.stdout.onReceiveCatching { result ->
                                val chunk = result.getOrNull()
                                if (chunk == null) {
                                    stdoutOpen = false
                                } else {
                                    onData(chunk)
                                }
                            }
                        }
                        if (stderrOpen) {
                            it.stderr.onReceiveCatching { result ->
                                val chunk = result.getOrNull()
                                if (chunk == null) {
                                    stderrOpen = false
                                } else {
                                    onData(chunk)
                                }
                            }
                        }
                    }
                }
            }
            val exit = it.exitInfo.await()
            return (exit as? SessionExit.Status)?.code?.toInt()
        }
    }
}

/**
 * Read/write/edit operations over the process-wide SSH connection: file
 * bodies move via SFTP, recursive `mkdir` shells out because SFTP's mkdir is
 * a single-level operation. Mirrors pi's ssh.ts approach; paths are remote
 * paths and are never interpreted locally.
 */
class RemoteFileOperations(private val provider: SshConnectionProvider) :
    ReadOperations,
    WriteOperations,
    EditOperations {

    private suspend fun <T> withSftp(operation: String, block: suspend (SftpClient) -> T): T {
        val sftp = when (val opened = connect(provider).client.openSftp()) {
            is SftpResult.Success -> opened.value

            is SftpResult.ServerError ->
                sftpFailure(operation, opened.statusCode, opened.message)

            is SftpResult.ProtocolError ->
                throw OperationsException("$operation failed: ${opened.message}")

            is SftpResult.IoError ->
                throw OperationsException(
                    "$operation failed: ${opened.cause.message ?: opened.cause.toString()}"
                )
        }
        sftp.use { return block(it) }
    }

    override suspend fun readFile(absolutePath: String): ByteArray = withSftp("read") { sftp ->
        val handle = sftp.open(absolutePath, setOf(SftpOpenFlag.READ)).unwrap("open", absolutePath)
        try {
            val chunks = mutableListOf<ByteArray>()
            var offset = 0L
            while (true) {
                val chunk = sftp.read(handle, offset, READ_CHUNK_BYTES).unwrap("read", absolutePath)
                    ?: break
                if (chunk.isEmpty()) break
                chunks += chunk
                offset += chunk.size
            }
            val out = ByteArray(chunks.sumOf { it.size })
            var pos = 0
            for (chunk in chunks) {
                chunk.copyInto(out, pos)
                pos += chunk.size
            }
            out
        } finally {
            sftp.close(handle)
        }
    }

    override suspend fun access(absolutePath: String) {
        withSftp("stat") { sftp -> sftp.stat(absolutePath).unwrap("stat", absolutePath) }
    }

    /** Sniffs the remote head; a failed sniff is not an image (pi's ssh.ts returns null on error), but cancellation propagates. */
    override suspend fun detectImageMimeType(absolutePath: String): String? = try {
        withSftp("read") { sftp ->
            val handle = sftp.open(
                absolutePath,
                setOf(SftpOpenFlag.READ)
            ).unwrap("open", absolutePath)
            try {
                val head = sftp.read(handle, 0, IMAGE_TYPE_SNIFF_BYTES).unwrap("read", absolutePath)
                head?.let(::detectSupportedImageMimeType)
            } finally {
                sftp.close(handle)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun writeFile(absolutePath: String, content: String) {
        val data = content.toByteArray(Charsets.UTF_8)
        withSftp("write") { sftp ->
            val handle =
                sftp
                    .open(
                        absolutePath,
                        setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.TRUNCATE)
                    )
                    .unwrap("open", absolutePath)
            try {
                sftp.write(handle, 0, data).unwrap("write", absolutePath)
            } finally {
                sftp.close(handle)
            }
        }
    }

    override suspend fun mkdir(dir: String) {
        val exit = RemoteBashOperations(provider).exec(
            "mkdir -p ${shellQuote(dir)}",
            "/",
            {},
            null
        )
        if (exit != 0) {
            throw OperationsException("mkdir failed on remote host")
        }
    }

    private fun <T> SftpResult<T>.unwrap(operation: String, path: String): T = when (this) {
        is SftpResult.Success -> value

        is SftpResult.ServerError ->
            sftpFailure("$operation '$path'", statusCode, message)

        is SftpResult.ProtocolError ->
            throw OperationsException("$operation '$path' failed: $message")

        is SftpResult.IoError ->
            throw OperationsException(
                "$operation '$path' failed: ${cause.message ?: cause.toString()}"
            )
    }

    private companion object {
        const val READ_CHUNK_BYTES = 32768
    }
}
