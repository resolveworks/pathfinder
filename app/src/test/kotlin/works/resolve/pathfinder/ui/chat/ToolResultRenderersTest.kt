package works.resolve.pathfinder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool

/**
 * Pins pi's renderer resolution: a custom renderResult is looked up by tool
 * name, and every other tool falls back to raw text.
 */
class ToolResultRenderersTest {
    @Test
    fun `web_search renders as markdown (Scry renderMarkdownResult)`() {
        assertEquals(
            ToolResultFormat.MARKDOWN,
            ToolResultRenderers.formatFor(BraveWebSearchTool.NAME)
        )
    }

    @Test
    fun `unregistered tools render as raw text (pi generic fallback)`() {
        assertEquals(ToolResultFormat.RAW, ToolResultRenderers.formatFor("bash"))
        assertEquals(ToolResultFormat.RAW, ToolResultRenderers.formatFor("read"))
        assertEquals(ToolResultFormat.RAW, ToolResultRenderers.formatFor("some_future_tool"))
    }
}
