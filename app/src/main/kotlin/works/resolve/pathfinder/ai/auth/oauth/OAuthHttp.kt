package works.resolve.pathfinder.ai.auth.oauth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The narrow Android stand-in for pi's `fetch`: a single JSON request
 * executed with bounded timeouts, returning status/body/headers for the flow
 * to interpret — matching pi, non-2xx handling is the caller's decision, not
 * the transport's.
 *
 * Network-level failures throw [IOException] (bounded connect/read timeouts
 * as [SocketTimeoutException]); coroutine cancellation disconnects the
 * underlying connection and propagates as
 * [kotlinx.coroutines.CancellationException]. Implementations must never log
 * request URLs' query secrets, bodies, response bodies, or headers.
 */
interface OAuthHttpClient {
    suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse
}

/**
 * One OAuth HTTP exchange. [body] is JSON bytes and may carry secrets
 * (verifier, authorization code); it is redacted in [toString]. So is the
 * request URL: query strings, fragments, and user-info may carry
 * codes/tokens, so [toString] surfaces only the safe
 * `scheme://host[:port]/path` form and falls back to a generic
 * `<redacted-url>` for anything unparseable.
 */
data class OAuthHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray,
    /** Bounded connect AND read timeout, mirroring pi's single exchange deadline. */
    val timeoutMs: Int,
) {
    override fun toString(): String =
        "OAuthHttpRequest(method=$method, url=$safeUrlString, headers=${headers.keys}, " +
            "body=<${body.size} bytes>, timeoutMs=$timeoutMs)"

    private val safeUrlString: String
        get() = try {
            val uri = java.net.URI(url)
            if (uri.scheme == null || uri.host == null) {
                "<redacted-url>"
            } else {
                buildString {
                    append(uri.scheme).append("://").append(uri.host)
                    if (uri.port != -1) append(":").append(uri.port)
                    uri.rawPath?.takeIf { it.isNotEmpty() }?.let { append(it) }
                }
            }
        } catch (_: Exception) {
            "<redacted-url>"
        }

    override fun equals(other: Any?): Boolean =
        other is OAuthHttpRequest &&
            other.method == method &&
            other.url == url &&
            other.headers == headers &&
            other.body.contentEquals(body) &&
            other.timeoutMs == timeoutMs

    override fun hashCode(): Int =
        31 * (31 * (31 * method.hashCode() + url.hashCode()) + headers.hashCode()) + body.contentHashCode()
}

/** [body] may carry credential values; it is redacted in [toString]. */
data class OAuthHttpResponse(
    val status: Int,
    /** Header names lower-cased. */
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    override fun toString(): String =
        "OAuthHttpResponse(status=$status, headers=${headers.keys}, body=<${body.size} bytes>)"

    override fun equals(other: Any?): Boolean =
        other is OAuthHttpResponse &&
            other.status == status &&
            other.headers == headers &&
            other.body.contentEquals(body)

    override fun hashCode(): Int =
        31 * (31 * status + headers.hashCode()) + body.contentHashCode()
}

/**
 * Platform [OAuthHttpClient] built exclusively on JDK/Android APIs
 * ([HttpURLConnection]) — deliberately not OkHttp: OAuth logins are rare,
 * one-shot, non-streaming exchanges, so the app's streaming dependency adds
 * no value here.
 *
 * The exchange runs on its own worker thread; response bodies are read only
 * up to [MAX_BODY_BYTES] so a hostile server cannot exhaust memory.
 */
class UrlConnectionOAuthHttpClient : OAuthHttpClient {

    override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val connection = try {
                (URL(request.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = request.method
                    connectTimeout = request.timeoutMs
                    readTimeout = request.timeoutMs
                    for ((name, value) in request.headers) setRequestProperty(name, value)
                    if (request.body.isNotEmpty()) {
                        doOutput = true
                        setFixedLengthStreamingMode(request.body.size)
                    }
                }
            } catch (error: Exception) {
                continuation.resumeWith(Result.failure(IOException(error.message, error)))
                return@suspendCancellableCoroutine
            }

            // Cancellation must disconnect so the blocked worker unblocks; the
            // cancelled continuation then discards any late result/exception.
            continuation.invokeOnCancellation { connection.disconnect() }

            thread(isDaemon = true, name = "oauth-http") {
                val response = try {
                    Result.success(perform(connection, request))
                } catch (error: Throwable) {
                    Result.failure<OAuthHttpResponse>(error)
                } finally {
                    connection.disconnect()
                }
                continuation.resumeWith(response)
            }
        }

    private fun perform(connection: HttpURLConnection, request: OAuthHttpRequest): OAuthHttpResponse {
        if (request.body.isNotEmpty()) {
            connection.outputStream.use { it.write(request.body) }
        }
        val status = connection.responseCode
        val body = try {
            connection.inputStream
        } catch (_: IOException) {
            connection.errorStream
        }.use { stream ->
            if (stream == null) ByteArray(0)
            else stream.readAtMost(MAX_BODY_BYTES)
        }
        val headers = connection.headerFields.entries
            .filter { (name, _) -> name != null }
            .associate { (name, values) -> name.lowercase() to values.toList() }
        return OAuthHttpResponse(status, headers, body)
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val count = read(buffer, read, limit - read)
            if (count < 0) break
            read += count
        }
        return buffer.copyOf(read)
    }

    companion object {
        /** Enough for any sane OAuth token/error payload; a bound against oversized bodies. */
        const val MAX_BODY_BYTES: Int = 64 * 1024
    }
}
