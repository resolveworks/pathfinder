package works.resolve.pathfinder.ai.auth.oauth

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.string
import kotlin.time.Clock
import works.resolve.pathfinder.ai.utils.strictDouble

/**
 * xAI OAuth device-code flow (RFC 8628): the user approves at the verification
 * URL while the token endpoint is polled until the authorization completes,
 * is denied, or expires.
 *
 * Divergences from pi:
 * - All HTTP goes through the injected [OAuthHttpClient] with a bounded
 *   request timeout; cancellation travels as coroutine cancellation.
 * - [AuthEvent.DeviceCode] carries `Int` interval/expiry fields, so the
 *   server's possibly fractional interval is floored when emitted — the
 *   poller floors the same value anyway.
 * - `Date.now()` reads through the injected [clock] (system clock by
 *   default) for deterministic expiry tests.
 *
 * Nothing secret is ever logged or placed in exception messages: errors carry
 * only HTTP statuses and server-provided `error`/`error_description` strings.
 */
class XaiOAuthAuth(
    private val http: OAuthHttpClient,
    private val clock: Clock = Clock.System,
) : OAuthAuth {

    override val name: String = "xAI (Grok/X subscription)"

    override val isSubscription: Boolean = true

    override val loginLabel: String = "Sign in with SuperGrok or X Premium"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val device = requestDeviceCode()
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = device.verificationUriComplete ?: device.verificationUri,
                intervalSeconds = device.intervalSeconds?.toInt(),
                expiresInSeconds = device.expiresInSeconds.toInt(),
            ),
        )
        return pollForTokens(device)
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        refreshXaiToken(credential.refresh)

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth = ModelAuth(apiKey = credential.access)

    internal data class XaiDeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val intervalSeconds: Double?,
        val expiresInSeconds: Long,
    )

    internal suspend fun requestDeviceCode(): XaiDeviceCode {
        val response = postForm(
            DEVICE_CODE_URL,
            mapOf(
                "client_id" to CLIENT_ID,
                "scope" to SCOPE,
                "referrer" to REFERRER,
            ),
        )
        if (!response.ok) {
            throw requestFailure("device authorization", response)
        }
        return parseDeviceCode(response.body)
    }

    /**
     * RFC 8628 allows interval 0 (no minimum wait); non-positive or
     * malformed values fall back to `null` so the poller applies its
     * default instead of failing.
     */
    private fun parseDeviceCode(body: JsonObject): XaiDeviceCode {
        val intervalSeconds = body.strictDouble("interval")?.takeIf { it > 0 }
        val verificationUriComplete =
            body.string("verification_uri_complete")
                ?.takeIf { it.isNotEmpty() }
                ?.let { validateVerificationUri(it) }
        return XaiDeviceCode(
            deviceCode = requiredString(body, "device_code"),
            userCode = requiredString(body, "user_code"),
            verificationUri = validateVerificationUri(requiredString(body, "verification_uri")),
            verificationUriComplete = verificationUriComplete,
            intervalSeconds = intervalSeconds,
            expiresInSeconds = positiveNumber(body, "expires_in"),
        )
    }

    private suspend fun pollForTokens(device: XaiDeviceCode): OAuthCredential =
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = device.expiresInSeconds,
                waitBeforeFirstPoll = true,
                poll = {
                    val response = postForm(
                        TOKEN_URL,
                        mapOf(
                            "grant_type" to DEVICE_GRANT_TYPE,
                            "client_id" to CLIENT_ID,
                            "device_code" to device.deviceCode,
                        ),
                    )

                    if (response.ok) {
                        OAuthDeviceCodePollResult.Complete(
                            credentialsFromTokenResponse(response.body),
                        )
                    } else {
                        when (val error = response.body.string("error")) {
                        "authorization_pending" -> OAuthDeviceCodePollResult.Pending
                        "slow_down" -> OAuthDeviceCodePollResult.SlowDown(
                            intervalSeconds = response.body.strictDouble("interval"),
                        )
                        "access_denied", "authorization_denied" ->
                            OAuthDeviceCodePollResult.Failed("xAI device authorization was denied")
                        "expired_token" -> OAuthDeviceCodePollResult.Failed("xAI device code expired")
                        else -> OAuthDeviceCodePollResult.Failed(
                            requestFailure("device token polling", response).message!!,
                        )
                        }
                    }
                },
            ),
            clock = clock,
        )

    private suspend fun refreshXaiToken(refreshToken: String): OAuthCredential {
        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "refresh_token" to refreshToken,
            ),
        )
        if (!response.ok) {
            throw requestFailure("token refresh", response)
        }
        return credentialsFromTokenResponse(response.body, refreshToken)
    }

    /**
     * xAI may omit `refresh_token` on refresh when the token is not rotated,
     * in which case the previous refresh token is retained.
     */
    internal fun credentialsFromTokenResponse(body: JsonObject, previousRefreshToken: String? = null): OAuthCredential {
        val access = requiredString(body, "access_token")
        val refresh =
            if (body["refresh_token"] == null && !previousRefreshToken.isNullOrEmpty()) previousRefreshToken
            else requiredString(body, "refresh_token")
        val expiresInSeconds =
            if (body["expires_in"] == null) DEFAULT_TOKEN_LIFETIME_SECONDS
            else positiveNumber(body, "expires_in")
        return OAuthCredential(
            access = access,
            refresh = refresh,
            expires = clock.now().toEpochMilliseconds() + expiresInSeconds * 1000 - REFRESH_SKEW_MS,
        )
    }

    internal suspend fun requestDeviceCodeForTest(): XaiDeviceCode = requestDeviceCode()

    /** Test seam: parses a raw JSON body via [parseDeviceCode]. */
    internal fun parseDeviceCodeForTest(body: String): XaiDeviceCode =
        parseDeviceCode(lenientJson.parseToJsonElement(body) as JsonObject)

    private fun requiredString(body: JsonObject, field: String): String {
        val value = body.string(field)
        if (value.isNullOrEmpty()) {
            throw IllegalStateException("Invalid xAI OAuth response field: $field")
        }
        return value
    }

    private fun positiveNumber(body: JsonObject, field: String): Long {
        val value = body.strictDouble(field) ?: throw invalidField(field)
        if (value <= 0) throw invalidField(field)
        return value.toLong()
    }

    private fun invalidField(field: String): IllegalStateException =
        IllegalStateException("Invalid xAI OAuth response field: $field")

    /**
     * The verification URI is opened in the user's browser; force it to be
     * an https URL so a malicious response cannot make `open` launch
     * something else.
     *
     * Divergence from pi: pi returns the WHATWG-normalized
     * `new URL(raw).href` (lower-cased host, default port removed, empty
     * path becomes `/`); the JDK has no href-equivalent normalizer, so the
     * parsed URI's string is returned as-is. The trust check errs strict:
     * anything `URI` rejects (and WHATWG would accept) is treated as
     * untrusted rather than opened.
     */
    private fun validateVerificationUri(raw: String): String {
        val url = try {
            java.net.URI(raw)
        } catch (_: Exception) {
            throw IllegalStateException("Untrusted verification URI in xAI OAuth response")
        }
        if (url.scheme != "https" || url.host == null) {
            throw IllegalStateException("Untrusted verification URI in xAI OAuth response")
        }
        return url.toString()
    }

    private data class FormResponse(
        val ok: Boolean,
        val status: Int,
        val body: JsonObject,
    )

    private suspend fun postForm(url: String, fields: Map<String, String>): FormResponse {
        val response: OAuthHttpResponse
        try {
            response = http.execute(
                OAuthHttpRequest(
                    method = "POST",
                    url = url,
                    headers = mapOf(
                        "accept" to "application/json",
                        "content-type" to "application/x-www-form-urlencoded",
                    ),
                    body = formUrlEncode(fields),
                    timeoutMs = REQUEST_TIMEOUT_MS,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        }

        val text = response.body.toString(Charsets.UTF_8)
        val parsed = try {
            lenientJson.parseToJsonElement(text)
        } catch (_: Exception) {
            throw IllegalStateException("xAI OAuth returned invalid JSON (HTTP ${response.status})")
        }
        return FormResponse(
            ok = response.status in 200..299,
            status = response.status,
            body = parsed as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    private fun requestFailure(action: String, response: FormResponse): IllegalStateException {
        val error = response.body.string("error")
        val description = response.body.string("error_description")
        val detail = listOfNotNull(error, description).joinToString(": ")
        return IllegalStateException(
            "xAI OAuth $action failed (HTTP ${response.status})${if (detail.isNotEmpty()) ": $detail" else ""}",
        )
    }

    companion object {
        const val CLIENT_ID: String = "b1a00492-073a-47ea-816f-4c329264a828"

        const val SCOPE: String = "openid profile email offline_access grok-cli:access api:access"

        const val DEVICE_CODE_URL: String = "https://auth.x.ai/oauth2/device/code"

        const val TOKEN_URL: String = "https://auth.x.ai/oauth2/token"

        /** Divergence from pi: upstream sends `referrer: "pi"`; `pathfinder` here (owner decision). */
        const val REFERRER: String = "pathfinder"

        const val DEVICE_GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:device_code"

        /** Refresh slightly before reported expiry so a token doesn't die mid-request. */
        const val REFRESH_SKEW_MS: Long = 5 * 60 * 1000

        const val DEFAULT_TOKEN_LIFETIME_SECONDS: Long = 3600

        const val REQUEST_TIMEOUT_MS: Int = 30_000

        /**
         * The JDK [java.net.URLEncoder] uses the WHATWG form-urlencoded set
         * (as `URLSearchParams` does): alphanumerics, `*`, `-`, `.`, `_`
         * stay bare, space becomes `+`, and every other byte (including `~`
         * → `%7E`) is percent-encoded.
         */
        internal fun formUrlEncode(fields: Map<String, String>): ByteArray {
            val out = StringBuilder()
            for ((index, entry) in fields.entries.withIndex()) {
                if (index > 0) out.append('&')
                encodeTo(out, entry.key)
                out.append('=')
                encodeTo(out, entry.value)
            }
            return out.toString().toByteArray(Charsets.UTF_8)
        }

        private fun encodeTo(out: StringBuilder, value: String) {
            out.append(java.net.URLEncoder.encode(value, "UTF-8"))
        }
    }
}
