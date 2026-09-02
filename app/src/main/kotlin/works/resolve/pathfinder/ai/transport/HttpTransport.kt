package works.resolve.pathfinder.ai.transport

import kotlinx.coroutines.flow.Flow

/**
 * Minimal HTTP SSE transport so request execution and event parsing stay
 * testable without a live network.
 *
 * [post] returns after response headers are received; collecting
 * [TransportResponse.events] reads the body. Non-2xx responses throw
 * [ProviderHttpException] with status, headers, and the bounded error body;
 * transport-level failures throw [NetworkException]. Implementations must
 * never log headers, auth values, or body content.
 */
interface HttpStreamingTransport {
    suspend fun post(request: TransportRequest): TransportResponse
}

data class TransportRequest(
    val url: String,
    /** Auth token sent as the Authorization Bearer credential; never logged or included in toString(). */
    val bearerToken: String?,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray,
    val timeoutMs: Long? = null,
) {
    override fun toString(): String =
        "TransportRequest(url=$url, bearerToken=" +
            (bearerToken?.let { "<redacted>" } ?: "null") +
            ", headers=${headers.keys}, body=<${body.size} bytes>" +
            ", timeoutMs=$timeoutMs)"

    override fun equals(other: Any?): Boolean =
        other is TransportRequest &&
            other.url == url &&
            other.bearerToken == bearerToken &&
            other.headers == headers &&
            other.body.contentEquals(body) &&
            other.timeoutMs == timeoutMs

    override fun hashCode(): Int =
        31 * (31 * (31 * url.hashCode() + bearerToken.hashCode()) + headers.hashCode()) + body.contentHashCode()
}

/** One complete SSE data event; framing/UTF-8/multiline handling lives below this boundary. */
data class SseEvent(val data: String, /** SSE `event:` field name, when the server sent one. */ val name: String? = null)

data class TransportResponse(
    val status: Int,
    /** Header names lower-cased. */
    val headers: Map<String, List<String>>,
    /**
     * Already-started, single-consumer view of the live response body, not a
     * cold/restartable flow: collection consumes the stream from the point
     * the response was received. Cancelling collection closes the underlying
     * response body; collecting more than once is unsupported.
     */
    val events: Flow<SseEvent>,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull { it.isNotBlank() }
}

/** Carries status/headers/body for retry classification. */
class ProviderHttpException(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    /** Status line reason phrase (fetch's Response.statusText); empty on HTTP/2. */
    val statusText: String? = null,
) : java.io.IOException("Provider returned HTTP $status") {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull { it.isNotBlank() }
}

/** No HTTP status; always retryable. */
class NetworkException(cause: Throwable) : java.io.IOException("Network request failed", cause)
