package works.resolve.pathfinder.ai.auth.oauth

import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.PkceGenerator
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.requireString
import works.resolve.pathfinder.ai.utils.string
import kotlin.time.Clock
import works.resolve.pathfinder.ai.utils.strictDouble

/**
 * A loopback callback server races the manual code prompt; a server result
 * wins outright and cancels the pending prompt.
 *
 * Divergences from pi:
 * - pi's in-handler catch renders a text/plain 500; this handler cannot
 *   realistically throw (the shared transport pre-parses the request), and
 *   [LoopbackOAuthServer]'s uniform HTML 500 covers the impossible case.
 * - Bind failure fails the login outright (like pi's Anthropic flow, unlike
 *   the sibling Codex flow, which degrades to manual login). pi surfaces
 *   Node's `EADDRINUSE` errno; this port has no Node error metadata and
 *   throws a plain `IllegalStateException` naming the failed loopback bind.
 * - No `PI_OAUTH_CALLBACK_HOST` env override: the host is `127.0.0.1`.
 *   Android apps share the device network namespace, so the socket is
 *   reachable from the on-device browser (Chrome/Vanadium allow cleartext
 *   `http://localhost`). Android may kill the app process while the browser
 *   is foregrounded; the login is simply retried.
 * - pi serializes Node error metadata (`code`, `errno`, `stack`) in failure
 *   details; this port has no Node metadata and emits `ClassName: message`.
 * - pi type-casts the token JSON unchecked; this port validates the required
 *   string/number fields and fails with an explicit field name.
 *
 * Secret safety: pi echoes the raw response body in its `HTTP request
 * failed ... body=` and invalid-JSON messages; a body can carry tokens (a
 * truncated token error, an echoed code/verifier), so this port never
 * interpolates an unparseable raw body. Non-2xx bodies that parse as JSON
 * objects contribute only their `error`/`error_description` strings;
 * anything else becomes `<redacted>`. Invalid-JSON messages carry no body
 * at all.
 */
