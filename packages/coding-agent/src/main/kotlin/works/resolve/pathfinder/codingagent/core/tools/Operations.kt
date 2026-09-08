package works.resolve.pathfinder.codingagent.core.tools

/**
 * Exception operations may throw when a filesystem-level error occurs.
 * [code] mirrors pi's Node error codes (`ENOENT`, …) in the edit tool's
 * "Error code:" failure message; the upstream check `"code" in error` has no
 * Kotlin equivalent on arbitrary throwables.
 */
class OperationsException(message: String, val code: String? = null) : Exception(message)

/** Formats an operations failure the way pi's edit tool reports it. */
internal fun operationsErrorMessage(error: Throwable): String = if (error is OperationsException &&
    error.code != null
) {
    "Error code: ${error.code}"
} else {
    (error.message ?: error.toString())
}

/**
 * Pluggable operation seams for the ported coding tools (read, write, edit,
 * bash). Overriding the default local-filesystem behavior is how pi's SSH
 * extension delegates to a remote machine; pathfinder keeps the same seam so
 * the app can supply local or SSH-backed implementations.
 *
 * Divergence from pi: this module is platform-neutral, so unlike upstream no
 * default local implementations exist here — callers must always provide
 * operations.
 */
interface ReadOperations {
    /** Read file contents as raw bytes. */
    suspend fun readFile(absolutePath: String): ByteArray

    /** Check if file is readable; throw if not. */
    suspend fun access(absolutePath: String)

    /**
     * Detect a supported image MIME type, or null for non-images. Pi models
     * this as an optional member: the default here plays the absent member,
     * and an absent member and a null result both skip the image path.
     */
    suspend fun detectImageMimeType(absolutePath: String): String? = null
}

interface WriteOperations {
    /** Write content to a file. */
    suspend fun writeFile(absolutePath: String, content: ByteArray)

    /** Create a directory recursively (pi's `mkdir(path, {recursive: true})`). */
    suspend fun mkdir(dir: String)
}

interface EditOperations {
    /**
     * Read file contents. Divergence from pi: pi reads a Buffer and decodes
     * UTF-8 in the tool; here decoding is part of the operation.
     */
    suspend fun readFile(absolutePath: String): String

    /** Write content to a file. */
    suspend fun writeFile(absolutePath: String, content: String)

    /** Check if file is readable and writable; throw if not. */
    suspend fun access(absolutePath: String)
}

interface BashOperations {
    /**
     * Execute a command and stream output, returning the exit code (null when
     * the process was killed without an exit status).
     *
     * [onData] is the merge point for stdout and stderr: implementations
     * forward interleaved chunks in arrival order, and the shell just
     * accumulates. [timeout] is in (whole) seconds — the truncation from pi's
     * fractional seconds is the price of the Long seam. Implementations must
     * propagate coroutine cancellation, and may fail with message "aborted" or
     * "timeout:<seconds>" to preserve pi's local-shell error contract. Pi's
     * `env` member is deliberately absent: its only producers/consumers are
     * the unported session-environment feature and local shell machinery.
     */
    suspend fun exec(
        command: String,
        cwd: String,
        onData: (ByteArray) -> Unit,
        timeout: Long?
    ): Int?
}
