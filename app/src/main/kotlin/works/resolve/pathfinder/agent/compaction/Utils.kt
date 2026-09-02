package works.resolve.pathfinder.agent.compaction

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.string

/**
 * [ToolCall.arguments] is the raw JSON argument string exactly as the
 * provider streamed it (pi stores a parsed object), so argument access
 * below parses that string; unparsable arguments are treated as absent,
 * mirroring pi's `if (!args) continue` guards.
 */

/** File paths touched by a session branch or compaction range. */
data class FileOperations(
    /** Files read but not necessarily modified. */
    val read: MutableSet<String>,
    val written: MutableSet<String>,
    val edited: MutableSet<String>,
)

fun createFileOps(): FileOperations = FileOperations(mutableSetOf(), mutableSetOf(), mutableSetOf())

private fun parseToolCallArguments(toolCall: ToolCall): JsonObject? =
    // runCatching is policy-compliant here: non-suspending, expected-failure parse.
    runCatching { lenientJson.parseToJsonElement(toolCall.arguments) }.getOrNull() as? JsonObject

fun extractFileOpsFromMessage(message: Message, fileOps: FileOperations) {
    if (message !is AssistantMessage) return

    for (block in message.content) {
        if (block !is ToolCall) continue
        val args = parseToolCallArguments(block) ?: continue

        val path = args.string("path") ?: continue

        when (block.name) {
            "read" -> fileOps.read.add(path)
            "write" -> fileOps.written.add(path)
            "edit" -> fileOps.edited.add(path)
        }
    }
}

fun computeFileLists(fileOps: FileOperations): Pair<List<String>, List<String>> {
    val modified = mutableSetOf<String>()
    modified.addAll(fileOps.edited)
    modified.addAll(fileOps.written)
    val readOnly = fileOps.read.filterNot { it in modified }.sorted()
    val modifiedFiles = modified.sorted()
    return readOnly to modifiedFiles
}

fun formatFileOperations(readFiles: List<String>, modifiedFiles: List<String>): String {
    val sections = mutableListOf<String>()
    if (readFiles.isNotEmpty()) {
        sections.add("<read-files>\n${readFiles.joinToString("\n")}\n</read-files>")
    }
    if (modifiedFiles.isNotEmpty()) {
        sections.add("<modified-files>\n${modifiedFiles.joinToString("\n")}\n</modified-files>")
    }
    if (sections.isEmpty()) return ""
    return "\n\n${sections.joinToString("\n\n")}"
}

private const val TOOL_RESULT_MAX_CHARS = 2000

private fun safeJsonStringify(value: JsonElement): String = value.toString()

private fun truncateForSummary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val truncatedChars = text.length - maxChars
    return "${text.substring(0, maxChars)}\n\n[... $truncatedChars more characters truncated]"
}

/** pi's `contentText` also accepts a plain string; pathfinder content is always structured. */
internal fun contentText(content: List<Content>, separator: String = "\n"): String =
    content.filter { it.type == ContentType.TEXT }.joinToString(separator) { (it as TextContent).text }

fun serializeConversation(messages: List<Message>): String {
    val parts = mutableListOf<String>()

    for (msg in messages) {
        when (msg) {
            is works.resolve.pathfinder.ai.core.UserMessage -> {
                val content = contentText(msg.content, "")
                if (content.isNotEmpty()) parts.add("[User]: $content")
            }
            is AssistantMessage -> {
                val thinkingParts = mutableListOf<String>()
                val toolCalls = mutableListOf<String>()

                for (block in msg.content) {
                    when (block) {
                        is works.resolve.pathfinder.ai.core.ThinkingContent -> thinkingParts.add(block.thinking)
                        is ToolCall -> {
                            val args = parseToolCallArguments(block)
                            val argsStr = when (args) {
                                null -> block.arguments
                                else -> args.entries.joinToString(", ") { (k, v) -> "$k=${safeJsonStringify(v)}" }
                            }
                            toolCalls.add("${block.name}($argsStr)")
                        }
                        else -> {}
                    }
                }

                if (thinkingParts.isNotEmpty()) {
                    parts.add("[Assistant thinking]: ${thinkingParts.joinToString("\n")}")
                }
                if (msg.content.any { it.type == ContentType.TEXT }) {
                    parts.add("[Assistant]: ${contentText(msg.content)}")
                }
                if (toolCalls.isNotEmpty()) {
                    parts.add("[Assistant tool calls]: ${toolCalls.joinToString("; ")}")
                }
            }
            is works.resolve.pathfinder.ai.core.ToolResultMessage -> {
                val content = contentText(msg.content, "")
                if (content.isNotEmpty()) {
                    parts.add("[Tool result]: ${truncateForSummary(content, TOOL_RESULT_MAX_CHARS)}")
                }
            }
        }
    }

    return parts.joinToString("\n\n")
}
