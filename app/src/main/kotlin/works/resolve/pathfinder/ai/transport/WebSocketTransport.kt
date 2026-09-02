package works.resolve.pathfinder.ai.transport

import java.io.IOException
import kotlinx.coroutines.channels.Channel

/**
 * Platform WebSocket seam for the Codex WebSocket transport (pi's
 * `WebSocketLike` / `connectWebSocket` family).
 *
 * Divergences from pi:
 * - pi aborts with `Error("Request was aborted")` and a silent close
 *   1000/"aborted"; coroutine cancellation plays that role here — the
 *   transport tears the socket down promptly when [connect] or the events
 *   consumer is cancelled.
 * - pi selects a proxy-aware WebSocket constructor per Node/Bun runtime;
 *   there is no counterpart here — OkHttp honors the system proxy
 *   configuration.
 * - pi's `connectWebSocket` deletes the `OpenAI-Beta` header before the
 *   handshake. That is Codex adapter policy, not transport policy: headers
 *   supplied to [connect] are passed to the handshake verbatim and the
 *   caller owns any filtering.
 *
 * Implementations must never log headers, auth values, or message content.
 */
interface WebSocketStreamingTransport {
    /**
     * Opens a WebSocket to [url], passing [headers] to the handshake.
     * Suspends until the socket is open; handshake failure,
     * close-before-open ([WebSocketCloseException]), or connect timeout
     * throw.
     *
     * @param connectTimeoutMs handshake timeout, only applied when greater
     * than zero; on expiry the socket is closed with 1000/"connect_timeout"
     * and [java.io.IOException](`WebSocket connect timeout after Nms`) is
     * thrown.
     */
    suspend fun connect(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Long = DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS,
    ): WebSocketConnection

    companion object {
        const val DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS: Long = 15_000
    }
}

/**
 * Close code 1009: when the peer supplies no reason, the close message gets
 * the default reason "message too big".
 */
const val WEBSOCKET_MESSAGE_TOO_BIG_CLOSE_CODE: Int = 1009

/**
 * An open WebSocket connection, obtained only after the socket is open, so
 * member calls need no connect-state handling. Not thread-safe for
 * concurrent [close]/[send] beyond OkHttp's own guarantees.
 */
interface WebSocketConnection {
    /** Sends one text frame; binary frames are not supported. */
    fun send(text: String)

    /** Closes with [code]/[reason], swallowing any exception; idempotent. */
    fun close(code: Int = 1000, reason: String = "done")

    /** True while the socket is open; an unknown state is treated as open/reusable. */
    val isOpen: Boolean

    /**
     * Server-to-client events. Unbounded and single-consumer: receive
     * directly or wrap with `receiveAsFlow()`. The channel is closed after
     * a terminal [WebSocketEvent.Closed] or [WebSocketEvent.Failure];
     * cancelling the channel tears the socket down.
     */
    val events: Channel<WebSocketEvent>
}

sealed interface WebSocketEvent {
    /** A text message, already UTF-8-decoded (OkHttp decodes frames before this boundary). */
    data class Message(val text: String) : WebSocketEvent {
        override fun toString(): String = "Message(text=<${text.length} chars>)"
    }

    /**
     * A close from the server. [message] is exactly `"WebSocket closed"`
     * plus `" $code"` and `" $reason"` when present, trimmed.
     */
    data class Closed(
        val code: Int?,
        val reason: String?,
        val wasClean: Boolean?,
        val message: String,
    ) : WebSocketEvent {
        override fun toString(): String =
            "Closed(code=$code, reason=$reason, wasClean=$wasClean, message=$message)"
    }

    /**
     * A transport failure. [message] is the failure's own message when
     * non-empty, otherwise "WebSocket error".
     */
    data class Failure(val message: String) : WebSocketEvent {
        override fun toString(): String = "Failure(message=$message)"
    }
}

/**
 * Thrown when the socket closes before opening; carries the structured
 * close fields. [message] uses the [WebSocketEvent.Closed.message] shape.
 */
class WebSocketCloseException(
    message: String,
    val code: Int?,
    val reason: String?,
    val wasClean: Boolean?,
) : IOException(message)
