package works.resolve.pathfinder.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens a URL in a Chrome Custom Tab (RFC 8252's external browser user-agent
 * for OAuth) rather than an embedded view; deliberately does nothing when no
 * browser is installed.
 */
fun Context.openInCustomTab(url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().build().launchUrl(this, uri)
        return
    } catch (_: ActivityNotFoundException) {
    }
    try {
        startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: ActivityNotFoundException) {
    }
}
