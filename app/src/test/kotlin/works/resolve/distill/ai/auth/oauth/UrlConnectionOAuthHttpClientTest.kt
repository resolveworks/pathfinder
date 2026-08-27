package works.resolve.distill.ai.auth.oauth

import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Loopback-only tests for the JDK HTTP boundary. No external network is
 * touched: a local [ServerSocket] stands in for the OAuth endpoint.
 */
class UrlConnectionOAuthHttpClientTest {

    private fun request(url: String, timeoutMs: Int = 5_000) = OAuthHttpRequest(
        method = "POST",
        url = url,
        headers = mapOf("content-type" to "application/json"),
        body = "{\"code\":\"c\"}".toByteArray(),
        timeoutMs = timeoutMs,
    )

    @Test
    fun `posts body, returns status, headers, and bounded body`() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 403 Forbidden\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: 42\r\n\r\n" +
                                "{\"error_description\":\"expired code value\"}").toByteArray(),
                        )
                        flush()
                    }
                }
            }
            val response = runBlocking {
                UrlConnectionOAuthHttpClient().execute(request("http://127.0.0.1:${server.localPort}/api/v1/auth/keys"))
            }
            worker.join(5_000)

            assertEquals(403, response.status)
            assertEquals(listOf("application/json"), response.headers["content-type"])
            assertTrue(response.body.decodeToString().contains("expired"))
        }
    }

    @Test
    fun `bounded read timeout surfaces as SocketTimeoutException`() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    readRequest(socket)
                    Thread.sleep(3_000) // never respond
                }
            }
            try {
                val error = assertFailsWith<SocketTimeoutException> {
                    runBlocking {
                        UrlConnectionOAuthHttpClient().execute(
                            request("http://127.0.0.1:${server.localPort}/", timeoutMs = 300),
                        )
                    }
                }
                assertTrue(error.message != null)
            } finally {
                worker.join(5_000)
            }
        }
    }

    @Test
    fun `coroutine cancellation propagates despite a pending blocking read`() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    readRequest(socket)
                    Thread.sleep(5_000) // far beyond the read timeout
                }
            }
            try {
                runBlocking {
                    val pending = async {
                        UrlConnectionOAuthHttpClient().execute(
                            request("http://127.0.0.1:${server.localPort}/", timeoutMs = 4_000),
                        )
                    }
                    delay(200)
                    pending.cancel()
                    // If cancellation did not disconnect, this would wait for
                    // the 4s read timeout instead of returning promptly.
                    withTimeout(2_000) { runCatching { pending.await() } }
                }
            } finally {
                worker.join(6_000)
            }
        }
    }

    @Test
    fun `connection failure surfaces as IOException`() {
        // Bind then close: nothing listens on this port.
        val port = ServerSocket(0).use { it.localPort }
        assertFailsWith<IOException> {
            runBlocking { UrlConnectionOAuthHttpClient().execute(request("http://127.0.0.1:$port/")) }
        }
    }

    @Test
    fun `toString redacts bodies`() {
        val req = request("https://openrouter.ai/")
        assertTrue("code" !in req.toString())
        assertTrue("body=<12 bytes>" in req.toString())
        val res = OAuthHttpResponse(200, emptyMap(), "secret-key".toByteArray())
        assertTrue("secret-key" !in res.toString())
        assertTrue("body=<10 bytes>" in res.toString())
    }

    @Test
    fun `toString redacts URL query, fragment, and user-info secrets`() {
        val secret = "or-v1-supersecret"
        val url =
            "https://openrouter.ai/api/v1/auth/keys?code=$secret&token=abc123#frag-$secret"
        val request = OAuthHttpRequest("POST", url, emptyMap(), ByteArray(0), 30_000)
        val rendered = request.toString()

        assertTrue(secret !in rendered)
        assertTrue("abc123" !in rendered)
        assertTrue("frag-" !in rendered)
        assertTrue("url=https://openrouter.ai/api/v1/auth/keys" in rendered)

        // User-info credentials must not surface either.
        val withUserInfo = OAuthHttpRequest(
            "POST",
            "https://user:pass123@openrouter.ai/api/v1/auth/keys",
            emptyMap(),
            ByteArray(0),
            30_000,
        )
        assertTrue("pass123" !in withUserInfo.toString())
        assertTrue("user:pass123@" !in withUserInfo.toString())
        assertTrue("url=https://openrouter.ai/api/v1/auth/keys" in withUserInfo.toString())

        // Non-default ports survive; unparseable URLs degrade to a generic marker.
        val ported = OAuthHttpRequest(
            "POST",
            "http://127.0.0.1:8080/callback?code=$secret",
            emptyMap(),
            ByteArray(0),
            30_000,
        )
        assertTrue("url=http://127.0.0.1:8080/callback" in ported.toString())
        assertTrue(secret !in ported.toString())

        val garbage = OAuthHttpRequest("POST", "not a url at all", emptyMap(), ByteArray(0), 30_000)
        assertTrue("url=<redacted-url>" in garbage.toString())
    }

    private fun readRequest(socket: java.net.Socket) {
        val input = socket.getInputStream()
        var contentLength = 0
        while (true) {
            val line = buildString {
                while (true) {
                    val byte = input.read()
                    if (byte == '\n'.code || byte < 0) break
                    if (byte != '\r'.code) append(byte.toChar())
                }
            }
            if (line.startsWith("Content-Length:")) contentLength = line.substringAfter(':').trim().toInt()
            if (line.isEmpty()) break
        }
        var remaining = contentLength
        while (remaining > 0) remaining -= input.read(ByteArray(remaining)).coerceAtLeast(0)
    }
}
