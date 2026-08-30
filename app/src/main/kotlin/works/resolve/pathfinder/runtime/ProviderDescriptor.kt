package works.resolve.pathfinder.runtime

import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.dashscope.DashscopeModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
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
 * sign-in flow driven by the UI and the runtime.
 */
sealed interface ProviderAuthKind {
    /** Labels the provider's API-key credential form field. */
    data class ApiKey(val prompt: String) : ProviderAuthKind

    /** ChatGPT subscription sign-in: in-app browser OAuth or device code. */
    data object ChatGptSignIn : ProviderAuthKind
}

/**
 * One provider the app can be configured with: its stable id, display name,
 * how it authenticates, and its selectable models.
 */
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    /** Credential UX and runtime dispatch for this provider. */
    val authKind: ProviderAuthKind,
    val models: List<ModelDescriptor>,
) {
    fun model(modelId: String): ModelDescriptor? = models.firstOrNull { it.id == modelId }
}

/**
 * The app-owned provider surface: presentation metadata plus Koog model
 * objects. Model lists are enumerated from Koog's own `LLModelDefinitions`
 * singletons (`ai.koog.prompt.executor.clients.<provider>`) rather than
 * hand-copied, so the catalog cannot drift from the Koog runtime that will
 * execute against these models (chunk 2 maps each provider's Koog
 * [ai.koog.prompt.llm.LLMProvider] to its Koog executor client);
 * subscription-backed coding-plan providers whose model line Koog does not
 * track hand-declare catalogs instead (see `runtime/CodingPlanModels.kt`).
 * Only models supporting [LLMCapability.Completion] are offered — the
 * runtime executes streaming chat completions, and Koog's definition
 * families also carry embedding/moderation models the product cannot run.
 * This file is permanent app architecture.
 */
object ProviderDescriptors {

    val all: List<ProviderDescriptor> = listOf(
        provider(
            id = "anthropic",
            displayName = "Anthropic",
            authKind = ProviderAuthKind.ApiKey("Anthropic API key"),
            definitions = AnthropicModels,
        ),
        provider(
            id = "openai",
            displayName = "OpenAI",
            authKind = ProviderAuthKind.ApiKey("OpenAI API key"),
            definitions = OpenAIModels,
        ),
        provider(
            id = "google",
            displayName = "Google",
            authKind = ProviderAuthKind.ApiKey("Google AI Studio API key"),
            definitions = GoogleModels,
        ),
        provider(
            id = "openrouter",
            displayName = "OpenRouter",
            authKind = ProviderAuthKind.ApiKey("OpenRouter API key"),
            definitions = OpenRouterModels,
        ),
        provider(
            id = "mistral",
            displayName = "Mistral",
            authKind = ProviderAuthKind.ApiKey("Mistral API key"),
            definitions = MistralAIModels,
        ),
        provider(
            id = "deepseek",
            displayName = "DeepSeek",
            authKind = ProviderAuthKind.ApiKey("DeepSeek API key"),
            definitions = DeepSeekModels,
        ),
        provider(
            id = "dashscope",
            displayName = "DashScope",
            authKind = ProviderAuthKind.ApiKey("DashScope API key"),
            definitions = DashscopeModels,
        ),
        // Coding-plan providers without a dedicated Koog client module:
        // OpenAI-/Anthropic-protocol endpoints executed by Koog's stock
        // clients against their coding base URLs, with hand-declared Koog
        // model catalogs (see `runtime/CodingPlanModels.kt`).
        provider(
            id = "zai",
            displayName = "Z.AI",
            authKind = ProviderAuthKind.ApiKey("Z.AI API key"),
            models = ZaiModels.descriptors,
        ),
        provider(
            id = "kimi",
            displayName = "Kimi",
            authKind = ProviderAuthKind.ApiKey("Kimi API key"),
            models = KimiModels.descriptors,
        ),
        // ChatGPT-subscription backend: same Responses API as OpenAI, but at
        // chatgpt.com with OAuth tokens (see `runtime/CodexLLMClients.kt`).
        // Koog's OpenAIModels tracks the API catalog, which lacks the
        // subscription-only model line (Codex Spark, Luna, Sol, Terra), so
        // the catalog is hand-declared (see `runtime/CodingPlanModels.kt`).
        provider(
            id = "openai-codex",
            displayName = "OpenAI Codex",
            authKind = ProviderAuthKind.ChatGptSignIn,
            models = CodexModels.descriptors,
        ),
    )

    fun byId(providerId: String): ProviderDescriptor? = all.firstOrNull { it.id == providerId }

    private fun provider(
        id: String,
        displayName: String,
        authKind: ProviderAuthKind,
        definitions: LLModelDefinitions,
    ): ProviderDescriptor = provider(
        id,
        displayName,
        authKind,
        definitions.models
            .filter { it.supports(LLMCapability.Completion) }
            .map { model -> ModelDescriptor(id = model.id, displayName = model.id, model = model) },
    )

    private fun provider(
        id: String,
        displayName: String,
        authKind: ProviderAuthKind,
        models: List<ModelDescriptor>,
    ): ProviderDescriptor = ProviderDescriptor(
        id = id,
        displayName = displayName,
        authKind = authKind,
        models = models,
    )
}
