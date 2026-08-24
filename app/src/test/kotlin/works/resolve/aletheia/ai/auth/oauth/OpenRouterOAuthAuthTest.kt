package works.resolve.aletheia.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import works.resolve.aletheia.ai.auth.AuthEvent
import works.resolve.aletheia.ai.auth.AuthInteraction
import works.resolve.aletheia.ai.auth.AuthPrompt
import works.resolve.aletheia.ai.auth.ModelAuth
import works.resolve.aletheia.ai.auth.OAuthCredential
import works.resolve.aletheia.ai.auth.Pkce
import works.resolve.aletheia.ai.auth.PkceGenerator
import java.io.IOException
import java.net.SocketTimeoutException

/** Ports the semantics of pi `packages/ai/src/auth/oauth/openrouter.ts`. */
class OpenRouterOAuthAuthTest {

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

    private class FakeHttpClient(
        var respond: suspend (OAuthHttpRequest) -> OAuthHttpResponse = {
            OAuthHttpResponse(200, mapOf("content-type" to listOf("application/json")), "{\"key\":\"sk-or-key\"}".toByteArray())
        },
    ) : OAuthHttpClient {
        val requests = mutableListOf<OAuthHttpRequest>()
        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            return respond(request)
        }
    }

    /** Deterministic PKCE: fixed bytes make verifier/challenge stable reference vectors. */
    private fun fixedPkce(): PkceGenerator = PkceGenerator(randomBytes = { count -> ByteArray(count) { it.toByte() } })

    private fun jsonResponse(status: Int, body: String): OAuthHttpResponse =
        OAuthHttpResponse(status, emptyMap(), body.toByteArray())

    private fun flow(): Pair<OpenRouterOAuthAuth, FakeHttpClient> {
        val http = FakeHttpClient()
        return OpenRouterOAuthAuth(http, fixedPkce()) to http
    }

    private fun pkce(): Pkce = fixedPkce().generate()

    @Test
    fun `authorize URL uses headless PKCE mode without callback_url`() = runBlocking {
        val (auth, http) = flow()
        val interaction = RecordingInteraction()

        auth.login(interaction)

        val urlEvent = interaction.events.filterIsInstance<AuthEvent.AuthUrl>().single()
        val pkcePair = pkce()
        assertEquals(
            "https://openrouter.ai/auth?code_challenge=${pkcePair.challenge}" +
                "&code_challenge_method=S256&key_label=Aletheia",
            urlEvent.url,
        )
        assertTrue("callback_url" !in urlEvent.url)
        assertTrue(urlEvent.instructions!!.contains("authorization code"))
        // Login completes without any request other than the key exchange.
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `manual code prompt mirrors pi's message`() = runBlocking {
        val (auth, _) = flow()
        val interaction = RecordingInteraction()

        auth.login(interaction)

        val prompt = interaction.prompts.single() as AuthPrompt.ManualCode
        assertEquals(
            "Complete sign-in in your browser, or paste the authorization code / redirect URL here:",
            prompt.message,
        )
    }

    @Test
    fun `raw code, query string, and redirect URL inputs are all accepted`() = runBlocking {
        val inputs = mapOf(
            "or-v1-abc123" to "or-v1-abc123",
            "  or-v1-abc123  " to "or-v1-abc123",
            "code=or-v1-abc123" to "or-v1-abc123",
            "foo=1&code=or-v1-abc123&state=x" to "or-v1-abc123",
            "https://openrouter.ai/auth?code=or-v1-abc123" to "or-v1-abc123",
            "https://127.0.0.1:5173/oauth/callback/x?code=or-v1-abc" to "or-v1-abc",
            "https://example.com/cb?code=a%2Bb%3Dc" to "a+b=c",
        )
        for ((input, expected) in inputs) {
            val sent = CompletableDeferred<String>()
            val http = FakeHttpClient(respond = { request ->
                sent.complete(parseCode(request))
                jsonResponse(200, "{\"key\":\"k\"}")
            })
            OpenRouterOAuthAuth(http, fixedPkce()).login(RecordingInteraction(input))
            assertEquals(expected, sent.await(), "input: $input")
        }
    }

    @Test
    fun `URL without code and blank input fail with Missing authorization code`() {
        for (input in listOf("https://example.com/cb?state=1", "", "   ")) {
            val (auth, http) = flow()
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { auth.login(RecordingInteraction(input)) }
            }
            assertEquals("Missing authorization code", error.message)
            assertTrue(http.requests.isEmpty())
        }
    }

    @Test
    fun `successful exchange posts pi's payload and returns a non-expiring credential`() = runBlocking {
        val (auth, http) = flow()
        val interaction = RecordingInteraction("https://openrouter.ai/auth?code=or-v1-xyz")

        val credential = auth.login(interaction)

        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals(OpenRouterOAuthAuth.TOKEN_URL, request.url)
        assertEquals(
            mapOf("accept" to "application/json", "content-type" to "application/json"),
            request.headers,
        )
        assertEquals(OpenRouterOAuthAuth.TOKEN_EXCHANGE_TIMEOUT_MS, request.timeoutMs)
        val sent = Json.parseToJsonElement(request.body.decodeToString()).let { it as kotlinx.serialization.json.JsonObject }
        val pkcePair = pkce()
        assertEquals("or-v1-xyz", sent["code"]!!.jsonPrimitiveContent())
        assertEquals(pkcePair.verifier, sent["code_verifier"]!!.jsonPrimitiveContent())
        assertEquals("S256", sent["code_challenge_method"]!!.jsonPrimitiveContent())

        assertEquals("sk-or-key", credential.access)
        assertEquals("", credential.refresh)
        assertEquals(OpenRouterOAuthAuth.NON_EXPIRING_EPOCH_MS, credential.expires)

        val progress = interaction.events.filterIsInstance<AuthEvent.Progress>().single()
        assertEquals("Exchanging authorization code for an API key...", progress.message)
    }

    @Test
    fun `missing, empty, and non-string key fields fail with pi's message`() {
        for (body in listOf("{}", "{\"key\":\"\"}", "{\"key\":42}", "{\"key\":{\"a\":1}}", "\"scalar\"", "[1,2]")) {
            val http = FakeHttpClient(respond = { jsonResponse(200, body) })
            val auth = OpenRouterOAuthAuth(http, fixedPkce())
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { auth.login(RecordingInteraction("the-code")) }
            }
            assertEquals("OpenRouter OAuth response carries no \"key\"", error.message, "body: $body")
        }
    }

    @Test
    fun `HTTP errors carry status and every error envelope detail variant`() {
        val cases = listOf(
            Triple(400, "{\"error\":\"bad_request\"}",
                "OpenRouter OAuth key exchange failed (HTTP 400): bad_request"),
            Triple(403, "{\"error_description\":\"code expired\"}",
                "OpenRouter OAuth key exchange failed (HTTP 403): code expired"),
            Triple(403, "{\"message\":\"Invalid code or code_verifier\"}",
                "OpenRouter OAuth key exchange failed (HTTP 403): Invalid code or code_verifier"),
            Triple(403, "{\"error\":{\"message\":\"denied\"}}",
                "OpenRouter OAuth key exchange failed (HTTP 403): denied"),
        )
        for ((status, body, expected) in cases) {
            val auth = OpenRouterOAuthAuth(FakeHttpClient(respond = { jsonResponse(status, body) }), fixedPkce())
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { auth.login(RecordingInteraction("the-code")) }
            }
            assertEquals(expected, error.message, "body: $body")
        }
        // No recognizable detail: status only. Non-JSON error bodies are tolerated.
        val auth = OpenRouterOAuthAuth(FakeHttpClient(respond = { jsonResponse(500, "<html>oops") }), fixedPkce())
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.login(RecordingInteraction("the-code")) }
        }
        assertEquals("OpenRouter OAuth key exchange failed (HTTP 500)", error.message)
    }

    @Test
    fun `invalid JSON on a successful response fails with pi's message`() {
        val auth = OpenRouterOAuthAuth(FakeHttpClient(respond = { jsonResponse(200, "not json") }), fixedPkce())
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.login(RecordingInteraction("the-code")) }
        }
        assertEquals("OpenRouter OAuth returned invalid JSON", error.message)
    }

    @Test
    fun `bounded exchange timeout maps to pi's timeout error`() {
        val auth = OpenRouterOAuthAuth(
            FakeHttpClient(respond = { throw SocketTimeoutException("read timed out") }),
            fixedPkce(),
        )
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.login(RecordingInteraction("the-code")) }
        }
        assertEquals("OpenRouter OAuth token exchange timed out", error.message)
    }

    @Test
    fun `cancellation at the HTTP boundary propagates unwrapped`() {
        val auth = OpenRouterOAuthAuth(
            FakeHttpClient(respond = { throw CancellationException("Login cancelled") }),
            fixedPkce(),
        )
        assertFailsWith<CancellationException> {
            runBlocking { auth.login(RecordingInteraction("the-code")) }
        }
    }

    @Test
    fun `network failures propagate as IOException for orchestration to wrap`() {
        val auth = OpenRouterOAuthAuth(
            FakeHttpClient(respond = { throw IOException("connection reset") }),
            fixedPkce(),
        )
        assertFailsWith<IOException> {
            runBlocking { auth.login(RecordingInteraction("the-code")) }
        }
    }

    @Test
    fun `refresh is a no-op and toAuth yields the key as apiKey`() = runBlocking {
        val (auth, _) = flow()
        val credential = OAuthCredential("sk-or-key", "", OpenRouterOAuthAuth.NON_EXPIRING_EPOCH_MS)
        assertEquals(credential, auth.refresh(credential))
        assertEquals(ModelAuth(apiKey = "sk-or-key"), auth.toAuth(credential))
    }

    @Test
    fun `labels match pi`() = runBlocking {
        val (auth, _) = flow()
        assertEquals("OpenRouter OAuth", auth.name)
        assertEquals("Sign in with OpenRouter", auth.loginLabel)
    }

    @Test
    fun `no secret material leaks into errors, events, or toString`() = runBlocking {
        val pkcePair = pkce()
        val http = FakeHttpClient(respond = { jsonResponse(403, "{\"error_description\":\"denied: or-v1-secret\"}") })
        val auth = OpenRouterOAuthAuth(http, fixedPkce())
        val interaction = RecordingInteraction("or-v1-secret")

        val error = assertFailsWith<IllegalStateException> { auth.login(interaction) }

        // The server-provided detail is safe to surface (pi does the same);
        // everything the client itself knows must be redacted.
        val request = http.requests.single()
        assertTrue("or-v1-secret" in request.body.decodeToString()) // sent, but…
        assertTrue("or-v1-secret" !in request.toString())
        assertTrue(pkcePair.verifier !in request.toString())
        assertTrue(pkcePair.verifier !in error.toString())

        val credential = OAuthCredential("sk-or-key", "", 1)
        assertTrue("sk-or-key" !in credential.toString())
    }

    private fun parseCode(request: OAuthHttpRequest): String =
        ((Json.parseToJsonElement(request.body.decodeToString()) as kotlinx.serialization.json.JsonObject)["code"]
            as kotlinx.serialization.json.JsonPrimitive).content

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
        (this as kotlinx.serialization.json.JsonPrimitive).content

}
