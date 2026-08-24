package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.api.GoogleRequest.CommonOptions
import works.resolve.aletheia.ai.api.GoogleRequest.GoogleThinking
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.ToolChoice
import works.resolve.aletheia.ai.core.mergeHeaders
import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.utils.ProviderRetry
import kotlinx.coroutines.flow.Flow

/**
 * Google Generative AI (Gemini API) streaming adapter, ported from pi's
 * packages/ai/src/api/google-generative-ai.ts.
 *
 * Upstream drives the `@google/genai` SDK; Aletheia implements the same wire
 * protocol directly: `POST {base}/models/{model}:streamGenerateContent?alt=sse`
 * with the API key in the `x-goog-api-key` header. A blank [Model.baseUrl]
 * means the SDK default (`https://generativelanguage.googleapis.com/v1beta`);
 * a non-blank one already includes the version path (upstream sets
 * `apiVersion: ""` so the SDK does not append one).
 *
 * Divergences (also see [GoogleShared] and [GoogleStreamEngine] KDoc):
 * - `options.fetch`/`onPayload` have no Kotlin counterpart; payload shaping
 *   is testable through the injected [HttpStreamingTransport].
 * - pi's synchronous `throw` for a missing API key (in `stream`/`streamSimple`)
 *   is encoded as a terminal Error event, per the ChatApi contract here.
 * - pi's User-Agent is `getPiUserAgent()`; this port sends
 *   [GoogleRequest.USER_AGENT].
 */
class GoogleGenerativeAiApi(
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ChatApi {

    /** pi's GoogleOptions: StreamOptions plus toolChoice and thinking. */
    data class GoogleOptions(
        val apiKey: String? = null,
        val sessionId: String? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val timeoutMs: Long? = null,
        val maxRetries: Int = 0,
        val maxRetryDelayMs: Long = works.resolve.aletheia.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
        val env: Map<String, String> = emptyMap(),
        val headers: Map<String, String?> = emptyMap(),
        /** "auto" | "none" | "any". */
        val toolChoice: String? = null,
        val thinking: GoogleThinking? = null,
    ) {
        override fun toString(): String = CommonOptions(
            apiKey, sessionId, temperature, maxTokens, timeoutMs, maxRetries, maxRetryDelayMs,
            env, headers, toolChoice, thinking,
        ).toString()

        internal fun toCommon() = CommonOptions(
            apiKey, sessionId, temperature, maxTokens, timeoutMs, maxRetries, maxRetryDelayMs,
            env, headers, toolChoice, thinking,
        )
    }

    /**
     * ChatApi integration: the provider-neutral options are interpreted like
     * pi's streamSimple — a null/OFF reasoning effort sends
     * `thinking { enabled: false }`, anything else resolves through
     * [GoogleRequest.thinkingForSimpleStream] (Gemini 3 levels, Gemma 4
     * levels, or Gemini 2.5 budgets).
     */
    override fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): Flow<works.resolve.aletheia.ai.core.AssistantMessageEvent> {
        val thinking = GoogleRequest.thinkingForSimpleStream(
            model,
            options.reasoning,
            options.thinkingBudgets,
            gemmaSupported = true,
        )
        return stream(
            model,
            context,
            GoogleOptions(
                apiKey = options.apiKey,
                sessionId = options.sessionId,
                temperature = options.temperature,
                maxTokens = works.resolve.aletheia.ai.utils.clampMaxTokensToContext(
                    model,
                    context,
                    options.maxTokens ?: model.maxTokens,
                ),
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
                env = options.env,
                headers = options.headers,
                toolChoice = when (options.toolChoice) {
                    null -> null
                    ToolChoice.Auto -> "auto"
                    ToolChoice.None -> "none"
                    ToolChoice.Any, ToolChoice.Required, is ToolChoice.Function -> "any"
                },
                thinking = thinking,
            ),
        )
    }

    /** The pi `stream` entry point with full GoogleOptions. */
    fun stream(
        model: Model,
        context: Context,
        options: GoogleOptions,
    ): Flow<works.resolve.aletheia.ai.core.AssistantMessageEvent> {
        // pi throws synchronously for a missing key; the ChatApi contract here
        // encodes setup failures as a terminal Error event instead.
        val apiKey = options.apiKey
            ?: return missingApiKeyFlow(model)

        val body = GoogleRequest.buildGenerateContentRequest(model, context, options.toCommon(), gemmaSupported = true)

        val baseUrl = model.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val url = "$baseUrl/models/${model.id}:streamGenerateContent?alt=sse"

        // pi: providerHeadersToRecord({"User-Agent": ..., ...model.headers, ...optionsHeaders})
        val headers = mergeHeaders(
            mergeHeaders(mapOf("User-Agent" to GoogleRequest.USER_AGENT), model.headers),
            options.headers,
        ).filterValues { it != null }
            .mapValues { it.value!! } + mapOf("x-goog-api-key" to apiKey)

        return GoogleStreamEngine.stream(
            transport,
            retry,
            nowMs,
            model,
            GoogleStreamEngine.Plan(
                url = url,
                headers = headers,
                body = body.toString().toByteArray(Charsets.UTF_8),
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
            ),
        )
    }

    private companion object {
        /** The `@google/genai` SDK default endpoint for the Gemini API. */
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    private fun missingApiKeyFlow(model: Model) = kotlinx.coroutines.flow.flow {
        val message = works.resolve.aletheia.ai.core.AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = works.resolve.aletheia.ai.core.StopReason.ERROR,
            errorMessage = "No API key for provider: ${model.provider}",
            timestamp = nowMs(),
        )
        emit(
            works.resolve.aletheia.ai.core.AssistantMessageEvent.Error(
                works.resolve.aletheia.ai.core.StopReason.ERROR,
                message,
            ),
        )
    }
}
