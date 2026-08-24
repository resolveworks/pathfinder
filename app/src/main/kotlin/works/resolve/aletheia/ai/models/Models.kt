package works.resolve.aletheia.ai.models

import works.resolve.aletheia.ai.api.ChatApi
import works.resolve.aletheia.ai.api.streamSimple
import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.mergeHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A resolved provider credential (pi's auth resolve result): the API key (or,
 * for header-auth providers, resolved auth headers), plus provider env values
 * (e.g. Cloudflare account/gateway ids) substituted into base-URL
 * placeholders at request time. Pure AI-layer type; it never exposes how the
 * app stores credentials.
 */
class ResolvedAuth(
    /** Bearer API key; null when auth is carried entirely by [headers]. */
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** Resolved auth headers (pi's ProviderHeaders): a null value removes the header when merged. */
    val headers: Map<String, String?> = emptyMap(),
) {
    override fun toString(): String =
        "ResolvedAuth(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", env=${env.keys}, headers=${headers.keys})"
}

/**
 * Provider identity, auth semantics, and model catalog — the pi Provider
 * concept. A provider owns which API implementation serves its models; the
 * registry dispatches by the model's provider id.
 */
class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /**
     * Resolves this provider's auth (pi's auth.resolve with request
     * overrides): a non-null [apiKey] is an explicit request key that must be
     * shaped by the provider's auth semantics (e.g. Cloudflare's
     * cf-aig-authorization header) WITHOUT reading stored credentials; null
     * means resolve the stored credential. Returns null when unconfigured.
     */
    val authResolver: (suspend (apiKey: String?) -> ResolvedAuth?)? = null,
    val models: List<Model>,
    val api: ChatApi,
)

/**
 * Registry dispatching chat streams by provider and model, resolving auth
 * from an explicit key first, then the provider's credential resolver.
 * Mirrors pi's Models entry point (stream takes the Model object and applies
 * auth ownership like pi's applyAuth), reduced to one streaming method.
 */
class Models(
    providers: List<Provider>,
) {
    private val byId = providers.associateBy { it.id }

    fun getProviders(): List<Provider> = byId.values.toList()

    fun getProvider(id: String): Provider? = byId[id]

    fun getModel(providerId: String, modelId: String): Model? =
        getProvider(providerId)?.models?.firstOrNull { it.id == modelId }

    private fun requireProvider(model: Model): Provider =
        byId[model.provider]
            ?: throw IllegalArgumentException("Unknown provider: ${model.provider}")

    /**
     * Starts a chat stream for [model] (pi's streamSimple ownership): the
     * model's provider must be registered; auth is resolved lazily inside the
     * flow and merged with [options] using pi's applyAuth precedence —
     * explicit request fields win, but like pi's resolveProviderAuth an
     * explicit apiKey is still passed through the provider's auth resolver so
     * custom auth shaping (Cloudflare's header auth) applies to it, without
     * reading stored credentials; env values merge per field with the request
     * on top, and resolved auth headers merge under explicit request headers
     * case-insensitively (pi's mergeHeaders). An absent or failing credential resolution surfaces as a single safe terminal
     * [AssistantMessageEvent.Error] event; unknown providers throw
     * immediately.
     */
    fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): Flow<AssistantMessageEvent> {
        val provider = requireProvider(model)
        return flow {
            // Resolve the credential lazily inside the flow so stored-credential
            // lookups can suspend without making stream() a suspend call.
            val auth = try {
                provider.authResolver?.invoke(options.apiKey)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emitAuthError(model, provider, "Failed to resolve stored credential for provider '${provider.id}'")
                return@flow
            }
            if (options.apiKey == null && auth == null) {
                emitAuthError(model, provider, "Provider '${provider.id}' is not configured")
                return@flow
            }
            val authHeaders = auth?.headers ?: emptyMap()
            // An explicit or resolved key normally wins per-field, but a
            // header-shaped resolution (auth.apiKey == null with headers, e.g.
            // Cloudflare) consumed the key into those headers; there is no
            // default apiKey/Authorization path left to fill.
            val mergedApiKey = when {
                auth != null && auth.apiKey == null && authHeaders.isNotEmpty() -> null
                else -> options.apiKey ?: auth?.apiKey
            }
            val merged = options.copy(
                apiKey = mergedApiKey,
                env = if (auth == null || auth.env.isEmpty()) {
                    options.env
                } else {
                    auth.env + options.env
                },
                headers = mergeHeaders(authHeaders, options.headers),
            )
            provider.api.streamSimple(model, context, merged).collect { emit(it) }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AssistantMessageEvent>.emitAuthError(
        model: Model,
        provider: Provider,
        message: String,
    ) {
        emit(
            AssistantMessageEvent.Error(
                StopReason.ERROR,
                AssistantMessage(
                    content = emptyList(),
                    api = model.api,
                    provider = provider.id,
                    model = model.id,
                    stopReason = StopReason.ERROR,
                    // Safe generic message: no exception or credential text.
                    errorMessage = message,
                    timestamp = System.currentTimeMillis(),
                ),
            ),
        )
    }
}
