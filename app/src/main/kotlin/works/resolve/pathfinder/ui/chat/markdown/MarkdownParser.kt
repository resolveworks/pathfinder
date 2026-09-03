package works.resolve.pathfinder.ui.chat.markdown

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/**
 * During streaming the full message text is re-parsed on every stream tick and the
 * Compose tree rebuilt, mirroring pi's TUI strategy of re-rendering the whole message
 * on each update; the parser must therefore be stateless and thread-safe, so a single
 * configured instance is built once and shared everywhere.
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

internal fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }
