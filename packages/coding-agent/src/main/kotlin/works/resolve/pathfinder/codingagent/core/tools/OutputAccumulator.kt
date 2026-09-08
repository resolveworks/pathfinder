package works.resolve.pathfinder.codingagent.core.tools

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

class OutputAccumulatorOptions(
    val maxLines: Int = DEFAULT_MAX_LINES,
    val maxBytes: Int = DEFAULT_MAX_BYTES,
    val tempFilePrefix: String = "pi-output"
)

class OutputSnapshot(
    val content: String,
    val truncation: TruncationResult,
    val fullOutputPath: String?
)

private fun defaultTempFilePath(tempDir: String, prefix: String): String {
    val id = ByteArray(8)
    SECURE_RANDOM.nextBytes(id)
    val hex = buildString(16) {
        for (byte in id) {
            append("0123456789abcdef"[byte.toInt() shr 4 and 0xf])
            append("0123456789abcdef"[byte.toInt() and 0xf])
        }
    }
    return "$tempDir/$prefix-$hex.log"
}

private val SECURE_RANDOM = SecureRandom()

/**
 * Incrementally tracks streaming output with bounded memory.
 *
 * Appends decode chunks with a streaming UTF-8 decoder, keeps only a decoded
 * tail for display snapshots, and opens a temp file when the full output needs
 * to be preserved.
 *
 * Divergence from pi: the temp directory is injected as a constructor
 * parameter (upstream uses `os.tmpdir()`; the app passes `cacheDir`). All
 * methods are synchronized because chunks arrive from the exec callback while
 * snapshots are taken from the tool coroutine; temp-file writes are
 * synchronous.
 */
class OutputAccumulator(
    private val tempDir: String,
    options: OutputAccumulatorOptions = OutputAccumulatorOptions()
) {
    private val maxLines = options.maxLines
    private val maxBytes = options.maxBytes
    private val maxRollingBytes = maxOf(maxBytes * 2, 1)
    private val tempFilePrefix = options.tempFilePrefix
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var rawChunks = mutableListOf<ByteArray>()
    private var tailText = ""
    private var tailBytes = 0
    private var tailStartsAtLineBoundary = true
    private var totalRawBytes = 0
    private var totalDecodedBytes = 0
    private var completedLines = 0
    private var totalLines = 0
    private var currentLineBytes = 0
    private var hasOpenLine = false
    private var finished = false

    private var tempFilePath: String? = null
    private var tempFileStream: FileOutputStream? = null

    @Synchronized
    fun append(data: ByteArray) {
        check(!finished) { "Cannot append to a finished output accumulator" }

        totalRawBytes += data.size
        appendDecodedText(decodeChunk(data, endOfInput = false))

        if (tempFileStream != null || shouldUseTempFile()) {
            ensureTempFile()
            tempFileStream?.write(data)
        } else if (data.isNotEmpty()) {
            rawChunks.add(data)
        }
    }

    @Synchronized
    fun finish() {
        if (finished) {
            return
        }
        finished = true
        appendDecodedText(decodeChunk(ByteArray(0), endOfInput = true))
        if (shouldUseTempFile()) {
            ensureTempFile()
        }
    }

    @Synchronized
    fun snapshot(persistIfTruncated: Boolean = false): OutputSnapshot {
        val tailTruncation = truncateTail(getSnapshotText(), TruncationOptions(maxLines, maxBytes))
        val truncated = totalLines > maxLines || totalDecodedBytes > maxBytes
        val truncatedBy = if (truncated) {
            tailTruncation.truncatedBy ?: (if (totalDecodedBytes > maxBytes) "bytes" else "lines")
        } else {
            null
        }
        val truncation = TruncationResult(
            content = tailTruncation.content,
            truncated = truncated,
            truncatedBy = truncatedBy,
            totalLines = totalLines,
            totalBytes = totalDecodedBytes,
            outputLines = tailTruncation.outputLines,
            outputBytes = tailTruncation.outputBytes,
            lastLinePartial = tailTruncation.lastLinePartial,
            firstLineExceedsLimit = tailTruncation.firstLineExceedsLimit,
            maxLines = maxLines,
            maxBytes = maxBytes
        )

        if (persistIfTruncated && truncation.truncated) {
            ensureTempFile()
        }

        return OutputSnapshot(truncation.content, truncation, tempFilePath)
    }

    @Synchronized
    fun closeTempFile() {
        tempFileStream?.let { stream ->
            tempFileStream = null
            stream.close()
        }
    }

    @Synchronized
    fun getLastLineBytes(): Int = currentLineBytes

    private fun decodeChunk(data: ByteArray, endOfInput: Boolean): String {
        if (data.isEmpty() && !endOfInput) return ""
        val input = ByteBuffer.wrap(data)
        val sb = StringBuilder()
        val out = CharBuffer.allocate(maxOf(64, data.size + 16))
        while (true) {
            out.clear()
            val result = decoder.decode(input, out, endOfInput)
            out.flip()
            sb.append(out)
            if (!result.isOverflow) {
                break
            }
        }
        return sb.toString()
    }

    private fun appendDecodedText(text: String) {
        if (text.isEmpty()) {
            return
        }

        val bytes = utf8ByteLength(text)
        totalDecodedBytes += bytes
        tailText += text
        tailBytes += bytes
        if (tailBytes > maxRollingBytes * 2) {
            trimTail()
        }

        var newlines = 0
        var lastNewline = -1
        var i = text.indexOf('\n')
        while (i != -1) {
            newlines++
            lastNewline = i
            i = text.indexOf('\n', i + 1)
        }
        if (newlines == 0) {
            currentLineBytes += bytes
            hasOpenLine = true
        } else {
            completedLines += newlines
            val tail = text.substring(lastNewline + 1)
            currentLineBytes = utf8ByteLength(tail)
            hasOpenLine = tail.isNotEmpty()
        }
        totalLines = completedLines + if (hasOpenLine) 1 else 0
    }

    private fun trimTail() {
        val buffer = tailText.toByteArray(Charsets.UTF_8)
        if (buffer.size <= maxRollingBytes) {
            tailBytes = buffer.size
            return
        }

        var start = buffer.size - maxRollingBytes
        while (start < buffer.size && (buffer[start].toInt() and 0xc0) == 0x80) {
            start++
        }

        tailStartsAtLineBoundary =
            if (start == 0) tailStartsAtLineBoundary else buffer[start - 1] == 0x0a.toByte()
        tailText = String(buffer, start, buffer.size - start, Charsets.UTF_8)
        tailBytes = utf8ByteLength(tailText)
    }

    private fun getSnapshotText(): String {
        if (tailStartsAtLineBoundary) {
            return tailText
        }

        val firstNewline = tailText.indexOf('\n')
        return if (firstNewline == -1) tailText else tailText.substring(firstNewline + 1)
    }

    private fun shouldUseTempFile(): Boolean =
        totalRawBytes > maxBytes || totalDecodedBytes > maxBytes || totalLines > maxLines

    private fun ensureTempFile() {
        if (tempFilePath != null) {
            return
        }
        tempFilePath = defaultTempFilePath(tempDir, tempFilePrefix)
        tempFileStream = FileOutputStream(File(tempFilePath))
        for (chunk in rawChunks) {
            tempFileStream?.write(chunk)
        }
        rawChunks = mutableListOf()
    }
}
