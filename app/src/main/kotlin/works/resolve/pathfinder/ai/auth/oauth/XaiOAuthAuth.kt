package works.resolve.pathfinder.ai.auth.oauth

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential

/**
 * xAI OAuth device-code flow, ported from pi
 * `packages/ai/src/auth/oauth/xai.ts`.
 *
 * Mirrors the upstream file symbol-for-symbol: `loginXai` ([login]),
 * `refreshXaiToken` ([refresh]), `requestDeviceCode`/`parseDeviceCode`,
 * `pollForTokens`, `credentialsFromTokenResponse`, `postForm`,
 * `requiredString`, `positiveNumber`, `validateVerificationUri`, and
 * `requestFailure`. Polling runs through the shared [pollOAuthDeviceCodeFlow]
 * (pi `pollOAuthDeviceCodeFlow` in `device-code.ts`) with
 * `waitBeforeFirstPoll = true`.
 *
 * Divergence from pi (documented per AGENTS.md): pi posts via `fetch` with an
 * `AbortSignal`; here all HTTP goes through the injected [OAuthHttpClient]
 * with a bounded request timeout, and cancellation travels as coroutine
 * cancellation. `Date.now()` in `credentialsFromTokenResponse` is read
 * through the internal [now] seam (system clock by default) for deterministic
 * expiry tests. [AuthEvent.DeviceCode] carries `Int` interval/expiry fields,
 * so the server's (possibly fractional) interval is floored when emitted —
 * the poller itself floors the same value anyway.
 *
 * Nothing secret is ever logged or placed in exception messages: errors carry
 * only HTTP statuses and server-provided `error`/`error_description` strings.
 */
