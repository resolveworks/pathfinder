package works.resolve.pathfinder.ai.api

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.ProviderAuthException
import works.resolve.pathfinder.ai.ProviderResponse
import works.resolve.pathfinder.ai.ProviderStreamException
import works.resolve.pathfinder.ai.SessionAffinityFormat
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.ToolChoice
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.hasHeader
import works.resolve.pathfinder.ai.headersToRecord
import works.resolve.pathfinder.ai.mergeHeaders
import works.resolve.pathfinder.ai.toModelThinkingLevel
import works.resolve.pathfinder.ai.toToolChoice
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.ClientIdentity
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.formatProviderError
import works.resolve.pathfinder.ai.utils.getDeclaredTools
import works.resolve.pathfinder.ai.utils.getIdentityHeaders
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.normalizeProviderError
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.ai.utils.resolveSamplingParams
import works.resolve.pathfinder.ai.utils.resolveTranscript
import works.resolve.pathfinder.ai.utils.resolveTranscriptTools
import works.resolve.pathfinder.telemetry.TelemetryContext

/**
 * Streaming adapter for the OpenAI Responses API.
 *
 * Divergence from pi: aborts surface as coroutine cancellation, which ends
 * the flow without an Error event — the established Pathfinder convention
 * (see OpenAiCompletionsApi).
 */

// OpenAI Responses rejects max_output_tokens below 16.
private const val OPENAI_RESPONSES_MIN_OUTPUT_TOKENS = 16
private const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"
private const val CHATGPT_WORKSPACE_LIMIT_HINT =
    "You've reached your ChatGPT workspace's usage limit. " +
        "Ask your workspace admin to increase it at " +
        "admin.openai.com → Workspace → Usage limits."

// Fixed reasoning.effort while configuration_update items carry the thinking level.
private const val CONFIGURATION_UPDATE_REQUEST_EFFORT = "medium"

/**
 * OpenAI API keys start with `sk-`; a different credential sent directly to OpenAI
 * is a Sign in with ChatGPT access token.
 */
private fun isChatGPTSignIn(model: Model, apiKey: String?): Boolean = model.provider == "openai" &&
    model.baseUrl == "https://api.openai.com/v1" &&
    apiKey != null &&
    !apiKey.startsWith("sk-")

/** Header-based auth stands in for a key ("unused" sentinel). */
internal fun getClientApiKey(
    provider: String,
    apiKey: String?,
    headers: Map<String, String?>
): String {
    if (apiKey != null) return apiKey
    if (hasHeader(headers, "authorization") ||
        hasHeader(headers, "cf-aig-authorization")
    ) {
        return "unused"
    }
    throw ProviderAuthException("No API key for provider: $provider")
}

internal fun detectSessionAffinityFormat(model: Model): SessionAffinityFormat =
    if (model.provider == "openrouter" || model.baseUrl.contains("openrouter.ai")) {
        SessionAffinityFormat.OPENROUTER
    } else {
        SessionAffinityFormat.OPENAI
    }

internal fun getCompat(model: Model): ResolvedResponsesCompat {
    val compat = model.responsesCompat
    return ResolvedResponsesCompat(
        supportsDeveloperRole = compat?.supportsDeveloperRole ?: true,
        supportsMidConvoSystemMessages = compat?.supportsMidConvoSystemMessages ?: false,
        sessionAffinityFormat = compat?.sessionAffinityFormat ?: detectSessionAffinityFormat(model),
        supportsLongCacheRetention = compat?.supportsLongCacheRetention ?: true,
        supportsStrictMode = compat?.supportsStrictMode ?: false,
        supportsOpenAIGrammarTools = compat?.supportsOpenAIGrammarTools ?: false,
        supportsAdditionalTools = compat?.supportsAdditionalTools ?: false,
        supportsToolSearch = compat?.supportsToolSearch ?: false,
        supportsExplicitPromptCacheMode = compat?.supportsExplicitPromptCacheMode ?: false,
        supportsMaxOutputTokens = compat?.supportsMaxOutputTokens ?: true,
        supportsMidConvoEffort = compat?.supportsMidConvoEffort ?: false
    )
}

