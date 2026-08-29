package works.resolve.pathfinder.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens an OAuth login URL in a Chrome Custom Tab, keeping the browser
 * user-agent session separate from the app's default browser (RFC 8252).
 * Falls back to a plain [Intent.ACTION_VIEW] when no Custom Tab provider is
 * available; if no handler exists either, does nothing.
 */
fun Context.openInCustomTab(url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().build().launchUrl(this, uri)
        return
    } catch (_: ActivityNotFoundException) {
        // No Custom Tab provider; try the default VIEW intent below.
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: ActivityNotFoundException) {
        // No browser available; nothing to do.
    }
}
