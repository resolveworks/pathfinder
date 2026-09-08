package works.resolve.pathfinder.codingagent.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.utils.double
import works.resolve.pathfinder.ai.utils.str

class ReadToolOptions(
    val operations: ReadOperations,
    /** Whether to auto-resize images to 2000x2000 max. Default: true */
    val autoResizeImages: Boolean = true,
    /**
     * Platform codec for the image path. Default null models pi's
     * "Photon not available": conversion and (auto-)resizing then fail with
     * pi's omission notes.
     */
    val imageProcessing: ImageProcessing? = null,
    /**
     * Supplies the current model for the non-vision image note. Upstream
     * reads `ctx.model` from the extension context and returns no note for an
     * undefined model, which the default `{ null }` preserves when unwired.
     */
    val modelProvider: () -> Model? = { null }
)

class ReadToolInput(val path: String, val offset: Double?, val limit: Double?)

/** Details for UI renderers: the read tool's truncation, when one occurred. */
class ReadToolDetails(val truncation: TruncationResult) {
    fun toJson(): JsonObject = buildJsonObject { put("truncation", truncation.toJson()) }
}

private fun getNonVisionImageNote(model: Model?): String? =
    if (model == null || model.input.contains(InputModality.IMAGE)) {
        null
    } else {
        "[Current model does not support images. The image will be omitted from this request.]"
    }

/**
 * The read tool shell. Divergences from pi: [ReadOperations] are required
 * (no default local filesystem — this module is platform-neutral);
 * `ExtensionContext` (`ctx.cwd`, `ctx.model`) is unported extension machinery,
 * so cwd is the constructor parameter and the model comes from
 * [ReadToolOptions.modelProvider]; and pi's AbortSignal is coroutine
 * cancellation, which propagates naturally at the operations' suspension
 * points (the agent loop surfaces it with the same "Operation aborted"
 * outcome pi's reject produces).
 */
