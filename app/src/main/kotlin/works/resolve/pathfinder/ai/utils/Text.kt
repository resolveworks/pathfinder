package works.resolve.pathfinder.ai.utils

import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.TextContent

/**
 * Extract and join text from message content (pi `utils/text.ts`).
 * pi's `contentText` also accepts a plain string; pathfinder content is
 * always structured.
 */
fun contentText(content: List<Content>, separator: String = "\n"): String =
    content.filter { it.type == ContentType.TEXT }.joinToString(separator) { (it as TextContent).text }
