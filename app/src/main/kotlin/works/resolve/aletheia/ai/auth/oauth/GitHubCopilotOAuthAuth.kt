package works.resolve.aletheia.ai.auth.oauth

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.aletheia.ai.auth.AuthEvent
import works.resolve.aletheia.ai.auth.AuthInteraction
import works.resolve.aletheia.ai.auth.AuthPrompt
import works.resolve.aletheia.ai.auth.ModelAuth
import works.resolve.aletheia.ai.auth.OAuthAuth
import works.resolve.aletheia.ai.auth.OAuthCredential

/**
 * GitHub Copilot OAuth account flow, ported from pi
 * `packages/ai/src/auth/oauth/github-copilot.ts`.
 *
 * Mirrors the upstream file symbol-for-symbol: `loginGitHubCopilot`
 * ([login]), `refreshGitHubCopilotToken` ([refresh]), `toAuth`,
 * `normalizeDomain`, `getUrls`, `getBaseUrlFromToken`,
 * `getGitHubCopilotBaseUrl`, `parseGitHubCopilotModelCatalog`,
 * `fetchWithRateLimitRetry`, `fetchGitHubCopilotModels`, `fetchJson`,
 * `startDeviceFlow`, `pollForGitHubAccessToken`,
 * `refreshGitHubCopilotAccessToken`, `enableGitHubCopilotModel(s)`, and
 * `copilotEnterpriseDomain`. Token polling runs through the shared
 * [pollOAuthDeviceCodeFlow] (pi `device-code.ts`) with
 * `waitBeforeFirstPoll = true`.
 *
 * Divergences from pi (documented per AGENTS.md):
 * - All HTTP goes through the injected [OAuthHttpClient] with bounded
 *   timeouts instead of `fetch` + `AbortSignal`; cancellation travels as
 *   coroutine cancellation. `fetchJson` (which pi leaves unbounded) uses a
 *   bounded 30s exchange like the other Aletheia flows; pi's per-attempt
 *   5s `AbortSignal.timeout` inside the rate-limit retry is kept verbatim.
 * - [OAuthHttpResponse] carries no HTTP reason phrase, so pi's
 *   `${status} ${statusText}` prefix becomes `${status}`. Raw response
 *   bodies are never interpolated into error messages (a security
 *   divergence from pi's `await response.text()`): only structured
 *   `error`/`error_description` fields from a JSON error object are
 *   preserved; unparseable or non-error bodies are redacted.
 * - pi's `GITHUB_COPILOT_MODELS` membership check (`Object.hasOwn`) uses the
 *   generated static model catalog; the constructor receives the same ids
 *   ([knownModelIds]) from the generated `models-catalog.json` provider
 *   entry, so no parallel model list is maintained.
 * - `Date.now()` (retry deadlines, expiry math) is read through the
 *   internal [now] seam (system clock by default) for deterministic tests.
 *
 * Nothing secret is ever logged or placed in exception messages by this
 * class: errors carry HTTP statuses and server-provided response text only.
 */
