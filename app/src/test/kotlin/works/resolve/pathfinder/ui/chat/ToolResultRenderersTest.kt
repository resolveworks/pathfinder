package works.resolve.pathfinder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool

/**
 * Pins result-format resolution: the two Pathfinder-owned web tools render
 * Markdown, everything else is monospace raw output (pi's generic fallback).
 */
class ToolResultRenderersTest {
    @Test
    fun `web_search renders as markdown`() {
        assertEquals(
            ToolResultFormat.MARKDOWN,
            ToolResultRenderers.formatFor(BraveWebSearchTool.NAME)
        )
    }

    @Test
    fun `web_fetch renders as markdown (page content is defuddle markdown)`() {
        assertEquals(
            ToolResultFormat.MARKDOWN,
            ToolResultRenderers.formatFor(WebFetchTool.NAME)
        )
    }

    @Test
    fun `every other tool renders as monospace raw output (pi generic fallback)`() {
        assertEquals(ToolResultFormat.MONO, ToolResultRenderers.formatFor("bash"))
        assertEquals(ToolResultFormat.MONO, ToolResultRenderers.formatFor("read"))
        assertEquals(ToolResultFormat.MONO, ToolResultRenderers.formatFor("some_future_tool"))
    }
}
