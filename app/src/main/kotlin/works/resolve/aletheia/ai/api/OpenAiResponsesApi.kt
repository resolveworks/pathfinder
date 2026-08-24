package works.resolve.aletheia.ai.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.CacheRetention
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.ModelThinkingLevel
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.ThinkingLevel
import works.resolve.aletheia.ai.core.ToolChoice
import works.resolve.aletheia.ai.core.Tool
import works.resolve.aletheia.ai.transport.ProviderHttpException
import works.resolve.aletheia.ai.transport.SseEvent
import works.resolve.aletheia.ai.transport.TransportRequest
import works.resolve.aletheia.ai.transport.TransportResponse
import works.resolve.aletheia.ai.utils.ProviderRetry

/**
 * Streaming adapters for the OpenAI Responses API family, ported from pi's
 * openai-responses.ts and azure-openai-responses.ts. Both share
 * [OpenAiResponsesShared] for message/tool conversion and stream processing,
 * as upstream does; they differ in client construction (URL, auth header,
 * session affinity) and payload assembly.
 *
 * Transport-level divergences from pi (documented per adapter): requests go
 * through Aletheia's [works.resolve.aletheia.ai.transport.HttpStreamingTransport]
 * with the OpenAI SDK's wire behavior re-created by hand (URL paths, headers,
 * retry via [ProviderRetry]); AbortSignal-based aborts map to coroutine
 * cancellation, which ends the flow without an Error event (the established
 * Aletheia convention in OpenAiCompletionsApi).
 */

/** Options for the OpenAI Responses adapter, pi's OpenAIResponsesOptions. */
data class OpenAiResponsesOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: ModelThinkingLevel? = null,
    /** "auto" | "detailed" | "concise" | null; null means "auto" when effort is set. */
    val reasoningSummary: String? = null,
    val serviceTier: String? = null,
    /** Raw `tool_choice` value passed through to the payload. */
    val toolChoice: String? = null,
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = works.resolve.aletheia.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
) {
    override fun toString(): String =
        "OpenAiResponsesOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoningEffort=$reasoningEffort, reasoningSummary=$reasoningSummary" +
            ", serviceTier=$serviceTier, toolChoice=$toolChoice, cacheRetention=$cacheRetention" +
            ", timeoutMs=$timeoutMs, maxRetries=$maxRetries, maxRetryDelayMs=$maxRetryDelayMs" +
            ", env=${env.keys}, headers=${headers.keys})"
}

/**
 * pi's splitDeferredTools: current tools split into prefix (immediate) and
 * transcript-loaded (deferred) definitions.
 */
data class DeferredToolPlacement(val immediate: List<Tool>, val deferred: Map<String, Tool>)

fun splitDeferredTools(context: Context, enabled: Boolean): DeferredToolPlacement {
    val uniqueTools = LinkedHashMap<String, Tool>()
    for (tool in context.tools) uniqueTools[tool.name] = tool
    if (!enabled) return DeferredToolPlacement(uniqueTools.values.toList(), emptyMap())

    val deferredNames = mutableSetOf<String>()
    val usedNames = mutableSetOf<String>()
    for (message in context.messages) {
        when (message) {
            is works.resolve.aletheia.ai.core.AssistantMessage ->
                message.content.filterIsInstance<works.resolve.aletheia.ai.core.ToolCall>()
                    .forEach { usedNames.add(it.name) }
            is works.resolve.aletheia.ai.core.ToolResultMessage ->
                for (name in message.addedToolNames) {
                    if (name !in usedNames) deferredNames.add(name)
                }
            else -> {}
        }
    }

    val immediate = mutableListOf<Tool>()
    val deferred = LinkedHashMap<String, Tool>()
    for ((name, tool) in uniqueTools) {
        if (name in deferredNames) deferred[name] = tool else immediate.add(tool)
    }
    return DeferredToolPlacement(immediate, deferred)
}

