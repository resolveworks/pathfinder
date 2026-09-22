package works.resolve.pathfinder.ai.auth.oauth

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class OkHttpOAuthHttpClientTest {

    private fun request(url: String, timeoutMs: Int = 5_000) = OAuthHttpRequest(
        method = "POST",
        url = url,
        headers = mapOf("content-type" to "application/json"),
        body = "{\"code\":\"c\"}".toByteArray(),
        timeoutMs = timeoutMs
    )

    @Test
    fun `posts body, returns status, lower-cased headers, and full body`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Retry-After", "10")
                    .setBody("{\"error_description\":\"expired code value\"}")
            )
            server.start()
            val response = runBlocking {
                OkHttpOAuthHttpClient().execute(
                    request(server.url("/api/v1/auth/keys").toString())
                )
            }

            assertEquals(403, response.status)
            assertEquals(listOf("application/json"), response.headers["content-type"])
            assertEquals(listOf("10"), response.headers["retry-after"])
            assertTrue(response.body.decodeToString().contains("expired"))

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/v1/auth/keys", recorded.path)
            assertEquals("{\"code\":\"c\"}", recorded.body.readUtf8())
            assertEquals("application/json", recorded.getHeader("Content-Type"))
        }
    }

    @Test
    fun `get requests carry no body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val response = runBlocking {
                OkHttpOAuthHttpClient().execute(
                    OAuthHttpRequest(
                        method = "GET",
                        url = server.url("/copilot_internal/v2/token").toString(),
                        headers = mapOf("Accept" to "application/json"),
                        body = ByteArray(0),
                        timeoutMs = 5_000
                    )
                )
            }
            assertEquals(200, response.status)

            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals(0, recorded.bodySize)
        }
    }

    @Test
    fun `response bodies are read unbounded`() {
        val bigBody = "x".repeat(200 * 1024) // well past any sane token payload
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(bigBody))
            server.start()
            val response = runBlocking {
                OkHttpOAuthHttpClient().execute(request(server.url("/big").toString()))
            }
            assertEquals(bigBody.length, response.body.decodeToString().length)
        }
    }

    @Test
    fun `whole-exchange deadline expiry surfaces as SocketTimeoutException`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            assertFailsWith<SocketTimeoutException> {
                runBlocking {
                    withTimeout(5.seconds) {
                        OkHttpOAuthHttpClient().execute(
                            request(server.url("/stall").toString(), timeoutMs = 300)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `coroutine cancellation propagates despite a pending response`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            runBlocking {
                val pending = async {
                    OkHttpOAuthHttpClient().execute(
                        request(server.url("/stall").toString(), timeoutMs = 8_000)
                    )
                }
                delay(200)
                pending.cancel()
                // If cancellation did not cancel the call, this would wait
                // for the exchange deadline instead of returning promptly.
                withTimeout(2_000) { runCatching { pending.await() } }
            }
        }
    }

    @Test
    fun `connection failure surfaces as IOException`() {
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/gone").toString()
            server.shutdown()
            assertFailsWith<IOException> {
                runBlocking { OkHttpOAuthHttpClient().execute(request(url)) }
            }
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

        val withUserInfo = OAuthHttpRequest(
            "POST",
            "https://user:pass123@openrouter.ai/api/v1/auth/keys",
            emptyMap(),
            ByteArray(0),
            30_000
        )
        assertTrue("pass123" !in withUserInfo.toString())
        assertTrue("user:pass123@" !in withUserInfo.toString())
        assertTrue("url=https://openrouter.ai/api/v1/auth/keys" in withUserInfo.toString())

        val ported = OAuthHttpRequest(
            "POST",
            "http://127.0.0.1:8080/callback?code=$secret",
            emptyMap(),
            ByteArray(0),
            30_000
        )
        assertTrue("url=http://127.0.0.1:8080/callback" in ported.toString())
        assertTrue(secret !in ported.toString())

        val garbage = OAuthHttpRequest("POST", "not a url at all", emptyMap(), ByteArray(0), 30_000)
        assertTrue("url=<redacted-url>" in garbage.toString())
    }
}
