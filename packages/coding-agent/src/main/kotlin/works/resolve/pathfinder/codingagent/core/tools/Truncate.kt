package works.resolve.pathfinder.codingagent.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared truncation utilities for tool outputs.
 *
 * Truncation is based on two independent limits — whichever is hit first
 * wins:
 * - Line limit (default: 2000 lines)
 * - Byte limit (default: 50KB)
 *
 * Never returns partial lines (except bash tail truncation edge case).
 */

const val DEFAULT_MAX_LINES = 2000

const val DEFAULT_MAX_BYTES = 50 * 1024 // 50KB

/** Max chars per grep match line (grep itself is unported; kept for [truncateLine]). */
const val GREP_MAX_LINE_LENGTH = 500

class TruncationOptions(
    val maxLines: Int = DEFAULT_MAX_LINES,
    val maxBytes: Int = DEFAULT_MAX_BYTES
)

data class TruncationResult(
    /** The truncated content. */
    val content: String,
    /** Whether truncation occurred. */
    val truncated: Boolean,
    /** Which limit was hit: "lines", "bytes", or null if not truncated. */
    val truncatedBy: String?,
    /** Total number of lines in the original content. */
    val totalLines: Int,
    /** Total number of bytes in the original content. */
    val totalBytes: Int,
    /** Number of complete lines in the truncated output. */
    val outputLines: Int,
    /** Number of bytes in the truncated output. */
    val outputBytes: Int,
    /** Whether the last line was partially truncated (only for tail truncation edge case). */
    val lastLinePartial: Boolean,
    /** Whether the first line exceeded the byte limit (for head truncation). */
    val firstLineExceedsLimit: Boolean,
    /** The max lines limit that was applied. */
    val maxLines: Int,
    /** The max bytes limit that was applied. */
    val maxBytes: Int
) {
    /** Wire shape of pi's TruncationResult for tool details. */
    fun toJson(): JsonObject = buildJsonObject {
        put("content", content)
        put("truncated", truncated)
        truncatedBy?.let { put("truncatedBy", it) }
        put("totalLines", totalLines)
        put("totalBytes", totalBytes)
        put("outputLines", outputLines)
        put("outputBytes", outputBytes)
        put("lastLinePartial", lastLinePartial)
        put("firstLineExceedsLimit", firstLineExceedsLimit)
        put("maxLines", maxLines)
        put("maxBytes", maxBytes)
    }
}

/** Length of the UTF-8 encoding of [content]. */
internal fun utf8ByteLength(content: String): Int = content.toByteArray(Charsets.UTF_8).size

private fun splitLinesForCounting(content: String): List<String> {
    if (content.isEmpty()) {
        return emptyList()
    }
    val lines = content.split("\n").toMutableList()
    if (content.endsWith("\n")) {
        lines.removeAt(lines.size - 1)
    }
    return lines
}

/** Format bytes as human-readable size. */
fun formatSize(bytes: Int): String = formatSize(bytes.toDouble())

fun formatSize(bytes: Long): String = formatSize(bytes.toDouble())

private fun formatSize(bytes: Double): String = when {
    bytes < 1024 -> "${bytes.toInt()}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(java.util.Locale.ROOT, bytes / 1024)
    else -> "%.1fMB".format(java.util.Locale.ROOT, bytes / (1024 * 1024))
}

/**
 * Truncate content from the head (keep first N lines/bytes). Suitable for
 * file reads where you want to see the beginning.
 *
 * Never returns partial lines. If the first line exceeds the byte limit,
 * returns empty content with firstLineExceedsLimit=true.
 */
fun truncateHead(
    content: String,
    options: TruncationOptions = TruncationOptions()
): TruncationResult {
    val maxLines = options.maxLines
    val maxBytes = options.maxBytes

    val totalBytes = utf8ByteLength(content)
    val lines = splitLinesForCounting(content)
    val totalLines = lines.size

    // Check if no truncation needed
    if (totalLines <= maxLines && totalBytes <= maxBytes) {
        return TruncationResult(
            content, truncated = false, truncatedBy = null, totalLines, totalBytes,
            outputLines = totalLines, outputBytes = totalBytes,
            lastLinePartial = false, firstLineExceedsLimit = false, maxLines, maxBytes
        )
    }

    // Check if first line alone exceeds byte limit
    val firstLineBytes = utf8ByteLength(lines[0])
    if (firstLineBytes > maxBytes) {
        return TruncationResult(
            "", truncated = true, truncatedBy = "bytes", totalLines, totalBytes,
            outputLines = 0, outputBytes = 0,
            lastLinePartial = false, firstLineExceedsLimit = true, maxLines, maxBytes
        )
    }

    // Collect complete lines that fit
    val outputLinesArr = mutableListOf<String>()
    var outputBytesCount = 0
    var truncatedBy = "lines"

    var i = 0
    while (i < lines.size && i < maxLines) {
        val line = lines[i]
        val lineBytes = utf8ByteLength(line) + if (i > 0) 1 else 0 // +1 for newline

        if (outputBytesCount + lineBytes > maxBytes) {
            truncatedBy = "bytes"
            break
        }

        outputLinesArr.add(line)
        outputBytesCount += lineBytes
        i++
    }

    // If we exited due to line limit
    if (outputLinesArr.size >= maxLines && outputBytesCount <= maxBytes) {
        truncatedBy = "lines"
    }

    val outputContent = outputLinesArr.joinToString("\n")
    val finalOutputBytes = utf8ByteLength(outputContent)

    return TruncationResult(
        outputContent, truncated = true, truncatedBy, totalLines, totalBytes,
        outputLines = outputLinesArr.size, outputBytes = finalOutputBytes,
        lastLinePartial = false, firstLineExceedsLimit = false, maxLines, maxBytes
    )
}

