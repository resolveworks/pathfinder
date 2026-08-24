package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.api.GoogleRequest.CommonOptions
import works.resolve.aletheia.ai.api.GoogleRequest.GoogleThinking
import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.mergeHeaders
import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.utils.ProviderRetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Vertex AI (Gemini) streaming adapter, ported from pi's
 * packages/ai/src/api/google-vertex.ts.
 *
 * Endpoint/auth shaping mirrors upstream exactly: an API key (Vertex Express)
 * is used only when it is neither blank, the `gcp-vertex-credentials` marker,
 * nor a `<...>` placeholder; the project comes from options or
 * GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT and the location from options or
 * GOOGLE_CLOUD_LOCATION; the request goes to
 * `https://{location}-aiplatform.googleapis.com/v1/publishers/google/models/{model}:streamGenerateContent?alt=sse`
 * (a custom base URL without `{location}` replaces the host root, and one that
 * already contains a `/vN` or `/vNbeta` path segment is used verbatim).
 *
 * Divergence (out of scope by design): upstream falls back to Application
 * Default Credentials (`googleAuthOptions`, GOOGLE_APPLICATION_CREDENTIALS
 * key files) when no API key resolves; interactive Vertex credential
 * storage/acquisition is not ported to Android, so the ADC path terminates
 * with a terminal Error event naming the missing piece. Project/location
 * resolution errors keep upstream's messages and ordering (they fire before
 * the request is sent).
 */
