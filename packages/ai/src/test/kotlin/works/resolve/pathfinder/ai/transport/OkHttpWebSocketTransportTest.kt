package works.resolve.pathfinder.ai.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class OkHttpWebSocketTransportTest {

    private fun transport() = OkHttpWebSocketTransport()

    private fun url(server: MockWebServer) = server.url("/v1/ws").toString()

    /** Sends one greeting on open, then echoes messages back. */
    private class EchoServerListener : WebSocketListener() {
        val serverClosed = CountDownLatch(1)
        val sent = CompletableDeferred<Unit>()

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("hello")
            sent.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.send(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Complete the closing handshake, as a compliant server does.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            serverClosed.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            serverClosed.countDown()
        }
    }

    private fun upgradeServer(listener: WebSocketListener): MockWebServer {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        return server
    }

    @Test
    fun `connect resolves open and echoed messages arrive as Message events`() {
        val serverListener = EchoServerListener()
        val server = upgradeServer(serverListener)
        runBlocking {
            val connection = transport().connect(
                url(server),
                mapOf("Authorization" to "Bearer secret-token")
            )
            assertTrue(connection.isOpen)
            assertEquals(
                WebSocketEvent.Message("hello"),
                withTimeout(5_000) {
                    connection.events.receive()
                }
            )
            connection.send("ping")
            assertEquals(
                WebSocketEvent.Message("ping"),
                withTimeout(5_000) {
                    connection.events.receive()
                }
            )
            connection.close()
        }
        assertTrue(serverListener.serverClosed.await(5, TimeUnit.SECONDS))
        val recorded = server.takeRequest()
        assertEquals("/v1/ws", recorded.path)
        assertEquals(
            "secret-token",
            recorded.getHeader("Authorization").let {
                it?.removePrefix("Bearer ")
            }
        )
        server.shutdown()
    }

    @Test
    fun `server initiated close surfaces Closed with pi's exact message shape`() {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.close(4408, "session expired")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }
        val server = upgradeServer(listener)
        runBlocking {
            val connection = transport().connect(url(server), emptyMap())
            val event = withTimeout(5_000) { connection.events.receive() }
            assertEquals(
                WebSocketEvent.Closed(
                    code = 4408,
                    reason = "session expired",
                    wasClean = null,
                    message = "WebSocket closed 4408 session expired"
                ),
                event
            )
            assertTrue(withTimeout(5_000) { connection.events.isClosedForReceive })
            assertFalse(connection.isOpen)
        }
        server.shutdown()
    }

    @Test
    fun `close code 1009 without reason gets the message too big default`() {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.close(1009, "")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }
        val server = upgradeServer(listener)
        runBlocking {
            val connection = transport().connect(url(server), emptyMap())
            val event = withTimeout(5_000) { connection.events.receive() }
            val closed = event as WebSocketEvent.Closed
            assertEquals(1009, closed.code)
            assertEquals(null, closed.reason)
            assertEquals("WebSocket closed 1009 message too big", closed.message)
        }
        server.shutdown()
    }

    @Test
    fun `failure before open rejects connect with the shaped error`() {
        val server = MockWebServer()
        // Non-101 response: OkHttp reports onFailure instead of onOpen.
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val error = assertFailsWith<java.io.IOException> {
            runBlocking { transport().connect(url(server), emptyMap()) }
        }
        assertTrue(!error.message.isNullOrBlank())
        server.shutdown()
    }

    @Test
    fun `connect timeout rejects with pi's exact timeout message`() {
        val server = MockWebServer()
        // Server accepts TCP but never responds: no onOpen and no onFailure,
        // so only the connect timeout can fire.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val error = assertFailsWith<java.io.IOException> {
            runBlocking { transport().connect(url(server), emptyMap(), connectTimeoutMs = 250) }
        }
        assertEquals("WebSocket connect timeout after 250ms", error.message)
        server.shutdown()
    }

    @Test
    fun `cancelling the consuming scope tears the socket down and the server observes it`() {
        val serverListener = EchoServerListener()
        val server = upgradeServer(serverListener)
        runBlocking {
            val connected = CompletableDeferred<WebSocketConnection>()
            val job = launch {
                val connection = transport().connect(
                    url(server),
                    emptyMap(),
                    connectTimeoutMs = 60_000
                )
                connected.complete(connection)
                while (true) {
                    connection.events.receive()
                }
            }
            val connection = withTimeout(5_000) { connected.await() }
            assertTrue(connection.isOpen)
            job.cancelAndJoin()
        }
        assertTrue(
            serverListener.serverClosed.await(5, TimeUnit.SECONDS),
            "server must observe the close/cancel after caller cancellation"
        )
        server.shutdown()
    }

    @Test
    fun `cancelling connect mid handshake cancels the socket`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        runBlocking {
            // Run on IO so blocking takeRequest below doesn't starve the
            // runBlocking event loop this coroutine would otherwise share.
            val job = launch(Dispatchers.IO) {
                transport().connect(url(server), emptyMap(), connectTimeoutMs = 60_000)
            }
            // Deterministic barrier: wait until the request reaches the
            // server, then cancel while the upgrade is withheld.
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            job.cancelAndJoin()
        }
        server.shutdown()
    }

    @Test
    fun `connect cancellation surfaces as CancellationException`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        var thrown: Throwable? = null
        runBlocking {
            val job = launch(Dispatchers.IO) {
                try {
                    transport().connect(url(server), emptyMap(), connectTimeoutMs = 60_000)
                } catch (error: Throwable) {
                    thrown = error
                }
            }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            job.cancelAndJoin()
        }
        assertTrue(thrown is CancellationException, "expected CancellationException, got $thrown")
        server.shutdown()
    }

    @Test
    fun `close is silent and idempotent`() {
        val serverListener = EchoServerListener()
        val server = upgradeServer(serverListener)
        runBlocking {
            val connection = transport().connect(url(server), emptyMap())
            withTimeout(5_000) { connection.events.receive() }
            connection.close(1000, "done")
            connection.close(1000, "done")
            val event = withTimeout(5_000) { connection.events.receive() }
            assertTrue(event is WebSocketEvent.Closed || event is WebSocketEvent.Failure)
            assertFalse(connection.isOpen)
        }
        assertTrue(serverListener.serverClosed.await(5, TimeUnit.SECONDS))
        server.shutdown()
    }

    @Test
    fun `toString never contains urls headers or message text`() {
        val serverListener = EchoServerListener()
        val server = upgradeServer(serverListener)
        runBlocking {
            val connection = transport().connect(
                url(server),
                mapOf(
                    "Authorization" to "Bearer secret-token",
                    "OpenAI-Beta" to "responses_websockets=2026-02-06"
                )
            )
            val event = withTimeout(5_000) { connection.events.receive() }
            val connectionText = connection.toString()
            val eventText = event.toString()
            assertTrue("secret-token" !in connectionText && "secret-token" !in eventText)
            assertTrue("responses_websockets" !in connectionText)
            assertTrue("hello" !in eventText, "message text must be redacted from toString")
            assertTrue("127.0.0.1" !in connectionText && "localhost" !in connectionText)
            connection.close()
        }
        serverListener.serverClosed.await(5, TimeUnit.SECONDS)
        server.shutdown()
    }
}