/** pi's getPiUserAgent shape, adapted for Aletheia's Android runtime. */
const val PI_USER_AGENT = "pi (android)"

/**
 * OpenAI Responses streaming adapter (openai-responses.ts). POSTs
 * `{baseUrl}/responses` with `stream: true`, `store: false`, session affinity
 * headers, and pi's cache-retention/prompt-cache-key policy.
 */
class OpenAiResponsesApi(
    private val transport: works.resolve.aletheia.ai.transport.HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ChatApi {

    /**
     * pi's streamSimple for openai-responses: buildBaseOptions (clamped max
     * tokens), clamped thinking level, forwarded tool choice.
     */
    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<AssistantMessageEvent> {
        val apiKey = options.apiKey
        val clamped = options.reasoning?.let {
            works.resolve.aletheia.ai.core.clampThinkingLevel(model, toModelThinkingLevel(it))
        }
        val reasoningEffort = if (clamped == ModelThinkingLevel.OFF) null else clamped
        return stream(
            model,
            context,
            OpenAiResponsesOptions(
                apiKey = apiKey,
                sessionId = options.sessionId,
                temperature = options.temperature,
                maxTokens = works.resolve.aletheia.ai.utils.clampMaxTokensToContext(
                    model,
                    context,
                    options.maxTokens ?: model.maxTokens,
                ),
                reasoningEffort = reasoningEffort,
                toolChoice = options.toolChoice?.let(::mapResponsesToolChoice),
                cacheRetention = options.cacheRetention,
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
                env = options.env,
                headers = options.headers,
            ),
        )
    }
    fun stream(
        model: Model,
        context: Context,
        options: OpenAiResponsesOptions = OpenAiResponsesOptions(),
    ): Flow<AssistantMessageEvent> = flow {
        val startedAtMs = nowMs()
        val state = OpenAiResponsesShared.ResponsesStreamState(
            model,
            startedAtMs,
            OpenAiResponsesShared.StreamProcessingOptions(
                serviceTier = options.serviceTier,
                applyServiceTierPricing = { usage, tier ->
                    OpenAiResponsesShared.applyServiceTierPricing(usage, tier, model.id)
                },
            ),
        )
        try {
            val apiKey = OpenAiResponsesShared.getClientApiKey(
                model.provider,
                options.apiKey,
                options.headers,
            )
            val compat = OpenAiResponsesShared.getCompat(model)
            val cacheRetention = OpenAiResponsesShared.resolveCacheRetention(options.cacheRetention, options.env)
            val cacheSessionId = if (cacheRetention == CacheRetention.NONE) null else options.sessionId

            val headers = OpenAiResponsesShared.mergeClientHeaders(
                model.headers + mapOf("User-Agent" to PI_USER_AGENT),
                cacheSessionId,
                compat,
                options.headers,
            ) + mapOf("Accept" to "text/event-stream")
            val body = buildParams(model, context, options, compat, cacheRetention)
                .toString().toByteArray(Charsets.UTF_8)
            val url = model.baseUrl.trimEnd('/') + "/responses"
            val request = TransportRequest(
                url = url,
                bearerToken = apiKey,
                headers = headers,
                body = body,
                timeoutMs = options.timeoutMs,
            )

            val response = retry.retryProviderRequest<TransportResponse>(
                options.maxRetries,
                options.maxRetryDelayMs,
            ) { transport.post(request) }

            emit(AssistantMessageEvent.Start(state.partialSnapshot()))
            for (event in response.events.toList()) {
                processSseEvent(event, state)?.forEach { emit(it) }
            }
            finish(state)
            emit(AssistantMessageEvent.Done(state.stopReason, state.partialSnapshot()))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emit(
                AssistantMessageEvent.Error(
                    StopReason.ERROR,
                    state.partialSnapshot().copy(
                        stopReason = StopReason.ERROR,
                        errorMessage = formatResponsesProviderError(error, "OpenAI API error"),
                    ),
                ),
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
}

internal fun buildParams(
    model: Model,
    context: Context,
    options: OpenAiResponsesOptions?,
    compat: OpenAiResponsesShared.ResolvedResponsesCompat,
    cacheRetention: CacheRetention,
): JsonObject {
    val deferredToolsMode = when {
        compat.supportsAdditionalTools -> OpenAiResponsesShared.DeferredToolsMode.ADDITIONAL_TOOLS
        compat.supportsToolSearch -> OpenAiResponsesShared.DeferredToolsMode.TOOL_SEARCH
        else -> null
    }
    val toolPlacement = splitDeferredTools(context, deferredToolsMode != null)
    val messages = OpenAiResponsesShared.convertResponsesMessages(
        model,
        context,
        OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        OpenAiResponsesShared.ConvertResponsesMessagesOptions(
            deferredTools = toolPlacement.deferred,
            deferredToolsMode = deferredToolsMode,
            toolOptions = OpenAiResponsesShared.ConvertResponsesToolsOptions(
                supportsStrictMode = compat.supportsStrictMode,
            ),
        ),
    )

    val disableImplicitPromptCache =
        cacheRetention == CacheRetention.NONE && compat.supportsExplicitPromptCacheMode
    val params = buildJsonObject {
        put("model", model.id)
        put("input", kotlinx.serialization.json.JsonArray(messages))
        put("stream", true)
        if (cacheRetention != CacheRetention.NONE) {
            OpenAiResponsesShared.clampOpenAIPromptCacheKey(options?.sessionId)?.let {
                put("prompt_cache_key", it)
            }
        }
        OpenAiResponsesShared.getPromptCacheRetention(compat, cacheRetention)?.let {
            put("prompt_cache_retention", it)
        }
        if (disableImplicitPromptCache) {
            put("prompt_cache_options", buildJsonObject { put("mode", "explicit") })
        }
        put("store", false)

        options?.maxTokens?.let {
            put(
                "max_output_tokens",
                maxOf(it, OpenAiResponsesShared.OPENAI_RESPONSES_MIN_OUTPUT_TOKENS),
            )
        }
        options?.temperature?.let { put("temperature", it) }
        options?.serviceTier?.let { put("service_tier", it) }
        if (toolPlacement.immediate.isNotEmpty()) {
            put(
                "tools",
                kotlinx.serialization.json.JsonArray(
                    OpenAiResponsesShared.convertResponsesTools(
                        toolPlacement.immediate,
                        OpenAiResponsesShared.ConvertResponsesToolsOptions(
                            supportsStrictMode = compat.supportsStrictMode,
                        ),
                    ),
                ),
            )
        }
        options?.toolChoice?.let { put("tool_choice", it) }

        if (model.reasoning) {
            if (options?.reasoningEffort != null || options?.reasoningSummary != null) {
                val effort = OpenAiResponsesShared.resolveReasoningEffort(
                    model,
                    options?.reasoningEffort,
                    "medium",
                )
                put(
                    "reasoning",
                    buildJsonObject {
                        put("effort", effort)
                        put("summary", options?.reasoningSummary?.takeIf { it.isNotEmpty() } ?: "auto")
                    },
                )
                put(
                    "include",
                    kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content"))),
                )
            } else if (model.provider != "github-copilot" &&
                !(model.thinkingLevelMap?.isSpecified(ModelThinkingLevel.OFF) == true &&
                    model.thinkingLevelMap?.forLevel(ModelThinkingLevel.OFF) == null)
            ) {
                val off = model.thinkingLevelMap?.takeIf {
                    it.isSpecified(ModelThinkingLevel.OFF)
                }?.forLevel(ModelThinkingLevel.OFF) ?: "none"
                put("reasoning", buildJsonObject { put("effort", off) })
            }
            // pi: xAI always wants encrypted reasoning content included.
            if (model.provider == "xai") {
                put(
                    "include",
                    kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content"))),
                )
            }
        }
    }
    return params
}

// ---------------------------------------------------------------------------
// Azure OpenAI Responses (azure-openai-responses.ts)
// ---------------------------------------------------------------------------

/** Options for the Azure adapter, pi's AzureOpenAIResponsesOptions. */
data class AzureOpenAiResponsesOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: ModelThinkingLevel? = null,
    val reasoningSummary: String? = null,
    val toolChoice: String? = null,
    val azureApiVersion: String? = null,
    val azureResourceName: String? = null,
    val azureBaseUrl: String? = null,
    val azureDeploymentName: String? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = works.resolve.aletheia.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
) {
    override fun toString(): String =
        "AzureOpenAiResponsesOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", reasoningEffort=$reasoningEffort, reasoningSummary=$reasoningSummary" +
            ", toolChoice=$toolChoice, azureApiVersion=$azureApiVersion" +
            ", azureResourceName=$azureResourceName, azureBaseUrl=$azureBaseUrl" +
            ", azureDeploymentName=$azureDeploymentName, timeoutMs=$timeoutMs" +
            ", maxRetries=$maxRetries, maxRetryDelayMs=$maxRetryDelayMs" +
            ", env=${env.keys}, headers=${headers.keys})"
}

private const val DEFAULT_AZURE_API_VERSION = "v1"

/** Pi's parseDeploymentNameMap: `modelId=deployment,modelId2=deployment2`. */
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
    val mapped = parseDeploymentNameMap(options?.env?.get("AZURE_OPENAI_DEPLOYMENT_NAME_MAP"))[model.id]
    return mapped ?: model.id
}

/**
 * Pi's normalizeAzureBaseUrl, implemented over parsed URL parts in
 * [normalizeAzureBaseUrlFor].
 */

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
            "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or AZURE_OPENAI_RESOURCE_NAME, " +
                "or pass azureBaseUrl, azureResourceName, or model.baseUrl.",
        )
    }
    return AzureConfig(normalizeAzureBaseUrlFor(resolvedBaseUrl), apiVersion)
}

