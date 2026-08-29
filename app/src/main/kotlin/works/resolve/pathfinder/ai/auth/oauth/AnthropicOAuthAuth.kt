package works.resolve.pathfinder.ai.auth.oauth

import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * Loopback callback race, ported from pi `startCallbackServer` +
 * `loginAnthropic`: a loopback HTTP server on 127.0.0.1:53692 races the
 * `AuthPrompt.ManualCode` prompt. The handler validates in pi's order —
 * non-`/callback` path → 404, `error` param → 400, missing `code`/`state` →
 * 400, state mismatch → 400, success → the success page plus settle
 * `{code, state}` — where the expected state is the PKCE verifier (`state =
 * verifier` in the authorize URL). A server result wins outright and cancels
 * the pending manual prompt; otherwise the manual answer goes through
 * `parseAuthorizationInput` and the same state checks. Divergences from pi
 * (documented per AGENTS.md):
 * - pi's in-handler catch renders a text/plain 500; this handler cannot
 *   realistically throw (the shared transport pre-parses the request), and
 *   [LoopbackOAuthServer]'s uniform HTML 500 covers the impossible case.
 * - Bind failure fails the login outright like pi's rejecting
 *   `server.on("error")` (unlike the sibling Codex flow, which degrades to
 *   manual login). pi surfaces Node's `EADDRINUSE` errno; this port has no
 *   Node error metadata and throws a plain `IllegalStateException` naming
 *   the failed loopback bind.
 * - pi abandons the pending manual promise when the server wins; here the
 *   prompt coroutine is cancelled, which is what clears the pending prompt
 *   sheet (`UiAuthInteraction.prompt` clears its state in `finally`).
 * - pi's `PI_OAUTH_CALLBACK_HOST` env override does not exist on Android;
 *   the host is the `127.0.0.1` constant. Android apps share the device
 *   network namespace, so the socket is reachable from the on-device browser
 *   (Chrome/Vanadium allow cleartext `http://localhost`). Process-death
 *   caveat: Android may kill the app process while the browser is
 *   foregrounded; the login is simply retried — the same risk class as pi's
 *   abortable login.
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
 * - Secret-safety divergence (deliberate, per Pathfinder's security rules): pi
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
    /**
     * Bind port for the loopback callback server. pi's fixed 53692 in
     * production ([CALLBACK_PORT]); tests inject 0 (ephemeral) or a free port
     * so they never race the fixed port.
     */
    private val callbackPort: Int = CALLBACK_PORT,
) : OAuthAuth {

    /** pi `name: "Anthropic (Claude Pro/Max)"`. */
    override val name: String = "Anthropic (Claude Pro/Max)"

    /** pi `isSubscription: true`. */
    override val isSubscription: Boolean = true

    // --- loopback callback server (pi `startCallbackServer`) ---

    /** pi's `{ code: string; state: string }` callback settle value. */
    internal data class CallbackResult(
        val code: String,
        val state: String,
    )

    /**
     * pi's request-handler decision table, in upstream order: route → error
     * param → missing code/state → state mismatch → success. The expected
     * state is the PKCE verifier (`state = verifier` in the authorize URL).
     */
    private fun callbackResponse(
        request: LoopbackCallbackRequest,
        settle: (CallbackResult?) -> Unit,
        expectedState: String,
    ): LoopbackCallbackResponse {
        if (request.path != CALLBACK_PATH) {
            return LoopbackCallbackResponse(404, oauthErrorHtml("Callback route not found."))
        }

        val code = request.query["code"]
        val state = request.query["state"]
        val error = request.query["error"]

        // pi `if (error)` — an empty string is falsy in JS.
        if (!error.isNullOrEmpty()) {
            return LoopbackCallbackResponse(
                400,
                oauthErrorHtml("Anthropic authentication did not complete.", "Error: $error"),
            )
        }

        // pi `if (!code || !state)` — empty strings are falsy.
        if (code.isNullOrEmpty() || state.isNullOrEmpty()) {
            return LoopbackCallbackResponse(400, oauthErrorHtml("Missing code or state parameter."))
        }

        if (state != expectedState) {
            return LoopbackCallbackResponse(400, oauthErrorHtml("State mismatch."))
        }

        settle(CallbackResult(code, state))
        return LoopbackCallbackResponse(
            200,
            oauthSuccessHtml("Anthropic authentication completed. You can close this window."),
        )
    }

    // --- login (pi `loginAnthropic`) ---

    override suspend fun login(interaction: AuthInteraction): OAuthCredential = coroutineScope {
        val challenge = pkce.generate()
        val verifier = challenge.verifier

        // pi's `server.on("error")` rejects and the login fails outright.
        val handle = LoopbackOAuthServer(port = callbackPort, host = CALLBACK_HOST) { request, settle ->
            callbackResponse(request, settle, verifier)
        }.start()
            ?: throw IllegalStateException(
                "Failed to bind the Anthropic OAuth callback server on $CALLBACK_HOST:$callbackPort; " +
                    "another OAuth login may already be using the port",
            )

        try {
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

            val manualPrompt = async {
                try {
                    interaction.prompt(
                        AuthPrompt.ManualCode(
                            message = "Complete login in your browser, or paste the authorization code / redirect URL here:",
                            placeholder = REDIRECT_URI,
                        ),
                    )
                } finally {
                    // pi cancels the server wait in both the answer and error
                    // legs of the manual promise.
                    handle.cancelWait()
                }
            }

            val result = handle.waitForResult()
            val code: String
            val state: String
            if (result != null) {
                // Server leg won: pi abandons the pending manual promise until
                // its `finally` aborts it; cancelling the prompt coroutine is
                // what clears the pending prompt sheet in the UI.
                manualPrompt.cancel()
                code = result.code
                state = result.state
            } else {
                // The manual leg completed and cancelled the wait.
                val manualInput = manualPrompt.await()
                val parsed = parseAuthorizationInput(manualInput)
                // pi: `if (parsed.state && parsed.state !== verifier)` — only a non-empty state mismatches.
                if (!parsed.state.isNullOrEmpty() && parsed.state != verifier) {
                    throw IllegalStateException("OAuth state mismatch")
                }
                // pi: `if (!code)` — an empty code is missing, checked before the state.
                code = parsed.code?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Missing authorization code")
                // pi: `state = parsed.state ?? verifier` (nullish — an empty state stays empty),
                // then `if (!state)` throws. Unreachable in practice, kept for fidelity.
                state = parsed.state ?: verifier
                if (state.isEmpty()) throw IllegalStateException("Missing OAuth state")
            }

            interaction.notify(AuthEvent.Progress("Exchanging authorization code for tokens..."))
            // The server path passes the query's state, which already passed
            // validation; the manual path passes its parsed state.
            exchangeAuthorizationCode(code, state, verifier, REDIRECT_URI)
        } finally {
            handle.close()
        }
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
         * pi `REDIRECT_URI` (`http://localhost:53692/callback`), pointing at
         * the loopback callback server's fixed port and path.
         */
        const val REDIRECT_URI: String = "http://localhost:53692/callback"

        /** pi `CALLBACK_HOST`. Not overridable on Android (no `PI_OAUTH_CALLBACK_HOST`). */
        internal const val CALLBACK_HOST: String = "127.0.0.1"

        /** pi `CALLBACK_PORT`. */
        internal const val CALLBACK_PORT: Int = 53692

        /** pi `CALLBACK_PATH`. */
        internal const val CALLBACK_PATH: String = "/callback"

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
