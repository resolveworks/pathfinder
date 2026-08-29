package works.resolve.pathfinder.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant
import works.resolve.pathfinder.ai.testing.FakeClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.OAuthCredential

/**
 * Ports the semantics of pi `packages/ai/src/auth/oauth/github-copilot.ts`:
 * device authorization through [DeviceCodePoller], GitHub→Copilot token
 * exchange, model catalog parsing with policy fallback, bounded 429
 * retry/retry-after handling, policy enablement, credential extras
 * (`enterpriseUrl`, `availableModelIds`), and the credential-specific
 * `toAuth` base URL. All HTTP is faked; time is virtual (`runTest` +
 * scheduler clock) so poll sleeps and retry backoffs are instant.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubCopilotOAuthAuthTest {

    /** Scheduler-backed clock: virtual time from `runTest`'s [TestScope]. */
    private val TestScope.virtualClock: Clock
        get() = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        }

    /** Clock reading a mutable local variable (script-driven time). */
    private fun mutableClockOf(ms: () -> Long): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms())
    }

    private class FakeHttpClient : OAuthHttpClient {
        /** FIFO script; each entry maps a request to its response. */
        val script = mutableListOf<suspend (OAuthHttpRequest) -> OAuthHttpResponse>()
        val requests = mutableListOf<OAuthHttpRequest>()
        var default: (suspend (OAuthHttpRequest) -> OAuthHttpResponse) = {
            error("unexpected request: ${it.method} ${it.url}")
        }

        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            val next = script.removeFirstOrNull() ?: return default(request)
            return next(request)
        }
    }

    private class RecordingInteraction(
        var textResponse: String = "",
    ) : AuthInteraction {
        val events = mutableListOf<AuthEvent>()
        val prompts = mutableListOf<AuthPrompt>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            return textResponse
        }

        override suspend fun notify(event: AuthEvent) {
            events += event
        }
    }

    private fun json(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) =
        OAuthHttpResponse(status, headers, body.toByteArray())

    private fun ok(body: String) = json(200, body)

    private fun copilotTokenJson(token: String = "tid=1;exp=99;proxy-ep=proxy.individual.githubcopilot.com;se=2") =
        """{"token":"$token","expires_at":1000,"refresh_in":1500,"endpoints":{}}"""

    private fun deviceCodeJson(interval: Int? = null) =
        """{"device_code":"DC","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device"${
            interval?.let { ""","interval":$it""" } ?: ""
        },"expires_in":900}"""

    private fun modelsJson(vararg entries: String) =
        """{"data":[${entries.joinToString(",")}]}"""

    private fun modelEntry(
        id: String,
        picker: Boolean = true,
        state: String? = "enabled",
        toolCalls: Boolean? = true,
    ): String = buildString {
        append("""{"id":"$id","model_picker_enabled":$picker,"capabilities":{"supports":{""")
        if (toolCalls != null) append("\"tool_calls\":$toolCalls")
        append("}},\"policy\":{")
        if (state != null) append("\"state\":\"$state\"")
        append("}}")
    }

    private fun auth(
        http: FakeHttpClient,
        known: Set<String> = setOf("gpt-4.1", "claude-sonnet-5", "grok-4.6"),
        clock: Clock = FakeClock(0L),
    ) = GitHubCopilotOAuthAuth(http = http, knownModelIds = known, clock = clock)

    private fun FakeHttpClient.happyPath(
        tokenBody: String = copilotTokenJson(),
        modelsBody: String = modelsJson(
            modelEntry("gpt-4.1"),
            modelEntry("claude-sonnet-5", state = "unconfigured"),
            modelEntry("grok-4.6", picker = false, state = "disabled"),
            modelEntry("unknown-model", picker = false, state = "unconfigured"),
        ),
        policyResponses: List<OAuthHttpResponse> = emptyList(),
    ) {
        // device code
        script += { ok(deviceCodeJson()) }
        // token poll: immediately complete
        script += { ok("""{"access_token":"gho_github","token_type":"bearer"}""") }
        // copilot token exchange
        script += { ok(tokenBody) }
        // account models
        script += { ok(modelsBody) }
        // policy enablement for unconfigured+known+picker models (claude-sonnet-5)
        policyResponses.forEach { response -> script += { response } }
    }

    // --- login: enterprise prompt & URL derivation ---

    @Test
    fun `login on github com uses public endpoints and exact headers`() = runTest {
        val http = FakeHttpClient()
        http.happyPath(policyResponses = listOf(ok("""{"policy":{"state":"enabled"}}""")))
        val interaction = RecordingInteraction(textResponse = "  ")

        val credential = auth(http, clock = virtualClock).login(interaction)

        // Enterprise prompt mirrors pi's message and placeholder.
        val prompt = interaction.prompts.single() as AuthPrompt.Text
        assertEquals("GitHub Enterprise URL/domain (blank for github.com)", prompt.message)
        assertEquals("company.ghe.com", prompt.placeholder)

        // Device code request: form body, pi client id/scope, GitHub Copilot UA.
        val deviceRequest = http.requests[0]
        assertEquals("POST", deviceRequest.method)
        assertEquals("https://github.com/login/device/code", deviceRequest.url)
        assertEquals(
            "client_id=Iv1.b507a08c87ecfe98&scope=read%3Auser",
            deviceRequest.body.toString(Charsets.UTF_8),
        )
        assertEquals(
            mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to "GitHubCopilotChat/0.35.0",
            ),
            deviceRequest.headers,
        )

        // Token poll: device grant.
        val pollRequest = http.requests[1]
        assertEquals("https://github.com/login/oauth/access_token", pollRequest.url)
        assertEquals(
            "client_id=Iv1.b507a08c87ecfe98&device_code=DC" +
                "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            pollRequest.body.toString(Charsets.UTF_8),
        )

        // Copilot token exchange: exact static Copilot headers (GET).
        val exchangeRequest = http.requests[2]
        assertEquals("https://api.github.com/copilot_internal/v2/token", exchangeRequest.url)
        assertEquals("GET", exchangeRequest.method)
        assertEquals("Bearer gho_github", exchangeRequest.headers["Authorization"])
        assertEquals(
            mapOf(
                "User-Agent" to "GitHubCopilotChat/0.35.0",
                "Editor-Version" to "vscode/1.107.0",
                "Editor-Plugin-Version" to "copilot-chat/0.35.0",
                "Copilot-Integration-Id" to "vscode-chat",
            ),
            GitHubCopilotOAuthAuth.COPILOT_HEADERS,
        )
        assertEquals("Bearer gho_github", exchangeRequest.headers["Authorization"])
        assertTrue(exchangeRequest.headers.entries.containsAll(GitHubCopilotOAuthAuth.COPILOT_HEADERS.entries))

        // Device code event mirrors the response.
        val deviceEvent = interaction.events.filterIsInstance<AuthEvent.DeviceCode>().single()
        assertEquals("ABCD-1234", deviceEvent.userCode)
        assertEquals("https://github.com/login/device", deviceEvent.verificationUri)
        assertEquals(900, deviceEvent.expiresInSeconds)

        // Expiry math: expires_at * 1000 - 5min skew.
        assertEquals(1000L * 1000 - 5 * 60 * 1000, credential.expires)
        assertEquals("gho_github", credential.refresh)

        // No enterprise domain stored.
        assertNull(credential.extras["enterpriseUrl"])
    }

    @Test
    fun `login with enterprise domain uses enterprise endpoints and stores the extra`() = runTest {
        val http = FakeHttpClient()
        // No proxy-ep in the token: enterprise fallback base URL applies.
        http.happyPath(tokenBody = """{"token":"tid=1;exp=99;se=2","expires_at":1000}""")
        val interaction = RecordingInteraction(textResponse = "https://Company.GHE.com/settings")

        val credential = auth(http).login(interaction)

        assertEquals("https://company.ghe.com/login/device/code", http.requests[0].url)
        assertEquals("https://api.company.ghe.com/copilot_internal/v2/token", http.requests[2].url)
        assertEquals(JsonPrimitive("company.ghe.com"), credential.extras["enterpriseUrl"])
    }

    @Test
    fun `invalid enterprise input fails login with pi's message`() = runTest {
        val http = FakeHttpClient()
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction(textResponse = "not a domain"))
        }
        assertEquals("Invalid GitHub Enterprise URL/domain", error.message)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `untrusted verification_uri is rejected`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok("""{"device_code":"DC","user_code":"ABCD-1234","verification_uri":"file:///bin/sh","expires_in":900}""") }
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction())
        }
        assertEquals("Untrusted verification_uri in device code response", error.message)
    }

    @Test
    fun `malformed device code fields fail with pi's message`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok("""{"device_code":"DC","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","interval":"fast","expires_in":900}""") }
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction())
        }
        assertEquals("Invalid device code response fields", error.message)
    }

    // --- polling: pending / slow_down / failure ---

    @Test
    fun `authorization pending then success completes the flow`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok(deviceCodeJson(interval = 1)) }
        http.script += { ok("""{"error":"authorization_pending"}""") }
        http.script += { ok("""{"error":"slow_down","interval":2}""") }
        http.script += { ok("""{"access_token":"gho_late"}""") }
        http.script += { ok(copilotTokenJson()) }
        http.script += { ok(modelsJson(modelEntry("gpt-4.1"))) }

        val credential = auth(http, clock = virtualClock).login(RecordingInteraction())

        // Three polls to the access-token endpoint, then the exchange.
        assertEquals(3, http.requests.count { it.url == "https://github.com/login/oauth/access_token" })
        assertEquals("gho_late", credential.refresh)
    }

    @Test
    fun `device flow error fails with pi's message`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok(deviceCodeJson(interval = 1)) }
        http.script += { ok("""{"error":"access_denied","error_description":"denied by user"}""") }
        val error = assertFailsWith<IllegalStateException> {
            auth(http, clock = virtualClock).login(RecordingInteraction())
        }
        assertEquals("Device flow failed: access_denied: denied by user", error.message)
    }

    @Test
    fun `invalid device token response fails the poll`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok(deviceCodeJson(interval = 1)) }
        http.script += { ok("""{"token_type":"bearer"}""") }
        val error = assertFailsWith<IllegalStateException> {
            auth(http, clock = virtualClock).login(RecordingInteraction())
        }
        assertEquals("Invalid device token response", error.message)
    }

    @Test
    fun `non-ok device code response fails with status and body`() = runTest {
        val http = FakeHttpClient()
        http.script += { json(404, """{"error":"not_found","error_description":"no such client"}""") }
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction())
        }
        // Structured error detail is preserved; the raw body never reaches the message.
        assertEquals("404: not_found: no such client", error.message)
    }

    // --- Copilot token exchange / expiry math ---

    @Test
    fun `malformed copilot token responses fail with pi's messages`() = runTest {
        for ((body, expected) in listOf(
            """{"token":123,"expires_at":1000}""" to "Invalid Copilot token response fields",
            """{"token":"t"}""" to "Invalid Copilot token response fields",
            """[]""" to "Invalid Copilot token response fields",
            """"scalar"""" to "Invalid Copilot token response",
        )) {
            val http = FakeHttpClient()
            http.script += { ok(deviceCodeJson()) }
            http.script += { ok("""{"access_token":"gho"}""") }
            http.script += { ok(body) }
            val error = assertFailsWith<IllegalStateException> {
                auth(http).login(RecordingInteraction())
            }
            assertEquals(expected, error.message, body)
        }
    }

    // --- model catalog parsing ---

    @Test
    fun `models request hits the token-derived base URL with exact headers`() = runTest {
        val http = FakeHttpClient()
        http.happyPath(modelsBody = modelsJson(modelEntry("gpt-4.1")))

        auth(http).login(RecordingInteraction())

        val modelsRequest = http.requests[3]
        assertEquals("https://api.individual.githubcopilot.com/models", modelsRequest.url)
        assertEquals("GET", modelsRequest.method)
        assertEquals(
            mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer " + "tid=1;exp=99;proxy-ep=proxy.individual.githubcopilot.com;se=2",
                "X-GitHub-Api-Version" to "2026-06-01",
            ) + GitHubCopilotOAuthAuth.COPILOT_HEADERS,
            modelsRequest.headers,
        )
    }

    @Test
    fun `catalog parse applies picker, policy, tool_calls, and known-model rules`() = runTest {
        val a = auth(FakeHttpClient())
        val raw = Json.parseToJsonElement(
            modelsJson(
                modelEntry("gpt-4.1"), // picker + enabled → available
                modelEntry("claude-sonnet-5", state = "unconfigured"), // unconfigured+known+picker → policy
                modelEntry("grok-4.6", picker = false, state = "enabled"), // not picker → not available
                modelEntry("gpt-4.1-b", state = "disabled"), // disabled → not available
                modelEntry("claude-sonnet-5-x", state = "unconfigured"), // unconfigured but unknown → not policy
                modelEntry("toolless", toolCalls = false), // explicit tool_calls false → dropped
                modelEntry("toolless-ok", toolCalls = null), // supports omitted → kept
            ),
        )
        val parsed = a.parseGitHubCopilotModelCatalog(raw, allowPolicyFallback = false)
        // pi: picker models are available unless explicitly disabled — missing
        // policy state counts as available.
        assertEquals(listOf("gpt-4.1", "claude-sonnet-5", "claude-sonnet-5-x", "toolless-ok"), parsed.availableModelIds)
        assertEquals(listOf("claude-sonnet-5"), parsed.policyModelIds)

        // Malformed shape (no data array) fails like pi.
        val error = assertFailsWith<IllegalStateException> {
            a.parseGitHubCopilotModelCatalog(Json.parseToJsonElement("""{"data":{}}"""), allowPolicyFallback = false)
        }
        assertEquals("Invalid Copilot models response", error.message)
    }

    @Test
    fun `individual fallback uses enabled policies when no picker flags are set`() = runTest {
        val a = auth(FakeHttpClient())
        val raw = Json.parseToJsonElement(
            modelsJson(
                modelEntry("gpt-4.1", picker = false, state = "enabled"),
                modelEntry("claude-sonnet-5", picker = false, state = "unconfigured"),
            ),
        )
        val fallback = a.parseGitHubCopilotModelCatalog(raw, allowPolicyFallback = true)
        assertEquals(listOf("gpt-4.1"), fallback.availableModelIds)
        // usePolicyFallback admits non-picker unconfigured models.
        assertEquals(listOf("claude-sonnet-5"), fallback.policyModelIds)

        val strict = a.parseGitHubCopilotModelCatalog(raw, allowPolicyFallback = false)
        assertTrue(strict.availableModelIds.isEmpty())
        assertTrue(strict.policyModelIds.isEmpty())
    }

    @Test
    fun `enterprise accounts never use the picker fallback`() = runTest {
        val http = FakeHttpClient()
        http.happyPath(
            tokenBody = """{"token":"tid=1;exp=99","expires_at":1000}""",
            modelsBody = modelsJson(modelEntry("gpt-4.1", picker = false, state = "enabled")),
        )
        val interaction = RecordingInteraction(textResponse = "company.ghe.com")

        val credential = auth(http).login(interaction)

        assertEquals("https://copilot-api.company.ghe.com/models", http.requests[3].url)
        // No picker models and no fallback: empty available list, no policy ids, no enablement.
        assertTrue(interaction.events.none { it is AuthEvent.Progress })
        assertEquals(0, (credential.extras["availableModelIds"] as JsonArray).size)
    }

    // --- policy enablement ---

    @Test
    fun `policy models are enabled with pi's exact request and merged deduped`() = runTest {
        val http = FakeHttpClient()
        http.happyPath(
            modelsBody = modelsJson(
                modelEntry("gpt-4.1"),
                modelEntry("claude-sonnet-5", state = "unconfigured"),
                modelEntry("grok-4.6", state = "unconfigured"),
            ),
            policyResponses = listOf(
                ok("""{"policy":{"state":"enabled"}}"""),
                json(500, "boom"),
            ),
        )
        val interaction = RecordingInteraction()

        val credential = auth(http).login(interaction)

        assertEquals(AuthEvent.Progress("Enabling models..."), interaction.events.filterIsInstance<AuthEvent.Progress>().single())

        for ((index, modelId) in listOf("claude-sonnet-5", "grok-4.6").withIndex()) {
            val policyRequest = http.requests[4 + index]
            assertEquals(
                "https://api.individual.githubcopilot.com/models/$modelId/policy",
                policyRequest.url,
            )
            assertEquals("POST", policyRequest.method)
            assertEquals("""{"state":"enabled"}""", policyRequest.body.toString(Charsets.UTF_8))
            assertEquals("chat-policy", policyRequest.headers["openai-intent"])
            assertEquals("chat-policy", policyRequest.headers["x-interaction-type"])
            assertTrue(policyRequest.headers.entries.containsAll(GitHubCopilotOAuthAuth.COPILOT_HEADERS.entries))
        }
        // Picker-enabled unconfigured models are available up front; the
        // failed enablement (HTTP 500) just adds nothing.
        assertEquals(
            listOf("gpt-4.1", "claude-sonnet-5", "grok-4.6"),
            (credential.extras["availableModelIds"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `exhausted 429 on policy enablement stops the batch without failing login`() = runTest {
        val http = FakeHttpClient()
        http.happyPath(
            modelsBody = modelsJson(
                modelEntry("claude-sonnet-5", state = "unconfigured"),
                modelEntry("grok-4.6", state = "unconfigured"),
            ),
        )
        // Three 429s exhaust maxRetries=2 (retry-after 0 keeps virtual time still);
        // pi treats this as best-effort: the batch stops, login still succeeds.
        repeat(3) { http.script += { json(429, """{"error":"rate_limited"}""", mapOf("retry-after" to listOf("0"))) } }

        val credential = auth(http, clock = virtualClock).login(RecordingInteraction())

        // The second model was never attempted (3 attempts = 1 + 2 retries).
        assertEquals(7, http.requests.size)
        // Only the picker-available ids survive; neither enablement succeeded.
        assertEquals(
            listOf("claude-sonnet-5", "grok-4.6"),
            (credential.extras["availableModelIds"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    // --- 429 retry / retry-after on the models fetch ---

    @Test
    fun `models fetch retries 429 honoring numeric retry-after and bounded retries`() = runTest {
        val http = FakeHttpClient()
        http.happyPath()
        http.script.removeAt(3) // replace models response
        http.script += { json(429, "x", mapOf("retry-after" to listOf("0"))) }
        http.script += { json(429, "x", mapOf("retry-after" to listOf("0"))) }
        http.script += { ok(modelsJson(modelEntry("gpt-4.1"))) }

        auth(http, clock = virtualClock).login(RecordingInteraction())

        // Two 429 attempts retried (maxRetries=2), third succeeded.
        assertEquals(3, http.requests.count { it.url == "https://api.individual.githubcopilot.com/models" })
    }

    @Test
    fun `retry-after beyond the elapsed budget returns the 429 response`() = runTest {
        val http = FakeHttpClient()
        http.happyPath()
        http.script.removeAt(3)
        // 10 minutes of retry-after cannot fit the 5s budget → the 429 is returned.
        http.script += { json(429, "x", mapOf("retry-after" to listOf("600"))) }

        val error = assertFailsWith<IllegalStateException> {
            auth(http, clock = virtualClock).login(RecordingInteraction())
        }
        assertTrue(error.message!!.startsWith("429:"))
        // Only the initial attempt ran.
        assertEquals(4, http.requests.size)
    }

    @Test
    fun `unparseable retry-after returns the 429 response without retrying`() = runTest {
        val http = FakeHttpClient()
        http.happyPath()
        http.script.removeAt(3)
        http.script += { json(429, "x", mapOf("retry-after" to listOf("soon"))) }

        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction())
        }
        assertTrue(error.message!!.startsWith("429:"))
        assertEquals(4, http.requests.size)
    }

    @Test
    fun `http-date retry-after is parsed`() = runTest {
        val http = FakeHttpClient()
        http.happyPath()
        http.script.removeAt(3)
        // A date before the (fixed, real-epoch) virtual clock clamps to an
        // immediate retry.
        http.script += { json(429, "x", mapOf("retry-after" to listOf("Wed, 21 Oct 2015 07:28:00 GMT"))) }
        http.script += { ok(modelsJson(modelEntry("gpt-4.1"))) }

        auth(http, clock = FakeClock(1_700_000_000_000L)).login(RecordingInteraction())

        assertEquals(5, http.requests.size)

        // Direct vectors for pi's Date.parse equivalent.
        val a = auth(FakeHttpClient())
        assertEquals(1445412480000L, a.parseHttpDateMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(a.parseHttpDateMs("soon"))
    }

    // --- refresh / rotation semantics ---

    @Test
    fun `refresh rotates the access token, keeps the refresh token, and refreshes model ids`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok(copilotTokenJson(token = "tid=2;exp=99;proxy-ep=proxy.business.githubcopilot.com")) }
        http.script += { ok(modelsJson(modelEntry("gpt-4.1"), modelEntry("claude-sonnet-5"))) }
        val stored = OAuthCredential(
            access = "old-access",
            refresh = "gho_keep",
            expires = 1,
            extras = mapOf(
                "enterpriseUrl" to JsonPrimitive("company.ghe.com"),
                "availableModelIds" to JsonArray(listOf(JsonPrimitive("stale"))),
            ),
        )

        val refreshed = auth(http).refresh(stored)

        assertEquals("tid=2;exp=99;proxy-ep=proxy.business.githubcopilot.com", refreshed.access)
        // Rotation keeps the GitHub refresh token verbatim (pi `refresh: refreshToken`).
        assertEquals("gho_keep", refreshed.refresh)
        assertEquals("company.ghe.com", refreshed.extras["enterpriseUrl"]?.let { (it as JsonPrimitive).content })
        assertEquals(
            listOf("gpt-4.1", "claude-sonnet-5"),
            (refreshed.extras["availableModelIds"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        // The enterprise exchange goes through the enterprise copilot token URL.
        assertEquals("https://api.company.ghe.com/copilot_internal/v2/token", http.requests[0].url)
    }

    @Test
    fun `refresh without 429 retries fails immediately on the first 429`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok(copilotTokenJson()) }
        http.script += { json(429, "x") }

        val error = assertFailsWith<IllegalStateException> {
            auth(http).refresh(OAuthCredential("a", "gho", 1))
        }
        assertTrue(error.message!!.startsWith("429:"))
        assertEquals(2, http.requests.size)
    }

    // --- toAuth: credential-specific base URL ---

    @Test
    fun `toAuth derives the proxy endpoint base URL per credential`() = runTest {
        val a = auth(FakeHttpClient())

        // proxy-ep → api host.
        val individual = a.toAuth(
            OAuthCredential(
                access = "tid=1;exp=9;proxy-ep=proxy.individual.githubcopilot.com;x=1",
                refresh = "gho",
                expires = Long.MAX_VALUE,
            ),
        )
        assertEquals("tid=1;exp=9;proxy-ep=proxy.individual.githubcopilot.com;x=1", individual.apiKey)
        assertEquals("https://api.individual.githubcopilot.com", individual.baseUrl)

        // Business proxy host.
        val business = a.toAuth(
            OAuthCredential("tid=1;proxy-ep=proxy.business.githubcopilot.com", "gho", Long.MAX_VALUE),
        )
        assertEquals("https://api.business.githubcopilot.com", business.baseUrl)

        // Enterprise fallback when the token carries no proxy-ep.
        val enterprise = a.toAuth(
            OAuthCredential(
                access = "tid=1;exp=9",
                refresh = "gho",
                expires = Long.MAX_VALUE,
                extras = mapOf("enterpriseUrl" to JsonPrimitive("https://GHE.Com")),
            ),
        )
        assertEquals("https://copilot-api.ghe.com", enterprise.baseUrl)

        // Default: individual.
        val fallback = a.toAuth(OAuthCredential("tid=1", "gho", Long.MAX_VALUE))
        assertEquals("https://api.individual.githubcopilot.com", fallback.baseUrl)
    }

    @Test
    fun `copilotEnterpriseDomain normalizes the stored extra`() = runTest {
        val a = auth(FakeHttpClient())
        assertEquals("ghe.com", a.copilotEnterpriseDomain(OAuthCredential("a", "r", 1, mapOf("enterpriseUrl" to JsonPrimitive("https://ghe.com/x")))))
        assertNull(a.copilotEnterpriseDomain(OAuthCredential("a", "r", 1)))
        assertNull(a.copilotEnterpriseDomain(OAuthCredential("a", "r", 1, mapOf("enterpriseUrl" to JsonPrimitive("")))))
    }

    // --- cancellation ---

    @Test
    fun `cancellation during the token exchange propagates`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok(deviceCodeJson()) }
        http.script += { ok("""{"access_token":"gho"}""") }
        http.script += { throw CancellationException("Login cancelled") }

        assertFailsWith<CancellationException> {
            auth(http).login(RecordingInteraction())
        }
    }

    // --- misc mirrors ---

    @Test
    fun `client id decodes to pi's GitHub Copilot client id`() {
        assertEquals("Iv1.b507a08c87ecfe98", GitHubCopilotOAuthAuth.CLIENT_ID)
    }

    @Test
    fun `base URL derivation mirrors pi's proxy-ep parsing`() {
        val a = auth(FakeHttpClient())
        assertNull(a.getBaseUrlFromToken("tid=1;exp=9"))
        assertEquals(
            "https://api.enterprise-proxy.example.com",
            a.getBaseUrlFromToken("tid=1;proxy-ep=proxy.enterprise-proxy.example.com;exp=9"),
        )
    }

    @Test
    fun `normalizeDomain accepts domains and urls and rejects garbage`() {
        val a = auth(FakeHttpClient())
        assertEquals("company.ghe.com", a.normalizeDomain("company.ghe.com"))
        assertEquals("company.ghe.com", a.normalizeDomain("  https://Company.GHE.com/org  "))
        assertNull(a.normalizeDomain(""))
        assertNull(a.normalizeDomain("   "))
        assertNull(a.normalizeDomain("://"))
    }

    @Test
    fun `login method surface matches the OAuth contract`() {
        val a = auth(FakeHttpClient())
        assertEquals("GitHub Copilot", a.name)
        assertTrue(a.isSubscription)
        assertNull(a.loginLabel)
    }

    // --- verification URI trust + normalization ---

    @Test
    fun `verification uri normalization mirrors WHATWG href form`() {
        val a = auth(FakeHttpClient())
        assertEquals(
            "https://github.com/login/device",
            a.validateVerificationUri("HTTPS://GitHub.COM:443/login/device"),
        )
        assertEquals("https://github.com/", a.validateVerificationUri("https://github.com"))
        assertEquals("https://github.com/", a.validateVerificationUri("https://GITHUB.com"))
        assertEquals(
            "http://github.com/login?x=1#frag",
            a.validateVerificationUri("http://github.com:80/login?x=1#frag"),
        )
        // Non-default ports are kept.
        assertEquals(
            "https://github.com:8443/login",
            a.validateVerificationUri("https://github.com:8443/login"),
        )
    }

    @Test
    fun `authority-less and malformed verification uris are rejected`() {
        val a = auth(FakeHttpClient())
        for (bad in listOf("http:foo", "https:", "//github.com", "file:///bin/sh", "github.com/login", "not a url")) {
            val error = assertFailsWith<IllegalStateException>(bad) { a.validateVerificationUri(bad) }
            assertEquals("Untrusted verification_uri in device code response", error.message)
        }
        // Control characters make the URI unparseable → untrusted.
        val error = assertFailsWith<IllegalStateException> {
            a.validateVerificationUri("https://github.com/lo\u0000gin")
        }
        assertEquals("Untrusted verification_uri in device code response", error.message)
    }

    @Test
    fun `login rejects authority-less verification uri from the server`() = runTest {
        val http = FakeHttpClient()
        http.script += { ok("""{"device_code":"DC","user_code":"ABCD-1234","verification_uri":"http:foo","expires_in":900}""") }
        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction())
        }
        assertEquals("Untrusted verification_uri in device code response", error.message)
    }

    // --- Retry-After numeric parsing (JS parseFloat semantics) ---

    @Test
    fun `retry-after numeric parsing mirrors js parseFloat`() {
        val a = auth(FakeHttpClient())
        assertEquals(1.0, a.parseFloatPrefix("1x"))
        assertEquals(-1.0, a.parseFloatPrefix("-1x"))
        assertEquals(2.5, a.parseFloatPrefix(" 2.5 sec"))
        assertEquals(100.0, a.parseFloatPrefix("1e2foo"))
        assertEquals(0.5, a.parseFloatPrefix(".5"))
        assertEquals(3.0, a.parseFloatPrefix("+3"))
        assertNull(a.parseFloatPrefix("soon"))
        assertNull(a.parseFloatPrefix(""))
        assertNull(a.parseFloatPrefix("Infinity"))
        assertNull(a.parseFloatPrefix("e5"))
    }

    // --- bounded retry budget caps attempt timeouts ---

    @Test
    fun `attempt timeouts are capped by the remaining elapsed budget`() = runTest {
        val http = FakeHttpClient()
        // A manual clock the fake client advances on each exchange.
        var clock = 0L
        http.script += { ok(deviceCodeJson()) }
        http.script += { ok("""{"access_token":"gho_g"}""") }
        http.script += { ok(copilotTokenJson()) }
        // Login's models fetch (maxRetries=2, budget 5000ms): the first
        // attempt consumes 3000ms of the budget before returning a 429.
        http.script += { request ->
            clock += 3000
            json(429, """{"error":"rate_limited"}""", mapOf("retry-after" to listOf("0")))
        }
        http.script += { ok(modelsJson(modelEntry("gpt-4.1"))) }
        val flow = GitHubCopilotOAuthAuth(http = http, knownModelIds = setOf("gpt-4.1"), clock = mutableClockOf { clock })

        val credential = flow.login(RecordingInteraction())

        // The retried attempt received only the remaining budget (2000ms),
        // not a fresh 5s — total elapsed cannot exceed the budget.
        assertEquals(5000, http.requests[3].timeoutMs)
        assertEquals(2000, http.requests[4].timeoutMs)
        assertEquals(
            listOf("gpt-4.1"),
            (credential.extras["availableModelIds"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `retry sleep that cannot fit the budget returns the last 429`() = runTest {
        val http = FakeHttpClient()
        var clock = 0L
        http.script += { ok(deviceCodeJson()) }
        http.script += { ok("""{"access_token":"gho_g"}""") }
        http.script += { ok(copilotTokenJson()) }
        // Login's models fetch: 4500ms of the 5000ms budget already consumed,
        // and the 429 demands a 1000ms sleep that cannot fit the remaining 500ms.
        http.script += { request ->
            clock += 4500
            json(429, """{"error":"rate_limited"}""", mapOf("retry-after" to listOf("1")))
        }
        val flow = GitHubCopilotOAuthAuth(http = http, knownModelIds = setOf("gpt-4.1"), clock = mutableClockOf { clock })

        val error = assertFailsWith<IllegalStateException> {
            flow.login(RecordingInteraction())
        }
        // The last 429 is returned (budget exhausted before another attempt).
        assertEquals("429: rate_limited", error.message)
        assertEquals(4, http.requests.size)
    }

    // --- error-message secret safety ---

    @Test
    fun `raw response bodies never reach error messages`() {
        val a = auth(FakeHttpClient())
        val token = "gho_secret_access_ABC123"
        // Unparseable body carrying a token.
        val unparseable = a.statusError(500, "token=$token".toByteArray())
        assertEquals("500: <redacted response body>", unparseable.message)
        assertTrue(token !in unparseable.message!!)
        // Non-error JSON object carrying a token.
        val nonError = a.statusError(500, """{"token":"$token"}""".toByteArray())
        assertEquals("500: <redacted response body>", nonError.message)
        // Structured error fields are preserved and safe.
        val structured = a.statusError(429, """{"error":"rate_limited","error_description":"too many"}""".toByteArray())
        assertEquals("429: rate_limited: too many", structured.message)
    }

    @Test
    fun `live flow errors never echo token values`() = runTest {
        val http = FakeHttpClient()
        val token = "tid=1;exp=9;proxy-ep=proxy.individual.githubcopilot.com"
        http.script += { ok(deviceCodeJson()) }
        http.script += { ok("""{"access_token":"gho_x"}""") }
        http.script += { json(403, """{"message":"bad token $token","token":"$token"}""") }

        val error = assertFailsWith<IllegalStateException> {
            auth(http).login(RecordingInteraction())
        }
        assertTrue(token !in error.message!!)
        assertTrue("gho_x" !in error.message!!)
        // Request/response toStrings never carry bodies or bearer values either.
        for (request in http.requests) {
            assertTrue(token !in request.toString())
            assertTrue("gho_x" !in request.toString())
        }
        val response = OAuthHttpResponse(500, emptyMap(), """{"x":"$token"}""".toByteArray())
        assertTrue(token !in response.toString())
    }

    // --- policy enablement resilience ---

    @Test
    fun `non-IOException transport failure continues the batch`() = runTest {
        val http = FakeHttpClient()
        // Force the individual policy fallback: no picker-enabled models, so
        // availableModelIds starts empty and only a successful policy
        // enablement can add an id.
        http.happyPath(
            modelsBody = modelsJson(
                modelEntry("claude-sonnet-5", picker = false, state = "unconfigured"),
                modelEntry("grok-4.6", picker = false, state = "unconfigured"),
            ),
        )
        // First enablement dies with a non-IOException transport failure...
        http.script += { throw IllegalStateException("tls blew up") }
        // ...the batch continues and the second model enables successfully.
        http.script += { ok("""{"policy":{"state":"enabled"}}""") }

        val credential = auth(http).login(RecordingInteraction())

        // Both policy endpoints were actually requested, in order.
        val policyUrls = http.requests.drop(4).map { it.url }
        assertEquals(
            listOf(
                "https://api.individual.githubcopilot.com/models/claude-sonnet-5/policy",
                "https://api.individual.githubcopilot.com/models/grok-4.6/policy",
            ),
            policyUrls,
        )
        // Only the second (successfully enabled) model was added.
        assertEquals(
            listOf("grok-4.6"),
            (credential.extras["availableModelIds"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }
}
