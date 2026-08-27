package works.resolve.distill.ai.auth.oauth

import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.distill.ai.auth.AuthEvent
import works.resolve.distill.ai.auth.AuthInteraction
import works.resolve.distill.ai.auth.AuthPrompt
import works.resolve.distill.ai.auth.ModelAuth
import works.resolve.distill.ai.auth.OAuthAuth
import works.resolve.distill.ai.auth.OAuthCredential
import works.resolve.distill.ai.auth.PkceGenerator

/**
 * Anthropic OAuth flow (Claude Pro/Max), ported from pi
 * `packages/ai/src/auth/oauth/anthropic.ts`.
 *
 * Mirrors the upstream file symbol-for-symbol: the constant set
 * ([CLIENT_ID], [AUTHORIZE_URL], [TOKEN_URL], [REDIRECT_URI], [SCOPES]),
 * `parseAuthorizationInput` ([parseAuthorizationInput]), `postJson`
 * ([postJson]), `exchangeAuthorizationCode` ([exchangeAuthorizationCode]),
 * `loginAnthropic` ([login]), `refreshAnthropicToken` ([refreshAnthropicToken]),
 * and the `anthropicOAuth` metadata (name/isSubscription/toAuth). Expiry is
 * `now + expires_in * 1000 - 5 * 60 * 1000` (the five-minute refresh skew)
 * in both the exchange and the refresh path; the refresh response's rotated
 * `refresh_token` replaces the stored one verbatim.
 *
 * Divergence from pi (documented per AGENTS.md): pi races a loopback HTTP
 * callback server (`node:http` on 127.0.0.1:53692, `PI_OAUTH_CALLBACK_HOST`
 * override) against the manual-code prompt; Android has neither a reachable
 * loopback browser context nor a trusted deep-link callback, so this port
 * keeps only the `AuthEvent.AuthUrl` + `AuthPrompt.ManualCode` leg of the
 * race. Everything else is preserved: the authorize URL still carries the
 * localhost `redirect_uri` and `state = verifier` (so a redirect URL pasted
 * back from a desktop browser validates exactly like pi), and the token
 * exchange still sends that same `redirect_uri`. Browsers are never opened
 * automatically — following [AuthEvent.AuthUrl] is an explicit user action.
 *
 * Other documented divergences:
 * - All HTTP goes through the injected [OAuthHttpClient] with pi's 30s
 *   bounded exchange (`AbortSignal.timeout(30_000)` → [REQUEST_TIMEOUT_MS]);
 *   cancellation travels as coroutine cancellation.
 * - pi's `formatErrorDetails` serializes Node error metadata (`code`,
 *   `errno`, `stack`); this port keeps its message shape with
 *   `ClassName: message` details.
 * - pi type-casts the token JSON unchecked (missing fields yield
 *   `undefined`); this port validates the required string/number fields and
 *   fails with an explicit field name, like the sibling xAI port.
 * - `Date.now()` is read through the [now] seam for deterministic expiry
 *   tests.
 *
 * - Secret-safety divergence (deliberate, per Distill's security rules): pi
 *   echoes the raw response body in its `HTTP request failed ... body=` and
 *   invalid-JSON messages; a body can carry tokens (a truncated token error,
 *   an echoed code/verifier), so this port never interpolates an unparseable
 *   raw body. Non-2xx bodies that parse as JSON objects contribute only their
 *   `error`/`error_description` strings (ordinary safe server error
 *   descriptors); anything else becomes `<redacted>`. Invalid-JSON messages
 *   carry no body at all.
 *
 * Nothing secret is logged: error messages carry only URLs without query
 */
