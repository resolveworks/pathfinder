package works.resolve.pathfinder.ai.transport

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * [HttpStreamingTransport] over OkHttp and okhttp-sse. Never logs the bearer
 * token, headers, or message content.
 *
 * Two-phase design mirroring pi's fetch-then-parse adapters: the HTTP phase is
 * owned here via a plain [Callback]; non-2xx bodies are captured in full (pi
 * reads `response.text()` in full; display truncation with its
 * `... [truncated N chars]` suffix happens in the shared error-body
 * normalizer) with status/headers available for retry classification before
 * any SSE machinery runs. Only a 2xx response is handed
 * to [EventSources.processResponse], which frames the stream (UTF-8, CR/LF,
 * comments, BOM, multiline data) so only complete `data:` payloads cross this
 * boundary. This split is required, not stylistic: once okhttp-sse starts
 * streaming a response it owns the body, and OkHttp 5 replaces it with a
 * stripped one that throws on read ([okhttp3.internal.UnreadableResponseBody],
 * square/okhttp#9038) — so the [EventSourceListener] must never touch a
 * response body, and cancelling mid-stream must surface as a plain failure,
 * not an attempted body read. There is no auto-reconnect.
 *
 * Divergence (accepted): okhttp-sse 5.5.0 does not flush
 * an unterminated SSE frame at EOF — a stream whose final `data:` line lacks
 * a trailing newline is never dispatched, and the stream does not end
 * cleanly either: the reader throws an internal okio
 * `IllegalArgumentException("byteCount < 0: -1")`, surfaced here as a
 * mid-stream [NetworkException] failure of the event flow. When the server
 * instead keeps an idle keep-alive connection open, that same read can block
 * indefinitely (okhttp-sse cancels the call timeout before reading, so no
 * read timeout rescues it) until the collector cancels. pi's adapters flush
 * the residual buffer at EOF (Codex's #9047 fix); because framing lives
 * below this boundary here, no parity workaround is attempted. Pinned by
 * OkHttpTransportTest's unterminated-terminal-frame probe.
 *
 * Timeouts mirror pi's two layers. The per-request
 * [TransportRequest.timeoutMs] is the deadline pi's adapters hand to the
 * provider SDKs, and every one of those covers DNS through response headers
 * only: the OpenAI and Anthropic SDKs clear their fetch timeout when `fetch()`
 * resolves, and codex cleans its `AbortSignal.timeout` after headers.
 * okhttp-sse's `RealEventSource.processResponse` cancels `Call.timeout()` at
 * the same point, so the timeout set here already has exactly that header-phase
 * scope for every API alike — no per-API distinction is needed. Streamed body
 * reads carry no whole-request deadline at this layer; their inter-read idle
 * cap is the client's readTimeout, the analog of the undici dispatcher
 * `bodyTimeout` (300s by default) that pi installs process-wide. pi's mistral
 * adapter alone threads its timeout signal into the body reader, capping that
 * stream's total duration; the mistral adapter reproduces that cap with a
 * `withTimeout` around its whole exchange, so no transport-level support is
 * needed. The composition root (PathfinderApplication) documents the full
 * client topology — this transport runs on the app's shared client, and why
 * OAuth keeps a separate one.
 */
class OkHttpTransport(
    /**
     * Production injects the app's shared client; the default builds an
     * unconfigured client for tests only.
     */
    private val client: OkHttpClient = OkHttpClient()
) : HttpStreamingTransport {

    override suspend fun post(request: TransportRequest): TransportResponse {
        val builder = Request.Builder()
            .url(request.url)
            .post(request.body.toRequestBody("application/json".toMediaType()))
        request.bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        for ((name, value) in request.headers) {
            builder.header(name, value)
        }
        val okRequest = builder.build()

        // Wrap the client so SSE calls still get the per-request timeout.
        val callFactory = Call.Factory { r ->
            client.newCall(r).also { call ->
                request.timeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
            }
        }

        val events = Channel<SseEvent>(Channel.UNLIMITED)
        val headers = HeadersResult()

        // SSE phase: framing only. Runs on the OkHttp dispatcher thread for the
        // whole stream; the response body belongs to okhttp-sse and is never
        // read here.
        val sseListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                headers.complete(response.code, response.headers.toMultimap())
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                events.trySend(SseEvent(data, type))
            }

            override fun onClosed(eventSource: EventSource) {
                headers.completeWithoutResponse()
                events.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val networkError = NetworkException(t ?: IOException("SSE stream failed"))
                if (headers.failBeforeOpen(networkError)) {
                    events.close()
                } else {
                    // Headers already delivered: the failure is a mid-stream
                    // error surfaced to the event collector, not a retryable one.
                    events.close(networkError)
                }
            }
        }

        // HTTP phase: non-2xx bodies are read here, where the body is owned
        // and readable; 2xx responses are delegated to the SSE phase above.
        val call = callFactory.newCall(okRequest)
        call.enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        // Full body like pi's `await response.text()`; the
                        // shared error-body normalizer trims and truncates for
                        // display, and retry classification sees the same body
                        // pi's adapters parse.
                        val errorBody = try {
                            response.body.string().trim()
                        } catch (_: IOException) {
                            ""
                        }
                        val responseHeaders = response.headers.toMultimap()
                        response.close()
                        events.close()
                        headers.fail(
                            ProviderHttpException(
                                status = response.code,
                                headers = responseHeaders,
                                body = errorBody,
                                statusText = response.message
                            )
                        )
                        return
                    }
                    EventSources.processResponse(response, sseListener)
                }

                override fun onFailure(call: Call, e: IOException) {
                    val networkError = NetworkException(e)
                    if (headers.failBeforeOpen(networkError)) {
                        events.close()
                    } else {
                        events.close(networkError)
                    }
                }
            }
        )

        // Cancellation while waiting for headers must cancel the call too.
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            headers.await()
        } catch (error: Throwable) {
            call.cancel()
            throw error
        } finally {
            cancellationHandle?.dispose()
        }

        return TransportResponse(
            status = headers.status,
            headers = headers.headerMap,
            // Cancelling a finished call is a no-op; this also guarantees the
            // call closes when the collector stops early (e.g. on [DONE]).
            events = events.receiveAsFlow().onCompletion { call.cancel() }
        )
    }

    /** Latch for the first response headers; later completions are ignored. */
    private class HeadersResult {
        private val deferred = CompletableDeferred<Unit>()

        var status: Int = 0
            private set
        var headerMap: Map<String, List<String>> = emptyMap()
            private set

        fun complete(status: Int, headers: Map<String, List<String>>) {
            if (deferred.isCompleted) return
            this.status = status
            this.headerMap = headers
            deferred.complete(Unit)
        }

        fun completeWithoutResponse() = complete(0, emptyMap())

        fun failBeforeOpen(error: Throwable): Boolean {
            if (deferred.isCompleted) return false
            deferred.completeExceptionally(error)
            return true
        }

        fun fail(error: Throwable) {
            deferred.completeExceptionally(error)
        }

        suspend fun await() = deferred.await()
    }
}
