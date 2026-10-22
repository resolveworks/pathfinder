package works.resolve.pathfinder.tools.webfetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.utils.validateToolArguments

class WebFetchToolTest {

    private var page: PageContent = PageContent("https://example.com/", "Example", "Hello world")
    private var error: Exception? = null

    private val tool = WebFetchTool { _ ->
        error?.let { throw it }
        page
    }

    private fun validateArguments(arguments: JsonObject): JsonObject = validateToolArguments(
        tool.definition,
        ToolCall(id = "call-1", name = WebFetchTool.NAME, arguments = arguments)
    )

    private fun resultText(result: AgentToolResult): String =
        (result.content.single() as TextContent).text

    private fun args(url: String? = null): JsonObject = buildJsonObject {
        url?.let { put("url", it) }
    }

    @Test
    fun `definition carries name description and schema`() {
        assertEquals("web_fetch", WebFetchTool.NAME)
        assertEquals("web_fetch", tool.definition.name)
        assertEquals(
            "Fetch a webpage and return its main readable content as markdown.",
            tool.definition.description
        )
        val params = tool.definition.parameters as JsonObject
        assertTrue(params["required"].toString().contains("url"))
        assertTrue(
            ((params["properties"] as JsonObject)["url"] as JsonObject)["type"].toString().contains(
                "string"
            )
        )
        assertEquals("Web Fetch", tool.label)
        assertTrue(tool.promptSnippet.contains("specific URL"))
        assertEquals(2, tool.promptGuidelines.size)
    }

    @Test
    fun `schema validation rejects missing url with pi's message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateArguments(args())
        }
        assertTrue(error.message!!.startsWith("Validation failed for tool \"web_fetch\""))
        assertTrue(error.message!!.contains("must have required properties url"))
    }

    @Test
    fun `schema validation coerces a numeric url to a string`() {
        val validated = validateArguments(buildJsonObject { put("url", 3) })
        assertEquals("3", validated["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `execute rejects invalid non-http and relative urls`() {
        for (url in listOf("not a url", "ftp://example.com/", "/relative/path", "example.com")) {
            assertFailsWith<IllegalArgumentException>("expected rejection of '$url'") {
                runBlocking { tool.execute("call-1", args(url)) {} }
            }
        }
    }

    @Test
    fun `execute accepts absolute http and https urls`() {
        for (url in listOf(
            "https://example.com/",
            "http://example.com/path?q=1",
            "HTTPS://EXAMPLE.COM/"
        )) {
            runBlocking { tool.execute("call-1", args(url)) {} }
        }
    }

    @Test
    fun `execute returns title source and markdown`() = runBlocking {
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
        val maxChars = 50_000
        page = PageContent("https://example.com/", null, "x".repeat(maxChars + 5000))
        val text = resultText(tool.execute("call-1", args("https://example.com/")) {})
        assertEquals(
            "Source: https://example.com/\n\n" + "x".repeat(maxChars) +
                "\n\n[Content truncated]",
            text
        )
    }

    @Test
    fun `parseExtraction decodes markdown title and unicode`() {
        val encoded = "{\"title\":\"Título — “quoted”\\nline\"," +
            "\"markdown\":\"# Heading\\n\\nSome *markdown* with \\u00e9 and \\\"quotes\\\"\"}"
        val page = WebViewPageFetcher.parseExtraction(
            "https://example.com/",
            "https://example.com/final",
            encoded
        )
        assertEquals("https://example.com/final", page.url)
        assertEquals("Título — “quoted”\nline", page.title)
        assertEquals("# Heading\n\nSome *markdown* with é and \"quotes\"", page.markdown)
    }

    @Test
    fun `parseExtraction maps absent title to null`() {
        val page = WebViewPageFetcher.parseExtraction(
            "https://example.com/",
            "https://example.com/",
            "{\"markdown\":\"Body\"}"
        )
        assertNull(page.title)
        assertEquals("Body", page.markdown)
    }

    @Test
    fun `parseExtraction surfaces wrapper errors with the requested url`() {
        val e = assertFailsWith<WebFetchException> {
            WebViewPageFetcher.parseExtraction(
                "https://example.com/",
                "https://example.com/",
                "{\"error\":\"boom\"}"
            )
        }
        assertEquals("https://example.com/: boom", e.message)
    }

    @Test
    fun `parseExtraction rejects null and null-literal results`() {
        for (encoded in listOf<String?>(null, "null")) {
            assertFailsWith<WebFetchException> {
                WebViewPageFetcher.parseExtraction(
                    "https://example.com/",
                    "https://example.com/",
                    encoded
                )
            }
        }
    }

    @Test
    fun `parseExtraction rejects non-object payloads`() {
        assertFailsWith<WebFetchException> {
            WebViewPageFetcher.parseExtraction(
                "https://example.com/",
                "https://example.com/",
                "\"just a string\""
            )
        }
    }

    @Test
    fun `parseExtraction rejects missing and non-string markdown`() {
        for (encoded in listOf(
            "{\"title\":\"T\"}",
            "{\"markdown\":42}",
            "{\"markdown\":null}",
            "[1,2]"
        )) {
            assertFailsWith<WebFetchException>("expected rejection of $encoded") {
                WebViewPageFetcher.parseExtraction(
                    "https://example.com/",
                    "https://example.com/",
                    encoded
                )
            }
        }
    }

    @Test
    fun `execute propagates fetch failures`() {
        error = WebFetchException("Failed to load https://example.com/")
        assertFailsWith<WebFetchException> {
            runBlocking { tool.execute("call-1", args("https://example.com/")) {} }
        }
    }
}
