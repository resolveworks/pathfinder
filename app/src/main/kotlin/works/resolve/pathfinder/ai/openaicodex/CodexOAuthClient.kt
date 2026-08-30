package works.resolve.pathfinder.ai.openaicodex

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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Fixed, user-safe error for every failure mode of the Codex OAuth device flow.
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
 * OAuth device-code client for the "OpenAI Codex" (ChatGPT subscription) provider.
 *
 * Behavioral reference: pi's implementation at
 * `packages/ai/src/auth/oauth/openai-codex.ts` (`startOpenAICodexDeviceAuth`,
 * `pollOpenAICodexDeviceAuth`, `loginOpenAICodexDeviceCode`, JWT account-id
 * decode) and `packages/ai/src/auth/oauth/device-code.ts`
 * (`pollOAuthDeviceCodeFlow` — pending/slow_down/timeout semantics).
 *
 * Pure protocol component: the HTTP client and clock are injected so tests can
 * drive it with Ktor's MockEngine and virtual time. Credential storage, the
 * Koog client wiring, and UI live elsewhere.
 *
 * Polling waits use [delay], so cancelling the calling coroutine aborts the
 * flow cleanly.
 */
class CodexOAuthClient(
    private val httpClient: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** OAuth client id for the Codex CLI device flow (see pi's openai-codex.ts). */
    private val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"

    private val deviceUserCodeUrl = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    private val deviceTokenUrl = "https://auth.openai.com/api/accounts/deviceauth/token"
    private val tokenUrl = "https://auth.openai.com/oauth/token"
    private val verificationUri = "https://auth.openai.com/codex/device"
    private val deviceRedirectUri = "https://auth.openai.com/deviceauth/callback"
    private val jwtClaimPath = "https://api.openai.com/auth"

    /** Device-flow timeout, 15 minutes (pi's `DEVICE_CODE_TIMEOUT_SECONDS`). */
    private val flowTimeoutMillis = 15 * 60 * 1000L

    /** Minimum polling wait so a server-supplied 0 interval does not hot-loop. */
    private val minimumIntervalMillis = 1000L

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
        }.getOrElse { throw CodexOAuthException("Sign-in could not be started.") }

        if (body.status != HttpStatusCode.OK) {
            throw CodexOAuthException("Sign-in could not be started (HTTP ${body.status.value}).")
        }

        val parsed = body.bodyAsText().parseJsonObjectOrNull()
            ?: throw CodexOAuthException("Sign-in response was invalid.")

        val deviceAuthId = parsed.stringOrNull("device_auth_id")
        val userCode = parsed.stringOrNull("user_code")
        val interval = parsed.intervalSecondsOrNull()
        if (deviceAuthId == null || userCode == null || interval == null) {
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
                    return exchangeAuthorizationCode(result.authorizationCode, result.codeVerifier)
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
        }.getOrElse { throw CodexOAuthException("Sign-in could not be checked.") }

        if (response.status == HttpStatusCode.OK) {
            val parsed = response.bodyAsText().parseJsonObjectOrNull()
            val code = parsed?.stringOrNull("authorization_code")
            val verifier = parsed?.stringOrNull("code_verifier")
            if (code == null || verifier == null) {
                throw CodexOAuthException("Sign-in response was invalid.")
            }
            return DevicePollResult.Complete(code, verifier)
        }

        // 403/404 mean "not approved yet" (see pi's pollOpenAICodexDeviceAuth).
        if (response.status.value == 403 || response.status.value == 404) {
            return DevicePollResult.Pending
        }

        val errorCode = response.bodyAsText().parseJsonObjectOrNull()
            ?.get("error")
            ?.let { if (it is JsonObject) it.stringOrNull("code") else (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        return when (errorCode) {
            "deviceauth_authorization_pending" -> DevicePollResult.Pending
            "slow_down" -> DevicePollResult.SlowDown
            else -> DevicePollResult.Failed(response.status.value)
        }
    }

    /** Exchanges the device flow's authorization code for tokens (pi's `exchangeAuthorizationCode`). */
    private suspend fun exchangeAuthorizationCode(authorizationCode: String, codeVerifier: String): CodexTokens =
        requestTokens(
            "Sign-in could not be completed",
            Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("code", authorizationCode)
                append("code_verifier", codeVerifier)
                append("redirect_uri", deviceRedirectUri)
            },
        )

    /** Refreshes an expired access token (pi's `refreshAccessToken`). */
    suspend fun refresh(refreshToken: String): CodexTokens =
        requestTokens(
            "Sign-in could not be refreshed",
            Parameters.build {
                append("grant_type", "refresh_token")
                append("client_id", clientId)
                append("refresh_token", refreshToken)
            },
        )

    private suspend fun requestTokens(step: String, form: Parameters): CodexTokens {
        val response = runCatching {
            httpClient.post(tokenUrl) {
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody(FormDataContent(form))
            }
        }.getOrElse { throw CodexOAuthException("$step.") }

        if (response.status != HttpStatusCode.OK) {
            throw CodexOAuthException("$step (HTTP ${response.status.value}).")
        }

        val parsed = response.bodyAsText().parseJsonObjectOrNull()
            ?: throw CodexOAuthException("$step: response was invalid.")
        val accessToken = parsed.stringOrNull("access_token")
        val refreshToken = parsed.stringOrNull("refresh_token")
        val expiresInSeconds = parsed.get("expires_in")?.jsonPrimitive?.longOrNull
        if (accessToken == null || refreshToken == null || expiresInSeconds == null) {
            throw CodexOAuthException("$step: response was invalid.")
        }

        val accountId = accountIdFromJwt(accessToken)
            ?: throw CodexOAuthException("Account information could not be read from the sign-in response.")

        return CodexTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMillis = clock() + expiresInSeconds * 1000,
            accountId = accountId,
        )
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
