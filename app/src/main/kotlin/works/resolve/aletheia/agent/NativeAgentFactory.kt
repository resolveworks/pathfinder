package works.resolve.aletheia.agent

import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.models.Models
import works.resolve.aletheia.ai.providers.ProviderCatalog
import works.resolve.aletheia.ai.providers.normalizeBaseUrl
import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.utils.ProviderRetry
import works.resolve.aletheia.data.credentials.ApiKeyStore
import works.resolve.aletheia.data.settings.ModelSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Production [AgentFactory]: builds the native agent from the persisted
 * configuration, serving any provider/model pair the generated catalog knows.
 *
 * The API key never enters the agent, its options, or any log: credentials
 * are read once per request inside the streaming flow (the explicit
 * options.apiKey takes precedence in Models.stream, so the provider's lazy
 * resolver is skipped), and a rotated key takes effect on the next prompt.
 */
class NativeAgentFactory(
    private val credentials: ApiKeyStore,
    private val catalog: ProviderCatalog,
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
) : AgentFactory {

    override fun create(settings: ModelSettings, sessionId: String, initialTranscript: List<Message>): Agent {
        val entry = catalog.getProvider(settings.providerId)
            ?: throw IllegalArgumentException("Unsupported provider: ${settings.providerId}")
        val model = entry.model(settings.modelId)
            ?: throw IllegalArgumentException(
                "Unknown model '${settings.modelId}' for provider '${settings.providerId}'",
            )
        val baseUrl = normalizeBaseUrl(settings.baseUrl ?: entry.baseUrl)

        val provider = entry.toRuntimeProvider(
            transport = transport,
            retry = retry,
            apiKeyResolver = { credentials.getApiKey(entry.id) },
            baseUrl = baseUrl,
        )
        val models = Models(listOf(provider))
        // Use the possibly-override-stamped model from the runtime provider.
        val effectiveModel = provider.models.first { it.id == settings.modelId }

        return Agent(
            model = effectiveModel,
            streamOptions = SimpleStreamOptions(
                sessionId = sessionId,
                timeoutMs = REQUEST_TIMEOUT_MS,
                maxRetries = MAX_RETRIES,
            ),
            streamFn = StreamFn { requestedModel, context, options ->
                // Resolve the full credential once per request and merge it
                // into the options (pi's applyAuth): key + env for base-URL
                // placeholder substitution, and the provider's bearer-header
                // override for Cloudflare-style gateways. The explicit
                // options.apiKey takes precedence in Models.stream, so the
                // provider's own lazy resolver is skipped.
                flow {
                    val credential = try {
                        credentials.getCredential(entry.id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        emit(
                            AssistantMessageEvent.Error(
                                StopReason.ERROR,
                                AssistantMessage(
                                    content = emptyList(),
                                    api = requestedModel.api,
                                    provider = entry.id,
                                    model = requestedModel.id,
                                    stopReason = StopReason.ERROR,
                                    errorMessage =
                                        "Failed to resolve stored credential for provider '${entry.id}'",
                                    timestamp = System.currentTimeMillis(),
                                ),
                            ),
                        )
                        return@flow
                    }
                    models.stream(
                        entry.id,
                        requestedModel.id,
                        context,
                        options.copy(
                            apiKey = credential?.key,
                            env = credential?.env ?: emptyMap(),
                            bearerHeaderName = entry.bearerHeaderName,
                        ),
                    ).collect { emit(it) }
                }
            },
        ).apply {
            if (initialTranscript.isNotEmpty()) replaceTranscript(initialTranscript)
        }
    }

    private companion object {
        /** Finite per-request timeout (covers headers through stream end via the call timeout). */
        const val REQUEST_TIMEOUT_MS = 5L * 60 * 1000

        /** Minimal retry budget chosen by the app (pi provider-retry defaults to 0);
         * one retry keeps worst-case request duration bounded on mobile. */
        const val MAX_RETRIES = 1
    }
}
