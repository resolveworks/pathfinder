package works.resolve.pathfinder.ai.transport

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Never logs headers, auth values, or message content; the connection's
 * [toString][OkHttpWebSocketConnection.toString] carries no URL or headers.
 */
class OkHttpWebSocketTransport(private val client: OkHttpClient = OkHttpClient()) :
    WebSocketStreamingTransport {

    override suspend fun connect(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Long
    ): WebSocketConnection {
        val callerJob = currentCoroutineContext()[Job]
        requireNotNull(callerJob) { "connect must be called from a coroutine scope" }

        val builder = Request.Builder().url(url)
        for ((name, value) in headers) {
            builder.header(name, value)
        }

        val connection = OkHttpWebSocketConnection()
        val socket = client.newWebSocket(builder.build(), connection.listener)
        connection.attach(socket)

        // Registered on the caller's job so cancelling either the connect
        // call or the consuming scope tears the socket down promptly;
        // normal scope completion does not.
        callerJob.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.teardown("aborted")
        }

        // Launched on the caller's job so its cancellation also stops the
        // timer.
        val timeoutJob = if (connectTimeoutMs > 0) {
            CoroutineScope(callerJob).launch {
                delay(connectTimeoutMs)
                if (connection.failConnect(
                        IOException("WebSocket connect timeout after ${connectTimeoutMs}ms")
                    )
                ) {
                    connection.teardown("connect_timeout")
                }
            }
        } else {
            null
        }

        try {
            connection.opened.await()
        } catch (error: Throwable) {
            connection.teardown(null)
            throw error
        } finally {
            timeoutJob?.cancel()
        }

        return connection
    }
}

class OkHttpWebSocketConnection internal constructor() : WebSocketConnection {

    override val events: Channel<WebSocketEvent> = Channel(Channel.UNLIMITED)

    internal val opened = CompletableDeferred<Unit>()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var terminated = false

    init {
        events.invokeOnClose { cause -> if (cause != null) teardown("aborted") }
    }

    internal fun attach(socket: WebSocket) {
        this.socket = socket
    }

    internal fun failConnect(error: Throwable): Boolean {
        if (opened.isCompleted) return false
        opened.completeExceptionally(error)
        return true
    }

    /** Close 1000/[closeReason] when open, then hard-cancel so teardown is prompt even mid-handshake. */
    internal fun teardown(closeReason: String?) {
        if (closeReason != null && isOpen && !terminated) {
            close(1000, closeReason)
        }
        terminated = true
        socket?.cancel()
        failConnect(CancellationException("Request was aborted"))
    }

    override fun send(text: String) {
        socket?.send(text)
    }

    override fun close(code: Int, reason: String) {
        // OkHttp no-ops close on an already-closed socket, so this is
        // idempotent.
        try {
            socket?.close(code, reason)
        } catch (_: Exception) {
        }
    }

    override val isOpen: Boolean
        get() = !terminated

    override fun toString(): String = "OkHttpWebSocketConnection(isOpen=$isOpen)"

    internal val listener: WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(WebSocketEvent.Message(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Echo the close so the handshake completes and onClosed follows.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            terminated = true
            events.trySend(closeEvent(code, reason, wasClean = null))
            events.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // OkHttp folds close-before-open into onFailure, where pi
            // distinguishes a close from an error: connect rejection uses the
            // extractWebSocketError shape and [WebSocketCloseException] has no
            // OkHttp trigger path (kept for API parity).
            terminated = true
            val message = extractWebSocketErrorMessage(t)
            if (!failConnect(IOException(message))) {
                events.trySend(WebSocketEvent.Failure(message))
            }
            events.close()
        }
    }
}

/** OkHttp supplies no wasClean flag, so [wasClean] stays null. */
internal fun closeEvent(code: Int, reason: String, wasClean: Boolean?): WebSocketEvent.Closed {
    val codeText = " $code"
    var reasonText = if (reason.isNotEmpty()) " $reason" else ""
    if (reasonText.isEmpty() && code == WEBSOCKET_MESSAGE_TOO_BIG_CLOSE_CODE) {
        reasonText = " message too big"
    }
    return WebSocketEvent.Closed(
        code = code,
        reason = reason.ifEmpty { null },
        wasClean = wasClean,
        message = "WebSocket closed$codeText$reasonText".trim()
    )
}

internal fun extractWebSocketErrorMessage(t: Throwable?): String {
    val message = t?.message
    return if (!message.isNullOrEmpty()) message else "WebSocket error"
}
