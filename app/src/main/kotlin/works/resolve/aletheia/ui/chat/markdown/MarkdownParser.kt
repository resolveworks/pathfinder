package works.resolve.aletheia.ui.chat.markdown

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/**
 * Shared CommonMark parser for markdown message rendering.
 *
 * Design intent: during streaming we re-parse the full message text on every stream
 * tick and rebuild the Compose tree, mirroring pi's TUI strategy of re-rendering the
 * whole message on each update. That means the parser must be stateless and
 * thread-safe, so a single configured instance is built once and shared everywhere.
 *
 * CommonMark extends an unclosed fenced code block to the end of the document, so
 * partial streams (including unterminated fences) parse into well-formed nodes
 * without any special-casing.
 */
object MarkdownParser {
    val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
            ),
        )
        .build()
}

/** The children of this node, in document order. */
internal fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }
