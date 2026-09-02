package works.resolve.pathfinder.ai.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class Model(
    val id: String,
    val name: String,
    val api: String,
    val provider: String,
    val baseUrl: String,
    val reasoning: Boolean = false,
    val thinkingLevelMap: ThinkingLevelMap? = null,
    val input: List<InputModality> = listOf(InputModality.TEXT),
    val cost: ModelCost = ModelCost(),
    val contextWindow: Int = 4096,
    val maxTokens: Int = 4096,
    val compat: OpenAiCompletionsCompat = OpenAiCompletionsCompat(),
    val anthropicCompat: AnthropicMessagesCompat = AnthropicMessagesCompat(),
    val responsesCompat: OpenAiResponsesCompat? = null,
    val headers: Map<String, String> = emptyMap(),
    /**
     * Default sampling parameters for this model, merged under per-request
     * [SimpleStreamOptions.samplingParams] keys by [mergeSamplingParams] and
     * applied only by OpenAI-compatible adapters. Not carried by the
     * generated model catalog.
     */
    val samplingParams: Map<String, JsonElement>? = null,
)

/** Per-million-token reference rates. */
interface ModelCostRates {
    val input: Double
    val output: Double
    val cacheRead: Double
    val cacheWrite: Double
}

data class ModelCost(
    override val input: Double = 0.0,
    override val output: Double = 0.0,
    override val cacheRead: Double = 0.0,
    override val cacheWrite: Double = 0.0,
    val tiers: List<ModelCostTier> = emptyList(),
) : ModelCostRates

/**
 * A rate set applied to the full request when total input usage exceeds
 * [inputTokensAbove].
 */
data class ModelCostTier(
    override val input: Double,
    override val output: Double,
    override val cacheRead: Double,
    override val cacheWrite: Double,
    val inputTokensAbove: Int,
) : ModelCostRates

enum class SessionAffinityFormat { OPENAI, OPENAI_NOSESSION, OPENROUTER }

data class OpenAiResponsesCompat(
    val supportsDeveloperRole: Boolean = true,
    /** null means auto-detect from provider/baseUrl. */
    val sessionAffinityFormat: SessionAffinityFormat? = null,
    val supportsLongCacheRetention: Boolean = true,
    val supportsStrictMode: Boolean = false,
    val supportsOpenAIGrammarTools: Boolean = false,
    val supportsAdditionalTools: Boolean = false,
    val supportsToolSearch: Boolean = false,
    val supportsExplicitPromptCacheMode: Boolean = false,
    /** Whether to send the `max_output_tokens` param. */
    val supportsMaxOutputTokens: Boolean = true,
)

/** How the provider expects the max output token limit to be spelled. */
enum class MaxTokensField { MAX_COMPLETION_TOKENS, MAX_TOKENS }

enum class ThinkingFormat { OPENAI, ZAI, QWEN, DEEPSEEK, BASETEN, OPENROUTER, ANT_LING, TOGETHER }

/** Only "anthropic" exists upstream; the single-member enum keeps pi's value domain. */
enum class CacheControlFormat { ANTHROPIC }

data class OpenAiCompletionsCompat(
    val supportsStore: Boolean = true,
    val supportsDeveloperRole: Boolean = true,
    val supportsReasoningEffort: Boolean = true,
    val supportsUsageInStreaming: Boolean = true,
    val supportsFinishReason: Boolean = true,
    val maxTokensField: MaxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
    val requiresToolResultName: Boolean = false,
    val requiresThinkingAsText: Boolean = false,
    val thinkingFormat: ThinkingFormat = ThinkingFormat.OPENAI,
    /** ZAI tool-call streaming flag (`tool_stream: true`). */
    val zaiToolStream: Boolean = false,
    /** Baseten chat_template_args: template kwargs before $var resolution. */
    val chatTemplateArgs: Map<String, ChatTemplateKwargValue> = emptyMap(),
    /** Whether to send session-affinity data from `options.sessionId`. */
    val sendSessionAffinityHeaders: Boolean = false,
    /** null means auto-detect (openrouter vs openai). */
    val sessionAffinityFormat: SessionAffinityFormat? = null,
    /** Whether `prompt_cache_retention: "24h"` is supported. */
    val supportsLongCacheRetention: Boolean = true,
    val supportsStrictMode: Boolean = true,
    val supportsOpenAIGrammarTools: Boolean = false,
    /** "anthropic" enables Anthropic-style cache_control emission; null disables it. */
    val cacheControlFormat: CacheControlFormat? = null,
    /**
     * For DeepSeek-style reasoning models, replayed assistant messages carry
     * `reasoning_content: ""` when no reasoning was set; some providers reject
     * assistant messages without it.
     */
    val requiresReasoningContentOnAssistantMessages: Boolean = false,
    /**
     * Provider-specific deferred tool serialization: "kimi" emits deferred
     * tools as a bare `tools` system message after tool results instead of the
     * standard `tools` param entry.
     */
    val deferredToolsMode: DeferredToolsMode? = null,
)

/** Only "kimi" exists upstream. */
enum class DeferredToolsMode { KIMI }

/** A chat-template kwarg value: a plain JSON scalar or a `$var` reference resolved at request time. */
sealed interface ChatTemplateKwargValue {
    data class Scalar(val value: JsonElement) : ChatTemplateKwargValue

