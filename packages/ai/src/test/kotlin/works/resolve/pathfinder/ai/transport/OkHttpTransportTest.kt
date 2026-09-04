package works.resolve.pathfinder.ai.transport

import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import works.resolve.pathfinder.ai.utils.MAX_PROVIDER_ERROR_BODY_CHARS

class OkHttpTransportTest {

    private fun transport() = OkHttpTransport()

    private fun request(server: MockWebServer, body: String = """{"x":1}""") = TransportRequest(
        url = server.url("/v1/chat/completions").toString(),
        bearerToken = "secret-token",
        headers = mapOf("Accept" to "text/event-stream"),
        body = body.toByteArray(),
        timeoutMs = 10_000
    )

    @Test
    fun `posts json body with bearer auth and receives sse events`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"a\":1}\r\n\r\ndata: [DONE]\r\n\r\n")
        )
        server.start()
        val events = runBlocking {
            val response = transport().post(request(server))
            assertEquals(200, response.status)
            assertEquals("text/event-stream", response.header("Content-Type"))
            response.events.toList()
        }
        assertEquals(listOf(SseEvent("""{"a":1}"""), SseEvent("[DONE]")), events)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer secret-token", recorded.getHeader("Authorization"))
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals("""{"x":1}""", recorded.body.readUtf8())
        server.shutdown()
    }

    @Test
    fun `multiline and fragmented sse data is handled by okhttp-sse`() {
        val server = MockWebServer()
        val body = ": comment\ndata: line1\ndata: line2\n\ndata: second\n\n"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body, 1)
        )
        server.start()
        val events = runBlocking { transport().post(request(server)).events.toList() }
        assertEquals(listOf(SseEvent("line1\nline2"), SseEvent("second")), events)
        server.shutdown()
    }

    @Test
    fun `non-2xx surfaces as ProviderHttpException with capped body and headers`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After-Ms", "1200")
                .setBody("x".repeat(10_000))
        )
        server.start()
        val error = assertFailsWith<ProviderHttpException> {
            runBlocking { transport().post(request(server)) }
        }
        assertEquals(429, error.status)
        assertEquals("1200", error.header("retry-after-ms"))
        assertEquals(MAX_PROVIDER_ERROR_BODY_CHARS, error.body.length)
        assertTrue(works.resolve.pathfinder.ai.utils.ProviderRetry().isRetryable(error))
        server.shutdown()
    }

    @Test
    fun `connection failure surfaces as retryable NetworkException`() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/x").toString()
        server.shutdown()
        val error = assertFailsWith<NetworkException> {
            runBlocking {
                transport().post(
                    TransportRequest(
                        url = url,
                        bearerToken = "k",
                        body = ByteArray(0),
                        timeoutMs = 5_000
                    )
                )
            }
        }
        assertTrue(works.resolve.pathfinder.ai.utils.ProviderRetry().isRetryable(error))
    }

    @Test
    fun `cancelling event collection closes the connection promptly`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: one\n\n")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN)
        )
        server.start()
        runBlocking {
            val response = transport().post(request(server))
            val first = withTimeout(5_000) { response.events.first() }
            assertEquals(SseEvent("one"), first)
            val remaining = withTimeout(2_000) { response.events.toList() }
            assertTrue(remaining.isEmpty(), "stream must be closed after collection stops")
        }
        server.shutdown()
    }

    @Test
    fun `cancelling post before headers cancels the call`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )
        server.start()
        runBlocking {
            // Run on IO so blocking takeRequest below doesn't starve the
            // runBlocking event loop this coroutine would otherwise share.
            val job = launch(Dispatchers.IO) {
                transport().post(request(server))
            }
            // Deterministic barrier: block until the request reaches the server,
            // then cancel while the server is withholding the response.
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(2_000) { job.join() }
        }
        server.shutdown()
    }

    @Test
    fun `request headers replace the default Authorization header`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: ok\n\n")
        )
        server.start()
        runBlocking {
            transport().post(
                TransportRequest(
                    url = server.url("/v1/chat/completions").toString(),
                    bearerToken = "secret-token",
                    headers = mapOf(
                        "Authorization" to "Basic custom",
                        "cf-aig-authorization" to "Bearer gateway-token"
                    ),
                    body = "{}".toByteArray()
                )
            ).events.toList()
        }
        val recorded = server.takeRequest()
        assertEquals("Basic custom", recorded.getHeader("Authorization"))
        assertEquals("Bearer gateway-token", recorded.getHeader("cf-aig-authorization"))
        server.shutdown()
    }

    @Test
    fun `no auth header when bearer token absent`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: ok\n\n")
        )
        server.start()
        runBlocking {
            transport().post(
                TransportRequest(
                    url = server.url("/v1/chat/completions").toString(),
                    bearerToken = null,
                    body = "{}".toByteArray()
                )
            ).events.toList()
        }
        assertNull(server.takeRequest().getHeader("Authorization"))
        server.shutdown()
    }

    /**
     * Probe (E1 drift, pi #9047 class): what does okhttp-sse do with a
     * terminal SSE frame whose `data:` line is never terminated by a
     * newline? pi's adapters flush the residual buffer at EOF and dispatch
     * the frame. okhttp-sse 5.5.0 never dispatches it, and how the stream
     * breaks is timing- and environment-dependent: either its reader throws
     * an internal okio `IllegalArgumentException("byteCount < 0: -1")` —
     * surfaced by this transport as a mid-stream [NetworkException] failure
     * of the event flow — or the read blocks indefinitely on an open
     * connection (okhttp-sse cancels the call timeout before reading, so no
     * read timeout rescues it). Both modes were verified against okhttp-sse
     * 5.5.0: the crash via a standalone JVM reproduction, the hang via this
     * suite itself. The test therefore pins the one stable property — the
     * truncated terminal event is never dispatched and the flow never
     * completes cleanly — using a bounded collection window. This is the
     * accepted transport-boundary divergence (differences.md §7,
     * OkHttpTransport KDoc). If this test ever fails, okhttp changed
     * truncated-frame handling and both the KDoc and the codex divergence
     * note should be revisited.
     */
    @Test
    fun `unterminated terminal sse frame never dispatches nor completes cleanly`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"a\":1}\n\ndata: [DONE]")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )
        server.start()
        val events = mutableListOf<SseEvent>()
        val failure = runBlocking {
            try {
                withTimeout(2_000) {
                    transport().post(request(server)).events.collect { events.add(it) }
                }
                null
            } catch (error: Exception) {
                error
            }
        }
        // The terminated frame dispatches; the truncated terminal frame never
        // does — the flow must fail (okhttp-sse internal error) or stall
        // (timeout) rather than complete as a clean stream end.
        assertEquals(listOf(SseEvent("""{"a":1}""")), events)
        assertNotNull(
            failure,
            "truncated frame was flushed and dispatched — revisit the divergence notes"
        )
        server.shutdown()
    }

    /**
     * Regression (square/okhttp#9038 crash class): once okhttp-sse starts
     * streaming, OkHttp 5 replaces the response body handed to
     * [okhttp3.sse.EventSourceListener] callbacks with a stripped one that
     * throws IllegalStateException on read. Cancelling a live stream used to
     * hit that path in onFailure, crashing the OkHttp dispatcher thread. The
     * throttled long body guarantees the reader is blocked mid-stream when
     * the collector cancels, so the failure is delivered with the stripped
     * response; the transport must fail without touching it.
     */
    @Test
    fun `cancelling mid-stream never reads okhttp-sse's stripped response body`() {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: x\n\n".repeat(512))
                    .throttleBody(16, 100, TimeUnit.MILLISECONDS)
            )
            server.start()
            runBlocking {
                val response = transport().post(request(server))
                val first = withTimeout(5_000) { response.events.first() }
                assertEquals(SseEvent("x"), first)
                // first() cancelled the collection; give the dispatcher thread
                // time to observe the cancel and run the failure callback.
                Thread.sleep(1_000)
            }
            assertTrue(uncaught.isEmpty(), "listener threw on the OkHttp thread: $uncaught")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            server.shutdown()
        }
    }
}
