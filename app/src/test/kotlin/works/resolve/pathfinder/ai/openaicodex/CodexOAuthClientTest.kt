package works.resolve.pathfinder.ai.openaicodex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CodexOAuthClientTest {

    private fun mockClient(vararg responses: Response): HttpClient =
        HttpClient(MockEngine(createHandler(responses.toList())))

    private class Response(
        val status: HttpStatusCode,
        val body: String,
    ) {
        companion object {
            fun ok(body: String) = Response(HttpStatusCode.OK, body)
            fun status(code: HttpStatusCode, body: String = "") = Response(code, body)
        }
    }

    private fun createHandler(responses: List<Response>): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData {
        val queue = ArrayDeque(responses)
        return { request ->
            val response = queue.removeFirstOrNull()
                ?: error("No mock response left for ${request.url.encodedPath}")
            respond(response.body, response.status, headersOf("Content-Type", "application/json"))
        }
    }

    private fun userCodeBody(interval: String) =
        """{"device_auth_id":"dav-1","user_code":"ABCD-EFGH","interval":$interval}"""

    private fun tokenBody(accessPayloadJson: String = jwtPayloadWithAccount("acct-42")): String {
        val jwt = "header.${base64Url(accessPayloadJson)}.signature"
        return """{"access_token":"$jwt","refresh_token":"rt-1","expires_in":3600}"""
    }

    private fun jwtPayloadWithAccount(accountId: String): String =
        """{"sub":"u","https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""

    private fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.encodeToByteArray())

    private fun device(intervalSeconds: Long = 1) =
        CodexDeviceAuth("dav-1", "ABCD-EFGH", "https://auth.openai.com/codex/device", intervalSeconds)

    // --- beginDeviceLogin ---

    @Test
    fun `begin parses interval as number`() = runTest {
        val oauth = CodexOAuthClient(mockClient(Response.ok(userCodeBody("5"))), clock = { testScheduler.currentTime })
        val result = oauth.beginDeviceLogin()
        assertEquals("dav-1", result.deviceAuthId)
        assertEquals("ABCD-EFGH", result.userCode)
        assertEquals("https://auth.openai.com/codex/device", result.verificationUri)
        assertEquals(5, result.intervalSeconds)
    }

    @Test
    fun `begin parses interval as numeric string`() = runTest {
        val oauth = CodexOAuthClient(mockClient(Response.ok(userCodeBody("\"7\""))), clock = { testScheduler.currentTime })
        assertEquals(7, oauth.beginDeviceLogin().intervalSeconds)
    }

    @Test
    fun `begin rejects invalid interval`() = runTest {
        val oauth = CodexOAuthClient(mockClient(Response.ok(userCodeBody("\"soon\""))), clock = { testScheduler.currentTime })
        assertFailsWith<CodexOAuthException> { oauth.beginDeviceLogin() }
    }

    @Test
    fun `begin rejects non-200`() = runTest {
        val oauth = CodexOAuthClient(mockClient(Response.status(HttpStatusCode.BadRequest)), clock = { testScheduler.currentTime })
        assertFailsWith<CodexOAuthException> { oauth.beginDeviceLogin() }
    }

    // --- awaitDeviceAuthorization polling ---

    @Test
    fun `await polls 403 pending then completes`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(
                Response.status(HttpStatusCode.Forbidden),
                Response.ok("""{"authorization_code":"ac-1","code_verifier":"cv-1"}"""),
                Response.ok(tokenBody()),
            ),
                clock = { testScheduler.currentTime },
        )
        val tokens = oauth.awaitDeviceAuthorization(device())
        assertEquals("acct-42", tokens.accountId)
        assertEquals(testScheduler.currentTime + 3_600_000, tokens.expiresAtEpochMillis)
    }

    @Test
    fun `await treats 404 and pending error as pending`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(
                Response.status(HttpStatusCode.NotFound),
                Response.status(HttpStatusCode.BadRequest, """{"error":{"code":"deviceauth_authorization_pending"}}"""),
                Response.ok("""{"authorization_code":"ac-1","code_verifier":"cv-1"}"""),
                Response.ok(tokenBody()),
            ),
                clock = { testScheduler.currentTime },
        )
        assertEquals("acct-42", oauth.awaitDeviceAuthorization(device()).accountId)
    }

    @Test
    fun `await treats string error as pending`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(
                Response.status(HttpStatusCode.BadRequest, """{"error":"deviceauth_authorization_pending"}"""),
                Response.ok("""{"authorization_code":"ac-1","code_verifier":"cv-1"}"""),
                Response.ok(tokenBody()),
            ),
                clock = { testScheduler.currentTime },
        )
        assertEquals("acct-42", oauth.awaitDeviceAuthorization(device()).accountId)
    }

    @Test
    fun `slow_down adds one extra interval to the wait`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(
                Response.status(HttpStatusCode.TooManyRequests, """{"error":"slow_down"}"""),
                Response.ok("""{"authorization_code":"ac-1","code_verifier":"cv-1"}"""),
                Response.ok(tokenBody()),
            ),
                clock = { testScheduler.currentTime },
        )
        val start = testScheduler.currentTime
        oauth.awaitDeviceAuthorization(device(intervalSeconds = 5))
        // First poll at t=0, slow_down waits interval + one extra interval,
        // second poll and exchange happen at t=10s.
        assertEquals(start + 10_000, testScheduler.currentTime)
    }

    @Test
    fun `unknown poll error fails`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(Response.status(HttpStatusCode.BadRequest, """{"error":"access_denied"}""")),
        )
        assertFailsWith<CodexOAuthException> { oauth.awaitDeviceAuthorization(device()) }
    }

    @Test
    fun `await times out after 15 minutes`() = runTest {
        val pending = generateSequence { Response.status(HttpStatusCode.Forbidden) }.take(1000).toList()
        val oauth = CodexOAuthClient(mockClient(*pending.toTypedArray()), clock = { testScheduler.currentTime })
        val failure = assertFailsWith<CodexOAuthException> { oauth.awaitDeviceAuthorization(device()) }
        assertTrue(failure.message!!.contains("timed out", ignoreCase = true))
        assertTrue(testScheduler.currentTime <= 15 * 60 * 1000 + 1000)
    }

    @Test
    fun `await rejects complete response missing fields`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(Response.ok("""{"authorization_code":"ac-1"}""")),
        )
        assertFailsWith<CodexOAuthException> { oauth.awaitDeviceAuthorization(device()) }
    }

    // --- exchange response validation ---

    @Test
    fun `exchange response missing fields fails`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(
                Response.ok("""{"authorization_code":"ac-1","code_verifier":"cv-1"}"""),
                Response.ok("""{"access_token":"x","expires_in":3600}"""),
            ),
                clock = { testScheduler.currentTime },
        )
        assertFailsWith<CodexOAuthException> { oauth.awaitDeviceAuthorization(device()) }
    }

    @Test
    fun `exchange non-200 fails`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(
                Response.ok("""{"authorization_code":"ac-1","code_verifier":"cv-1"}"""),
                Response.status(HttpStatusCode.BadRequest),
            ),
                clock = { testScheduler.currentTime },
        )
        assertFailsWith<CodexOAuthException> { oauth.awaitDeviceAuthorization(device()) }
    }

    // --- refresh ---

    @Test
    fun `refresh happy path`() = runTest {
        val oauth = CodexOAuthClient(mockClient(Response.ok(tokenBody())), clock = { testScheduler.currentTime })
        val tokens = oauth.refresh("rt-old")
        assertEquals("acct-42", tokens.accountId)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals(testScheduler.currentTime + 3_600_000, tokens.expiresAtEpochMillis)
    }

    @Test
    fun `refresh missing fields fails`() = runTest {
        val oauth = CodexOAuthClient(mockClient(Response.ok("""{"access_token":"x"}""")))
        assertFailsWith<CodexOAuthException> { oauth.refresh("rt-old") }
    }

    // --- JWT decode ---

    @Test
    fun `jwt without account claim fails`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(Response.ok(tokenBody("""{"sub":"u"}"""))),
        )
        assertFailsWith<CodexOAuthException> { oauth.refresh("rt-old") }
    }

    @Test
    fun `jwt with empty account claim fails`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(Response.ok(tokenBody(jwtPayloadWithAccount("")))),
        )
        assertFailsWith<CodexOAuthException> { oauth.refresh("rt-old") }
    }

    @Test
    fun `jwt with malformed base64 fails`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(Response.ok("""{"access_token":"h.!!!!.s","refresh_token":"rt","expires_in":1}""")),
        )
        assertFailsWith<CodexOAuthException> { oauth.refresh("rt-old") }
    }

    @Test
    fun `non-JWT access token fails`() = runTest {
        val oauth = CodexOAuthClient(
            mockClient(Response.ok("""{"access_token":"not-a-jwt","refresh_token":"rt","expires_in":1}""")),
        )
        assertFailsWith<CodexOAuthException> { oauth.refresh("rt-old") }
    }
}
