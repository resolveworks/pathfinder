package works.resolve.pathfinder.ai.models

import kotlin.time.Clock
import works.resolve.pathfinder.ai.api.ChatApi
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.mergeHeaders
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.GITHUB_COPILOT_PROVIDER_ID
import works.resolve.pathfinder.ai.providers.filterGitHubCopilotModels
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last

// Pathfinder uses a bundled static catalog rather than pi's dynamic model store.

/**
 * A resolved provider credential: the API key (or, for header-auth providers,
 * resolved auth headers), plus provider env values (e.g. Cloudflare
 * account/gateway ids) substituted into base-URL placeholders at request
 * time. Pure AI-layer type; it never exposes how the app stores credentials.
 */
class ResolvedAuth(
    /** Bearer API key; null when auth is carried entirely by [headers]. */
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** Resolved auth headers: a null value removes the header when merged. */
    val headers: Map<String, String?> = emptyMap(),
    /**
     * Credential-specific request base URL overriding the model's (e.g.
     * GitHub Copilot's per-account proxy endpoint); null keeps the model's
     * own. Not secret.
     */
    val baseUrl: String? = null,
) {
    override fun toString(): String = optionsToString(
        "ResolvedAuth",
        "apiKey" to redactedSecret(apiKey),
        "env" to env.keys,
        "headers" to headers.keys,
        "baseUrl" to baseUrl,
    )
}

/**
 * Provider identity, auth semantics, and model catalog; [apis] maps each
 * supported model API id to the chat implementation serving it.
 */
class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /**
     * Resolves this provider's auth with explicit request overrides:
     * [apiKey]/[env] must be shaped by the provider's auth semantics (e.g.
     * Cloudflare's cf-aig-authorization header) WITHOUT reading stored
     * credentials; a null [apiKey] with empty [env] means resolve the stored
     * credential (explicit env still merges over stored env there). Returns
     * null when unconfigured.
     */
    val authResolver: (suspend (apiKey: String?, env: Map<String, String>) -> ResolvedAuth?)? = null,
    val models: List<Model>,
    val apis: Map<String, ChatApi>,
) {
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

class Models(
    providers: List<Provider>,
    private val clock: Clock = Clock.System,
) {
    private val byId = providers.associateBy { it.id }

    fun getProviders(): List<Provider> = byId.values.toList()

    fun getProvider(id: String): Provider? = byId[id]

    fun getModel(providerId: String, modelId: String): Model? =
        getProvider(providerId)?.models?.firstOrNull { it.id == modelId }

    /**
     * Whether auth is configured for a provider. Reads the stored credential
     * lazily per call, so a rotated key is observed on the next check; a
     * resolver failure counts as unconfigured (false), not an exception.
     */
    suspend fun checkAuth(providerId: String): Boolean {
        val provider = byId[providerId] ?: return false
        val resolver = provider.authResolver ?: return false
        return try {
            resolver(null, emptyMap()) != null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
    }

    companion object {
        fun modelsAreEqual(a: Model?, b: Model?): Boolean =
            a != null && b != null && a.id == b.id && a.provider == b.provider
    }

    private fun requireProvider(model: Model): Provider =
        byId[model.provider]
            ?: throw IllegalArgumentException("Unknown provider: ${model.provider}")

    /**
     * Starts a chat stream for [model]: the model's provider must be
     * registered; unknown providers throw immediately. Auth is resolved
     * lazily inside the flow and merged with [options]; the explicit
     * [options] apiKey/env still pass through the provider's auth resolver so
     * custom auth shaping (Cloudflare's header auth) applies to them, without
     * reading stored credentials. Explicit request fields win: env values
     * merge per field with the request on top, and resolved auth headers
     * merge under explicit request headers case-insensitively. An absent or
     * failing credential resolution surfaces as a single safe terminal
     * [AssistantMessageEvent.Error] event, not a mid-stream exception.
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
     * Collects [stream] to its terminal AssistantMessage. A terminal `Error`
     * event is returned as the error AssistantMessage, so callers inspect
     * [AssistantMessage.stopReason] to distinguish failure from success; a
     * stream that ends without a terminal event throws.
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
                    timestamp = clock.now().toEpochMilliseconds(),
                ),
            ),
        )
    }
}

/**
 * pi's per-provider `filterModels` hook dispatch, which `getAvailable`
 * applies as `provider.filterModels?.(models, credential) ?? models`
 * (models.ts L538 at pin b8b873b98); GitHub Copilot alone defines the hook.
 * The dispatch lives in this free function instead of a `filterModels`
 * field on [CatalogProvider] because the catalog is generated data, while
 * the filter is runtime behavior tied to the credential shape.
 */
fun filterCatalogModels(provider: CatalogProvider, credential: Credential?): List<Model> =
    if (provider.id == GITHUB_COPILOT_PROVIDER_ID) {
        filterGitHubCopilotModels(provider.models, credential)
    } else {
        provider.models
    }
