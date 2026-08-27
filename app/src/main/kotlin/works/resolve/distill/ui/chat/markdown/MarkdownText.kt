package works.resolve.distill.ui.chat.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak

/**
 * Prose styling for rendered text blocks: text color and italics. A block-level
 * analog of pi's thinking/quote overrides (`{ color, italic }`); [Color.Unspecified]
 * with [italic] false renders with theme defaults.
 */
private data class ProseStyle(val color: Color = Color.Unspecified, val italic: Boolean = false, val dimmed: Boolean = false)

/**
 * Renders a markdown string as structured Compose content, porting pi's block-level
 * markdown rendering (`Markdown.render()` / `renderToken()`) to Material 3.
 *
 * Inline styling (emphasis, links, code spans) is delegated to the pure
 * [buildInlineMarkdown][Node.buildInlineMarkdown] builder with values resolved from
 * [MaterialTheme] here; this layer owns block structure: headings, lists, quotes,
 * tables, code blocks, and rules.
 *
 * @param color prose text color override; [Color.Unspecified] keeps theme defaults.
 * @param italic renders prose in italics (e.g. thinking traces); quotes force this on.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    italic: Boolean = false,
) {
    val document: Node = remember(markdown) { MarkdownParser.parser.parse(markdown) }
    val inlineStyles = InlineMarkdownStyles(
        linkColor = MaterialTheme.colorScheme.primary,
        codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        codeTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RenderBlocks(document, inlineStyles, ProseStyle(color, italic))
    }
}

/** Walks [parent]'s children and renders each block, mirroring pi's token loop. */
@Composable
private fun RenderBlocks(parent: Node, styles: InlineMarkdownStyles, prose: ProseStyle) {
    for (child in parent.children()) {
        RenderBlock(child, styles, prose)
    }
}

@Composable
private fun RenderBlock(node: Node, styles: InlineMarkdownStyles, prose: ProseStyle) {
    when (node) {
        is Paragraph -> Text(
            text = node.buildInlineMarkdown(styles),
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = if (prose.italic) FontStyle.Italic else null,
            color = prose.resolveColor(),
        )

        is Heading -> Text(
            text = node.buildInlineMarkdown(styles),
            style = when (node.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            color = prose.resolveColor(),
        )

        // Info string (node.info) is available; syntax highlighting is out of scope.
        is FencedCodeBlock -> CodeBlock(node.literal)
        is IndentedCodeBlock -> CodeBlock(node.literal)

        is BlockQuote -> {
            val barColor = MaterialTheme.colorScheme.outlineVariant
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = barColor,
                            start = Offset(1.dp.toPx(), 0f),
                            end = Offset(1.dp.toPx(), size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Quotes render their content italic + dimmed; merge with any outer prose override.
                val quoted = ProseStyle(
                    color = prose.color.takeIf { it != Color.Unspecified }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    italic = true,
                    dimmed = true,
                )
                RenderBlocks(node, styles, quoted)
            }
        }

        is ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        is BulletList -> ListBlock(node, ordered = false, styles = styles, prose = prose)

        is OrderedList -> ListBlock(node, ordered = true, styles = styles, prose = prose)

        is TableBlock -> Table(node, styles, prose)

        is HtmlBlock -> Text(
            text = node.literal,
            style = MaterialTheme.typography.bodyLarge,
            color = if (prose.color != Color.Unspecified) prose.color else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is TaskListItemMarker -> Unit // rendered as a checkbox by the owning ListItem

        else -> RenderBlocks(node, styles, prose) // unknown block: walk children
    }
}

/**
 * Resolves the effective prose color: an explicit override wins, otherwise quoted
 * (dimmed) content falls back to [MaterialTheme.colorScheme.onSurfaceVariant] and
 * normal content keeps [Color.Unspecified] (theme default text color).
 */
@Composable
private fun ProseStyle.resolveColor(): Color =
    when {
        color != Color.Unspecified -> color
        dimmed -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.Unspecified
    }

@Composable
private fun CodeBlock(literal: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = literal,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

/**
 * Renders a bullet/ordered list, one [ListItem] per row: a fixed-width marker column
 * (• / "n." / task checkbox) plus the item's block children. Task list items replace
 * the bullet with ☑/☐, matching pi. [RenderBlocks] already skips the
 * [TaskListItemMarker] node itself.
 */
@Composable
private fun ListBlock(listNode: Node, ordered: Boolean, styles: InlineMarkdownStyles, prose: ProseStyle) {
    val startNumber = (listNode as? OrderedList)?.markerStartNumber ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var itemIndex = 0
        for (child in listNode.children()) {
            if (child is ListItem) {
                val taskMarker = child.firstChild as? TaskListItemMarker
                val marker = when {
                    taskMarker != null -> if (taskMarker.isChecked) "☑" else "☐"
                    ordered -> "${startNumber + itemIndex}."
                    else -> "•"
                }
                Row {
                    Text(
                        text = marker,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        RenderBlocks(child, styles, prose)
                    }
                }
                itemIndex++
            } else {
                RenderBlock(child, styles, prose)
            }
        }
    }
}

/** Tables: Column of Rows; header row bold with a divider beneath it. */
@Composable
private fun Table(table: TableBlock, styles: InlineMarkdownStyles, prose: ProseStyle) {
    Column {
        for (section in table.children()) {
            val isHead = section is TableHead
            for (row in section.children()) {
                if (row is TableRow) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (cell in row.children()) {
                            if (cell is TableCell) {
                                Text(
                                    text = cell.buildInlineMarkdown(styles),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isHead) FontWeight.Bold else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontStyle = if (prose.italic) FontStyle.Italic else null,
                                    color = prose.resolveColor(),
                                )
                            }
                        }
                    }
                }
            }
            if (isHead) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MarkdownTextPreview() {
    MaterialTheme {
        MarkdownText(
            """
            # Heading One

            A paragraph with **bold**, *italic*, `code`, and a [link](https://pi.dev).

            ## Heading Two

            ### Heading Three

            - First bullet
            - Second bullet with [a link](https://example.com)
                - Nested bullet
            1. Ordered item
            2. Another item

            - [x] Done task
            - [ ] Pending task

            > A quoted line.
            > > Nested quote with *emphasis*.
            > - quoted list item
            > - another quoted item
            >
            > | Q | A |
            > |---|---|
            > | one | two |

            | Name | Value |
            |------|-------|
            | alpha | 1 |
            | beta | 2 |

            ```
            fun main() {
                println("a rather long line of code that should scroll horizontally instead of wrapping awkwardly")
            }
            ```

            ---

            <div>literal html</div>
            """.trimIndent(),
        )
        MarkdownText(
            markdown = """
            # Thinking heading

            A dimmed, italic thinking paragraph with **bold** and a [link](https://pi.dev).

            - italic thinking list item

            > A quote inside thinking stays italic + dimmed.
            """.trimIndent(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            italic = true,
        )
    }
}