internal data class ResolvedResponsesCompat(
    val supportsDeveloperRole: Boolean,
    val supportsMidConvoSystemMessages: Boolean,
    val sessionAffinityFormat: SessionAffinityFormat,
    val supportsLongCacheRetention: Boolean,
    val supportsStrictMode: Boolean,
    val supportsOpenAIGrammarTools: Boolean,
    val supportsAdditionalTools: Boolean,
    val supportsToolSearch: Boolean,
    val supportsExplicitPromptCacheMode: Boolean,
    /** Default true; when false, `max_output_tokens` is not sent (some gateways reject it). */
    val supportsMaxOutputTokens: Boolean,
    val supportsMidConvoEffort: Boolean
)

/** Effort to send in configuration_update items, or null to send it as reasoning.effort. */
private fun getConfigurationUpdateEffort(
    model: Model,
    options: OpenAiResponsesOptions?,
    compat: ResolvedResponsesCompat
): String? {
    val reasoningEffort = options?.reasoningEffort
    if (!compat.supportsMidConvoEffort ||
        reasoningEffort == null ||
        // Sign in with ChatGPT rejects configuration_update items.
        isChatGPTSignIn(model, options.apiKey)
    ) {
        return null
    }
    return model.thinkingLevelMap?.takeIf { it.isSpecified(reasoningEffort) }
        ?.forLevel(reasoningEffort)
        ?: reasoningEffort.name.lowercase()
}

internal fun getPromptCacheRetention(
    compat: ResolvedResponsesCompat,
    cacheRetention: CacheRetention
): String? = if (cacheRetention == CacheRetention.LONG && compat.supportsLongCacheRetention &&
    !compat.supportsExplicitPromptCacheMode
) {
    "24h"
} else {
    null
}

internal fun getPromptCacheOptions(
    compat: ResolvedResponsesCompat,
    cacheRetention: CacheRetention
): JsonObject? {
    if (!compat.supportsExplicitPromptCacheMode) return null
    if (cacheRetention == CacheRetention.NONE) {
        return buildJsonObject { put("mode", "explicit") }
    }
    if (cacheRetention == CacheRetention.LONG && compat.supportsLongCacheRetention) {
        return buildJsonObject { put("ttl", "30m") }
    }
    return null
}

internal fun sessionAffinityHeaders(
    sessionId: String?,
    compat: ResolvedResponsesCompat
): Map<String, String> {
    if (sessionId == null) return emptyMap()
    return when (compat.sessionAffinityFormat) {
        SessionAffinityFormat.OPENROUTER -> mapOf("x-session-id" to sessionId)

        SessionAffinityFormat.OPENAI_NOSESSION -> mapOf("x-client-request-id" to sessionId)

        SessionAffinityFormat.OPENAI -> mapOf(
            "session_id" to sessionId,
            "x-client-request-id" to sessionId
        )
    }
}

internal fun mergeClientHeaders(
    modelHeaders: Map<String, String>,
    sessionId: String?,
    compat: ResolvedResponsesCompat,
    optionsHeaders: Map<String, String?>
): Map<String, String> {
    val merged = mergeHeaders(
        mergeHeaders(modelHeaders, sessionAffinityHeaders(sessionId, compat)),
        optionsHeaders
    )
    return merged.filterValues { it != null }.mapValues { it.value!! }
}

data class OpenAiResponsesOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: ModelThinkingLevel? = null,
    /** "auto" | "detailed" | "concise" | null; null means "auto" when effort is set. */
    val reasoningSummary: String? = null,
    val serviceTier: String? = null,
    /** Raw `tool_choice` wire passthrough; unlike sibling adapters it stays a
     * String? because pi forwards the value verbatim. [mapResponsesToolChoice]
     * maps the sealed [ToolChoice] onto this wire value. */
    val toolChoice: String? = null,
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long =
        works.resolve.pathfinder.ai.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
    /** How the request identifies the calling app. */
    val identity: ClientIdentity? = null,
    /**
     * Request hook that may return a replacement for the outgoing payload. It
     * receives full message content — installers must not log it. Never
     * included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /** Invoked after the 2xx response headers arrive. Never included in toString(). */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * Merged into the request params last, so custom keys override the named
     * request fields. Only keys appear in toString().
     */
    val samplingParams: Map<String, JsonElement>? = null,
    /**
     * Explicit parent telemetry context for this request. Dormant in this
     * port — carried for shape fidelity.
     */
    val telemetry: TelemetryContext? = null
) {
    override fun toString(): String = optionsToString(
        "OpenAiResponsesOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "reasoningEffort" to reasoningEffort,
        "reasoningSummary" to reasoningSummary,
        "serviceTier" to serviceTier,
        "toolChoice" to toolChoice,
        "cacheRetention" to cacheRetention,
        "timeoutMs" to timeoutMs,
        "maxRetries" to maxRetries,
        "maxRetryDelayMs" to maxRetryDelayMs,
        "env" to env.keys,
        "headers" to headers.keys,
        "onPayload" to (onPayload != null),
        "onResponse" to (onResponse != null),
        "samplingParams" to samplingParams?.keys,
        "telemetry" to (telemetry != null)
    )
}

internal fun buildOpenAiResponsesOptions(
    model: Model,
    context: TranscriptContext,
    options: SimpleStreamOptions,
    reasoningEffort: ModelThinkingLevel?
): OpenAiResponsesOptions = OpenAiResponsesOptions(
    apiKey = options.apiKey,
    sessionId = options.sessionId,
    temperature = options.temperature,
    maxTokens = works.resolve.pathfinder.ai.utils.clampMaxTokensToContext(
        model,
        context,
        options.maxTokens ?: model.maxTokens
    ),
    reasoningEffort = reasoningEffort,
    toolChoice = options.toolChoice?.toToolChoice()?.let(::mapResponsesToolChoice),
    cacheRetention = options.cacheRetention,
    timeoutMs = options.timeoutMs,
    maxRetries = options.maxRetries,
    maxRetryDelayMs = options.maxRetryDelayMs,
    env = options.env,
    headers = options.headers,
    identity = options.identity,
    onPayload = options.onPayload,
    onResponse = options.onResponse,
    samplingParams = options.samplingParams,
    telemetry = options.telemetry
)

class OpenAiResponsesApi(
    private val transport: works.resolve.pathfinder.ai.transport.HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val clock: Clock = Clock.System
) : ChatApi {

    override fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        val apiKey = options.apiKey
        val clamped = options.reasoning?.let {
            works.resolve.pathfinder.ai.clampThinkingLevel(model, it.toModelThinkingLevel())
        }
        val reasoningEffort = if (clamped == ModelThinkingLevel.OFF) null else clamped
        return stream(
            model,
            context,
            buildOpenAiResponsesOptions(model, context, options, reasoningEffort)
        )
    }
    fun stream(
        model: Model,
        context: TranscriptContext,
        options: OpenAiResponsesOptions = OpenAiResponsesOptions()
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = clock.now().toEpochMilliseconds()
        val compat = getCompat(model)
        val configurationUpdateEffort = getConfigurationUpdateEffort(model, options, compat)
        val normalizedContext =
            resolveTranscript(context, compat.supportsMidConvoSystemMessages)
        val grammarToolInputProperties = createGrammarToolInputProperties(
            getDeclaredTools(normalizedContext.messages),
            compat.supportsOpenAIGrammarTools
        )
        val state = OpenAiResponsesShared.ResponsesStreamState(
            model,
            startedAtMs,
            OpenAiResponsesShared.StreamProcessingOptions(
                serviceTier = options.serviceTier,
                grammarToolInputProperties = grammarToolInputProperties,
                applyServiceTierPricing = { usage, tier ->
                    applyServiceTierPricing(usage, tier, model.id)
                }
            ),
            configurationUpdateEffort
        )
        try {
            val apiKey = getClientApiKey(
                model.provider,
                options.apiKey,
                options.headers
            )
            val cacheRetention = resolveCacheRetention(options.cacheRetention, options.env)
            val cacheSessionId = if (cacheRetention ==
                CacheRetention.NONE
            ) {
                null
            } else {
                options.sessionId
            }

            val headers = mergeClientHeaders(
                // Copilot dynamic headers (github-copilot only) sit between
                // the model headers and the affinity/options headers.
                mergeHeaders(
                    getIdentityHeaders(model, options.identity),
                    mergeHeaders(model.headers, copilotDynamicHeadersFor(model, normalizedContext))
                ).filterValues { it != null }.mapValues { it.value!! },
                cacheSessionId,
                compat,
                options.headers
            ) + mapOf("Accept" to "text/event-stream")
            var params = buildParams(
                model,
                normalizedContext,
                options,
                compat,
                cacheRetention,
                grammarToolInputProperties,
                configurationUpdateEffort
            )
            options.onPayload?.let { hook -> hook(params, model)?.let { params = it } }
            val body = params
                .toString().toByteArray(Charsets.UTF_8)
            val url = model.baseUrl.trimEnd('/') + "/responses"
            val request = TransportRequest(
                url = url,
                bearerToken = apiKey,
                headers = headers,
                body = body,
                timeoutMs = options.timeoutMs
            )

            val response = retry.retryProviderRequest<TransportResponse>(
                options.maxRetries,
                options.maxRetryDelayMs
            ) { transport.post(request) }

            // Only runs for 2xx: the transport throws ProviderHttpException
            // on non-2xx before reaching this point.
            options.onResponse?.invoke(
                ProviderResponse(response.status, headersToRecord(response.headers)),
                model
            )

            emit(AssistantMessageEvent.Start(state.partialSnapshot()))

            // No early break: like pi, the SDK stream is consumed to its
            // end; finish() validates the terminal state afterwards.
            response.events.collect { event ->
                processSseEvent(event, state)?.forEach { emit(it) }
            }
            finish(state)
            emit(AssistantMessageEvent.Done(state.stopReason, state.partialSnapshot()))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val errorMessage = formatResponsesProviderError(
                error,
                "${if (model.provider == "openai") "OpenAI" else model.provider} API error"
            )
            // Sign in with ChatGPT shares the subscription's usage limit with other apps.
            // On ChatGPT Business plans, the workspace admin also sets a per-user limit.
            val errorMessageWithHint = when {
                errorMessage.contains("subscription_sharing_usage_limit_exceeded") ->
                    "$errorMessage\nCheck your ChatGPT usage: $CHATGPT_USAGE_URL"

                errorMessage.contains(
                    "subscription_sharing_workplace_individual_usage_limit_exceeded"
                ) -> "$errorMessage\n$CHATGPT_WORKSPACE_LIMIT_HINT"

                else -> errorMessage
            }
            emit(
                AssistantMessageEvent.Error(
                    StopReason.ERROR,
                    state.partialSnapshot().copy(
                        stopReason = StopReason.ERROR,
                        errorMessage = errorMessageWithHint
                    )
                )
            )
        }
    }

    internal fun finish(state: OpenAiResponsesShared.ResponsesStreamState) {
        state.assertTerminalEvent()
        if (state.stopReason == StopReason.PENDING) {
            throw ProviderStreamException("OpenAI Responses stream ended without a stop reason")
        }
        if (state.stopReason == StopReason.ERROR || state.stopReason == StopReason.ABORTED) {
            throw ProviderStreamException(state.errorMessage ?: "An unknown error occurred")
        }
    }

    /**
     * Resolve cache retention preference.
     * Defaults to "short" and uses PI_CACHE_RETENTION for backward compatibility.
     *
     * Divergence from pi: upstream declares this as a plain module-private
     * function in `openai-responses.ts` (with per-file copies in the other
     * adapters). Kotlin forbids a second top-level `resolveCacheRetention`
     * with this signature in the package — `AnthropicMessagesApi.kt` already
     * declares an identical internal one — so it lives on the companion
     * object.
     */
    companion object {
        internal fun resolveCacheRetention(
            cacheRetention: CacheRetention?,
            env: Map<String, String>
        ): CacheRetention = when {
            cacheRetention != null -> cacheRetention
            env["PI_CACHE_RETENTION"] == "long" -> CacheRetention.LONG
            else -> CacheRetention.SHORT
        }
    }
}

