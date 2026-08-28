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
    /**
     * pi's Model.samplingParams (packages/ai/src/types.ts:837): default
     * sampling parameters for this model, merged under per-request
     * [SimpleStreamOptions.samplingParams] keys by [mergeSamplingParams] and
     * applied only by OpenAI-compatible adapters. The generated model catalog
     * does not carry this field, so it stays null for catalog models.
     */
    val samplingParams: Map<String, JsonElement>? = null,
)

/**
 * Per-million-token reference rates (pi's ModelCostRates, types.ts:810-815).
 */
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
    /** Request-wide pricing tiers (pi's ModelCost.tiers); currently none ship in pi's data. */
    val tiers: List<ModelCostTier> = emptyList(),
) : ModelCostRates

/**
 * pi's ModelCostTier (types.ts:816-819): a rate set applied to the full request
 * when total input usage exceeds [inputTokensAbove].
 */
data class ModelCostTier(
    override val input: Double,
    override val output: Double,
    override val cacheRead: Double,
    override val cacheWrite: Double,
    val inputTokensAbove: Int,
) : ModelCostRates

/** Pi's SessionAffinityFormat: "openai" | "openai-nosession" | "openrouter". */
enum class SessionAffinityFormat { OPENAI, OPENAI_NOSESSION, OPENROUTER }

/**
 * OpenAI Responses API-family compatibility flags, ported from pi's
 * OpenAIResponsesCompat. Defaults mirror pi's getCompat()
 * (openai-responses.ts:68-77).
 *
 * supportsOpenAIGrammarTools is plumbed only: adapters do not yet consume it,
 * because Tool carries no constrainedSampling config, so grammar ("custom")
 * tools are never emitted or replayed. Unfinished parity, not a descope:
 * consume it alongside Tool.constrainedSampling and pi's
 * constrained-sampling.ts when agent tool support lands.
 */
data class OpenAiResponsesCompat(
    val supportsDeveloperRole: Boolean = true,
    /** null means auto-detect from provider/baseUrl (pi's detectSessionAffinityFormat). */
    val sessionAffinityFormat: SessionAffinityFormat? = null,
    val supportsLongCacheRetention: Boolean = true,
    val supportsStrictMode: Boolean = false,
    /**
     * pi's supportsOpenAIGrammarTools (types.ts:638; openai-responses.ts:74
     * `model.compat?.supportsOpenAIGrammarTools ?? false`): default false; the
     * generated model catalog enables it for capable models.
     */
    val supportsOpenAIGrammarTools: Boolean = false,
    val supportsAdditionalTools: Boolean = false,
    val supportsToolSearch: Boolean = false,
    val supportsExplicitPromptCacheMode: Boolean = false,
)

/** How the provider expects the max output token limit to be spelled. */
enum class MaxTokensField { MAX_COMPLETION_TOKENS, MAX_TOKENS }

/** Pi's ThinkingFormat. */
enum class ThinkingFormat { OPENAI, ZAI, QWEN, DEEPSEEK, BASETEN, OPENROUTER, ANT_LING, TOGETHER }

/**
 * Pi's OpenAICompletionsCompat.cacheControlFormat union ("anthropic" |
 * undefined, openai-completions.ts). Only "anthropic" exists upstream; the
 * single-member enum keeps pi's value domain without widening it.
 */
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
    /** Whether to send session-affinity data from `options.sessionId`; pi's detected default is false. */
    val sendSessionAffinityHeaders: Boolean = false,
    /** null means auto-detect (openrouter vs openai), pi's detectCompat. */
    val sessionAffinityFormat: SessionAffinityFormat? = null,
    /** Whether `prompt_cache_retention: "24h"` is supported; pi's detected default is true. */
    val supportsLongCacheRetention: Boolean = true,
    /**
     * pi's supportsStrictMode (openai-completions.ts:1653 detected via
     * `!isMoonshot && !isTogether && !isCloudflareAiGateway && !isNvidia`, and
     * :1699 `model.compat.supportsStrictMode ?? detected.supportsStrictMode`):
     * the detected default is true; the catalog marks it false for moonshotai,
     * together, nvidia, and cloudflare-ai-gateway.
     */
    val supportsStrictMode: Boolean = true,
    /**
     * pi's supportsOpenAIGrammarTools (types.ts:612; openai-completions.ts:1654
     * detected default false, :1700 `model.compat.supportsOpenAIGrammarTools ??
     * detected.supportsOpenAIGrammarTools`): the generated model catalog
     * enables it for capable models.
     */
    val supportsOpenAIGrammarTools: Boolean = false,
    /**
     * Pi's cacheControlFormat (openai-completions.ts:1633 detectCompat,
     * :1700 getCompat): "anthropic" when provider is openrouter and the model
     * id starts with "anthropic/", overridable per model; null (undefined)
     * disables Anthropic-style cache_control emission.
     */
    val cacheControlFormat: CacheControlFormat? = null,
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
 * rates. Mirrors pi's calculateCost (packages/ai/src/models.ts:879-899): tier
 * selection applies the highest `inputTokensAbove` threshold the request's
 * total input usage strictly exceeds to the full request, and Anthropic's 1h
 * cache writes are priced at 2x base input while the remainder uses the
 * cacheWrite rate. With no tiers and no 1h split the math reduces to the
 * plain per-component products.
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
