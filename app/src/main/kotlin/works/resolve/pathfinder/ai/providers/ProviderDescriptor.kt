package works.resolve.pathfinder.ai.providers

import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.llm.LLModel

/** One selectable model of a provider, backed by its Koog [LLModel]. */
data class ModelDescriptor(
    val id: String,
    /**
     * Display name. Koog model ids (e.g. "claude-haiku-4-5") are already
     * human-readable, so the id doubles as the display name; introduce a
     * curated surface only when the UI needs one.
     */
    val displayName: String,
    val model: LLModel,
)

/**
 * One provider the app can be configured with: its stable id, display name,
 * the API-key prompt for its credential form, and its selectable models.
 */
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    /** Label shown next to the API-key field of this provider's credential form. */
    val apiKeyPrompt: String,
    val models: List<ModelDescriptor>,
) {
    fun model(modelId: String): ModelDescriptor? = models.firstOrNull { it.id == modelId }
}

/**
 * The app-owned provider surface: presentation metadata plus Koog model
 * objects. Model lists are enumerated from Koog's own `LLModelDefinitions`
 * singletons (`ai.koog.prompt.executor.clients.<provider>`) rather than
 * hand-copied, so the catalog cannot drift from the Koog runtime that will
 * execute against these models (chunk 2 maps each provider id to its Koog
 * executor client). This file is permanent app architecture.
 */
object ProviderDescriptors {

    val all: List<ProviderDescriptor> = listOf(
        provider(
            id = "anthropic",
            displayName = "Anthropic",
            apiKeyPrompt = "Anthropic API key",
            definitions = AnthropicModels,
        ),
        provider(
            id = "openai",
            displayName = "OpenAI",
            apiKeyPrompt = "OpenAI API key",
            definitions = OpenAIModels,
        ),
        provider(
            id = "google",
            displayName = "Google",
            apiKeyPrompt = "Google AI Studio API key",
            definitions = GoogleModels,
        ),
        provider(
            id = "openrouter",
            displayName = "OpenRouter",
            apiKeyPrompt = "OpenRouter API key",
            definitions = OpenRouterModels,
        ),
        provider(
            id = "mistral",
            displayName = "Mistral",
            apiKeyPrompt = "Mistral API key",
            definitions = MistralAIModels,
        ),
    )

    fun byId(providerId: String): ProviderDescriptor? = all.firstOrNull { it.id == providerId }

    private fun provider(
        id: String,
        displayName: String,
        apiKeyPrompt: String,
        definitions: LLModelDefinitions,
    ): ProviderDescriptor = ProviderDescriptor(
        id = id,
        displayName = displayName,
        apiKeyPrompt = apiKeyPrompt,
        models = definitions.models.map { model ->
            ModelDescriptor(id = model.id, displayName = model.id, model = model)
        },
    )
}
