package works.resolve.pathfinder.ai.utils

import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.ContentType
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent

/**
 * Extract and join text from message content (pi `utils/text.ts`).
 * pi's `contentText` also accepts a plain string; pathfinder content is
 * always structured.
 */
fun contentText(content: List<out Content>, separator: String = "\n"): String = content.filter {
    it.type == ContentType.TEXT
}.joinToString(separator) { (it as TextContent).text }

/** Render a system message as a complete prompt: its content followed by its sections. */
fun getSystemMessageText(message: SystemMessage): String {
    val parts = mutableListOf(contentText(message.content))
    for (text in message.sections.orEmpty().values) {
        if (text != null) parts.add(text)
    }
    return parts.filter { it.isNotEmpty() }.joinToString("\n\n")
}

/**
 * Render a later system message for APIs that accept system messages mid-conversation.
 * Section changes are framed by name so the model can relate them to the leading prompt.
 * This framing is request-time only and may change between versions.
 */
fun renderSystemMessageUpdate(message: SystemMessage): String {
    val parts = mutableListOf<String>()
    val text = contentText(message.content)
    if (text.isNotEmpty()) parts.add(text)
    for ((name, value) in message.sections.orEmpty()) {
        parts.add(
            if (value == null) {
                "Removed system prompt section \"$name\"."
            } else {
                "Updated system prompt section \"$name\":\n\n$value"
            }
        )
    }
    return parts.joinToString("\n\n")
}
