package works.resolve.pathfinder.tools.webfetch

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Routes by URL shape: paths whose last segment ends in `.pdf` go to the PDF
 * fetcher, everything else to the WebView fetcher. No content sniffing — a
 * PDF served without the extension renders (or fails) in the WebView like any
 * page.
 */
class RoutingPageFetcher(
    private val webViewFetcher: PageFetcher,
    private val pdfFetcher: PageFetcher
) : PageFetcher {

    override suspend fun fetch(url: String): PageContent =
        if (isPdfPath(url.toHttpUrl().encodedPath)) {
            pdfFetcher.fetch(url)
        } else {
            webViewFetcher.fetch(url)
        }

    companion object {
        internal fun isPdfPath(path: String): Boolean =
            path.substringAfterLast('/').endsWith(".pdf", ignoreCase = true)
    }
}
