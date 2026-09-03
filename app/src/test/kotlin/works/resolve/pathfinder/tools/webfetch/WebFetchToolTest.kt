package works.resolve.pathfinder.tools.webfetch

import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.agent.ToolExecutionMode
import works.resolve.pathfinder.ai.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebFetchToolTest {

    private var page: PageContent = PageContent("https://example.com/", "Example", "Hello world")
    private var error: Exception? = null

    private val tool = WebFetchTool { _ ->
        error?.let { throw it }
        page
    }

    private fun resultText(result: AgentToolResult): String = (result.content.single() as TextContent).text

    private fun args(url: String? = null): JsonObject = buildJsonObject {
        url?.let { put("url", it) }
    }

    @Test
    fun `definition carries name description and schema`() {
        assertEquals("web_fetch", WebFetchTool.NAME)
        assertEquals("web_fetch", tool.definition.name)
        assertEquals("Fetch a webpage and return its main readable content as text.", tool.definition.description)
        val params = tool.definition.parameters as JsonObject
        assertTrue(params["required"].toString().contains("url"))
        assertTrue(((params["properties"] as JsonObject)["url"] as JsonObject)["type"].toString().contains("string"))
        assertEquals("Web Fetch", tool.label)
        assertEquals(ToolExecutionMode.SEQUENTIAL, tool.executionMode)
        assertTrue(tool.promptSnippet.contains("specific URL"))
        assertEquals(2, tool.promptGuidelines.size)
    }

    @Test
    fun `validation rejects missing url`() {
        assertFailsWith<IllegalArgumentException> { tool.validateArguments(args()) }
            .also { assertTrue(it.message!!.contains("missing required argument 'url'")) }
    }

    @Test
    fun `validation rejects non-string url`() {
        assertFailsWith<IllegalArgumentException> {
            tool.validateArguments(buildJsonObject { put("url", 3) })
        }
    }

    @Test
    fun `validation rejects invalid non-http and relative urls`() {
        for (url in listOf("not a url", "ftp://example.com/", "/relative/path", "example.com")) {
            assertFailsWith<IllegalArgumentException>("expected rejection of '$url'") {
                tool.validateArguments(args(url))
            }
        }
    }

    @Test
    fun `validation accepts absolute http and https urls`() {
        for (url in listOf("https://example.com/", "http://example.com/path?q=1", "HTTPS://EXAMPLE.COM/")) {
            val validated = tool.validateArguments(args(url))
            assertSame(validated["url"], validated["url"])
        }
    }

    @Test
    fun `execute returns title source and text`() = runBlocking {
        val result = tool.execute("call-1", args("https://example.com/")) {}
        val text = resultText(result)
        assertTrue(text.startsWith("# Example\n\n"))
        assertTrue(text.contains("Source: https://example.com/\n\n"))
        assertTrue(text.endsWith("Hello world"))
    }

    @Test
    fun `execute omits heading when title is blank`() = runBlocking {
        page = PageContent("https://example.com/", null, "Body only")
        val text = resultText(tool.execute("call-1", args("https://example.com/")) {})
        assertTrue(text.startsWith("Source: https://example.com/"))
        assertTrue(text.endsWith("Body only"))
    }

    @Test
    fun `execute reports no readable content`() = runBlocking {
        page = PageContent("https://example.com/", "Empty", "  ")
        val text = resultText(tool.execute("call-1", args("https://example.com/")) {})
        assertEquals("No readable content found at https://example.com/.", text)
    }

    @Test
    fun `execute truncates oversized text`() = runBlocking {
        page = PageContent("https://example.com/", null, "x".repeat(WebFetchTool.MAX_CONTENT_CHARS + 5000))
        val text = resultText(tool.execute("call-1", args("https://example.com/")) {})
        assertEquals(
            "Source: https://example.com/\n\n" + "x".repeat(WebFetchTool.MAX_CONTENT_CHARS) + "\n\n[Content truncated]",
            text,
        )
    }

    @Test
    fun `execute propagates fetch failures`() {
        error = WebFetchException("Failed to load https://example.com/")
        assertFailsWith<WebFetchException> {
            runBlocking { tool.execute("call-1", args("https://example.com/")) {} }
        }
    }
}
