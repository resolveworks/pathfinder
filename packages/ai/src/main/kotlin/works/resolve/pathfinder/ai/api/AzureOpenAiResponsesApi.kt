package works.resolve.pathfinder.ai.api

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.ProviderAuthException
import works.resolve.pathfinder.ai.ProviderResponse
import works.resolve.pathfinder.ai.ProviderStreamException
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.headersToRecord
import works.resolve.pathfinder.ai.mergeHeaders
import works.resolve.pathfinder.ai.mergeSamplingParams
import works.resolve.pathfinder.ai.toModelThinkingLevel
import works.resolve.pathfinder.ai.toToolChoice
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.telemetry.TelemetryContext

/**
 * Streaming adapter for the Azure OpenAI Responses API.
 *
 * Divergence from pi: aborts surface as coroutine cancellation, which ends
 * the flow without an Error event — the established Pathfinder convention
 * (see OpenAiCompletionsApi).
 */

private const val DEFAULT_AZURE_API_VERSION = "v1"

private val AZURE_TOOL_CALL_PROVIDERS =
    OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS + "azure-openai-responses"

// OpenAI Responses rejects max_output_tokens below 16.
private const val OPENAI_RESPONSES_MIN_OUTPUT_TOKENS = 16

/** Parses `modelId=deployment,modelId2=deployment2` entries. */
internal fun parseDeploymentNameMap(value: String?): Map<String, String> {
    if (value.isNullOrBlank()) return emptyMap()
    val map = mutableMapOf<String, String>()
    for (entry in value.split(",")) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) continue
        val modelId = trimmed.substringBefore("=", "").trim()
        val deploymentName = trimmed.substringAfter("=", "").trim()
        if (modelId.isEmpty() || deploymentName.isEmpty()) continue
        map[modelId] = deploymentName
    }
    return map
}

internal fun resolveDeploymentName(model: Model, options: AzureOpenAiResponsesOptions?): String {
    options?.azureDeploymentName?.let { return it }
    val mapped = parseDeploymentNameMap(
        options?.env?.get("AZURE_OPENAI_DEPLOYMENT_NAME_MAP")
    )[model.id]
    return mapped ?: model.id
}

data class AzureOpenAiResponsesOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: ModelThinkingLevel? = null,
    val reasoningSummary: String? = null,
    /** Raw `tool_choice` wire passthrough; unlike sibling adapters it stays a
     * String? because pi forwards the value verbatim. [mapResponsesToolChoice]
     * maps the sealed [works.resolve.pathfinder.ai.ToolChoice] onto this wire value. */
    val toolChoice: String? = null,
    val azureApiVersion: String? = null,
    val azureResourceName: String? = null,
    val azureBaseUrl: String? = null,
    val azureDeploymentName: String? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long =
        works.resolve.pathfinder.ai.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
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
    val telemetryContext: TelemetryContext? = null
) {
    override fun toString(): String = optionsToString(
        "AzureOpenAiResponsesOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "reasoningEffort" to reasoningEffort,
        "reasoningSummary" to reasoningSummary,
        "toolChoice" to toolChoice,
        "azureApiVersion" to azureApiVersion,
        "azureResourceName" to azureResourceName,
        "azureBaseUrl" to azureBaseUrl,
        "azureDeploymentName" to azureDeploymentName,
        "timeoutMs" to timeoutMs,
        "maxRetries" to maxRetries,
        "maxRetryDelayMs" to maxRetryDelayMs,
        "env" to env.keys,
        "headers" to headers.keys,
        "onPayload" to (onPayload != null),
        "onResponse" to (onResponse != null),
        "samplingParams" to samplingParams?.keys,
        "telemetryContext" to (telemetryContext != null)
    )
}

internal fun buildAzureOpenAiResponsesOptions(
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
    reasoningEffort: ModelThinkingLevel?
): AzureOpenAiResponsesOptions = AzureOpenAiResponsesOptions(
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
    timeoutMs = options.timeoutMs,
    maxRetries = options.maxRetries,
    maxRetryDelayMs = options.maxRetryDelayMs,
    env = options.env,
    headers = options.headers,
    onPayload = options.onPayload,
    onResponse = options.onResponse,
    samplingParams = mergeSamplingParams(model, options),
    telemetryContext = options.telemetryContext
)

/**
 * Azure OpenAI Responses streaming adapter. Authenticates with the Azure
 * `api-key` header — the SDK's auth scheme for `/responses`, which is not a
 * deployments-prefixed path in the v1 API.
 */
