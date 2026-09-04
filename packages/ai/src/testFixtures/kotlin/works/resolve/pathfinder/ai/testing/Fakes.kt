package works.resolve.pathfinder.ai.testing

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.transport.WebSocketConnection
import works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport

internal class FakeTransport : HttpStreamingTransport {
    val requests = mutableListOf<TransportRequest>()
    var outcomes: MutableList<suspend () -> TransportResponse> = mutableListOf()

    /** Set when the caller stopped consuming a response's events. */
    var cancelled = MutableStateFlow(false)

    override suspend fun post(request: TransportRequest): TransportResponse {
        requests.add(request)
        check(outcomes.isNotEmpty()) { "unexpected request" }
        return outcomes.removeAt(0)()
    }

    fun enqueueResponse(chunks: List<String>, status: Int = 200) {
        outcomes.add {
            val events = flow {
                try {
                    chunks.forEach { emit(SseEvent(it)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // take() aborts the flow without necessarily flipping
                    // Job.isActive, so observe cancellation via the exception.
                    cancelled.value = true
                    throw e
                }
            }
            TransportResponse(
                status = status,
                headers = mapOf("content-type" to listOf("text/event-stream")),
                events = events
            )
        }
    }

    fun enqueueNamedResponse(vararg events: Pair<String?, String>, status: Int = 200) {
        enqueueNamedResponse(events.toList(), status)
    }

    /** Anthropic-style responses that also carry an `event:` name. */
    fun enqueueNamedResponse(events: List<Pair<String?, String>>, status: Int = 200) {
        outcomes.add {
            val flow = flow {
                try {
                    events.forEach { (name, data) -> emit(SseEvent(data, name)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled.value = true
                    throw e
                }
            }
            TransportResponse(
                status = status,
                headers = mapOf("content-type" to listOf("text/event-stream")),
                events = flow
            )
        }
    }

    /** A stream that never ends server-side after the given chunks. */
    fun enqueueHangingResponse(vararg chunks: String) {
        outcomes.add {
            val events = flow {
                try {
                    chunks.forEach { emit(SseEvent(it)) }
                    awaitCancellation()
                } finally {
                    cancelled.value = true
                }
            }
            TransportResponse(
                status = 200,
                headers = mapOf("content-type" to listOf("text/event-stream")),
                events = events
            )
        }
    }

    fun enqueueError(
        status: Int,
        body: String,
        headers: Map<String, List<String>> = emptyMap(),
        statusText: String? = null
    ) {
        outcomes.add { throw ProviderHttpException(status, headers, body, statusText) }
    }
}

internal fun sse(vararg payloads: String): List<String> = payloads.toList()

/**
 * WebSocket connects always fail, so AUTO-transport Codex requests fall back
 * to SSE — the same externally observable path as a real connect failure.
 */
internal object NoWebSocketTransport : WebSocketStreamingTransport {
    override suspend fun connect(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Long
    ): WebSocketConnection = throw java.io.IOException("no websocket transport in this test")
}
