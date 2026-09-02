package works.resolve.pathfinder.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import works.resolve.pathfinder.ai.testing.FakeClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.Pkce
import works.resolve.pathfinder.ai.auth.PkceGenerator
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL

class AnthropicOAuthAuthTest {

    private class RecordingInteraction(
        val manualCodeResponse: String = "the-code",
    ) : AuthInteraction {
        val events = mutableListOf<AuthEvent>()
        val prompts = mutableListOf<AuthPrompt>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            return manualCodeResponse
        }

        override suspend fun notify(event: AuthEvent) {
            events += event
        }
    }

    private class PendingInteraction(
        val answer: CompletableDeferred<String> = CompletableDeferred(),
    ) : AuthInteraction {
        val events = mutableListOf<AuthEvent>()
        val prompts = mutableListOf<AuthPrompt>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            return answer.await()
        }

        override suspend fun notify(event: AuthEvent) {
            events += event
        }
    }

    private class FakeHttpClient(
        var respond: suspend (OAuthHttpRequest) -> OAuthHttpResponse = {
            OAuthHttpResponse(200, emptyMap(), anthropicTokenBody("access", "refresh").toByteArray())
        },
    ) : OAuthHttpClient {
        val requests = mutableListOf<OAuthHttpRequest>()
        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            return respond(request)
        }
    }

    private fun fixedPkce(): PkceGenerator = PkceGenerator(randomBytes = { count -> ByteArray(count) { it.toByte() } })

    private fun pkce(): Pkce = fixedPkce().generate()

    private fun flow(
        clock: Clock = FakeClock(1_000_000L),
        port: Int = 0,
    ): Pair<AnthropicOAuthAuth, FakeHttpClient> {
        val http = FakeHttpClient()
        return AnthropicOAuthAuth(http, fixedPkce(), clock, callbackPort = port) to http
    }

    /** A currently-free loopback port (racy in principle; fine within one JVM). */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun jsonResponse(status: Int, body: String): OAuthHttpResponse =
        OAuthHttpResponse(status, emptyMap(), body.toByteArray())

    @Test
    fun `authorize URL carries pi's exact params, pkce challenge and verifier state`() = runBlocking {
        val (auth, http) = flow()
        val interaction = RecordingInteraction()
        val pair = pkce()

        auth.login(interaction)

        val urlEvent = interaction.events.filterIsInstance<AuthEvent.AuthUrl>().single()
        assertEquals(
            "https://claude.ai/oauth/authorize?code=true" +
                "&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
                "&response_type=code" +
                "&redirect_uri=http%3A%2F%2Flocalhost%3A53692%2Fcallback" +
                "&scope=org%3Acreate_api_key+user%3Aprofile+user%3Ainference+" +
                "user%3Asessions%3Aclaude_code+user%3Amcp_servers+user%3Afile_upload" +
                "&code_challenge=${pair.challenge}" +
                "&code_challenge_method=S256" +
                "&state=${pair.verifier}",
            urlEvent.url,
        )
        assertEquals(
            "Complete login in your browser. If the browser is on another machine, paste the final redirect URL here.",
            urlEvent.instructions,
        )
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `manual code prompt mirrors pi's message and placeholder`() = runBlocking {
        val (auth, _) = flow()
        val interaction = RecordingInteraction()

        auth.login(interaction)

        val prompt = assertIs<AuthPrompt.ManualCode>(interaction.prompts.single())
        assertEquals(
            "Complete login in your browser, or paste the authorization code / redirect URL here:",
            prompt.message,
        )
        assertEquals(AnthropicOAuthAuth.REDIRECT_URI, prompt.placeholder)
    }

    @Test
    fun `redirect URL, code-state, query string, and raw code inputs all resolve`() = runBlocking {
        val pair = pkce()
        val inputs = mapOf(
            "http://localhost:53692/callback?code=manual-code&state=${pair.verifier}" to "manual-code",
            "manual-code#${pair.verifier}" to "manual-code",
            "code=the-code&state=${pair.verifier}" to "the-code",
            "  raw-code  " to "raw-code",
        )
        for ((input, expected) in inputs) {
            val (auth, http) = flow()
            val credential = auth.login(RecordingInteraction(input))
            assertEquals("access", credential.access, "input: $input")
            val sent = Json.parseToJsonElement(http.requests.single().body.decodeToString()).jsonObject
            assertEquals(expected, sent["code"]!!.jsonPrimitive.content, "input: $input")
            assertEquals(pair.verifier, sent["state"]!!.jsonPrimitive.content, "input: $input")
        }
    }

    @Test
    fun `hash input keeps only the first two segments like js split limit 2`() = runBlocking {
        val pair = pkce()
        val (auth, http) = flow()
        val credential = auth.login(RecordingInteraction("manual-code#${pair.verifier}#ignored"))

        assertEquals("access", credential.access)
        val sent = Json.parseToJsonElement(http.requests.single().body.decodeToString()).jsonObject
        assertEquals(pair.verifier, sent["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `input without a code fails with Missing authorization code`() {
        for (input in listOf("https://claude.ai/oauth/authorize", "", "   ", "#")) {
            val (auth, http) = flow()
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { auth.login(RecordingInteraction(input)) }
            }
            assertEquals("Missing authorization code", error.message, "input: $input")
            assertTrue(http.requests.isEmpty(), "input: $input")
        }
    }

    @Test
    fun `state mismatch is reported before a missing code, like pi's parse order`() {
        val (auth, http) = flow()
        val error = assertFailsWith<IllegalStateException> {
            runBlocking {
                auth.login(RecordingInteraction("https://claude.ai/oauth/authorize?state=other"))
            }
        }
        assertEquals("OAuth state mismatch", error.message)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `state mismatch fails with pi's message`() {
        val (auth, http) = flow()
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.login(RecordingInteraction("https://localhost:53692/callback?code=c&state=other")) }
        }
        assertEquals("OAuth state mismatch", error.message)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `exchange posts pi's payload and applies the five minute expiry skew`() = runBlocking {
        val (auth, http) = flow(clock = FakeClock(1_000_000L))
        val pair = pkce()
        val interaction = RecordingInteraction("the-code")

        val credential = auth.login(interaction)

        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals(AnthropicOAuthAuth.TOKEN_URL, request.url)
        assertEquals(
            mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            request.headers,
        )
        assertEquals(AnthropicOAuthAuth.REQUEST_TIMEOUT_MS, request.timeoutMs)
        val sent = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertEquals("authorization_code", sent["grant_type"]!!.jsonPrimitive.content)
        assertEquals(AnthropicOAuthAuth.CLIENT_ID, sent["client_id"]!!.jsonPrimitive.content)
        assertEquals("the-code", sent["code"]!!.jsonPrimitive.content)
        assertEquals(pair.verifier, sent["state"]!!.jsonPrimitive.content)
        assertEquals(AnthropicOAuthAuth.REDIRECT_URI, sent["redirect_uri"]!!.jsonPrimitive.content)
        assertEquals(pair.verifier, sent["code_verifier"]!!.jsonPrimitive.content)

        assertEquals("access", credential.access)
        assertEquals("refresh", credential.refresh)
        assertEquals(1_000_000L + 3_600_000L - 300_000L, credential.expires)

        val progress = interaction.events.filterIsInstance<AuthEvent.Progress>().single()
        assertEquals("Exchanging authorization code for tokens...", progress.message)
    }

    @Test
    fun `refresh posts pi's payload without scope and rotates both tokens`() = runBlocking {
        val (auth, http) = flow(clock = FakeClock(2_000_000L))
        http.respond = { jsonResponse(200, anthropicTokenBody("new-access", "new-refresh")) }

        val credential = auth.refresh(OAuthCredential(access = "old-access", refresh = "refresh", expires = 0))

        val sent = Json.parseToJsonElement(http.requests.single().body.decodeToString()).jsonObject
        assertEquals(setOf("grant_type", "client_id", "refresh_token"), sent.keys)
        assertEquals("refresh_token", sent["grant_type"]!!.jsonPrimitive.content)
        assertEquals(AnthropicOAuthAuth.CLIENT_ID, sent["client_id"]!!.jsonPrimitive.content)
        assertEquals("refresh", sent["refresh_token"]!!.jsonPrimitive.content)

        assertEquals("new-access", credential.access)
        assertEquals("new-refresh", credential.refresh)
        assertEquals(2_000_000L + 3_600_000L - 300_000L, credential.expires)
    }

    @Test
    fun `toAuth derives request auth from the access token`() = runBlocking {
        val (auth, _) = flow()
        val modelAuth = auth.toAuth(OAuthCredential(access = "access", refresh = "refresh", expires = 0))
        assertEquals("access", modelAuth.apiKey)
        assertEquals(0, modelAuth.headers.size)
        assertEquals(null, modelAuth.baseUrl)
    }

    @Test
    fun `labels and subscription metadata mirror pi's anthropicOAuth`() {
        val (auth, _) = flow()
        assertEquals("Anthropic (Claude Pro/Max)", auth.name)
        assertTrue(auth.isSubscription)
        assertEquals(null, auth.loginLabel)
    }

    @Test
    fun `invalid JSON on exchange fails with pi's invalid JSON message`() {
        val (auth, _) = flow()
        val auth2 = AnthropicOAuthAuth(FakeHttpClient(respond = { jsonResponse(200, "not json") }), fixedPkce(), callbackPort = 0)
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth2.login(RecordingInteraction("the-code")) }
        }
        assertTrue(
            error.message!!.startsWith(
                "Token exchange returned invalid JSON. url=${AnthropicOAuthAuth.TOKEN_URL}; details=",
            ),
            error.message,
        )
    }

    @Test
    fun `invalid JSON on refresh fails with pi's invalid JSON message`() {
        val auth = AnthropicOAuthAuth(FakeHttpClient(respond = { jsonResponse(200, "{\"a\":1") }), fixedPkce(), callbackPort = 0)
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.refresh(OAuthCredential("a", "r", 0)) }
        }
        assertTrue(
            error.message!!.startsWith(
                "Anthropic token refresh returned invalid JSON. url=${AnthropicOAuthAuth.TOKEN_URL}; details=",
            ),
            error.message,
        )
    }

    @Test
    fun `missing, empty, and mistyped token fields fail with a named field`() {
        val bodies = listOf(
            "{}",
            """{"access_token":"","refresh_token":"r","expires_in":3600}""",
            """{"access_token":42,"refresh_token":"r","expires_in":3600}""",
            """{"access_token":"a","refresh_token":"","expires_in":3600}""",
            """{"access_token":"a","refresh_token":"r","expires_in":"3600"}""",
            """{"access_token":"a","refresh_token":"r","expires_in":0}""",
            """{"access_token":"a"}""",
        )
        for (body in bodies) {
            val auth = AnthropicOAuthAuth(FakeHttpClient(respond = { jsonResponse(200, body) }), fixedPkce(), callbackPort = 0)
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { auth.refresh(OAuthCredential("a", "r", 0)) }
            }
            assertTrue(
                error.message!!.startsWith("Invalid Anthropic OAuth response field: "),
                "body: $body -> ${error.message}",
            )
        }
    }

    @Test
    fun `non-2xx exchange carries pi's shape with a sanitized structured error`() {
        val auth = AnthropicOAuthAuth(
            FakeHttpClient(respond = { jsonResponse(400, """{"error":"invalid_grant","error_description":"code expired"}""") }),
            fixedPkce(),
        )
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.login(RecordingInteraction("the-code")) }
        }
        assertEquals(
            "Token exchange request failed. url=${AnthropicOAuthAuth.TOKEN_URL}; " +
                "redirect_uri=${AnthropicOAuthAuth.REDIRECT_URI}; response_type=authorization_code; " +
                "details=IllegalStateException: HTTP request failed. status=400; " +
                "url=${AnthropicOAuthAuth.TOKEN_URL}; body=error=invalid_grant: code expired",
            error.message,
        )
    }

    @Test
    fun `non-2xx refresh carries pi's shape and redacts an unparseable body`() {
        val auth = AnthropicOAuthAuth(FakeHttpClient(respond = { jsonResponse(401, "unauthorized") }), fixedPkce(), callbackPort = 0)
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.refresh(OAuthCredential("a", "stale", 0)) }
        }
        assertTrue(
            error.message!!.startsWith(
                "Anthropic token refresh request failed. url=${AnthropicOAuthAuth.TOKEN_URL}; " +
                    "details=IllegalStateException: HTTP request failed. status=401; "+
                    "url=${AnthropicOAuthAuth.TOKEN_URL}; body=<redacted>",
            ),
            error.message,
        )
    }

    @Test
    fun `bounded exchange timeout surfaces in pi's request failed details`() {
        val auth = AnthropicOAuthAuth(
            FakeHttpClient(respond = { throw SocketTimeoutException("read timed out") }),
            fixedPkce(),
        )
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.login(RecordingInteraction("the-code")) }
        }
        assertTrue(
            error.message!!.startsWith(
                "Token exchange request failed. url=${AnthropicOAuthAuth.TOKEN_URL}; " +
                    "redirect_uri=${AnthropicOAuthAuth.REDIRECT_URI}; response_type=authorization_code; " +
                    "details=SocketTimeoutException",
            ),
            error.message,
        )
    }

    @Test
    fun `cancellation propagates unwrapped from the exchange`() {
        val auth = AnthropicOAuthAuth(
            FakeHttpClient(respond = { throw CancellationException("cancelled") }),
            fixedPkce(),
        )
        val error = assertFailsWith<CancellationException> {
            runBlocking { auth.login(RecordingInteraction("the-code")) }
        }
        assertEquals("cancelled", error.message)
    }

    @Test
    fun `error messages and request tostring never carry secrets`() {
        val auth = AnthropicOAuthAuth(FakeHttpClient(respond = { jsonResponse(403, """{"error":"denied"}""") }), fixedPkce(), callbackPort = 0)
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.login(RecordingInteraction("secret-code")) }
        }
        for (secret in listOf("secret-code")) {
            assertTrue(!error.message!!.contains(secret), "secret leaked: ${error.message}")
        }
    }

    @Test
    fun `unparseable non-2xx bodies carrying secrets are never interpolated`() {
        val bodies = listOf(
            "access_token=sk-ant-oat-distinctive-access refresh_token=distinctive-refresh",
            "<html>bad code the-secret-code verifier the-secret-verifier</html>",
            // Valid JSON but no error envelope: other fields are not echoed.
            """{"access_token":"sk-ant-oat-distinctive-access","refresh_token":"distinctive-refresh"}""",
            "[\"sk-ant-oat-distinctive-access\"]",
        )
        for (body in bodies) {
            val auth = AnthropicOAuthAuth(FakeHttpClient(respond = { jsonResponse(500, body) }), fixedPkce(), callbackPort = 0)
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { auth.login(RecordingInteraction("the-secret-code")) }
            }
            val verifier = pkce().verifier
            assertNoSecrets(
                error.message,
                listOf("sk-ant-oat-distinctive-access", "distinctive-refresh", "the-secret-code", verifier, "the-secret-verifier"),
                "body: $body",
            )
        }
    }

    @Test
    fun `invalid json bodies carrying secrets are never echoed`() {
        val bodies = listOf(
            "not json sk-ant-oat-distinctive-access",
            """{"access_token":"sk-ant-oat-distinctive-access"""",
        )
        for (body in bodies) {
            val auth = AnthropicOAuthAuth(FakeHttpClient(respond = { jsonResponse(200, body) }), fixedPkce(), callbackPort = 0)
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { auth.login(RecordingInteraction("the-secret-code")) }
            }
            assertNoSecrets(
                error.message,
                listOf("sk-ant-oat-distinctive-access", "the-secret-code"),
                "body: $body",
            )
        }
    }

    @Test
    fun `request tostring redacts the token exchange body and url`() {
        val http = FakeHttpClient()
        runBlocking {
            try {
                AnthropicOAuthAuth(http, fixedPkce(), callbackPort = 0).login(RecordingInteraction("the-secret-code"))
            } catch (_: Exception) {
            }
        }
        val rendered = http.requests.single().toString()
        assertNoSecrets(
            rendered,
            listOf("the-secret-code", "sk-ant-oat", "access\":\"", pkce().verifier),
            "toString: $rendered",
        )
    }

    @Test
    fun `formEncode matches URLSearchParams including tilde`() {
        assertEquals("x=%7E+*", AnthropicOAuthAuth.formEncode(mapOf("x" to "~ *")))
    }

    private fun assertNoSecrets(text: String?, secrets: List<String>, context: String) {
        for (secret in secrets) {
            assertTrue(!text.orEmpty().contains(secret), "secret leaked: $secret in $context")
        }
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        val status = conn.responseCode
        val body = (if (status in 200..399) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return status to body
    }

    /** Retry until the async login has bound its server (or fail after ~5s). */
    private fun httpGetOnceUp(port: Int, pathAndQuery: String): Pair<Int, String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            try {
                return httpGet("http://127.0.0.1:$port$pathAndQuery")
            } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("server never came up", e)
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun `server callback wins the race and exchanges the query code and state`() = runBlocking {
        val port = freePort()
        val (auth, http) = flow(port = port)
        val pair = pkce()
        val interaction = PendingInteraction()

        val loginJob = async { auth.login(interaction) }
        // Yield so the login coroutine binds its server before the callback is sent.
        delay(100)

        val (status, body) = httpGetOnceUp(port, "/callback?code=server-code&state=${pair.verifier}")
        assertEquals(200, status)
        assertTrue(body.contains("Anthropic authentication completed. You can close this window."), body)

        val credential = loginJob.await()
        assertEquals("access", credential.access)
        val sent = Json.parseToJsonElement(http.requests.single().body.decodeToString()).jsonObject
        assertEquals("server-code", sent["code"]!!.jsonPrimitive.content)
        assertEquals(pair.verifier, sent["state"]!!.jsonPrimitive.content)
        assertEquals(pair.verifier, sent["code_verifier"]!!.jsonPrimitive.content)
        // The suspended manual prompt was cancelled with the server's win.
        assertTrue(interaction.answer.isActive)
        assertEquals(
            "Exchanging authorization code for tokens...",
            interaction.events.filterIsInstance<AuthEvent.Progress>().single().message,
        )
    }

    @Test
    fun `manual answer wins the race when no callback arrives`() = runBlocking {
        val (auth, http) = flow()
        val pair = pkce()
        val interaction = PendingInteraction()

        val loginJob = launch { auth.login(interaction) }
        // Let the login reach the race before answering.
        delay(200)
        interaction.answer.complete("manual-code#${pair.verifier}")
        loginJob.join()

        val sent = Json.parseToJsonElement(http.requests.single().body.decodeToString()).jsonObject
        assertEquals("manual-code", sent["code"]!!.jsonPrimitive.content)
    }

    private suspend fun CoroutineScope.driveCallback(
        port: Int,
        pathAndQuery: String,
    ): Pair<Int, String> {
        val (auth, http) = flow(port = port)
        val interaction = PendingInteraction()
        val job = launch { auth.login(interaction) }
        delay(100)
        val response = httpGetOnceUp(port, pathAndQuery)
        job.cancel()
        job.join()
        assertTrue(http.requests.isEmpty(), "no exchange may happen for a rejected callback")
        return response
    }

    @Test
    fun `wrong callback path gets a 404 route-not-found page`() = runBlocking {
        val (status, body) = driveCallback(freePort(), "/other?code=server-code&state=x")
        assertEquals(404, status)
        assertTrue(body.contains("Callback route not found."), body)
    }

    @Test
    fun `error query param gets a 400 page echoing the error`() = runBlocking {
        val (status, body) = driveCallback(freePort(), "/callback?error=access_denied")
        assertEquals(400, status)
        assertTrue(body.contains("Anthropic authentication did not complete."), body)
        assertTrue(body.contains("Error: access_denied"), body)
    }

    @Test
    fun `missing code or state gets a 400 page`() = runBlocking {
        val pair = pkce()
        for (query in listOf("/callback?code=server-code", "/callback?state=${pair.verifier}")) {
            val (status, body) = driveCallback(freePort(), query)
            assertEquals(400, status, query)
            assertTrue(body.contains("Missing code or state parameter."), query)
        }
    }

    @Test
    fun `state mismatch gets a 400 page and no settle`() = runBlocking {
        val (status, body) = driveCallback(freePort(), "/callback?code=server-code&state=other")
        assertEquals(400, status)
        assertTrue(body.contains("State mismatch."), body)
    }

    @Test
    fun `bind failure fails the login like pi's rejecting error handler`() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { blocker ->
            val port = blocker.localPort
            val (auth, http) = flow(port = port)
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { auth.login(RecordingInteraction("the-code")) }
            }
            assertTrue(error.message!!.contains("callback server"), error.message)
            assertTrue(error.message!!.contains("127.0.0.1:$port"), error.message)
            assertTrue(http.requests.isEmpty())
        }
    }
}

private fun anthropicTokenBody(access: String, refresh: String, expiresInSeconds: Int = 3600): String =
    """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresInSeconds}"""
