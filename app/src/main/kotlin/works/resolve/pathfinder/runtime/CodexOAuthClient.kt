package works.resolve.pathfinder.runtime

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.parseQueryString
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import works.resolve.pathfinder.diagnostics.DiagnosticEvent
import works.resolve.pathfinder.diagnostics.Diagnostics
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Fixed, user-safe error for every failure mode of the Codex OAuth flows.
 * Messages never include response bodies, tokens, codes, or URLs with params —
 * only the failing step and, where relevant, an HTTP status code.
 */
class CodexOAuthException(message: String) : Exception(message)

/**
 * Device-code parameters shown to the user so they can approve the login at
 * [verificationUri] with [userCode].
 */
data class CodexDeviceAuth(
    val deviceAuthId: String,
    val userCode: String,
    /** Page the user must visit: https://auth.openai.com/codex/device. */
    val verificationUri: String,
    val intervalSeconds: Long,
)

/**
 * Browser-flow parameters for one authorization attempt: the authorize URL to
 * load in a browser/WebView, the `state` the redirect must echo, and the
 * single-use PKCE code verifier (RFC 7636). The verifier is ephemeral: it
 * lives only in the caller's in-memory sign-in flow, is never persisted, and
 * is never logged.
 */
data class CodexBrowserAuth(
    val authorizeUrl: String,
    val state: String,
    val codeVerifier: String,
)

/**
 * Result of a successful token exchange or refresh.
 */
data class CodexTokens(
    val accessToken: String,
    val refreshToken: String,
    /** `clock() + expires_in * 1000`. */
    val expiresAtEpochMillis: Long,
    /** `chatgpt_account_id` from the access-token JWT's `https://api.openai.com/auth` claim. */
    val accountId: String,
)

/**
 * OAuth client for the "OpenAI Codex" (ChatGPT subscription) provider, with
 * both sign-in flows pi implements (`packages/ai/src/auth/oauth/openai-codex.ts`):
 *
 * - the device-code flow (`loginOpenAICodexDeviceCode`: user code + polling,
 *   pi's `startOpenAICodexDeviceAuth` / `pollOpenAICodexDeviceAuth` and
 *   `packages/ai/src/auth/oauth/device-code.ts` pending/slow_down/timeout
 *   semantics);
 * - the browser flow (`loginOpenAICodex`: PKCE + `state` via
 *   `createAuthorizationFlow`). The authorize URL is opened in the user's
 *   default browser, and the fixed loopback redirect
 *   `http://localhost:1455/auth/callback` is caught by
 *   [CodexLoopbackServer] — exactly the mechanism pi's CLI uses on desktop
 *   (`startLocalOAuthServer`); the caught URL is handed back to
 *   [completeBrowserLogin], which validates it and exchanges the code.
 *
 * JWT account-id decode follows pi's `getAccountId`. Pure protocol component:
 * the HTTP client and clock are injected so tests can drive it with Ktor's
 * MockEngine and virtual time. Credential storage, the Koog client wiring,
 * and UI live elsewhere.
 *
 * Device-flow polling waits use [delay], so cancelling the calling coroutine
 * aborts the flow cleanly.
 */