class ReadTool internal constructor(private val cwd: String, private val options: ReadToolOptions) :
    AgentTool {

    override val definition: Tool = Tool(
        name = NAME,
        description =
            "Read the contents of a file. Supports text files and images (jpg, png, gif, " +
                "webp, bmp). Images are sent as attachments. For text files, output is truncated " +
                "to $DEFAULT_MAX_LINES lines or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit " +
                "first). Use offset/limit for large files. When you need the full file, continue " +
                "with offset until complete.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Path to the file to read (relative or absolute)")
                        }
                    )
                    put(
                        "offset",
                        buildJsonObject {
                            put("type", "number")
                            put("description", "Line number to start reading from (1-indexed)")
                        }
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", "number")
                            put("description", "Maximum number of lines to read")
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"))))
        }
    )

    override val label: String = NAME

    override val promptSnippet: String = "Read file contents"

    override val promptGuidelines: List<String> =
        listOf("Use read to examine files instead of cat or sed.")

    override fun validateArguments(arguments: JsonObject): JsonObject {
        requireString(arguments, "path")
        arguments.double("offset")?.let { offset ->
            require(offset.isFinite()) { "read: 'offset' must be a number" }
        }
        arguments.double("limit")?.let { limit ->
            require(limit.isFinite()) { "read: 'limit' must be a number" }
        }
        return arguments
    }

    override suspend fun execute(
        toolCallId: String,
        arguments: JsonObject,
        onUpdate: (AgentToolResult) -> Unit
    ): AgentToolResult {
        val input = decodeInput(arguments)
        val ops = options.operations

        val absolutePath = resolveToCwd(input.path, cwd)
        // Check if file exists and is readable.
        ops.access(absolutePath)
        val mimeType = ops.detectImageMimeType(absolutePath)
        val nonVisionImageNote = getNonVisionImageNote(options.modelProvider())
        if (mimeType != null) {
            // Read image as binary.
            val buffer = ops.readFile(absolutePath)
            val processed = processImage(
                options.imageProcessing,
                buffer,
                mimeType,
                autoResizeImages = options.autoResizeImages
            )
            return when (processed) {
                is ProcessImageResult.Failed -> {
                    var textNote = "Read image file [$mimeType]\n${processed.message}"
                    if (nonVisionImageNote != null) textNote += "\n$nonVisionImageNote"
                    AgentToolResult(content = listOf(TextContent(textNote)))
                }

                is ProcessImageResult.Ok -> {
                    var textNote = "Read image file [${processed.mimeType}]"
                    if (processed.hints.isNotEmpty()) {
                        textNote +=
                            "\n${processed.hints.joinToString("\n")}"
                    }
                    if (nonVisionImageNote != null) textNote += "\n$nonVisionImageNote"
                    AgentToolResult(
                        content = listOf(
                            TextContent(textNote),
                            ImageContent(processed.data, processed.mimeType)
                        )
                    )
                }
            }
        }

        // Read text content.
        val buffer = ops.readFile(absolutePath)
        val textContent = String(buffer, Charsets.UTF_8)
        val allLines = textContent.split("\n")
        val totalFileLines = allLines.size
        // Apply offset if specified. Convert from 1-indexed input to 0-indexed array access.
        val startLine = if (input.offset != null &&
            input.offset != 0.0
        ) {
            maxOf(0, (input.offset - 1).toInt())
        } else {
            0
        }
        val startLineDisplay = startLine + 1
        // Check if offset is out of bounds.
        if (startLine >= allLines.size) {
            throw IllegalStateException(
                "Offset ${jsNumber(
                    input.offset
                )} is beyond end of file ($totalFileLines lines total)"
            )
        }
        var selectedContent: String
        var userLimitedLines: Int? = null
        // If limit is specified by the user, honor it first. Otherwise truncateHead decides.
        if (input.limit != null) {
            val endLine = minOf(startLine + input.limit, totalFileLines.toDouble()).toInt()
            selectedContent = allLines.subList(startLine, endLine).joinToString("\n")
            userLimitedLines = endLine - startLine
        } else {
            selectedContent = allLines.subList(startLine, allLines.size).joinToString("\n")
        }
        // Apply truncation, respecting both line and byte limits.
        val truncation = truncateHead(selectedContent)
        var details: ReadToolDetails? = null
        val outputText: String
        if (truncation.firstLineExceedsLimit) {
            // First line alone exceeds the byte limit. Point the model at a bash fallback.
            val firstLineSize = formatSize(utf8ByteLength(allLines[startLine]))
            outputText =
                "[Line $startLineDisplay is $firstLineSize, exceeds ${formatSize(
                    DEFAULT_MAX_BYTES
                )} limit. " +
                "Use bash: sed -n '${startLineDisplay}p' " +
                "${input.path} | head -c $DEFAULT_MAX_BYTES]"
            details = ReadToolDetails(truncation)
        } else if (truncation.truncated) {
            // Truncation occurred. Build an actionable continuation notice.
            val endLineDisplay = startLineDisplay + truncation.outputLines - 1
            val nextOffset = endLineDisplay + 1
            outputText = truncation.content +
                if (truncation.truncatedBy == "lines") {
                    "\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalFileLines. " +
                        "Use offset=$nextOffset to continue.]"
                } else {
                    "\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalFileLines " +
                        "(${formatSize(
                            DEFAULT_MAX_BYTES
                        )} limit). Use offset=$nextOffset to continue.]"
                }
            details = ReadToolDetails(truncation)
        } else if (userLimitedLines != null && startLine + userLimitedLines < allLines.size) {
            // User-specified limit stopped early, but the file still has more content.
            val remaining = allLines.size - (startLine + userLimitedLines)
            val nextOffset = startLine + userLimitedLines + 1
            outputText =
                "${truncation.content}\n\n[$remaining more lines in file. " +
                "Use offset=$nextOffset to continue.]"
        } else {
            // No truncation and no remaining user-limited content.
            outputText = truncation.content
        }
        return AgentToolResult(
            content = listOf(TextContent(outputText)),
            details = details?.toJson()
        )
    }

    private fun decodeInput(arguments: JsonObject): ReadToolInput {
        val path =
            arguments.str("path")
                ?: throw IllegalArgumentException("read: missing required argument 'path'")
        return ReadToolInput(path, arguments.double("offset"), arguments.double("limit"))
    }

    companion object {
        const val NAME = "read"
    }
}

fun createReadTool(cwd: String, options: ReadToolOptions): AgentTool = ReadTool(cwd, options)

internal fun requireString(arguments: JsonObject, key: String) {
    val value = arguments[key]
    if (value == null) {
        throw IllegalArgumentException("missing required argument '$key'")
    }
    val primitive = value as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw IllegalArgumentException("'$key' must be a string")
    }
}

/** Renders a number the way JavaScript template literals do (5, not 5.0). */
internal fun jsNumber(value: Double?): String = when {
    value == null -> "null"
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> value.toString()
}
