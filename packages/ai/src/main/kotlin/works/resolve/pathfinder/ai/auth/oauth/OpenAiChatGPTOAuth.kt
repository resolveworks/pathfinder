package works.resolve.pathfinder.ai.auth.oauth

import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.LoginOptions
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.utils.formQuery
import works.resolve.pathfinder.ai.utils.formUrlEncode
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.requireString
import works.resolve.pathfinder.ai.utils.strictDouble
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * OpenAI Responses API token sharing through Sign in with ChatGPT.
 *
 * This public-client flow uses no client secret and sends the resulting user
 * access token directly to api.openai.com. Every login registers a new client
 * with [DYNAMIC_CLIENT_ID]; OpenAI returns the issued client ID in the
 * callback.
 *
 * Ported differences from pi:
 * - The callback transport is the shared JDK loopback server, but this flow
 *   keeps its own callback semantics (issued client ID, registration-specific
 *   pages) as upstream does.
 * - Failed token HTTP responses never interpolate the raw body, which can
 *   carry tokens.
 * - The default agent name is "Pathfinder", not pi's name.
 */
class OpenAiChatGPTOAuth(
    private val http: OAuthHttpClient,
    private val clock: Clock = Clock.System,
    private val pkce: PkceGenerator = PkceGenerator(),
    /** Injectable so tests never race the fixed port. */
    private val callbackPort: Int = CALLBACK_PORT,
    private val gate: OAuthForegroundGate? = null
) : OAuthAuth {

    override val name: String = "OpenAI (ChatGPT subscription)"
    override val loginLabel: String = "Sign in with ChatGPT"
    override val isSubscription: Boolean = true

    private data class AuthorizationResult(val code: String, val clientId: String) {
        override fun toString(): String = "AuthorizationResult(code=<redacted>, clientId=$clientId)"
    }

    private sealed interface AuthorizationOutcome {
        data class Success(val result: AuthorizationResult) : AuthorizationOutcome

        data class Failure(val error: Throwable) : AuthorizationOutcome
    }

    private data class CallbackServer(val handle: LoopbackCallbackHandle<AuthorizationOutcome>)

    override suspend fun login(
        interaction: AuthInteraction,
        options: LoginOptions?
    ): OAuthCredential = coroutineScope {
        val hostId = agentHostId(options?.getDeviceId?.invoke())
        val pair = pkce.generate()
        val state = randomValue()
        val nonce = randomValue()
        // Without this server, the browser's callback would reach whatever else holds the port (another
        // pending login or the Codex CLI), which rejects it as a state mismatch. Fail with a clear error instead.
        val callback = startCallbackServer(state)

        try {
            val authParams = linkedMapOf(
                "client_id" to DYNAMIC_CLIENT_ID,
                "agent_name_hint" to (options?.agentName ?: AGENT_NAME_HINT),
                "ext_agent_host_id" to hostId,
                "response_type" to "code",
                "redirect_uri" to REDIRECT_URI,
                "resource" to RESOURCE,
                "scope" to SCOPE,
                "state" to state,
                "code_challenge" to pair.challenge,
                "code_challenge_method" to "S256",
                "nonce" to nonce
            )
            interaction.notify(
                AuthEvent.AuthUrl(
                    url = AUTHORIZE_URL + "?" + formUrlEncode(authParams),
                    instructions =
                        "Complete sign-in in your browser. If the callback does not complete, paste the final redirect URL here."
                )
            )

            val manual = async {
                val input = interaction.prompt(
                    AuthPrompt.ManualCode(
                        message = "Complete login in your browser, " +
                            "or paste the final redirect URL here:",
                        placeholder = REDIRECT_URI
                    )
                )
                authorizationResultFromManualInput(input, state)
            }
            val callbackResult = async {
                when (val outcome = callback.handle.waitForResult()) {
                    is AuthorizationOutcome.Success -> outcome.result
                    is AuthorizationOutcome.Failure -> throw outcome.error
                    null -> throw IllegalStateException("Login cancelled")
                }
            }

            try {
                val result = select {
                    callbackResult.onAwait { it }
                    manual.onAwait { it }
                }
                interaction.notify(
                    AuthEvent.Progress("Exchanging authorization code for tokens...")
                )
                exchangeAuthorizationCode(result.code, pair.verifier, result.clientId)
            } finally {
                manual.cancel()
                callbackResult.cancel()
            }
        } finally {
            callback.handle.close()
        }
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
        val clientId = credential.extras["clientId"].stringOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException(
                "Stored OpenAI OAuth credential does not contain an issued client ID; reconnect ChatGPT"
            )
        val token = requestToken(
            linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to credential.refresh,
                "resource" to RESOURCE
            )
        )
        return credentialFromTokenResponse(token, clientId)
    }

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)

    /** OpenAI identifies each installation ("agent host") by a stable URI such as `urn:uuid:<uuid>`. */
    internal fun agentHostId(deviceId: String?): String {
        if (deviceId == null || !UUID_PATTERN.matches(deviceId)) {
            throw IllegalStateException(
                "Sign in with ChatGPT requires a device ID (UUID) for this installation"
            )
        }
        return "urn:uuid:${deviceId.lowercase()}"
    }

    private suspend fun startCallbackServer(state: String): CallbackServer {
        val handle = LoopbackOAuthServer<AuthorizationOutcome>(
            port = callbackPort,
            host = CALLBACK_HOST,
            gate = gate,
            handler = { request, settle ->
                when {
                    request.path != CALLBACK_PATH ->
                        LoopbackCallbackResponse(
                            404,
                            oauthErrorHtml("Callback route not found.")
                        )

                    !request.query["error"].isNullOrEmpty() -> {
                        val error = request.query["error"]!!
                        settle(
                            AuthorizationOutcome.Failure(
                                IllegalStateException("ChatGPT authorization failed: $error")
                            )
                        )
                        LoopbackCallbackResponse(
                            400,
                            oauthErrorHtml("ChatGPT was not connected.", "Error: $error")
                        )
                    }

                    else -> {
                        val result = try {
                            authorizationResultFromCallback(request.query, state)
                        } catch (error: Exception) {
                            return@LoopbackOAuthServer LoopbackCallbackResponse(
                                400,
                                oauthErrorHtml(error.message ?: "Invalid callback")
                            )
                        }
                        settle(AuthorizationOutcome.Success(result))
                        LoopbackCallbackResponse(
                            200,
                            oauthSuccessHtml(
                                "ChatGPT authentication completed. You can close this window."
                            )
                        )
                    }
                }
            }
        ).start()
            ?: throw IllegalStateException(
                "Port $CALLBACK_PORT is in use, probably by an unfinished login in another " +
                    "Pathfinder session or by the Codex CLI. Cancel that login and try again."
            )
        return CallbackServer(handle)
    }

    private fun authorizationResultFromCallback(
        query: Map<String, String>,
        expectedState: String
    ): AuthorizationResult {
        val code = query["code"]?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Missing authorization code")
        val state = query["state"]?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Missing OAuth state")
        if (state != expectedState) throw IllegalStateException("OAuth state mismatch")
        val clientId = query["client_id"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException(
                "OpenAI OAuth registration callback did not contain an issued client ID"
            )
        return AuthorizationResult(code, clientId)
    }

    private fun authorizationResultFromManualInput(
        input: String,
        expectedState: String
    ): AuthorizationResult {
        val url = input.trim().toHttpUrlOrNull()
            ?: throw IllegalStateException("Paste the full callback URL from the browser")
        val expected = REDIRECT_URI.toHttpUrl()
        if (url.scheme != expected.scheme ||
            url.host != expected.host ||
            url.port != expected.port ||
            url.encodedPath != expected.encodedPath
        ) {
            throw IllegalStateException("The pasted callback URL must start with $REDIRECT_URI")
        }
        val error = url.queryParameter("error")
        if (!error.isNullOrEmpty()) {
            throw IllegalStateException(
                "ChatGPT authorization failed: $error"
            )
        }
        return authorizationResultFromCallback(formQuery(url.encodedQuery ?: ""), expectedState)
    }

    private suspend fun exchangeAuthorizationCode(
        code: String,
        verifier: String,
        clientId: String
    ): OAuthCredential {
        val token = requestToken(
            linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to REDIRECT_URI,
                "resource" to RESOURCE
            )
        )
        // Pathfinder does not use the ID token to identify the user or read profile data.
        // Keep the presence check as part of the token-response contract.
        if (token.string("id_token").isNullOrEmpty()) {
            throw IllegalStateException("OpenAI OAuth token response did not contain an ID token")
        }
        return credentialFromTokenResponse(token, clientId)
    }

    private suspend fun requestToken(fields: Map<String, String>): JsonObject {
        val response = http.execute(
            OAuthHttpRequest(
                method = "POST",
                url = TOKEN_URL,
                headers = mapOf(
                    "accept" to "application/json",
                    "content-type" to "application/x-www-form-urlencoded"
                ),
                body = formUrlEncode(fields).toByteArray(Charsets.UTF_8),
                timeoutMs = REQUEST_TIMEOUT_MS
            )
        )
        val text = response.body.toString(Charsets.UTF_8)
        if (response.status !in 200..299) {
            throw IllegalStateException(
                "OpenAI OAuth token request failed (${response.status})"
            )
        }
        return try {
            lenientJson.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalStateException("OpenAI OAuth token response must be an object")
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: Exception) {
            throw IllegalStateException("OpenAI OAuth token response must be an object")
        }
    }

    private fun credentialFromTokenResponse(token: JsonObject, clientId: String): OAuthCredential {
        val access = requireTokenString(token, "access_token")
        val refresh = requireTokenString(token, "refresh_token")
        val scope = requireTokenString(token, "scope")
        val expiresIn = token.strictDouble("expires_in")?.takeIf { it.isFinite() && it > 0 }
            ?: throw IllegalStateException("OpenAI OAuth token response has invalid expires_in")
        val scopes = scope.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (!scopes.contains(DIRECT_TOKEN_SCOPE)) {
            throw IllegalStateException(
                "OpenAI OAuth grant did not include $DIRECT_TOKEN_SCOPE"
            )
        }
        return OAuthCredential(
            access = access,
            refresh = refresh,
            expires = clock.now().toEpochMilliseconds() +
                (expiresIn * 1000).toLong() - EXPIRY_MARGIN_MS,
            extras = mapOf(
                "clientId" to JsonPrimitive(clientId),
                "scopes" to JsonArray(scopes.map { JsonPrimitive(it) })
            )
        )
    }

    private fun requireTokenString(token: JsonObject, field: String): String {
        val value = token.requireString(field) {
            IllegalStateException("OpenAI OAuth token response has invalid $field")
        }
        if (value.trim().isEmpty()) {
            throw IllegalStateException("OpenAI OAuth token response has invalid $field")
        }
        return value
    }

    companion object {
        // every login registers a new client with this ID; OpenAI returns the issued client ID in the callback
        const val DYNAMIC_CLIENT_ID: String = "dynamic_agent_client"

        const val AGENT_NAME_HINT: String = "Pathfinder"

        private val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        )

        const val AUTHORIZE_URL: String = "https://auth.openai.com/api/accounts/authorize"

        const val TOKEN_URL: String = "https://auth.openai.com/api/accounts/oauth/token"

        const val RESOURCE: String = "https://api.openai.com/v1"

        /** Fixed loopback host; pi's `getCallbackHost()` env override doesn't exist on Android. */
        const val CALLBACK_HOST: String = "127.0.0.1"

        const val CALLBACK_PORT: Int = 1455

        const val CALLBACK_PATH: String = "/auth/callback"

        const val REDIRECT_URI: String = "http://127.0.0.1:$CALLBACK_PORT$CALLBACK_PATH"

        const val DIRECT_TOKEN_SCOPE: String = "chatgpt.tokens.use.direct"

        const val SCOPE: String =
            "openid profile email offline_access resource.invoke $DIRECT_TOKEN_SCOPE"

        /** Refresh this long before the real expiry so a request never starts with a token about to expire. */
        const val EXPIRY_MARGIN_MS: Long = 3 * 60 * 1000

        /** Bounded timeout for every OAuth request; pi relies on fetch defaults. */
        const val REQUEST_TIMEOUT_MS: Int = 30_000

        private val WHITESPACE = Regex("\\s+")

        private fun randomValue(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
