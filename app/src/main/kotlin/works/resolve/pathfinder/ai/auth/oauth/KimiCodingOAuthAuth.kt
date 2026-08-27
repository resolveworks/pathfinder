package works.resolve.pathfinder.ai.auth.oauth

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.coroutines.coroutineContext
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential

/**
 * Kimi Code (subscription) OAuth flow, ported from pi
 * `packages/ai/src/auth/oauth/kimi-coding.ts`.
 *
 * RFC 8628 device authorization grant against `https://auth.kimi.com` with
 * JSON responses. The access token authenticates requests to
 * `https://api.kimi.com/coding` as an `Authorization: Bearer` header.
 *
 * Divergence from pi (documented per AGENTS.md): pi resolves the OAuth host
 * from `KIMI_CODE_OAUTH_HOST`/`KIMI_OAUTH_HOST` provider env overrides; the
 * Android process has no ambient provider env at this layer, so this port
 * always uses pi's default host [OAUTH_HOST] (the upstream default
 * when neither override is set). The [now] clock supplier is injectable for
 * deterministic tests only; production callers use the system-clock default.
 *
 * HTTP goes through the injected [OAuthHttpClient]; no network happens in
 * tests. Nothing secret is logged: exceptions carry only statuses and
 * server-provided error details.
 */
