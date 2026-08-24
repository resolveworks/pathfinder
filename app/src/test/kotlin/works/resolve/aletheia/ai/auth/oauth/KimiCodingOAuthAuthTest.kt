package works.resolve.aletheia.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import works.resolve.aletheia.ai.auth.AuthEvent
import works.resolve.aletheia.ai.auth.AuthInteraction
import works.resolve.aletheia.ai.auth.OAuthCredential

/**
 * Ports the semantics of pi `packages/ai/src/auth/oauth/kimi-coding.ts`
 * (and its use of `packages/ai/src/auth/oauth/device-code.ts`).
 *
 * Time is deterministic: `runTest` virtualizes [kotlinx.coroutines.delay]
 * (used by the poller and the refresh backoff), and the flow's `now` clock is
 * bound to the test scheduler's virtual clock so `expires` computations are
 * stable. Virtual request timestamps are recorded for interval assertions.
 */
class KimiCodingOAuthAuthTest {

    private class RecordingInteraction : AuthInteraction {
        val events = mutableListOf<AuthEvent>()
        override suspend fun prompt(prompt: works.resolve.aletheia.ai.auth.AuthPrompt): String =
            throw UnsupportedOperationException()
        override suspend fun notify(event: AuthEvent) {
            events += event
        }
    }

