package works.resolve.pathfinder.ai.auth.oauth

/**
 * OAuth success/error pages for the loopback callback servers
 * ([LoopbackOAuthServer]) to serve into the on-device browser. Layout, CSS,
 * and escaping follow pi's `oauth-page.ts`; the logo is Pathfinder's own
 * serpentine mark in the shared accent palette.
 */
private const val LOGO_SVG =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\" aria-hidden=\"true\">" +
        "<path fill=\"none\" stroke=\"#F09082\" stroke-linecap=\"round\" stroke-width=\"4.2\" " +
        "d=\"M53.1,68.4L61.36,68.4M69.16,68.36A7.2,7.2 0 0 0 75.24,63.45" +
        "M73.59,56.21A7.2,7.2 0 0 0 68.4,54L65.93,54M58.13,54L49.87,54" +
        "M42.07,54L39.6,54A7.2,7.2 0 0 1 34.41,51.79" +
        "M32.76,44.55A7.2,7.2 0 0 1 38.84,39.64M46.64,39.6L54.9,39.6\"/>" +
        "<path fill=\"none\" stroke=\"#4D9ABF\" stroke-width=\"6\" " +
        "d=\"M39.6,63.6A4.8,4.8 0 1 1 39.6,73.2A4.8,4.8 0 1 1 39.6,63.6Z\"/>" +
        "<path fill=\"none\" stroke=\"#F1BE58\" stroke-linecap=\"round\" stroke-width=\"6\" " +
        "d=\"M63.6,34.8L73.2,44.4M73.2,34.8L63.6,44.4\"/>" +
        "</svg>"

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
