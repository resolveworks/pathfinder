package works.resolve.pathfinder.ai.testing

import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** Scripted transport; records requests and replays scripted outcomes as complete SSE events. */
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
                events = events,
            )
        }
    }

    /** Anthropic-style responses that also carry an `event:` name. */
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
                events = flow,
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
                events = events,
            )
        }
    }

    fun enqueueError(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) {
        outcomes.add { throw ProviderHttpException(status, headers, body) }
    }
}

internal fun sse(vararg payloads: String): List<String> = payloads.toList()
