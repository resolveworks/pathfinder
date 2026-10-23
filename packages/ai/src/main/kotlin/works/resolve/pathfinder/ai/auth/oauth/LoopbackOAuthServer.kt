package works.resolve.pathfinder.ai.auth.oauth

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.utils.formQuery

internal data class LoopbackCallbackRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>
)

internal data class LoopbackCallbackResponse(
    val status: Int,
    val body: String,
    val contentType: String = "text/html; charset=utf-8"
)

/** Handle over a running loopback OAuth callback server. */
internal interface LoopbackCallbackHandle<R> {
    /** The actual bound port (OpenRouter builds its callback URL from this before the authorize URL). */
    val port: Int

    /** Completes with the value passed to `settle`; `null` after [cancelWait]. */
    suspend fun waitForResult(): R?

    /** Hands the login to the manual path without failing. */
    fun cancelWait()

    /** Idempotent; stops accepting and lets in-flight requests drain. */
    fun close()
}

/**
 * Generic JDK-only loopback HTTP callback server consolidating pi's three
 * inline `node:http` callback servers. This class owns only the transport —
 * bind, accept, parse, respond, settle-once; all provider semantics (fixed vs
 * ephemeral port, route checks, state validation, exchange inside the handler,
 * 409 reuse guards) stay in flow code via [handler]. Callback queries parse
 * with the shared WHATWG-lenient `formQuery` (URLSearchParams never fails on
 * malformed escapes) — deliberately unlike PathUtils' strict
 * `fileURLToPath` percent-decoding, which rejects them.
 *
 * Divergences from pi:
 * - Responses always carry `Cache-Control: no-store`; upstream sets it only
 *   in OpenRouter's `sendHtml`.
 * - Handler errors are answered with each flow's upstream 500 shape via
 *   [handlerError]; upstream OpenRouter serves nothing on a handler throw
 *   (the error escapes its async wrapper), so its default here is the
 *   shared error page.
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
     * Served when [handler] throws — each flow's upstream 500 shape:
     * Anthropic answers `text/plain` "Internal error", Codex and OpenRouter
     * the shared error page.
     */
    val handlerError: LoopbackCallbackResponse = LoopbackCallbackResponse(
        500,
        oauthErrorHtml("Internal error while processing OAuth callback.")
    ),
    /**
     * Invoked per request. May call [settle] at most once, from any coroutine
     * (OpenRouter settles only after an in-handler token exchange completes);
     * the first call wins.
     */
    val handler: suspend (
        request: LoopbackCallbackRequest,
        settle: (R?) -> Unit
    ) -> LoopbackCallbackResponse
) {
    /**
     * Bind and listen. Returns `null` when the port cannot be bound; flows
     * degrade to manual-code login.
     */
    suspend fun start(): LoopbackCallbackHandle<R>? {
        val callerJob = currentCoroutineContext()[Job]
        requireNotNull(callerJob) { "start must be called from a coroutine scope" }
        return withContext(Dispatchers.IO) {
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
            // pi aborts the flow's `complete` through the same AbortSignal it
            // gives this server; coroutine cancellation is the analog, while
            // close() only stops accepting.
            callerJob.invokeOnCompletion { cause ->
                if (cause is CancellationException) scope.cancel()
            }
            scope.launch {
                while (true) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: SocketException) {
                        break // close() released the socket
                    } catch (_: IOException) {
                        continue
                    }
                    // pi's node:http server dispatches each request concurrently;
                    // a second callback must be answered while the first is in flight.
                    scope.launch {
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
            }

            val handle = object : LoopbackCallbackHandle<R> {
                override val port: Int = serverSocket.localPort

                override suspend fun waitForResult(): R? {
                    gate?.awaitForeground()
                    return result.await()
                }

                override fun cancelWait() = settle(null)

                override fun close() {
                    // node's server.close() stops accepting and lets in-flight
                    // requests finish; soTimeout bounds a stalled read.
                    runCatching { serverSocket.close() }
                }
            }
            handle
        }
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
                handlerError
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
        val query = if (queryStart >=
            0
        ) {
            formQuery(target.substring(queryStart + 1))
        } else {
            emptyMap()
        }
        return LoopbackCallbackRequest(method, rawPath, query)
    }

    private fun writeResponse(socket: Socket, response: LoopbackCallbackResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(
                "HTTP/1.1 "
            ).append(
                response.status
            ).append(' ').append(reasonPhrase(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
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

internal class OAuthCallbackServerOptions<T>(
    /** Provider name used on the browser page, for example `OpenAI`. */
    val providerName: String,
    /** Address to listen on. */
    val host: String,
    /** Port to listen on; `0` picks a free port. */
    val port: Int,
    val path: String,
    /** Host in `redirectUri` when it differs from [host], for example `localhost`. */
    val redirectHost: String? = null,
    /** Expected `state` parameter. Null when the provider does not send one. */
    val state: String? = null,
    /**
     * Finishes the sign-in with the received code before the browser page is sent, so the page can
     * show exchange failures. Pass `{ code -> code }` to exchange the code later.
     */
    val complete: suspend (code: String) -> T,
    val timeoutMs: Long? = null,
    /** Android foreground gate for the loopback wait; null = pi parity. */
    val gate: OAuthForegroundGate? = null
)

internal interface OAuthCallbackServer<T> {
    val redirectUri: String

    /**
     * Returns the result of [OAuthCallbackServerOptions.complete], or null after [cancel]. Throws when
     * the provider redirects with an error, completion fails, the login is cancelled, or the timeout
     * elapses.
     */
    suspend fun wait(): T?

    /** Stop waiting for the browser unless a callback is already being completed. */
    fun cancel()

    fun close()
}

private sealed interface CallbackCompletion<out T> {
    data class Value<T>(val value: T) : CallbackCompletion<T>

    data object Cancelled : CallbackCompletion<Nothing>

    data class Failed(val error: Throwable) : CallbackCompletion<Nothing>
}

/**
 * Loopback OAuth redirect handler shared by the browser sign-in flows.
 *
 * Provider-neutral and secret-free: nothing from a request is echoed into a response except through
 * the flow's completion decision, and the server logs nothing. Response pages always carry
 * `Cache-Control: no-store` (upstream sets it in every shared-server response; the older per-flow
 * servers did not).
 *
 * Android apps share the device network namespace, so a socket bound on `127.0.0.1` is reachable
 * from the on-device browser, which allows cleartext `http://localhost`. Android may kill the app
 * process while the browser is foregrounded; the login is simply retried.
 */
internal suspend fun <T> startOAuthCallbackServer(
    options: OAuthCallbackServerOptions<T>
): OAuthCallbackServer<T> {
    currentCoroutineContext().ensureActive()
    val providerName = options.providerName
    val host = options.host
    val port = options.port
    val path = options.path
    val redirectHost = options.redirectHost
    val state = options.state
    val timeoutMs = options.timeoutMs
    val gate = options.gate

    val claimed = AtomicBoolean(false)
    val settled = AtomicBoolean(false)
    val result = CompletableDeferred<CallbackCompletion<T>>()

    fun finish(completion: CallbackCompletion<T>) {
        if (settled.compareAndSet(false, true)) result.complete(completion)
    }

    val handle = LoopbackOAuthServer<T>(
        port = port,
        host = host,
        gate = gate,
        handler = { request, _ ->
            when {
                request.method != "GET" || request.path != path ->
                    LoopbackCallbackResponse(404, oauthErrorHtml("Callback route not found."))

                state != null && request.query["state"] != state ->
                    LoopbackCallbackResponse(400, oauthErrorHtml("State mismatch."))

                claimed.get() || settled.get() ->
                    LoopbackCallbackResponse(
                        409,
                        oauthErrorHtml("This sign-in has already been handled.")
                    )

                !request.query["error"].isNullOrEmpty() -> {
                    val error = request.query["error"]!!
                    val description = request.query["error_description"] ?: error
                    finish(
                        CallbackCompletion.Failed(
                            IllegalStateException(
                                "$providerName authorization failed: $description"
                            )
                        )
                    )
                    LoopbackCallbackResponse(
                        400,
                        oauthErrorHtml("$providerName authorization failed.", description)
                    )
                }

                request.query["code"].isNullOrEmpty() ->
                    LoopbackCallbackResponse(
                        400,
                        oauthErrorHtml("Missing authorization code.")
                    )

                // pi checks and sets `claimed` in one synchronous turn before
                // awaiting `complete`; the CAS preserves that for concurrent requests.
                !claimed.compareAndSet(false, true) ->
                    LoopbackCallbackResponse(
                        409,
                        oauthErrorHtml("This sign-in has already been handled.")
                    )

                else -> {
                    val code = request.query["code"]!!
                    try {
                        val value = options.complete(code)
                        finish(CallbackCompletion.Value(value))
                        LoopbackCallbackResponse(
                            200,
                            oauthSuccessHtml(
                                "Signed in to $providerName. You may now close this page."
                            )
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        finish(CallbackCompletion.Failed(error))
                        LoopbackCallbackResponse(
                            502,
                            oauthErrorHtml("$providerName sign-in failed.", error.message)
                        )
                    }
                }
            }
        }
    ).start()
        ?: throw IllegalStateException(
            "Could not bind the OAuth callback server on $host:$port"
        )

    val redirectHostValue = redirectHost ?: host
    val redirectUri = "http://" +
        (if (redirectHostValue.contains(":")) "[$redirectHostValue]" else redirectHostValue) +
        ":${handle.port}$path"

    return object : OAuthCallbackServer<T> {
        override val redirectUri: String = redirectUri

        override suspend fun wait(): T? {
            val completion = if (timeoutMs == null) {
                result.await()
            } else {
                withTimeoutOrNull(timeoutMs) { result.await() }
                    ?: run {
                        val timeout = IllegalStateException("$providerName sign-in timed out")
                        finish(CallbackCompletion.Failed(timeout))
                        throw timeout
                    }
            }
            return when (completion) {
                is CallbackCompletion.Value -> completion.value
                CallbackCompletion.Cancelled -> null
                is CallbackCompletion.Failed -> throw completion.error
            }
        }

        override fun cancel() {
            if (!claimed.get()) finish(CallbackCompletion.Cancelled)
        }

        override fun close() {
            finish(
                CallbackCompletion.Failed(
                    IllegalStateException("OAuth callback server closed")
                )
            )
            handle.close()
        }
    }
}

internal sealed interface CallbackOrManualInput<out T> {
    data class Callback<T>(val value: T) : CallbackOrManualInput<T>

    data class Manual(val input: String) : CallbackOrManualInput<Nothing>
}

/**
 * Wait for the browser callback, or for the user to paste the code or redirect URL when the browser
 * cannot reach the loopback server (for example over SSH). Without a callback server only the manual
 * prompt is used.
 */
internal suspend fun <T> waitForCallbackOrManualInput(
    interaction: AuthInteraction,
    callback: OAuthCallbackServer<T>?,
    message: String,
    placeholder: String
): CallbackOrManualInput<T> = coroutineScope {
    var manualInput: String? = null
    var manualError: Throwable? = null
    val manual = launch {
        try {
            manualInput = interaction.prompt(AuthPrompt.ManualCode(message, placeholder))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            manualError = error
        } finally {
            if (manualError != null || manualInput != null) callback?.cancel()
        }
    }

    try {
        val value = callback?.wait()
        manualError?.let { throw it }
        if (value != null) {
            manual.cancel()
            return@coroutineScope CallbackOrManualInput.Callback(value)
        }
        manual.join()
        manualError?.let { throw it }
        CallbackOrManualInput.Manual(manualInput ?: "")
    } finally {
        manual.cancel()
    }
}

private fun reasonPhrase(status: Int): String = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    404 -> "Not Found"
    409 -> "Conflict"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    else -> "Status"
}
