package works.resolve.pathfinder.ai.auth.oauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

internal data class LoopbackCallbackRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
)

internal data class LoopbackCallbackResponse(
    val status: Int,
    val html: String,
)

/** Handle over a running loopback OAuth callback server. */
internal interface LoopbackCallbackHandle<R> {
    /** The actual bound port (OpenRouter builds its callback URL from this before the authorize URL). */
    val port: Int

    /** Completes with the value passed to `settle`; `null` after [cancelWait]. */
    suspend fun waitForResult(): R?

    /** Hands the login to the manual path without failing. */
    fun cancelWait()

    /** Idempotent; releases the socket and the accept coroutine. */
    fun close()
}

/**
 * Generic JDK-only loopback HTTP callback server consolidating pi's three
 * inline `node:http` callback servers. This class owns only the transport —
 * bind, accept, parse, respond, settle-once; all provider semantics (fixed vs
 * ephemeral port, route checks, state validation, exchange inside the handler,
 * 409 reuse guards) stay in flow code via [handler].
 *
 * Divergences from pi:
 * - Responses always carry `Cache-Control: no-store`; upstream sets it only
 *   in OpenRouter's `sendHtml`.
 * - Android apps share the device network namespace, so a socket bound on
 *   `127.0.0.1` is reachable from the on-device browser, which allows
 *   cleartext `http://localhost`.
 * - An optional [OAuthForegroundGate] defers [LoopbackCallbackHandle.waitForResult]
 *   until the app is foregrounded: the server keeps serving while backgrounded
 *   (the on-device browser must still be able to deliver the redirect), but
 *   the flow does not proceed into a token exchange that background-restricted
 *   Android would kill.
 *
 * Provider-neutral and secret-free: nothing from a request is echoed into a
 * response except through the flow handler's decision, and the server logs
 * nothing.
 */
internal class LoopbackOAuthServer<R>(
    /** Fixed port, or 0 for an ephemeral port (OpenRouter). */
    val port: Int,
    val host: String = "127.0.0.1",
    /** Optional Android foreground gate for `waitForResult`; `null` = pi parity. */
    val gate: OAuthForegroundGate? = null,
    /**
     * Invoked per request. May call [settle] at most once, from any coroutine
     * (OpenRouter settles only after an in-handler token exchange completes);
     * the first call wins.
     */
    val handler: suspend (request: LoopbackCallbackRequest, settle: (R?) -> Unit) -> LoopbackCallbackResponse,
) {
    /**
     * Bind and listen. Returns `null` when the port cannot be bound; flows
     * degrade to manual-code login.
     */
    suspend fun start(): LoopbackCallbackHandle<R>? = withContext(Dispatchers.IO) {
        val serverSocket = try {
            ServerSocket().apply {
                // Node sets SO_REUSEADDR by default; back-to-back logins must
                // be able to rebind the fixed ports.
                reuseAddress = true
                bind(InetSocketAddress(host, port))
            }
        } catch (_: IOException) {
            return@withContext null
        }

        val settled = AtomicBoolean(false)
        val result = CompletableDeferred<R?>()

        fun settle(value: R?) {
            if (settled.compareAndSet(false, true)) {
                result.complete(value)
            }
        }

        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            while (true) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break // close() released the socket
                } catch (_: IOException) {
                    continue
                }
                // One connection must never kill the accept loop, but
                // cancellation must propagate (runCatching would swallow it).
                try {
                    serveConnection(socket, ::settle)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                runCatching { socket.close() }
            }
        }

        val handle = object : LoopbackCallbackHandle<R> {
            override val port: Int = serverSocket.localPort

            override suspend fun waitForResult(): R? {
                gate?.awaitForeground()
                return result.await()
            }

            override fun cancelWait() = settle(null)

            override fun close() {
                runCatching { serverSocket.close() }
                scope.cancel()
            }
        }
        handle
    }

    /** Malformed or unparseable requests get a minimal 400 page; the server keeps serving. */
    private suspend fun serveConnection(socket: Socket, settle: (R?) -> Unit) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.use { s ->
            val request = try {
                parseRequest(s.getInputStream())
            } catch (_: Exception) {
                null
            }
            if (request == null) {
                runCatching { writeResponse(s, LoopbackCallbackResponse(400, BAD_REQUEST_HTML)) }
                return
            }

            val response = try {
                handler(request, settle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LoopbackCallbackResponse(500, oauthErrorHtml("Internal error while processing OAuth callback."))
            }
            writeResponse(s, response)
        }
    }

    private fun parseRequest(input: InputStream): LoopbackCallbackRequest {
        val requestLine = readLine(input) ?: error("no request line")

        val parts = requestLine.split(" ")
        if (parts.size < 3) error("malformed request line")

        // Drain headers; any body is ignored.
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }

        val method = parts[0]
        val target = parts[1]
        val queryStart = target.indexOf('?')
        val rawPath = if (queryStart >= 0) target.substring(0, queryStart) else target
        val query = if (queryStart >= 0) parseQuery(target.substring(queryStart + 1)) else emptyMap()
        return LoopbackCallbackRequest(method, rawPath, query)
    }

    private fun writeResponse(socket: Socket, response: LoopbackCallbackResponse) {
        val body = response.html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reasonPhrase(response.status)).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val output = socket.getOutputStream()
        output.write(head)
        output.write(body)
        output.flush()
    }

    /** Read a CRLF-terminated line as ISO-8859-1 (HTTP header semantics); `null` on immediate EOF. */
    private fun readLine(input: InputStream): String? {
        val bytes = StringBuilder()
        while (bytes.length < MAX_LINE_LENGTH) {
            val b = input.read()
            if (b == -1) {
                return if (bytes.isEmpty()) null else bytes.toString()
            }
            if (b == '\n'.code) {
                val length = bytes.length
                if (length > 0 && bytes[length - 1] == '\r') bytes.setLength(length - 1)
                return bytes.toString()
            }
            bytes.append(b.toChar())
        }
        error("header line too long")
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 10_000
        const val MAX_LINE_LENGTH = 16 * 1024
        const val BAD_REQUEST_HTML =
            "<!doctype html><html lang=\"en\"><body><h1>400 Bad Request</h1></body></html>"
    }
}

/**
 * Parse `a=1&b=2` with form decoding (so `+` means space) and first-occurrence
 * semantics, matching `URLSearchParams.get`.
 */
internal fun parseQuery(rawQuery: String): Map<String, String> {
    if (rawQuery.isEmpty()) return emptyMap()
    val map = LinkedHashMap<String, String>()
    for (pair in rawQuery.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val key = if (eq >= 0) pair.substring(0, eq) else pair
        val value = if (eq >= 0) pair.substring(eq + 1) else ""
        val decodedKey = urlDecode(key) ?: continue
        if (!map.containsKey(decodedKey)) {
            map[decodedKey] = urlDecode(value) ?: ""
        }
    }
    return map
}

/** `URLSearchParams` percent + form decoding; `null` when the input is malformed. */
private fun urlDecode(value: String): String? = try {
    URLDecoder.decode(value, Charsets.UTF_8)
} catch (_: IllegalArgumentException) {
    null
}

/** Minimal status-line reason phrases (HTTP/1.1 allows any token; clients ignore it). */
private fun reasonPhrase(status: Int): String = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    404 -> "Not Found"
    409 -> "Conflict"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    else -> "Status"
}
