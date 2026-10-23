package works.resolve.pathfinder.ai.auth.oauth

import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt

class OAuthCallbackServerTest {

    private fun callbackUrl(redirectUri: String, params: Map<String, String>): String {
        val separator = if ('?' in redirectUri) '&' else '?'
        return redirectUri + separator + params.entries.joinToString("&") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=" +
                java.net.URLEncoder.encode(it.value, "UTF-8")
        }
    }

    private data class Page(val status: Int, val contentType: String?, val body: String)

    private fun page(url: String): Page {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            Page(
                status,
                connection.getHeaderField("content-type"),
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun start(
        port: Int = 0,
        state: String? = "expected-state",
        redirectHost: String? = null,
        timeoutMs: Long? = null,
        complete: suspend (String) -> String = { code -> "completed:$code" }
    ): OAuthCallbackServer<String> = runBlocking {
        startOAuthCallbackServer(
            OAuthCallbackServerOptions(
                providerName = "Example",
                host = "127.0.0.1",
                port = port,
                path = "/callback",
                redirectHost = redirectHost,
                state = state,
                complete = complete,
                timeoutMs = timeoutMs
            )
        )
    }

    @Test
    fun `ignores stray requests and resolves with the completed code`() = runBlocking {
        val server = start()
        try {
            assertTrue(
                Regex("^http://127\\.0\\.0\\.1:\\d+/callback$").matches(server.redirectUri),
                server.redirectUri
            )
            assertEquals(404, page(server.redirectUri.replace("/callback", "/other")).status)
            val wrongState = page(
                callbackUrl(server.redirectUri, mapOf("code" to "c", "state" to "other"))
            )
            assertEquals(400, wrongState.status)
            assertTrue(wrongState.body.contains("State mismatch."), wrongState.body)
            assertEquals(
                400,
                page(callbackUrl(server.redirectUri, mapOf("state" to "expected-state"))).status
            )

            val success = page(
                callbackUrl(
                    server.redirectUri,
                    mapOf("code" to "the-code", "state" to "expected-state")
                )
            )
            assertEquals(200, success.status)
            assertEquals("text/html; charset=utf-8", success.contentType)
            assertTrue(success.body.contains("Authentication successful"))
            assertTrue(success.body.contains("Signed in to Example."))
            assertTrue(success.body.contains("stroke=\"#F09082\""))
            assertTrue(success.body.contains("stroke=\"#4D9ABF\""))
            assertTrue(success.body.contains("stroke=\"#F1BE58\""))
            assertEquals("completed:the-code", server.wait())
        } finally {
            server.close()
        }
    }

    @Test
    fun `uses the redirect host and skips the state check when none is expected`() = runBlocking {
        val server = start(redirectHost = "localhost", state = null)
        try {
            assertTrue(
                Regex("^http://localhost:\\d+/callback$").matches(server.redirectUri),
                server.redirectUri
            )
            assertEquals(
                200,
                page(callbackUrl(server.redirectUri, mapOf("code" to "no-state"))).status
            )
            assertEquals("completed:no-state", server.wait())
        } finally {
            server.close()
        }
    }

    @Test
    fun `shows completion failures on the page and rejects the wait`() = runBlocking {
        val server = start(complete = { error("token exchange failed") })
        try {
            val failure = page(
                callbackUrl(
                    server.redirectUri,
                    mapOf("code" to "c", "state" to "expected-state")
                )
            )
            assertEquals(502, failure.status)
            assertTrue(failure.body.contains("Example sign-in failed."), failure.body)
            assertTrue(failure.body.contains("token exchange failed"), failure.body)
            val error = assertFailsWith<IllegalStateException> { server.wait() }
            assertEquals("token exchange failed", error.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects the wait when the provider redirects with an error`() = runBlocking {
        val server = start()
        try {
            val failure = page(
                callbackUrl(
                    server.redirectUri,
                    mapOf(
                        "error" to "access_denied",
                        "error_description" to "User denied access",
                        "state" to "expected-state"
                    )
                )
            )
            assertEquals(400, failure.status)
            assertTrue(failure.body.contains("User denied access"), failure.body)
            val error = assertFailsWith<IllegalStateException> { server.wait() }
            assertEquals(
                "Example authorization failed: User denied access",
                error.message
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `resolves with null after cancel and rejects late callbacks`() = runBlocking {
        val server = start()
        try {
            server.cancel()
            assertNull(server.wait())
            assertEquals(
                409,
                page(
                    callbackUrl(
                        server.redirectUri,
                        mapOf("code" to "c", "state" to "expected-state")
                    )
                ).status
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `completes only the first callback`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server = start(
            complete = { code ->
                started.complete(Unit)
                release.await()
                "completed:$code"
            }
        )
        try {
            val url = callbackUrl(
                server.redirectUri,
                mapOf("code" to "c", "state" to "expected-state")
            )
            val first = async(Dispatchers.IO) { page(url) }
            started.await()
            val second = async(Dispatchers.IO) { page(url) }
            // pi answers the second callback while the first exchange is in flight.
            assertEquals(409, withTimeout(5_000) { second.await() }.status)
            // A claimed callback keeps completing even when the caller switches to manual input.
            server.cancel()
            release.complete(Unit)
            assertEquals(200, first.await().status)
            assertEquals("completed:c", server.wait())
        } finally {
            release.complete(Unit)
            server.close()
        }
    }

    @Test
    fun `times out the wait and handles later callbacks as already handled`() = runBlocking {
        val server = start(timeoutMs = 10)
        try {
            val error = assertFailsWith<IllegalStateException> { server.wait() }
            assertEquals("Example sign-in timed out", error.message)
            assertEquals(
                409,
                page(
                    callbackUrl(
                        server.redirectUri,
                        mapOf("code" to "c", "state" to "expected-state")
                    )
                ).status
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `fails to start when the requested port is taken`() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { blocker ->
            val error = assertFailsWith<IllegalStateException> {
                start(port = blocker.localPort)
            }
            assertTrue(error.message!!.contains("Could not bind"), error.message)
        }
    }

    private class TestInteraction(private val answer: suspend () -> String) : AuthInteraction {
        var promptCount = 0

        override suspend fun prompt(prompt: AuthPrompt): String {
            promptCount++
            return answer()
        }

        override suspend fun notify(event: AuthEvent) {}
    }

    @Test
    fun `manual input wins without a callback server`() = runBlocking {
        val result = waitForCallbackOrManualInput<String>(
            TestInteraction { "pasted" },
            null,
            "paste",
            "http://localhost/callback"
        )
        assertEquals(CallbackOrManualInput.Manual("pasted"), result)
    }

    @Test
    fun `propagates manual prompt failures`() = runBlocking {
        val error = assertFailsWith<IllegalStateException> {
            waitForCallbackOrManualInput<String>(
                TestInteraction { error("prompt cancelled") },
                null,
                "paste",
                "http://localhost/callback"
            )
        }
        assertEquals("prompt cancelled", error.message)
    }

    @Test
    fun `browser callback wins and cancels the pending manual prompt`() = runBlocking {
        val server = start(state = null)
        try {
            val interaction = TestInteraction {
                kotlinx.coroutines.awaitCancellation()
            }
            val result = async {
                waitForCallbackOrManualInput(
                    interaction,
                    server,
                    "paste",
                    server.redirectUri
                )
            }
            withContext(Dispatchers.IO) {
                page(callbackUrl(server.redirectUri, mapOf("code" to "from-browser")))
            }
            assertEquals(
                CallbackOrManualInput.Callback("completed:from-browser"),
                result.await()
            )
        } finally {
            server.close()
        }
    }
}