class AnthropicOAuthAuth(
    private val http: OAuthHttpClient,
    private val pkce: PkceGenerator = PkceGenerator(),
    private val now: () -> Long = { System.currentTimeMillis() },
) : OAuthAuth {

    /** pi `name: "Anthropic (Claude Pro/Max)"`. */
    override val name: String = "Anthropic (Claude Pro/Max)"

    /** pi `isSubscription: true`. */
    override val isSubscription: Boolean = true

    // --- login (pi `loginAnthropic`) ---

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val challenge = pkce.generate()
        val verifier = challenge.verifier

        // pi's URLSearchParams insertion order — see formEncode's encoding
        // note.
        val authParams = linkedMapOf(
            "code" to "true",
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES,
            "code_challenge" to challenge.challenge,
            "code_challenge_method" to "S256",
            "state" to verifier,
        )
        interaction.notify(
            AuthEvent.AuthUrl(
                url = AUTHORIZE_URL + "?" + formEncode(authParams),
                instructions =
                    "Complete login in your browser. If the browser is on another machine, paste the final redirect URL here.",
            ),
        )

        val manualInput = interaction.prompt(
            AuthPrompt.ManualCode(
                message = "Complete login in your browser, or paste the authorization code / redirect URL here:",
                placeholder = REDIRECT_URI,
            ),
        )

        val parsed = parseAuthorizationInput(manualInput)
        // pi: `if (parsed.state && parsed.state !== verifier)` — only a non-empty state mismatches.
        if (!parsed.state.isNullOrEmpty() && parsed.state != verifier) {
            throw IllegalStateException("OAuth state mismatch")
        }
        // pi: `if (!code)` — an empty code is missing, checked before the state.
        val code = parsed.code?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Missing authorization code")
        // pi: `state = parsed.state ?? verifier` (nullish — an empty state stays empty),
        // then `if (!state)` throws. Unreachable in practice, kept for fidelity.
        val state = parsed.state ?: verifier
        if (state.isEmpty()) throw IllegalStateException("Missing OAuth state")

        interaction.notify(AuthEvent.Progress("Exchanging authorization code for tokens..."))
        return exchangeAuthorizationCode(code, state, verifier, REDIRECT_URI)
    }

    // --- authorization-input parsing (pi `parseAuthorizationInput`) ---

    /** pi's `{ code?, state? }` return shape. */
    internal data class ParsedAuthorizationInput(
        val code: String?,
        val state: String?,
    )

    /**
     * Extracts code/state from any user input form, mirroring pi's
     * `parseAuthorizationInput`: a full URL returns its `code`/`state` query
     * parameters; a `#`-separated value splits into `code#state`; a bare
     * query string containing `code=` is parsed as one; anything else is
     * taken as the raw code.
     */
    internal fun parseAuthorizationInput(input: String): ParsedAuthorizationInput {
        val value = input.trim()
        if (value.isEmpty()) return ParsedAuthorizationInput(null, null)

        try {
            val uri = URI(value)
            if (uri.scheme != null) {
                // Parsed as an absolute URL like pi's `new URL(value)`.
                val params = uri.rawQuery?.let(::parseQueryString) ?: emptyMap()
                return ParsedAuthorizationInput(params["code"], params["state"])
            }
        } catch (_: Exception) {
            // not a URL
        }

        if (value.contains("#")) {
            // pi: `value.split("#", 2)` — at most two segments; JS discards the
            // remainder, so the state is only up to the next `#`.
            val segments = value.split("#")
            val code = segments[0]
            val state = segments.getOrElse(1) { "" }
            return ParsedAuthorizationInput(code, state)
        }

        if (value.contains("code=")) {
            val params = parseQueryString(value) ?: emptyMap()
            return ParsedAuthorizationInput(params["code"], params["state"])
        }

        return ParsedAuthorizationInput(value, null)
    }

    /**
     * Minimal query-string parser with pi's `URLSearchParams` semantics
     * (`&`/`=` pairs, form decoding, first occurrence wins).
     */
    private fun parseQueryString(query: String): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            if (separator < 0) continue
            val name = pair.substring(0, separator).urlDecode()
            val decoded = pair.substring(separator + 1).urlDecode()
            if (!result.containsKey(name)) result[name] = decoded
        }
        return result
    }

    private fun String.urlDecode(): String =
        try {
            URLDecoder.decode(this, "UTF-8")
        } catch (_: IllegalArgumentException) {
            this
        }

    // --- token exchange (pi `exchangeAuthorizationCode`) ---

    internal suspend fun exchangeAuthorizationCode(
        code: String,
        state: String,
        verifier: String,
        redirectUri: String,
    ): OAuthCredential {
        val responseBody: String
        try {
            responseBody = postJson(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to CLIENT_ID,
                    "code" to code,
                    "state" to state,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to verifier,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException(
                "Token exchange request failed. url=$TOKEN_URL; redirect_uri=$redirectUri; " +
                    "response_type=authorization_code; details=${formatErrorDetails(error)}",
                error,
            )
        }

        val tokenData: JsonObject
        try {
            tokenData = Json.parseToJsonElement(responseBody) as? JsonObject
                ?: throw IllegalArgumentException("JSON is not an object")
        } catch (_: Exception) {
            // No formatErrorDetails here: the parser's message embeds the raw
            // input, which can carry secrets.
            throw IllegalStateException(
                "Token exchange returned invalid JSON. url=$TOKEN_URL; " +
                    "details=IllegalStateException: response body is not valid JSON",
            )
        }

        return credentialFrom(tokenData)
    }

    // --- refresh (pi `refreshAnthropicToken`) ---

    internal suspend fun refreshAnthropicToken(refreshToken: String): OAuthCredential {
        val responseBody: String
        try {
            responseBody = postJson(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to CLIENT_ID,
                    "refresh_token" to refreshToken,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException(
                "Anthropic token refresh request failed. url=$TOKEN_URL; details=${formatErrorDetails(error)}",
                error,
            )
        }

        val data: JsonObject
        try {
            data = Json.parseToJsonElement(responseBody) as? JsonObject
                ?: throw IllegalArgumentException("JSON is not an object")
        } catch (_: Exception) {
            // No formatErrorDetails here: the parser's message embeds the raw
            // input, which can carry secrets.
            throw IllegalStateException(
                "Anthropic token refresh returned invalid JSON. url=$TOKEN_URL; " +
                    "details=IllegalStateException: response body is not valid JSON",
            )
        }

        return credentialFrom(data)
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        refreshAnthropicToken(credential.refresh)

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth = ModelAuth(apiKey = credential.access)

    // --- response shaping ---

    /**
     * Shapes the token response into a credential. Both pi paths use the
     * same arithmetic: `Date.now() + expires_in * 1000 - 5 * 60 * 1000`.
     */
    private fun credentialFrom(body: JsonObject): OAuthCredential {
        val access = requiredString(body, "access_token")
        val refresh = requiredString(body, "refresh_token")
        val expiresInSeconds = (body["expires_in"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 }
            ?: throw IllegalStateException("Invalid Anthropic OAuth response field: expires_in")
        return OAuthCredential(
            access = access,
            refresh = refresh,
            expires = now() + (expiresInSeconds * 1000).toLong() - REFRESH_SKEW_MS,
        )
    }

    private fun requiredString(body: JsonObject, field: String): String {
        val value = (body[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (value.isNullOrEmpty()) {
            throw IllegalStateException("Invalid Anthropic OAuth response field: $field")
        }
        return value
    }

    /**
     * Port of pi `formatErrorDetails` reduced to its portable core: pi
     * serializes Node error metadata (`code`, `errno`, `stack`) that has no
     * Kotlin counterpart, so details are `ClassName: message`.
     */
    private fun formatErrorDetails(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: ""}"

    // --- HTTP (pi `postJson`) ---

    /**
     * Port of pi `postJson`: JSON POST to [TOKEN_URL] with the 30s bounded
     * exchange; a non-2xx response fails with pi's exact message (status,
     * URL, and the server's response body — never request secrets).
     */
    private suspend fun postJson(fields: Map<String, String>): String {
        val response = http.execute(
            OAuthHttpRequest(
                method = "POST",
                url = TOKEN_URL,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = jsonRequest(fields),
                timeoutMs = REQUEST_TIMEOUT_MS,
            ),
        )
        val responseBody = response.body.toString(Charsets.UTF_8)
        if (response.status !in 200..299) {
            // Divergence from pi (documented in the class KDoc): pi echoes the
            // raw body, which can carry tokens; only structured
            // `error`/`error_description` strings survive, anything else is
            // `<redacted>`.
            throw IllegalStateException(
                "HTTP request failed. status=${response.status}; url=$TOKEN_URL; body=${safeBodySummary(responseBody)}",
            )
        }
        return responseBody
    }

    /**
     * Sanitized `body=` summary for a non-2xx response: `error=` plus the
     * JSON object's `error`/`error_description` strings when present, else
     * `<redacted>` — an unparseable or non-error body is never interpolated.
     */
    private fun safeBodySummary(responseBody: String): String {
        val body = try {
            Json.parseToJsonElement(responseBody) as? JsonObject ?: return "<redacted>"
        } catch (_: Exception) {
            return "<redacted>"
        }
        val error = (body["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val description = (body["error_description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val detail = listOfNotNull(error, description).joinToString(": ")
        return if (detail.isNotEmpty()) "error=$detail" else "<redacted>"
    }

    private fun jsonRequest(fields: Map<String, String>): ByteArray =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                for ((name, value) in fields) put(name, value)
            },
        ).toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * pi decodes `atob("OWQxYzI1MGEtZTYxYi00NGQ5LTg4ZWQtNTk0NGQxOTYyZjVl")`;
         * this is that decoded UUID.
         */
        const val CLIENT_ID: String = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

        const val AUTHORIZE_URL: String = "https://claude.ai/oauth/authorize"

        const val TOKEN_URL: String = "https://platform.claude.com/v1/oauth/token"

        /**
         * pi `REDIRECT_URI` (`http://localhost:53692/callback`). The loopback
         * callback server is the dropped desktop leg; the URI itself stays in
         * the authorize URL and the token exchange so pasted redirect URLs
         * validate and Anthropic's redirect contract is unchanged.
         */
        const val REDIRECT_URI: String = "http://localhost:53692/callback"

        /** pi `SCOPES`, verbatim. */
        const val SCOPES: String =
            "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"

        /** pi `5 * 60 * 1000`: refresh five minutes before reported expiry. */
        const val REFRESH_SKEW_MS: Long = 5 * 60 * 1000

        /** pi `AbortSignal.timeout(30_000)` bounding every token exchange. */
        const val REQUEST_TIMEOUT_MS: Int = 30_000

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `application/x-www-form-urlencoded` serialization matching pi's
         * `new URLSearchParams(fields).toString()` byte for byte. The JDK
         * [java.net.URLEncoder] uses the same WHATWG form-urlencoded set:
         * alphanumerics, `*`, `-`, `.`, `_` stay bare, space becomes `+`, and
         * every other byte (including `~` → `%7E`) is percent-encoded.
         */
        internal fun formEncode(fields: Map<String, String>): String =
            fields.entries.joinToString("&") { (name, value) ->
                urlEncode(name) + "=" + urlEncode(value)
            }

        private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}
