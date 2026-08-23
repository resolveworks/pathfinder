package works.resolve.aletheia.agent

import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.models.Models
import works.resolve.aletheia.ai.providers.ZaiModels
import works.resolve.aletheia.ai.providers.ZaiProvider
import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.utils.ProviderRetry
import works.resolve.aletheia.data.credentials.ApiKeyStore
import works.resolve.aletheia.data.settings.ModelSettings

/**
 * Production [AgentFactory]: builds the real native Z.AI agent from the
 * persisted configuration.
 *
 * The API key never enters the agent, its options, or any log: the provider's
 * key resolver stays lazy and reads [ApiKeyStore] once per request inside the
 * streaming flow, so a rotated key takes effect on the next prompt.
 */
class NativeAgentFactory(
    private val credentials: ApiKeyStore,
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
) : AgentFactory {

    override fun create(settings: ModelSettings, sessionId: String, initialTranscript: List<Message>): Agent {
        require(settings.providerId == ZaiModels.PROVIDER_ID) {
            "Unsupported provider: ${settings.providerId}"
        }
        require(settings.modelId in KNOWN_MODEL_IDS) {
            "Unknown model '${settings.modelId}' for provider '${ZaiModels.PROVIDER_ID}'"
        }
        val baseUrl = ZaiProvider.normalizeBaseUrl(settings.baseUrl ?: ZaiModels.BASE_URL)

        val provider = ZaiProvider.create(
            transport = transport,
            retry = retry,
            apiKeyResolver = { credentials.getApiKey(ZaiModels.PROVIDER_ID) },
            baseUrl = baseUrl,
        )
        val models = Models(listOf(provider))
        val model = provider.models.first { it.id == settings.modelId }

        return Agent(
            model = model,
            streamOptions = SimpleStreamOptions(
                sessionId = sessionId,
                timeoutMs = REQUEST_TIMEOUT_MS,
                maxRetries = MAX_RETRIES,
            ),
            streamFn = StreamFn { requestedModel, context, options ->
                models.stream(requestedModel.provider, requestedModel.id, context, options)
            },
        ).apply {
            if (initialTranscript.isNotEmpty()) replaceTranscript(initialTranscript)
        }
    }

    private companion object {
        val KNOWN_MODEL_IDS: Set<String> = ZaiModels.ALL.map { it.id }.toSet()

        /** Finite per-request timeout (covers headers through stream end via the call timeout). */
        const val REQUEST_TIMEOUT_MS = 5L * 60 * 1000

        /** Minimal retry budget chosen by the app (pi provider-retry defaults to 0);
         * one retry keeps worst-case request duration bounded on mobile. */
        const val MAX_RETRIES = 1
    }
}
