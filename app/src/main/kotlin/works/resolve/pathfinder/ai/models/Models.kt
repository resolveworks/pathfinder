package works.resolve.pathfinder.ai.models

import works.resolve.pathfinder.ai.api.ChatApi
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.mergeHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last

// Exclusion: pi's models-store.ts (dynamic catalog persistence and refresh)
// is not ported; Pathfinder uses the bundled static catalog per the policy in
// ai/AGENTS.md.

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
    /**
     * Credential-specific request base URL overriding the model's (pi
     * `ModelAuth.baseUrl`, e.g. GitHub Copilot's per-account proxy
     * endpoint); null keeps the model's own base URL. Not secret.
     */
    val baseUrl: String? = null,
) {
    override fun toString(): String =
        "ResolvedAuth(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", env=${env.keys}, headers=${headers.keys}, baseUrl=$baseUrl)"
}

/**
 * Provider identity, auth semantics, and model catalog — the pi Provider
 * concept. A provider owns which API implementations serve its models,
 * keyed by model API id (pi's `api: Api | Record<ApiId, Api>`); the
 * registry dispatches by the model's provider id and `model.api`.
 */
class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /**
     * Resolves this provider's auth (pi's auth.resolve with request
     * overrides): [apiKey]/[env] are explicit request overrides that must be
     * shaped by the provider's auth semantics (e.g. Cloudflare's
     * cf-aig-authorization header) WITHOUT reading stored credentials; a null
     * [apiKey] with empty [env] means resolve the stored credential (explicit
     * env still merges over stored env there). Returns null when unconfigured.
     */
    val authResolver: (suspend (apiKey: String?, env: Map<String, String>) -> ResolvedAuth?)? = null,
    val models: List<Model>,
    val apis: Map<String, ChatApi>,
) {
    /** Single-API convenience (pi's `api: Api` provider shape). */
    constructor(
        id: String,
        name: String,
        baseUrl: String,
        authResolver: (suspend (apiKey: String?, env: Map<String, String>) -> ResolvedAuth?)? = null,
        models: List<Model>,
        apiId: String,
        api: ChatApi,
    ) : this(id, name, baseUrl, authResolver, models, mapOf(apiId to api))
}

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

    companion object {
        /**
         * Port of pi's models.ts `modelsAreEqual` (packages/ai/src/models.ts,
         * `modelsAreEqual`): two models are equal when both their id and
         * provider match; null (or absent) on either side is never equal.
         *
         * Omitted from the port: pi's `hasApi` (models.ts) is a TypeScript
         * type guard narrowing `Model<Api>` to `Model<TApi>` for dynamically
         * looked-up models. Kotlin's `Model.api` is a plain string and the
         * type system has no equivalent narrowing, so there is no runtime
         * counterpart to port.
         */
        fun modelsAreEqual(a: Model?, b: Model?): Boolean =
            a != null && b != null && a.id == b.id && a.provider == b.provider
    }

    private fun requireProvider(model: Model): Provider =
        byId[model.provider]
            ?: throw IllegalArgumentException("Unknown provider: ${model.provider}")

    /**
     * Starts a chat stream for [model] (pi's streamSimple ownership): the
     * model's provider must be registered; auth is resolved lazily inside the
     * flow and merged with [options] using pi's applyAuth precedence —
     * explicit request fields win, but like pi's resolveProviderAuth the
     * explicit apiKey/env are still passed through the provider's auth
     * resolver so custom auth shaping (Cloudflare's header auth) applies to
     * them, without reading stored credentials; env values merge per field
     * with the request on top, and resolved auth headers merge under explicit
     * request headers case-insensitively (pi's mergeHeaders). An absent or
     * failing credential resolution surfaces as a single safe terminal
     * [AssistantMessageEvent.Error] event; unknown providers throw
     * immediately.
     */
    fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): Flow<AssistantMessageEvent> {
        val provider = requireProvider(model)
        val api = provider.apis[model.api]
            ?: throw IllegalArgumentException(
                "Provider '${provider.id}' has no API implementation for '${model.api}'" +
                    " (model '${model.id}')",
            )
        return flow {
            // Resolve the credential lazily inside the flow so stored-credential
            // lookups can suspend without making stream() a suspend call.
            val auth = try {
                provider.authResolver?.invoke(options.apiKey, options.env)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emitAuthError(model, provider, "Failed to resolve stored credential for provider '${provider.id}'")
                return@flow
            }
            // A provider with an auth resolver owns auth semantics: a null
            // resolution always means unconfigured, even with an explicit
            // request key (never fall back to a raw Authorization). Only a
            // resolver-less provider may use a raw explicit apiKey directly.
            if (auth == null && (provider.authResolver != null || options.apiKey == null)) {
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
            // pi applyAuth: `auth.baseUrl ? { ...model, baseUrl: auth.baseUrl } : model` —
            // a credential-specific base URL (GitHub Copilot's proxy endpoint)
            // overrides the model's catalog base URL for this request.
            val requestModel = auth?.baseUrl?.let { model.copy(baseUrl = it) } ?: model
            val merged = options.copy(
                apiKey = mergedApiKey,
                env = if (auth == null || auth.env.isEmpty()) {
                    options.env
                } else {
                    auth.env + options.env
                },
                headers = mergeHeaders(authHeaders, options.headers),
            )
            api.streamSimple(requestModel, context, merged).collect { emit(it) }
        }
    }

    /**
     * Port of pi's models.ts `completeSimple` (packages/ai/src/models.ts,
     * `completeSimple` = `streamSimple(model, context, options).result()`):
     * collects the event stream to its terminal AssistantMessage. A terminal
     * `Error` event is returned as the ERROR/ABORTED AssistantMessage per the
     * stream contract (callers inspect `stopReason`, like pi's `result()`);
     * a malformed stream that ends without a terminal event throws, like
     * pi's result() rejecting on a stream without a message.
     *
     * Divergence: pi's `streamSimple` is a separate models entry point that
     * applies auth over provider.streamSimple; pathfinder's [stream] already
     * is that exact simple-API path (the port reduced Models to one streaming
     * method), so `completeSimple` collects [stream] directly instead of
     * duplicating a streamSimple alias.
     */
    suspend fun completeSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): AssistantMessage {
        var terminal: AssistantMessage? = null
        stream(model, context, options).collect { event ->
            when (event) {
                is AssistantMessageEvent.Done -> terminal = event.message
                is AssistantMessageEvent.Error -> terminal = event.error
                else -> {}
            }
        }
        return checkNotNull(terminal) {
            "Stream for model '${model.id}' ended without a terminal Done/Error event"
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
