package works.resolve.pathfinder.ai.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Model metadata and adapter compatibility flags ported from pi's Model type.
 * Compatibility is grouped by protocol family so each retained provider uses
 * the same generated model shape as its upstream adapter.
 */
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
    /** Anthropic Messages API compatibility flags (pi's AnthropicMessagesCompat). */
    val anthropicCompat: AnthropicMessagesCompat = AnthropicMessagesCompat(),
    /** OpenAI Responses-family compatibility flags; null for non-Responses models. */
    val responsesCompat: OpenAiResponsesCompat? = null,
    /** Per-model HTTP headers (e.g. github-copilot, nvidia). */
    val headers: Map<String, String> = emptyMap(),
)

/** Per-million-token reference rates. */
data class ModelCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0,
)

/** Session affinity header format for OpenAI Responses providers, pi's compat flag. */
/** Pi's SessionAffinityFormat: "openai" | "openai-nosession" | "openrouter". */
enum class SessionAffinityFormat { OPENAI, OPENAI_NOSESSION, OPENROUTER }

/**
 * OpenAI Responses API-family compatibility flags, ported from pi's
 * OpenAIResponsesCompat. Defaults mirror pi's getCompat() resolution.
 *
 * Divergence from pi: supportsOpenAIGrammarTools is not ported — Pathfinder has
 * no grammar constrained-sampling support (Tool carries no constrainedSampling
 * config), so grammar ("custom") tools are never emitted or replayed.
 */
data class OpenAiResponsesCompat(
    val supportsDeveloperRole: Boolean = true,
    /** null means auto-detect from provider/baseUrl (pi's detectSessionAffinityFormat). */
    val sessionAffinityFormat: SessionAffinityFormat? = null,
    val supportsLongCacheRetention: Boolean = true,
    val supportsStrictMode: Boolean = false,
    val supportsAdditionalTools: Boolean = false,
    val supportsToolSearch: Boolean = false,
    val supportsExplicitPromptCacheMode: Boolean = false,
)

/** How the provider expects the max output token limit to be spelled. */
enum class MaxTokensField { MAX_COMPLETION_TOKENS, MAX_TOKENS }

enum class ThinkingFormat { OPENAI, ZAI, QWEN, DEEPSEEK, BASETEN, OPENROUTER, ANT_LING, TOGETHER }

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
)

/**
 * A chat-template kwarg value, mirroring pi's ChatTemplateKwargValue: either a
 * plain JSON scalar or a `$var` reference resolved at request time.
 */
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
 * Computes reference cost in USD from token usage and the model's per-million
 * rates. Mirrors pi's calculateCost (tiers and 1h cache writes omitted).
 */
fun calculateCost(model: Model, usage: Usage): Cost {
    val cost = Cost(
        input = (model.cost.input / 1_000_000.0) * usage.input,
        output = (model.cost.output / 1_000_000.0) * usage.output,
        cacheRead = (model.cost.cacheRead / 1_000_000.0) * usage.cacheRead,
        cacheWrite = (model.cost.cacheWrite / 1_000_000.0) * usage.cacheWrite,
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
 * first, then down. Mirrors pi's clampThinkingLevel.
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

/**
 * Anthropic Messages API compatibility flags, ported from pi's
 * AnthropicMessagesCompat (packages/ai/src/types.ts). Only the flags the
 * anthropic-messages adapter consumes are modeled; defaults match pi's
 * getAnthropicCompat. `forceAdaptiveThinking` stays nullable because pi
 * distinguishes "unset" from `false` there.
 */
data class AnthropicMessagesCompat(
    val supportsEagerToolInputStreaming: Boolean = true,
    val supportsLongCacheRetention: Boolean = true,
    val sendSessionAffinityHeaders: Boolean = false,
    val supportsCacheControlOnTools: Boolean = true,
    val supportsTemperature: Boolean = true,
    val allowEmptySignature: Boolean = false,
    val supportsStrictTools: Boolean = false,
    /** pi's compat.forceAdaptiveThinking: null = unset, true/false explicit. */
    val forceAdaptiveThinking: Boolean? = null,
)

/** Resolved Anthropic compat flags with pi's defaults, pi's getAnthropicCompat. */
fun anthropicCompatOf(model: Model): AnthropicMessagesCompat = model.anthropicCompat