class KimiCodingOAuthAuth(
    private val http: OAuthHttpClient,
    private val now: () -> Long = { System.currentTimeMillis() },
) : OAuthAuth {

    override val name: String = "Kimi Code (subscription)"
    override val isSubscription: Boolean = true
    override val loginLabel: String = "Sign in with Kimi Code"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val device = startDeviceAuthorization()
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = device.verificationUriComplete,
                intervalSeconds = device.intervalSeconds.toInt(),
                expiresInSeconds = device.expiresInSeconds.toInt(),
            ),
        )
        val token = pollForToken(device)
        return token.toCredential()
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        refreshToken(credential.refresh).toCredential()

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(headers = mapOf("Authorization" to "Bearer ${credential.access}"))

    /**
     * Port of pi `DeviceAuthorization`.
     */
    internal data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val intervalSeconds: Double,
        val expiresInSeconds: Long,
    )

    /**
     * Port of pi `TokenResponse`. `expires` mirrors pi's
     * `Date.now() + expiresIn * 1000`, computed through the injectable clock.
     */
    internal data class TokenResponse(
        val access: String,
        val refresh: String,
        val expires: Long,
    )

    /** Port of pi `startDeviceAuthorization`. */
    private suspend fun startDeviceAuthorization(): DeviceAuthorization {
        val response = http.execute(
            OAuthHttpRequest(
                method = "POST",
                url = "$OAUTH_HOST/api/oauth/device_authorization",
                headers = FORM_HEADERS,
                body = formUrlEncode(mapOf("client_id" to CLIENT_ID)),
                timeoutMs = REQUEST_TIMEOUT_MS,
            ),
        )

        if (response.status !in 200..299) {
            throw IllegalStateException(
                "Kimi Code device authorization failed with status ${response.status}${response.errorTextSuffix()}",
            )
        }

        val json = readJson(response)
        val deviceCode = json?.string("device_code")
        val userCode = json?.string("user_code")
        val verificationUri = json?.string("verification_uri")
        val verificationUriComplete = json?.string("verification_uri_complete")
        if (deviceCode == null || userCode == null || verificationUri == null || verificationUriComplete == null ||
            trustedHttpUrl(verificationUriComplete) == null || trustedHttpUrl(verificationUri) == null
        ) {
            throw IllegalStateException(
                "Invalid Kimi Code device authorization response: ${json.jsonString()}",
            )
        }

        val interval = json.number("interval")
        val expiresIn = json.number("expires_in")
        return DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = verificationUriComplete,
            intervalSeconds = if (interval != null && interval.isFinite() && interval > 0) interval else DEFAULT_POLL_INTERVAL_SECONDS.toDouble(),
            expiresInSeconds = if (expiresIn != null && expiresIn.isFinite() && expiresIn > 0) expiresIn.toLong() else DEVICE_CODE_TIMEOUT_SECONDS,
        )
    }

    /** Port of pi `parseTokenResponse`. */
    internal fun parseTokenResponse(json: JsonElement?, operation: String): TokenResponse {
        val accessToken = json?.string("access_token")
        val refreshToken = json?.string("refresh_token")
        val expiresIn = json?.number("expires_in")
        if (accessToken.isNullOrEmpty() || refreshToken.isNullOrEmpty() ||
            expiresIn == null || !expiresIn.isFinite() || expiresIn <= 0
        ) {
            throw IllegalStateException(
                "Kimi Code token $operation response missing fields: ${json.jsonString()}",
            )
        }
        return TokenResponse(
            access = accessToken,
            refresh = refreshToken,
            expires = now() + (expiresIn * 1000).toLong(),
        )
    }

    /** Port of pi `pollForToken` via the shared [pollOAuthDeviceCodeFlow]. */
    private suspend fun pollForToken(device: DeviceAuthorization): TokenResponse =
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = device.expiresInSeconds,
                waitBeforeFirstPoll = true,
                poll = { pollTokenRequest(device) },
            ),
            now = now,
        )

    /**
     * One token poll: port of the `poll` callback pi passes to
     * `pollOAuthDeviceCodeFlow`. Classifies each response into the shared
     * poll result statuses (pending / slow_down / expired / denied / failed /
     * complete) with pi's messages verbatim.
     */
    private suspend fun pollTokenRequest(device: DeviceAuthorization): OAuthDeviceCodePollResult<TokenResponse> {
        val response = http.execute(
            OAuthHttpRequest(
                method = "POST",
                url = "$OAUTH_HOST/api/oauth/token",
                headers = FORM_HEADERS,
                body = formUrlEncode(
                    mapOf(
                        "client_id" to CLIENT_ID,
                        "device_code" to device.deviceCode,
                        "grant_type" to DEVICE_CODE_GRANT_TYPE,
                    ),
                ),
                timeoutMs = REQUEST_TIMEOUT_MS,
            ),
        )

        if (response.status >= 500) {
            return OAuthDeviceCodePollResult.Failed(
                "Kimi Code device token request failed with status ${response.status}${response.errorTextSuffix()}",
            )
        }

        val json = readJson(response)
        if (response.status in 200..299 && json?.string("access_token") != null) {
            return try {
                OAuthDeviceCodePollResult.Complete(parseTokenResponse(json, "poll"))
            } catch (error: IllegalStateException) {
                OAuthDeviceCodePollResult.Failed(error.message ?: error.toString())
            }
        }

        val error = json?.string("error")
        val description = json?.string("error_description")?.let { ": $it" } ?: ""
        return when (error) {
            "authorization_pending" -> OAuthDeviceCodePollResult.Pending
            "slow_down" -> {
                val interval = json.number("interval")
                OAuthDeviceCodePollResult.SlowDown(
                    if (interval != null && interval > 0) interval else null,
                )
            }
            "expired_token" -> OAuthDeviceCodePollResult.Failed(
                "Kimi Code device authorization expired. Please restart login.",
            )
            "access_denied" -> OAuthDeviceCodePollResult.Failed("Kimi Code login was denied.")
            else -> OAuthDeviceCodePollResult.Failed(
                "Kimi Code device token request failed (status ${response.status})" +
                    (error?.let { ": $it$description" } ?: ""),
            )
        }
    }

    /**
     * Port of pi `refreshToken`: up to [REFRESH_MAX_RETRIES] retries with
     * 1s/2s/4s backoff for network failures, 429, and 5xx; 401/403 and
     * `invalid_grant` fail immediately (the stored credential is dead and the
     * orchestrator must clear it and prompt re-login).
     *
     * Cancellation: pi checks `signal.aborted` after each backoff sleep and
     * its `sleep` rejects on abort; here [delay] throws
     * [CancellationException] promptly, and [ensureActive] performs pi's
     * explicit check before each attempt.
     */
    internal suspend fun refreshToken(refreshTokenValue: String): TokenResponse {
        var lastError: IllegalStateException? = null
        for (attempt in 0..REFRESH_MAX_RETRIES) {
            if (attempt > 0) {
                delay(1000L shl (attempt - 1))
                coroutineContext.ensureActive()
                // pi: throw new Error("Kimi Code token refresh aborted") when the signal is aborted.
            }

            val response: OAuthHttpResponse
            try {
                response = http.execute(
                    OAuthHttpRequest(
                        method = "POST",
                        url = "$OAUTH_HOST/api/oauth/token",
                        headers = FORM_HEADERS,
                        body = formUrlEncode(
                            mapOf(
                                "client_id" to CLIENT_ID,
                                "grant_type" to "refresh_token",
                                "refresh_token" to refreshTokenValue,
                            ),
                        ),
                        timeoutMs = REQUEST_TIMEOUT_MS,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                // pi catches any fetch error (including its abort timeout) and retries.
                lastError = IllegalStateException(error.message ?: error.toString(), error)
                continue
            }

            val json = readJson(response)
            if (response.status in 200..299) {
                return parseTokenResponse(json, "refresh")
            }

            // Unauthorized: the stored credential is dead; Models clears it and prompts re-login.
            if (response.status == 401 || response.status == 403 || json?.string("error") == "invalid_grant") {
                val description = json?.string("error_description")?.let { ": $it" } ?: ""
                throw IllegalStateException(
                    "Kimi Code token refresh unauthorized (status ${response.status})$description",
                )
            }

            if (isRetryableRefreshFailure(response.status) && attempt < REFRESH_MAX_RETRIES) {
                lastError = IllegalStateException("Kimi Code token refresh failed with status ${response.status}")
                continue
            }

            val text = json.jsonString()
            throw IllegalStateException(
                "Kimi Code token refresh failed with status ${response.status}${if (text.isNotEmpty()) ": $text" else ""}",
            )
        }

        throw lastError ?: IllegalStateException("Kimi Code token refresh failed")
    }

    private fun TokenResponse.toCredential(): OAuthCredential =
        OAuthCredential(access = access, refresh = refresh, expires = expires)

    companion object {
        /** pi `CLIENT_ID`. */
        const val CLIENT_ID: String = "17e5f671-d194-4dfb-9706-5516cb48c098"

        /** pi `DEFAULT_OAUTH_HOST` (trailing slashes stripped like pi's `getOauthHost`). */
        const val OAUTH_HOST: String = "https://auth.kimi.com"

        /** pi `DEVICE_CODE_TIMEOUT_SECONDS`. */
        const val DEVICE_CODE_TIMEOUT_SECONDS: Long = 15 * 60

        /** pi `DEFAULT_POLL_INTERVAL_SECONDS`. */
        const val DEFAULT_POLL_INTERVAL_SECONDS: Int = 5

        /** pi `REQUEST_TIMEOUT_MS`, the bound on every exchange. */
        const val REQUEST_TIMEOUT_MS: Int = 30_000

        /** pi `REFRESH_MAX_RETRIES`. */
        const val REFRESH_MAX_RETRIES: Int = 3

        /** RFC 8628 device-code grant type URI. */
        private const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        /** pi sends `Content-Type` + `Accept` on every form POST. */
        private val FORM_HEADERS = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json",
        )

        /** Port of pi `isRetryableRefreshFailure`. */
        internal fun isRetryableRefreshFailure(status: Int): Boolean = status == 429 || status >= 500

        /**
         * Port of pi `formUrlEncode` (`new URLSearchParams(fields).toString()`):
         * application/x-www-form-urlencoded serialization — space becomes `+`,
         * exactly like `URLSearchParams` (and `URLEncoder`) on the wire.
         */
        internal fun formUrlEncode(fields: Map<String, String>): ByteArray =
            fields.entries.joinToString("&") { (name, value) ->
                urlEncode(name) + "=" + urlEncode(value)
            }.toByteArray(Charsets.UTF_8)

        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

        /**
         * Port of pi `readJson`: JS `typeof [] === "object"`, so any non-null
         * JSON object *or array* is returned as-is (pi keeps it for
         * `JSON.stringify` in malformed-response errors); scalars and
         * unparseable bodies become null. Field access still only succeeds on
         * [JsonObject], matching pi's `json?.field` → `undefined` on arrays.
         */
        internal fun readJson(response: OAuthHttpResponse): JsonElement? {
            val parsed = try {
                Json.parseToJsonElement(response.body.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                return null
            }
            return if (parsed is JsonObject || parsed is kotlinx.serialization.json.JsonArray) parsed else null
        }

        /**
         * The verification URI is opened in the user's browser; only http(s)
         * URLs are trusted (pi `trustedHttpUrl`). Like pi's `url.href` return,
         * the value is the normalized URL form, rebuilt from the parsed [URI]:
         * lowercase scheme, root path `/` for empty paths, and default ports
         * `:80`/`:443` omitted.
         *
         * Divergence from pi (documented per AGENTS.md): WHATWG URL accepts
         * authority-less/opaque forms like `https:foo`; [URI] (and this port)
         * reject them by requiring a non-empty host, because a provider device
         * authorization response should always carry an absolute verification
         * URL. This is the narrow safety boundary.
         */
        internal fun trustedHttpUrl(value: String?): String? {
            if (value.isNullOrEmpty()) return null
            return try {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()
                val port = uri.port
                if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) {
                    null
                } else {
                    buildString {
                        append(scheme).append("://")
                        uri.rawUserInfo?.let { append(it).append('@') }
                        append(host)
                        if (port != -1 && !(scheme == "http" && port == 80) && !(scheme == "https" && port == 443)) {
                            append(':').append(port)
                        }
                        append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                        uri.rawQuery?.let { append('?').append(it) }
                        uri.rawFragment?.let { append('#').append(it) }
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        /** pi reads string fields with `typeof === "string"`; arrays have no fields (→ `undefined`). */
        private fun JsonElement?.string(name: String): String? =
            ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * pi reads numeric fields with `typeof === "number"` +
         * `Number.isFinite`; quoted numeric strings (JSON string primitives)
         * are rejected so callers apply their fallbacks, exactly like pi.
         */
        private fun JsonElement?.number(name: String): Double? =
            ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

        /** pi's `JSON.stringify(json)` rendering (`"null"` for null, compact JSON otherwise). */
        private fun JsonElement?.jsonString(): String = this?.toString() ?: "null"

        /** pi's `: ${await response.text()}` suffix for error bodies. */
        private fun OAuthHttpResponse.errorTextSuffix(): String {
            val text = body.toString(Charsets.UTF_8)
            return if (text.isNotEmpty()) ": $text" else ""
        }
    }
}