internal fun buildParams(
    model: Model,
    context: TranscriptContext,
    options: OpenAiResponsesOptions?,
    compat: ResolvedResponsesCompat,
    cacheRetention: CacheRetention,
    grammarToolInputProperties: Map<String, String> = createGrammarToolInputProperties(
        getDeclaredTools(context.messages),
        compat.supportsOpenAIGrammarTools
    ),
    configurationUpdateEffort: String? = null
): JsonObject {
    val transcriptTools = resolveTranscriptTools(
        context.messages,
        OpenAiResponsesShared.getResponsesToolSupport(
            compat.supportsMidConvoSystemMessages,
            compat.supportsAdditionalTools,
            compat.supportsToolSearch
        )
    )
    val messages = OpenAiResponsesShared.convertResponsesMessages(
        model,
        context,
        OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        OpenAiResponsesShared.ConvertResponsesMessagesOptions(
            grammarToolInputProperties = grammarToolInputProperties,
            supportsMidConvoSystemMessages = compat.supportsMidConvoSystemMessages,
            supportsAdditionalTools = compat.supportsAdditionalTools,
            supportsToolSearch = compat.supportsToolSearch,
            toolOptions = OpenAiResponsesShared.ConvertResponsesToolsOptions(
                supportsStrictMode = compat.supportsStrictMode,
                supportsOpenAIGrammarTools = compat.supportsOpenAIGrammarTools
            ),
            configurationUpdateEffort = configurationUpdateEffort
        )
    )

    // Sign in with ChatGPT rejects these request fields.
    val omitUnsupportedFields = isChatGPTSignIn(model, options?.apiKey)
    val reasoningEffort = options?.reasoningEffort
        ?: if (options?.reasoningSummary != null) ModelThinkingLevel.MEDIUM else null
    var params = buildJsonObject {
        put("model", model.id)
        put("input", kotlinx.serialization.json.JsonArray(messages))
        put("stream", true)
        if (cacheRetention != CacheRetention.NONE) {
            clampOpenAIPromptCacheKey(options?.sessionId)?.let {
                put("prompt_cache_key", it)
            }
        }
        if (!omitUnsupportedFields) {
            getPromptCacheRetention(compat, cacheRetention)?.let {
                put("prompt_cache_retention", it)
            }
            getPromptCacheOptions(compat, cacheRetention)?.let { options ->
                put("prompt_cache_options", options)
            }
        }
        put("store", false)

        // Some gateways reject max_output_tokens with 400.
        if (options?.maxTokens != null && compat.supportsMaxOutputTokens &&
            !omitUnsupportedFields
        ) {
            put(
                "max_output_tokens",
                maxOf(options.maxTokens, OPENAI_RESPONSES_MIN_OUTPUT_TOKENS)
            )
        }
        if (!omitUnsupportedFields) {
            options?.temperature?.let { put("temperature", it) }
        }
        options?.serviceTier?.let { put("service_tier", it) }
        if (transcriptTools.requestTools.isNotEmpty()) {
            put(
                "tools",
                kotlinx.serialization.json.JsonArray(
                    OpenAiResponsesShared.convertResponsesTools(
                        transcriptTools.requestTools,
                        OpenAiResponsesShared.ConvertResponsesToolsOptions(
                            supportsStrictMode = compat.supportsStrictMode,
                            supportsOpenAIGrammarTools = compat.supportsOpenAIGrammarTools
                        )
                    )
                )
            )
        }
        options?.toolChoice?.let { put("tool_choice", it) }

        if (model.reasoning) {
            if (reasoningEffort != null) {
                val requestedEffort = OpenAiResponsesShared.resolveReasoningEffort(
                    model,
                    options?.reasoningEffort,
                    "medium"
                )
                val effort =
                    if (configurationUpdateEffort == null) {
                        requestedEffort
                    } else {
                        CONFIGURATION_UPDATE_REQUEST_EFFORT
                    }
                put(
                    "reasoning",
                    buildJsonObject {
                        put("effort", effort)
                        put(
                            "summary",
                            options?.reasoningSummary?.takeIf { it.isNotEmpty() } ?: "auto"
                        )
                    }
                )
                put(
                    "include",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content")
                        )
                    )
                )
            } else if (model.provider != "github-copilot" &&
                !(
                    model.thinkingLevelMap?.isSpecified(ModelThinkingLevel.OFF) == true &&
                        model.thinkingLevelMap?.forLevel(ModelThinkingLevel.OFF) == null
                    )
            ) {
                val off = model.thinkingLevelMap?.takeIf {
                    it.isSpecified(ModelThinkingLevel.OFF)
                }?.forLevel(ModelThinkingLevel.OFF) ?: "none"
                put("reasoning", buildJsonObject { put("effort", off) })
            }
            // xAI always wants encrypted reasoning content included.
            if (model.provider == "xai") {
                put(
                    "include",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content")
                        )
                    )
                )
            }
        }
    }
    // Last so model and request sampling parameters override named request fields.
    resolveSamplingParams(
        model,
        reasoningEffort ?: ModelThinkingLevel.OFF,
        options?.samplingParams
    )?.let { params = JsonObject(params.toMap() + it) }
    return params
}

