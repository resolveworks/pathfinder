package works.resolve.aletheia.ai.transport

import kotlinx.coroutines.flow.Flow

/**
 * Minimal HTTP SSE transport abstraction so request execution and event
 * parsing stay testable without a live network. Implementations POST a
 * request with optional bearer auth and expose the response's complete SSE
 * `data:` events as a cancellable flow.
 *
 * A successful call returns after response headers are received; collecting
 * [TransportResponse.events] reads the body. Non-2xx responses throw
 * [ProviderHttpException] with status, headers, and the bounded error body;
 * transport-level failures throw [NetworkException]. No implementation may
 * log headers, auth, or body content.
 */
interface HttpStreamingTransport {
    suspend fun post(request: TransportRequest): TransportResponse
}

data class TransportRequest(
    val url: String,
    /** Auth token sent as a Bearer credential; never logged or included in toString(). */
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
data class SseEvent(val data: String)

data class TransportResponse(
    val status: Int,
    /** Header names lower-cased. */
    val headers: Map<String, List<String>>,
    /**
     * Complete SSE data events. This flow is an already-started,
     * single-consumer view of the live response body, not a cold/restartable
     * flow: collecting it consumes the stream from the point the response was
     * received. Collecting is cancellable; cancelling collection closes the
     * underlying response body. Attempting to collect more than once is not
     * supported.
     */
    val events: Flow<SseEvent>,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull { it.isNotBlank() }
}

/** Pi's cap on captured provider error bodies (MAX_PROVIDER_ERROR_BODY_CHARS). */
const val MAX_PROVIDER_ERROR_BODY_CHARS = 4000

/** HTTP error response; carries retry classification inputs. */
class ProviderHttpException(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) : java.io.IOException("Provider returned HTTP $status") {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull { it.isNotBlank() }
}

/** Connection-level failure with no HTTP status; always retryable. */
class NetworkException(cause: Throwable) : java.io.IOException("Network request failed", cause)