    /** Scripted [OAuthHttpClient] that records virtual-time request timestamps. */
    private class FakeHttpClient(
        private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        private val respond: suspend (request: OAuthHttpRequest) -> OAuthHttpResponse,
    ) : OAuthHttpClient {
        val requests = mutableListOf<Pair<Long, OAuthHttpRequest>>()
        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += scheduler.currentTime to request
            return respond(request)
        }
    }

    private fun json(status: Int, body: String): OAuthHttpResponse =
        OAuthHttpResponse(status, mapOf("content-type" to listOf("application/json")), body.toByteArray())

    private fun deviceAuthorizationBody(
        interval: String? = "\"interval\":2",
        expires: String? = "\"expires_in\":600",
    ): String =
        "{" +
            "\"device_code\":\"dev-1\"," +
            "\"user_code\":\"ABCD-EFGH\"," +
            "\"verification_uri\":\"https://auth.kimi.com/device\"," +
            "\"verification_uri_complete\":\"https://auth.kimi.com/device?code=ABCD-EFGH\"" +
            (interval?.let { ",$it" } ?: "") +
            (expires?.let { ",$it" } ?: "") +
            "}"

    private suspend fun kotlinx.coroutines.test.TestScope.newFlow(
        respond: suspend (OAuthHttpRequest) -> OAuthHttpResponse,
    ): Pair<KimiCodingOAuthAuth, FakeHttpClient> {
        val http = FakeHttpClient(testScheduler, respond)
        val flow = KimiCodingOAuthAuth(http, now = { testScheduler.currentTime })
        return flow to http
    }

    // --- login: device authorization + poll ---

    @Test
    fun `login happy path sends correct requests and notifies device code event`() = runTest {
        val (flow, http) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) {
                json(200, deviceAuthorizationBody())
            } else {
                json(
                    200,
                    "{\"access_token\":\"acc-1\",\"refresh_token\":\"ref-1\",\"expires_in\":3600}",
                )
            }
        }
        val interaction = RecordingInteraction()

        val credential = flow.login(interaction)

        // expires_in=3600 measured at poll time, which is t=2s (after wait-before-first-poll)
        assertEquals(OAuthCredential(access = "acc-1", refresh = "ref-1", expires = 3_602_000), credential)

        // device authorization request: pi's form body, headers, and 30s bound
        val auth = http.requests.first().second
        assertEquals("POST", auth.method)
        assertEquals("https://auth.kimi.com/api/oauth/device_authorization", auth.url)
        assertEquals(
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            auth.headers,
        )
        assertEquals("client_id=17e5f671-d194-4dfb-9706-5516cb48c098", auth.body.toString(Charsets.UTF_8))
        assertEquals(KimiCodingOAuthAuth.REQUEST_TIMEOUT_MS, auth.timeoutMs)

        // pi notifies verification_uri_complete + server interval/expiry
        assertEquals(
            AuthEvent.DeviceCode(
                userCode = "ABCD-EFGH",
                verificationUri = "https://auth.kimi.com/device?code=ABCD-EFGH",
                intervalSeconds = 2,
                expiresInSeconds = 600,
            ),
            interaction.events.single(),
        )

        // waitBeforeFirstPoll: the first token poll happens after one 2s interval
        assertEquals(2_000, http.requests[1].first)
        // device_code grant body
        assertEquals(
            "client_id=17e5f671-d194-4dfb-9706-5516cb48c098&device_code=dev-1&" +
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            http.requests[1].second.body.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `fallback interval and expiry when fields are missing or invalid`() = runTest {
        val (flow, http) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) {
                json(200, deviceAuthorizationBody(interval = "\"interval\":\"nope\"", expires = "\"expires_in\":0"))
            } else {
                json(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}")
            }
        }
        val interaction = RecordingInteraction()

        flow.login(interaction)

        // pi falls back to DEFAULT_POLL_INTERVAL_SECONDS / DEVICE_CODE_TIMEOUT_SECONDS
        assertEquals(5, interaction.events.single().let { (it as AuthEvent.DeviceCode).intervalSeconds })
        assertEquals(15 * 60, (interaction.events.single() as AuthEvent.DeviceCode).expiresInSeconds)
        assertEquals(5_000, http.requests[1].first)
    }

    @Test
    fun `device authorization failure surfaces pi's status message`() = runTest {
        val (flow, _) = newFlow { json(500, "boom") }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals("Kimi Code device authorization failed with status 500: boom", error.message)
    }

    @Test
    fun `non-http verification uris are rejected`() = runTest {
        val (flow, _) = newFlow {
            json(
                200,
                deviceAuthorizationBody().replace("https://auth.kimi.com/device?code=ABCD-EFGH", "ftp://auth.kimi.com/device"),
            )
        }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertTrue(error.message!!.startsWith("Invalid Kimi Code device authorization response:"))
    }

    @Test
    fun `missing device fields are rejected`() = runTest {
        val (flow, _) = newFlow { json(200, "{\"device_code\":\"dev-1\"}") }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertTrue(error.message!!.startsWith("Invalid Kimi Code device authorization response:"))
    }

    @Test
    fun `authorization_pending polls until complete`() = runTest {
        var polls = 0
        val (flow, http) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) {
                json(200, deviceAuthorizationBody(interval = "\"interval\":1"))
            } else {
                polls++
                if (polls < 3) json(400, "{\"error\":\"authorization_pending\"}")
                else json(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}")
            }
        }

        val credential = flow.login(RecordingInteraction())

        assertEquals("a", credential.access)
        // first poll after 1s wait, then 1s intervals between polls
        assertEquals(listOf(1_000L, 2_000L, 3_000L), http.requests.drop(1).map { it.first })
    }

    @Test
    fun `slow_down uses server interval when provided`() = runTest {
        var polls = 0
        val (flow, http) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) {
                json(200, deviceAuthorizationBody(interval = "\"interval\":1"))
            } else {
                polls++
                if (polls == 1) json(400, "{\"error\":\"slow_down\",\"interval\":4}")
                else json(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}")
            }
        }

        flow.login(RecordingInteraction())

        // poll at 1s (after wait-before-first-poll), then the server-provided 4s interval
        assertEquals(listOf(1_000L, 5_000L), http.requests.drop(1).map { it.first })
    }

    @Test
    fun `slow_down without interval adds five seconds`() = runTest {
        var polls = 0
        val (flow, http) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) {
                json(200, deviceAuthorizationBody(interval = "\"interval\":1"))
            } else {
                polls++
                if (polls == 1) json(400, "{\"error\":\"slow_down\"}")
                else json(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}")
            }
        }

        flow.login(RecordingInteraction())

        // 1s wait, then RFC 8628 section 3.5: 1s + 5s
        assertEquals(listOf(1_000L, 7_000L), http.requests.drop(1).map { it.first })
    }

    @Test
    fun `expired_token fails with pi's message`() = runTest {
        val (flow, _) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) json(200, deviceAuthorizationBody())
            else json(400, "{\"error\":\"expired_token\"}")
        }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals("Kimi Code device authorization expired. Please restart login.", error.message)
    }

    @Test
    fun `access_denied fails with pi's message`() = runTest {
        val (flow, _) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) json(200, deviceAuthorizationBody())
            else json(400, "{\"error\":\"access_denied\"}")
        }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals("Kimi Code login was denied.", error.message)
    }

    @Test
    fun `unknown poll error fails with status and error`() = runTest {
        val (flow, _) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) json(200, deviceAuthorizationBody())
            else json(400, "{\"error\":\"server_error\",\"error_description\":\"nope\"}")
        }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals(
            "Kimi Code device token request failed (status 400): server_error: nope",
            error.message,
        )
    }

    @Test
    fun `poll response with status 500 fails immediately`() = runTest {
        val (flow, _) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) json(200, deviceAuthorizationBody())
            else json(503, "overloaded")
        }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals(
            "Kimi Code device token request failed with status 503: overloaded",
            error.message,
        )
    }

    @Test
    fun `token parse failure on ok poll response becomes a failed poll`() = runTest {
        val (flow, _) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) json(200, deviceAuthorizationBody())
            else json(200, "{\"access_token\":\"a\"}")
        }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals(
            "Kimi Code token poll response missing fields: {\"access_token\":\"a\"}",
            error.message,
        )
    }

    @Test
    fun `device flow timeout surfaces pi's message`() = runTest {
        val (flow, _) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) {
                json(200, deviceAuthorizationBody(interval = "\"interval\":1", expires = "\"expires_in\":1"))
            } else {
                json(400, "{\"error\":\"authorization_pending\"}")
            }
        }
        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals("Device flow timed out", error.message)
    }

    // --- refresh ---

    @Test
    fun `refresh exchanges the refresh token and computes expiry`() = runTest {
        val (flow, http) = newFlow {
            json(200, "{\"access_token\":\"acc-2\",\"refresh_token\":\"ref-2\",\"expires_in\":120}")
        }

        val credential = flow.refresh(OAuthCredential(access = "old", refresh = "ref-1", expires = 0))

        assertEquals(OAuthCredential(access = "acc-2", refresh = "ref-2", expires = 120_000), credential)
        val request = http.requests.single().second
        assertEquals("https://auth.kimi.com/api/oauth/token", request.url)
        assertEquals(
            "client_id=17e5f671-d194-4dfb-9706-5516cb48c098&grant_type=refresh_token&refresh_token=ref-1",
            request.body.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `refresh retries 5xx with 1s 2s 4s backoff then succeeds`() = runTest {
        var attempts = 0
        val (flow, http) = newFlow {
            attempts++
            if (attempts <= 3) json(500, "{}") else json(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}")
        }

        val credential = flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0))

        assertEquals("a", credential.access)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), http.requests.map { it.first })
    }

    @Test
    fun `refresh retries network failures and 429`() = runTest {
        var attempts = 0
        val (flow, http) = newFlow {
            attempts++
            when (attempts) {
                1 -> throw java.io.IOException("connection reset")
                2 -> json(429, "{}")
                else -> json(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}")
            }
        }

        assertEquals("a", flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0)).access)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun `refresh gives up after three retries`() = runTest {
        val (flow, http) = newFlow { json(503, "{}") }

        val error = assertFailsWith<IllegalStateException> {
            flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0))
        }
        // pi renders the JSON body via JSON.stringify: "{}" is truthy, so ": {}" is appended
        assertEquals("Kimi Code token refresh failed with status 503: {}", error.message)
        // pi: attempt 0..REFRESH_MAX_RETRIES = 4 requests, backoff 1s/2s/4s
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), http.requests.map { it.first })
    }

    @Test
    fun `refresh fails immediately on 401`() = runTest {
        val (flow, http) = newFlow { json(401, "{\"error_description\":\"expired\"}") }

        val error = assertFailsWith<IllegalStateException> {
            flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0))
        }
        assertEquals("Kimi Code token refresh unauthorized (status 401): expired", error.message)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `refresh fails immediately on 403`() = runTest {
        val (flow, http) = newFlow { json(403, "{}") }

        val error = assertFailsWith<IllegalStateException> {
            flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0))
        }
        assertEquals("Kimi Code token refresh unauthorized (status 403)", error.message)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `refresh fails immediately on invalid_grant`() = runTest {
        val (flow, http) = newFlow { json(400, "{\"error\":\"invalid_grant\"}") }

        val error = assertFailsWith<IllegalStateException> {
            flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0))
        }
        assertEquals("Kimi Code token refresh unauthorized (status 400)", error.message)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `refresh fails immediately on other 4xx`() = runTest {
        val (flow, http) = newFlow { json(404, "{\"error\":\"not_found\"}") }

        val error = assertFailsWith<IllegalStateException> {
            flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0))
        }
        assertEquals("Kimi Code token refresh failed with status 404: {\"error\":\"not_found\"}", error.message)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `refresh token parse failure surfaces pi's message`() = runTest {
        val (flow, _) = newFlow { json(200, "{\"access_token\":\"a\",\"expires_in\":60}") }

        val error = assertFailsWith<IllegalStateException> {
            flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0))
        }
        assertEquals(
            "Kimi Code token refresh response missing fields: {\"access_token\":\"a\",\"expires_in\":60}",
            error.message,
        )
    }

    @Test
    fun `refresh cancellation during backoff is prompt`() = runTest {
        val (flow, _) = newFlow { json(500, "{}") }
        val job = async {
            flow.refresh(OAuthCredential(access = "", refresh = "ref", expires = 0))
        }
        // Run the first attempt (fails at t=0) and start the 1s backoff sleep.
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    // --- toAuth / constants ---

    @Test
    fun `toAuth shapes the Bearer authorization header`() = runTest {
        val (flow, _) = newFlow { throw UnsupportedOperationException() }
        val auth = flow.toAuth(OAuthCredential(access = "token-1", refresh = "r", expires = 1))
        assertEquals(mapOf("Authorization" to "Bearer token-1"), auth.headers)
        assertEquals(null, auth.apiKey)
    }

    @Test
    fun `metadata mirrors pi`() = runTest {
        val (flow, _) = newFlow { throw UnsupportedOperationException() }
        assertEquals("Kimi Code (subscription)", flow.name)
        assertTrue(flow.isSubscription)
        assertEquals("Sign in with Kimi Code", flow.loginLabel)
        assertEquals("https://auth.kimi.com", KimiCodingOAuthAuth.OAUTH_HOST)
    }

    // --- form encoding / URL trust ---

    @Test
    fun `formUrlEncode matches URLSearchParams percent-encoding`() {
        // URLSearchParams.toString() encodes spaces as '+' on the wire
        assertEquals(
            "a=1&b=hello+world&c=x%2By",
            KimiCodingOAuthAuth.formUrlEncode(
                linkedMapOf("a" to "1", "b" to "hello world", "c" to "x+y"),
            ).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `trustedHttpUrl accepts only http and https and normalizes like URL href`() {
        assertEquals("https://a.example/x", KimiCodingOAuthAuth.trustedHttpUrl("https://a.example/x"))
        assertEquals("http://a.example/x", KimiCodingOAuthAuth.trustedHttpUrl("http://a.example/x"))
        // URL.href adds the root path for authority-only URLs; so does toExternalForm
        assertEquals("https://a.example/", KimiCodingOAuthAuth.trustedHttpUrl("https://a.example"))
        assertEquals(null, KimiCodingOAuthAuth.trustedHttpUrl("ftp://a.example/x"))
        assertEquals(null, KimiCodingOAuthAuth.trustedHttpUrl("javascript:alert(1)"))
        assertEquals(null, KimiCodingOAuthAuth.trustedHttpUrl(""))
        assertEquals(null, KimiCodingOAuthAuth.trustedHttpUrl(null))
        assertEquals(null, KimiCodingOAuthAuth.trustedHttpUrl("not a url"))
    }

    @Test
    fun `quoted numeric interval and expires_in fall back to defaults`() = runTest {
        // pi requires typeof number: '"interval":"2"' is a string, so the fallback applies
        val (flow, http) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) {
                json(200, deviceAuthorizationBody(interval = "\"interval\":\"2\"", expires = "\"expires_in\":\"600\""))
            } else {
                json(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}")
            }
        }
        val interaction = RecordingInteraction()

        flow.login(interaction)

        assertEquals(5, (interaction.events.single() as AuthEvent.DeviceCode).intervalSeconds)
        assertEquals(15 * 60, (interaction.events.single() as AuthEvent.DeviceCode).expiresInSeconds)
        assertEquals(5_000, http.requests[1].first)
    }

    @Test
    fun `quoted numeric expires_in in a token response fails as missing fields`() = runTest {
        val (flow, _) = newFlow { request ->
            if (request.url.endsWith("device_authorization")) {
                json(200, deviceAuthorizationBody())
            } else {
                json(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":\"60\"}")
            }
        }

        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals(
            "Kimi Code token poll response missing fields: " +
                "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":\"60\"}",
            error.message,
        )
    }

    @Test
    fun `array device authorization body renders in the malformed-response error`() = runTest {
        // JS typeof [] === "object", so pi's readJson keeps the array and
        // JSON.stringify renders it; field lookup fails like json?.field → undefined
        val (flow, _) = newFlow { json(200, "[\"device_code\"]") }

        val error = assertFailsWith<IllegalStateException> { flow.login(RecordingInteraction()) }
        assertEquals(
            "Invalid Kimi Code device authorization response: [\"device_code\"]",
            error.message,
        )
    }
}
