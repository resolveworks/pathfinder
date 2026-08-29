package works.resolve.pathfinder.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.Pkce
import works.resolve.pathfinder.ai.auth.PkceGenerator
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

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

    /** Never answers the manual prompt, so the loopback callback (or timeout) decides the race. */
    private class HangingInteraction : AuthInteraction {
        val events = mutableListOf<AuthEvent>()
        val prompts = mutableListOf<AuthPrompt>()
        val authUrl = CompletableDeferred<AuthEvent.AuthUrl>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            awaitCancellation()
        }

        override suspend fun notify(event: AuthEvent) {
            events += event
            if (event is AuthEvent.AuthUrl) authUrl.complete(event)
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

    /** Drives the loopback callback with a real HTTP GET (server transport itself is covered by LoopbackOAuthServerTest). */
    private fun httpGet(url: String): Pair<Int, String> = runBlocking {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            try {
                val status = connection.responseCode
                val stream = if (status in 200..399) connection.inputStream else connection.errorStream
                status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun callbackUrlFrom(authorizeUrl: String): String {
        val query = authorizeUrl.substringAfter("?", "")
        return parseQuery(query).entries.first { it.key == "callback_url" }.value
    }

    @Test
    fun `authorize URL carries the loopback callback_url with pi's exact param order`() = runBlocking {
        val (auth, http) = flow()
        val interaction = RecordingInteraction()

        auth.login(interaction)

        val urlEvent = interaction.events.filterIsInstance<AuthEvent.AuthUrl>().single()
        val pkcePair = pkce()
        val callbackUrl = callbackUrlFrom(urlEvent.url)
        assertTrue(
            Regex("""^http://127\.0\.0\.1:\d+/oauth/callback/[0-9a-f-]{36}$""").matches(callbackUrl),
            "callback_url: $callbackUrl",
        )
        assertEquals(
            "https://openrouter.ai/auth?callback_url=" + java.net.URLEncoder.encode(callbackUrl, "UTF-8").replace("+", "%20") +
                "&code_challenge=${pkcePair.challenge}&code_challenge_method=S256",
            urlEvent.url,
        )
        assertTrue("key_label" !in urlEvent.url)
        assertEquals(
            "Complete sign-in in your browser. If the browser is on another machine, paste the final redirect URL here.",
            urlEvent.instructions,
        )

        // Progress announcing the callback URL precedes the auth URL (pi's order).
        val progress = interaction.events.filterIsInstance<AuthEvent.Progress>().first()
        assertEquals("Listening for OpenRouter OAuth callback on $callbackUrl", progress.message)
        assertTrue(interaction.events.indexOf(progress) < interaction.events.indexOf(urlEvent))

        // Manual fallback completes the login; only the key-exchange request happens.
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `manual code prompt mirrors pi's message and uses the callback URL as placeholder`() = runBlocking {
        val (auth, _) = flow()
        val interaction = RecordingInteraction()

        auth.login(interaction)

        val prompt = interaction.prompts.single() as AuthPrompt.ManualCode
        assertEquals(
            "Complete sign-in in your browser, or paste the authorization code / redirect URL here:",
            prompt.message,
        )
        val urlEvent = interaction.events.filterIsInstance<AuthEvent.AuthUrl>().single()
        assertEquals(callbackUrlFrom(urlEvent.url), prompt.placeholder)
    }

    @Test
    fun `server-driven login exchanges the code inside the handler and returns the credential`() = runBlocking {
        val http = FakeHttpClient()
        val auth = OpenRouterOAuthAuth(http, fixedPkce())
        val interaction = HangingInteraction()

        val login = async { auth.login(interaction) }
        val callbackUrl = callbackUrlFrom(interaction.authUrl.await().url)
        val (status, body) = httpGet("$callbackUrl?code=or-v1-xyz")

        assertEquals(200, status)
        assertTrue("Signed in to OpenRouter. You may now close this page." in body, body)
        val credential = login.await()
        assertEquals("sk-or-key", credential.access)
        assertEquals("", credential.refresh)
        assertEquals(OpenRouterOAuthAuth.NON_EXPIRING_EPOCH_MS, credential.expires)

        // The exchange ran inside the handler (manual prompt never answered).
        assertEquals(1, http.requests.size)
        assertEquals("or-v1-xyz", parseCode(http.requests.single()))
    }

    @Test
    fun `exchange failure inside the handler yields a 502 page and fails the login`() = runBlocking {
        val http = FakeHttpClient(respond = { jsonResponse(403, "{\"error_description\":\"code expired\"}") })
        val auth = OpenRouterOAuthAuth(http, fixedPkce())
        val interaction = HangingInteraction()

        supervisorScope {
            val login = async { auth.login(interaction) }
            val callbackUrl = callbackUrlFrom(interaction.authUrl.await().url)
            val (status, body) = httpGet("$callbackUrl?code=or-v1-bad")

            assertEquals(502, status)
            assertTrue("OpenRouter key exchange failed." in body, body)
            assertTrue("OpenRouter OAuth key exchange failed (HTTP 403): code expired" in body, body)
            val error = try {
                login.await()
                null
            } catch (error: IllegalStateException) {
                error
            }
            assertEquals("OpenRouter OAuth key exchange failed (HTTP 403): code expired", requireNotNull(error) { "login completed" }.message)
        }
    }

    @Test
    fun `error query param yields a 400 denied page and fails the login`() = runBlocking {
        val (auth, http) = flow()
        val interaction = HangingInteraction()

        supervisorScope {
            val login = async { auth.login(interaction) }
            val callbackUrl = callbackUrlFrom(interaction.authUrl.await().url)
            val (status, body) = httpGet("$callbackUrl?error=access_denied&error_description=user+said+no")

            assertEquals(400, status)
            assertTrue("OpenRouter authorization was denied." in body, body)
            assertTrue("user said no" in body, body)
            val error = try {
                login.await()
                null
            } catch (error: IllegalStateException) {
                error
            }
            assertEquals("OpenRouter authorization failed: user said no", requireNotNull(error) { "login completed" }.message)
        }
        // Denied before any claim: no token exchange request was made.
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `empty error param is not a denial and the callback proceeds`() = runBlocking {
        val http = FakeHttpClient()
        val auth = OpenRouterOAuthAuth(http, fixedPkce())
        val interaction = HangingInteraction()

        val login = async { auth.login(interaction) }
        val callbackUrl = callbackUrlFrom(interaction.authUrl.await().url)
        // pi's `if (oauthError)` is a JS truthy check: `?error=` (empty) is
        // ignored and the callback is treated like any other.
        val (status, body) = httpGet("$callbackUrl?error=&code=or-v1-xyz")

        assertEquals(200, status)
        assertTrue("Signed in to OpenRouter." in body, body)
        assertEquals("sk-or-key", login.await().access)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `concurrent second callback is rejected with 409`() = runBlocking {
        val (auth, http) = flow()
        val interaction = HangingInteraction()

        val login = async { auth.login(interaction) }
        val callbackUrl = callbackUrlFrom(interaction.authUrl.await().url)
        // Both requests arrive before the winning exchange can settle the
        // login and close the server (pi's `claimed` guard).
        val first = async { httpGet("$callbackUrl?code=or-v1-first") }
        val second = async { httpGet("$callbackUrl?code=or-v1-second") }

        val statuses = listOf(first.await().first, second.await().first).sorted()
        assertEquals(listOf(200, 409), statuses)
        assertEquals("sk-or-key", login.await().access)
        // Only the winning callback's exchange ran (either request may win).
        assertEquals(1, http.requests.size)
        assertTrue(parseCode(http.requests.single()) in setOf("or-v1-first", "or-v1-second"))
    }

    @Test
    fun `wrong path and missing code answer without claiming the callback`() = runBlocking {
        val (auth, http) = flow()
        val interaction = HangingInteraction()

        val login = async { auth.login(interaction) }
        val callbackUrl = callbackUrlFrom(interaction.authUrl.await().url)
        val port = callbackUrl.substringAfterLast(":").substringBefore("/").toInt()

        assertEquals(404, httpGet("http://127.0.0.1:$port/oauth/callback/not-the-path?code=x").first)
        assertEquals(400, httpGet("$callbackUrl?state=1").first)
        assertEquals(200, httpGet("$callbackUrl?code=or-v1-late").first)

        assertEquals("sk-or-key", login.await().access)
        // Only the valid callback exchanged.
        assertEquals(1, http.requests.size)
        assertEquals("or-v1-late", parseCode(http.requests.single()))
    }

    @Test
    fun `timeout with no callback fails the login with pi's message`() {
        val http = FakeHttpClient()
        val auth = OpenRouterOAuthAuth(http, fixedPkce(), loginTimeoutMs = 200)
        val interaction = HangingInteraction()

        val error = assertFailsWith<IllegalStateException> {
            runBlocking { auth.login(interaction) }
        }
        assertEquals("OpenRouter OAuth login timed out", error.message)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `manual-prompt answer wins when no callback arrives`() = runBlocking {
        val (auth, http) = flow()
        val credential = auth.login(RecordingInteraction("https://openrouter.ai/auth?code=or-v1-manual"))

        assertEquals("sk-or-key", credential.access)
        assertEquals("or-v1-manual", parseCode(http.requests.single()))
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
    fun `manual exchange posts pi's payload and emits pi's progress event`() = runBlocking {
        val (auth, http) = flow()
        val interaction = RecordingInteraction("https://openrouter.ai/auth?code=or-v1-xyz")

        auth.login(interaction)

        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals(OpenRouterOAuthAuth.TOKEN_URL, request.url)
        assertEquals(
            mapOf("accept" to "application/json", "content-type" to "application/json"),
            request.headers,
        )
        assertEquals(OpenRouterOAuthAuth.TOKEN_EXCHANGE_TIMEOUT_MS, request.timeoutMs)
        val sent = Json.parseToJsonElement(request.body.decodeToString()) as kotlinx.serialization.json.JsonObject
        val pkcePair = pkce()
        assertEquals("or-v1-xyz", sent["code"]!!.jsonPrimitiveContent())
        assertEquals(pkcePair.verifier, sent["code_verifier"]!!.jsonPrimitiveContent())
        assertEquals("S256", sent["code_challenge_method"]!!.jsonPrimitiveContent())

        assertTrue(interaction.events.filterIsInstance<AuthEvent.Progress>().any { it.message == "Exchanging authorization code for an API key..." })
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