/** Pi's normalizeAzureBaseUrl, implemented over parsed URL parts. */
internal fun normalizeAzureBaseUrlFor(raw: String): String {
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
    val path = (url.path ?: "").trimEnd('/')

    var effectivePath = path
    if (isAzureHost && (effectivePath.isEmpty() || effectivePath == "/openai" || effectivePath == "/openai/v1/responses")) {
        effectivePath = "/openai/v1"
    }
    val port = if (url.port != -1) ":${url.port}" else ""
    return "${url.scheme}://$host$port$effectivePath"
}

/**
 * Azure OpenAI Responses streaming adapter (azure-openai-responses.ts).
 * Requests go to `{base}/responses?api-version={version}` with the Azure
 * `api-key` header (the AzureOpenAI SDK's auth scheme for `/responses`, which
 * is not a deployments-prefixed path in the v1 API).
 */
class AzureOpenAiResponsesApi(
    private val transport: works.resolve.aletheia.ai.transport.HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ChatApi {

    /**
     * pi's streamSimple for azure-openai-responses: missing keys fail fast,
     * then buildBaseOptions plus the clamped reasoning level and tool choice.
     */
    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<AssistantMessageEvent> {
        val apiKey = options.apiKey
            ?: throw IllegalStateException("No API key for provider: ${model.provider}")
        val clamped = options.reasoning?.let {
            works.resolve.aletheia.ai.core.clampThinkingLevel(model, toModelThinkingLevel(it))
        }
        val reasoningEffort = if (clamped == ModelThinkingLevel.OFF) null else clamped
        return stream(
            model,
            context,
            AzureOpenAiResponsesOptions(
                apiKey = apiKey,
                sessionId = options.sessionId,
                temperature = options.temperature,
                maxTokens = works.resolve.aletheia.ai.utils.clampMaxTokensToContext(
                    model,
                    context,
                    options.maxTokens ?: model.maxTokens,
                ),
                reasoningEffort = reasoningEffort,
                toolChoice = options.toolChoice?.let(::mapResponsesToolChoice),
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
                env = options.env,
                headers = options.headers,
            ),
        )
    }
    fun stream(
        model: Model,
        context: Context,
        options: AzureOpenAiResponsesOptions = AzureOpenAiResponsesOptions(),
    ): Flow<AssistantMessageEvent> = flow {
        val deploymentName = resolveDeploymentName(model, options)
        val startedAtMs = nowMs()
        val state = OpenAiResponsesShared.ResponsesStreamState(model, startedAtMs)
        try {
            val apiKey = options.apiKey
                ?: throw IllegalStateException("No API key for provider: ${model.provider}")
            val config = resolveAzureConfig(model, options)
            val messages = OpenAiResponsesShared.convertResponsesMessages(
                model,
                context,
                OpenAiResponsesShared.AZURE_TOOL_CALL_PROVIDERS,
            )

            val params = buildAzureParams(model, context, options, deploymentName, messages)
            val headers = LinkedHashMap<String, String?>()
            headers.putAll(model.headers)
            headers["User-Agent"] = PI_USER_AGENT
            headers.putAll(options.headers)
            headers["api-key"] = apiKey
            headers["Accept"] = "text/event-stream"
            val request = TransportRequest(
                url = config.baseUrl.trimEnd('/') + "/responses?api-version=" + config.apiVersion,
                bearerToken = null,
                headers = headers.filterValues { it != null }.mapValues { it.value!! },
                body = params.toString().toByteArray(Charsets.UTF_8),
                timeoutMs = options.timeoutMs,
            )

            val response = retry.retryProviderRequest<TransportResponse>(
                options.maxRetries,
                options.maxRetryDelayMs,
            ) { transport.post(request) }

            emit(AssistantMessageEvent.Start(state.partialSnapshot()))
            for (event in response.events.toList()) {
                processSseEvent(event, state)?.forEach { emit(it) }
            }
            state.assertTerminalEvent()
            if (state.stopReason == StopReason.PENDING) {
                throw ProviderStreamException("Azure OpenAI Responses stream ended without a stop reason")
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
                        errorMessage = formatResponsesProviderError(error, "Azure OpenAI API error"),
                    ),
                ),
            )
        }
    }
}

