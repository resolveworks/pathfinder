package com.aletheia.ai.models

import com.aletheia.ai.api.ChatApi
import com.aletheia.ai.api.streamSimple
import com.aletheia.ai.core.AssistantMessageEvent
import com.aletheia.ai.core.Context
import com.aletheia.ai.core.Model
import com.aletheia.ai.core.SimpleStreamOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Provider identity, auth semantics, and model catalog — the pi Provider
 * concept. A provider owns which API implementation serves its models; the
 * registry dispatches by provider/model id.
 */
class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** Resolves the stored API key for this provider (pi's auth.resolve). */
    val apiKeyResolver: (suspend () -> String?)? = null,
    val models: List<Model>,
    val api: ChatApi,
)

/**
 * Registry dispatching chat streams by provider and model id, resolving auth
 * from an explicit key first, then the provider's credential resolver.
 * Mirrors the pi Models entry point, reduced to one streaming method.
 */
class Models(
    providers: List<Provider>,
) {
    private val byId = providers.associateBy { it.id }

    fun getProviders(): List<Provider> = byId.values.toList()

    fun getProvider(id: String): Provider? = byId[id]

    fun getModel(providerId: String, modelId: String): Model? =
        getProvider(providerId)?.models?.firstOrNull { it.id == modelId }

    /**
     * Starts a chat stream. Unknown provider/model ids throw immediately;
     * request failures (including a missing API key) surface as
     * [AssistantMessageEvent.Error] events, like pi.
     */
    fun stream(
        providerId: String,
        modelId: String,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): Flow<AssistantMessageEvent> {
        val provider = getProvider(providerId)
            ?: throw IllegalArgumentException("Unknown provider: $providerId")
        val model = provider.models.firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown model '$modelId' for provider '$providerId'")
        return flow {
            // Resolve the credential lazily inside the flow so stored-credential
            // lookups can suspend without making stream() a suspend call.
            val apiKey = options.apiKey ?: provider.apiKeyResolver?.invoke()
            provider.api.streamSimple(
                model,
                context,
                options.copy(apiKey = apiKey),
            ).collect { emit(it) }
        }
    }
}