/**
 * Truncate content from the tail (keep last N lines/bytes). Suitable for bash
 * output where you want to see the end (errors, final results).
 *
 * May return a partial first line if the last line of the original content
 * exceeds the byte limit.
 */
fun truncateTail(
    content: String,
    options: TruncationOptions = TruncationOptions()
): TruncationResult {
    val maxLines = options.maxLines
    val maxBytes = options.maxBytes

    val totalBytes = utf8ByteLength(content)
    val lines = splitLinesForCounting(content)
    val totalLines = lines.size

    // Check if no truncation needed
    if (totalLines <= maxLines && totalBytes <= maxBytes) {
        return TruncationResult(
            content, truncated = false, truncatedBy = null, totalLines, totalBytes,
            outputLines = totalLines, outputBytes = totalBytes,
            lastLinePartial = false, firstLineExceedsLimit = false, maxLines, maxBytes
        )
    }

    // Work backwards from the end
    val outputLinesArr = mutableListOf<String>()
    var outputBytesCount = 0
    var truncatedBy = "lines"
    var lastLinePartial = false

    var i = lines.size - 1
    while (i >= 0 && outputLinesArr.size < maxLines) {
        val line = lines[i]
        // +1 for newline
        val lineBytes = utf8ByteLength(line) + if (outputLinesArr.isNotEmpty()) 1 else 0

        if (outputBytesCount + lineBytes > maxBytes) {
            truncatedBy = "bytes"
            // Edge case: if we haven't added ANY lines yet and this line exceeds maxBytes,
            // take the end of the line (partial)
            if (outputLinesArr.isEmpty()) {
                val truncatedLine = truncateStringToBytesFromEnd(line, maxBytes)
                outputLinesArr.add(0, truncatedLine)
                outputBytesCount = utf8ByteLength(truncatedLine)
                lastLinePartial = true
            }
            break
        }

        outputLinesArr.add(0, line)
        outputBytesCount += lineBytes
        i--
    }

    // If we exited due to line limit
    if (outputLinesArr.size >= maxLines && outputBytesCount <= maxBytes) {
        truncatedBy = "lines"
    }

    val outputContent = outputLinesArr.joinToString("\n")
    val finalOutputBytes = utf8ByteLength(outputContent)

    return TruncationResult(
        outputContent, truncated = true, truncatedBy, totalLines, totalBytes,
        outputLines = outputLinesArr.size, outputBytes = finalOutputBytes,
        lastLinePartial, firstLineExceedsLimit = false, maxLines, maxBytes
    )
}

/**
 * Truncate a string to fit within a byte limit (from the end). Handles
 * multi-byte UTF-8 characters correctly.
 */
private fun truncateStringToBytesFromEnd(str: String, maxBytes: Int): String {
    val buf = str.toByteArray(Charsets.UTF_8)
    if (buf.size <= maxBytes) {
        return str
    }

    // Start from the end, skip maxBytes back
    var start = buf.size - maxBytes

    // Find a valid UTF-8 boundary (start of a character)
    while (start < buf.size && (buf[start].toInt() and 0xc0) == 0x80) {
        start++
    }

    return String(buf, start, buf.size - start, Charsets.UTF_8)
}

/**
 * Truncate a single line to max characters, adding a "[truncated]" suffix.
 * Used for grep match lines.
 */
fun truncateLine(line: String, maxChars: Int = GREP_MAX_LINE_LENGTH): TruncatedLine =
    if (line.length <= maxChars) {
        TruncatedLine(line, wasTruncated = false)
    } else {
        TruncatedLine(line.take(maxChars) + "... [truncated]", wasTruncated = true)
    }

data class TruncatedLine(val text: String, val wasTruncated: Boolean)
