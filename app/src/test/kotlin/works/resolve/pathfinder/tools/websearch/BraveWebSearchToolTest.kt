package works.resolve.pathfinder.tools.websearch

import works.resolve.pathfinder.ai.core.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BraveWebSearchToolTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()

    private var key: String? = "test-key"

    private fun tool(): BraveWebSearchTool =
        BraveWebSearchTool(client, { key }, server.url("/").toString().removeSuffix("/"))

    private fun resultText(result: works.resolve.pathfinder.agent.AgentToolResult): String =
        (result.content.single() as TextContent).text

    private fun args(query: String? = null, freshness: String? = null): JsonObject = buildJsonObject {
        query?.let { put("query", it) }
        freshness?.let { put("freshness", it) }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `definition carries scry name description and schema`() {
        val tool = tool()
        assertEquals("web_search", BraveWebSearchTool.NAME)
        assertEquals("web_search", tool.definition.name)
        assertEquals("Search Brave's web index and return relevant results as markdown.", tool.definition.description)
        val params = tool.definition.parameters as JsonObject
        val required = params["required"].toString()
        assertTrue(required.contains("query"))
        val freshness = (params["properties"] as JsonObject)["freshness"].toString()
        assertTrue(freshness.contains("pd") && freshness.contains("py"))
        assertEquals("Web Search", tool.label)
        assertEquals(1, tool.promptGuidelines.size)
        assertTrue(tool.promptSnippet.contains("Search the web"))
    }

    @Test
    fun `validation rejects missing and mistyped query`() {
        val tool = tool()
        assertFailsWith<IllegalArgumentException> { tool.validateArguments(args()) }
        assertFailsWith<IllegalArgumentException> {
            tool.validateArguments(buildJsonObject { put("query", 5) })
        }
        // Scry's `Type.String` has no minLength: a blank query is valid, and
        // the port must not strengthen the upstream schema.
        tool.validateArguments(args(query = "  "))
    }

    @Test
    fun `validation rejects invalid freshness and accepts valid ones`() {
        val tool = tool()
        assertFailsWith<IllegalArgumentException> { tool.validateArguments(args(query = "q", freshness = "po")) }
        for (f in BraveWebSearchTool.FRESHNESS_VALUES) {
            tool.validateArguments(args(query = "q", freshness = f))
        }
        tool.validateArguments(args(query = "q"))
    }

    @Test
    fun `sends scry request shape and headers`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody("""{"web":{"results":[]}}"""),
        )
        tool().execute("t1", tool().validateArguments(args(query = "kotlin coroutines")), {})

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        val path = recorded.path!!
        assertTrue(path.startsWith("/res/v1/web/search?"))
        assertTrue(path.contains("q=kotlin+coroutines"))
        assertTrue(path.contains("count=10"))
        assertTrue(path.contains("extra_snippets=true"))
        assertTrue(!path.contains("freshness"))
        assertEquals("test-key", recorded.getHeader("X-Subscription-Token"))
        assertEquals("application/json", recorded.getHeader("Accept"))
    }

    @Test
    fun `includes freshness parameter when provided`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"web":{"results":[]}}"""))
        tool().execute("t1", tool().validateArguments(args(query = "q", freshness = "pw")), {})
        assertTrue(server.takeRequest().path!!.contains("freshness=pw"))
    }

    @Test
    fun `formats results as numbered markdown with extra snippets`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """
                {"web":{"results":[
                  {"title":"First","url":"https://a.example","description":"Desc one","extra_snippets":["s1","s2"]},
                  {"title":"Second","url":"https://b.example"},
                  {"title":"Third","url":"https://c.example","description":""}
                ]}}
                """.trimIndent(),
            ),
        )
        val result = tool().execute("t1", tool().validateArguments(args(query = "q")), {})
        assertEquals(
            "1. **[First](https://a.example)**\n" +
                "   Desc one\n" +
                "   > s1\n" +
                "   > s2\n" +
                "\n" +
                "2. **[Second](https://b.example)**\n" +
                "\n" +
                // Scry's `if (r.description)`: empty descriptions are skipped.
                "3. **[Third](https://c.example)**",
            resultText(result),
        )
        assertEquals("{}", result.details.toString())
    }

    @Test
    fun `no results yields scry message`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"web":{"results":[]}}"""))
        var result = tool().execute("t1", tool().validateArguments(args(query = "nothing")), {})
        assertEquals("No results found for \"nothing\".", resultText(result))

        server.enqueue(MockResponse().setBody("{}"))
        result = tool().execute("t2", tool().validateArguments(args(query = "nothing")), {})
        assertEquals("No results found for \"nothing\".", resultText(result))
        assertEquals("{}", result.details.toString())
    }

    @Test
    fun `non-2xx response is returned as text content`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val result = tool().execute("t1", tool().validateArguments(args(query = "q")), {})
        assertEquals("Search failed (429): rate limited", resultText(result))
        assertEquals("{}", result.details.toString())
    }

    @Test
    fun `non-2xx body is capped and empty body falls back to status text`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(10_000)))
        var text = resultText(tool().execute("t1", tool().validateArguments(args(query = "q")), {}))
        assertTrue(text.startsWith("Search failed (500): x"))
        assertTrue(text.length < 5000)
        assertTrue(text.contains("truncated"))

        server.enqueue(MockResponse().setResponseCode(503).setBody(""))
        text = resultText(tool().execute("t2", tool().validateArguments(args(query = "q")), {}))
        // OkHttp supplies a reason phrase for the status code.
        assertTrue(text.startsWith("Search failed (503): "))
    }

    @Test
    fun `missing api key returns stable message without a request`() = runBlocking<Unit> {
        key = null
        val result = tool().execute("t1", tool().validateArguments(args(query = "q")), {})
        assertEquals(BraveWebSearchTool.MISSING_KEY_MESSAGE, resultText(result))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `network failure throws IOException`() {
        val url = server.url("/").toString().removeSuffix("/")
        server.shutdown()
        val tool = BraveWebSearchTool(client, { key }, url)
        runBlocking {
            assertFailsWith<java.io.IOException> {
                tool.execute("t1", tool.validateArguments(args(query = "q")), {})
            }
        }
    }

    @Test
    fun `cancellation rethrows CancellationException instead of returning aborted content`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse()
                .setBody("""{"web":{"results":[]}}""")
                .setHeadersDelay(10, java.util.concurrent.TimeUnit.SECONDS),
        )
        // Run execute on IO so the runBlocking thread can block in takeRequest
        // while the request is dispatched (single-threaded event loop would
        // otherwise never start the call).
        val job = async(Dispatchers.IO) {
            tool().execute("t1", tool().validateArguments(args(query = "q")), {})
        }
        // Deterministically wait until the request is in flight before canceling.
        assertTrue(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) != null)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        try {
            job.await()
            kotlin.test.fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        // Scry's "Search aborted." content path is deliberately not ported:
        // cancellation must propagate (see BraveWebSearchTool KDoc).
    }
}