class AzureOpenAiResponsesApi(
    private val transport: works.resolve.pathfinder.ai.transport.HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val clock: Clock = Clock.System
) : ChatApi {

    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        val apiKey = options.apiKey
            ?: throw ProviderAuthException("No API key for provider: ${model.provider}")
        val clamped = options.reasoning?.let {
            works.resolve.pathfinder.ai.clampThinkingLevel(model, it.toModelThinkingLevel())
        }
        val reasoningEffort = if (clamped == ModelThinkingLevel.OFF) null else clamped
        return stream(
            model,
            context,
            buildAzureOpenAiResponsesOptions(model, context, options, reasoningEffort)
        )
    }
    fun stream(
        model: Model,
        context: Context,
        options: AzureOpenAiResponsesOptions = AzureOpenAiResponsesOptions()
    ): Flow<AssistantMessageEvent> = flow {
        val deploymentName = resolveDeploymentName(model, options)
        val startedAtMs = clock.now().toEpochMilliseconds()
        val grammarToolInputProperties = createGrammarToolInputProperties(
            context.tools,
            model.responsesCompat?.supportsOpenAIGrammarTools ?: false
        )
        val state = OpenAiResponsesShared.ResponsesStreamState(
            model,
            startedAtMs,
            OpenAiResponsesShared.StreamProcessingOptions(
                grammarToolInputProperties = grammarToolInputProperties
            )
        )
        try {
            val apiKey = options.apiKey
                ?: throw ProviderAuthException("No API key for provider: ${model.provider}")
            val config = resolveAzureConfig(model, options)
            val messages = OpenAiResponsesShared.convertResponsesMessages(
                model,
                context,
                AZURE_TOOL_CALL_PROVIDERS,
                OpenAiResponsesShared.ConvertResponsesMessagesOptions(
                    grammarToolInputProperties = grammarToolInputProperties
                )
            )

            var params = buildAzureParams(model, context, options, deploymentName, messages)
            options.onPayload?.let { hook -> hook(params, model)?.let { params = it } }
            val headers = LinkedHashMap<String, String?>()
            // Precedence: default User-Agent, then model headers (which may
            // override it), then options headers last.
            headers.putAll(mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), model.headers))
            headers.putAll(options.headers)
            headers["api-key"] = apiKey
            headers["Accept"] = "text/event-stream"
            val request = TransportRequest(
                url = config.baseUrl.trimEnd('/') + "/responses?api-version=" + config.apiVersion,
                bearerToken = null,
                headers = headers.filterValues { it != null }.mapValues { it.value!! },
                body = params.toString().toByteArray(Charsets.UTF_8),
                timeoutMs = options.timeoutMs
            )

            val response = retry.retryProviderRequest<TransportResponse>(
                options.maxRetries,
                options.maxRetryDelayMs
            ) { transport.post(request) }

            // Only runs for 2xx: the transport throws before this on non-2xx.
            options.onResponse?.invoke(
                ProviderResponse(response.status, headersToRecord(response.headers)),
                model
            )

            emit(AssistantMessageEvent.Start(state.partialSnapshot()))
            for (event in response.events.toList()) {
                processSseEvent(event, state)?.forEach { emit(it) }
            }
            state.assertTerminalEvent()
            if (state.stopReason == StopReason.PENDING) {
                throw ProviderStreamException(
                    "Azure OpenAI Responses stream ended without a stop reason"
                )
            }
            if (state.stopReason == StopReason.ERROR || state.stopReason == StopReason.ABORTED) {
                throw ProviderStreamException(state.errorMessage ?: "An unknown error occurred")
            }
            emit(AssistantMessageEvent.Done(state.stopReason, state.partialSnapshot()))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emit(
                AssistantMessageEvent.Error(
                    StopReason.ERROR,
                    state.partialSnapshot().copy(
                        stopReason = StopReason.ERROR,
                        errorMessage = formatResponsesProviderError(
                            error,
                            "Azure OpenAI API error"
                        )
                    )
                )
            )
        }
    }
}

/** Query and fragment are preserved for non-Azure hosts; only the Azure-host
 * path rewrite strips the query. */
