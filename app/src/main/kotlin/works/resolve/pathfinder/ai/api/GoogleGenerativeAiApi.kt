package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.api.GoogleRequest.CommonOptions
import works.resolve.pathfinder.ai.api.GoogleRequest.GoogleThinking
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ProviderResponse
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.SimpleToolChoice
import works.resolve.pathfinder.ai.core.mergeHeaders
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.telemetry.TelemetryContext
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.json.JsonObject

/**
 * Google Generative AI (Gemini API) streaming adapter.
 *
 * Upstream drives the `@google/genai` SDK; Pathfinder implements the same
 * wire protocol directly. A blank [Model.baseUrl] means the SDK default
 * ([DEFAULT_BASE_URL]); a non-blank one already includes the version path
 * (upstream sets `apiVersion: ""` so the SDK does not append one).
 *
 * Divergences from pi (also see [GoogleShared] and [GoogleStreamEngine]):
 * - `options.fetch` has no Kotlin counterpart; requests go through the
 *   injected [HttpStreamingTransport].
 * - pi's streamSimple throws synchronously for a missing API key; here the
 *   failure is a terminal Error event, per the ChatApi contract.
 * - The User-Agent is [getPiUserAgent]; only its platform-string details
 *   differ from pi's.
 */
class GoogleGenerativeAiApi(
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val clock: Clock = Clock.System,
) : ChatApi {

    data class GoogleOptions(
        val apiKey: String? = null,
        val sessionId: String? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val timeoutMs: Long? = null,
        val maxRetries: Int = 0,
        val maxRetryDelayMs: Long = works.resolve.pathfinder.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
        val env: Map<String, String> = emptyMap(),
        val headers: Map<String, String?> = emptyMap(),
        /** "auto" | "none" | "any". */
        val toolChoice: String? = null,
        val thinking: GoogleThinking? = null,
        /**
         * Request hook: may return a replacement for the outgoing payload.
         * Divergence: upstream's hook receives the `@google/genai` SDK's
         * GenerateContentParameters; here it receives the wire-format JSON
         * payload this port builds. Receives full message content —
         * installers must not log it. Never included in toString().
         */
        val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
        /**
         * Carried for shape fidelity: pi inherits this hook from StreamOptions
         * but the Google adapter never invokes it. Never included in
         * toString().
         */
        val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
        /**
         * Explicit parent telemetry context for this request. Dormant in this
         * port — carried for shape fidelity.
         */
        val telemetryContext: TelemetryContext? = null,
    ) {
        override fun toString(): String = CommonOptions(
            apiKey, sessionId, temperature, maxTokens, timeoutMs, maxRetries, maxRetryDelayMs,
            env, headers, toolChoice, thinking,
        ).toString().dropLast(1) +
            ", onPayload=${onPayload != null}, onResponse=${onResponse != null}" +
            ", telemetryContext=${telemetryContext != null})"

        internal fun toCommon() = CommonOptions(
            apiKey, sessionId, temperature, maxTokens, timeoutMs, maxRetries, maxRetryDelayMs,
            env, headers, toolChoice, thinking,
        )
    }

    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<works.resolve.pathfinder.ai.core.AssistantMessageEvent> =
        stream(model, context, buildGoogleOptions(model, context, options))

    fun stream(
        model: Model,
        context: Context,
        options: GoogleOptions,
    ): Flow<works.resolve.pathfinder.ai.core.AssistantMessageEvent> {
        val apiKey = options.apiKey
            ?: return missingApiKeyFlow(model)

        val body = GoogleRequest.buildGenerateContentRequest(model, context, options.toCommon(), gemmaSupported = true)

        val baseUrl = model.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val url = "$baseUrl/models/${model.id}:streamGenerateContent?alt=sse"

        val headers = mergeHeaders(
            mergeHeaders(mapOf("User-Agent" to getPiUserAgent()), model.headers),
            options.headers,
        ).filterValues { it != null }
            .mapValues { it.value!! } + mapOf("x-goog-api-key" to apiKey)

        // onPayload is suspend, so the plan is built inside the flow, at
        // collection time.
        return kotlinx.coroutines.flow.flow {
            emitAll(
                GoogleStreamEngine.stream(
                    transport,
                    retry,
                    clock,
                    model,
                    GoogleStreamEngine.Plan(
                        url = url,
                        headers = headers,
                        body = (options.onPayload?.let { it(body, model) } ?: body)
                            .toString().toByteArray(Charsets.UTF_8),
                        timeoutMs = options.timeoutMs,
                        maxRetries = options.maxRetries,
                        maxRetryDelayMs = options.maxRetryDelayMs,
                    ),
                ),
            )
        }
    }

    private companion object {
        /** The `@google/genai` SDK default endpoint for the Gemini API. */
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    private fun missingApiKeyFlow(model: Model) = kotlinx.coroutines.flow.flow {
        val message = works.resolve.pathfinder.ai.core.AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = works.resolve.pathfinder.ai.core.StopReason.ERROR,
            errorMessage = "No API key for provider: ${model.provider}",
            timestamp = clock.now().toEpochMilliseconds(),
        )
        emit(
            works.resolve.pathfinder.ai.core.AssistantMessageEvent.Error(
                works.resolve.pathfinder.ai.core.StopReason.ERROR,
                message,
            ),
        )
    }
}

/**
 * The streamSimple options conversion (resolved thinking config), extracted
 * as a named function so the conversion — including the telemetryContext
 * identity — is directly testable.
 */
internal fun buildGoogleOptions(
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
): GoogleGenerativeAiApi.GoogleOptions = GoogleGenerativeAiApi.GoogleOptions(
    apiKey = options.apiKey,
    sessionId = options.sessionId,
    temperature = options.temperature,
    maxTokens = works.resolve.pathfinder.ai.utils.clampMaxTokensToContext(
        model,
        context,
        options.maxTokens ?: model.maxTokens,
    ),
    timeoutMs = options.timeoutMs,
    maxRetries = options.maxRetries,
    maxRetryDelayMs = options.maxRetryDelayMs,
    env = options.env,
    headers = options.headers,
    onPayload = options.onPayload,
    onResponse = options.onResponse,
    toolChoice = when (options.toolChoice) {
        null -> null
        SimpleToolChoice.Auto -> "auto"
        SimpleToolChoice.None -> "none"
    },
    thinking = GoogleRequest.thinkingForSimpleStream(
        model,
        options.reasoning,
        options.thinkingBudgets,
        gemmaSupported = true,
    ),
    telemetryContext = options.telemetryContext,
)
