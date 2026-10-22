package works.resolve.pathfinder.ai.auth.oauth

import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.LoginOptions
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.oauth.PkceGenerator
import works.resolve.pathfinder.ai.utils.MAX_SAFE_INTEGER
import works.resolve.pathfinder.ai.utils.formQuery
import works.resolve.pathfinder.ai.utils.formUrlEncode
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.trimJsWhitespace
import works.resolve.pathfinder.ai.utils.urlQueryParamsOrNull

/**
 * OpenRouter OAuth PKCE flow: OpenRouter exchanges an authorization code for
 * a permanent user-controlled API key rather than an expiring
 * access/refresh pair, so [refresh] is a no-op and the credential never
 * expires.
 *
 * Login races a one-shot loopback callback server against a manual-code
 * prompt. OpenRouter has no pre-registered redirect, so the authorize URL's
 * `callback_url` names the server's ephemeral `http://127.0.0.1:<port><path>`
 * endpoint, and the token exchange runs inside the shared callback server's
 * completion so the browser sees the exchange outcome as its response page.
 *
 * If Android kills the app process while the browser is foregrounded, the
 * callback server dies with it and the login must be retried.
 *
 * Nothing secret is ever logged: exceptions carry only statuses and
 * server-provided error details, never the code, verifier, or key.
 */
class OpenRouterOAuthAuth(
    private val http: OAuthHttpClient,
    private val pkce: PkceGenerator = PkceGenerator(),
    /** Injectable seam so tests can exercise the timeout race quickly. */
    private val loginTimeoutMs: Long = LOGIN_TIMEOUT_MS,
    /** Android foreground gate for the loopback wait. */
    private val gate: OAuthForegroundGate? = null
) : OAuthAuth {

    override val name: String = "OpenRouter OAuth"
    override val loginLabel: String = "Sign in with OpenRouter"

    override suspend fun login(
        interaction: AuthInteraction,
        options: LoginOptions?
    ): OAuthCredential {
        val challenge = pkce.generate()
        val callbackPath = "/oauth/callback/${UUID.randomUUID()}"
        // OpenRouter sends no `state`; the random path keeps stray requests from completing the sign-in.
        val callback = startOAuthCallbackServer(
            OAuthCallbackServerOptions(
                providerName = "OpenRouter",
                host = CALLBACK_HOST,
                port = 0,
                path = callbackPath,
                complete = { code -> exchangeAuthorizationCode(code, challenge.verifier) },
                gate = gate,
                timeoutMs = loginTimeoutMs
            )
        )

        try {
            val authorizeUrl = AUTHORIZE_URL + "?" +
                formUrlEncode(
                    linkedMapOf(
                        "callback_url" to callback.redirectUri,
                        "code_challenge" to challenge.challenge,
                        "code_challenge_method" to "S256"
                    )
                )

            interaction.notify(
                AuthEvent.Progress(
                    "Listening for OpenRouter OAuth callback on ${callback.redirectUri}"
                )
            )
            interaction.notify(
                AuthEvent.AuthUrl(
                    url = authorizeUrl,
                    instructions =
                        "Complete sign-in in your browser. If the browser is on another machine, paste the final redirect URL here."
                )
            )

            val result = waitForCallbackOrManualInput(
                interaction,
                callback,
                message = "Complete sign-in in your browser, " +
                    "or paste the authorization code / redirect URL here:",
                placeholder = callback.redirectUri
            )
            if (result is CallbackOrManualInput.Callback) return result.value

            val code = parseAuthorizationCodeInput(
                (result as CallbackOrManualInput.Manual).input
            ) ?: throw IllegalStateException("Missing authorization code")
            interaction.notify(
                AuthEvent.Progress("Exchanging authorization code for an API key...")
            )
            return exchangeAuthorizationCode(code, challenge.verifier)
        } finally {
            callback.close()
        }
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)

    /** POSTs the code/verifier pair and shapes the response credential; error message wording mirrors pi verbatim. */
    internal suspend fun exchangeAuthorizationCode(
        code: String,
        verifier: String
    ): OAuthCredential {
        val request = OAuthHttpRequest(
            method = "POST",
            url = TOKEN_URL,
            headers = mapOf("accept" to "application/json", "content-type" to "application/json"),
            body = jsonRequest(code, verifier),
            timeoutMs = TOKEN_EXCHANGE_TIMEOUT_MS
        )

        val response: OAuthHttpResponse
        val body: JsonObject
        try {
            response = http.execute(request)
            body = parseBody(response)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw IllegalStateException("OpenRouter OAuth token exchange timed out", error)
        }

        if (response.status !in 200..299) {
            val detail = errorDetail(body)
            throw IllegalStateException(
                "OpenRouter OAuth key exchange failed (HTTP ${response.status})${detail?.let {
                    ": $it"
                } ?: ""}"
            )
        }

        val key = body.string("key")
        if (key.isNullOrEmpty()) {
            throw IllegalStateException("OpenRouter OAuth response carries no \"key\"")
        }
        return OAuthCredential(
            access = key,
            refresh = "",
            expires = NON_EXPIRING_EPOCH_MS
        )
    }

    companion object {
        /** JS `Number.MAX_SAFE_INTEGER`: the sentinel for a non-expiring key. */
        const val NON_EXPIRING_EPOCH_MS: Long = MAX_SAFE_INTEGER
        const val AUTHORIZE_URL: String = "https://openrouter.ai/auth"
        const val TOKEN_URL: String = "https://openrouter.ai/api/v1/auth/keys"

        /** Fixed loopback host; pi's `getCallbackHost()` env override doesn't exist on Android. */
        const val CALLBACK_HOST: String = "127.0.0.1"

        const val LOGIN_TIMEOUT_MS: Long = 5 * 60 * 1000
        const val TOKEN_EXCHANGE_TIMEOUT_MS: Int = 30_000

        private val json = lenientJson

        /** Extracts the code from a pasted URL, a bare `code=` query string, or raw code. */
        internal fun parseAuthorizationCodeInput(input: String): String? {
            val value = trimJsWhitespace(input)
            if (value.isEmpty()) return null
            urlQueryParamsOrNull(value)?.let { return it["code"] }
            if (value.contains("code=")) {
                return formQuery(value)["code"]
            }
            return value
        }

        internal fun errorDetail(body: JsonObject): String? {
            body.string("error_description")?.let { return it }
            body.string("message")?.let { return it }
            body.string("error")?.let { return it }
            body.obj("error")?.string("message")?.let { return it }
            return null
        }

        /**
         * Lenient like pi's exchange: an unparseable non-2xx body becomes an
         * empty object so the failure path reports status only, while a bad
         * body on a 2xx response throws invalid-JSON.
         */
        private fun parseBody(response: OAuthHttpResponse): JsonObject {
            val text = response.body.toString(Charsets.UTF_8)
            val parsed = try {
                lenientJson.parseToJsonElement(text)
            } catch (_: Exception) {
                if (response.status in 200..299) {
                    throw IllegalStateException("OpenRouter OAuth returned invalid JSON")
                }
                null
            }
            return parsed as? JsonObject ?: JsonObject(emptyMap())
        }

        private fun jsonRequest(code: String, verifier: String): ByteArray = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("code", code)
                put("code_verifier", verifier)
                put("code_challenge_method", "S256")
            }
        ).toByteArray(Charsets.UTF_8)
    }
}
