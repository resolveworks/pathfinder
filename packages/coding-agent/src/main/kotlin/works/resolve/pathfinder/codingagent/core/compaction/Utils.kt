package works.resolve.pathfinder.codingagent.core.compaction

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.ContentType
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.utils.contentText

private const val TOOL_RESULT_MAX_CHARS = 2000

private fun truncateForSummary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val truncatedChars = text.length - maxChars
    return "${text.substring(0, maxChars)}\n\n[... $truncatedChars more characters truncated]"
}

/**
 * Thinking is omitted: providers return summaries of the real reasoning, and
 * replayed thinking is often not part of the model's live context (a block that
 * does not match its request is dropped), so including it can make the
 * summarization request larger than the context it summarizes. Visible text,
 * tool calls, and tool results record what happened.
 */
fun serializeConversation(messages: List<Message>): String {
    val parts = mutableListOf<String>()

    for (msg in messages) {
        when (msg) {
            is works.resolve.pathfinder.ai.UserMessage -> {
                val content = contentText(msg.content, "")
                if (content.isNotEmpty()) parts.add("[User]: $content")
            }

            is AssistantMessage -> {
                val toolCalls = mutableListOf<String>()

                for (block in msg.content) {
                    when (block) {
                        is ToolCall -> {
                            val argsStr = block.arguments.entries.joinToString(", ") { (k, v) ->
                                "$k=$v"
                            }
                            toolCalls.add("${block.name}($argsStr)")
                        }

                        else -> {}
                    }
                }

                if (msg.content.any { it.type == ContentType.TEXT }) {
                    parts.add("[Assistant]: ${contentText(msg.content)}")
                }
                if (toolCalls.isNotEmpty()) {
                    parts.add("[Assistant tool calls]: ${toolCalls.joinToString("; ")}")
                }
            }

            is works.resolve.pathfinder.ai.ToolResultMessage -> {
                val content = contentText(msg.content, "")
                if (content.isNotEmpty()) {
                    parts.add(
                        "[Tool result]: ${truncateForSummary(content, TOOL_RESULT_MAX_CHARS)}"
                    )
                }
            }

            is SystemMessage -> Unit
        }
    }

    return parts.joinToString("\n\n")
}