    /** `$var` reference. [varName] is e.g. "thinking.enabled". */
    data class Ref(
        val varName: String,
        val omitWhenOff: Boolean = false,
    ) : ChatTemplateKwargValue

    companion object {
        fun of(value: String): ChatTemplateKwargValue = Scalar(JsonPrimitive(value))
        fun of(value: Double): ChatTemplateKwargValue = Scalar(JsonPrimitive(value))
        fun of(value: Boolean): ChatTemplateKwargValue = Scalar(JsonPrimitive(value))
        fun ofNull(): ChatTemplateKwargValue = Scalar(JsonNull)
    }
}

/**
 * Reference cost in USD from token usage and per-million rates. The tier with
 * the highest `inputTokensAbove` threshold that the request's total input
 * usage strictly exceeds applies its rates to the full request.
 */
fun calculateCost(model: Model, usage: Usage): Cost {
    val inputTokens = usage.input + usage.cacheRead + usage.cacheWrite
    var rates: ModelCostRates = model.cost
    var matchedThreshold = -1
    for (tier in model.cost.tiers) {
        if (inputTokens > tier.inputTokensAbove && tier.inputTokensAbove > matchedThreshold) {
            rates = tier
            matchedThreshold = tier.inputTokensAbove
        }
    }

    // Anthropic charges 2x base input for 1h cache writes.
    val longWrite = usage.cacheWrite1h
    val shortWrite = usage.cacheWrite - longWrite
    val cost = Cost(
        input = (rates.input / 1_000_000.0) * usage.input,
        output = (rates.output / 1_000_000.0) * usage.output,
        cacheRead = (rates.cacheRead / 1_000_000.0) * usage.cacheRead,
        cacheWrite = (rates.cacheWrite * shortWrite + rates.input * 2 * longWrite) / 1_000_000.0,
    )
    return cost.copy(total = cost.input + cost.output + cost.cacheRead + cost.cacheWrite)
}

private val EXTENDED_THINKING_LEVELS = listOf(
    ModelThinkingLevel.OFF,
    ModelThinkingLevel.MINIMAL,
    ModelThinkingLevel.LOW,
    ModelThinkingLevel.MEDIUM,
    ModelThinkingLevel.HIGH,
    ModelThinkingLevel.XHIGH,
    ModelThinkingLevel.MAX,
)

fun getSupportedThinkingLevels(model: Model): List<ModelThinkingLevel> {
    if (!model.reasoning) return listOf(ModelThinkingLevel.OFF)
    val map = model.thinkingLevelMap
    return EXTENDED_THINKING_LEVELS.filter { level ->
        val mapped = map?.forLevel(level)
        val specified = map?.isSpecified(level) == true
        // Explicit null means unsupported.
        if (specified && mapped == null) return@filter false
        // XHIGH/MAX require an explicit non-null mapping; OFF..HIGH default to supported.
        if (level == ModelThinkingLevel.XHIGH || level == ModelThinkingLevel.MAX) {
            return@filter specified && mapped != null
        }
        true
    }
}

/**
 * Clamps a requested thinking level to one the model supports, rounding up
 * first, then down.
 */
fun clampThinkingLevel(model: Model, level: ModelThinkingLevel): ModelThinkingLevel {
    val available = getSupportedThinkingLevels(model)
    if (available.contains(level)) return level

    val requestedIndex = EXTENDED_THINKING_LEVELS.indexOf(level)
    if (requestedIndex == -1) return available.firstOrNull() ?: ModelThinkingLevel.OFF

    for (i in requestedIndex until EXTENDED_THINKING_LEVELS.size) {
        if (available.contains(EXTENDED_THINKING_LEVELS[i])) return EXTENDED_THINKING_LEVELS[i]
    }
    for (i in requestedIndex - 1 downTo 0) {
        if (available.contains(EXTENDED_THINKING_LEVELS[i])) return EXTENDED_THINKING_LEVELS[i]
    }
    return available.firstOrNull() ?: ModelThinkingLevel.OFF
}

data class AnthropicMessagesCompat(
    val supportsEagerToolInputStreaming: Boolean = true,
    val supportsLongCacheRetention: Boolean = true,
    val sendSessionAffinityHeaders: Boolean = false,
    val supportsCacheControlOnTools: Boolean = true,
    val supportsTemperature: Boolean = true,
    val allowEmptySignature: Boolean = false,
    val supportsStrictTools: Boolean = false,
    /** null = unset, true/false explicit. */
    val forceAdaptiveThinking: Boolean? = null,
    /**
     * Models Anthropic accepts in `fallbacks` for server-side refusal fallback,
     * with local pricing metadata for returned fallback responses. When empty,
     * callers must omit `fallbacks`; Anthropic rejects the field for models
     * with no permitted fallback targets.
     */
    val allowedFallbackModels: List<AnthropicAllowedFallbackModel> = emptyList(),
)

fun anthropicCompatOf(model: Model): AnthropicMessagesCompat = model.anthropicCompat

/**
 * One server-side fallback target. Only [model] goes on the wire in
 * `fallbacks`; [provider] and [cost] are local metadata for usage attribution
 * when the server serves the fallback model.
 */
data class AnthropicAllowedFallbackModel(
    val provider: String,
    val model: String,
    val cost: ModelCost,
)
