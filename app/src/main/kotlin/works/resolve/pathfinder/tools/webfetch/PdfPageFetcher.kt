package works.resolve.pathfinder.tools.webfetch

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [PageFetcher] for PDF URLs: downloads the document with the shared OkHttp
 * client and extracts text through androidx.pdf's isolated-process engine.
 * The body is spooled to a temp file because the engine needs a seekable
 * file descriptor, not a stream. Download cancellation propagates through
 * the OkHttp call; the open+extract phase carries its own timeout because it
 * runs in the platform service, outside the shared client's idle-cap model.
 */
class PdfPageFetcher(private val context: Context, private val client: OkHttpClient) : PageFetcher {

    override suspend fun fetch(url: String): PageContent {
        val response = execute(request = Request.Builder().url(url).build())
        response.use {
            if (!it.isSuccessful) {
                throw WebFetchException("Failed to fetch $url: HTTP ${it.code}")
            }
            val finalUrl = it.request.url.toString()
            val file = withContext(Dispatchers.IO) { spoolToTempFile(it, url) }
            try {
                val markdown = withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                    extractText(finalUrl, file)
                } ?: throw WebFetchException("Timed out extracting PDF from $url")
                // The engine exposes no document title, so there is none to report.
                return PageContent(
                    url = finalUrl,
                    title = null,
                    markdown = markdown,
                    httpStatus = it.code
                )
            } finally {
                // NonCancellable: an aborted fetch must still drop its temp
                // file — a plain withContext would throw before deleting.
                withContext(NonCancellable) { file.delete() }
            }
        }
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response)
                    }
                }
            )
        }

    /**
     * Streams the body to a temp file, enforcing the size cap both up front
     * (Content-Length, when present) and during the copy (streamed bodies
     * may lie or omit it). Exceeding the cap aborts the response.
     */
    private fun spoolToTempFile(response: Response, url: String): File {
        val contentLength = response.body.contentLength()
        if (contentLength > MAX_PDF_BYTES) {
            throw WebFetchException(
                "PDF at $url exceeds the ${MAX_PDF_BYTES / (1024 * 1024)} MiB fetch cap"
            )
        }
        val file = File.createTempFile(TMP_PREFIX, TMP_SUFFIX, context.cacheDir)
        try {
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PDF_BYTES) {
                            throw WebFetchException(
                                "PDF at $url exceeds the ${MAX_PDF_BYTES / (1024 * 1024)} MiB fetch cap"
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /**
     * Opens the spooled file and joins per-page text. Descriptor ownership
     * follows the loader's contract: on success the document closes the pfd;
     * on throw it is still ours, so both paths are covered here.
     */
    private suspend fun extractText(finalUrl: String, file: File): String {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        var document: PdfDocument? = null
        try {
            document = SandboxedPdfLoader(context).openDocument(Uri.parse(finalUrl), pfd)
            return buildString {
                for (page in 0 until document.pageCount) {
                    val text = document.getPageContent(page)
                        ?.textContents
                        ?.joinToString(separator = "\n") { it.text }
                    if (text != null) {
                        if (isNotEmpty()) append("\n\n")
                        append(text)
                    }
                }
            }
        } catch (e: PdfPasswordException) {
            throw WebFetchException("PDF at $finalUrl requires a password", e)
        } catch (e: IOException) {
            throw WebFetchException("Failed to read PDF at $finalUrl", e)
        } finally {
            if (document == null) {
                pfd.close()
            } else {
                document.close()
            }
        }
    }

    companion object {
        /** Mirrors [WebViewPageFetcher]'s whole-load budget for the open+extract phase. */
        private const val EXTRACT_TIMEOUT_MS = 30_000L

        /** Cap on a downloaded PDF; the engine's parse cost grows with document size. */
        private const val MAX_PDF_BYTES = 25L * 1024 * 1024

        private const val TMP_PREFIX = "webfetch"
        private const val TMP_SUFFIX = ".pdf"
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
