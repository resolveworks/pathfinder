package works.resolve.pathfinder.ai.auth.oauth

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The narrow Android stand-in for pi's `fetch`: a single request executed
 * under one whole-exchange deadline, returning status/body/headers for the
 * flow to interpret — matching pi, non-2xx handling is the caller's decision,
 * not the transport's.
 *
 * Network-level failures throw [IOException], with whole-exchange deadline
 * expiry surfaced as [SocketTimeoutException] (OkHttp reports it as a bare
 * `InterruptedIOException("timeout")`); coroutine cancellation cancels the
 * underlying call and propagates as
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
    /**
     * Deadline for the complete exchange — DNS, connect, request write, and
     * response read together, mirroring pi's single `AbortSignal.timeout`.
     */
    val timeoutMs: Int
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

    override fun equals(other: Any?): Boolean = other is OAuthHttpRequest &&
        other.method == method &&
        other.url == url &&
        other.headers == headers &&
        other.body.contentEquals(body) &&
        other.timeoutMs == timeoutMs

    override fun hashCode(): Int =
        31 * (31 * (31 * method.hashCode() + url.hashCode()) + headers.hashCode()) +
            body.contentHashCode()
}

/** [body] may carry credential values; it is redacted in [toString]. */
data class OAuthHttpResponse(
    val status: Int,
    /** Header names lower-cased. */
    val headers: Map<String, List<String>>,
    val body: ByteArray
) {
    override fun toString(): String =
        "OAuthHttpResponse(status=$status, headers=${headers.keys}, body=<${body.size} bytes>)"

    override fun equals(other: Any?): Boolean = other is OAuthHttpResponse &&
        other.status == status &&
        other.headers == headers &&
        other.body.contentEquals(body)

    override fun hashCode(): Int = 31 * (31 * status + headers.hashCode()) + body.contentHashCode()
}

/**
 * Platform [OAuthHttpClient] over OkHttp. The request's [OAuthHttpRequest.timeoutMs]
 * is applied as OkHttp's per-client call timeout — one deadline covering DNS
 * through the last response byte, like pi's `AbortSignal.timeout` — with the
 * connect timeout capped at the same budget so a late connect can never
 * extend it. Response bodies are read in full, unbounded, like pi's
 * `await response.text()` / `.json()`.
 *
 * Deliberately a private client rather than the app's shared streaming
 * client (see the composition root's topology note): whole-exchange call
 * deadlines are a different model than the shared client's inter-read idle
 * cap plus per-request header-phase deadlines, and sharing would couple the
 * two.
 */
class OkHttpOAuthHttpClient : OAuthHttpClient {

    /** Derivatives share this client's dispatcher and connection pool. */
    private val baseClient = OkHttpClient()

    override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val client = baseClient
                .newBuilder()
                .callTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .connectTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                // The call deadline is the only read/write bound; a stalled
                // exchange fails with it, not with a separate per-read
                // timeout.
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val call = client.newCall(request.toOkHttp())
            // Cancellation must cancel the in-flight call so a blocked
            // exchange unblocks; the cancelled continuation then discards
            // any late result/exception.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        // OkHttp reports call-deadline expiry as
                        // InterruptedIOException("timeout"); surface the
                        // timeout flavor the flows recognize.
                        val failure: IOException =
                            if (e is InterruptedIOException && e.message == "timeout") {
                                SocketTimeoutException("timeout").apply { initCause(e) }
                            } else {
                                e
                            }
                        continuation.resumeWith(Result.failure(failure))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            continuation.resumeWith(
                                Result.success(
                                    OAuthHttpResponse(
                                        status = it.code,
                                        headers = it.headers.toMultimap().mapKeys { (name, _) ->
                                            name.lowercase(Locale.ROOT)
                                        },
                                        body = it.body.bytes()
                                    )
                                )
                            )
                        }
                    }
                }
            )
        }

    private fun OAuthHttpRequest.toOkHttp(): Request = Request.Builder()
        .url(url)
        // OkHttp rejects GET/HEAD request bodies; those requests are
        // always bodyless here.
        .method(
            method,
            if (method.equals("GET", ignoreCase = true) ||
                method.equals("HEAD", ignoreCase = true)
            ) {
                null
            } else {
                body.toRequestBody()
            }
        )
        .apply {
            for ((name, value) in headers) header(name, value)
        }
        .build()
}
