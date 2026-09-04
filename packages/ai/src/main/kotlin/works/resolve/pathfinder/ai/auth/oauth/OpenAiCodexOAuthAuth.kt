package works.resolve.pathfinder.ai.auth.oauth

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.oauth.PkceGenerator
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.strictDouble
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull
import works.resolve.pathfinder.ai.utils.truthyString

/**
 * Divergences from pi:
 * - **Loopback callback race.** The browser flow races the loopback callback
 *   server against a manual [AuthPrompt.ManualCode]: a server result with a
 *   code wins outright, manual input goes through [parseAuthorizationInput],
 *   the state check, and the exchange, and when the server wins the
 *   manual-prompt coroutine is cancelled — pi abandons the pending promise,
 *   while here that cancellation is what clears the UI sheet
 *   (`UiAuthInteraction.prompt` clears pending state in `finally`). A bind
 *   failure degrades to the manual-only flow. If Android kills the app
 *   process while the browser is foregrounded, the login dies and must be
 *   retried.
 * - **originator default.** The authorize request presents as whatever
 *   originator it names, so pi's `"pi"` default would misattribute
 *   Pathfinder's traffic; this port defaults to [ORIGINATOR] (`"pathfinder"`).
 *   Deliberate divergence; the parameter is otherwise kept as in pi.
 * - **HTTP boundary.** Pi `fetch`es with an `AbortSignal`; all HTTP goes
 *   through the injected [OAuthHttpClient] with a bounded request timeout,
 *   and cancellation travels as coroutine cancellation.
 * - **Redacted error bodies.** Pi interpolates raw response bodies into
 *   several error messages; a server response can echo back the very secrets
 *   the request carried (authorization code, code verifier, device auth id,
 *   tokens), so this port never interpolates a raw body: it keeps only the
 *   structured `error` / `error.code` / `error.message` /
 *   `error_description` strings (scrubbed of any in-flight secret value) as
 *   `error=<detail>`, writes `<redacted>` when none parse, and reports
 *   invalid JSON shapes as missing field names only. Everything else in the
 *   messages (statuses, wording, ordering) is verbatim.
 * - **No status line fallback.** `OAuthHttpResponse` has no `statusText`, so
 *   failed token responses append nothing when the body is empty.
 * - **JWT base64 tolerance.** Pi decodes with `atob` (standard base64); this
 *   port also accepts unpadded base64url, since real ChatGPT access tokens
 *   are RFC 7515 base64url JWTs — strictly more permissive, never less.
 * - **Test seams.** `Date.now()` reads through [clock], state generation
 *   through [createState], and PKCE through [pkce] — all for deterministic
 *   tests; production uses defaults.
 *
 * Nothing secret is ever logged or echoed in exception messages, and the
 * internal result shapes redact their secret fields in `toString`.
 */
