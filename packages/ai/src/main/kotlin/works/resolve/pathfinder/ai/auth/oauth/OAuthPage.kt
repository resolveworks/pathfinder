package works.resolve.pathfinder.ai.auth.oauth

/**
 * OAuth success/error pages for the loopback callback servers
 * ([LoopbackOAuthServer]) to serve into the on-device browser. Rendering
 * must stay byte-for-byte identical to pi's `oauth-page.ts`: same markup,
 * CSS, and escaping.
 */
private const val LOGO_SVG =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 800\" aria-hidden=\"true\"><path fill=\"#fff\" fill-rule=\"evenodd\" d=\"M165.29 165.29 H517.36 V400 H400 V517.36 H282.65 V634.72 H165.29 Z M282.65 282.65 V400 H400 V282.65 Z\"/><path fill=\"#fff\" d=\"M517.36 400 H634.72 V634.72 H517.36 Z\"/></svg>"

internal fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun renderPage(
    title: String,
    heading: String,
    message: String,
    details: String? = null
): String {
    val escapedTitle = escapeHtml(title)
    val escapedHeading = escapeHtml(heading)
    val escapedMessage = escapeHtml(message)
    val escapedDetails = details?.let { escapeHtml(it) }

    return """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$escapedTitle</title>
  <style>
    :root {
      --text: #fafafa;
      --text-dim: #a1a1aa;
      --page-bg: #09090b;
      --font-sans: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji";
      --font-mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
    }
    * { box-sizing: border-box; }
    html { color-scheme: dark; }
    body {
      margin: 0;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
      background: var(--page-bg);
      color: var(--text);
      font-family: var(--font-sans);
      text-align: center;
    }
    main {
      width: 100%;
      max-width: 560px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    }
    .logo {
      width: 72px;
      height: 72px;
      display: block;
      margin-bottom: 24px;
    }
    h1 {
      margin: 0 0 10px;
      font-size: 28px;
      line-height: 1.15;
      font-weight: 650;
      color: var(--text);
    }
    p {
      margin: 0;
      line-height: 1.7;
      color: var(--text-dim);
      font-size: 15px;
    }
    .details {
      margin-top: 16px;
      font-family: var(--font-mono);
      font-size: 13px;
      color: var(--text-dim);
      white-space: pre-wrap;
      word-break: break-word;
    }
  </style>
</head>
<body>
  <main>
    <div class="logo">${LOGO_SVG}</div>
    <h1>$escapedHeading</h1>
    <p>$escapedMessage</p>
    ${escapedDetails?.let { "<div class=\"details\">$it</div>" } ?: ""}
  </main>
</body>
</html>"""
}

fun oauthSuccessHtml(message: String): String = renderPage(
    title = "Authentication successful",
    heading = "Authentication successful",
    message = message
)

fun oauthErrorHtml(message: String, details: String? = null): String = renderPage(
    title = "Authentication failed",
    heading = "Authentication failed",
    message = message,
    details = details
)
