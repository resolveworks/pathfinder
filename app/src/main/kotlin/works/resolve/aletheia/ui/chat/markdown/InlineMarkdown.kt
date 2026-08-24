package works.resolve.aletheia.ui.chat.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

/**
 * Concrete styling inputs for inline markdown rendering. The composable layer reads
 * [androidx.compose.material3.MaterialTheme] and passes resolved values here; the
 * builder itself is pure and JVM-testable.
 */
data class InlineMarkdownStyles(
    val linkColor: Color,
    val codeBackgroundColor: Color,
    val codeTextColor: Color = Color.Unspecified,
    val monospaceFontFamily: FontFamily = FontFamily.Monospace,
)

/**
 * Renders the inline subtree of [this] node (typically a Paragraph, Heading, or
 * table cell) into an [AnnotatedString], mirroring pi's `renderInlineTokens`.
 */
fun Node.buildInlineMarkdown(styles: InlineMarkdownStyles): AnnotatedString =
    buildAnnotatedString { renderChildren(this@buildInlineMarkdown, styles) }

private fun AnnotatedString.Builder.renderChildren(node: Node, styles: InlineMarkdownStyles) {
    var child = node.firstChild
    while (child != null) {
        render(child, styles)
        child = child.next
    }
}

private fun AnnotatedString.Builder.render(node: Node, styles: InlineMarkdownStyles) {
    when (node) {
        is Text -> append(node.literal)

        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { renderChildren(node, styles) }

        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { renderChildren(node, styles) }

        is Strikethrough -> withStyle(
            SpanStyle(textDecoration = TextDecoration.LineThrough),
        ) { renderChildren(node, styles) }

        is Code -> withStyle(
            SpanStyle(
                fontFamily = styles.monospaceFontFamily,
                background = styles.codeBackgroundColor,
                color = styles.codeTextColor,
            ),
        ) { append(node.literal) }

        is Link -> renderLink(node, styles)

        is SoftLineBreak -> append(' ')

        is HardLineBreak -> append('\n')

        is Image -> {
            val alt = plainText(node)
            if (alt.isNotEmpty()) append(alt) else append(node.destination)
        }

        is HtmlInline -> append(node.literal)

        else -> {
            // Unknown node: walk children rather than silently dropping them.
            renderChildren(node, styles)
        }
    }
}

private fun literalOf(node: Node): String? = when (node) {
    is Text -> node.literal
    is Code -> node.literal
    is HtmlInline -> node.literal
    else -> null
}

private fun AnnotatedString.Builder.renderLink(node: Link, styles: InlineMarkdownStyles) {
    val linkStyle = SpanStyle(
        color = styles.linkColor,
        textDecoration = TextDecoration.Underline,
    )
    withStyle(linkStyle) {
        withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(linkStyle))) {
            renderChildren(node, styles)
        }
    }
}

/** Flattens a node subtree to its unstyled text, for link text/href comparison and image alt. */
private fun plainText(node: Node): String = buildString {
    var child = node.firstChild
    while (child != null) {
        appendNodeText(child)
        child = child.next
    }
}

private fun StringBuilder.appendNodeText(node: Node) {
    when (node) {
        is SoftLineBreak -> append(' ')
        is HardLineBreak -> append('\n')
        else -> {
            literalOf(node)?.let { append(it) }
            var child = node.firstChild
            while (child != null) {
                appendNodeText(child)
                child = child.next
            }
        }
    }
}
