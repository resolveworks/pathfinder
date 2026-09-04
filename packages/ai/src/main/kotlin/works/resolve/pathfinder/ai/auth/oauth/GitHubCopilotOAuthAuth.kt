package works.resolve.pathfinder.ai.auth.oauth

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Base64
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.strictDouble
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * GitHub Copilot OAuth account flow: device-code login against github.com or
 * a GitHub Enterprise domain, Copilot token exchange/refresh, and account
 * model discovery/enablement.
 *
 * Divergences from pi:
 * - All HTTP goes through the injected [OAuthHttpClient] with bounded
 *   timeouts; cancellation travels as coroutine cancellation. [fetchJson]
 *   is bounded at 30s where pi leaves it unbounded.
 * - [OAuthHttpResponse] carries no HTTP reason phrase, so error messages
 *   prefix with the bare status.
 * - Raw response bodies are never interpolated into error messages (pi
 *   interpolates `await response.text()`): only structured
 *   `error`/`error_description` fields from a JSON error object are
 *   preserved; unparseable or non-error bodies are redacted.
 * - pi's `GITHUB_COPILOT_MODELS` membership check uses the generated static
 *   model catalog, received as [knownModelIds]; no parallel model list is
 *   maintained.
 */
class GitHubCopilotOAuthAuth(
    private val http: OAuthHttpClient,
    private val knownModelIds: Set<String>,
    private val clock: Clock = Clock.System
) : OAuthAuth {

    override val name: String = "GitHub Copilot"

    override val isSubscription: Boolean = true

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val input = interaction.prompt(
            AuthPrompt.Text(
                message = "GitHub Enterprise URL/domain (blank for github.com)",
                placeholder = "company.ghe.com"
            )
        )
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            throw CancellationException("Login cancelled", error)
        }

        val trimmed = input.trim()
        val enterpriseDomain = normalizeDomain(input)
        if (trimmed.isNotEmpty() && enterpriseDomain == null) {
            throw IllegalStateException("Invalid GitHub Enterprise URL/domain")
        }
        val domain = enterpriseDomain ?: "github.com"

        val device = startDeviceFlow(domain)
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = device.verificationUri,
                intervalSeconds = device.intervalSeconds?.toInt(),
                expiresInSeconds = device.expiresInSeconds.toInt()
            )
        )

        val githubAccessToken = pollForGitHubAccessToken(domain, device)
        val credentials = refreshGitHubCopilotAccessToken(githubAccessToken, enterpriseDomain)
        val models = fetchGitHubCopilotModels(
            copilotToken = credentials.access,
            enterpriseDomain = enterpriseDomain,
            retryPolicy = RetryPolicy(maxRetries = 2, maxElapsedMs = 5000)
        )
        var enabledModelIds: List<String> = emptyList()
        if (models.policyModelIds.isNotEmpty()) {
            interaction.notify(AuthEvent.Progress("Enabling models..."))
            enabledModelIds = enableGitHubCopilotModels(
                token = credentials.access,
                modelIds = models.policyModelIds,
                enterpriseDomain = enterpriseDomain
            )
        }
        return credentials.copy(
            extras = credentials.extras + (
                "availableModelIds" to JsonArray(
                    (models.availableModelIds + enabledModelIds).distinct().map {
                        JsonPrimitive(it)
                    }
                )
                )
        )
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        refreshGitHubCopilotToken(credential.refresh, copilotEnterpriseDomain(credential))

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth = ModelAuth(
        apiKey = credential.access,
        baseUrl = getGitHubCopilotBaseUrl(
            credential.access,
            copilotEnterpriseDomain(credential)
        )
    )

    /**
     * WHATWG `new URL` lower-cases the host; [java.net.URI] preserves case,
     * so the host is explicitly lower-cased to keep hostname semantics
     * identical. Anything WHATWG accepts but `URI` rejects is null:
     * strictly rejecting rather than opening.
     */
    internal fun normalizeDomain(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return try {
            java.net.URI(if (trimmed.contains("://")) trimmed else "https://$trimmed")
                .host
                ?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
    }

    internal fun getUrls(domain: String): CopilotUrls = CopilotUrls(
        deviceCodeUrl = "https://$domain/login/device/code",
        accessTokenUrl = "https://$domain/login/oauth/access_token",
        copilotTokenUrl = "https://api.$domain/copilot_internal/v2/token"
    )

    internal data class CopilotUrls(
        val deviceCodeUrl: String,
        val accessTokenUrl: String,
        val copilotTokenUrl: String
    )

    /** Copilot tokens embed `proxy-ep=<host>`; the API host swaps the `proxy.` prefix. */
    internal fun getBaseUrlFromToken(token: String): String? {
        val match = PROXY_EP_REGEX.find(token) ?: return null
        val proxyHost = match.groupValues[1]
        val apiHost = proxyHost.replaceFirst("^proxy\\.".toRegex(), "api.")
        return "https://$apiHost"
    }

    internal fun getGitHubCopilotBaseUrl(token: String?, enterpriseDomain: String?): String {
        if (token != null) {
            getBaseUrlFromToken(token)?.let { return it }
        }
        if (enterpriseDomain != null) return "https://copilot-api.$enterpriseDomain"
        return INDIVIDUAL_BASE_URL
    }

    internal data class CopilotModelCatalog(
        val availableModelIds: List<String>,
        val policyModelIds: List<String>
    )

    internal fun parseGitHubCopilotModelCatalog(
        raw: JsonElement?,
        allowPolicyFallback: Boolean
    ): CopilotModelCatalog {
        val data = (raw as? JsonObject)?.get("data")
        if (data !is JsonArray) throw IllegalStateException("Invalid Copilot models response")

        data class AccountModel(
            val id: String,
            val pickerEnabled: Boolean,
            val policyState: String?
        )

        val accountModels = data.flatMap { rawItem ->
            val item = rawItem as? JsonObject ?: return@flatMap emptyList()
            val id = item.string("id") ?: return@flatMap emptyList()

            val capabilities = item.obj("capabilities")
            val supports = capabilities.obj("supports")
            if (supports?.get("tool_calls") == JsonPrimitive(false)) return@flatMap emptyList()

            listOf(
                AccountModel(
                    id = id,
                    pickerEnabled = item["model_picker_enabled"] == JsonPrimitive(true),
                    policyState = item.obj("policy")?.get("state").stringOrNull()
                )
            )
        }
        val pickerModelIds = accountModels
            .filter { it.pickerEnabled && it.policyState != "disabled" }
            .map { it.id }
        val usePolicyFallback = allowPolicyFallback && pickerModelIds.isEmpty()
        val availableModelIds =
            if (pickerModelIds.isNotEmpty() || !allowPolicyFallback) {
                pickerModelIds
            } else {
                accountModels.filter { it.policyState == "enabled" }.map { it.id }
            }
        val policyModelIds = accountModels
            .filter {
                it.policyState == "unconfigured" &&
                    it.id in knownModelIds &&
                    (it.pickerEnabled || usePolicyFallback)
            }
            .map { it.id }
        return CopilotModelCatalog(availableModelIds, policyModelIds)
    }

    internal data class RetryPolicy(val maxRetries: Int, val maxElapsedMs: Long)

    /**
     * The per-attempt timeout is clamped to the remaining elapsed budget, so
     * a fresh attempt can never spend more than the budget on a full
     * request timeout.
     */
    private suspend fun fetchWithRateLimitRetry(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray = ByteArray(0),
        retryPolicy: RetryPolicy
    ): OAuthHttpResponse {
        val retryDeadline =
            if (retryPolicy.maxRetries > 0 &&
                retryPolicy.maxElapsedMs > 0
            ) {
                clock.now().toEpochMilliseconds() +
                    retryPolicy.maxElapsedMs
            } else {
                null
            }
        var retry = 0
        while (true) {
            val attemptTimeoutMs =
                if (retryDeadline == null) {
                    PER_ATTEMPT_TIMEOUT_MS.toLong()
                } else {
                    (retryDeadline - clock.now().toEpochMilliseconds()).coerceIn(
                        1L,
                        PER_ATTEMPT_TIMEOUT_MS.toLong()
                    )
                }
            val response = http.execute(
                OAuthHttpRequest(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                    timeoutMs = attemptTimeoutMs.toInt()
                )
            )
            if (response.status != 429 || retry == retryPolicy.maxRetries) return response

            val retryAfter = response.headers["retry-after"]?.firstOrNull()
            var delayMs: Double = 500.0 * 2.0.pow(retry)
            if (retryAfter != null) {
                val seconds = parseFloatPrefix(retryAfter)
                delayMs =
                    if (seconds != null && seconds.isFinite()) {
                        seconds * 1000
                    } else {
                        parseHttpDateMs(retryAfter)?.let {
                            (it - clock.now().toEpochMilliseconds()).toDouble()
                        }
                            ?: Double.NaN
                    }
                if (delayMs.isNaN()) return response
            }
            delayMs = max(0.0, delayMs)
            if (retryDeadline != null &&
                delayMs >= (retryDeadline - clock.now().toEpochMilliseconds()).toDouble()
            ) {
                return response
            }
            delay(delayMs.toLong())
            retry++
        }
    }

    internal fun parseHttpDateMs(value: String): Long? = try {
        DateTimeFormatter.RFC_1123_DATE_TIME
            .parse(value.trim())
            .getLong(ChronoField.INSTANT_SECONDS) * 1000
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchGitHubCopilotModels(
        copilotToken: String,
        enterpriseDomain: String?,
        retryPolicy: RetryPolicy
    ): CopilotModelCatalog {
        val baseUrl = getGitHubCopilotBaseUrl(copilotToken, enterpriseDomain)
        // Some Individual accounts return false for every picker flag despite explicit enabled policies.
        // Limit the fallback to that endpoint so other account types keep strict picker semantics.
        val allowPolicyFallback = baseUrl == INDIVIDUAL_BASE_URL
        val response = fetchWithRateLimitRetry(
            url = "$baseUrl/models",
            method = "GET",
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $copilotToken"
            ) + COPILOT_HEADERS + mapOf("X-GitHub-Api-Version" to COPILOT_API_VERSION),
            retryPolicy = retryPolicy
        )
        if (response.status !in 200..299) {
            throw statusError(response.status, response.body)
        }
        val raw = try {
            lenientJson.parseToJsonElement(response.body.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IllegalStateException("Invalid Copilot models response")
        }
        return parseGitHubCopilotModelCatalog(raw, allowPolicyFallback)
    }

    private suspend fun fetchJson(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray
    ): JsonElement {
        val response = http.execute(
            OAuthHttpRequest(
                method = method,
                url = url,
                headers = headers,
                body = body,
                timeoutMs = REQUEST_TIMEOUT_MS
            )
        )
        if (response.status !in 200..299) {
            throw statusError(response.status, response.body)
        }
        return try {
            lenientJson.parseToJsonElement(response.body.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IllegalStateException("Invalid JSON (HTTP ${response.status})")
        }
    }

    internal data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Double?,
        val expiresInSeconds: Double
    )

    private suspend fun startDeviceFlow(domain: String): DeviceCodeResponse {
        val urls = getUrls(domain)
        val data = fetchJson(
            url = urls.deviceCodeUrl,
            method = "POST",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to COPILOT_USER_AGENT
            ),
            body = XaiOAuthAuth.formUrlEncode(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "scope" to "read:user"
                )
            )
        ).let { recordOr(it, "Invalid device code response") }

        val deviceCode = data.string("device_code")
        val userCode = data.string("user_code")
        val verificationUri = data.string("verification_uri")
        val interval = data.strictDouble("interval")
        val expiresIn = data.strictDouble("expires_in")

        // `interval` is optional but must be numeric when present.
        val intervalAbsent = data["interval"] == null || data["interval"] is JsonNull
        if (
            deviceCode == null || userCode == null || verificationUri == null ||
            (!intervalAbsent && interval == null) || expiresIn == null
        ) {
            throw IllegalStateException("Invalid device code response fields")
        }

        val normalizedUri = validateVerificationUri(verificationUri)

        return DeviceCodeResponse(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = normalizedUri,
            intervalSeconds = interval,
            expiresInSeconds = expiresIn
        )
    }

    private suspend fun pollForGitHubAccessToken(
        domain: String,
        device: DeviceCodeResponse
    ): String {
        val urls = getUrls(domain)
        return pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = device.expiresInSeconds.toLong(),
                waitBeforeFirstPoll = true,
                poll = {
                    val raw = fetchJson(
                        url = urls.accessTokenUrl,
                        method = "POST",
                        headers = mapOf(
                            "Accept" to "application/json",
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "User-Agent" to COPILOT_USER_AGENT
                        ),
                        body = XaiOAuthAuth.formUrlEncode(
                            mapOf(
                                "client_id" to CLIENT_ID,
                                "device_code" to device.deviceCode,
                                "grant_type" to DEVICE_GRANT_TYPE
                            )
                        )
                    ).let { recordOr(it, "Invalid device token response") }

                    when {
                        raw.string("access_token") != null ->
                            OAuthDeviceCodePollResult.Complete(raw.string("access_token")!!)

                        raw.string("error") != null -> when (raw.string("error")) {
                            "authorization_pending" -> OAuthDeviceCodePollResult.Pending

                            "slow_down" -> OAuthDeviceCodePollResult.SlowDown(
                                intervalSeconds = raw.strictDouble("interval")
                            )

                            else -> {
                                val description = raw.string("error_description")
                                val descriptionSuffix = description?.let { ": $it" } ?: ""
                                OAuthDeviceCodePollResult.Failed(
                                    "Device flow failed: ${raw.string(
                                        "error"
                                    )}$descriptionSuffix"
                                )
                            }
                        }

                        else -> OAuthDeviceCodePollResult.Failed("Invalid device token response")
                    }
                }
            ),
            clock = clock
        )
    }

    internal suspend fun refreshGitHubCopilotAccessToken(
        refreshToken: String,
        enterpriseDomain: String?
    ): OAuthCredential {
        val domain = enterpriseDomain ?: "github.com"
        val urls = getUrls(domain)

        val raw = fetchJson(
            url = urls.copilotTokenUrl,
            method = "GET",
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $refreshToken"
            ) + COPILOT_HEADERS,
            body = ByteArray(0)
        ).let { recordOr(it, "Invalid Copilot token response") }

        val token = raw.string("token")
        val expiresAt = raw.strictDouble("expires_at")
        if (token == null || expiresAt == null) {
            throw IllegalStateException("Invalid Copilot token response fields")
        }

        val extras = if (enterpriseDomain != null) {
            mapOf("enterpriseUrl" to JsonPrimitive(enterpriseDomain))
        } else {
            emptyMap()
        }
        return OAuthCredential(
            access = token,
            refresh = refreshToken,
            expires = (expiresAt * 1000).toLong() - REFRESH_SKEW_MS,
            extras = extras
        )
    }

    internal suspend fun refreshGitHubCopilotToken(
        refreshToken: String,
        enterpriseDomain: String?
    ): OAuthCredential {
        val credentials = refreshGitHubCopilotAccessToken(refreshToken, enterpriseDomain)
        val models = fetchGitHubCopilotModels(
            copilotToken = credentials.access,
            enterpriseDomain = enterpriseDomain,
            retryPolicy = RetryPolicy(maxRetries = 0, maxElapsedMs = 0)
        )
        return credentials.copy(
            extras = credentials.extras +
                (
                    "availableModelIds" to
                        JsonArray(models.availableModelIds.map { JsonPrimitive(it) })
                    )
        )
    }

    /**
     * Best effort: a non-cancellation transport failure reports false, but a
     * 429 that exhausts retries throws.
     */
    private suspend fun enableGitHubCopilotModel(
        token: String,
        modelId: String,
        enterpriseDomain: String?
    ): Boolean {
        val baseUrl = getGitHubCopilotBaseUrl(token, enterpriseDomain)
        val url = "$baseUrl/models/$modelId/policy"

        val response = try {
            fetchWithRateLimitRetry(
                url = url,
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $token",
                    "openai-intent" to "chat-policy",
                    "x-interaction-type" to "chat-policy"
                ) + COPILOT_HEADERS,
                body = jsonPolicyBody(),
                retryPolicy = RetryPolicy(maxRetries = 2, maxElapsedMs = 5000)
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return false
        }
        if (response.status == 429) {
            throw statusError(response.status, response.body)
        }
        return response.status in 200..299
    }

    private suspend fun enableGitHubCopilotModels(
        token: String,
        modelIds: List<String>,
        enterpriseDomain: String?
    ): List<String> {
        val enabledModelIds = mutableListOf<String>()
        for (modelId in modelIds) {
            try {
                if (enableGitHubCopilotModel(token, modelId, enterpriseDomain)) {
                    enabledModelIds += modelId
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                break
            }
        }
        return enabledModelIds
    }

    internal fun knownModelIdsForTest(): Set<String> = knownModelIds

    internal fun copilotEnterpriseDomain(credential: OAuthCredential): String? {
        val enterpriseUrl = credential.extras["enterpriseUrl"].stringOrNull()
        if (enterpriseUrl.isNullOrEmpty()) return null
        return normalizeDomain(enterpriseUrl)
    }

    /**
     * Mirrors the upstream JS guard where `typeof [] === "object"`: arrays
     * become the empty object and fail downstream field validation; scalars
     * and null fail with [message].
     */
    private fun recordOr(raw: JsonElement?, message: String): JsonObject = when (raw) {
        is JsonObject -> raw
        is JsonArray -> JsonObject(emptyMap())
        else -> throw IllegalStateException(message)
    }

    /**
     * Raw response bodies never reach exception messages: only structured
     * `error`/`error_description` string fields from a JSON error object are
     * preserved; anything else (unparseable body, non-object JSON, no error
     * fields) is fully redacted. Those fields are provider-authored text and
     * could echo request material, so the app neither logs nor projects
     * these exception messages.
     */
    internal fun statusError(status: Int, body: ByteArray): IllegalStateException =
        IllegalStateException("$status: ${safeErrorDetail(body)}")

    private fun safeErrorDetail(body: ByteArray): String {
        val parsed = try {
            lenientJson.parseToJsonElement(body.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return REDACTED_BODY
        }
        val obj = parsed as? JsonObject ?: return REDACTED_BODY
        val error = obj.string("error")
        val description = obj.string("error_description")
        return listOfNotNull(error, description).joinToString(": ").ifEmpty { REDACTED_BODY }
    }

    /**
     * Mirrors JS `Number.parseFloat` prefix semantics for `Retry-After`: the
     * longest numeric prefix of the trimmed value parses (`"1x"`,
     * `" 2.5 sec"`), anything else (no numeric prefix, `"Infinity"`) is null.
     */
    internal fun parseFloatPrefix(value: String): Double? {
        val trimmed = value.trim()
        val match =
            Regex("""^[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""").find(trimmed)
                ?: return null
        return match.value.toDoubleOrNull()
    }

    /**
     * The device flow's verification URI is opened in the user's browser, so
     * it must be a strict http(s) URL. The JDK has no WHATWG `href`
     * normalizer, so the safe form is rebuilt: lower-cased scheme and host,
     * scheme-default ports (`:80`/`:443`) omitted, empty path becomes `/`,
     * query and fragment preserved verbatim. Anything `URI` rejects (opaque
     * authority-less forms like `http:foo`, control characters, invalid
     * escapes) is treated as untrusted rather than opened.
     */
    internal fun validateVerificationUri(raw: String): String {
        val uri = try {
            java.net.URI(raw)
        } catch (_: Exception) {
            null
        } ?: throw IllegalStateException("Untrusted verification_uri in device code response")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "https" && scheme != "http") || uri.host.isNullOrEmpty()) {
            throw IllegalStateException("Untrusted verification_uri in device code response")
        }
        val host = uri.host.lowercase(Locale.ROOT)
        val defaultPort =
            (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
        val port = if (uri.port != -1 && !defaultPort) ":${uri.port}" else ""
        val path = uri.rawPath.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""
        return "$scheme://$host$port$path$query$fragment"
    }

    private fun jsonPolicyBody(): ByteArray =
        buildJsonObject { put("state", "enabled") }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        val CLIENT_ID: String =
            Base64.getDecoder().decode("SXYxLmI1MDdhMDhjODdlY2ZlOTg=").toString(Charsets.US_ASCII)

        const val COPILOT_USER_AGENT: String = "GitHubCopilotChat/0.35.0"

        val COPILOT_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to COPILOT_USER_AGENT,
            "Editor-Version" to "vscode/1.107.0",
            "Editor-Plugin-Version" to "copilot-chat/0.35.0",
            "Copilot-Integration-Id" to "vscode-chat"
        )

        const val COPILOT_API_VERSION: String = "2026-06-01"

        const val INDIVIDUAL_BASE_URL: String = "https://api.individual.githubcopilot.com"

        const val DEVICE_GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:device_code"

        const val REFRESH_SKEW_MS: Long = 5 * 60 * 1000

        const val PER_ATTEMPT_TIMEOUT_MS: Int = 5000

        const val REQUEST_TIMEOUT_MS: Int = 30_000

        internal const val REDACTED_BODY: String = "<redacted response body>"

        private val PROXY_EP_REGEX = Regex("proxy-ep=([^;]+)")
    }
}
