package works.resolve.pathfinder.ui.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.commonmark.node.Paragraph
import works.resolve.pathfinder.ui.chat.markdown.MarkdownParser.parser

class InlineMarkdownTest {

    private val styles = InlineMarkdownStyles(
        linkColor = Color.Blue,
        codeBackgroundColor = Color.LightGray,
        codeTextColor = Color.Red
    )

    private fun render(markdown: String) =
        (parser.parse(markdown).firstChild as Paragraph).buildInlineMarkdown(styles)

    @Test
    fun plainTextRoundTrips() {
        assertEquals("hello world", render("hello world").text)
        assertEquals("a * b", render("a \\* b").text)
    }

    @Test
    fun emphasisGetsItalicSpan() {
        val result = render("some *emphasized* text")
        assertEquals("some emphasized text", result.text)
        val span = result.spanStyles.single()
        assertEquals(FontStyle.Italic, span.item.fontStyle)
        assertEquals(5..15, span.start..span.end)
    }

    @Test
    fun strongEmphasisGetsBoldSpan() {
        val result = render("**bold** rest")
        assertEquals("bold rest", result.text)
        val span = result.spanStyles.single()
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(0..4, span.start..span.end)
    }

    @Test
    fun nestedStrongInsideEmphasisStacksSpans() {
        val result = render("*a **b** c*")
        assertEquals("a b c", result.text)
        val overlapping = result.spanStyles.filter { it.start <= 2 && it.end >= 3 }
        assertEquals(2, overlapping.size)
        val italics = result.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        val bolds = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, italics.size)
        assertEquals(0..5, italics.single().start..italics.single().end)
        assertEquals(2..3, bolds.single().start..bolds.single().end)
    }

    @Test
    fun strikethroughGetsLineThroughSpan() {
        val result = render("~~gone~~")
        assertEquals("gone", result.text)
        val span = result.spanStyles.single()
        assertEquals(TextDecoration.LineThrough, span.item.textDecoration)
        assertEquals(0..4, span.start..span.end)
    }

    @Test
    fun codeSpanIsMonospaceWithBackground() {
        val result = render("x `code` y")
        assertEquals("x code y", result.text)
        val span = result.spanStyles.single()
        assertEquals(FontFamily.Monospace, span.item.fontFamily)
        assertEquals(styles.codeBackgroundColor, span.item.background)
        assertEquals(styles.codeTextColor, span.item.color)
        assertEquals(2..6, span.start..span.end)
    }

    @Test
    fun linkWithDistinctTextIsClickableWithoutHrefSuffix() {
        val result = render("see [docs](https://example.com)")
        assertEquals("see docs", result.text)

        val link = result.annotationsSingle()
        assertTrue(link is LinkAnnotation.Url)
        assertEquals("https://example.com", link.url)

        val linkSpan = result.spanStyles.first {
            it.item.textDecoration == TextDecoration.Underline
        }
        assertEquals(4..8, linkSpan.start..linkSpan.end)
    }

    @Test
    fun linkWhereTextEqualsHrefHasNoSuffix() {
        val result = render("<https://example.com>")
        assertEquals("https://example.com", result.text)
        val link = result.annotationsSingle()
        assertTrue(link is LinkAnnotation.Url)
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun mailtoAutolinkHasNoSuffix() {
        val result = render("<foo@bar.com>")
        assertEquals("foo@bar.com", result.text)
        assertEquals("mailto:foo@bar.com", (result.annotationsSingle() as LinkAnnotation.Url).url)
    }

    @Test
    fun softBreakRendersAsSpaceAndHardBreakAsNewline() {
        assertEquals("a b", render("a\nb").text)
        assertEquals("a\nb", render("a\\\nb").text)
        assertEquals("a\nb", render("a  \nb").text)
    }

    @Test
    fun imageUsesAltText() {
        assertEquals("the logo", render("![the logo](https://example.com/x.png)").text)
    }

    @Test
    fun imageWithoutAltTextFallsBackToDestination() {
        assertEquals("https://example.com/x.png", render("![](https://example.com/x.png)").text)
    }

    @Test
    fun inlineHtmlPassesThroughAsPlainText() {
        assertEquals("a <b>bold</b> c", render("a <b>bold</b> c").text)
    }

    @Test
    fun multipleInlinesProduceNonOverlappingRanges() {
        val result = render("*a* `b` **c**")
        assertEquals("a b c", result.text)
        val ranges = result.spanStyles.map { it.start..it.end }
        assertEquals(listOf(0..1, 2..3, 4..5), ranges)
        ranges.forEachIndexed { i, r ->
            ranges.take(i).forEach { prev ->
                assertTrue(prev.last <= r.first, "overlap: $prev vs $r")
            }
        }
    }

    private fun androidx.compose.ui.text.AnnotatedString.annotationsSingle(): LinkAnnotation =
        getLinkAnnotations(0, length).single().item
}
