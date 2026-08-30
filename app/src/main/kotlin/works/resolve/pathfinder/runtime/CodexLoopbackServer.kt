package works.resolve.pathfinder.runtime

import io.ktor.http.parseQueryString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * One-shot loopback HTTP listener that completes the Codex browser sign-in
 * on-device the way pi's CLI does on desktop (`startLocalOAuthServer` in
 * `packages/ai/src/auth/oauth/openai-codex.ts`): the authorize URL is opened
 * in the user's default browser (sharing the browser's own login session),
 * and when OpenAI redirects to the registered loopback redirect
 * `http://localhost:1455/auth/callback`, the browser's request lands on this
 * listener instead of a dead port.
 *
 * Responses mirror pi's server so the browser shows the flow's outcome: 404
 * for foreign paths, 400 with an error page for a `state` mismatch or a
 * missing code, and the success page ("OpenAI authentication completed. You
 * can close this window.") for the valid callback. The valid callback's full
 * URL is returned from [awaitRedirect] and validated again (defense in
 * depth) plus exchanged by [CodexOAuthClient.completeBrowserLogin].
 *
 * Pure socket component: no HTTP client and no Android dependencies, bound
 * to the loopback interface only (the fixed registered port; overridable so
 * tests can bind an ephemeral one). [close] or coroutine cancellation
 * (accepts and request reads are interruptible) tears the listener down at
 * any point; a redirect arriving afterwards is simply refused by the OS.
 */
class CodexLoopbackServer(private val state: String) : AutoCloseable {

    /** Registered Codex redirect port (pi's REDIRECT_URI). */
    private val callbackPath = "/auth/callback"

    /** Header-read guard so a silent peer cannot pin the accept loop. */
    private val headerTimeoutMillis = 10_000

    /** Request-head cap: the callback is a short GET line plus headers. */
    private val maxHeadBytes = 16 * 1024

    private var serverSocket: ServerSocket? = null

    /** The bound port: the registered redirect port, or the ephemeral one when tests bind 0. */
    val port: Int
        get() = requireBound().localPort

    /**
     * Binds [port] on the loopback interface. Must be called (and succeed)
     * before the authorize URL is opened in the browser, so the redirect can
     * only ever arrive on a listening socket. Idempotent per instance is not
     * supported: one listener serves one sign-in attempt.
     */
    suspend fun bind(port: Int = REDIRECT_PORT) {
        withContext(Dispatchers.IO) {
            val server = ServerSocket()
            server.reuseAddress = true
            try {
                server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            } catch (e: IOException) {
                server.close()
                throw CodexOAuthException("Sign-in could not be started.")
            }
            serverSocket = server
        }
    }

    /**
     * Accepts requests until the valid callback arrives and returns its full
     * URL (origin-form targets are reconstructed against the bound port).
     * Suspends while waiting — there is deliberately no timeout, matching
     * pi's server; cancellation is the exit. Foreign or invalid requests are
     * answered and skipped, so stray connections (favicons, preconnects)
     * cannot consume the listener.
     */
    suspend fun awaitRedirect(): String {
        val server = requireBound()
        while (true) {
            val socket = runInterruptible(Dispatchers.IO) { server.accept() }
            try {
                val callback = runInterruptible(Dispatchers.IO) { serve(socket) }
                if (callback != null) return callback
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    /** Closes the listener; a redirect arriving afterwards is refused. */
    override fun close() {
        val server = serverSocket ?: return
        serverSocket = null
        runCatching { server.close() }
    }

    private fun requireBound(): ServerSocket =
        serverSocket ?: throw CodexOAuthException("Sign-in could not be started.")

    /**
     * Answers one request: returns the full callback URL for the valid
     * redirect, or null when the request must be skipped (the accept loop
     * then continues waiting).
     */
    private fun serve(socket: Socket): String? {
        socket.soTimeout = headerTimeoutMillis
        val head = readRequestHead(socket) ?: return null
        val target = head.substringBefore("\r\n").split(' ').getOrNull(1)
        if (target.isNullOrEmpty()) {
            respond(socket, 400, "Bad Request", errorPage("Malformed request."))
            return null
        }
        if (target.substringBefore('?') != callbackPath) {
            respond(socket, 404, "Not Found", errorPage("Callback route not found."))
            return null
        }
        // Browsers send origin-form targets; absolute-form is accepted too.
        val url = if (target.startsWith("http")) target else "http://localhost:${socket.localPort}$target"
        val query = runCatching { parseQueryString(URI(url).rawQuery ?: "") }.getOrNull()
        if (query == null || query["state"] != state) {
            respond(socket, 400, "Bad Request", errorPage("State mismatch."))
            return null
        }
        if (query["code"].isNullOrEmpty()) {
            respond(socket, 400, "Bad Request", errorPage("Missing authorization code."))
            return null
        }
        respond(socket, 200, "OK", successPage())
        return url
    }

    /** Reads up to the end of the request head; null when unreadable or truncated. */
    private fun readRequestHead(socket: Socket): String? = try {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        val input = BufferedInputStream(socket.getInputStream())
        while (output.size() < maxHeadBytes) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.toString(StandardCharsets.ISO_8859_1.name()).contains("\r\n\r\n")) break
        }
        output.toString(StandardCharsets.ISO_8859_1.name()).takeIf { it.isNotBlank() }
    } catch (_: IOException) {
        null
    }

    private fun respond(socket: Socket, status: Int, reason: String, body: String) = try {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(payload)
            flush()
        }
    } catch (_: IOException) {
        // The peer went away; nothing to answer.
    }

    /** pi's oauthSuccessHtml, reduced to the message the user needs. */
    private fun successPage(): String = page(
        "Authentication successful",
        "OpenAI authentication completed. You can close this window.",
    )

    /** pi's oauthErrorHtml, reduced to the message the user needs. */
    private fun errorPage(message: String): String = page("Authentication failed", message)

    private fun page(heading: String, message: String): String = buildString {
        append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(heading).append("</title>\n")
        append(
            "<style>html{color-scheme:dark}body{margin:0;min-height:100vh;display:flex;" +
                "align-items:center;justify-content:center;background:#09090b;color:#fafafa;" +
                "font-family:system-ui,-apple-system,sans-serif;text-align:center}" +
                "p{color:#a1a1aa;line-height:1.7}</style>\n",
        )
        append("</head>\n<body>\n<main>\n")
        append("<h1>").append(heading).append("</h1>\n")
        append("<p>").append(message).append("</p>\n")
        append("</main>\n</body>\n</html>\n")
    }

    private companion object {
        /** Loopback port of the registered Codex browser redirect. */
        const val REDIRECT_PORT = 1455
    }
}
