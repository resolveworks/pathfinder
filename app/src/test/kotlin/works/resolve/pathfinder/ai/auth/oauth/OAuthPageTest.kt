package works.resolve.pathfinder.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the OAuth result pages, ported from pi
 * `packages/ai/src/auth/oauth/oauth-page.ts`. Upstream has no dedicated tests
 * for `oauth-page.ts`; these assert the template structure and the escaping
 * that pi's callback servers depend on.
 */
class OAuthPageTest {

    @Test
    fun `escapeHtml escapes ampersands brackets quotes and apostrophes`() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", escapeHtml("&<>\"'"))
    }

    @Test
    fun `escapeHtml does not touch ordinary text`() {
        assertEquals("Authentication successful", escapeHtml("Authentication successful"))
    }

    @Test
    fun `success page uses the success title heading and message`() {
        val html = oauthSuccessHtml("OpenAI authentication completed. You can close this window.")
        assertTrue(html.startsWith("<!doctype html>\n<html lang=\"en\">"))
        assertTrue(html.contains("<title>Authentication successful</title>"))
        assertTrue(html.contains("<h1>Authentication successful</h1>"))
        assertTrue(html.contains("<p>OpenAI authentication completed. You can close this window.</p>"))
        assertFalse(html.contains("class=\"details\""))
    }

    @Test
    fun `error page uses the failure title heading and message`() {
        val html = oauthErrorHtml("State mismatch.")
        assertTrue(html.contains("<title>Authentication failed</title>"))
        assertTrue(html.contains("<h1>Authentication failed</h1>"))
        assertTrue(html.contains("<p>State mismatch.</p>"))
        assertFalse(html.contains("class=\"details\""))
    }

    @Test
    fun `error page renders details block only when details are given`() {
        val html = oauthErrorHtml("Anthropic authentication did not complete.", "Error: access_denied")
        assertTrue(html.contains("<div class=\"details\">Error: access_denied</div>"))
        assertFalse(oauthErrorHtml("Missing code or state parameter.").contains("class=\"details\""))
    }

    @Test
    fun `message and details are html-escaped`() {
        val html = oauthErrorHtml("<script>&", "\"injected\" <b>details</b>")
        assertTrue(html.contains("<p>&lt;script&gt;&amp;</p>"))
        assertTrue(html.contains("<div class=\"details\">&quot;injected&quot; &lt;b&gt;details&lt;/b&gt;</div>"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<b>details</b>"))
    }

    @Test
    fun `page embeds the logo svg and dark theme styles from upstream`() {
        val html = oauthSuccessHtml("signed in")
        assertTrue(html.contains("viewBox=\"0 0 800 800\""))
        assertTrue(html.contains("<div class=\"logo\">"))
        assertTrue(html.contains("--page-bg: #09090b;"))
        assertTrue(html.contains("color-scheme: dark;"))
    }
}