class AnthropicOAuthAuth(
    private val http: OAuthHttpClient,
    private val pkce: PkceGenerator = PkceGenerator(),
    private val clock: Clock = Clock.System,
    /**
     * Bind port for the loopback callback server; tests inject an ephemeral
     * or free port so they never race the fixed production port.
     */
    private val callbackPort: Int = CALLBACK_PORT,
    /** Android foreground gate for the loopback wait. */
    private val gate: OAuthForegroundGate? = null,
) : OAuthAuth {

    override val name: String = "Anthropic (Claude Pro/Max)"

    override val isSubscription: Boolean = true

    internal data class CallbackResult(
        val code: String,
        val state: String,
    )

    /** The expected state is the PKCE verifier: the authorize URL sends `state = verifier`. */
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

        if (!error.isNullOrEmpty()) {
            return LoopbackCallbackResponse(
                400,
                oauthErrorHtml("Anthropic authentication did not complete.", "Error: $error"),
            )
        }

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

    override suspend fun login(interaction: AuthInteraction): OAuthCredential = coroutineScope {
        val challenge = pkce.generate()
        val verifier = challenge.verifier

        val handle = LoopbackOAuthServer(port = callbackPort, host = CALLBACK_HOST, gate = gate) { request, settle ->
            callbackResponse(request, settle, verifier)
        }.start()
            ?: throw IllegalStateException(
                "Failed to bind the Anthropic OAuth callback server on $CALLBACK_HOST:$callbackPort; " +
                    "another OAuth login may already be using the port",
            )

        try {
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
                    handle.cancelWait()
                }
            }

            val result = handle.waitForResult()
            val code: String
            val state: String
            if (result != null) {
                // Cancelling the prompt coroutine is what clears the pending
                // prompt sheet in the UI.
                manualPrompt.cancel()
                code = result.code
                state = result.state
            } else {
                val manualInput = manualPrompt.await()
                val parsed = parseAuthorizationInput(manualInput)
                if (!parsed.state.isNullOrEmpty() && parsed.state != verifier) {
                    throw IllegalStateException("OAuth state mismatch")
                }
                code = parsed.code?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Missing authorization code")
                state = parsed.state ?: verifier
                if (state.isEmpty()) throw IllegalStateException("Missing OAuth state")
            }

            interaction.notify(AuthEvent.Progress("Exchanging authorization code for tokens..."))
            exchangeAuthorizationCode(code, state, verifier, REDIRECT_URI)
        } finally {
            handle.close()
        }
    }

    internal data class ParsedAuthorizationInput(
        val code: String?,
        val state: String?,
    )

    /** Extracts code/state from a pasted URL, `code#state`, bare `code=` query string, or raw code. */
    internal fun parseAuthorizationInput(input: String): ParsedAuthorizationInput {
        val value = input.trim()
        if (value.isEmpty()) return ParsedAuthorizationInput(null, null)

        try {
            val uri = URI(value)
            if (uri.scheme != null) {
                val params = uri.rawQuery?.let(::parseQueryString) ?: emptyMap()
                return ParsedAuthorizationInput(params["code"], params["state"])
            }
        } catch (_: Exception) {
            // not a URL
        }

        if (value.contains("#")) {
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
            tokenData = lenientJson.parseToJsonElement(responseBody) as? JsonObject
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
            data = lenientJson.parseToJsonElement(responseBody) as? JsonObject
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

    private fun credentialFrom(body: JsonObject): OAuthCredential {
        // Empty string counts as missing — pi's unchecked cast would pass it through.
        val access = body.requireString("access_token") { invalidField(it) }.takeIf { it.isNotEmpty() }
            ?: throw invalidField("access_token")
        val refresh = body.requireString("refresh_token") { invalidField(it) }.takeIf { it.isNotEmpty() }
            ?: throw invalidField("refresh_token")
        val expiresInSeconds = body.strictDouble("expires_in")?.takeIf { it > 0 }
            ?: throw invalidField("expires_in")
        return OAuthCredential(
            access = access,
            refresh = refresh,
            expires = clock.now().toEpochMilliseconds() + (expiresInSeconds * 1000).toLong() - REFRESH_SKEW_MS,
        )
    }

    private fun invalidField(field: String): IllegalStateException =
        IllegalStateException("Invalid Anthropic OAuth response field: $field")

    /**
     * pi serializes Node error metadata (`code`, `errno`, `stack`) with no
     * Kotlin counterpart, so details are `ClassName: message`.
     */
    private fun formatErrorDetails(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: ""}"

    /**
     * JSON POST to [TOKEN_URL]. A non-2xx response fails with the status,
     * URL, and a sanitized body summary — never request secrets.
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
            throw IllegalStateException(
                "HTTP request failed. status=${response.status}; url=$TOKEN_URL; body=${safeBodySummary(responseBody)}",
            )
        }
        return responseBody
    }

    /**
     * An unparseable or non-error body is never interpolated — a body can
     * carry tokens; pi echoes the raw body here (deliberately diverged).
     */
    private fun safeBodySummary(responseBody: String): String {
        val body = try {
            lenientJson.parseToJsonElement(responseBody) as? JsonObject ?: return "<redacted>"
        } catch (_: Exception) {
            return "<redacted>"
        }
        val error = body.string("error")
        val description = body.string("error_description")
        val detail = listOfNotNull(error, description).joinToString(": ")
        return if (detail.isNotEmpty()) "error=$detail" else "<redacted>"
    }

    private fun jsonRequest(fields: Map<String, String>): ByteArray =
        lenientJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                for ((name, value) in fields) put(name, value)
            },
        ).toByteArray(Charsets.UTF_8)

    companion object {
        const val CLIENT_ID: String = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

        const val AUTHORIZE_URL: String = "https://claude.ai/oauth/authorize"

        const val TOKEN_URL: String = "https://platform.claude.com/v1/oauth/token"

        /** Loopback callback URL; must match the callback server's fixed port and path. */
        const val REDIRECT_URI: String = "http://localhost:53692/callback"

        /** Not overridable on Android — there is no `PI_OAUTH_CALLBACK_HOST` env override. */
        internal const val CALLBACK_HOST: String = "127.0.0.1"

        internal const val CALLBACK_PORT: Int = 53692

        internal const val CALLBACK_PATH: String = "/callback"

        const val SCOPES: String =
            "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"

        /** Credentials are treated as expired five minutes early, so refresh precedes actual expiry. */
        const val REFRESH_SKEW_MS: Long = 5 * 60 * 1000

        const val REQUEST_TIMEOUT_MS: Int = 30_000

        /**
         * The JDK [java.net.URLEncoder] uses the WHATWG form-urlencoded set
         * (as `URLSearchParams` does): alphanumerics, `*`, `-`, `.`, `_` stay
         * bare, space becomes `+`, and every other byte (including `~` →
         * `%7E`) is percent-encoded.
         */
        internal fun formEncode(fields: Map<String, String>): String =
            fields.entries.joinToString("&") { (name, value) ->
                urlEncode(name) + "=" + urlEncode(value)
            }

        private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}
