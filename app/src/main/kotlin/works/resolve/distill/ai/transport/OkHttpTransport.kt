package works.resolve.distill.ai.transport

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.onCompletion
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Production [HttpStreamingTransport] backed by OkHttp and Square's okhttp-sse.
 *
 * - Sends JSON POST requests with a Bearer Authorization token; the token is
 *   never logged.
 * - Applies the request timeout via OkHttp's per-call timeout (using a
 *   [Call.Factory] wrapper so the SSE factory still honors it).
 * - Delegates SSE framing (UTF-8, CR/LF, comments, BOM, multiline data) to
 *   okhttp-sse; only complete `data:` payloads cross this boundary.
 * - [post] suspends until response headers arrive so non-2xx bodies are
 *   captured (capped at [MAX_PROVIDER_ERROR_BODY_CHARS]) and status/headers
 *   surface for retry classification before any content is consumed.
 * - Cancels the underlying [EventSource] promptly when the caller's event
 *   collection is cancelled or the stream completes. Never auto-reconnects.
 */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient(),
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

        val eventSource = EventSources.createFactory(callFactory).newEventSource(
            okRequest,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    headers.complete(response.code, response.headers.toMultimap())
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    events.trySend(SseEvent(data, type))
                }

                override fun onClosed(eventSource: EventSource) {
                    headers.completeWithoutResponse()
                    events.close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (response != null) {
                        // Read at most ~4x the char cap (worst-case UTF-8) so a
                        // huge error body is never fully buffered just to be
                        // truncated.
                        val readLimit = MAX_PROVIDER_ERROR_BODY_CHARS.toLong() * 4
                        val errorBody = try {
                            val source = response.body.source()
                            source.request(readLimit)
                            val buffered = source.buffer
                            buffered.readUtf8(minOf(buffered.size, readLimit))
                                .take(MAX_PROVIDER_ERROR_BODY_CHARS)
                        } catch (_: IOException) {
                            ""
                        }
                        val headers2 = response.headers.toMultimap()
                        response.close()
                        events.close()
                        headers.fail(
                            ProviderHttpException(
                                status = response.code,
                                headers = headers2,
                                body = errorBody,
                            ),
                        )
                    } else {
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
            },
        )

        // Cancellation while waiting for headers must cancel the call too.
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) eventSource.cancel()
        }
        try {
            headers.await()
        } catch (error: Throwable) {
            eventSource.cancel()
            throw error
        } finally {
            cancellationHandle?.dispose()
        }

        return TransportResponse(
            status = headers.status,
            headers = headers.headerMap,
            // Cancelling a finished source is a no-op; this also guarantees the
            // call closes when the collector stops early (e.g. on [DONE]).
            events = events.receiveAsFlow().onCompletion { eventSource.cancel() },
        )
    }

    /** Latch for the first response headers; completes once, from listener callbacks. */
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

        /** Fails the latch if headers were not delivered yet; false if already completed. */
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
