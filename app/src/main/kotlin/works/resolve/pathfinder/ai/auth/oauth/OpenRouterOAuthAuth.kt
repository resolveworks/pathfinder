package works.resolve.pathfinder.ai.auth.oauth

import kotlinx.coroutines.CancellationException
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
 * Divergence from pi (documented per AGENTS.md): pi races a one-shot
 * loopback HTTP callback server against a manual-code prompt so a desktop
 * browser can redirect back automatically. Android has neither a reachable
 * loopback browser context nor custom-scheme deep-link guarantees users can
 * trust, so this port uses OpenRouter's documented headless PKCE mode
 * (https://openrouter.ai/docs/use-cases/oauth-pkce): `callback_url` is
 * omitted and `key_label=Pathfinder` is sent instead, OpenRouter displays the
 * authorization code on screen, and the user pastes it back into the app.
 * The authorize-URL emission, manual-code prompt, authorization-input
 * parsing (raw code, `code=...`, or full redirect URL), token exchange
 * request/response handling, and error messages all mirror pi exactly.
 *
 * HTTP goes through the injected [OAuthHttpClient]; no network happens in
 * tests. Nothing secret is ever logged: exceptions carry only statuses and
 * server-provided error details, never the code, verifier, or key.
 */
class OpenRouterOAuthAuth(
    private val http: OAuthHttpClient,
    private val pkce: PkceGenerator = PkceGenerator(),
) : OAuthAuth {

    override val name: String = "OpenRouter OAuth"
    override val loginLabel: String = "Sign in with OpenRouter"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val challenge = pkce.generate()
        val authorizeUrl = buildString {
            append(AUTHORIZE_URL)
            append("?code_challenge=").append(urlEncode(challenge.challenge))
            append("&code_challenge_method=S256")
            append("&key_label=").append(urlEncode(APP_LABEL))
        }

        interaction.notify(
            AuthEvent.AuthUrl(
                url = authorizeUrl,
                instructions =
                    "Complete sign-in in your browser. OpenRouter will display an authorization code; " +
                    "copy it and paste it back into $APP_LABEL.",
            ),
        )

        val input = interaction.prompt(
            AuthPrompt.ManualCode("Complete sign-in in your browser, or paste the authorization code / redirect URL here:"),
        )
        val code = parseAuthorizationCodeInput(input) ?: throw IllegalStateException("Missing authorization code")
        interaction.notify(AuthEvent.Progress("Exchanging authorization code for an API key..."))
        return exchangeAuthorizationCode(code, challenge.verifier)
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
        const val APP_LABEL: String = "Pathfinder"
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
