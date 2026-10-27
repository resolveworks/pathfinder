package works.resolve.pathfinder.tools.websearch

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import works.resolve.pathfinder.ai.utils.MAX_PROVIDER_ERROR_BODY_CHARS

/** A response whose body was fully read on the OkHttp dispatcher thread. */
class ServedResponse(val code: Int, val reasonPhrase: String?, val body: String) {
    val successful: Boolean get() = code in 200..299
}

/**
 * The one OkHttp send bridge shared by every search executor, preserving
 * the cancellation and error-body-capping semantics the web_search tool
 * always had.
 */
class SearchTransport(private val client: OkHttpClient) {

    /**
     * The callback consumes the response (body included) before resuming, so
     * the continuation only ever owns plain values and a cancellation racing
     * the resume can never leak one. Cancellation of the call itself goes
     * through [continuation.invokeOnCancellation][kotlinx.coroutines.CancellableContinuation.invokeOnCancellation];
     * the resulting `onFailure` resume on an already-cancelled continuation
     * is dropped.
     */
    suspend fun send(request: Request): ServedResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        response.use {
                            val body = try {
                                if (it.isSuccessful) {
                                    it.body.string()
                                } else {
                                    // Read at most 4x the char cap in bytes (UTF-8 worst
                                    // case) so a huge error body is never fully buffered
                                    // just to be truncated.
                                    val readLimit = MAX_PROVIDER_ERROR_BODY_CHARS.toLong() * 4
                                    val source = it.body.source()
                                    source.request(readLimit)
                                    val buffered = source.buffer
                                    buffered.readUtf8(minOf(buffered.size, readLimit))
                                }
                            } catch (e: IOException) {
                                continuation.resumeWithException(e)
                                return
                            }
                            continuation.resume(ServedResponse(it.code, it.message, body))
                        }
                    }
                }
            )
        }
}
