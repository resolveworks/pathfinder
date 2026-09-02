package works.resolve.pathfinder.ai.auth.oauth

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.string
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder

/**
 * OpenRouter OAuth PKCE flow: OpenRouter exchanges an authorization code for
 * a permanent user-controlled API key rather than an expiring
 * access/refresh pair, so [refresh] is a no-op and the credential never
 * expires.
 *
 * Login races a one-shot loopback callback server against a manual-code
 * prompt. OpenRouter has no pre-registered redirect, so the authorize URL's
 * `callback_url` names the server's ephemeral `http://127.0.0.1:<port><path>`
 * endpoint, and the token exchange runs *inside* the request handler so the
 * browser sees the exchange outcome as its response page.
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
    private val gate: OAuthForegroundGate? = null,
) : OAuthAuth {

    /**
     * The login outcome: a credential, a failure to rethrow, or null (the
     * login was handed to manual entry). Encoded because `waitForResult()`
     * can only carry `R?`.
     */
    private sealed interface CallbackResult {
        data class Credential(val credential: OAuthCredential) : CallbackResult

        data class Failure(val error: Throwable) : CallbackResult
    }

    override val name: String = "OpenRouter OAuth"
    override val loginLabel: String = "Sign in with OpenRouter"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val challenge = pkce.generate()
        val callbackPath = "/oauth/callback/${UUID.randomUUID()}"

        val claimed = AtomicBoolean(false)
        val settled = AtomicBoolean(false)

        val handle = LoopbackOAuthServer<CallbackResult>(
            port = 0,
            gate = gate,
            handler = { request, settle ->
                handleCallback(request, { result ->
                    settled.set(true)
                    settle(result)
                }, callbackPath, challenge.verifier, claimed, settled)
            },
        ).start()
            // The shared server reports bind failure as null; like pi's
            // listen error, the login fails rather than degrading to manual
            // entry. Practically unreachable on an ephemeral port.
            ?: throw IllegalStateException("Could not bind the OpenRouter OAuth callback server")

        try {
            val callbackUrl = "http://127.0.0.1:${handle.port}$callbackPath"
            val authorizeUrl = buildString {
                append(AUTHORIZE_URL)
                append("?callback_url=").append(urlEncode(callbackUrl))
                append("&code_challenge=").append(urlEncode(challenge.challenge))
                append("&code_challenge_method=S256")
            }

            interaction.notify(AuthEvent.Progress("Listening for OpenRouter OAuth callback on $callbackUrl"))
            interaction.notify(
                AuthEvent.AuthUrl(
                    url = authorizeUrl,
                    instructions =
                        "Complete sign-in in your browser. If the browser is on another machine, paste the final redirect URL here.",
                ),
            )

            var manualInput: String? = null
            var manualError: Throwable? = null

            /** Hands the login to manual code entry unless a callback already claimed the exchange; that callback settles the login. */
            fun cancelWaitUnlessClaimed() {
                if (!claimed.get()) handle.cancelWait()
            }

            return coroutineScope {
                val manualJob = launch {
                    try {
                        manualInput = interaction.prompt(
                            AuthPrompt.ManualCode(
                                message = "Complete sign-in in your browser, or paste the authorization code / redirect URL here:",
                                placeholder = callbackUrl,
                            ),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        manualError = error
                    }
                    cancelWaitUnlessClaimed()
                }

                val result = try {
                    withTimeout(loginTimeoutMs) { handle.waitForResult() }
                } catch (error: TimeoutCancellationException) {
                    // Only the timeout race becomes an error; cancellation of
                    // the login itself propagates as CancellationException.
                    throw IllegalStateException("OpenRouter OAuth login timed out", error)
                }

                manualError?.let { throw it }
                when (result) {
                    is CallbackResult.Credential -> {
                        // Clears the pending manual prompt's UI sheet.
                        manualJob.cancel()
                        result.credential
                    }
                    is CallbackResult.Failure -> throw result.error
                    null -> {
                        manualJob.join()
                        manualError?.let { throw it }
                        val code = manualInput?.let(::parseAuthorizationCodeInput)
                            ?: throw IllegalStateException("Missing authorization code")
                        interaction.notify(AuthEvent.Progress("Exchanging authorization code for an API key..."))
                        exchangeAuthorizationCode(code, challenge.verifier)
                    }
                }
            }
        } finally {
            handle.close()
        }
    }

    private suspend fun handleCallback(
        request: LoopbackCallbackRequest,
        settle: (CallbackResult?) -> Unit,
        callbackPath: String,
        verifier: String,
        claimed: AtomicBoolean,
        settled: AtomicBoolean,
    ): LoopbackCallbackResponse {
        if (request.method != "GET" || request.path != callbackPath) {
            return LoopbackCallbackResponse(404, oauthErrorHtml("OAuth callback route not found."))
        }
        if (claimed.get() || settled.get()) {
            return LoopbackCallbackResponse(409, oauthErrorHtml("This OAuth callback has already been used."))
        }

        val oauthError = request.query["error"]
        if (!oauthError.isNullOrEmpty()) {
            val description = request.query["error_description"] ?: oauthError
            settle(CallbackResult.Failure(IllegalStateException("OpenRouter authorization failed: $description")))
            return LoopbackCallbackResponse(400, oauthErrorHtml("OpenRouter authorization was denied.", description))
        }

        val code = request.query["code"]
        if (code.isNullOrEmpty()) {
            return LoopbackCallbackResponse(400, oauthErrorHtml("OpenRouter returned no authorization code."))
        }
        claimed.set(true)

        return try {
            val credential = exchangeAuthorizationCode(code, verifier)
            settle(CallbackResult.Credential(credential))
            LoopbackCallbackResponse(200, oauthSuccessHtml("Signed in to OpenRouter. You may now close this page."))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: "Unknown token exchange error"
            settle(CallbackResult.Failure(error))
            LoopbackCallbackResponse(502, oauthErrorHtml("OpenRouter key exchange failed.", message))
        }
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth = ModelAuth(apiKey = credential.access)

    /** POSTs the code/verifier pair and shapes the response credential; error message wording mirrors pi verbatim. */
    internal suspend fun exchangeAuthorizationCode(code: String, verifier: String): OAuthCredential {
        val request = OAuthHttpRequest(
            method = "POST",
            url = TOKEN_URL,
            headers = mapOf("accept" to "application/json", "content-type" to "application/json"),
            body = jsonRequest(code, verifier),
            timeoutMs = TOKEN_EXCHANGE_TIMEOUT_MS,
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
                "OpenRouter OAuth key exchange failed (HTTP ${response.status})${detail?.let { ": $it" } ?: ""}",
            )
        }

        val key = body.string("key")
        if (key.isNullOrEmpty()) {
            throw IllegalStateException("OpenRouter OAuth response carries no \"key\"")
        }
        return OAuthCredential(
            access = key,
            refresh = "",
            expires = NON_EXPIRING_EPOCH_MS,
        )
    }

    companion object {
        /** JS `Number.MAX_SAFE_INTEGER`: the sentinel for a non-expiring key. */
        const val NON_EXPIRING_EPOCH_MS: Long = 9_007_199_254_740_991L
        const val AUTHORIZE_URL: String = "https://openrouter.ai/auth"
        const val TOKEN_URL: String = "https://openrouter.ai/api/v1/auth/keys"

        const val LOGIN_TIMEOUT_MS: Long = 5 * 60 * 1000
        const val TOKEN_EXCHANGE_TIMEOUT_MS: Int = 30_000

        private val json = lenientJson

        /** Extracts the code from a pasted URL, a bare `code=` query string, or raw code. */
        internal fun parseAuthorizationCodeInput(input: String): String? {
            val value = input.trim()
            if (value.isEmpty()) return null
            try {
                val uri = URI(value)
                if (uri.scheme != null) {
                    return uri.rawQuery?.let(::parseQueryString)?.get("code")
                }
            } catch (_: Exception) {
                // not a URL
            }
            if (value.contains("code=")) {
                return parseQueryString(value)?.get("code")
            }
            return value
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

        private fun jsonRequest(code: String, verifier: String): ByteArray =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("code", code)
                    put("code_verifier", verifier)
                    put("code_challenge_method", "S256")
                },
            ).toByteArray(Charsets.UTF_8)

        private fun urlEncode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
