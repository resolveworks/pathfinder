package works.resolve.pathfinder.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.testing.FakeClock

class XaiOAuthAuthTest {

    private class RecordingInteraction : AuthInteraction {
        val events = mutableListOf<AuthEvent>()
        val prompts = mutableListOf<AuthPrompt>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            error("no prompts expected")
        }

        override suspend fun notify(event: AuthEvent) {
            events += event
        }
    }

    private class FakeHttpClient : OAuthHttpClient {
        val requests = mutableListOf<OAuthHttpRequest>()
        var respond: (suspend (OAuthHttpRequest) -> OAuthHttpResponse)? = null
        val defaultResponse = OAuthHttpResponse(200, emptyMap(), "{}".toByteArray())

        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            return respond?.invoke(request) ?: defaultResponse
        }
    }

    private fun json(status: Int, body: String): OAuthHttpResponse =
        OAuthHttpResponse(status, emptyMap(), body.toByteArray())

    private fun deviceCodeBody(): String =
        """
        {
          "device_code": "dev-123",
          "user_code": "ABCD-EFGH",
          "verification_uri": "https://auth.x.ai/activate",
          "verification_uri_complete": "https://auth.x.ai/activate?user_code=ABCD-EFGH",
          "interval": 4,
          "expires_in": 900
        }
        """.trimIndent()

    private fun tokenBody(): String =
        """
        {
          "access_token": "acc-1",
          "refresh_token": "ref-1",
          "expires_in": 7200
        }
        """.trimIndent()

    private fun requestBody(request: OAuthHttpRequest): String =
        request.body.toString(Charsets.UTF_8)

    private fun lastRequest(http: FakeHttpClient): OAuthHttpRequest = http.requests.last()

    @Test
    fun `metadata mirrors pi xaiOAuth`() {
        val auth = XaiOAuthAuth(FakeHttpClient())
        assertEquals("xAI (Grok/X subscription)", auth.name)
        assertEquals("Sign in with SuperGrok or X Premium", auth.loginLabel)
        assertTrue(auth.isSubscription)
    }

    @Test
    fun `device code request is form-encoded with pi fields`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http)
        http.respond = { json(200, deviceCodeBody()) }

        auth.requestDeviceCodeForTest()

        val request = http.requests.single()
        assertEquals(XaiOAuthAuth.DEVICE_CODE_URL, request.url)
        assertEquals("POST", request.method)
        assertEquals("application/json", request.headers["accept"])
        assertEquals("application/x-www-form-urlencoded", request.headers["content-type"])
        assertEquals(
            "client_id=b1a00492-073a-47ea-816f-4c329264a828" +
                "&scope=openid+profile+email+offline_access+grok-cli%3Aaccess+api%3Aaccess" +
                "&referrer=pathfinder",
            requestBody(request)
        )
        assertTrue(request.timeoutMs > 0)
    }

    @Test
    fun `device code failure carries status and server error detail`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http)
        http.respond = {
            json(400, """{"error":"invalid_request","error_description":"bad scope"}""")
        }
        val error = assertFailsWith<IllegalStateException> { auth.requestDeviceCodeForTest() }
        assertEquals(
            "xAI OAuth device authorization failed (HTTP 400): invalid_request: bad scope",
            error.message
        )
    }

    @Test
    fun `device_code event fields mirror the response`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(1_000L))
        val interaction = RecordingInteraction()
        http.respond = { json(200, deviceCodeBody()) }
        http.respond = { request ->
            if (request.url == XaiOAuthAuth.DEVICE_CODE_URL) {
                json(200, deviceCodeBody())
            } else {
                json(200, tokenBody())
            }
        }

        auth.login(interaction)

        val event = interaction.events.filterIsInstance<AuthEvent.DeviceCode>().single()
        assertEquals("ABCD-EFGH", event.userCode)
        assertEquals("https://auth.x.ai/activate?user_code=ABCD-EFGH", event.verificationUri)
        assertEquals(4, event.intervalSeconds)
        assertEquals(900, event.expiresInSeconds)
    }

    @Test
    fun `non-https verification URI is rejected`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http)
        val error = assertFailsWith<IllegalStateException> {
            auth.parseDeviceCodeForTest(
                """{"device_code":"d","user_code":"u","verification_uri":"http://auth.x.ai/activate","expires_in":900}"""
            )
        }
        assertEquals("Untrusted verification URI in xAI OAuth response", error.message)
    }

    @Test
    fun `unparseable verification URI is rejected`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http)
        val error = assertFailsWith<IllegalStateException> {
            auth.parseDeviceCodeForTest(
                """{"device_code":"d","user_code":"u","verification_uri":"not a url","expires_in":900}"""
            )
        }
        assertEquals("Untrusted verification URI in xAI OAuth response", error.message)
    }

    @Test
    fun `missing or non-string fields throw invalid-field errors`() = runTest {
        val auth = XaiOAuthAuth(FakeHttpClient())
        for (body in listOf(
            """{"user_code":"u","verification_uri":"https://x.ai","expires_in":900}""",
            """{"device_code":"","user_code":"u","verification_uri":"https://x.ai","expires_in":900}""",
            """{"device_code":1,"user_code":"u","verification_uri":"https://x.ai","expires_in":900}"""
        )) {
            val error = assertFailsWith<IllegalStateException> { auth.parseDeviceCodeForTest(body) }
            assertEquals("Invalid xAI OAuth response field: device_code", error.message)
        }
    }

    @Test
    fun `non-positive expires_in is rejected and interval falls back to default`() = runTest {
        val auth = XaiOAuthAuth(FakeHttpClient())
        val error = assertFailsWith<IllegalStateException> {
            auth.parseDeviceCodeForTest(
                """{"device_code":"d","user_code":"u","verification_uri":"https://x.ai","expires_in":0}"""
            )
        }
        assertEquals("Invalid xAI OAuth response field: expires_in", error.message)

        val device = auth.parseDeviceCodeForTest(
            """{"device_code":"d","user_code":"u","verification_uri":"https://x.ai","interval":0,"expires_in":900}"""
        )
        assertNull(device.intervalSeconds) // RFC 8628 interval 0 → poller default
    }

    @Test
    fun `expiry uses lifetime minus 5-minute skew`() {
        val auth = XaiOAuthAuth(FakeHttpClient(), clock = FakeClock(100_000L))
        val credential = auth.credentialsFromTokenResponse(
            Json.parseToJsonElement(tokenBody()) as JsonObject
        )
        assertEquals("acc-1", credential.access)
        assertEquals("ref-1", credential.refresh)
        assertEquals(100_000 + 7200 * 1000 - XaiOAuthAuth.REFRESH_SKEW_MS, credential.expires)
    }

    @Test
    fun `missing expires_in defaults to one hour`() {
        val auth = XaiOAuthAuth(FakeHttpClient(), clock = FakeClock(0L))
        val credential = auth.credentialsFromTokenResponse(
            Json.parseToJsonElement("""{"access_token":"a","refresh_token":"r"}""") as JsonObject
        )
        assertEquals(3600 * 1000 - XaiOAuthAuth.REFRESH_SKEW_MS, credential.expires)
    }

    @Test
    fun `omitted refresh_token on refresh retains the previous one`() {
        val auth = XaiOAuthAuth(FakeHttpClient(), clock = FakeClock(0L))
        val credential = auth.credentialsFromTokenResponse(
            Json.parseToJsonElement("""{"access_token":"a2"}""") as JsonObject,
            previousRefreshToken = "kept-refresh"
        )
        assertEquals("kept-refresh", credential.refresh)
    }

    @Test
    fun `omitted refresh_token with empty previous refresh fails validation (pi truthiness)`() {
        val auth = XaiOAuthAuth(FakeHttpClient(), clock = FakeClock(0L))
        // pi retains previousRefreshToken only when truthy — an empty string is
        // falsy, so the omitted field falls through to requiredString.
        val error = assertFailsWith<IllegalStateException> {
            auth.credentialsFromTokenResponse(
                Json.parseToJsonElement("""{"access_token":"a2"}""") as JsonObject,
                previousRefreshToken = ""
            )
        }
        assertEquals("Invalid xAI OAuth response field: refresh_token", error.message)
    }

    @Test
    fun `pending then complete succeeds and posts the device grant`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        var polls = 0
        http.respond = { request ->
            if (request.url == XaiOAuthAuth.DEVICE_CODE_URL) {
                json(200, deviceCodeBody())
            } else {
                polls += 1
                if (polls == 1) {
                    json(400, """{"error":"authorization_pending"}""")
                } else {
                    json(200, tokenBody())
                }
            }
        }

        val credential = auth.login(RecordingInteraction())

        assertEquals("acc-1", credential.access)
        val tokenRequest = http.requests.last()
        assertEquals(XaiOAuthAuth.TOKEN_URL, tokenRequest.url)
        assertEquals(
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code" +
                "&client_id=b1a00492-073a-47ea-816f-4c329264a828&device_code=dev-123",
            requestBody(tokenRequest)
        )
    }

    @Test
    fun `slow_down passes the reported interval to the poller`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        var polls = 0
        http.respond = { request ->
            if (request.url == XaiOAuthAuth.DEVICE_CODE_URL) {
                json(200, deviceCodeBody())
            } else {
                polls += 1
                if (polls == 1) {
                    json(400, """{"error":"slow_down","interval":10}""")
                } else {
                    json(200, tokenBody())
                }
            }
        }
        val credential = auth.login(RecordingInteraction())
        assertEquals("acc-1", credential.access)
    }

    @Test
    fun `access_denied fails with pi message`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        http.respond = { request ->
            if (request.url == XaiOAuthAuth.DEVICE_CODE_URL) {
                json(200, deviceCodeBody())
            } else {
                json(400, """{"error":"access_denied"}""")
            }
        }
        val error = assertFailsWith<IllegalStateException> { auth.login(RecordingInteraction()) }
        assertEquals("xAI device authorization was denied", error.message)
    }

    @Test
    fun `authorization_denied also fails with pi message`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        http.respond = { request ->
            if (request.url == XaiOAuthAuth.DEVICE_CODE_URL) {
                json(200, deviceCodeBody())
            } else {
                json(400, """{"error":"authorization_denied"}""")
            }
        }
        val error = assertFailsWith<IllegalStateException> { auth.login(RecordingInteraction()) }
        assertEquals("xAI device authorization was denied", error.message)
    }

    @Test
    fun `expired_token fails with pi message`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        http.respond = { request ->
            if (request.url == XaiOAuthAuth.DEVICE_CODE_URL) {
                json(200, deviceCodeBody())
            } else {
                json(400, """{"error":"expired_token"}""")
            }
        }
        val error = assertFailsWith<IllegalStateException> { auth.login(RecordingInteraction()) }
        assertEquals("xAI device code expired", error.message)
    }

    @Test
    fun `unknown poll error uses requestFailure message`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        http.respond = { request ->
            if (request.url == XaiOAuthAuth.DEVICE_CODE_URL) {
                json(200, deviceCodeBody())
            } else {
                json(500, """{"error":"server_error","error_description":"boom"}""")
            }
        }
        val error = assertFailsWith<IllegalStateException> { auth.login(RecordingInteraction()) }
        assertEquals(
            "xAI OAuth device token polling failed (HTTP 500): server_error: boom",
            error.message
        )
    }

    @Test
    fun `invalid JSON throws pi message with status`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        http.respond = { json(200, "not json") }
        val error = assertFailsWith<IllegalStateException> { auth.login(RecordingInteraction()) }
        assertEquals("xAI OAuth returned invalid JSON (HTTP 200)", error.message)
    }

    @Test
    fun `refresh posts the refresh grant and keeps the previous token when omitted`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        http.respond = { json(200, """{"access_token":"acc-2"}""") }

        val refreshed = auth.refresh(
            OAuthCredential(access = "acc-1", refresh = "ref-1", expires = 1)
        )

        val request = lastRequest(http)
        assertEquals(XaiOAuthAuth.TOKEN_URL, request.url)
        assertEquals(
            "grant_type=refresh_token&client_id=b1a00492-073a-47ea-816f-4c329264a828&refresh_token=ref-1",
            requestBody(request)
        )
        assertEquals("acc-2", refreshed.access)
        assertEquals("ref-1", refreshed.refresh)
    }

    @Test
    fun `refresh failure carries status and detail`() = runTest {
        val http = FakeHttpClient()
        val auth = XaiOAuthAuth(http, clock = FakeClock(0L))
        http.respond = { json(401, """{"error":"invalid_grant"}""") }
        val error = assertFailsWith<IllegalStateException> {
            auth.refresh(OAuthCredential(access = "a", refresh = "r", expires = 1))
        }
        assertEquals("xAI OAuth token refresh failed (HTTP 401): invalid_grant", error.message)
    }

    @Test
    fun `form encoding matches URLSearchParams semantics`() {
        val encoded = XaiOAuthAuth.formUrlEncode(
            mapOf("a b" to "c/d", "e" to "ü~*.-_1\uD83D\uDE00")
        ).toString(Charsets.UTF_8)
        // URLSearchParams percent-encodes `~`, keeps `*`, `.-_` and
        // alphanumerics, encodes spaces as `+`, and encodes the supplementary
        // code point as its full UTF-8 sequence (not surrogate halves).
        assertEquals("a+b=c%2Fd&e=%C3%BC%7E*.-_1%F0%9F%98%80", encoded)
    }
}
