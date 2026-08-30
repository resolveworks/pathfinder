package works.resolve.pathfinder.ai.providers

import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.llm.LLMCapability
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
 * How a provider authenticates: an API-key form, or the ChatGPT OAuth
 * sign-in flow.
 */
sealed interface ProviderAuthKind {
    /** [prompt] labels the provider's API-key field. */
    data class ApiKey(val prompt: String) : ProviderAuthKind

    /** ChatGPT subscription sign-in (device-code OAuth, handled by the UI + runtime). */
    data object ChatGptSignIn : ProviderAuthKind
}

/**
 * One provider the app can be configured with: its stable id, display name,
 * how it authenticates, and its selectable models.
 */
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    /** How the user authenticates with this provider. */
    val authKind: ProviderAuthKind,
    val models: List<ModelDescriptor>,
) {
    fun model(modelId: String): ModelDescriptor? = models.firstOrNull { it.id == modelId }
}

/**
 * The app-owned provider surface: presentation metadata plus Koog model
 * objects. Models are enumerated from Koog's own model-definition objects
 * (`ai.koog.prompt.executor.clients.<provider>`) rather than hand-copied, so
 * the catalog cannot drift from the Koog runtime that will execute against
 * these models — the five API-key providers from their `LLModelDefinitions`
 * singletons, and the ChatGPT-backed Codex provider from a pinned subset of
 * [OpenAIModels.Chat] entries (Koog ships no `LLModelDefinitions` family for
 * the codex-only models). Only models supporting [LLMCapability.Completion]
 * are offered — the runtime executes streaming chat completions, and Koog's
 * definition families also carry embedding/moderation models the product
 * cannot run. This file is permanent app architecture.
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
        provider(
            id = "openai-codex",
            displayName = "OpenAI Codex",
            models = listOf(
                OpenAIModels.Chat.GPT5Codex,
                OpenAIModels.Chat.GPT5_1Codex,
                OpenAIModels.Chat.GPT5_1CodexMax,
                OpenAIModels.Chat.GPT5_2Codex,
                OpenAIModels.Chat.GPT5_3Codex,
            ),
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
        authKind = ProviderAuthKind.ApiKey(apiKeyPrompt),
        models = definitionModels(definitions),
    )

    private fun provider(
        id: String,
        displayName: String,
        models: List<LLModel>,
    ): ProviderDescriptor = ProviderDescriptor(
        id = id,
        displayName = displayName,
        authKind = ProviderAuthKind.ChatGptSignIn,
        models = models
            .filter { it.supports(LLMCapability.Completion) }
            .map { model ->
                ModelDescriptor(id = model.id, displayName = model.id, model = model)
            },
    )

    private fun definitionModels(definitions: LLModelDefinitions): List<ModelDescriptor> =
        definitions.models
            .filter { it.supports(LLMCapability.Completion) }
            .map { model ->
                ModelDescriptor(id = model.id, displayName = model.id, model = model)
            }
}