internal fun normalizeAzureBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    val url = try {
        java.net.URI(trimmed)
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid Azure OpenAI base URL: $raw")
    }
    val host = url.host ?: throw IllegalArgumentException("Invalid Azure OpenAI base URL: $raw")
    val isAzureHost = host.endsWith(".openai.azure.com") ||
        host.endsWith(".cognitiveservices.azure.com") ||
        host.endsWith(".ai.azure.com")
    var effectivePath = (url.path ?: "").trimEnd('/')
    if (isAzureHost &&
        (
            effectivePath.isEmpty() || effectivePath == "/openai" ||
                effectivePath == "/openai/v1/responses"
            )
    ) {
        effectivePath = "/openai/v1"
    }
    val port = if (url.port != -1) ":${url.port}" else ""
    val userInfo = url.userInfo?.takeIf { it.isNotEmpty() }?.let { "$it@" } ?: ""
    val query = if (isAzureHost) {
        ""
    } else {
        url.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }
            ?: ""
    }
    val fragment = url.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""
    return "${url.scheme}://$userInfo$host$port$effectivePath$query$fragment"
}

internal data class AzureConfig(val baseUrl: String, val apiVersion: String)

internal fun resolveAzureConfig(model: Model, options: AzureOpenAiResponsesOptions?): AzureConfig {
    val apiVersion = options?.azureApiVersion
        ?: options?.env?.get("AZURE_OPENAI_API_VERSION")
        ?: DEFAULT_AZURE_API_VERSION

    val envBaseUrl = options?.env?.get("AZURE_OPENAI_BASE_URL")?.trim()
    val baseUrl = options?.azureBaseUrl?.trim() ?: envBaseUrl ?: envBaseUrl
    val resourceName = options?.azureResourceName ?: options?.env?.get("AZURE_OPENAI_RESOURCE_NAME")

    var resolvedBaseUrl = baseUrl?.takeIf { it.isNotEmpty() }
    if (resolvedBaseUrl == null && resourceName != null) {
        resolvedBaseUrl = "https://$resourceName.openai.azure.com/openai/v1"
    }
    if (resolvedBaseUrl == null && model.baseUrl.isNotEmpty()) {
        resolvedBaseUrl = model.baseUrl
    }
    if (resolvedBaseUrl == null) {
        throw IllegalStateException(
            "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or " +
                "AZURE_OPENAI_RESOURCE_NAME, or pass azureBaseUrl, azureResourceName, or model.baseUrl."
        )
    }
    return AzureConfig(normalizeAzureBaseUrl(resolvedBaseUrl), apiVersion)
}

internal fun buildAzureParams(
    model: Model,
    context: Context,
    options: AzureOpenAiResponsesOptions?,
    deploymentName: String,
    messages: List<JsonObject>
): JsonObject {
    var params = buildJsonObject {
        put("model", deploymentName)
        put("input", kotlinx.serialization.json.JsonArray(messages))
        put("stream", true)
        clampOpenAIPromptCacheKey(options?.sessionId)?.let {
            put("prompt_cache_key", it)
        }
        put("store", false)

        options?.maxTokens?.let {
            put("max_output_tokens", maxOf(it, OPENAI_RESPONSES_MIN_OUTPUT_TOKENS))
        }
        options?.temperature?.let { put("temperature", it) }
        if (!context.tools.isEmpty()) {
            // Defaults to true here, unlike openai-responses' getCompat (false).
            val supportsStrictMode = model.responsesCompat?.supportsStrictMode ?: true
            put(
                "tools",
                kotlinx.serialization.json.JsonArray(
                    OpenAiResponsesShared.convertResponsesTools(
                        context.tools,
                        OpenAiResponsesShared.ConvertResponsesToolsOptions(
                            supportsStrictMode = supportsStrictMode,
                            supportsOpenAIGrammarTools =
                                model.responsesCompat?.supportsOpenAIGrammarTools
                                    ?: false
                        )
                    )
                )
            )
        }
        options?.toolChoice?.let { put("tool_choice", it) }

        if (model.reasoning) {
            if (options?.reasoningEffort != null || options?.reasoningSummary != null) {
                val effort = OpenAiResponsesShared.resolveReasoningEffort(
                    model,
                    options?.reasoningEffort,
                    "medium"
                )
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
            } else if (!(
                    model.thinkingLevelMap?.isSpecified(ModelThinkingLevel.OFF) == true &&
                        model.thinkingLevelMap?.forLevel(ModelThinkingLevel.OFF) == null
                    )
            ) {
                // thinkingLevelMap explicitly mapping OFF to null (unsupported)
                // omits the reasoning block entirely.
                val off = model.thinkingLevelMap?.takeIf { it.isSpecified(ModelThinkingLevel.OFF) }
                    ?.forLevel(ModelThinkingLevel.OFF) ?: "none"
                put("reasoning", buildJsonObject { put("effort", off) })
            }
        }
    }
    // Merged last so custom keys override the named request fields.
    options?.samplingParams?.let { params = JsonObject(params.toMap() + it) }
    return params
}
