package com.aletheia.ai.providers

import com.aletheia.ai.core.InputModality
import com.aletheia.ai.core.MaxTokensField
import com.aletheia.ai.core.Model
import com.aletheia.ai.core.ModelCost
import com.aletheia.ai.core.ModelThinkingLevel
import com.aletheia.ai.core.OpenAiCompletionsCompat
import com.aletheia.ai.core.ThinkingFormat
import com.aletheia.ai.core.ThinkingLevelMap

/**
 * Global Z.AI Coding Plan model catalog, ported from the current
 * models.dev `zai-coding-plan` source as processed by pi's
 * scripts/generate-models.ts `processZaiModels` (provider "zai",
 * baseUrl https://api.z.ai/api/coding/paas/v4).
 *
 * Reference costs come from the pay-as-you-go `zai` source where present
 * there (coding-plan requests themselves are covered by the subscription).
 * glm-5.3 and glm-5.2-highspeed have no pay-as-you-go reference entry, so
 * their reference cost is zero, matching pi's generator.
 *
 * All models: reasoning-capable, text-only input, tool calls enabled,
 * `tool_stream: true` (the unsupported set only contains glm-4.5* models).
 */
object ZaiModels {

    const val PROVIDER_ID = "zai"
    const val BASE_URL = "https://api.z.ai/api/coding/paas/v4"

    // Effort [low, high, max]; processZaiModels emits every level explicitly,
    // mapping off/unsupported levels to null.
    private val GLM_5_3_MAP = ThinkingLevelMap.of(
        ModelThinkingLevel.OFF to null,
        ModelThinkingLevel.MINIMAL to null,
        ModelThinkingLevel.LOW to "low",
        ModelThinkingLevel.MEDIUM to null,
        ModelThinkingLevel.HIGH to "high",
        ModelThinkingLevel.XHIGH to null,
        ModelThinkingLevel.MAX to "max",
    )

    // Effort [high, max]; processZaiModels maps off -> "none" for glm-5.2*.
    private val GLM_5_2_MAP = ThinkingLevelMap.of(
        ModelThinkingLevel.OFF to "none",
        ModelThinkingLevel.MINIMAL to null,
        ModelThinkingLevel.LOW to null,
        ModelThinkingLevel.MEDIUM to null,
        ModelThinkingLevel.HIGH to "high",
        ModelThinkingLevel.XHIGH to null,
        ModelThinkingLevel.MAX to "max",
    )

    private fun catalogModel(
        id: String,
        name: String,
        contextWindow: Int,
        maxTokens: Int,
        thinkingLevelMap: ThinkingLevelMap?,
        cost: ModelCost,
    ): Model = Model(
        id = id,
        name = name,
        api = "openai-completions",
        provider = PROVIDER_ID,
        baseUrl = BASE_URL,
        reasoning = true,
        thinkingLevelMap = thinkingLevelMap,
        input = listOf(InputModality.TEXT),
        cost = cost,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        compat = OpenAiCompletionsCompat(
            supportsStore = false,
            supportsDeveloperRole = false,
            supportsReasoningEffort = thinkingLevelMap != null,
            supportsUsageInStreaming = true,
            supportsFinishReason = true,
            maxTokensField = MaxTokensField.MAX_TOKENS,
            thinkingFormat = ThinkingFormat.ZAI,
            zaiToolStream = true,
        ),
    )

    val GLM_4_7 = catalogModel(
        id = "glm-4.7",
        name = "GLM-4.7",
        contextWindow = 204_800,
        maxTokens = 131_072,
        thinkingLevelMap = null, // toggle reasoning only
        cost = ModelCost(input = 0.6, output = 2.2, cacheRead = 0.11, cacheWrite = 0.0),
    )

    val GLM_5_TURBO = catalogModel(
        id = "glm-5-turbo",
        name = "GLM-5-Turbo",
        contextWindow = 200_000,
        maxTokens = 131_072,
        thinkingLevelMap = null, // toggle reasoning only
        cost = ModelCost(input = 1.2, output = 4.0, cacheRead = 0.24, cacheWrite = 0.0),
    )

    val GLM_5_3 = catalogModel(
        id = "glm-5.3",
        name = "GLM-5.3",
        contextWindow = 1_000_000,
        maxTokens = 131_072,
        thinkingLevelMap = GLM_5_3_MAP,
        cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
    )

    val GLM_5_2 = catalogModel(
        id = "glm-5.2",
        name = "GLM-5.2",
        contextWindow = 1_000_000,
        maxTokens = 131_072,
        thinkingLevelMap = GLM_5_2_MAP,
        cost = ModelCost(input = 1.4, output = 4.4, cacheRead = 0.26, cacheWrite = 0.0),
    )

    val GLM_5_2_HIGHSPEED = catalogModel(
        id = "glm-5.2-highspeed",
        name = "GLM-5.2 Highspeed",
        contextWindow = 1_000_000,
        maxTokens = 131_072,
        thinkingLevelMap = GLM_5_2_MAP,
        cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
    )

    val ALL: List<Model> = listOf(GLM_4_7, GLM_5_TURBO, GLM_5_3, GLM_5_2, GLM_5_2_HIGHSPEED)
}
