package works.resolve.pathfinder.ai.auth.oauth

import kotlinx.serialization.json.Json
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
import works.resolve.pathfinder.ai.auth.PkceGenerator

/**
 * OpenAI Codex (ChatGPT OAuth) flow, ported from pi
 * `packages/ai/src/auth/oauth/openai-codex.ts`.
 *
 * Mirrors the upstream file symbol-for-symbol: the login-method [AuthPrompt.Select]
 * (`browser` / `device_code`, pi `loginOpenAICodex`), PKCE browser flow
 * (`createAuthorizationFlow`), RFC 8628 device flow (`startOpenAICodexDeviceAuth`,
 * `pollOpenAICodexDeviceAuth` via the shared [pollOAuthDeviceCodeFlow]), strict
 * token validation (`readTokenResponse`), JWT account metadata (`decodeJwt`,
 * `getAccountId`, `credentialsFromToken`), refresh (`refreshAccessToken`), and
 * `toAuth` (`{ apiKey: credential.access }`). The provider metadata matches pi
 * `providers/openai-codex.ts`: `name "OpenAI (ChatGPT Plus/Pro)"`,
 * `isSubscription: true`, no `loginLabel`.
 *
 * Divergences from pi (documented per AGENTS.md, each as narrow as possible):
 * - **No loopback server.** Pi's `startLocalOAuthServer` binds a Node
 *   `http.Server` on `127.0.0.1:1455` and races its callback against a manual
 *   code prompt. Android cannot own a loopback port in the browser login UX,
 *   so the browser flow notifies [AuthEvent.AuthUrl] and then asks for the
 *   authorization code / redirect URL through a single
 *   [AuthPrompt.ManualCode]; `parseAuthorizationInput` accepts pi's input
 *   shapes: a bare code, `code#state`, a `code=`-style query, or a
 *   full redirect URL, and state is validated exactly like pi's manual path
 *   (`parsed.state != null && parsed.state != state` → "State mismatch").
 * - **HTTP boundary.** Pi `fetch`es with an `AbortSignal`; all HTTP goes
 *   through the injected [OAuthHttpClient] with a bounded request timeout,
 *   and cancellation travels as coroutine cancellation (pi's
 *   `fetchWithLoginCancellation` abort→"Login cancelled" mapping).
 * - **Redacted error bodies.** Pi interpolates raw response bodies into
 *   several error messages (`device code request failed`, `Invalid OpenAI
 *   Codex device code response`, `Invalid OpenAI Codex device auth token
 *   response`, `device auth failed`, token `failed`/`missing fields`). A
 *   server response can echo back the very secrets the request carried
 *   (authorization code, code verifier, device auth id, tokens, JWT, account
 *   id), so this port never interpolates a raw body: it keeps only the
 *   structured `error` / `error.code` / `error.message` /
 *   `error_description` strings (scrubbed of any in-flight secret value) as
 *   `error=<detail>`, writes `<redacted>` when none parse, and reports
 *   invalid JSON shapes as missing field names only. Everything else in the
 *   messages (statuses, wording, ordering) is verbatim.
 * - **No status line fallback.** `OAuthHttpResponse` has no `statusText`, so
 *   failed token responses append nothing when the body is empty (pi:
 *   `text || response.statusText`).
 * - **JWT base64 tolerance.** Pi decodes with `atob` (standard base64); this
 *   port also accepts unpadded base64url, since real ChatGPT access tokens
 *   are RFC 7515 base64url JWTs — strictly more permissive, never less.
 * - **Seams.** `Date.now()` reads through [now], state generation through
 *   [createState] (pi `createState`: 16 random bytes, hex), and PKCE through
 *   [pkce] — all for deterministic tests; production uses defaults.
 *
 * Nothing secret is ever logged or echoed in exception messages: even the
 * structured server error text that survives is scrubbed of every in-flight
 * secret value, and the internal result shapes redact their secret fields
 * in `toString`.
 */
