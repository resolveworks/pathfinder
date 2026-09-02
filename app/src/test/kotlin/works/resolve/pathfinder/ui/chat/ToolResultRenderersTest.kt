package works.resolve.pathfinder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool

/**
 * Tests for [ToolResultRenderers], pinning the per-tool renderer resolution
 * (pi's ToolExecutionComponent resolves custom renderResult by tool name;
 * tools without one use the generic raw-text fallback):
 * - Scry's web_search renders its result as Markdown
 *   (`renderResult: renderMarkdownResult`, index.ts).
 * - Every other tool name — the generic path — renders raw text
 *   (pi's createResultFallback).
 */
class ToolResultRenderersTest {
    @Test
    fun `web_search renders as markdown (Scry renderMarkdownResult)`() {
        assertEquals(ToolResultFormat.MARKDOWN, ToolResultRenderers.formatFor(BraveWebSearchTool.NAME))
    }

    @Test
    fun `unregistered tools render as raw text (pi generic fallback)`() {
        assertEquals(ToolResultFormat.RAW, ToolResultRenderers.formatFor("bash"))
        assertEquals(ToolResultFormat.RAW, ToolResultRenderers.formatFor("read"))
        assertEquals(ToolResultFormat.RAW, ToolResultRenderers.formatFor("some_future_tool"))
    }
}