internal fun getServiceTierCostMultiplier(modelId: String, serviceTier: String?): Double =
    when (serviceTier) {
        "flex" -> 0.5
        "priority", "fast" -> if (modelId == "gpt-5.5") 2.5 else 2.0
        else -> 1.0
    }

internal fun applyServiceTierPricing(usage: Usage, serviceTier: String?, modelId: String): Usage {
    val multiplier = getServiceTierCostMultiplier(modelId, serviceTier)
    val cost = usage.cost
    if (multiplier == 1.0) return usage
    val scaled = Cost(
        input = cost.input * multiplier,
        output = cost.output * multiplier,
        cacheRead = cost.cacheRead * multiplier,
        cacheWrite = cost.cacheWrite * multiplier
    )
    return usage.copy(
        cost = scaled.copy(
            total =
                scaled.input + scaled.output + scaled.cacheRead + scaled.cacheWrite
        )
    )
}

// ---------------------------------------------------------------------------
// Shared SSE plumbing
// ---------------------------------------------------------------------------

/** The function case is built through the JSON DOM so the tool name is
 * properly escaped. */
internal fun mapResponsesToolChoice(choice: ToolChoice): String = when (choice) {
    ToolChoice.Auto -> "auto"

    ToolChoice.None -> "none"

    ToolChoice.Any, ToolChoice.Required -> "required"

    is ToolChoice.Function -> buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject { put("name", choice.name) })
    }.toString()
}

internal val responsesJson: Json = lenientJson

internal fun processSseEvent(
    event: SseEvent,
    state: OpenAiResponsesShared.ResponsesStreamState
): List<AssistantMessageEvent>? {
    if (event.data.trim() == "[DONE]") return null
    val parsed = try {
        responsesJson.parseToJsonElement(event.data)
    } catch (error: Exception) {
        throw ProviderStreamException(
            "Malformed SSE JSON payload: ${error.message ?: error::class.simpleName}"
        )
    }
    if (parsed !is JsonObject) {
        throw ProviderStreamException("Malformed SSE JSON payload: expected a JSON object")
    }
    return state.onEvent(parsed)
}

/**
 * Divergence from pi: on a blank error body pi composes
 * `"prefix (status): <SDK message>"`, but the SDK message does not exist
 * here ([ProviderHttpException.message] is just "Provider returned HTTP N"),
 * so a blank body yields only `"prefix (status)"`.
 */
internal fun formatResponsesProviderError(error: Exception, prefix: String): String = when (error) {
    is ProviderHttpException -> {
        val norm = normalizeProviderError(error)
        if (norm.body != null) {
            formatProviderError(norm, prefix)
        } else {
            "$prefix (${error.status})"
        }
    }

    is ProviderStreamException -> error.message ?: "Provider stream error"

    else -> error.message ?: error::class.simpleName ?: "Unknown error"
}
