package works.resolve.aletheia.agent

import works.resolve.aletheia.ai.api.ChatApiRegistry
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.models.Models
import works.resolve.aletheia.ai.models.ResolvedAuth
import works.resolve.aletheia.ai.providers.CatalogProvider
import works.resolve.aletheia.ai.providers.ProviderCatalog
import works.resolve.aletheia.ai.providers.normalizeBaseUrl
import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.utils.ProviderRetry
import works.resolve.aletheia.ai.auth.ApiKeyCredential
import works.resolve.aletheia.data.credentials.ApiKeyStore
import works.resolve.aletheia.data.settings.ModelSettings

/**
 * Production [AgentFactory]: builds the native agent from the persisted
 * configuration, serving any provider/model pair the generated catalog knows.
 *
 * All credential ownership lives behind the provider's auth resolver (pi's
 * auth.resolve): credentials are read once per request inside Models.stream's
 * lazy flow, so a rotated or completed credential takes effect on the next
 * prompt. An explicit request key is shaped by the same resolver without
 * reading the store. The API key never enters the agent, its options, or any
 * log; an incomplete credential resolves to null (defense in depth on top of
 * the UI's CatalogProvider.isCredentialComplete gate) and surfaces as a
 * single safe Error event from Models.
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
        // Fail fast on APIs without a Kotlin implementation (the catalog
        // carries all of pi's model APIs); streaming would reject it too.
        require(ChatApiRegistry.isSupported(model.api)) {
            "Unsupported API '${model.api}' for provider '${settings.providerId}' (model '${settings.modelId}')"
        }
        // The selected effective model is created once (pi's requestModel):
        // the catalog model with its normalized base URL.
        val effectiveModel = model.copy(baseUrl = normalizeBaseUrl(model.baseUrl))

        val provider = entry.toRuntimeProvider(
            transport = transport,
            retry = retry,
            authResolver = catalogAuthResolver(entry, credentials),
        )
        val models = Models(listOf(provider))

        return Agent(
            model = effectiveModel,
            streamOptions = SimpleStreamOptions(
                sessionId = sessionId,
                timeoutMs = REQUEST_TIMEOUT_MS,
                maxRetries = MAX_RETRIES,
            ),
            streamFn = StreamFn { requestedModel, context, options ->
                models.stream(requestedModel, context, options)
            },
        ).apply {
            if (initialTranscript.isNotEmpty()) replaceTranscript(initialTranscript)
        }
    }
}

/**
 * The factory's provider auth resolver (pi's auth.resolve with overrides):
 * an explicit request key/env is shaped by the provider's auth semantics
 * without reading stored credentials; otherwise the stored credential is
 * read per request with explicit env merged over stored env per field before
 * completeness and shaping. Returns null when unconfigured.
 */
internal fun catalogAuthResolver(
    entry: CatalogProvider,
    credentials: ApiKeyStore,
): suspend (apiKey: String?, env: Map<String, String>) -> ResolvedAuth? =
    { explicitKey, explicitEnv ->
        if (explicitKey != null && entry.isCredentialComplete(explicitKey, explicitEnv)) {
            entry.toResolvedAuth(explicitKey, explicitEnv)
        } else if (explicitKey != null) {
            // Incomplete explicit auth: unconfigured, never a partial fallback.
            null
        } else {
            credentials.getCredential(entry.id)
                ?.let { credential -> ApiKeyCredential(credential.key, credential.env + explicitEnv) }
                ?.takeIf { entry.isCredentialComplete(it.key, it.env) }
                ?.let { credential -> credential.key?.let { entry.toResolvedAuth(it, credential.env) } }
        }
    }

/** Finite per-request timeout (covers headers through stream end via the call timeout). */
private const val REQUEST_TIMEOUT_MS = 5L * 60 * 1000

/** Minimal retry budget chosen by the app (pi provider-retry defaults to 0);
 * one retry keeps worst-case request duration bounded on mobile. */
private const val MAX_RETRIES = 1
