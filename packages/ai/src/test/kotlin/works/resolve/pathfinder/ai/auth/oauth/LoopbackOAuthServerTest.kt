package works.resolve.pathfinder.ai.auth.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackOAuthServerTest {

    private val handles = mutableListOf<LoopbackCallbackHandle<*>>()

    @AfterTest
    fun tearDown() {
        handles.forEach { it.close() }
    }

    private fun <R> started(
        port: Int = 0,
        handler: suspend (LoopbackCallbackRequest, (R?) -> Unit) -> LoopbackCallbackResponse,
    ): LoopbackCallbackHandle<R> = runBlocking {
        val handle = LoopbackOAuthServer<R>(port = port, handler = handler).start()
        handles += assertNotNull(handle)
        handle
    }

    private fun get(
        port: Int,
        path: String,
        method: String = "GET",
    ): Triple<Int, Map<String, String>, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
            val bytes = stream.use { it.readBytes() }
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .associate { (it.key as String).lowercase() to it.value.first() }
            Triple(connection.responseCode, headers, String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `handler receives method path and form-decoded query parameters`() {
        var received: LoopbackCallbackRequest? = null
        val handle = started<String> { request, _ ->
            received = request
            LoopbackCallbackResponse(200, oauthSuccessHtml("done"))
        }
        get(handle.port, "/auth/callback?code=a%2Bb%3Dc&state=hello+world&dup=first&dup=second")

        val request = assertNotNull(received)
        assertEquals("GET", request.method)
        assertEquals("/auth/callback", request.path)
        assertEquals("a+b=c", request.query["code"])
        assertEquals("hello world", request.query["state"])
        assertEquals("first", request.query["dup"])
    }

    @Test
    fun `request without query yields empty map`() {
        var received: LoopbackCallbackRequest? = null
        val handle = started<String> { request, _ ->
            received = request
            LoopbackCallbackResponse(200, "ok")
        }
        get(handle.port, "/auth/callback")
        assertEquals(emptyMap(), assertNotNull(received).query)
    }

    @Test
    fun `response carries status html content-type content-length no-store and close`() {
        val html = oauthSuccessHtml("You can close this window.")
        val handle = started<String> { _, _ -> LoopbackCallbackResponse(200, html) }
        val (status, headers, body) = get(handle.port, "/cb")

        assertEquals(200, status)
        assertEquals(html, body)
        assertEquals("text/html; charset=utf-8", headers["content-type"])
        assertEquals(html.toByteArray(Charsets.UTF_8).size.toString(), headers["content-length"])
        // Divergence: always no-store (upstream: OpenRouter only).
        assertEquals("no-store", headers["cache-control"])
    }

    @Test
    fun `ephemeral port zero reports the actual bound port`() {
        val handle = started<String> { _, _ -> LoopbackCallbackResponse(200, "ok") }
        assertTrue(handle.port > 0)
        val (status, _, body) = get(handle.port, "/")
        assertEquals(200, status)
        assertEquals("ok", body)
    }

    @Test
    fun `settle completes waitForResult with the settled value`() = runBlocking {
        val handle = started<String> { request, settle ->
            settle(request.query["code"])
            LoopbackCallbackResponse(200, oauthSuccessHtml("done"))
        }
        get(handle.port, "/cb?code=abc123")
        assertEquals("abc123", handle.waitForResult())
    }

    @Test
    fun `settle wins at most once and first call wins`() = runBlocking {
        val handle = started<Int> { request, settle ->
            settle(1)
            settle(2)
            LoopbackCallbackResponse(200, "ok")
        }
        get(handle.port, "/cb")
        assertEquals(1, handle.waitForResult())
    }

    @Test
    fun `handler may settle from its own coroutine after the response`() = runBlocking {
        // OpenRouter pattern: the token exchange runs inside the handler.
        val handle = started<String> { request, settle ->
            launch(Dispatchers.Default) {
                delay(50)
                settle(request.query["code"])
            }
            LoopbackCallbackResponse(200, oauthSuccessHtml("Signed in."))
        }
        get(handle.port, "/cb?code=exchanged")
        assertEquals("exchanged", withTimeout(5_000) { handle.waitForResult() })
    }

    @Test
    fun `cancelWait completes waitForResult with null`() = runBlocking {
        val handle = started<String> { _, _ -> LoopbackCallbackResponse(200, "ok") }
        handle.cancelWait()
        assertNull(handle.waitForResult())
    }

    @Test
    fun `cancelWait after settle does not overwrite the result`() = runBlocking {
        val handle = started<String> { request, settle ->
            settle(request.query["code"])
            LoopbackCallbackResponse(200, "ok")
        }
        get(handle.port, "/cb?code=keepme")
        handle.cancelWait()
        assertEquals("keepme", handle.waitForResult())
    }

    @Test
    fun `server keeps listening after settle and later requests reach the handler`() = runBlocking {
        var settled = false
        val handle = started<String> { _, settle ->
            if (!settled) {
                settled = true
                settle("first")
                LoopbackCallbackResponse(200, "claimed")
            } else {
                // OpenRouter 409 reuse guard lives in flow code.
                LoopbackCallbackResponse(409, oauthErrorHtml("This OAuth callback has already been used."))
            }
        }
        val (status1, _, body1) = get(handle.port, "/cb")
        assertEquals("first", handle.waitForResult())
        assertEquals(200, status1)
        assertEquals("claimed", body1)

        val (status2, _, body2) = get(handle.port, "/cb")
        assertEquals(409, status2)
        assertTrue(body2.contains("already been used"))
    }

    @Test
    fun `bind conflict returns null from start`() = runBlocking {
        ServerSocket().use { occupier ->
            occupier.reuseAddress = true
            occupier.bind(InetSocketAddress("127.0.0.1", 0))
            val handle = LoopbackOAuthServer<String>(port = occupier.localPort) { _, _ ->
                LoopbackCallbackResponse(200, "ok")
            }.start()
            assertNull(handle)
        }
    }

    @Test
    fun `close is idempotent and the same fixed port can be rebound`() = runBlocking {
        val fixedPort = ServerSocket().use { reserve ->
            reserve.reuseAddress = true
            reserve.bind(InetSocketAddress("127.0.0.1", 0))
            reserve.localPort
        }

        val first = assertNotNull(
            LoopbackOAuthServer<String>(port = fixedPort) { _, _ -> LoopbackCallbackResponse(200, "first") }.start(),
        )
        assertEquals(200, get(first.port, "/").first)
        first.close()
        first.close()

        // SO_REUSEADDR (Node default) lets back-to-back logins rebind.
        val second = assertNotNull(
            LoopbackOAuthServer<String>(port = fixedPort) { _, _ -> LoopbackCallbackResponse(200, "second") }.start(),
        )
        handles += second
        val (status, _, body) = get(second.port, "/")
        assertEquals(200, status)
        assertEquals("second", body)
    }

    @Test
    fun `malformed request line gets a 400 and the server keeps serving`() {
        val handle = started<String> { _, _ -> LoopbackCallbackResponse(200, "ok") }

        Socket("127.0.0.1", handle.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("GARBAGE\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val response = ByteArrayOutputStream().also { out ->
                val input = socket.getInputStream()
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    out.write(b)
                }
            }.toString("UTF-8")
            assertTrue(response.startsWith("HTTP/1.1 400 "), response)
            assertTrue(response.contains("400 Bad Request"))
        }

        assertEquals(200, get(handle.port, "/").first)
    }

    @Test
    fun `handler throwing produces a 500 error page and the server keeps serving`() {
        var thrown = false
        val handle = started<String> { _, _ ->
            if (!thrown) {
                thrown = true
                error("boom")
            }
            LoopbackCallbackResponse(200, "ok")
        }
        val (status, _, body) = get(handle.port, "/cb")
        assertEquals(500, status)
        assertTrue(body.contains("Internal error while processing OAuth callback."))
        assertEquals(200, get(handle.port, "/later").first)
    }
}
