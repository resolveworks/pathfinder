package works.resolve.pathfinder.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.OAuthCredential

/**
 * Tests for the OpenAI Codex (ChatGPT) OAuth flow, ported from pi
 * `packages/ai/test/openai-codex-oauth.test.ts` plus the browser-flow cases
 * pi covers with its loopback server (here: the documented manual-code
 * divergence).
 *
 * All HTTP goes through a fake [OAuthHttpClient] (no network); clock, state,
 * and PKCE seams are deterministic.
 */
class OpenAiCodexOAuthAuthTest {

    private class FakeHttpClient : OAuthHttpClient {
        val requests = mutableListOf<OAuthHttpRequest>()
        var respond: (suspend (OAuthHttpRequest) -> OAuthHttpResponse)? = null
        val defaultResponse = OAuthHttpResponse(200, emptyMap(), "{}".toByteArray())

        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            return respond?.invoke(request) ?: defaultResponse
        }
    }

    /** Answers each request in order; extra requests fail the test. */
    private fun FakeHttpClient.enqueue(vararg responses: OAuthHttpResponse) {
        val remaining = responses.toMutableList()
        respond = {
            val next = remaining.removeFirstOrNull()
                ?: error("Unexpected extra OAuth request: ${it.url}")
            next
        }
    }

    private fun json(status: Int, body: String): OAuthHttpResponse =
        OAuthHttpResponse(status, emptyMap(), body.toByteArray())

    private class RecordingInteraction(
        val answers: Map<String, suspend (AuthPrompt) -> String> = emptyMap(),
    ) : AuthInteraction {
        val events = mutableListOf<AuthEvent>()
        val prompts = mutableListOf<AuthPrompt>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            return answers[prompt::class.simpleName]?.invoke(prompt)
                ?: error("unexpected prompt: $prompt")
        }

        override suspend fun notify(event: AuthEvent) {
            events += event
        }
    }

    /** Raw entropy injected through the PKCE seam; the flow sees its base64url form. */
    private val rawVerifierBytes = "unit-test-verifier-unit-test-verifier".toByteArray(Charsets.US_ASCII)

    /** The verifier `PkceGenerator` derives from [rawVerifierBytes]. */
    private val fixedVerifier = works.resolve.pathfinder.ai.auth.PkceGenerator.base64url(rawVerifierBytes)

    private fun auth(
        http: OAuthHttpClient,
        nowMs: Long = 1_000_000L,
        state: String = "0123456789abcdef",
        callbackPort: Int = 0,
    ) = OpenAiCodexOAuthAuth(
        http = http,
        now = { nowMs },
        createState = { state },
        pkce = fixedPkce(),
        callbackPort = callbackPort,
    )

    private fun fixedPkce() = works.resolve.pathfinder.ai.auth.PkceGenerator { rawVerifierBytes }

    private fun accessToken(accountId: String, urlSafe: Boolean = false): String {
        val payload = """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
        val encoder =
            if (urlSafe) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val body = encoder.encodeToString(payload.toByteArray())
        return "$header.$body.signature"
    }

    private fun formFields(request: OAuthHttpRequest): Map<String, String> =
        request.body.toString(Charsets.UTF_8).split('&').associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun requestBody(request: OAuthHttpRequest): String = request.body.toString(Charsets.UTF_8)

    // ------------------------------------------------------------------
    // Login-method selection (pi's select prompt)
    // ------------------------------------------------------------------

    @Test
    fun `select prompt offers browser first then device code with pi labels`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(
                200,
                """{"device_auth_id":"device-auth-id","user_code":"ABCD-1234","interval":"5"}""",
            ),
            json(403, """{"error":{"code":"deviceauth_authorization_pending"}}"""),
            json(200, """{"authorization_code":"oauth-code","code_verifier":"device-code-verifier"}"""),
            json(
                200,
                """{"access_token":"${accessToken("account-456")}","refresh_token":"refresh-token","expires_in":3600}""",
            ),
        )
        val interaction = RecordingInteraction(
            mapOf("Select" to { "device_code" }),
        )

        val credential = auth(http).login(interaction)

        val select = assertIs<AuthPrompt.Select>(interaction.prompts.single())
        assertEquals("Select OpenAI Codex login method:", select.message)
        assertEquals(
            listOf(
                AuthPrompt.Select.Option("browser", "Browser login (default)"),
                AuthPrompt.Select.Option("device_code", "Device code login (headless)"),
            ),
            select.options,
        )
        // Device-code branch: no auth_url event, one device_code event.
        val deviceCode = assertIs<AuthEvent.DeviceCode>(interaction.events.single())
        assertEquals("ABCD-1234", deviceCode.userCode)
        assertEquals("https://auth.openai.com/codex/device", deviceCode.verificationUri)
        assertEquals(5, deviceCode.intervalSeconds)
        assertEquals(900, deviceCode.expiresInSeconds)

        // The credential carries the JWT account id and no-skew expiry.
        assertEquals(accessToken("account-456"), credential.access)
        assertEquals("refresh-token", credential.refresh)
        assertEquals(1_000_000L + 3600 * 1000, credential.expires)
        assertEquals(JsonPrimitive("account-456"), credential.extras["accountId"])
    }

    @Test
    fun `unknown login method fails with pi message`() = runTest {
        val interaction = RecordingInteraction(mapOf("Select" to { "carrier-pigeon" }))
        val error = assertFailsWith<IllegalStateException> {
            auth(FakeHttpClient()).login(interaction)
        }
        assertEquals("Unknown OpenAI Codex login method: carrier-pigeon", error.message)
    }

    // ------------------------------------------------------------------
    // Device-code flow
    // ------------------------------------------------------------------

    @Test
    fun `device code login exchanges authorization code with the device redirect uri`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"device_auth_id":"d1","user_code":"WXYZ-7890","interval":4}"""),
            json(200, """{"authorization_code":"oauth-code","code_verifier":"device-code-verifier"}"""),
            json(
                200,
                """{"access_token":"${accessToken("account-1")}","refresh_token":"refresh-token","expires_in":1800}""",
            ),
        )

        val credential = auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))

        assertEquals(1_000_000L + 1800 * 1000, credential.expires)

        val userCodeRequest = http.requests[0]
        assertEquals("https://auth.openai.com/api/accounts/deviceauth/usercode", userCodeRequest.url)
        assertEquals("POST", userCodeRequest.method)
        assertEquals("application/json", userCodeRequest.headers["content-type"])
        assertEquals("""{"client_id":"app_EMoamEEZ73f0CkXaXp7hrann"}""", requestBody(userCodeRequest))

        val pollRequest = http.requests[1]
        assertEquals("https://auth.openai.com/api/accounts/deviceauth/token", pollRequest.url)
        assertEquals("""{"device_auth_id":"d1","user_code":"WXYZ-7890"}""", requestBody(pollRequest))

        val tokenRequest = http.requests[2]
        assertEquals("https://auth.openai.com/oauth/token", tokenRequest.url)
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "app_EMoamEEZ73f0CkXaXp7hrann",
                "code" to "oauth-code",
                "code_verifier" to "device-code-verifier",
                "redirect_uri" to "https://auth.openai.com/deviceauth/callback",
            ),
            formFields(tokenRequest),
        )
    }

    @Test
    fun `device code 404 reports the not-enabled message`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(json(404, ""))
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        assertEquals(
            "OpenAI Codex device code login is not enabled for this server. " +
                "Use browser login or verify the server URL.",
            error.message,
        )
    }

    @Test
    fun `device code request failure includes status and sanitized error detail`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(json(500, """{"error":"nope"}"""))
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        assertEquals(
            "OpenAI Codex device code request failed with status 500: error=nope",
            error.message,
        )

        // Unparseable bodies are never interpolated.
        val raw = FakeHttpClient()
        raw.enqueue(json(502, "gateway melted"))
        val rawError = assertFailsWith<IllegalStateException> {
            auth(raw).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        assertEquals(
            "OpenAI Codex device code request failed with status 502: <redacted>",
            rawError.message,
        )
    }

    @Test
    fun `invalid device code responses report missing field names only`() = runTest {
        for ((body, missing) in listOf(
            """{"device_auth_id":"","user_code":"ABCD","interval":5}""" to "device_auth_id",
            """{"device_auth_id":"d","user_code":"","interval":5}""" to "user_code",
            """{"device_auth_id":"d","user_code":"ABCD","interval":-1}""" to "interval",
            """{"device_auth_id":"d","user_code":"ABCD","interval":"soon"}""" to "interval",
            """{"device_auth_id":"d","user_code":"ABCD"}""" to "interval",
            "not json" to "device_auth_id, user_code, interval",
        )) {
            val http = FakeHttpClient()
            http.enqueue(json(200, body))
            val error = assertFailsWith<IllegalStateException> {
                auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
            }
            assertEquals(
                "Invalid OpenAI Codex device code response: missing fields: $missing",
                error.message,
            )
            // pi echoes the raw body here; the port never does.
            assertTrue(body !in error.message!!, error.message)
        }
    }

    @Test
    fun `device poll treats 403 and 404 as pending`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"device_auth_id":"d","user_code":"ABCD","interval":1}"""),
            json(403, """{"error":"access_denied","error_description":"denied"}"""),
            json(404, "not ready"),
            json(200, """{"authorization_code":"c","code_verifier":"v"}"""),
            json(200, """{"access_token":"${accessToken("account-403-404")}","refresh_token":"r","expires_in":60}"""),
        )
        val credential = auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        assertEquals("account-403-404", credential.extras["accountId"]!!.jsonPrimitive.content)
        assertEquals(5, http.requests.size)
    }

    @Test
    fun `device poll failure includes status and sanitized error detail`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"device_auth_id":"d","user_code":"ABCD","interval":5}"""),
            json(500, """{"error":"server_error","error_description":"try again later"}"""),
        )
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        assertEquals(
            "OpenAI Codex device auth failed with status 500: " +
                "error=server_error: try again later",
            error.message,
        )
    }

    @Test
    fun `device poll failure scrubs secrets echoed by the server`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"device_auth_id":"device-secret-123","user_code":"ABCD","interval":5}"""),
            json(
                500,
                """{"error":"failed for device-secret-123","error_description":"user ABCD retry"}""",
            ),
        )
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        assertEquals(
            "OpenAI Codex device auth failed with status 500: " +
                "error=failed for <redacted>: user <redacted> retry",
            error.message,
        )
    }

    @Test
    fun `invalid device token response reports missing field names only`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"device_auth_id":"d","user_code":"ABCD","interval":5}"""),
            json(200, """{"authorization_code":"auth-code-secret","code_challenge":"x"}"""),
        )
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        assertEquals(
            "Invalid OpenAI Codex device auth token response: missing fields: code_verifier",
            error.message,
        )
        // pi echoes the raw body, which here carries the authorization code.
        assertTrue("auth-code-secret" !in error.message!!, error.message)
    }

    @Test
    fun `device code login is cancellable while polling`() = runTest {
        val http = FakeHttpClient()
        val polled = CompletableDeferred<Unit>()
        http.respond = {
            if (http.requests.size == 1) {
                json(200, """{"device_auth_id":"d","user_code":"ABCD","interval":5}""")
            } else {
                polled.complete(Unit)
                json(403, """{"error":{"code":"deviceauth_authorization_pending"}}""")
            }
        }

        val deferred = async {
            auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        polled.await()
        deferred.cancel()
        assertFailsWith<CancellationException> { deferred.await() }
    }

    // ------------------------------------------------------------------
    // Browser login (manual-code divergence)
    // ------------------------------------------------------------------

    @Test
    fun `browser login notifies the auth url and exchanges a pasted code`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(
                200,
                """{"access_token":"${accessToken("account-9", urlSafe = true)}","refresh_token":"r","expires_in":100}""",
            ),
        )
        val interaction = RecordingInteraction(
            mapOf(
                "Select" to { "browser" },
                "ManualCode" to { "pasted-code" },
            ),
        )

        val credential = auth(http).login(interaction)

        val url = assertIs<AuthEvent.AuthUrl>(interaction.events.single())
        assertEquals("A browser window should open. Complete login to finish.", url.instructions)
        assertEquals(
            "https://auth.openai.com/oauth/authorize" +
                "?response_type=code" +
                "&client_id=app_EMoamEEZ73f0CkXaXp7hrann" +
                "&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback" +
                "&scope=openid+profile+email+offline_access" +
                "&code_challenge=${works.resolve.pathfinder.ai.auth.PkceGenerator.challengeFor(fixedVerifier)}" +
                "&code_challenge_method=S256" +
                "&state=0123456789abcdef" +
                "&id_token_add_organizations=true" +
                "&codex_cli_simplified_flow=true" +
                "&originator=pathfinder",
            url.url,
        )

        val manual = assertIs<AuthPrompt.ManualCode>(interaction.prompts[1])
        assertEquals(
            "Complete login in your browser, or paste the authorization code / redirect URL here:",
            manual.message,
        )
        assertEquals(OpenAiCodexOAuthAuth.REDIRECT_URI, manual.placeholder)

        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "app_EMoamEEZ73f0CkXaXp7hrann",
                "code" to "pasted-code",
                "code_verifier" to fixedVerifier,
                "redirect_uri" to "http://localhost:1455/auth/callback",
            ),
            formFields(http.requests.single()),
        )
        assertEquals("account-9", credential.extras["accountId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `browser login accepts a redirect url with matching state`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"access_token":"${accessToken("account-10")}","refresh_token":"r","expires_in":10}"""),
        )
        auth(http).login(
            RecordingInteraction(
                mapOf(
                    "Select" to { "browser" },
                    "ManualCode" to {
                        "http://localhost:1455/auth/callback?code=redir-code&state=0123456789abcdef"
                    },
                ),
            ),
        )
        assertEquals("redir-code", formFields(http.requests.single())["code"])
    }

    @Test
    fun `browser login treats an empty state as absent like pi`() = runTest {
        for (answer in listOf(
            "code-from-fragment#",
            "http://localhost:1455/auth/callback?code=code-from-url&state=",
        )) {
            val http = FakeHttpClient()
            http.enqueue(
                json(
                    200,
                    """{"access_token":"${accessToken("empty-state-account")}","refresh_token":"r","expires_in":10}""",
                ),
            )
            auth(http).login(
                RecordingInteraction(
                    mapOf(
                        "Select" to { "browser" },
                        "ManualCode" to { answer },
                    ),
                ),
            )
            assertEquals(1, http.requests.size)
        }
    }

    @Test
    fun `browser login rejects mismatched state from a redirect url`() = runTest {
        val http = FakeHttpClient()
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(
                RecordingInteraction(
                    mapOf(
                        "Select" to { "browser" },
                        "ManualCode" to { "http://localhost:1455/auth/callback?code=c&state=evil" },
                    ),
                ),
            )
        }
        assertEquals("State mismatch", error.message)
        assertTrue(http.requests.isEmpty(), "no exchange after a state mismatch")
    }

    @Test
    fun `browser login without a code fails with pi message`() = runTest {
        val http = FakeHttpClient()
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(
                RecordingInteraction(
                    mapOf(
                        "Select" to { "browser" },
                        "ManualCode" to {
                            "http://localhost:1455/auth/callback?error=access_denied&state=0123456789abcdef"
                        },
                    ),
                ),
            )
        }
        assertEquals("Missing authorization code", error.message)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `browser login rejects an empty code like pi if (!code)`() = runTest {
        for (answer in listOf(
            // URL with an explicitly empty code.
            "http://localhost:1455/auth/callback?code=&state=0123456789abcdef",
            // Bare query form with an empty code value.
            "code=",
            // Whitespace-only input trims to an empty parse result.
            "   ",
        )) {
            val http = FakeHttpClient()
            val error = assertFailsWith<IllegalStateException> {
                auth(http).login(
                    RecordingInteraction(
                        mapOf(
                            "Select" to { "browser" },
                            "ManualCode" to { answer },
                        ),
                    ),
                )
            }
            assertEquals("Missing authorization code", error.message)
            assertTrue(http.requests.isEmpty())
        }
    }

    /** An interaction that answers the select with `browser` and parks the manual-code prompt until the test completes it. */
    private class GatedManualInteraction : AuthInteraction {
        val events = mutableListOf<AuthEvent>()
        val prompts = mutableListOf<AuthPrompt>()
        val manualAnswer = CompletableDeferred<String>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            return when (prompt) {
                is AuthPrompt.Select -> "browser"
                is AuthPrompt.ManualCode -> manualAnswer.await()
                else -> error("unexpected prompt: $prompt")
            }
        }

        override suspend fun notify(event: AuthEvent) {
            events += event
        }
    }

    /** Drives the loopback callback server with a real HTTP GET (status, body). */
    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        val connection = java.net.URL("http://127.0.0.1:$port$path").openConnection()
            as java.net.HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            return status to stream.readBytes().decodeToString()
        } finally {
            connection.disconnect()
        }
    }

    /** Waits until the browser flow has bound its loopback server and parked the manual prompt. */
    private suspend fun awaitBrowserPrompt(interaction: GatedManualInteraction, flow: OpenAiCodexOAuthAuth): Int {
        while (interaction.prompts.size < 2 || flow.lastCallbackPort == null) {
            kotlinx.coroutines.yield()
        }
        return flow.lastCallbackPort!!
    }

    @Test
    fun `browser login completes from the loopback callback server`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"access_token":"${accessToken("account-server")}","refresh_token":"r","expires_in":10}"""),
        )
        val interaction = GatedManualInteraction()
        val flow = auth(http)
        val login = async { flow.login(interaction) }

        val port = awaitBrowserPrompt(interaction, flow)
        val (status, body) = withContext(Dispatchers.IO) {
            httpGet(port, "/auth/callback?code=server-code&state=0123456789abcdef")
        }

        assertEquals(200, status)
        assertTrue("OpenAI authentication completed. You can close this window." in body, body)
        assertEquals("server-code", formFields(http.requests.single())["code"])
        assertEquals("account-server", login.await().extras["accountId"]!!.jsonPrimitive.content)
        // The manual prompt was never answered; the server result won outright.
        assertTrue(!interaction.manualAnswer.isCompleted)
    }

    @Test
    fun `callback state mismatch yields 400 and falls back to the manual prompt`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"access_token":"${accessToken("account-mismatch")}","refresh_token":"r","expires_in":10}"""),
        )
        val interaction = GatedManualInteraction()
        val flow = auth(http)
        val login = async { flow.login(interaction) }

        val port = awaitBrowserPrompt(interaction, flow)
        val (status, body) = withContext(Dispatchers.IO) {
            httpGet(port, "/auth/callback?code=evil-code&state=evil")
        }

        assertEquals(400, status)
        assertTrue("State mismatch." in body, body)
        assertTrue(http.requests.isEmpty(), "no exchange after a state mismatch")

        // The login is still parked on the manual prompt; a pasted code finishes it.
        interaction.manualAnswer.complete("manual-after-mismatch")
        login.await()
        assertEquals("manual-after-mismatch", formFields(http.requests.single())["code"])
    }

    @Test
    fun `callback without a code yields 400`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"access_token":"${accessToken("account-nocode")}","refresh_token":"r","expires_in":10}"""),
        )
        val interaction = GatedManualInteraction()
        val flow = auth(http)
        val login = async { flow.login(interaction) }

        val port = awaitBrowserPrompt(interaction, flow)
        val (status, body) = withContext(Dispatchers.IO) {
            httpGet(port, "/auth/callback?state=0123456789abcdef")
        }

        assertEquals(400, status)
        assertTrue("Missing authorization code." in body, body)
        assertTrue(http.requests.isEmpty())

        interaction.manualAnswer.complete("manual-after-nocode")
        login.await()
        assertEquals("manual-after-nocode", formFields(http.requests.single())["code"])
    }

    @Test
    fun `callback on the wrong path yields 404`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"access_token":"${accessToken("account-404")}","refresh_token":"r","expires_in":10}"""),
        )
        val interaction = GatedManualInteraction()
        val flow = auth(http)
        val login = async { flow.login(interaction) }

        val port = awaitBrowserPrompt(interaction, flow)
        val (status, body) = withContext(Dispatchers.IO) {
            httpGet(port, "/wrong?code=c&state=0123456789abcdef")
        }

        assertEquals(404, status)
        assertTrue("Callback route not found." in body, body)
        assertTrue(http.requests.isEmpty())

        interaction.manualAnswer.complete("manual-after-404")
        login.await()
        assertEquals("manual-after-404", formFields(http.requests.single())["code"])
    }

    @Test
    fun `manual prompt answer wins when no callback arrives`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"access_token":"${accessToken("account-manual")}","refresh_token":"r","expires_in":10}"""),
        )
        val interaction = GatedManualInteraction()
        val flow = auth(http)
        val login = async { flow.login(interaction) }

        awaitBrowserPrompt(interaction, flow)
        // No callback request at all: cancelWait unblocks the race.
        interaction.manualAnswer.complete("pasted-without-callback")

        val credential = login.await()
        assertEquals("pasted-without-callback", formFields(http.requests.single())["code"])
        assertEquals("account-manual", credential.extras["accountId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bind conflict on the callback port degrades to the manual-only flow`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"access_token":"${accessToken("account-bindfail")}","refresh_token":"r","expires_in":10}"""),
        )
        val blocker = java.net.ServerSocket(0)
        try {
            val flow = auth(http, callbackPort = blocker.localPort)
            val credential = flow.login(
                RecordingInteraction(
                    mapOf(
                        "Select" to { "browser" },
                        "ManualCode" to { "pasted-when-port-taken" },
                    ),
                ),
            )
            assertNull(flow.lastCallbackPort, "bind failure must not record a port")
            assertEquals("pasted-when-port-taken", formFields(http.requests.single())["code"])
            assertEquals("account-bindfail", credential.extras["accountId"]!!.jsonPrimitive.content)
        } finally {
            blocker.close()
        }
    }

    @Test
    fun `manual prompt failure surfaces as the login error`() = runTest {
        val http = FakeHttpClient()
        val interaction = object : AuthInteraction {
            override suspend fun prompt(prompt: AuthPrompt): String =
                if (prompt is AuthPrompt.Select) "browser" else throw IllegalStateException("prompt dismissed")

            override suspend fun notify(event: AuthEvent) {}
        }
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(interaction)
        }
        assertEquals("prompt dismissed", error.message)
        assertTrue(http.requests.isEmpty())
    }

    // ------------------------------------------------------------------
    // Interval coercion (JS Number(trimmed)) and shared helpers
    // ------------------------------------------------------------------

    @Test
    fun `jsNumber pins JS Number coercion for string intervals`() {
        val flow = OpenAiCodexOAuthAuth(FakeHttpClient())
        assertEquals(5.0, flow.jsNumber("5"))
        assertEquals(5.0, flow.jsNumber(" 5 \t"))
        // JS: Number("") and Number(" ") are both 0 (finite and non-negative,
        // so a whitespace-only interval is *valid* with a zero interval).
        assertEquals(0.0, flow.jsNumber(""))
        assertEquals(0.0, flow.jsNumber("   "))
        assertEquals(0.0, flow.jsNumber("\u00A0\uFEFF"))
        assertEquals(16.0, flow.jsNumber("0x10"))
        assertEquals(-16.0, flow.jsNumber("-0x10"))
        assertEquals(5.0, flow.jsNumber("0b101"))
        assertEquals(15.0, flow.jsNumber("0o17"))
        assertEquals(100.0, flow.jsNumber("1e2"))
        assertEquals(0.5, flow.jsNumber(".5"))
        assertTrue(flow.jsNumber("Infinity").isInfinite())
        assertTrue(flow.jsNumber("-Infinity").isInfinite())
        assertTrue(flow.jsNumber("NaN").isNaN())
        assertTrue(flow.jsNumber("12px").isNaN())
        // Java-only Double.parseDouble suffix forms are not JS numbers.
        assertTrue(flow.jsNumber("5f").isNaN())
        assertTrue(flow.jsNumber("5d").isNaN())
    }

    @Test
    fun `string intervals coerce through jsNumber in the device auth response`() = runTest {
        for ((interval, expected) in listOf(" 5 " to 5.0, "0x10" to 16.0, " " to 0.0)) {
            val http = FakeHttpClient()
            http.enqueue(
                json(200, """{"device_auth_id":"d","user_code":"ABCD","interval":"$interval"}"""),
            )
            val device = auth(http).startOpenAICodexDeviceAuth()
            assertEquals(expected, device.intervalSeconds)
        }
    }

    @Test
    fun `form encoding matches URLSearchParams byte for byte`() {
        // WHATWG application/x-www-form-urlencoded: `~` percent-encodes, `*`
        // stays literal, a space becomes `+` — exactly java.net.URLEncoder.
        assertEquals(
            "k=%7E+*",
            XaiOAuthAuth.formUrlEncode(mapOf("k" to "~ *")).toString(Charsets.UTF_8),
        )
        assertEquals(
            "a=1&b=two+words",
            XaiOAuthAuth.formUrlEncode(linkedMapOf("a" to "1", "b" to "two words"))
                .toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `every oauth request carries the bounded timeout`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"device_auth_id":"d","user_code":"ABCD","interval":4}"""),
            json(200, """{"authorization_code":"c","code_verifier":"v"}"""),
            json(200, """{"access_token":"${accessToken("acc")}","refresh_token":"r","expires_in":60}"""),
        )
        auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        assertTrue(http.requests.isNotEmpty())
        assertTrue(
            http.requests.all { it.timeoutMs == OpenAiCodexOAuthAuth.REQUEST_TIMEOUT_MS },
            http.requests.map { it.timeoutMs }.toString(),
        )
    }

    @Test
    fun `internal result shapes redact secrets in toString`() {
        assertTrue(
            "verifier-value" !in OpenAiCodexOAuthAuth.AuthorizationFlow("verifier-value", "state", "https://example.invalid")
                .toString(),
        )
        assertTrue("<redacted>" in OpenAiCodexOAuthAuth.AuthorizationFlow("v", "s", "u").toString())
        val token = OpenAiCodexOAuthAuth.OAuthToken("access-secret", "refresh-secret", 1).toString()
        assertTrue("access-secret" !in token && "refresh-secret" !in token && "<redacted>" in token)
        val success = OpenAiCodexOAuthAuth.DeviceTokenSuccess("auth-code-secret", "verifier-secret").toString()
        assertTrue("auth-code-secret" !in success && "verifier-secret" !in success)
        val device = OpenAiCodexOAuthAuth.DeviceAuthInfo("device-secret", "ABCD-1234", 5.0).toString()
        assertTrue("device-secret" !in device && "ABCD-1234" in device)
    }

    // ------------------------------------------------------------------
    // parseAuthorizationInput
    // ------------------------------------------------------------------

    @Test
    fun `parseAuthorizationInput handles url fragment query and bare forms`() {
        val flow = OpenAiCodexOAuthAuth(FakeHttpClient())
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("c1", "s1"),
            flow.parseAuthorizationInput("http://localhost:1455/auth/callback?code=c1&state=s1"),
        )
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("c2", "s2"),
            flow.parseAuthorizationInput("c2#s2"),
        )
        // JS value.split("#", 2): the third segment is discarded, not glued
        // onto the state like Kotlin's split(limit = 2) remainder.
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("c2", "s2"),
            flow.parseAuthorizationInput("c2#s2#ignored"),
        )
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("c3", "s3"),
            flow.parseAuthorizationInput("code=c3&state=s3"),
        )
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("c4", null),
            flow.parseAuthorizationInput("  c4  "),
        )
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput(null, null),
            flow.parseAuthorizationInput("   "),
        )
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("with space", null),
            flow.parseAuthorizationInput("code=with+space"),
        )
        // A URL without a code param yields a null code, not an empty one.
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput(null, "only"),
            flow.parseAuthorizationInput("http://localhost:1455/auth/callback?state=only"),
        )
    }

    @Test
    fun `parseAuthorizationInput matches URLSearchParams name decoding and first-match semantics`() {
        val flow = OpenAiCodexOAuthAuth(FakeHttpClient())
        // URLSearchParams decodes parameter names: %63ode is `code`. (The
        // URL branch is the only path that decodes names: the bare-input
        // branches mirror pi's literal `includes("code=")` check, which a
        // percent-encoded name never satisfies.)
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("decoded-name", null),
            flow.parseAuthorizationInput("http://localhost:1455/auth/callback?%63ode=decoded-name"),
        )
        // First occurrence wins.
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("first", null),
            flow.parseAuthorizationInput("code=first&code=second"),
        )
        // `+` decodes as a space in names and values.
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("a b", null),
            flow.parseAuthorizationInput("http://localhost:1455/auth/callback?c%6Fde=a+b"),
        )
        // A bare percent-encoded name never matches pi's literal code= check;
        // the whole input is the code, exactly like upstream.
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("%63ode=decoded-name", null),
            flow.parseAuthorizationInput("%63ode=decoded-name"),
        )
        // A bare key yields the empty string (pi: get() ?? undefined → "").
        assertEquals(
            OpenAiCodexOAuthAuth.AuthorizationInput("", null),
            flow.parseAuthorizationInput("http://localhost:1455/auth/callback?code"),
        )
    }

    // ------------------------------------------------------------------
    // Token response validation, refresh, JWT account metadata
    // ------------------------------------------------------------------

    @Test
    fun `token failures carry status and sanitized detail without a status line fallback`() {
        val flow = OpenAiCodexOAuthAuth(FakeHttpClient())
        val error = assertFailsWith<IllegalStateException> {
            flow.readTokenResponse(
                json(401, """{"error":{"message":"Could not validate your token. Please try signing in again."}}"""),
                OpenAiCodexOAuthAuth.TokenOperation.REFRESH,
            )
        }
        assertEquals(
            "OpenAI Codex token refresh failed (401): " +
                "error=Could not validate your token. Please try signing in again.",
            error.message,
        )

        val empty = assertFailsWith<IllegalStateException> {
            flow.readTokenResponse(json(503, ""), OpenAiCodexOAuthAuth.TokenOperation.EXCHANGE)
        }
        assertEquals("OpenAI Codex token exchange failed (503)", empty.message)
    }

    @Test
    fun `token failures scrub in-flight secrets echoed by the server`() {
        val flow = OpenAiCodexOAuthAuth(FakeHttpClient())
        val error = assertFailsWith<IllegalStateException> {
            flow.readTokenResponse(
                json(400, """{"error":"bad code verifier: leaked-verifier-secret"}"""),
                OpenAiCodexOAuthAuth.TokenOperation.EXCHANGE,
                secrets = listOf("leaked-verifier-secret"),
            )
        }
        assertEquals(
            "OpenAI Codex token exchange failed (400): error=bad code verifier: <redacted>",
            error.message,
        )
    }

    @Test
    fun `missing token fields are reported by name without echoing token material`() {
        val flow = OpenAiCodexOAuthAuth(FakeHttpClient())
        val error = assertFailsWith<IllegalStateException> {
            flow.readTokenResponse(
                json(200, """{"access_token":"secret-access","expires_in":3600}"""),
                OpenAiCodexOAuthAuth.TokenOperation.EXCHANGE,
            )
        }
        assertEquals(
            "OpenAI Codex token exchange response missing fields: refresh_token",
            error.message,
        )
        assertTrue("secret-access" !in error.message!!)

        val both = assertFailsWith<IllegalStateException> {
            flow.readTokenResponse(json(200, "{}"), OpenAiCodexOAuthAuth.TokenOperation.REFRESH)
        }
        assertEquals(
            "OpenAI Codex token refresh response missing fields: access_token, refresh_token, expires_in",
            both.message,
        )
    }

    @Test
    fun `string or non-finite expires_in is rejected`() {
        val flow = OpenAiCodexOAuthAuth(FakeHttpClient())
        for (body in listOf(
            """{"access_token":"a","refresh_token":"r","expires_in":"3600"}""",
            """{"access_token":"a","refresh_token":"r"}""",
        )) {
            val error = assertFailsWith<IllegalStateException> {
                flow.readTokenResponse(json(200, body), OpenAiCodexOAuthAuth.TokenOperation.EXCHANGE)
            }
            assertTrue(error.message!!.endsWith("expires_in"), error.message)
        }
    }

    @Test
    fun `refresh rotates the credential and keeps the account id`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(
                200,
                """{"access_token":"${accessToken("account-refreshed")}","refresh_token":"rotated","expires_in":7200}""",
            ),
        )
        val flow = auth(http, nowMs = 5_000_000L)
        val credential = flow.refresh(
            OAuthCredential(access = "old", refresh = "old-refresh", expires = 0),
        )
        assertEquals("rotated", credential.refresh)
        assertEquals(5_000_000L + 7200 * 1000, credential.expires)
        assertEquals("account-refreshed", credential.extras["accountId"]!!.jsonPrimitive.content)
        assertEquals(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to "old-refresh",
                "client_id" to "app_EMoamEEZ73f0CkXaXp7hrann",
            ),
            formFields(http.requests.single()),
        )
    }

    @Test
    fun `network failures during refresh wrap in pi message`() = runTest {
        val http = object : OAuthHttpClient {
            override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse =
                throw java.io.IOException("connection reset")
        }
        val error = assertFailsWith<IllegalStateException> {
            OpenAiCodexOAuthAuth(http).refresh(
                OAuthCredential(access = "a", refresh = "r", expires = 0),
            )
        }
        assertEquals("OpenAI Codex token refresh error: connection reset", error.message)
    }

    @Test
    fun `jwt account metadata decoding`() {
        val flow = OpenAiCodexOAuthAuth(FakeHttpClient())
        assertEquals("acc-1", flow.getAccountId(accessToken("acc-1")))
        assertEquals("acc-2", flow.getAccountId(accessToken("acc-2", urlSafe = true)))
        assertNull(flow.getAccountId("not-a-jwt"))
        assertNull(flow.getAccountId("a.b.c"))
        assertNull(flow.getAccountId(accessToken("")))
        assertNull(flow.getAccountId("""{"alg":"none"}.notbase64.sig"""))
    }

    @Test
    fun `tokens without account metadata fail with pi message`() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"device_auth_id":"d","user_code":"ABCD","interval":5}"""),
            json(200, """{"authorization_code":"c","code_verifier":"v"}"""),
            json(200, """{"access_token":"opaque-not-a-jwt","refresh_token":"r","expires_in":60}"""),
        )
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        assertEquals("Failed to extract accountId from token", error.message)
    }

    // ------------------------------------------------------------------
    // Secret safety
    // ------------------------------------------------------------------

    @Test
    fun `no secret material appears in any error message`() = runTest {
        val secrets = listOf(
            fixedVerifier,
            "device-code-verifier",
            "pasted-code",
            "secret-access",
        )
        val http = FakeHttpClient()
        http.enqueue(
            json(200, """{"device_auth_id":"d","user_code":"ABCD","interval":5}"""),
            json(200, """{"authorization_code":"pasted-code","code_verifier":"device-code-verifier"}"""),
            json(400, """{"error":"invalid_grant"}"""),
        )
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction(mapOf("Select" to { "device_code" })))
        }
        for (secret in secrets) {
            assertTrue(secret !in error.message!!, "leaked secret: $secret")
        }
    }

    @Test
    fun `requests never log bodies or query secrets`() {
        val request = OAuthHttpRequest(
            method = "POST",
            url = "https://auth.openai.com/oauth/token?code=secret",
            body = "code=secret&code_verifier=secret".toByteArray(),
            timeoutMs = 1000,
        )
        assertTrue("secret" !in request.toString())
    }
}
