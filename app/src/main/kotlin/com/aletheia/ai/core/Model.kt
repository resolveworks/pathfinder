package com.aletheia.ai.core

/**
 * Model metadata and OpenAI Chat Completions compatibility flags, ported from
 * pi's Model type and OpenAICompletionsCompat. Only the flags the ZAI/OpenAI
 * Completions path consumes are modeled; more can be added as providers grow.
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
)

/** Per-million-token reference rates. */
data class ModelCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0,
)

/** How the provider expects the max output token limit to be spelled. */
enum class MaxTokensField { MAX_COMPLETION_TOKENS, MAX_TOKENS }

enum class ThinkingFormat { OPENAI, ZAI }

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
)

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