class CodexOAuthClient(
    private val httpClient: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** OAuth client id for the Codex CLI flows (see pi's openai-codex.ts). */
    private val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"

    private val authorizeUrl = "https://auth.openai.com/oauth/authorize"
    private val deviceUserCodeUrl = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    private val deviceTokenUrl = "https://auth.openai.com/api/accounts/deviceauth/token"
    private val tokenUrl = "https://auth.openai.com/oauth/token"
    private val verificationUri = "https://auth.openai.com/codex/device"
    private val deviceRedirectUri = "https://auth.openai.com/deviceauth/callback"
    private val jwtClaimPath = "https://api.openai.com/auth"

    /** Loopback redirect the Codex client id is registered for (pi's REDIRECT_URI). */
    private val browserRedirectUri = "http://localhost:1455/auth/callback"
    private val scope = "openid profile email offline_access"

    /** `originator` marker identifying this app in the authorize request. */
    private val originator = "pathfinder"

    /** Device-flow timeout, 15 minutes (pi's `DEVICE_CODE_TIMEOUT_SECONDS`). */
    private val flowTimeoutMillis = 15 * 60 * 1000L

    /** Minimum polling wait so a server-supplied 0 interval does not hot-loop. */
    private val minimumIntervalMillis = 1000L

    /** PKCE verifier entropy (pi: 32 random bytes, base64url-encoded to 43 chars). */
    private val pkceVerifierBytes = 32

    /** `state` entropy (pi: 16 random bytes, hex-encoded). */
    private val stateBytes = 16

    private val secureRandom = SecureRandom()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Starts the device flow: requests a user code for [clientId].
     *
     * The server may return `interval` as a JSON number or a numeric string
     * (pi parses both); a missing, non-finite, or negative interval is an error.
     */
    suspend fun beginDeviceLogin(): CodexDeviceAuth {
        val body = runCatching {
            httpClient.post(deviceUserCodeUrl) {
                contentType(ContentType.Application.Json)
                setBody("""{"client_id":"$clientId"}""")
            }
        }.getOrElse { error ->
            Diagnostics.failure(DiagnosticEvent.CODEX_DEVICE_BEGIN_TRANSPORT_FAILED, error)
            throw CodexOAuthException("Sign-in could not be started.")
        }

        if (body.status != HttpStatusCode.OK) {
            Diagnostics.httpFailure(DiagnosticEvent.CODEX_DEVICE_BEGIN_HTTP_FAILED, body.status.value)
            throw CodexOAuthException("Sign-in could not be started (HTTP ${body.status.value}).")
        }

        val responseText = runCatching { body.bodyAsText() }.getOrElse { error ->
            Diagnostics.failure(DiagnosticEvent.CODEX_DEVICE_BEGIN_TRANSPORT_FAILED, error)
            throw CodexOAuthException("Sign-in could not be started.")
        }
        val parsed = responseText.parseJsonObjectOrNull() ?: run {
            Diagnostics.event(DiagnosticEvent.CODEX_DEVICE_BEGIN_RESPONSE_INVALID)
            throw CodexOAuthException("Sign-in response was invalid.")
        }

        val deviceAuthId = parsed.stringOrNull("device_auth_id")
        val userCode = parsed.stringOrNull("user_code")
        val interval = parsed.intervalSecondsOrNull()
        if (deviceAuthId == null || userCode == null || interval == null) {
            Diagnostics.event(DiagnosticEvent.CODEX_DEVICE_BEGIN_RESPONSE_INVALID)
            throw CodexOAuthException("Sign-in response was invalid.")
        }
        return CodexDeviceAuth(deviceAuthId, userCode, verificationUri, interval)
    }

    /**
     * Polls the device-token endpoint until the user approves the login, then
     * exchanges the authorization code for tokens.
     *
     * Semantics follow pi's `pollOAuthDeviceCodeFlow`: 403/404 and the
     * `deviceauth_authorization_pending` error mean still pending; `slow_down`
     * also stays pending but adds one extra interval to the next wait; any
     * other error fails the flow. The whole flow fails after 15 minutes.
     */
    suspend fun awaitDeviceAuthorization(device: CodexDeviceAuth): CodexTokens {
        val deadline = clock() + flowTimeoutMillis
        val intervalMillis = maxOf(minimumIntervalMillis, device.intervalSeconds * 1000)
        var extraWaitMillis = 0L

        while (true) {
            when (val result = pollDeviceToken(device)) {
                is DevicePollResult.Complete ->
                    return exchangeAuthorizationCode(
                        result.authorizationCode,
                        result.codeVerifier,
                        deviceRedirectUri,
                    )
                is DevicePollResult.Failed ->
                    throw CodexOAuthException("Sign-in failed (HTTP ${result.status}).")
                DevicePollResult.Pending -> Unit
                DevicePollResult.SlowDown -> extraWaitMillis += intervalMillis
            }

            val remaining = deadline - clock()
            if (remaining <= 0) throw CodexOAuthException("Sign-in timed out.")
            delay(minOf(intervalMillis + extraWaitMillis, remaining))
        }
    }

    private sealed interface DevicePollResult {
        data class Complete(val authorizationCode: String, val codeVerifier: String) : DevicePollResult
        data object Pending : DevicePollResult
        data object SlowDown : DevicePollResult
        data class Failed(val status: Int) : DevicePollResult
    }

    private suspend fun pollDeviceToken(device: CodexDeviceAuth): DevicePollResult {
        val response = runCatching {
            httpClient.post(deviceTokenUrl) {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"device_auth_id":"${device.deviceAuthId}","user_code":"${device.userCode}"}""",
                )
            }
        }.getOrElse { error ->
            Diagnostics.failure(DiagnosticEvent.CODEX_DEVICE_POLL_TRANSPORT_FAILED, error)
            throw CodexOAuthException("Sign-in could not be checked.")
        }

        if (response.status == HttpStatusCode.OK) {
            val responseText = runCatching { response.bodyAsText() }.getOrElse { error ->
                Diagnostics.failure(DiagnosticEvent.CODEX_DEVICE_POLL_TRANSPORT_FAILED, error)
                throw CodexOAuthException("Sign-in could not be checked.")
            }
            val parsed = responseText.parseJsonObjectOrNull()
            val code = parsed?.stringOrNull("authorization_code")
            val verifier = parsed?.stringOrNull("code_verifier")
            if (code == null || verifier == null) {
                Diagnostics.event(DiagnosticEvent.CODEX_DEVICE_POLL_RESPONSE_INVALID)
                throw CodexOAuthException("Sign-in response was invalid.")
            }
            return DevicePollResult.Complete(code, verifier)
        }

        // 403/404 mean "not approved yet" (see pi's pollOpenAICodexDeviceAuth).
        if (response.status.value == 403 || response.status.value == 404) {
            return DevicePollResult.Pending
        }

        val errorBody = runCatching { response.bodyAsText() }.getOrElse { error ->
            Diagnostics.failure(DiagnosticEvent.CODEX_DEVICE_POLL_TRANSPORT_FAILED, error)
            throw CodexOAuthException("Sign-in could not be checked.")
        }
        val errorCode = errorBody.parseJsonObjectOrNull()
            ?.get("error")
            ?.let { if (it is JsonObject) it.stringOrNull("code") else (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        return when (errorCode) {
            "deviceauth_authorization_pending" -> DevicePollResult.Pending
            "slow_down" -> DevicePollResult.SlowDown
            else -> {
                Diagnostics.httpFailure(DiagnosticEvent.CODEX_DEVICE_POLL_HTTP_FAILED, response.status.value)
                DevicePollResult.Failed(response.status.value)
            }
        }
    }

    /**
     * Starts the browser flow: builds the PKCE-protected authorize URL
     * (pi's `createAuthorizationFlow`) and returns it with the `state` and
     * code verifier needed to complete the flow. Pure local computation — no
     * network — so it cannot fail in practice.
     */
    fun beginBrowserLogin(): CodexBrowserAuth {
        val verifier = randomBytes(pkceVerifierBytes).toBase64Url()
        val challenge = sha256(verifier.encodeToByteArray()).toBase64Url()
        val state = randomBytes(stateBytes).toHexString()

        val query = Parameters.build {
            append("response_type", "code")
            append("client_id", clientId)
            append("redirect_uri", browserRedirectUri)
            append("scope", scope)
            append("code_challenge", challenge)
            append("code_challenge_method", "S256")
            append("state", state)
            append("id_token_add_organizations", "true")
            append("codex_cli_simplified_flow", "true")
            append("originator", originator)
        }
        return CodexBrowserAuth(
            authorizeUrl = "$authorizeUrl?${query.formUrlEncode()}",
            state = state,
            codeVerifier = verifier,
        )
    }

    /**
     * True when [url] is the browser flow's loopback redirect (scheme, host,
     * port, and path of the registered `redirect_uri`); query contents are
     * validated separately by [completeBrowserLogin].
     */
    fun isBrowserRedirect(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "http" &&
            uri.host == "localhost" &&
            uri.port == 1455 &&
            uri.path == "/auth/callback"
    }

    /**
     * Completes the browser flow with the redirect URL caught by
     * [CodexLoopbackServer]: validates it is the loopback redirect, that it
     * echoes [CodexBrowserAuth.state], and that it carries an authorization
     * code (an OAuth `error` redirect fails with a user-safe message), then
     * exchanges the code with the PKCE verifier (pi's
     * `exchangeAuthorizationCode`).
     */
    suspend fun completeBrowserLogin(auth: CodexBrowserAuth, redirectUrl: String): CodexTokens {
        if (!isBrowserRedirect(redirectUrl)) {
            Diagnostics.event(DiagnosticEvent.CODEX_BROWSER_REDIRECT_INVALID)
            throw CodexOAuthException("Sign-in could not be completed.")
        }
        val query = parseQueryString(URI(redirectUrl).rawQuery ?: "")
        if (query["error"] != null) {
            Diagnostics.event(DiagnosticEvent.CODEX_BROWSER_AUTHORIZATION_DENIED)
            throw CodexOAuthException("Sign-in was not completed.")
        }
        val code = query["code"]
        if (query["state"] != auth.state || code == null) {
            Diagnostics.event(DiagnosticEvent.CODEX_BROWSER_REDIRECT_PAYLOAD_INVALID)
            throw CodexOAuthException("Sign-in could not be completed.")
        }
        return exchangeAuthorizationCode(code, auth.codeVerifier, browserRedirectUri)
    }

    /** Exchanges the device flow's authorization code for tokens (pi's `exchangeAuthorizationCode`). */
    private suspend fun exchangeAuthorizationCode(
        authorizationCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): CodexTokens =
        requestTokens(
            TokenOperation.EXCHANGE,
            Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("code", authorizationCode)
                append("code_verifier", codeVerifier)
                append("redirect_uri", redirectUri)
            },
        )

    /** Refreshes an expired access token (pi's `refreshAccessToken`). */
    suspend fun refresh(refreshToken: String): CodexTokens =
        requestTokens(
            TokenOperation.REFRESH,
            Parameters.build {
                append("grant_type", "refresh_token")
                append("client_id", clientId)
                append("refresh_token", refreshToken)
            },
        )

    private suspend fun requestTokens(operation: TokenOperation, form: Parameters): CodexTokens {
        Diagnostics.event(operation.startedEvent)
        val response = runCatching {
            httpClient.post(tokenUrl) {
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody(FormDataContent(form))
            }
        }.getOrElse { error ->
            Diagnostics.failure(operation.transportFailedEvent, error)
            throw CodexOAuthException("${operation.failureStep}.")
        }

        if (response.status != HttpStatusCode.OK) {
            Diagnostics.httpFailure(operation.httpFailedEvent, response.status.value)
            throw CodexOAuthException("${operation.failureStep} (HTTP ${response.status.value}).")
        }

        val responseText = runCatching { response.bodyAsText() }.getOrElse { error ->
            Diagnostics.failure(operation.transportFailedEvent, error)
            throw CodexOAuthException("${operation.failureStep}.")
        }
        val parsed = responseText.parseJsonObjectOrNull() ?: run {
            Diagnostics.event(operation.responseInvalidEvent)
            throw CodexOAuthException("${operation.failureStep}: response was invalid.")
        }
        val accessToken = parsed.stringOrNull("access_token")
        val refreshToken = parsed.stringOrNull("refresh_token")
        val expiresInSeconds = parsed.get("expires_in")?.jsonPrimitive?.longOrNull
        if (accessToken == null || refreshToken == null || expiresInSeconds == null) {
            Diagnostics.event(operation.responseInvalidEvent)
            throw CodexOAuthException("${operation.failureStep}: response was invalid.")
        }

        val accountId = accountIdFromJwt(accessToken) ?: run {
            Diagnostics.event(operation.accountInvalidEvent)
            throw CodexOAuthException("Account information could not be read from the sign-in response.")
        }

        Diagnostics.event(operation.succeededEvent)
        return CodexTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMillis = clock() + expiresInSeconds * 1000,
            accountId = accountId,
        )
    }

    private enum class TokenOperation(
        val failureStep: String,
        val startedEvent: DiagnosticEvent,
        val transportFailedEvent: DiagnosticEvent,
        val httpFailedEvent: DiagnosticEvent,
        val responseInvalidEvent: DiagnosticEvent,
        val accountInvalidEvent: DiagnosticEvent,
        val succeededEvent: DiagnosticEvent,
    ) {
        EXCHANGE(
            failureStep = "Sign-in could not be completed",
            startedEvent = DiagnosticEvent.CODEX_TOKEN_EXCHANGE_STARTED,
            transportFailedEvent = DiagnosticEvent.CODEX_TOKEN_EXCHANGE_TRANSPORT_FAILED,
            httpFailedEvent = DiagnosticEvent.CODEX_TOKEN_EXCHANGE_HTTP_FAILED,
            responseInvalidEvent = DiagnosticEvent.CODEX_TOKEN_EXCHANGE_RESPONSE_INVALID,
            accountInvalidEvent = DiagnosticEvent.CODEX_TOKEN_EXCHANGE_ACCOUNT_INVALID,
            succeededEvent = DiagnosticEvent.CODEX_TOKEN_EXCHANGE_SUCCEEDED,
        ),
        REFRESH(
            failureStep = "Sign-in could not be refreshed",
            startedEvent = DiagnosticEvent.CODEX_TOKEN_REFRESH_STARTED,
            transportFailedEvent = DiagnosticEvent.CODEX_TOKEN_REFRESH_TRANSPORT_FAILED,
            httpFailedEvent = DiagnosticEvent.CODEX_TOKEN_REFRESH_HTTP_FAILED,
            responseInvalidEvent = DiagnosticEvent.CODEX_TOKEN_REFRESH_RESPONSE_INVALID,
            accountInvalidEvent = DiagnosticEvent.CODEX_TOKEN_REFRESH_ACCOUNT_INVALID,
            succeededEvent = DiagnosticEvent.CODEX_TOKEN_REFRESH_SUCCEEDED,
        ),
    }

    /**
     * Decodes the access-token JWT payload and reads
     * `https://api.openai.com/auth` → `chatgpt_account_id` (pi's `getAccountId`).
     * Returns null for non-JWT tokens, malformed base64, or a missing/empty claim.
     */
    private fun accountIdFromJwt(accessToken: String): String? {
        val parts = accessToken.split('.')
        if (parts.size != 3) return null
        val payload = runCatching {
            String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')))
        }.getOrNull() ?: return null
        val claim = payload.parseJsonObjectOrNull()?.get(jwtClaimPath) as? JsonObject ?: return null
        val accountId = claim.stringOrNull("chatgpt_account_id") ?: return null
        return accountId.ifEmpty { null }
    }

    private fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also(secureRandom::nextBytes)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toBase64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.parseJsonObjectOrNull(): JsonObject? =
        runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    /** Accepts `interval` as a JSON number or numeric string; must be finite and >= 0. */
    private fun JsonObject.intervalSecondsOrNull(): Long? {
        val interval = get("interval") ?: return null
        val primitive = interval as? kotlinx.serialization.json.JsonPrimitive ?: return null
        val value = if (primitive.isString) primitive.content.trim().toDoubleOrNull() else primitive.doubleOrNull
        return value?.takeIf { it.isFinite() && it >= 0 }?.toLong()
    }
}