class OpenAiCodexOAuthAuth(
    private val http: OAuthHttpClient,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val createState: () -> String = { defaultCreateState() },
    private val pkce: PkceGenerator = PkceGenerator(),
) : OAuthAuth {

    override val name: String = "OpenAI (ChatGPT Plus/Pro)"

    /** pi `isSubscription: true`. */
    override val isSubscription: Boolean = true

    /** Pi's provider definition passes no `loginLabel`, so the default null stands. */
    override val loginLabel: String? = null

    // --- login (pi `openaiCodexOAuth.login`) ---

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val method = interaction.prompt(
            AuthPrompt.Select(
                message = "Select OpenAI Codex login method:",
                options = listOf(
                    AuthPrompt.Select.Option(BROWSER_LOGIN_METHOD, "Browser login (default)"),
                    AuthPrompt.Select.Option(DEVICE_CODE_LOGIN_METHOD, "Device code login (headless)"),
                ),
            ),
        )

        return when (method) {
            DEVICE_CODE_LOGIN_METHOD -> loginOpenAICodexDeviceCode(interaction)
            BROWSER_LOGIN_METHOD -> loginOpenAICodex(interaction)
            else -> throw IllegalStateException("Unknown OpenAI Codex login method: $method")
        }
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        credentialsFromToken(refreshAccessToken(credential.refresh))

    /** Port of pi `toAuth`: `{ apiKey: credential.access }`. */
    override suspend fun toAuth(credential: OAuthCredential): ModelAuth = ModelAuth(apiKey = credential.access)

    // --- browser login (pi `loginOpenAICodex` + `createAuthorizationFlow`) ---

    /**
     * Port of pi `createAuthorizationFlow`: PKCE pair + state + the exact
     * authorize-URL parameter set (order preserved as upstream insertion
     * order; spaces encode as `+` like `URLSearchParams.toString()`).
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
                    "originator" to originator,
                ),
            ).toString(Charsets.UTF_8)
        return AuthorizationFlow(verifier = pair.verifier, state = state, url = url)
    }

    /**
     * Port of pi `createAuthorizationFlow`'s result shape. The verifier is
     * redacted in `toString` (it must never reach logs or error surfaces).
     */
    internal data class AuthorizationFlow(val verifier: String, val state: String, val url: String) {
        override fun toString(): String =
            "AuthorizationFlow(verifier=<redacted>, state=$state, url=$url)"
    }

    /**
     * Port of pi `loginOpenAICodex` (browser branch), adapted for Android as
     * documented on the class: instead of pi's Node loopback callback server,
     * the user pastes the authorization code or the full redirect URL into a
     * single [AuthPrompt.ManualCode] with pi's message and placeholder.
     * State validation matches pi's manual-code path exactly.
     */
    private suspend fun loginOpenAICodex(interaction: AuthInteraction): OAuthCredential {
        val flow = createAuthorizationFlow()
        interaction.notify(
            AuthEvent.AuthUrl(
                url = flow.url,
                instructions = "A browser window should open. Complete login to finish.",
            ),
        )
        val manualCode = interaction.prompt(
            AuthPrompt.ManualCode(
                message = "Complete login in your browser, or paste the authorization code / redirect URL here:",
                placeholder = REDIRECT_URI,
            ),
        )
        val parsed = parseAuthorizationInput(manualCode)
        // pi uses `if (parsed.state && parsed.state !== state)`: an empty
        // state is absent for this check, while any non-empty state must match.
        if (!parsed.state.isNullOrEmpty() && parsed.state != flow.state) {
            throw IllegalStateException("State mismatch")
        }
        // pi: `if (!code) throw` — rejects both a missing and an empty code
        // (e.g. `?code=&state=...` or a bare empty `code=` value).
        val code = parsed.code
        if (code.isNullOrEmpty()) throw IllegalStateException("Missing authorization code")
        return exchangeAuthorizationCodeForCredentials(code, flow.verifier, REDIRECT_URI)
    }

    /**
     * Port of pi `parseAuthorizationInput`. Accepts a URL (query params), a
     * `code#state` fragment pair, a `code=`-style query string, or a bare
     * code. Android uses [java.net.URI] to recognize an absolute pasted
     * redirect URL; non-URLs fall through to the fragment/query/bare-code
     * branches.
     *
     * The `#` branch mirrors JS `value.split("#", 2)`, which keeps only the
     * first two segments: `code#state#ignored` yields `state`, not
     * `state#ignored` (Kotlin's `split(limit = 2)` would keep the whole
     * remainder, so the port splits without a limit and takes `[0]`/`[1]`).
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
                state = queryParam(url.rawQuery, "state"),
            )
        }

        if (value.contains("#")) {
            val parts = value.split("#")
            return AuthorizationInput(code = parts[0], state = parts.getOrNull(1))
        }

        if (value.contains("code=")) {
            return AuthorizationInput(
                code = queryParam(value, "code"),
                state = queryParam(value, "state"),
            )
        }

        return AuthorizationInput(code = value, state = null)
    }

    /** Port of pi `parseAuthorizationInput`'s result shape. */
    internal data class AuthorizationInput(val code: String?, val state: String?)

    /**
     * Reads the first matching form-encoded query parameter, matching the
     * `URLSearchParams.get` behavior used by pi for valid OAuth redirects.
     */
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

    /** Standard UTF-8 application/x-www-form-urlencoded component decoding. */
    private fun formUrlDecode(raw: String): String =
        java.net.URLDecoder.decode(raw, Charsets.UTF_8.name())

    // --- device-code login (pi `loginOpenAICodexDeviceCode`) ---

    private suspend fun loginOpenAICodexDeviceCode(interaction: AuthInteraction): OAuthCredential {
        val device = startOpenAICodexDeviceAuth()
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = DEVICE_VERIFICATION_URI,
                intervalSeconds = device.intervalSeconds.toInt(),
                expiresInSeconds = DEVICE_CODE_TIMEOUT_SECONDS.toInt(),
            ),
        )
        val code = pollOpenAICodexDeviceAuth(device)
        return exchangeAuthorizationCodeForCredentials(
            code.authorizationCode,
            code.codeVerifier,
            DEVICE_REDIRECT_URI,
        )
    }

    /** Port of pi `DeviceAuthInfo`; the device auth id is redacted in `toString`. */
    internal data class DeviceAuthInfo(
        val deviceAuthId: String,
        val userCode: String,
        val intervalSeconds: Double,
    ) {
        override fun toString(): String =
            "DeviceAuthInfo(deviceAuthId=<redacted>, userCode=$userCode, intervalSeconds=$intervalSeconds)"
    }

    /**
     * Port of pi `startOpenAICodexDeviceAuth`: POSTs `{client_id}` as JSON,
     * maps 404 to pi's "not enabled" message, other failures to the
     * status message with a sanitized body (see class KDoc), and validates
     * `device_auth_id`, `user_code`, and `interval` — string intervals go
     * through JS `Number(trimmed)` coercion via [jsNumber] (so
     * `" 0x10 "` is 16 and whitespace-only is 0), numbers must be finite
     * and non-negative. Invalid shapes fail with the missing field names
     * instead of pi's raw `JSON.stringify(json)` body.
     */
    internal suspend fun startOpenAICodexDeviceAuth(): DeviceAuthInfo {
        val response = postJson(
            DEVICE_USER_CODE_URL,
            buildJsonObject { put("client_id", CLIENT_ID) }.toString(),
        )
        val text = response.body.toString(Charsets.UTF_8)
        if (response.status !in 200..299) {
            if (response.status == 404) {
                throw IllegalStateException(
                    "OpenAI Codex device code login is not enabled for this server. " +
                        "Use browser login or verify the server URL.",
                )
            }
            throw IllegalStateException(
                withErrorBody(
                    "OpenAI Codex device code request failed with status ${response.status}",
                    text,
                ),
            )
        }

        val json = parseJson(text)?.let { it as? JsonObject }
        val intervalSeconds = coerceIntervalSeconds(json)
        val deviceAuthId = json?.stringField("device_auth_id")
        val userCode = json?.stringField("user_code")
        val intervalValid = intervalSeconds.isFinite() && intervalSeconds >= 0
        if (deviceAuthId == null || userCode == null || !intervalValid) {
            val missing = listOfNotNull(
                if (deviceAuthId == null) "device_auth_id" else null,
                if (userCode == null) "user_code" else null,
                if (!intervalValid) "interval" else null,
            )
            throw IllegalStateException(
                "Invalid OpenAI Codex device code response: missing fields: ${missing.joinToString()}",
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
                if (interval.isString) jsNumber(interval.content)
                else interval.content.toDoubleOrNull() ?: Double.NaN
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
            "Infinity" -> return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            "NaN" -> return Double.NaN
        }
        RadixLiteral.matchEntire(s)?.let { match ->
            val digits = match.groupValues[2].substring(2)
            val radix = when (match.groupValues[2][1].lowercaseChar()) {
                'x' -> 16
                'o' -> 8
                else -> 2
            }
            val value = digits.toLongOrNull(radix) ?: return Double.NaN
            return (if (negative) -value else value).toDouble()
        }
        return if (DecimalLiteral.matches(s)) s.toDouble() else Double.NaN
    }

    private val RadixLiteral = Regex("^([+-]?)(0[xX][0-9a-fA-F]+|0[oO][0-7]+|0[bB][01]+)$")

    private val DecimalLiteral = Regex("^([+-]?)((\\d+(\\.\\d*)?)|\\.\\d+)([eE][+-]?\\d+)?$")

    /**
     * Port of pi `DeviceTokenSuccess`; both fields are secrets and are
     * redacted in `toString`.
     */
    internal data class DeviceTokenSuccess(val authorizationCode: String, val codeVerifier: String) {
        override fun toString(): String =
            "DeviceTokenSuccess(authorizationCode=<redacted>, codeVerifier=<redacted>)"
    }

    /**
     * Port of pi `pollOpenAICodexDeviceAuth`: RFC 8628 polling through the
     * shared [pollOAuthDeviceCodeFlow] with pi's 15-minute expiry. 403/404
     * poll responses are pending; `deviceauth_authorization_pending` and
     * `slow_down` error codes (string or `{code}` object) map to the poller's
     * Pending/SlowDown; anything else fails with pi's status+body message.
     */
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
                        }.toString(),
                    )
                    val text = response.body.toString(Charsets.UTF_8)

                    if (response.status in 200..299) {
                        val json = parseJson(text) as? JsonObject
                        val authorizationCode = json?.stringField("authorization_code")
                        val codeVerifier = json?.stringField("code_verifier")
                        if (authorizationCode == null || codeVerifier == null) {
                            // pi echoes the raw body, which can carry the
                            // authorization code / code verifier; report the
                            // missing field names only (see class KDoc).
                            OAuthDeviceCodePollResult.Failed(
                                "Invalid OpenAI Codex device auth token response: missing fields: " +
                                    listOfNotNull(
                                        if (authorizationCode == null) "authorization_code" else null,
                                        if (codeVerifier == null) "code_verifier" else null,
                                    ).joinToString(),
                            )
                        } else {
                            OAuthDeviceCodePollResult.Complete(
                                DeviceTokenSuccess(authorizationCode, codeVerifier),
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
                                    secrets = listOf(device.deviceAuthId, device.userCode),
                                ),
                            )
                        }
                    }
                },
            ),
            now = now,
        )

    /** Pi's inline error-code extraction: `error` as string or `{code}` object. */
    private fun errorCode(body: String): String? {
        val error = (parseJson(body) as? JsonObject)?.get("error") ?: return null
        return when (error) {
            is JsonPrimitive -> error.content.takeIf { error.isString }
            is JsonObject -> (error["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            else -> null
        }
    }

    // --- token exchange / refresh (pi `exchangeAuthorizationCode`, `refreshAccessToken`) ---

    private suspend fun exchangeAuthorizationCode(
        code: String,
        verifier: String,
        redirectUri: String,
    ): OAuthToken {
        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to CLIENT_ID,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirectUri,
            ),
        )
        return readTokenResponse(response, TokenOperation.EXCHANGE, secrets = listOf(code, verifier))
    }

    /**
     * Port of pi `refreshAccessToken`: network failures wrap in
     * `OpenAI Codex token refresh error: <message>`; caller cancellation
     * propagates unwrapped.
     */
    private suspend fun refreshAccessToken(refreshToken: String): OAuthToken {
        val response: OAuthHttpResponse
        try {
            response = postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID,
                ),
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("OpenAI Codex token refresh error: ${error.message ?: error.toString()}")
        }
        return readTokenResponse(response, TokenOperation.REFRESH, secrets = listOf(refreshToken))
    }

    /** Port of pi `OAuthToken`; token values are redacted in `toString`. */
    internal data class OAuthToken(val access: String, val refresh: String, val expires: Long) {
        override fun toString(): String =
            "OAuthToken(access=<redacted>, refresh=<redacted>, expires=$expires)"
    }

    /**
     * Port of pi `readTokenResponse`. Non-2xx fails with pi's status message
     * plus a sanitized body (see class KDoc; no status-line fallback);
     * [secrets] covers the in-flight values a hostile body could echo. A 2xx
     * body must be a JSON object carrying non-empty `access_token` and
     * `refresh_token` strings and a numeric `expires_in`, else the
     * missing-fields error (field names only — redacted divergence). Expiry
     * is `now + expires_in * 1000` with no skew (pi's five-minute refresh
     * skew lives in the shared resolver, not this flow).
     */
    internal fun readTokenResponse(
        response: OAuthHttpResponse,
        operation: TokenOperation,
        secrets: List<String> = emptyList(),
    ): OAuthToken {
        val text = response.body.toString(Charsets.UTF_8)
        if (response.status !in 200..299) {
            throw IllegalStateException(
                withErrorBody(
                    "OpenAI Codex token ${operation.id} failed (${response.status})",
                    text,
                    secrets,
                ),
            )
        }

        val json = parseJson(text)?.let { it as? JsonObject }
        val access = json?.stringField("access_token")
        val refresh = json?.stringField("refresh_token")
        val expiresIn =
            (json?.get("expires_in") as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content
                ?.toDoubleOrNull()
        if (access == null || refresh == null || expiresIn == null || !expiresIn.isFinite()) {
            val missing = listOfNotNull(
                if (access == null) "access_token" else null,
                if (refresh == null) "refresh_token" else null,
                if (expiresIn == null || !expiresIn.isFinite()) "expires_in" else null,
            )
            throw IllegalStateException(
                "OpenAI Codex token ${operation.id} response missing fields: ${missing.joinToString()}",
            )
        }

        return OAuthToken(
            access = access,
            refresh = refresh,
            expires = now() + (expiresIn * 1000).toLong(),
        )
    }

    /** Port of pi `TokenOperation`. */
    internal enum class TokenOperation(internal val id: String) {
        EXCHANGE("exchange"),
        REFRESH("refresh"),
    }

    // --- credentials (pi `decodeJwt`, `getAccountId`, `credentialsFromToken`) ---

    /**
     * Port of pi `decodeJwt`: splits on `.` (exactly three parts), decodes
     * the payload segment, parses it as JSON. Returns null on any failure —
     * no exception, no partial payload. Accepts unpadded standard and
     * base64url alphabets (see class KDoc divergence note).
     */
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

    /**
     * Port of pi `getAccountId`: the `chatgpt_account_id` claim under
     * `https://api.openai.com/auth`, or null when absent/empty.
     */
    internal fun getAccountId(accessToken: String): String? =
        decodeJwt(accessToken)
            ?.get(JWT_CLAIM_PATH)
            ?.let { it as? JsonObject }
            ?.stringField("chatgpt_account_id")

    /**
     * Port of pi `credentialsFromToken`: extracts the account id from the
     * access-token JWT (failing with pi's message when it cannot) and stores
     * it as the `accountId` extra alongside the canonical OAuth fields.
     */
    private fun credentialsFromToken(token: OAuthToken): OAuthCredential {
        val accountId =
            getAccountId(token.access)
                ?: throw IllegalStateException("Failed to extract accountId from token")
        return OAuthCredential(
            access = token.access,
            refresh = token.refresh,
            expires = token.expires,
            extras = mapOf(ACCOUNT_ID_EXTRA to JsonPrimitive(accountId)),
        )
    }

    /** The credential extra carrying pi's `accountId` field. */
    internal val ACCOUNT_ID_EXTRA: String = "accountId"

    private suspend fun exchangeAuthorizationCodeForCredentials(
        code: String,
        verifier: String,
        redirectUri: String,
    ): OAuthCredential = credentialsFromToken(exchangeAuthorizationCode(code, verifier, redirectUri))

    // --- HTTP (pi `fetch` with JSON/form bodies) ---

    private suspend fun postForm(url: String, fields: Map<String, String>): OAuthHttpResponse =
        http.execute(
            OAuthHttpRequest(
                method = "POST",
                url = url,
                headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                body = XaiOAuthAuth.formUrlEncode(fields),
                timeoutMs = REQUEST_TIMEOUT_MS,
            ),
        )

    private suspend fun postJson(url: String, json: String): OAuthHttpResponse =
        http.execute(
            OAuthHttpRequest(
                method = "POST",
                url = url,
                headers = mapOf("content-type" to "application/json"),
                body = json.toByteArray(Charsets.UTF_8),
                timeoutMs = REQUEST_TIMEOUT_MS,
            ),
        )

    private fun parseJson(text: String): kotlinx.serialization.json.JsonElement? =
        try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }

    /** pi truthiness check: non-empty string, JSON string primitive only. */
    private fun JsonObject.stringField(field: String): String? =
        (get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /**
     * Appends a sanitized `: error=<detail>` suffix for a non-empty body (see
     * class KDoc): only structured `error` / `error.code` / `error.message` /
     * `error_description` strings survive, scrubbed of any [secrets]; an
     * unparseable or detail-free body yields `<redacted>`.
     */
    private fun withErrorBody(message: String, body: String, secrets: List<String> = emptyList()): String {
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
                error.stringField("code")?.let { parts += it }
                error.stringField("message")?.let { parts += it }
            }
            else -> {}
        }
        obj?.stringField("error_description")?.let { parts += it }
        if (parts.isEmpty()) return "$message: <redacted>"
        return "$message: error=" + scrub(parts.joinToString(": "), secrets)
    }

    /** Replaces every occurrence of an in-flight secret with `<redacted>`. */
    private fun scrub(text: String, secrets: List<String>): String =
        secrets.filter { it.isNotEmpty() }.fold(text) { acc, secret -> acc.replace(secret, "<redacted>") }

    companion object {
        /** pi `CLIENT_ID`. */
        const val CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"

        /** pi `AUTH_BASE_URL` and the URLs derived from it. */
        const val AUTH_BASE_URL: String = "https://auth.openai.com"

        /** pi `AUTHORIZE_URL` path. */
        const val AUTHORIZE_PATH: String = "/oauth/authorize"

        /** pi `TOKEN_URL`. */
        const val TOKEN_URL: String = "$AUTH_BASE_URL/oauth/token"

        /** pi `REDIRECT_URI` (the loopback callback address, kept for the exchange and prompt placeholder). */
        const val REDIRECT_URI: String = "http://localhost:1455/auth/callback"

        /** pi `DEVICE_USER_CODE_URL`. */
        const val DEVICE_USER_CODE_URL: String = "$AUTH_BASE_URL/api/accounts/deviceauth/usercode"

        /** pi `DEVICE_TOKEN_URL`. */
        const val DEVICE_TOKEN_URL: String = "$AUTH_BASE_URL/api/accounts/deviceauth/token"

        /** pi `DEVICE_VERIFICATION_URI`. */
        const val DEVICE_VERIFICATION_URI: String = "$AUTH_BASE_URL/codex/device"

        /** pi `DEVICE_REDIRECT_URI`. */
        const val DEVICE_REDIRECT_URI: String = "$AUTH_BASE_URL/deviceauth/callback"

        /** pi `DEVICE_CODE_TIMEOUT_SECONDS` (15 minutes). */
        const val DEVICE_CODE_TIMEOUT_SECONDS: Long = 15 * 60

        /** pi `OPENAI_CODEX_BROWSER_LOGIN_METHOD`. */
        const val BROWSER_LOGIN_METHOD: String = "browser"

        /** pi `OPENAI_CODEX_DEVICE_CODE_LOGIN_METHOD`. */
        const val DEVICE_CODE_LOGIN_METHOD: String = "device_code"

        /** pi `SCOPE`. */
        const val SCOPE: String = "openid profile email offline_access"

        /** pi `JWT_CLAIM_PATH`. */
        const val JWT_CLAIM_PATH: String = "https://api.openai.com/auth"

        /** pi `originator: "pi"` default in `createAuthorizationFlow`. */
        const val ORIGINATOR: String = "pi"

        /** Bounded connect+read timeout for every OAuth exchange (pi relies on fetch; Pathfinder bounds it). */
        const val REQUEST_TIMEOUT_MS: Int = 30_000

        /** Port of pi `createState`: 16 random bytes as hex. */
        private fun defaultCreateState(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
