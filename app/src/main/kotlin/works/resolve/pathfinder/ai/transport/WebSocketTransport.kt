package works.resolve.pathfinder.ai.transport

import java.io.IOException
import kotlinx.coroutines.channels.Channel

/**
 * Platform WebSocket seam for porting pi's Codex WebSocket transport.
 *
 * Mirrors the `WebSocketLike` / `connectWebSocket` family from pi's
 * `packages/ai/src/api/openai-codex-responses.ts`:
 *
 * - [WebSocketStreamingTransport.connect] is `connectWebSocket`
 * (openai-codex-responses.ts ~:1038): it suspends until the socket is OPEN
 * and throws on handshake failure, close-before-open
 * ([WebSocketCloseException]), or connect timeout
 * (`WebSocket connect timeout after Nms`).
 * - [WebSocketConnection.events] replaces the DOM-style
 * `addEventListener("message" | "error" | "close")` surface with a channel;
 * [WebSocketEvent.Message] is the UTF-8-decoded text of `decodeWebSocketData`
 * (~:1265) — OkHttp decodes text frames before this boundary.
 * - [WebSocketConnection.close] is `closeWebSocketSilently` (~:1019): it
 * never throws.
 * - [WebSocketConnection.isOpen] is `isWebSocketReusable` (~:1009): an
 * unknown state is treated as open/reusable.
 *
 * Divergences from pi (adaptation boundaries, not reinterpretations):
 *
 * - Cancellation: pi's `connectWebSocket` rejects with
 * `Error("Request was aborted")` and closes the socket with 1000/"aborted".
 * Kotlin coroutine cancellation instead propagates as
 * [kotlinx.coroutines.CancellationException]; this transport still tears the
 * socket down promptly (OkHttp `cancel()`, or a silent close 1000/"aborted"
 * once open) when the `connect` call or the events consumer is cancelled,
 * matching the abort handling's effect.
 * - pi's `getWebSocketConstructor` selects a proxy-aware WebSocket
 * constructor per Node/Bun runtime; there is no counterpart here — OkHttp
 * honors the JVM/Android system proxy configuration.
 * - pi's `connectWebSocket` deletes the `OpenAI-Beta` header before the
 * handshake. That is Codex adapter policy, not transport policy: headers
 * supplied to [connect] are passed to the handshake verbatim and the caller
 * owns any filtering.
 *
 * Implementations must never log headers, auth values, or message content.
 */
interface WebSocketStreamingTransport {
    /**
     * Opens a WebSocket to [url], passing [headers] to the handshake.
     * Suspends until the socket is open.
     *
     * @param connectTimeoutMs port of pi's `connectWebSocket` timeout
     * (default `DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS` = 15 000). Only
     * applied when greater than zero; on expiry the socket is closed with
     * 1000/"connect_timeout" and connect throws
     * [java.io.IOException](`WebSocket connect timeout after Nms`).
     */
    suspend fun connect(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Long = DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS,
    ): WebSocketConnection

    companion object {
        /** pi `DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS` (openai-codex-responses.ts:50). */
        const val DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS: Long = 15_000
    }
}

/**
 * pi `WEBSOCKET_MESSAGE_TOO_BIG_CLOSE_CODE` (openai-codex-responses.ts:55):
 * close code 1009, which gets the default reason "message too big" when the
 * peer supplies none.
 */
const val WEBSOCKET_MESSAGE_TOO_BIG_CLOSE_CODE: Int = 1009

/**
 * An open WebSocket connection; pi's `WebSocketLike` plus its event surface.
 * Obtained only after the socket is open, so member calls need no
 * connect-state handling. Not thread-safe for concurrent [close]/[send]
 * beyond OkHttp's own guarantees.
 */
interface WebSocketConnection {
    /** Sends one text frame (`WebSocketLike.send(data: string)`); text only. */
    fun send(text: String)

    /**
     * `closeWebSocketSilently` (openai-codex-responses.ts ~:1019): closes
     * with [code]/[reason], swallowing any exception. Idempotent.
     */
    fun close(code: Int = 1000, reason: String = "done")

    /**
     * `isWebSocketReusable` (~:1009): true while the socket is open; an
     * unknown state is treated as reusable.
     */
    val isOpen: Boolean

    /**
     * Server-to-client events (`addEventListener("message"|"error"|"close")`).
     * Unbounded and single-consumer: receive directly or wrap with
     * `receiveAsFlow()`. The channel is closed after a terminal
     * [WebSocketEvent.Closed] or [WebSocketEvent.Failure]. Cancelling the
     * channel tears the socket down.
     */
    val events: Channel<WebSocketEvent>
}

/**
 * One server-to-client WebSocket event; port of the DOM-style
 * message/error/close events pi consumes from `WebSocketLike`.
 */
sealed interface WebSocketEvent {
    /**
     * A text message, already UTF-8-decoded — the semantics of pi's
     * `decodeWebSocketData` (~:1265) applied at this boundary.
     */
    data class Message(val text: String) : WebSocketEvent {
        override fun toString(): String = "Message(text=<${text.length} chars>)"
    }

    /**
     * A normal close (server `onClosing`/`onClosed`). [message] is exactly
     * pi's `extractWebSocketCloseError` (~:1242) shape:
     * `"WebSocket closed${" $code"}${" $reason"}"`.trim(), where code 1009
     * with an absent reason yields " message too big".
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
     * A transport failure; [message] follows pi's `extractWebSocketError`
     * (~:1215): the failure's message when non-empty, otherwise
     * "WebSocket error".
     */
    data class Failure(val message: String) : WebSocketEvent {
        override fun toString(): String = "Failure(message=$message)"
    }
}

/**
 * pi `WebSocketCloseError` (openai-codex-responses.ts:990): thrown when the
 * socket closes before opening; also carries the structured close fields.
 * [message] is the exact `extractWebSocketCloseError` text.
 */
class WebSocketCloseException(
    message: String,
    val code: Int?,
    val reason: String?,
    val wasClean: Boolean?,
) : IOException(message)
