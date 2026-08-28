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
 * Production [WebSocketStreamingTransport] backed by OkHttp, mirroring pi's
 * `connectWebSocket` (packages/ai/src/api/openai-codex-responses.ts ~:1038).
 *
 * - `client.newWebSocket(request, listener)` performs the handshake with the
 * caller's headers verbatim (pi deletes `OpenAI-Beta` in the Codex adapter;
 * that filtering is caller policy, see [WebSocketStreamingTransport]).
 * - Connect suspends until `onOpen`; failure before open rejects with
 * pi's `extractWebSocketError` fallbacks over the throwable. OkHttp folds
 * close-before-open into onFailure, so [WebSocketCloseException] has no
 * OkHttp trigger path (kept for parity; see the onFailure note below).
 * - Connect timeout (only when > 0) rejects with
 * `WebSocket connect timeout after Nms` and closes 1000/"connect_timeout".
 * - Cancelling connect, or the consuming scope that called it, tears the
 * socket down (OkHttp `cancel()`, preceded by a silent close 1000/"aborted"
 * when open), matching the effect of pi's abort handling. Coroutine
 * cancellation propagates as [CancellationException] instead of pi's
 * `Error("Request was aborted")` (documented divergence).
 * - OkHttp decodes text frames to UTF-8 strings before this boundary, so
 * `onMessage(text)` maps directly onto `decodeWebSocketData` semantics.
 *
 * Never logs headers, auth, or message content; the connection's
 * [toString][OkHttpWebSocketConnection.toString] carries no URL or headers.
 */
class OkHttpWebSocketTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : WebSocketStreamingTransport {

    override suspend fun connect(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Long,
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

        // pi: on abort, fail with "Request was aborted" and close 1000/
        // "aborted" when open. Registered on the caller's job so cancelling
        // either the connect call or the consuming scope tears the socket
        // down promptly; normal scope completion does not.
        callerJob.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.teardown("aborted")
        }

        // pi: connect timeout (only when > 0) fails with
        // "WebSocket connect timeout after Nms" and closes 1000/
        // "connect_timeout". Launched on the caller's job so its cancellation
        // also stops the timer.
        val timeoutJob = if (connectTimeoutMs > 0) {
            CoroutineScope(callerJob).launch {
                delay(connectTimeoutMs)
                if (connection.failConnect(IOException("WebSocket connect timeout after ${connectTimeoutMs}ms"))) {
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

/**
 * State-tracking OkHttp realization of [WebSocketConnection]. Obtained from
 * [OkHttpWebSocketTransport.connect] only after the socket is open.
 */
class OkHttpWebSocketConnection internal constructor() : WebSocketConnection {

    override val events: Channel<WebSocketEvent> = Channel(Channel.UNLIMITED)

    internal val opened = CompletableDeferred<Unit>()

    @Volatile
    private var socket: WebSocket? = null

    // Known terminal state only; an unknown state stays "open" per
    // isWebSocketReusable (~:1009).
    @Volatile
    private var terminated = false

    init {
        // pi's abort handling from the consumer side: an explicitly cancelled
        // events channel tears the socket down.
        events.invokeOnClose { cause -> if (cause != null) teardown("aborted") }
    }

    internal fun attach(socket: WebSocket) {
        this.socket = socket
    }

    /** Fails connect; true when this call settled it (pi's `fail` guard). */
    internal fun failConnect(error: Throwable): Boolean {
        if (opened.isCompleted) return false
        opened.completeExceptionally(error)
        return true
    }

    /**
     * pi's `fail` close path: silent close with 1000/[closeReason] when the
     * socket may be open, then a hard OkHttp cancel so teardown is prompt
     * even mid-handshake.
     */
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
        // closeWebSocketSilently (~:1019): swallow close exceptions; OkHttp's
        // close on an already-closed socket is a no-op, so this is idempotent.
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
            // Complete the closing handshake so onClosed follows; pi's parser
            // reads close events, it does not author them.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            terminated = true
            events.trySend(closeEvent(code, reason, wasClean = null))
            events.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Divergence: pi distinguishes an "error" event from a close-
            // before open; OkHttp folds both into onFailure, so connect
            // rejection always uses the extractWebSocketError shape and
            // [WebSocketCloseException] has no OkHttp trigger path (kept for
            // API parity with pi's close-before-open rejection).
            terminated = true
            val message = extractWebSocketErrorMessage(t)
            // Close-before-open: reject connect with the shaped error
            // (pi rejects via extractWebSocketError / the close-event path).
            if (!failConnect(IOException(message))) {
                events.trySend(WebSocketEvent.Failure(message))
            }
            events.close()
        }
    }
}

/**
 * pi `extractWebSocketCloseError` (~:1242): message
 * `"WebSocket closed${" $code"}${" $reason"}"`.trim(), where code 1009 with
 * an absent reason yields the reason text "message too big". OkHttp always
 * supplies a numeric code and a (possibly empty) reason string, and has no
 * wasClean flag, so it stays null.
 */
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
        message = "WebSocket closed$codeText$reasonText".trim(),
    )
}

/**
 * pi `extractWebSocketError` (~:1215) over a throwable: its message when
 * non-empty, else "WebSocket error". The nested `event.error` unwrapping has
 * no OkHttp counterpart (failures arrive as throwables).
 */
internal fun extractWebSocketErrorMessage(t: Throwable?): String {
    val message = t?.message
    return if (!message.isNullOrEmpty()) message else "WebSocket error"
}