class GitHubCopilotOAuthAuth(
    private val http: OAuthHttpClient,
    /** pi `GITHUB_COPILOT_MODELS` ids (generated catalog entry's model ids). */
    private val knownModelIds: Set<String>,
    private val now: () -> Long = { System.currentTimeMillis() },
) : OAuthAuth {

    override val name: String = "GitHub Copilot"

    /** pi `isSubscription: true`. */
    override val isSubscription: Boolean = true

    // --- login / refresh / toAuth (pi `loginGitHubCopilot` etc.) ---

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val input = interaction.prompt(
            AuthPrompt.Text(
                message = "GitHub Enterprise URL/domain (blank for github.com)",
                placeholder = "company.ghe.com",
            ),
        )
        if (coroutineContext[Job]?.isActive == false) throw CancellationException("Login cancelled")

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
                expiresInSeconds = device.expiresInSeconds.toInt(),
            ),
        )

        val githubAccessToken = pollForGitHubAccessToken(domain, device)
        val credentials = refreshGitHubCopilotAccessToken(githubAccessToken, enterpriseDomain)
        val models = fetchGitHubCopilotModels(
            copilotToken = credentials.access,
            enterpriseDomain = enterpriseDomain,
            retryPolicy = RetryPolicy(maxRetries = 2, maxElapsedMs = 5000),
        )
        var enabledModelIds: List<String> = emptyList()
        if (models.policyModelIds.isNotEmpty()) {
            interaction.notify(AuthEvent.Progress("Enabling models..."))
            enabledModelIds = enableGitHubCopilotModels(
                token = credentials.access,
                modelIds = models.policyModelIds,
                enterpriseDomain = enterpriseDomain,
            )
        }
        return credentials.copy(
            extras = credentials.extras + (
                "availableModelIds" to JsonArray(
                    (models.availableModelIds + enabledModelIds).distinct().map { JsonPrimitive(it) },
                )
                ),
        )
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        refreshGitHubCopilotToken(credential.refresh, copilotEnterpriseDomain(credential))

    /**
     * Port of pi `toAuth`: derives the credential-specific proxy endpoint for
     * each request — API key plus the per-account base URL.
     */
    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(
            apiKey = credential.access,
            baseUrl = getGitHubCopilotBaseUrl(credential.access, copilotEnterpriseDomain(credential)),
        )

    // --- domain normalization / URL derivation (pi `normalizeDomain`, `getUrls`, base URL helpers) ---

    /**
     * Port of pi `normalizeDomain`: accepts a bare domain or a URL and
     * returns its hostname, or null when unparseable.
     *
     * Divergence from pi (documented per AGENTS.md): WHATWG `new URL`
     * lower-cases the host; the JDK [java.net.URI] preserves case, so the
     * host is explicitly lower-cased here to keep hostname semantics
     * identical. Anything WHATWG accepts but `URI` rejects is null
     * (treated as invalid input), strictly rejecting rather than opening.
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

    /** Port of pi `getUrls`. */
    internal fun getUrls(domain: String): CopilotUrls =
        CopilotUrls(
            deviceCodeUrl = "https://$domain/login/device/code",
            accessTokenUrl = "https://$domain/login/oauth/access_token",
            copilotTokenUrl = "https://api.$domain/copilot_internal/v2/token",
        )

    internal data class CopilotUrls(
        val deviceCodeUrl: String,
        val accessTokenUrl: String,
        val copilotTokenUrl: String,
    )

    /**
     * Port of pi `getBaseUrlFromToken`. Token format:
     * `tid=...;exp=...;proxy-ep=proxy.individual.githubcopilot.com;...`;
     * `proxy.xxx` becomes `api.xxx`.
     */
    internal fun getBaseUrlFromToken(token: String): String? {
        val match = PROXY_EP_REGEX.find(token) ?: return null
        val proxyHost = match.groupValues[1]
        val apiHost = proxyHost.replaceFirst("^proxy\\.".toRegex(), "api.")
        return "https://$apiHost"
    }

    /** Port of pi `getGitHubCopilotBaseUrl`. */
    internal fun getGitHubCopilotBaseUrl(token: String?, enterpriseDomain: String?): String {
        if (token != null) {
            getBaseUrlFromToken(token)?.let { return it }
        }
        if (enterpriseDomain != null) return "https://copilot-api.$enterpriseDomain"
        return INDIVIDUAL_BASE_URL
    }

    // --- model catalog (pi `parseGitHubCopilotModelCatalog`) ---

    /** Port of pi's `{ availableModelIds, policyModelIds }` parse result. */
    internal data class CopilotModelCatalog(
        val availableModelIds: List<String>,
        val policyModelIds: List<String>,
    )

    /** Port of pi `parseGitHubCopilotModelCatalog` (strict shape validation). */
    internal fun parseGitHubCopilotModelCatalog(raw: JsonElement?, allowPolicyFallback: Boolean): CopilotModelCatalog {
        val data = (raw as? JsonObject)?.get("data")
        if (data !is JsonArray) throw IllegalStateException("Invalid Copilot models response")

        data class AccountModel(val id: String, val pickerEnabled: Boolean, val policyState: String?)

        val accountModels = data.flatMap { rawItem ->
            val item = rawItem as? JsonObject
            val id = (item?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (item == null || id == null) return@flatMap emptyList()

            val capabilities = item["capabilities"] as? JsonObject
            val supports = capabilities?.get("supports") as? JsonObject
            // pi: `supports?.tool_calls === false` — only an explicit boolean false skips.
            if (supports?.get("tool_calls") == JsonPrimitive(false)) return@flatMap emptyList()

            listOf(
                AccountModel(
                    id = id,
                    pickerEnabled = item["model_picker_enabled"] == JsonPrimitive(true),
                    policyState = (item["policy"] as? JsonObject)?.get("state")
                        ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content,
                ),
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

    // --- rate-limited fetch (pi `fetchWithRateLimitRetry`) ---

    /** Port of pi's `{ maxRetries, maxElapsedMs }` retry policy. */
    internal data class RetryPolicy(val maxRetries: Int, val maxElapsedMs: Long)

    /**
     * Port of pi `fetchWithRateLimitRetry`: retries 429s with exponential
     * backoff (500ms * 2^retry), honoring `Retry-After` (seconds or
     * HTTP-date) and stopping at the retry count or elapsed budget. Sleeps
     * are cancellable; per-attempt timeout is pi's 5s `AbortSignal.timeout`
     * clamped to the remaining elapsed budget (pi's `AbortSignal.any` of
     * per-attempt and budget signals), so retries can never spend more than
     * the budget on a fresh full request timeout.
     */
    private suspend fun fetchWithRateLimitRetry(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray = ByteArray(0),
        retryPolicy: RetryPolicy,
    ): OAuthHttpResponse {
        val retryDeadline =
            if (retryPolicy.maxRetries > 0 && retryPolicy.maxElapsedMs > 0) now() + retryPolicy.maxElapsedMs else null
        var retry = 0
        while (true) {
            val attemptTimeoutMs =
                if (retryDeadline == null) {
                    PER_ATTEMPT_TIMEOUT_MS.toLong()
                } else {
                    (retryDeadline - now()).coerceIn(1L, PER_ATTEMPT_TIMEOUT_MS.toLong())
                }
            val response = http.execute(
                OAuthHttpRequest(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                    timeoutMs = attemptTimeoutMs.toInt(),
                ),
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
                        parseHttpDateMs(retryAfter)?.let { (it - now()).toDouble() } ?: Double.NaN
                    }
                if (delayMs.isNaN()) return response
            }
            delayMs = max(0.0, delayMs)
            if (retryDeadline != null && delayMs >= (retryDeadline - now()).toDouble()) return response
            delay(delayMs.toLong())
            retry++
        }
    }

    /** Parses an HTTP-date `Retry-After` value to epoch ms (pi's `Date.parse`). */
    internal fun parseHttpDateMs(value: String): Long? = try {
        DateTimeFormatter.RFC_1123_DATE_TIME
            .parse(value.trim())
            .getLong(ChronoField.INSTANT_SECONDS) * 1000
        } catch (_: Exception) {
            null
        }

    // --- model fetch / enablement (pi `fetchGitHubCopilotModels`, `enableGitHubCopilotModel(s)`) ---

    private suspend fun fetchGitHubCopilotModels(
        copilotToken: String,
        enterpriseDomain: String?,
        retryPolicy: RetryPolicy,
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
                "Authorization" to "Bearer $copilotToken",
            ) + COPILOT_HEADERS + mapOf("X-GitHub-Api-Version" to COPILOT_API_VERSION),
            retryPolicy = retryPolicy,
        )
        if (response.status !in 200..299) {
            throw statusError(response.status, response.body)
        }
        val raw = try {
            Json.parseToJsonElement(response.body.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IllegalStateException("Invalid Copilot models response")
        }
        return parseGitHubCopilotModelCatalog(raw, allowPolicyFallback)
    }

    private suspend fun fetchJson(url: String, method: String, headers: Map<String, String>, body: ByteArray): JsonElement {
        val response = http.execute(
            OAuthHttpRequest(method = method, url = url, headers = headers, body = body, timeoutMs = REQUEST_TIMEOUT_MS),
        )
        if (response.status !in 200..299) {
            throw statusError(response.status, response.body)
        }
        return try {
            Json.parseToJsonElement(response.body.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IllegalStateException("Invalid JSON (HTTP ${response.status})")
        }
    }

    // --- device authorization (pi `startDeviceFlow`, `pollForGitHubAccessToken`) ---

    /** Port of pi `DeviceCodeResponse`. */
    internal data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Double?,
        val expiresInSeconds: Double,
    )

    private suspend fun startDeviceFlow(domain: String): DeviceCodeResponse {
        val urls = getUrls(domain)
        val data = fetchJson(
            url = urls.deviceCodeUrl,
            method = "POST",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to COPILOT_USER_AGENT,
            ),
            body = XaiOAuthAuth.formUrlEncode(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "scope" to "read:user",
                ),
            ),
        ).let { recordOr(it, "Invalid device code response") }

        val deviceCode = data.stringField("device_code")
        val userCode = data.stringField("user_code")
        val verificationUri = data.stringField("verification_uri")
        val interval = data.numberField("interval")
        val expiresIn = data.numberField("expires_in")

        // pi: `interval` may be absent but must be a number when present.
        val intervalAbsent = data["interval"] == null || data["interval"] is JsonNull
        if (
            deviceCode == null || userCode == null || verificationUri == null ||
            (!intervalAbsent && interval == null) || expiresIn == null
        ) {
            throw IllegalStateException("Invalid device code response fields")
        }

        // The verification URI is opened in the user's browser and to prevent `open` from
        // opening an executable or similar, we force it to be an http(s) URL.
        val normalizedUri = validateVerificationUri(verificationUri)

        return DeviceCodeResponse(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = normalizedUri,
            intervalSeconds = interval,
            expiresInSeconds = expiresIn,
        )
    }

    private suspend fun pollForGitHubAccessToken(domain: String, device: DeviceCodeResponse): String {
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
                            "User-Agent" to COPILOT_USER_AGENT,
                        ),
                        body = XaiOAuthAuth.formUrlEncode(
                            mapOf(
                                "client_id" to CLIENT_ID,
                                "device_code" to device.deviceCode,
                                "grant_type" to DEVICE_GRANT_TYPE,
                            ),
                        ),
                    ).let { recordOr(it, "Invalid device token response") }

                    when {
                        raw.stringField("access_token") != null ->
                            OAuthDeviceCodePollResult.Complete(raw.stringField("access_token")!!)

                        raw.stringField("error") != null -> when (raw.stringField("error")) {
                                "authorization_pending" -> OAuthDeviceCodePollResult.Pending
                                "slow_down" -> OAuthDeviceCodePollResult.SlowDown(
                                    intervalSeconds = raw.numberField("interval"),
                                )
                                else -> {
                                    val description = raw.stringField("error_description")
                                    val descriptionSuffix = description?.let { ": $it" } ?: ""
                                    OAuthDeviceCodePollResult.Failed(
                                        "Device flow failed: ${raw.stringField("error")}$descriptionSuffix",
                                    )
                                }
                            }

                        else -> OAuthDeviceCodePollResult.Failed("Invalid device token response")
                    }
                },
            ),
            now = now,
        )
    }

    // --- Copilot token exchange / refresh (pi `refreshGitHubCopilotAccessToken`, `refreshGitHubCopilotToken`) ---

    /**
     * Port of pi `refreshGitHubCopilotAccessToken`: exchanges the GitHub
     * OAuth token for a Copilot token. `expires` is pi's exact skew math
     * (`expires_at * 1000 - 5min`); `enterpriseUrl` is stored as a credential
     * extra only when an enterprise domain is in play (pi's undefined field
     * is simply absent from the JSON record).
     */
    internal suspend fun refreshGitHubCopilotAccessToken(
        refreshToken: String,
        enterpriseDomain: String?,
    ): OAuthCredential {
        val domain = enterpriseDomain ?: "github.com"
        val urls = getUrls(domain)

        val raw = fetchJson(
            url = urls.copilotTokenUrl,
            method = "GET",
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $refreshToken",
            ) + COPILOT_HEADERS,
            body = ByteArray(0),
        ).let { recordOr(it, "Invalid Copilot token response") }

        val token = raw.stringField("token")
        val expiresAt = raw.numberField("expires_at")
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
            extras = extras,
        )
    }

    /** Port of pi `refreshGitHubCopilotToken`: exchange + account model list (no retries). */
    internal suspend fun refreshGitHubCopilotToken(
        refreshToken: String,
        enterpriseDomain: String?,
    ): OAuthCredential {
        val credentials = refreshGitHubCopilotAccessToken(refreshToken, enterpriseDomain)
        val models = fetchGitHubCopilotModels(
            copilotToken = credentials.access,
            enterpriseDomain = enterpriseDomain,
            retryPolicy = RetryPolicy(maxRetries = 0, maxElapsedMs = 0),
        )
        return credentials.copy(
            extras = credentials.extras +
                ("availableModelIds" to JsonArray(models.availableModelIds.map { JsonPrimitive(it) }))
        )
    }

    // --- policy enablement (pi `enableGitHubCopilotModel` / `enableGitHubCopilotModels`) ---

    /**
     * Port of pi `enableGitHubCopilotModel`: POSTs
     * `{"state":"enabled"}` to the model's policy endpoint. Any
     * non-cancellation failure (network error, other non-ok status) returns
     * false; an exhausted 429 throws.
     */
    private suspend fun enableGitHubCopilotModel(
        token: String,
        modelId: String,
        enterpriseDomain: String?,
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
                    "x-interaction-type" to "chat-policy",
                ) + COPILOT_HEADERS,
                body = jsonPolicyBody(),
                retryPolicy = RetryPolicy(maxRetries = 2, maxElapsedMs = 5000),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // pi catches any non-abort fetch failure and reports false; the
            // batch continues with the next model.
            return false
        }
        if (response.status == 429) {
            throw statusError(response.status, response.body)
        }
        return response.status in 200..299
    }

    /**
     * Port of pi `enableGitHubCopilotModels`: policy updates are best
     * effort; a false enablement (any non-cancellation transport failure)
     * continues with the next model, and only a thrown error (exhausted
     * rate limiting) stops the batch; cancellation always propagates.
     */
    private suspend fun enableGitHubCopilotModels(
        token: String,
        modelIds: List<String>,
        enterpriseDomain: String?,
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

    /** Test seam: pi `GITHUB_COPILOT_MODELS` id set as injected. */
    internal fun knownModelIdsForTest(): Set<String> = knownModelIds

    /** Port of pi `copilotEnterpriseDomain`. */
    internal fun copilotEnterpriseDomain(credential: OAuthCredential): String? {
        val enterpriseUrl = credential.extras["enterpriseUrl"]?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }?.content
        if (enterpriseUrl.isNullOrEmpty()) return null
        return normalizeDomain(enterpriseUrl)
    }

    // --- JSON field helpers (pi's inline `typeof` checks) ---

    /**
     * pi's `!raw || typeof raw !== "object"` guard: JSON objects pass
     * through; arrays pass pi's check too (JS arrays are objects) but then
     * fail field validation; scalars/null fail with [message].
     */
    private fun recordOr(raw: JsonElement?, message: String): JsonObject = when (raw) {
        is JsonObject -> raw
        is JsonArray -> JsonObject(emptyMap())
        else -> throw IllegalStateException(message)
    }

    private fun JsonObject.stringField(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.numberField(field: String): Double? =
        (this[field] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull && !it.isString }
            ?.content
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }

    /**
     * Safe non-OK failure for a status/body pair (Aletheia security
     * divergence from pi's raw `response.text()` interpolation): the message
     * carries the status plus only structured `error`/`error_description`
     * string fields from a JSON error object; anything else (unparseable
     * body, non-object JSON, no error fields) is fully redacted so response
     * bodies can never leak credential material into exceptions or logs.
     */
    internal fun statusError(status: Int, body: ByteArray): IllegalStateException =
        IllegalStateException("$status: ${safeErrorDetail(body)}")

    private fun safeErrorDetail(body: ByteArray): String {
        val parsed = try {
            Json.parseToJsonElement(body.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return REDACTED_BODY
        }
        val obj = parsed as? JsonObject ?: return REDACTED_BODY
        val error = obj.stringField("error")
        val description = obj.stringField("error_description")
        return listOfNotNull(error, description).joinToString(": ").ifEmpty { REDACTED_BODY }
    }

    /**
     * Port of JS `Number.parseFloat` for the `Retry-After` numeric form: the
     * longest numeric prefix of the trimmed value parses (`"1x"`, `" 2.5 sec"`,
     * `"1e2foo"`), anything else (no numeric prefix, `"Infinity"`) is null.
     */
    internal fun parseFloatPrefix(value: String): Double? {
        val trimmed = value.trim()
        val match = Regex("""^[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""").find(trimmed)
            ?: return null
        return match.value.toDoubleOrNull()
    }

    /**
     * Trust check plus normalization for the device flow's verification URI
     * (pi parses with WHATWG `new URL` and returns `parsedUri.href`).
     *
     * Divergence from pi (documented per AGENTS.md): the JDK has no href
     * normalizer, so the safe form is rebuilt here — lower-cased scheme and
     * host, scheme-default ports (`:80`/`:443`) omitted, empty path becomes
     * `/`, query and fragment preserved verbatim. The check is equally
     * strict-or-stricter than WHATWG: the URI must carry an http(s) scheme
     * and a non-empty authority, and anything `URI` rejects (opaque
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
        val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
        val port = if (uri.port != -1 && !defaultPort) ":${uri.port}" else ""
        val path = uri.rawPath.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""
        return "$scheme://$host$port$path$query$fragment"
    }

    private fun jsonPolicyBody(): ByteArray =
        buildJsonObject { put("state", "enabled") }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** pi `CLIENT_ID` (pi stores it base64-decoded at module load). */
        val CLIENT_ID: String =
            Base64.getDecoder().decode("SXYxLmI1MDdhMDhjODdlY2ZlOTg=").toString(Charsets.US_ASCII)

        /** pi `COPILOT_HEADERS["User-Agent"]`. */
        const val COPILOT_USER_AGENT: String = "GitHubCopilotChat/0.35.0"

        /** pi `COPILOT_HEADERS`. */
        val COPILOT_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to COPILOT_USER_AGENT,
            "Editor-Version" to "vscode/1.107.0",
            "Editor-Plugin-Version" to "copilot-chat/0.35.0",
            "Copilot-Integration-Id" to "vscode-chat",
        )

        /** pi `COPILOT_API_VERSION`. */
        const val COPILOT_API_VERSION: String = "2026-06-01"

        /** pi's individual-account default base URL. */
        const val INDIVIDUAL_BASE_URL: String = "https://api.individual.githubcopilot.com"

        /** pi device grant type (RFC 8628 section 3.4). */
        const val DEVICE_GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:device_code"

        /** pi `5 * 60 * 1000` expiry skew in `refreshGitHubCopilotAccessToken`. */
        const val REFRESH_SKEW_MS: Long = 5 * 60 * 1000

        /** pi's `AbortSignal.timeout(5000)` per attempt in `fetchWithRateLimitRetry`. */
        const val PER_ATTEMPT_TIMEOUT_MS: Int = 5000

        /** Bounded exchange timeout for pi's otherwise-unbounded `fetchJson` calls (Aletheia divergence). */
        const val REQUEST_TIMEOUT_MS: Int = 30_000

        /** Redaction marker for response bodies that carry no safe structured error detail. */
        internal const val REDACTED_BODY: String = "<redacted response body>"

        private val PROXY_EP_REGEX = Regex("proxy-ep=([^;]+)")
    }
}