class OpenAiCodexOAuthAuth(
    private val http: OAuthHttpClient,
    private val clock: Clock = Clock.System,
    private val createState: () -> String = { defaultCreateState() },
    private val pkce: PkceGenerator = PkceGenerator(),
    /** Injectable so tests never race the fixed port. */
    private val callbackPort: Int = CALLBACK_PORT,
    /** Android foreground gate for the loopback wait; `null` = pi parity. */
    private val gate: OAuthForegroundGate? = null
) : OAuthAuth {

    override val name: String = "OpenAI (ChatGPT Plus/Pro)"

    override val isSubscription: Boolean = true

    /**
     * Port the loopback callback server actually bound, or null after a bind
     * failure; test seam for driving the callback on an ephemeral port.
     */
    internal var lastCallbackPort: Int? = null
        private set

    override val loginLabel: String? = null

    // --- login ---

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val method = interaction.prompt(
            AuthPrompt.Select(
                message = "Select OpenAI Codex login method:",
                options = listOf(
                    AuthPrompt.Select.Option(BROWSER_LOGIN_METHOD, "Browser login (default)"),
                    AuthPrompt.Select.Option(
                        DEVICE_CODE_LOGIN_METHOD,
                        "Device code login (headless)"
                    )
                )
            )
        )

        return when (method) {
            DEVICE_CODE_LOGIN_METHOD -> loginOpenAICodexDeviceCode(interaction)
            BROWSER_LOGIN_METHOD -> loginOpenAICodex(interaction)
            else -> throw IllegalStateException("Unknown OpenAI Codex login method: $method")
        }
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        credentialsFromToken(refreshAccessToken(credential.refresh))

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)

    // --- browser login ---

    /**
     * The authorize-URL parameter set encodes like `URLSearchParams`:
     * insertion order preserved, spaces as `+`.
     */
    internal fun createAuthorizationFlow(originator: String = ORIGINATOR): AuthorizationFlow {
        val pair = pkce.generate()
        val state = createState()
        val url = AUTH_BASE_URL + AUTHORIZE_PATH + "?" +
            XaiOAuthAuth.formUrlEncode(
                linkedMapOf(
                    "response_type" to "code",
                    "client_id" to CLIENT_ID,
                    "redirect_uri" to REDIRECT_URI,
                    "scope" to SCOPE,
                    "code_challenge" to pair.challenge,
                    "code_challenge_method" to "S256",
                    "state" to state,
                    "id_token_add_organizations" to "true",
                    "codex_cli_simplified_flow" to "true",
                    "originator" to originator
                )
            ).toString(Charsets.UTF_8)
        return AuthorizationFlow(verifier = pair.verifier, state = state, url = url)
    }

    internal data class AuthorizationFlow(
        val verifier: String,
        val state: String,
        val url: String
    ) {
        override fun toString(): String =
            "AuthorizationFlow(verifier=<redacted>, state=$state, url=$url)"
    }

    /** Races the loopback callback against a manual [AuthPrompt.ManualCode] (see class KDoc). */
    private suspend fun loginOpenAICodex(interaction: AuthInteraction): OAuthCredential {
        val flow = createAuthorizationFlow()
        val handle = startLocalOAuthServer(flow.state)
        try {
            interaction.notify(
                AuthEvent.AuthUrl(
                    url = flow.url,
                    instructions = "A browser window should open. Complete login to finish."
                )
            )
            return coroutineScope {
                var manualCode: String? = null
                var manualError: Throwable? = null
                val manualJob = launch {
                    try {
                        manualCode = interaction.prompt(
                            AuthPrompt.ManualCode(
                                message = "Complete login in your browser, " +
                                    "or paste the authorization code / redirect URL here:",
                                placeholder = REDIRECT_URI
                            )
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        manualError = error
                    }
                    handle?.cancelWait()
                }
                try {
                    // A bind failure makes this null, leaving the manual
                    // prompt as the only path.
                    val result = handle?.waitForResult()
                    manualError?.let { throw it }
                    var code = result?.code
                        ?: manualCode?.let { manual ->
                            val parsed = parseAuthorizationInput(manual)
                            // An empty state counts as absent; any non-empty
                            // state must match (JS truthiness).
                            if (!parsed.state.isNullOrEmpty() && parsed.state != flow.state) {
                                throw IllegalStateException("State mismatch")
                            }
                            // An empty code counts as absent.
                            parsed.code?.takeIf { it.isNotEmpty() }
                        }
                    if (code == null) {
                        // The prompt may not have answered yet; wait before
                        // giving up.
                        manualJob.join()
                        manualError?.let { throw it }
                        code = manualCode?.let { manual ->
                            val parsed = parseAuthorizationInput(manual)
                            if (!parsed.state.isNullOrEmpty() && parsed.state != flow.state) {
                                throw IllegalStateException("State mismatch")
                            }
                            parsed.code?.takeIf { it.isNotEmpty() }
                        }
                    }
                    code ?: throw IllegalStateException("Missing authorization code")
                    exchangeAuthorizationCodeForCredentials(code, flow.verifier, REDIRECT_URI)
                } finally {
                    // When the server wins, cancelling the manual prompt is
                    // what clears the UI sheet.
                    manualJob.cancel()
                }
            }
        } finally {
            handle?.close()
        }
    }

    /** Bind failure returns null, degrading to the manual-only flow. */
    private suspend fun startLocalOAuthServer(
        state: String
    ): LoopbackCallbackHandle<CallbackCode>? {
        val server = LoopbackOAuthServer(
            port = callbackPort,
            host = CALLBACK_HOST,
            gate = gate,
            handler = { request, settle ->
                if (request.path != CALLBACK_PATH) {
                    LoopbackCallbackResponse(404, oauthErrorHtml("Callback route not found."))
                } else if (request.query["state"] != state) {
                    LoopbackCallbackResponse(400, oauthErrorHtml("State mismatch."))
                } else {
                    val code = request.query["code"]
                    if (code.isNullOrEmpty()) {
                        LoopbackCallbackResponse(400, oauthErrorHtml("Missing authorization code."))
                    } else {
                        settle(CallbackCode(code))
                        LoopbackCallbackResponse(
                            200,
                            oauthSuccessHtml(
                                "OpenAI authentication completed. You can close this window."
                            )
                        )
                    }
                }
            }
        )
        val handle = server.start()
        lastCallbackPort = handle?.port
        return handle
    }

    internal data class CallbackCode(val code: String) {
        override fun toString(): String = "CallbackCode(code=<redacted>)"
    }

    /**
     * Accepts a pasted redirect URL, a `code#state` fragment, a `code=`-style
     * query string, or a bare code.
     *
     * The `#` branch mirrors JS `value.split("#", 2)`, keeping only the first
     * two segments: `code#state#ignored` yields `state`, not `state#ignored`
     * (Kotlin's `split(limit = 2)` would keep the whole remainder).
     */
    internal fun parseAuthorizationInput(input: String): AuthorizationInput {
        val value = input.trim()
        if (value.isEmpty()) return AuthorizationInput(code = null, state = null)

        val url = try {
            java.net.URI(value)
        } catch (_: Exception) {
            null
        }
        if (url?.scheme != null) {
            return AuthorizationInput(
                code = queryParam(url.rawQuery, "code"),
                state = queryParam(url.rawQuery, "state")
            )
        }

        if (value.contains("#")) {
            val parts = value.split("#")
            return AuthorizationInput(code = parts[0], state = parts.getOrNull(1))
        }

        if (value.contains("code=")) {
            return AuthorizationInput(
                code = queryParam(value, "code"),
                state = queryParam(value, "state")
            )
        }

        return AuthorizationInput(code = value, state = null)
    }

    internal data class AuthorizationInput(val code: String?, val state: String?)

    /** First matching form-encoded query parameter (`URLSearchParams.get` parity). */
    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery == null) return null
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            val rawName = if (separator >= 0) pair.substring(0, separator) else pair
            if (formUrlDecode(rawName) == name) {
                return if (separator >= 0) formUrlDecode(pair.substring(separator + 1)) else ""
            }
        }
        return null
    }

    private fun formUrlDecode(raw: String): String =
        java.net.URLDecoder.decode(raw, Charsets.UTF_8.name())

    // --- device-code login ---

    private suspend fun loginOpenAICodexDeviceCode(interaction: AuthInteraction): OAuthCredential {
        val device = startOpenAICodexDeviceAuth()
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = DEVICE_VERIFICATION_URI,
                intervalSeconds = device.intervalSeconds.toInt(),
                expiresInSeconds = DEVICE_CODE_TIMEOUT_SECONDS.toInt()
            )
        )
        val code = pollOpenAICodexDeviceAuth(device)
        return exchangeAuthorizationCodeForCredentials(
            code.authorizationCode,
            code.codeVerifier,
            DEVICE_REDIRECT_URI
        )
    }

    internal data class DeviceAuthInfo(
        val deviceAuthId: String,
        val userCode: String,
        val intervalSeconds: Double
    ) {
        override fun toString(): String =
            "DeviceAuthInfo(deviceAuthId=<redacted>, userCode=$userCode, intervalSeconds=$intervalSeconds)"
    }

    internal suspend fun startOpenAICodexDeviceAuth(): DeviceAuthInfo {
        val response = postJson(
            DEVICE_USER_CODE_URL,
            buildJsonObject { put("client_id", CLIENT_ID) }.toString()
        )
        val text = response.body.toString(Charsets.UTF_8)
        if (response.status !in 200..299) {
            if (response.status == 404) {
                throw IllegalStateException(
                    "OpenAI Codex device code login is not enabled for this server. " +
                        "Use browser login or verify the server URL."
                )
            }
            throw IllegalStateException(
                withErrorBody(
                    "OpenAI Codex device code request failed with status ${response.status}",
                    text
                )
            )
        }

        val json = parseJson(text)?.let { it as? JsonObject }
        val intervalSeconds = coerceIntervalSeconds(json)
        val deviceAuthId = json?.truthyString("device_auth_id")
        val userCode = json?.truthyString("user_code")
        val intervalValid = intervalSeconds.isFinite() && intervalSeconds >= 0
        if (deviceAuthId == null || userCode == null || !intervalValid) {
            val missing = listOfNotNull(
                if (deviceAuthId == null) "device_auth_id" else null,
                if (userCode == null) "user_code" else null,
                if (!intervalValid) "interval" else null
            )
            throw IllegalStateException(
                "Invalid OpenAI Codex device code response: missing fields: ${missing.joinToString()}"
            )
        }
        return DeviceAuthInfo(deviceAuthId, userCode, intervalSeconds)
    }

    /**
     * `typeof json.interval === "string" ? Number(json.interval.trim()) : json.interval`
     * with the `typeof number` gate: JSON numbers pass through, anything else
     * (booleans, objects, absent) is NaN so the validity check rejects it.
     */
    private fun coerceIntervalSeconds(json: JsonObject?): Double =
        when (val interval = json?.get("interval")) {
            is JsonPrimitive ->
                if (interval.isString) {
                    jsNumber(interval.content)
                } else {
                    interval.content.toDoubleOrNull() ?: Double.NaN
                }

            else -> Double.NaN
        }

    /**
     * JS `Number(string)` for the string-interval path: trims JS whitespace
     * (including `\u00A0`/`\uFEFF`), maps the empty/whitespace-only result to
     * 0, accepts signed decimal/exponent notation plus `0x`/`0o`/`0b` radix
     * literals and `Infinity`/`NaN`, and returns NaN for everything else
     * (including Java-only forms like a trailing `f`/`d` suffix). Narrow
     * divergence: radix literals beyond `Long` range return NaN instead of
     * JS's rounded double.
     */
    internal fun jsNumber(raw: String): Double {
        val s = raw.trim { it.isWhitespace() || it == '\u00A0' || it == '\uFEFF' }
        if (s.isEmpty()) return 0.0
        val negative = s.startsWith("-")
        val unsigned = s.removePrefix("+").removePrefix("-")
        when (unsigned) {
            "Infinity" ->
                return if (negative) {
                    Double.NEGATIVE_INFINITY
                } else {
                    Double.POSITIVE_INFINITY
                }

            "NaN" -> return Double.NaN
        }
        radixLiteral.matchEntire(s)?.let { match ->
            val digits = match.groupValues[2].substring(2)
            val radix = when (match.groupValues[2][1].lowercaseChar()) {
                'x' -> 16
                'o' -> 8
                else -> 2
            }
            val value = digits.toLongOrNull(radix) ?: return Double.NaN
            return (if (negative) -value else value).toDouble()
        }
        return if (decimalLiteral.matches(s)) s.toDouble() else Double.NaN
    }

    private val radixLiteral = Regex("^([+-]?)(0[xX][0-9a-fA-F]+|0[oO][0-7]+|0[bB][01]+)$")

    private val decimalLiteral = Regex("^([+-]?)((\\d+(\\.\\d*)?)|\\.\\d+)([eE][+-]?\\d+)?$")

    internal data class DeviceTokenSuccess(
        val authorizationCode: String,
        val codeVerifier: String
    ) {
        override fun toString(): String =
            "DeviceTokenSuccess(authorizationCode=<redacted>, codeVerifier=<redacted>)"
    }

    private suspend fun pollOpenAICodexDeviceAuth(device: DeviceAuthInfo): DeviceTokenSuccess =
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = DEVICE_CODE_TIMEOUT_SECONDS,
                poll = {
                    val response = postJson(
                        DEVICE_TOKEN_URL,
                        buildJsonObject {
                            put("device_auth_id", device.deviceAuthId)
                            put("user_code", device.userCode)
                        }.toString()
                    )
                    val text = response.body.toString(Charsets.UTF_8)

                    if (response.status in 200..299) {
                        val json = parseJson(text) as? JsonObject
                        val authorizationCode = json?.truthyString("authorization_code")
                        val codeVerifier = json?.truthyString("code_verifier")
                        if (authorizationCode == null || codeVerifier == null) {
                            // The raw body can echo the code/verifier; report
                            // missing field names only.
                            OAuthDeviceCodePollResult.Failed(
                                "Invalid OpenAI Codex device auth token response: " +
                                    "missing fields: " +
                                    listOfNotNull(
                                        if (authorizationCode ==
                                            null
                                        ) {
                                            "authorization_code"
                                        } else {
                                            null
                                        },
                                        if (codeVerifier == null) "code_verifier" else null
                                    ).joinToString()
                            )
                        } else {
                            OAuthDeviceCodePollResult.Complete(
                                DeviceTokenSuccess(authorizationCode, codeVerifier)
                            )
                        }
                    } else if (response.status == 403 || response.status == 404) {
                        OAuthDeviceCodePollResult.Pending
                    } else {
                        when (errorCode(text)) {
                            "deviceauth_authorization_pending" -> OAuthDeviceCodePollResult.Pending

                            "slow_down" -> OAuthDeviceCodePollResult.SlowDown()

                            else -> OAuthDeviceCodePollResult.Failed(
                                withErrorBody(
                                    "OpenAI Codex device auth failed with status ${response.status}",
                                    text,
                                    secrets = listOf(device.deviceAuthId, device.userCode)
                                )
                            )
                        }
                    }
                }
            ),
            clock = clock
        )

    private fun errorCode(body: String): String? {
        val error = (parseJson(body) as? JsonObject)?.get("error") ?: return null
        return when (error) {
            is JsonPrimitive -> error.stringOrNull()
            is JsonObject -> error.string("code")
            else -> null
        }
    }

    // --- token exchange / refresh ---

    private suspend fun exchangeAuthorizationCode(
        code: String,
        verifier: String,
        redirectUri: String
    ): OAuthToken {
        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to CLIENT_ID,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirectUri
            )
        )
        return readTokenResponse(
            response,
            TokenOperation.EXCHANGE,
            secrets = listOf(code, verifier)
        )
    }

    private suspend fun refreshAccessToken(refreshToken: String): OAuthToken {
        val response: OAuthHttpResponse
        try {
            response = postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException(
                "OpenAI Codex token refresh error: ${error.message ?: error.toString()}"
            )
        }
        return readTokenResponse(response, TokenOperation.REFRESH, secrets = listOf(refreshToken))
    }

    internal data class OAuthToken(val access: String, val refresh: String, val expires: Long) {
        override fun toString(): String =
            "OAuthToken(access=<redacted>, refresh=<redacted>, expires=$expires)"
    }

    /**
     * [secrets] are the in-flight values a hostile response body could echo
     * into the error message (see class KDoc). Expiry is `now + expires_in`
     * with no skew: pi's five-minute refresh skew lives in the shared
     * resolver, not this flow.
     */
    internal fun readTokenResponse(
        response: OAuthHttpResponse,
        operation: TokenOperation,
        secrets: List<String> = emptyList()
    ): OAuthToken {
        val text = response.body.toString(Charsets.UTF_8)
        if (response.status !in 200..299) {
            throw IllegalStateException(
                withErrorBody(
                    "OpenAI Codex token ${operation.id} failed (${response.status})",
                    text,
                    secrets
                )
            )
        }

        val json = parseJson(text)?.let { it as? JsonObject }
        val access = json?.truthyString("access_token")
        val refresh = json?.truthyString("refresh_token")
        val expiresIn = json?.strictDouble("expires_in")
        if (access == null || refresh == null || expiresIn == null) {
            val missing = listOfNotNull(
                if (access == null) "access_token" else null,
                if (refresh == null) "refresh_token" else null,
                if (expiresIn == null) "expires_in" else null
            )
            throw IllegalStateException(
                "OpenAI Codex token ${operation.id} response missing fields: ${missing.joinToString()}"
            )
        }

        return OAuthToken(
            access = access,
            refresh = refresh,
            expires = clock.now().toEpochMilliseconds() + (expiresIn * 1000).toLong()
        )
    }

    internal enum class TokenOperation(internal val id: String) {
        EXCHANGE("exchange"),
        REFRESH("refresh")
    }

    // --- credentials ---

    internal fun decodeJwt(token: String): JsonObject? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payload = parts[1]
            val normalized = payload.replace('-', '+').replace('_', '/')
            val decoded =
                java.util.Base64.getDecoder()
                    .decode(normalized + "=".repeat((4 - normalized.length % 4) % 4))
            parseJson(decoded.decodeToString()) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    internal fun getAccountId(accessToken: String): String? = decodeJwt(accessToken)
        ?.obj(JWT_CLAIM_PATH)
        ?.truthyString("chatgpt_account_id")

    private fun credentialsFromToken(token: OAuthToken): OAuthCredential {
        val accountId =
            getAccountId(token.access)
                ?: throw IllegalStateException("Failed to extract accountId from token")
        return OAuthCredential(
            access = token.access,
            refresh = token.refresh,
            expires = token.expires,
            extras = mapOf(accountIdExtra to JsonPrimitive(accountId))
        )
    }

    /** The credential extra carrying pi's `accountId` field. */
    internal val accountIdExtra: String = "accountId"

    private suspend fun exchangeAuthorizationCodeForCredentials(
        code: String,
        verifier: String,
        redirectUri: String
    ): OAuthCredential =
        credentialsFromToken(exchangeAuthorizationCode(code, verifier, redirectUri))

    // --- HTTP ---

    private suspend fun postForm(url: String, fields: Map<String, String>): OAuthHttpResponse =
        http.execute(
            OAuthHttpRequest(
                method = "POST",
                url = url,
                headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                body = XaiOAuthAuth.formUrlEncode(fields),
                timeoutMs = REQUEST_TIMEOUT_MS
            )
        )

    private suspend fun postJson(url: String, json: String): OAuthHttpResponse = http.execute(
        OAuthHttpRequest(
            method = "POST",
            url = url,
            headers = mapOf("content-type" to "application/json"),
            body = json.toByteArray(Charsets.UTF_8),
            timeoutMs = REQUEST_TIMEOUT_MS
        )
    )

    private fun parseJson(text: String): kotlinx.serialization.json.JsonElement? = try {
        lenientJson.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    /**
     * Appends a sanitized `: error=<detail>` suffix (see class KDoc for what
     * survives), scrubbed of any [secrets]; `<redacted>` when nothing does.
     */
    private fun withErrorBody(
        message: String,
        body: String,
        secrets: List<String> = emptyList()
    ): String {
        if (body.isEmpty()) return message
        val obj = try {
            parseJson(body) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val parts = mutableListOf<String>()
        when (val error = obj?.get("error")) {
            is JsonPrimitive -> if (error.isString) parts += error.content

            is JsonObject -> {
                error.truthyString("code")?.let { parts += it }
                error.truthyString("message")?.let { parts += it }
            }

            else -> {}
        }
        obj?.truthyString("error_description")?.let { parts += it }
        if (parts.isEmpty()) return "$message: <redacted>"
        return "$message: error=" + scrub(parts.joinToString(": "), secrets)
    }

    private fun scrub(text: String, secrets: List<String>): String = secrets.filter {
        it.isNotEmpty()
    }.fold(text) { acc, secret -> acc.replace(secret, "<redacted>") }

    companion object {
        const val CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"

        const val AUTH_BASE_URL: String = "https://auth.openai.com"

        const val AUTHORIZE_PATH: String = "/oauth/authorize"

        const val TOKEN_URL: String = "$AUTH_BASE_URL/oauth/token"

        const val REDIRECT_URI: String = "http://localhost:1455/auth/callback"

        const val DEVICE_USER_CODE_URL: String = "$AUTH_BASE_URL/api/accounts/deviceauth/usercode"

        const val DEVICE_TOKEN_URL: String = "$AUTH_BASE_URL/api/accounts/deviceauth/token"

        const val DEVICE_VERIFICATION_URI: String = "$AUTH_BASE_URL/codex/device"

        const val DEVICE_REDIRECT_URI: String = "$AUTH_BASE_URL/deviceauth/callback"

        const val DEVICE_CODE_TIMEOUT_SECONDS: Long = 15 * 60

        const val BROWSER_LOGIN_METHOD: String = "browser"

        const val DEVICE_CODE_LOGIN_METHOD: String = "device_code"

        const val SCOPE: String = "openid profile email offline_access"

        const val JWT_CLAIM_PATH: String = "https://api.openai.com/auth"

        /** Deliberate divergence from pi's `"pi"` default (see class KDoc). */
        const val ORIGINATOR: String = "pathfinder"

        const val CALLBACK_PORT: Int = 1455

        /** Fixed loopback host; pi's `getCallbackHost()` env override doesn't exist on Android. */
        const val CALLBACK_HOST: String = "127.0.0.1"

        const val CALLBACK_PATH: String = "/auth/callback"

        /** Bounded timeout for every OAuth request; pi relies on fetch defaults. */
        const val REQUEST_TIMEOUT_MS: Int = 30_000

        private fun defaultCreateState(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
