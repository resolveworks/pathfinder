package works.resolve.pathfinder.runtime

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * The Kimi LLM provider as a Koog [LLMProvider] instance. Koog has no
 * Moonshot/Kimi constant (`prompt/prompt-llm/.../LLMProvider.kt`), so this
 * extends Koog's open provider type with product values — the registry point
 * Koog defines for exactly this — rather than introducing a parallel
 * provider contract. Keyed on in [KoogClients] only.
 */
val KimiProvider: LLMProvider = LLMProvider("kimi", "Kimi")

/**
 * Models of the Z.AI coding plan (`https://api.z.ai/api/coding/paas/v4`).
 *
 * Koog ships no ZhipuAI client module, so these models are declared by hand
 * — as Koog [LLModel]s, not a parallel catalog type — and executed by the
 * stock [OpenAILLMClient][ai.koog.prompt.executor.clients.openai.OpenAILLMClient]
 * against the coding endpoint (see [KoogClients]). The behavioral reference
 * is pi's provider definition
 * `packages/ai/src/providers/zai.ts` + `providers/data/zai.json`:
 * OpenAI chat-completions protocol, `reasoning_content` thinking (mapped to
 * `StreamFrame.ReasoningDelta` by Koog's OpenAI client), `max_tokens` —
 * moot for Pathfinder, which sends no params.
 *
 * Every model carries [LLMCapability.OpenAIEndpoint.Completions]: with no
 * params, that capability is what routes the stock OpenAI client to its
 * chat-completions branch (`OpenAILLMClient.determineParams`).
 */
internal object ZaiModels {

    private val CAPABILITIES = listOf(
        LLMCapability.Completion,
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
        LLMCapability.MultipleChoices,
        LLMCapability.Thinking,
        LLMCapability.OpenAIEndpoint.Completions,
    )

    private fun model(id: String, contextLength: Long): LLModel = LLModel(
        provider = LLMProvider.ZhipuAI,
        id = id,
        capabilities = CAPABILITIES,
        contextLength = contextLength,
        maxOutputTokens = ZAI_MAX_OUTPUT_TOKENS,
    )

    /** Display names and context windows follow pi's `providers/data/zai.json` catalog. */
    val descriptors: List<ModelDescriptor> = listOf(
        ModelDescriptor("glm-4.7", "GLM-4.7", model("glm-4.7", 204_800)),
        ModelDescriptor("glm-5-turbo", "GLM-5-Turbo", model("glm-5-turbo", 200_000)),
        ModelDescriptor("glm-5.2", "GLM-5.2", model("glm-5.2", 1_000_000)),
        ModelDescriptor("glm-5.2-highspeed", "GLM-5.2 Highspeed", model("glm-5.2-highspeed", 1_000_000)),
        ModelDescriptor("glm-5.3", "GLM-5.3", model("glm-5.3", 1_000_000)),
    )
}

/** All Z.AI coding models share pi's catalog output cap of 131072 tokens. */
private const val ZAI_MAX_OUTPUT_TOKENS = 131_072L

/**
 * Models of Kimi For Coding (`https://api.kimi.com/coding`).
 *
 * Koog ships no Moonshot/Kimi client module, but the endpoint speaks the
 * Anthropic Messages protocol, so these models are declared by hand — as
 * Koog [LLModel]s tagged [KimiProvider] — and executed by the stock
 * [AnthropicLLMClient][ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient]
 * against the coding endpoint (see [KoogClients]). The behavioral reference
 * is pi's provider definition
 * `packages/ai/src/providers/kimi-coding.ts` + `providers/data/kimi-coding.json`.
 *
 * [versionMap] is required: Koog's Anthropic request builder resolves the
 * request's `model` field through `AnthropicClientSettings.modelVersionsMap`
 * and rejects unmapped models.
 */
internal object KimiModels {

    private val CAPABILITIES = listOf(
        LLMCapability.Completion,
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
        LLMCapability.MultipleChoices,
        LLMCapability.Thinking,
        LLMCapability.Vision.Image,
    )

