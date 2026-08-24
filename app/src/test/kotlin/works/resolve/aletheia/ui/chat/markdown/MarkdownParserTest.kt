package works.resolve.aletheia.ui.chat.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private val parser = MarkdownParser.parser

    @Test
    fun `fenced code block parses with literal and info string`() {
        val doc = parser.parse("```kotlin\nval x = 1\n```\n") as Document
        val code = doc.firstChild as FencedCodeBlock
        assertEquals("kotlin", code.info)
        assertEquals("val x = 1\n", code.literal)
    }

    @Test
    fun `heading levels are parsed`() {
        val doc = parser.parse("## Title\n\n### Sub\n") as Document
        val h2 = doc.firstChild as Heading
        assertEquals(2, h2.level)
        val h3 = h2.next as Heading
        assertEquals(3, h3.level)
    }

    @Test
    fun `emphasis and strong emphasis are parsed`() {
        val doc = parser.parse("*em* and **strong**\n") as Document
        val paragraph = doc.firstChild as Paragraph
        val em = paragraph.firstChild as Emphasis
        assertEquals("em", (em.firstChild as Text).literal)
        val strong = em.next.next as StrongEmphasis
        assertEquals("strong", (strong.firstChild as Text).literal)
    }

    @Test
    fun `gfm table parses to tables extension nodes`() {
        val doc = parser.parse("| a | b |\n| --- | --- |\n| 1 | 2 |\n") as Document
        val block = doc.firstChild as TableBlock
        val head = block.firstChild as TableHead
        val headRow = head.firstChild as TableRow
        val headerCells = headRow.childrenSequence().filterIsInstance<TableCell>().map { cell ->
            (cell.firstChild as Text).literal
        }.toList()
        assertEquals(listOf("a", "b"), headerCells)

        val bodyRow = (head.next as TableBody).firstChild as TableRow
        val bodyCells = bodyRow.childrenSequence().filterIsInstance<TableCell>().map { cell ->
            (cell.firstChild as Text).literal
        }.toList()
        assertEquals(listOf("1", "2"), bodyCells)
    }

    @Test
    fun `strikethrough parses to extension node`() {
        val doc = parser.parse("~~gone~~\n") as Document
        val paragraph = doc.firstChild as Paragraph
        val strike = paragraph.firstChild as Strikethrough
        assertEquals("gone", (strike.firstChild as Text).literal)
    }

    @Test
    fun `task list items expose checked state`() {
        val doc = parser.parse("- [x] done\n- [ ] todo\n") as Document
        val list = doc.firstChild
        val done = list.firstChild.firstChild as TaskListItemMarker
        assertTrue(done.isChecked)

        val todo = list.firstChild.next.firstChild as TaskListItemMarker
        assertTrue(!todo.isChecked)
    }

    @Test
    fun `unclosed fenced code block during stream still parses as code block`() {
        val doc = parser.parse("```python\ndef f():\n    pass\n") as Document
        val code = doc.firstChild as FencedCodeBlock
        assertEquals("python", code.info)
        assertEquals("def f():\n    pass\n", code.literal)
    }

    @Test
    fun `parser instance is stateless across repeated parses`() {
        val first = parser.parse("> quote\n") as Document
        val second = parser.parse("# Heading\n") as Document
        assertTrue(first.firstChild is BlockQuote)
        assertTrue(second.firstChild is Heading)
    }

    private fun org.commonmark.node.Node.childrenSequence(): Sequence<org.commonmark.node.Node> =
        generateSequence(this.firstChild) { it.next }
}
