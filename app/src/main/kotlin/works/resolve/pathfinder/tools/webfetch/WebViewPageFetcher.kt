package works.resolve.pathfinder.tools.webfetch

import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.utils.lenientJson

/**
 * [PageFetcher] that renders each page in a throwaway hidden WebView and
 * extracts its main readable content as markdown by injecting the defuddle
 * full bundle into the page context. "Anonymous" means only that fetching runs
 * in the app-owned `web_fetch` WebView profile — kept separate from the
 * default profile a user-facing WebView would share — not network anonymity.
 * Fetches run concurrently, and a fetched page's URL and content are never
 * logged.
 *
 * Defuddle's synchronous `parse()` runs in the page's main world alongside
 * untrusted page JavaScript; that is acceptable here because the page is
 * destroyed immediately after extraction and its output is treated as
 * untrusted data. Async extractors (network-backed) are not used: they would
 * be CORS-blocked in-page anyway. The bundle itself is CSP-exempt because
 * `evaluateJavascript` executes directly in the renderer rather than as
 * page-loaded script. No `url` option is passed — `document.URL` is
 * authoritative in-page.
 */
class WebViewPageFetcher(private val context: Context) : PageFetcher {

    private val defuddleBundle: String by lazy { readDefuddleBundle() }

    override suspend fun fetch(url: String): PageContent =
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadInHiddenWebView(url) }
            ?: throw WebFetchException("Timed out loading $url")

    private fun readDefuddleBundle(): String = try {
        context.assets.open(DEFUDDLE_ASSET_PATH).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        throw WebFetchException("Missing defuddle bundle asset '$DEFUDDLE_ASSET_PATH'", e)
    }

    private suspend fun loadInHiddenWebView(url: String): PageContent =
        withContext(Dispatchers.Main) {
            val webView = WebView(context)
            try {
                // No profile-support fallback: on a WebView without profile
                // support this fails loudly instead of sharing the default
                // profile's cookies and storage.
                WebViewCompat.setProfile(webView, PROFILE_NAME)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true

                val loaded = CompletableDeferred<String>()
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.complete(url)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        // Some pages emit a spurious about:blank main-frame error
                        // alongside a successful load; only real navigations fail.
                        if (request.isForMainFrame && request.url.toString() != "about:blank") {
                            loaded.completeExceptionally(
                                WebFetchException("Failed to load $url: ${error.description}")
                            )
                        }
                    }
                }

                webView.loadUrl(url)
                loaded.await()
                // Let late-running page scripts render before reading the DOM.
                delay(PAGE_SETTLE_DELAY_MS)

                val encoded = webView.evaluateSuspending(defuddleBundle + "\n" + EXTRACTION_WRAPPER)
                parseExtraction(url, webView.url ?: url, encoded)
            } finally {
                withContext(NonCancellable) {
                    runCatching { webView.stopLoading() }
                    runCatching { webView.destroy() }
                }
            }
        }

    private suspend fun WebView.evaluateSuspending(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { value -> continuation.resume(value) }
        }

    companion object {

        /**
         * Strictly decodes the single JSON-encoded result of the extraction
         * wrapper into a [PageContent]. Any deviation — null result,
         * non-object payload, error report, missing or non-string `markdown` —
         * throws [WebFetchException]; there are no fallbacks.
         */
        internal fun parseExtraction(
            requestedUrl: String,
            finalUrl: String,
            encoded: String?
        ): PageContent {
            if (encoded == null || encoded == "null") {
                throw WebFetchException("No content extracted from $requestedUrl")
            }
            val page = lenientJson.parseToJsonElement(encoded) as? JsonObject
                ?: throw WebFetchException("Unexpected extraction result from $requestedUrl")
            (page["error"] as? JsonPrimitive)?.let {
                throw WebFetchException("$requestedUrl: ${it.content}")
            }
            if (page["error"] != null) {
                throw WebFetchException("Unexpected extraction result from $requestedUrl")
            }
            val markdown = (page["markdown"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?: throw WebFetchException("Unexpected extraction result from $requestedUrl")
            val title = (page["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return PageContent(url = finalUrl, title = title, markdown = markdown.content)
        }

        private const val PROFILE_NAME = "web_fetch"
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val PAGE_SETTLE_DELAY_MS = 500L
        private const val DEFUDDLE_ASSET_PATH = "defuddle/index.js"

        /**
         * Runs defuddle's synchronous parse against the live page DOM and
         * returns the result object; `evaluateJavascript` hands the callback
         * its JSON encoding exactly once. The wrapper's only job is the
         * try/catch: `evaluateJavascript` has no JS-exception channel, so an
         * uncaught throw would surface as `null`, indistinguishable from an
         * empty page.
         */
        const val EXTRACTION_WRAPPER =
            ";(function(){try{const r=new Defuddle(document,{markdown:true}).parse();" +
                "return{title:r.title,markdown:r.content}}" +
                "catch(e){return{error:String(e&&e.message||e)}}})()"
    }
}