    private fun model(id: String, contextLength: Long, maxOutputTokens: Long): LLModel = LLModel(
        provider = KimiProvider,
        id = id,
        capabilities = CAPABILITIES,
        contextLength = contextLength,
        maxOutputTokens = maxOutputTokens,
    )

    /** Display names follow pi's `providers/data/kimi-coding.json` catalog. */
    val descriptors: List<ModelDescriptor> = listOf(
        ModelDescriptor("k3", "Kimi K3", model("k3", 1_048_576L, 131_072L)),
        ModelDescriptor("k3-256k", "Kimi K3-256K", model("k3-256k", 262_144L, 131_072L)),
        ModelDescriptor("kimi-for-coding", "Kimi K2.7 Code", model("kimi-for-coding", 262_144L, 32_768L)),
        ModelDescriptor(
            "kimi-for-coding-highspeed",
            "Kimi For Coding HighSpeed",
            model("kimi-for-coding-highspeed", 262_144L, 32_768L),
        ),
    )

    /**
     * Maps each model to the request `model` string. Values mirror the model
     * ids (Anthropic-proper maps to dated version strings; Kimi's endpoint
     * takes the ids verbatim).
     */
    val versionMap: Map<LLModel, String> = descriptors.associate { it.model to it.id }
}

/**
 * Models of the ChatGPT subscription backend
 * (`https://chatgpt.com/backend-api/codex/responses`).
 *
 * Koog's [OpenAIModels] tracks the OpenAI API catalog, which does not carry
 * the subscription-only model line served by the ChatGPT backend (Codex
 * Spark, Luna, Sol, Terra), so these models are declared by hand — as Koog
 * [LLModel]s tagged [LLMProvider.OpenAI] — and executed by the stock OpenAI
 * client that [CodexLLMClients] assembles against the codex Responses
 * endpoint. The behavioral reference is pi's provider definition
 * `packages/ai/src/providers/openai-codex.ts` + `providers/data/openai-codex.json`.
 *
 * Every model carries [LLMCapability.OpenAIEndpoint.Responses]: the ChatGPT
 * backend speaks only the Responses API, and that capability is what routes
 * Koog's OpenAI client to its Responses branch
 * (`OpenAILLMClient.determineParams` plus its `requireCapability` guard).
 * Capabilities otherwise mirror Koog's own codex entries, minus what pi's
 * catalog does not declare for these models (document input, speculation).
 */
internal object CodexModels {

    private val CAPABILITIES = listOf(
        LLMCapability.Completion,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.MultipleChoices,
        LLMCapability.Thinking,
        LLMCapability.OpenAIEndpoint.Responses,
    )

    private val IMAGE_CAPABILITIES = CAPABILITIES + LLMCapability.Vision.Image

    private fun model(id: String, contextLength: Long, capabilities: List<LLMCapability>): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = id,
        capabilities = capabilities,
        contextLength = contextLength,
        maxOutputTokens = CODEX_MAX_OUTPUT_TOKENS,
    )

    /** Display names, context windows, and inputs follow pi's `providers/data/openai-codex.json` catalog. */
    val descriptors: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            "gpt-5.3-codex-spark",
            "GPT-5.3 Codex Spark",
            model("gpt-5.3-codex-spark", 128_000, CAPABILITIES),
        ),
        ModelDescriptor("gpt-5.4", "GPT-5.4", model("gpt-5.4", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor("gpt-5.4-mini", "GPT-5.4 mini", model("gpt-5.4-mini", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor("gpt-5.5", "GPT-5.5", model("gpt-5.5", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor("gpt-5.6-luna", "GPT-5.6 Luna", model("gpt-5.6-luna", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor("gpt-5.6-sol", "GPT-5.6 Sol", model("gpt-5.6-sol", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor("gpt-5.6-terra", "GPT-5.6 Terra", model("gpt-5.6-terra", 272_000, IMAGE_CAPABILITIES)),
    )
}

/** All ChatGPT-backend models share pi's catalog output cap of 128000 tokens. */
private const val CODEX_MAX_OUTPUT_TOKENS = 128_000L