class GoogleVertexApi(
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ChatApi {

    /** pi's GoogleVertexOptions: GoogleOptions plus project and location. */
    data class GoogleVertexOptions(
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
        val project: String? = null,
        val location: String? = null,
    ) {
        override fun toString(): String = CommonOptions(
            apiKey, sessionId, temperature, maxTokens, timeoutMs, maxRetries, maxRetryDelayMs,
            env, headers, toolChoice, thinking,
        ).toString() + ", project=$project, location=$location"

        internal fun toCommon() = CommonOptions(
            apiKey, sessionId, temperature, maxTokens, timeoutMs, maxRetries, maxRetryDelayMs,
            env, headers, toolChoice, thinking,
        )
    }

    /** ChatApi integration; see [GoogleGenerativeAiApi.stream] (Vertex has no Gemma branches upstream). */
    override fun stream(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
    ): Flow<AssistantMessageEvent> {
        val effort = options.reasoningEffort?.let {
            works.resolve.aletheia.ai.core.ModelThinkingLevel.valueOf(it.name)
        }
        val thinking = GoogleRequest.thinkingForSimpleStream(
            model,
            effort?.let { level ->
                works.resolve.aletheia.ai.core.ThinkingLevel.entries
                    .firstOrNull { it.name == level.name }
            },
            options.thinkingBudgets,
            gemmaSupported = false,
        )
        return stream(
            model,
            context,
            GoogleVertexOptions(
                apiKey = options.apiKey,
                sessionId = options.sessionId,
                temperature = options.temperature,
                maxTokens = options.maxTokens,
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
                env = options.env,
                headers = options.headers,
                thinking = thinking,
            ),
        )
    }

    /** The pi `stream` entry point with full GoogleVertexOptions. */
    fun stream(
        model: Model,
        context: Context,
        options: GoogleVertexOptions,
    ): Flow<AssistantMessageEvent> {
        // Upstream's buildParams runs before the request; project/location
        // resolution errors (and the ADC divergence below) surface as terminal
        // Error events per the ChatApi contract here.
        val body = GoogleRequest.buildGenerateContentRequest(model, context, options.toCommon(), gemmaSupported = false)

        val apiKey = resolveApiKey(options)

        // pi: providerHeadersToRecord({"User-Agent": ..., ...model.headers, ...optionsHeaders})
        val mergedHeaders = mergeHeaders(
            mergeHeaders(mapOf("User-Agent" to GoogleRequest.USER_AGENT), model.headers),
            options.headers,
        ).filterValues { it != null }.mapValues { it.value!! }
            .let { if (apiKey != null) it + mapOf("x-goog-api-key" to apiKey) else it }

        val urlFlow: Flow<AssistantMessageEvent> = try {
            val project = resolveProject(options)
            val location = resolveLocation(options)
            val base = resolveBaseUrl(model.baseUrl, location)
            val url = "$base/publishers/google/models/${model.id}:streamGenerateContent?alt=sse"
            if (apiKey == null) {
                adcUnsupportedFlow(model, project, location)
            } else {
                GoogleStreamEngine.stream(
                    transport,
                    retry,
                    nowMs,
                    model,
                    GoogleStreamEngine.Plan(
                        url = url,
                        headers = mergedHeaders,
                        body = body.toString().toByteArray(Charsets.UTF_8),
                        timeoutMs = options.timeoutMs,
                        maxRetries = options.maxRetries,
                        maxRetryDelayMs = options.maxRetryDelayMs,
                    ),
                )
            }
        } catch (error: IllegalStateException) {
            setupErrorFlow(model, error.message ?: "Vertex AI setup failed")
        }
        return urlFlow
    }

    private fun setupErrorFlow(model: Model, message: String): Flow<AssistantMessageEvent> = flow {
        emit(
            AssistantMessageEvent.Error(
                StopReason.ERROR,
                AssistantMessage(
                    content = emptyList(),
                    api = model.api,
                    provider = model.provider,
                    model = model.id,
                    stopReason = StopReason.ERROR,
                    errorMessage = message,
                    timestamp = nowMs(),
                ),
            ),
        )
    }

    private fun adcUnsupportedFlow(model: Model, project: String, location: String): Flow<AssistantMessageEvent> =
        setupErrorFlow(
            model,
            "Vertex AI requires Application Default Credentials for project $project in $location, " +
                "which Aletheia does not support; provide a Vertex Express API key",
        )

    private companion object {
        const val API_VERSION = "v1"
        const val GCP_VERTEX_CREDENTIALS_MARKER = "gcp-vertex-credentials"

        private val API_VERSION_SEGMENT = Regex("""(?:^|/)v\d+(?:beta\d*)?(?:/|$)""")

        /** pi's resolveApiKey: blank, marker, and `<...>` placeholder keys fall back to ADC. */
        fun resolveApiKey(options: GoogleVertexOptions): String? {
            val apiKey = options.apiKey?.trim()
            if (apiKey.isNullOrEmpty() || apiKey == GCP_VERTEX_CREDENTIALS_MARKER) return null
            if (Regex("^<[^>]+>$").matches(apiKey)) return null
            return apiKey
        }

        /** pi's resolveProject. */
        fun resolveProject(options: GoogleVertexOptions): String {
            val project = options.project
                ?: options.env["GOOGLE_CLOUD_PROJECT"]
                ?: options.env["GCLOUD_PROJECT"]
            if (project.isNullOrEmpty()) {
                throw IllegalStateException(
                    "Vertex AI requires a project ID. Set GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT or pass project in options.",
                )
            }
            return project
        }

        /** pi's resolveLocation. */
        fun resolveLocation(options: GoogleVertexOptions): String {
            val location = options.location ?: options.env["GOOGLE_CLOUD_LOCATION"]
            if (location.isNullOrEmpty()) {
                throw IllegalStateException(
                    "Vertex AI requires a location. Set GOOGLE_CLOUD_LOCATION or pass location in options.",
                )
            }
            return location
        }

        /**
         * pi's buildHttpOptions base URL handling: a custom base URL without
         * `{location}` replaces the default root; if it already contains an
         * API version path segment the SDK does not append `v1` (upstream's
         * baseUrlIncludesApiVersion + baseUrlResourceScope=COLLECTION).
         */
        fun resolveBaseUrl(modelBaseUrl: String, location: String): String {
            val trimmed = modelBaseUrl.trim()
            if (trimmed.isNotEmpty() && !trimmed.contains("{location}")) {
                val base = trimmed.trimEnd('/')
                return if (hasApiVersionSegment(base)) base else "$base/$API_VERSION"
            }
            return "https://$location-aiplatform.googleapis.com/$API_VERSION"
        }

        private fun hasApiVersionSegment(baseUrl: String): Boolean {
            val path = baseUrl.substringAfter("://", baseUrl).substringAfter('/', "")
            return path.isNotEmpty() && API_VERSION_SEGMENT.containsMatchIn("/$path/")
        }
    }
}
