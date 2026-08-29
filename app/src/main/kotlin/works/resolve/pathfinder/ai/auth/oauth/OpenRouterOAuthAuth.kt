package works.resolve.pathfinder.ai.auth.oauth

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder

/**
 * OpenRouter OAuth PKCE flow, ported from pi
 * `packages/ai/src/auth/oauth/openrouter.ts`.
 *
 * OpenRouter exchanges an authorization code for a permanent user-controlled
 * API key rather than an expiring access/refresh pair, so [refresh] is a
 * no-op and the credential is effectively non-expiring.
 *
 * Like pi's `loginOpenRouter`, login races a one-shot loopback callback
 * server ([LoopbackOAuthServer]) against a manual-code prompt: the server
 * binds an ephemeral port with a random `/oauth/callback/<uuid>` path, the
 * authorize URL passes the resulting `http://127.0.0.1:<port><path>` as the
 * `callback_url` parameter (OpenRouter has no pre-registered redirect), and
 * the token exchange runs *inside* the request handler via the injected
 * [OAuthHttpClient] so the browser sees the exchange outcome as its response
 * page. A five-minute [loginTimeoutMs] races the callback result (pi's
 * `LOGIN_TIMEOUT_MS`); bind failure — practically impossible on an ephemeral
 * port, but mirroring pi's listen-error rejection — throws instead of
 * degrading to manual entry. When the callback wins, the manual-prompt child
 * coroutine is cancelled so the pending UI sheet clears; when manual entry
 * wins, [parseAuthorizationCodeInput] and the exchange mirror pi's manual
 * path exactly.
 *
 * `waitForResult` can only carry `R?`, while pi's `waitForCredential`
 * rejects on handler failure or timeout and resolves null only on
 * `cancelWait`; [CallbackResult] encodes that distinction: `null` means the
 * login was handed to manual entry, [CallbackResult.Failure] rethrows the
 * encoded error.
 *
 * Divergence caveat (documented per AGENTS.md): Android may kill the app
 * process while the browser is foregrounded, losing the callback server —
 * the same retryable risk class as pi's abortable login.
 *
 * HTTP goes through the injected [OAuthHttpClient]; no network happens in
 * tests. Nothing secret is ever logged: exceptions carry only statuses and
 * server-provided error details, never the code, verifier, or key.
 */
class OpenRouterOAuthAuth(
    private val http: OAuthHttpClient,
    private val pkce: PkceGenerator = PkceGenerator(),
    /** pi `LOGIN_TIMEOUT_MS`; injectable seam so tests can exercise the timeout race quickly. */
    private val loginTimeoutMs: Long = LOGIN_TIMEOUT_MS,
) : OAuthAuth {

    /**
     * pi's credential promise resolves a credential, resolves null (manual
     * handover via `cancelWait`), or rejects — encoded here because
     * `waitForResult()` can only carry `R?`.
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

        // pi `startCallbackServer` guards reuse with `claimed`/`settled` in
        // the request-handler closure; the same state lives here.
        val claimed = AtomicBoolean(false)
        val settled = AtomicBoolean(false)

        val handle = LoopbackOAuthServer<CallbackResult>(
            port = 0,
            handler = { request, settle ->
                handleCallback(request, { result ->
                    settled.set(true)
                    settle(result)
                }, callbackPath, challenge.verifier, claimed, settled)
            },
        ).start()
            // pi's listen error rejects the login; the shared server reports
            // bind failure as null. Ephemeral port, so this is practically
            // unreachable — mirror pi and throw.
            ?: throw IllegalStateException("Could not bind the OpenRouter OAuth callback server")

        try {
            // pi builds the authorize URL from the bound port before any
            // notification; `callback_url` carries the dynamic loopback URL.
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

            /**
             * pi `cancelWait`: hands the login to manual code entry unless a
             * callback already claimed the exchange (a claimed callback lets
             * its exchange settle the login).
             */
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
                        // The callback won (or the login was cancelled); pi
                        // abandons the pending manual promise via `manualAbort`.
                        throw error
                    } catch (error: Throwable) {
                        manualError = error
                    }
                    cancelWaitUnlessClaimed()
                }

                val result = try {
                    withTimeout(loginTimeoutMs) { handle.waitForResult() }
                } catch (error: TimeoutCancellationException) {
                    // Only the timeout race maps to pi's message; cancellation
                    // of the login itself propagates as CancellationException.
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
            // pi's `finally`: abort the manual prompt and close the server.
            handle.close()
        }
    }

    /**
     * Port of pi's `createServer` request handler: route check, reuse guard,
     * error-param denial, code extraction, then the token exchange *inside*
     * the handler so only a finished exchange settles the login and the
     * browser sees the exchange outcome as its response page.
     */
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
        // pi `if (oauthError)` — an empty string is falsy in JS, so an empty
        // `error=` param is not a denial.
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

    /**
     * POSTs the code/verifier pair to OpenRouter's key endpoint and shapes
     * the response credential, mirroring pi's `exchangeAuthorizationCode`
     * (messages verbatim). Cancellation propagates as [CancellationException];
     * a bounded connect/read timeout becomes pi's timeout error; other
     * network failures propagate as [IOException] for the login orchestration
     * to wrap.
     */
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

        val key = (body["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
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
        /** pi: `Number.MAX_SAFE_INTEGER` marks the non-expiring OpenRouter key. */
        const val NON_EXPIRING_EPOCH_MS: Long = 9_007_199_254_740_991L
        const val AUTHORIZE_URL: String = "https://openrouter.ai/auth"
        const val TOKEN_URL: String = "https://openrouter.ai/api/v1/auth/keys"

        /** pi `LOGIN_TIMEOUT_MS`: 5 minutes. */
        const val LOGIN_TIMEOUT_MS: Long = 5 * 60 * 1000
        const val TOKEN_EXCHANGE_TIMEOUT_MS: Int = 30_000

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Extracts the authorization code from any user input form, mirroring
         * pi's `parseAuthorizationInput`: a full URL returns its `code` query
         * parameter (possibly null); a bare query string containing `code=`
         * is parsed as one; anything else is taken as the raw code.
         */
        internal fun parseAuthorizationCodeInput(input: String): String? {
            val value = input.trim()
            if (value.isEmpty()) return null
            try {
                val uri = URI(value)
                if (uri.scheme != null) {
                    // Parsed as an absolute URL like pi's `new URL(value)`:
                    // the result is whatever its `code` parameter says (or null).
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

        /** Minimal query-string parser with pi's `URLSearchParams` semantics (`&`/`=` pairs, form decoding). */
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
            (body["error_description"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
            (body["message"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
            body["error"]?.let { field ->
                (field as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
                (field as? JsonObject)?.get("message")?.let { message ->
                    (message as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
                }
            }
            return null
        }

        /**
         * Parses the response body as a JSON object (pi tolerates invalid
         * JSON on error responses but fails ok responses with pi's message).
         */
        private fun parseBody(response: OAuthHttpResponse): JsonObject {
            val text = response.body.toString(Charsets.UTF_8)
            val parsed = try {
                Json.parseToJsonElement(text)
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