internal fun buildAzureParams(
    model: Model,
    context: Context,
    options: AzureOpenAiResponsesOptions?,
    deploymentName: String,
    messages: List<JsonObject>,
): JsonObject = buildJsonObject {
    put("model", deploymentName)
    put("input", kotlinx.serialization.json.JsonArray(messages))
    put("stream", true)
    OpenAiResponsesShared.clampOpenAIPromptCacheKey(options?.sessionId)?.let {
        put("prompt_cache_key", it)
    }
    put("store", false)

    options?.maxTokens?.let {
        put("max_output_tokens", maxOf(it, OpenAiResponsesShared.OPENAI_RESPONSES_MIN_OUTPUT_TOKENS))
    }
    options?.temperature?.let { put("temperature", it) }
    if (!context.tools.isEmpty()) {
        // pi's azure buildParams defaults supportsStrictMode to true (unlike
        // openai-responses, whose getCompat defaults it to false).
        val supportsStrictMode = model.responsesCompat?.supportsStrictMode ?: true
        put(
            "tools",
            kotlinx.serialization.json.JsonArray(
                OpenAiResponsesShared.convertResponsesTools(
                    context.tools,
                    OpenAiResponsesShared.ConvertResponsesToolsOptions(
                        supportsStrictMode = supportsStrictMode,
                    ),
                ),
            ),
        )
    }
    options?.toolChoice?.let { put("tool_choice", it) }

    if (model.reasoning) {
        if (options?.reasoningEffort != null || options?.reasoningSummary != null) {
            val effort = OpenAiResponsesShared.resolveReasoningEffort(model, options?.reasoningEffort, "medium")
            put(
                "reasoning",
                buildJsonObject {
                    put("effort", effort)
                    put("summary", options?.reasoningSummary?.takeIf { it.isNotEmpty() } ?: "auto")
                },
            )
            put(
                "include",
                kotlinx.serialization.json.JsonArray(
                    listOf(kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content")),
                ),
            )
        } else if (!(model.thinkingLevelMap?.isSpecified(ModelThinkingLevel.OFF) == true &&
            model.thinkingLevelMap?.forLevel(ModelThinkingLevel.OFF) == null)
        ) {
            // pi: thinkingLevelMap.off === null (explicitly unsupported) omits
            // the reasoning block entirely.
            val off = model.thinkingLevelMap?.takeIf { it.isSpecified(ModelThinkingLevel.OFF) }
                ?.forLevel(ModelThinkingLevel.OFF) ?: "none"
            put("reasoning", buildJsonObject { put("effort", off) })
        }
    }
}

// ---------------------------------------------------------------------------
// Shared SSE plumbing
// ---------------------------------------------------------------------------

/** Maps a core ToolChoice onto the Responses `tool_choice` wire value. */
internal fun mapResponsesToolChoice(choice: ToolChoice): String = when (choice) {
    ToolChoice.Auto -> "auto"
    ToolChoice.None -> "none"
    ToolChoice.Any, ToolChoice.Required -> "required"
    is ToolChoice.Function ->
        """{"type":"function","function":{"name":"${choice.name}"}}"""
}

internal fun toModelThinkingLevel(level: ThinkingLevel): ModelThinkingLevel =
    ModelThinkingLevel.valueOf(level.name)

internal val responsesJson = Json { ignoreUnknownKeys = true }

/**
 * Parses one complete SSE data payload as a Responses stream event and feeds
 * the shared state machine. Returns the block events to emit; throws
 * [ProviderStreamException] for malformed payloads and provider error events.
 */
internal fun processSseEvent(
    event: SseEvent,
    state: OpenAiResponsesShared.ResponsesStreamState,
): List<AssistantMessageEvent>? {
    if (event.data.trim() == "[DONE]") return null
    val parsed = try {
        responsesJson.parseToJsonElement(event.data)
    } catch (error: Exception) {
        throw ProviderStreamException(
            "Malformed SSE JSON payload: ${error.message ?: error::class.simpleName}",
        )
    }
    if (parsed !is JsonObject) {
        throw ProviderStreamException("Malformed SSE JSON payload: expected a JSON object")
    }
    return state.onEvent(parsed)
}

/**
 * Pi's formatProviderError: plain non-HTTP errors keep their message (no
 * prefix); HTTP errors surface status plus the parsed body with the provider
 * prefix.
 */
internal fun formatResponsesProviderError(error: Exception, prefix: String): String = when (error) {
    is ProviderHttpException -> {
        val body = formatErrorBody(error.body)
        if (body != null) "$prefix (${error.status}): $body" else "$prefix (${error.status})"
    }
    is ProviderStreamException -> error.message ?: "Provider stream error"
    else -> error.message ?: error::class.simpleName ?: "Unknown error"
}

internal fun formatErrorBody(body: String): String? {
    if (body.isBlank()) return null
    return try {
        val parsed = responsesJson.parseToJsonElement(body)
        val obj = parsed as? JsonObject ?: return body.take(500)
        val error = obj["error"] as? JsonObject
        if (error != null) {
            val message = error["message"].stringOrBlank()
            val code = error["code"].stringOrBlank()
            listOfNotNull(code, message).joinToString(": ").ifEmpty { body.take(500) }
        } else {
            body.take(500)
        }
    } catch (_: Exception) {
        body.take(500)
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringOrBlank(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