class XaiOAuthAuth(
    private val http: OAuthHttpClient,
    private val now: () -> Long = { System.currentTimeMillis() },
) : OAuthAuth {

    override val name: String = "xAI (Grok/X subscription)"

    /** pi `isSubscription: true`. */
    override val isSubscription: Boolean = true

    /** pi `loginLabel: "Sign in with SuperGrok or X Premium"`. */
    override val loginLabel: String = "Sign in with SuperGrok or X Premium"

    // --- login / refresh (pi `loginXai` / `refreshXaiToken`) ---

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

    // --- device authorization (pi `requestDeviceCode` + `parseDeviceCode`) ---

    /** Port of pi `XaiDeviceCode`. */
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
     * Port of pi `parseDeviceCode`. RFC 8628 allows interval 0 (no minimum
     * wait); non-positive or malformed values fall back to `null` so the
     * poller applies its default instead of failing.
     */
    private fun parseDeviceCode(body: JsonObject): XaiDeviceCode {
        val interval = (body["interval"] as? JsonPrimitive)?.takeIf { it.isString.not() && it.content.isNotEmpty() }
        val intervalSeconds =
            interval?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        val verificationUriComplete =
            (body["verification_uri_complete"] as? JsonPrimitive)
                ?.takeIf { it.isString && it.content.isNotEmpty() }
                ?.let { validateVerificationUri(it.content) }
        return XaiDeviceCode(
            deviceCode = requiredString(body, "device_code"),
            userCode = requiredString(body, "user_code"),
            verificationUri = validateVerificationUri(requiredString(body, "verification_uri")),
            verificationUriComplete = verificationUriComplete,
            intervalSeconds = intervalSeconds,
            expiresInSeconds = positiveNumber(body, "expires_in"),
        )
    }

    // --- token polling (pi `pollForTokens`) ---

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
                        when (val error = (response.body["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                        "authorization_pending" -> OAuthDeviceCodePollResult.Pending
                        "slow_down" -> OAuthDeviceCodePollResult.SlowDown(
                            intervalSeconds = numberField(response.body, "interval"),
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
            now = now,
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

    // --- response shaping ---

    /**
     * Port of pi `credentialsFromTokenResponse`. xAI may omit
     * `refresh_token` on refresh when the token is not rotated, in which case
     * the previous refresh token is retained.
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
            expires = now() + expiresInSeconds * 1000 - REFRESH_SKEW_MS,
        )
    }

    internal suspend fun requestDeviceCodeForTest(): XaiDeviceCode = requestDeviceCode()

    /** Test seam: parses a raw JSON body via [parseDeviceCode]. */
    internal fun parseDeviceCodeForTest(body: String): XaiDeviceCode =
        parseDeviceCode(Json.parseToJsonElement(body) as JsonObject)

    /** Port of pi `requiredString`. */
    private fun requiredString(body: JsonObject, field: String): String {
        val value = (body[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (value.isNullOrEmpty()) {
            throw IllegalStateException("Invalid xAI OAuth response field: $field")
        }
        return value
    }

    /** Port of pi `positiveNumber`. */
    private fun positiveNumber(body: JsonObject, field: String): Long {
        val value = numberField(body, field) ?: throw invalidField(field)
        if (value <= 0) throw invalidField(field)
        return value.toLong()
    }

    private fun numberField(body: JsonObject, field: String): Double? =
        (body[field] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }

    private fun invalidField(field: String): IllegalStateException =
        IllegalStateException("Invalid xAI OAuth response field: $field")

    /**
     * Port of pi `validateVerificationUri`. The verification URI is opened in
     * the user's browser; force it to be an https URL so a malicious response
     * cannot make `open` launch something else.
     *
     * Divergence from pi (documented per AGENTS.md): pi returns the
     * WHATWG-normalized `new URL(raw).href` (lower-cased host, default port
     * `:443` removed, empty path becomes `/`). The JDK has no href-equivalent
     * normalizer (`java.net.URL.toExternalForm` preserves host case and the
     * default port; `java.net.URI.toString` preserves the raw form), so this
     * port returns the parsed URI's string as-is after the same trust check.
     * The check itself is equally strict-or-stricter: anything `URI` rejects
     * (and WHATWG would accept) is treated as untrusted rather than opened.
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

    // --- HTTP (pi `postForm` / `requestFailure`) ---

    /** pi's `{ ok, status, body }` postForm result. */
    private data class FormResponse(
        val ok: Boolean,
        val status: Int,
        val body: JsonObject,
    )

    /**
     * Port of pi `postForm`: form-url-encoded POST expecting JSON. A network
     * failure propagates; invalid JSON throws pi's message. Non-object JSON
     * bodies (arrays, scalars, `null`) become the empty object like upstream.
     */
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
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw IllegalStateException("xAI OAuth returned invalid JSON (HTTP ${response.status})")
        }
        return FormResponse(
            ok = response.status in 200..299,
            status = response.status,
            body = parsed as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /** Port of pi `requestFailure` (message verbatim, no secrets). */
    private fun requestFailure(action: String, response: FormResponse): IllegalStateException {
        val error = (response.body["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val description =
            (response.body["error_description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val detail = listOfNotNull(error, description).joinToString(": ")
        return IllegalStateException(
            "xAI OAuth $action failed (HTTP ${response.status})${if (detail.isNotEmpty()) ": $detail" else ""}",
        )
    }

    companion object {
        /** pi `XAI_CLIENT_ID`. */
        const val CLIENT_ID: String = "b1a00492-073a-47ea-816f-4c329264a828"

        /** pi `XAI_SCOPE`. */
        const val SCOPE: String = "openid profile email offline_access grok-cli:access api:access"

        /** pi `XAI_DEVICE_CODE_URL`. */
        const val DEVICE_CODE_URL: String = "https://auth.x.ai/oauth2/device/code"

        /** pi `XAI_TOKEN_URL`. */
        const val TOKEN_URL: String = "https://auth.x.ai/oauth2/token"

        /** pi `referrer: "pi"` in the device-code request; `pathfinder` here (owner decision, like the User-Agent product token). */
        const val REFERRER: String = "pathfinder"

        /** pi device grant type (RFC 8628 section 3.4). */
        const val DEVICE_GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:device_code"

        /** pi `REFRESH_SKEW_MS`: refresh slightly before reported expiry. */
        const val REFRESH_SKEW_MS: Long = 5 * 60 * 1000

        /** pi `DEFAULT_TOKEN_LIFETIME_SECONDS`. */
        const val DEFAULT_TOKEN_LIFETIME_SECONDS: Long = 3600

        /** Bounded connect+read timeout for every OAuth exchange (pi relies on fetch; Pathfinder bounds it). */
        const val REQUEST_TIMEOUT_MS: Int = 30_000

        /**
         * `application/x-www-form-urlencoded` serialization matching pi's
         * `new URLSearchParams(fields)` exactly, via the JDK
         * [java.net.URLEncoder]: it encodes supplementary code points as their
         * full UTF-8 sequences, `~` as `%7E`, and spaces as `+`, and like
         * URLSearchParams it leaves `*`, `.-_`, and alphanumerics unescaped —
         * no post-processing needed.
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
