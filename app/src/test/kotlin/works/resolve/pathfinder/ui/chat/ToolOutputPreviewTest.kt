package works.resolve.pathfinder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins pi's preview clip: ten source lines shown, the rest counted behind
 * the show-all button. Scry-path (markdown) errors render whole; the
 * generic fallback clips errors too.
 */
class ToolOutputPreviewTest {
    private fun lines(n: Int): String = (1..n).joinToString("\n") { "line $it" }

    @Test
    fun `output within the budget passes through unclipped`() {
        val preview =
            toolOutputPreview(lines(9), ToolResultFormat.RAW, isError = false, showAll = false)
        assertEquals(lines(9), preview.text)
        assertEquals(0, preview.hiddenLines)
    }

    @Test
    fun `exactly ten lines do not clip`() {
        val preview = toolOutputPreview(
            lines(10),
            ToolResultFormat.MARKDOWN,
            isError = false,
            showAll = false
        )
        assertEquals(lines(10), preview.text)
        assertEquals(0, preview.hiddenLines)
    }

    @Test
    fun `long raw output clips at ten lines`() {
        val preview = toolOutputPreview(
            lines(13),
            ToolResultFormat.RAW,
            isError = false,
            showAll = false
        )
        assertEquals(lines(10), preview.text)
        assertEquals(3, preview.hiddenLines)
    }

    @Test
    fun `long markdown output clips at ten lines`() {
        val preview = toolOutputPreview(
            lines(12),
            ToolResultFormat.MARKDOWN,
            isError = false,
            showAll = false
        )
        assertEquals(lines(10), preview.text)
        assertEquals(2, preview.hiddenLines)
    }

    @Test
    fun `markdown error output renders whole (Scry skips errors)`() {
        val preview = toolOutputPreview(
            lines(25),
            ToolResultFormat.MARKDOWN,
            isError = true,
            showAll = false
        )
        assertEquals(lines(25), preview.text)
        assertEquals(0, preview.hiddenLines)
    }

    @Test
    fun `raw error output still clips (generic fallback)`() {
        val preview = toolOutputPreview(
            lines(25),
            ToolResultFormat.RAW,
            isError = true,
            showAll = false
        )
        assertEquals(lines(10), preview.text)
        assertEquals(15, preview.hiddenLines)
    }

    @Test
    fun `showAll reveals everything`() {
        val preview = toolOutputPreview(
            lines(40),
            ToolResultFormat.RAW,
            isError = false,
            showAll = true
        )
        assertEquals(lines(40), preview.text)
        assertEquals(0, preview.hiddenLines)
    }
}
