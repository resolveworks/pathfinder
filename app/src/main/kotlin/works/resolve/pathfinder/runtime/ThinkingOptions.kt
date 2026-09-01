package works.resolve.pathfinder.runtime

import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams

/**
 * One selectable thinking configuration for a model. The variants carry
 * Koog's own provider-parameter values verbatim — there is no Pathfinder
 * thinking model, no normalization ladder, and no invented token budget:
 * whatever the framework's params types express is exactly what the UI
 * offers and what the request sends.
 */
sealed interface ThinkingOption {

    /** Koog-native name, used verbatim as the UI label and persisted value. */
    val label: String

    /** Provider default: no thinking parameter is sent at all. */
    data object Default : ThinkingOption {
        override val label: String = "default"
    }

    /** Disables thinking, where the provider's Koog params model an off switch. */
    data object Off : ThinkingOption {
        override val label: String = "off"
    }

    /**
     * An OpenAI-protocol reasoning effort
     * ([ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort]),
     * including `none`; used by every provider executed through Koog's
     * OpenAI clients (openai, zai, openai-codex).
     */
    data class Effort(val effort: ReasoningEffort) : ThinkingOption {
        override val label: String = effort.name.lowercase()
    }

    /** A Gemini 3 thinking level. */
    data class GeminiLevel(val level: GoogleThinkingLevel) : ThinkingOption {
        override val label: String = level.name.lowercase()
    }
}

/**
 * The thinking surface of the provider catalog, as a thin wrapper over
 * Koog: which options a model offers (its provider's Koog params values,
 * gated on the model's [LLMCapability.Thinking] capability), how a
 * persisted option is read back, and how an option becomes the provider's
 * Koog [LLMParams]. This dispatch mirrors [KoogClients] — the same
 * per-provider knowledge, applied to params rather than clients.
 *
 * Providers whose Koog params type models no thinking configuration
 * (deepseek, mistral, dashscope, openrouter) offer only
 * [ThinkingOption.Default]: the model's built-in behavior applies and the
 * UI shows no thinking selector.
 */
object ThinkingOptions {

    /**
     * The options for [model] under the provider with Pathfinder id
     * [providerId]. Never empty: [ThinkingOption.Default] is always first.
     * A list of just [ThinkingOption.Default] means "nothing to configure".
     */
    fun forModel(providerId: String, model: LLModel): List<ThinkingOption> {
        if (!model.supports(LLMCapability.Thinking)) return listOf(ThinkingOption.Default)
        return when (providerId) {
            "openai", "zai", "openai-codex" ->
                listOf(ThinkingOption.Default) + ReasoningEffort.entries.map(ThinkingOption::Effort)
            "google" ->
                listOf(ThinkingOption.Default) + GoogleThinkingLevel.entries.map(ThinkingOption::GeminiLevel)
            "anthropic", "kimi" -> listOf(ThinkingOption.Default, ThinkingOption.Off)
            else -> listOf(ThinkingOption.Default)
        }
    }

    /**
     * Reads back a persisted [label] (see [ThinkingOption.label]) for
     * [model]; anything unknown — including a label persisted for a
     * different provider's parameter space — resolves to
     * [ThinkingOption.Default] rather than failing.
     */
    fun parse(providerId: String, model: LLModel, label: String?): ThinkingOption {
        if (label == null) return ThinkingOption.Default
        return forModel(providerId, model).firstOrNull { it.label == label } ?: ThinkingOption.Default
    }

    /**
     * Builds the Koog request params that carry [option] for the provider
     * with Pathfinder id [providerId] (the ChatGPT Codex backend is handled
     * separately by [CodexLLMClients.promptParams], which merges the effort
     * into its Responses params). The option values are passed through
     * as-is; whether a specific endpoint honors a given value is the
     * provider's contract, not Pathfinder's.
     */
    fun params(providerId: String, option: ThinkingOption): LLMParams = when (providerId) {
        "openai", "zai" -> OpenAIChatParams(
            reasoningEffort = (option as? ThinkingOption.Effort)?.effort,
        )
        "google" -> GoogleParams(
            thinkingConfig = (option as? ThinkingOption.GeminiLevel)?.let {
                GoogleThinkingConfig(thinkingLevel = it.level)
            },
        )
        "anthropic", "kimi" -> AnthropicParams(
            thinking = if (option == ThinkingOption.Off) AnthropicThinking.Disabled() else null,
        )
        else -> LLMParams()
    }
}
