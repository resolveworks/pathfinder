package works.resolve.pathfinder.tools.webfetch

import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.str
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [PageFetcher] that renders each page in a throwaway hidden WebView.
 * "Anonymous" means only that fetching runs in the app-owned `web_fetch`
 * WebView profile — kept separate from the default profile a user-facing
 * WebView would share — not network anonymity. A process-wide mutex
 * serializes fetches (one WebView at a time), and a fetched page's URL and
 * content are never logged.
 */
class WebViewPageFetcher(private val context: Context) : PageFetcher {

    private val mutex = Mutex()

    override suspend fun fetch(url: String): PageContent = mutex.withLock {
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadInHiddenWebView(url) }
            ?: throw WebFetchException("Timed out loading $url")
    }

    private suspend fun loadInHiddenWebView(url: String): PageContent = withContext(Dispatchers.Main) {
        val webView = WebView(context)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                WebViewCompat.setProfile(webView, PROFILE_NAME)
            }
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true

            val loaded = CompletableDeferred<String>()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    loaded.complete(url)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // Some pages emit a spurious about:blank main-frame error
                    // alongside a successful load; only real navigations fail.
                    if (request.isForMainFrame && request.url.toString() != "about:blank") {
                        loaded.completeExceptionally(
                            WebFetchException("Failed to load $url: ${error.description}"),
                        )
                    }
                }
            }

            webView.loadUrl(url)
            loaded.await()
            // Let late-running page scripts render before reading the DOM.
            delay(PAGE_SETTLE_DELAY_MS)

            val encoded = webView.evaluateSuspending(EXTRACTION_SCRIPT)
            parseExtracted(url, webView.url ?: url, encoded)
        } finally {
            withContext(NonCancellable) {
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
        }
    }

    /** Unwraps the JSON-encoded script result into a [PageContent]. */
    private fun parseExtracted(requestedUrl: String, finalUrl: String, encoded: String?): PageContent {
        if (encoded == null || encoded == "null") {
            throw WebFetchException("No content extracted from $requestedUrl")
        }
        val payload = (lenientJson.parseToJsonElement(encoded) as? JsonPrimitive)?.content
            ?: throw WebFetchException("Unexpected extraction result from $requestedUrl")
        val page = lenientJson.parseToJsonElement(payload) as? JsonObject
            ?: throw WebFetchException("Unexpected extraction result from $requestedUrl")
        return PageContent(
            url = finalUrl,
            title = page.str("title")?.takeIf { it.isNotBlank() },
            text = page.str("text").orEmpty(),
        )
    }

    private suspend fun WebView.evaluateSuspending(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { value -> continuation.resume(value) }
        }

    private companion object {
        const val PROFILE_NAME = "web_fetch"
        const val LOAD_TIMEOUT_MS = 30_000L
        const val PAGE_SETTLE_DELAY_MS = 500L
        const val EXTRACTION_SCRIPT =
            "(function(){return JSON.stringify({title:document.title," +
                "text:document.body?document.body.innerText:''});})()"
    }
}
