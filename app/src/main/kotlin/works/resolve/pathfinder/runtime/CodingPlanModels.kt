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
        ModelDescriptor(providerId = "zai", id = "glm-4.7", displayName = "GLM-4.7", model = model("glm-4.7", 204_800)),
        ModelDescriptor(providerId = "zai", id = "glm-5-turbo", displayName = "GLM-5-Turbo", model = model("glm-5-turbo", 200_000)),
        ModelDescriptor(providerId = "zai", id = "glm-5.2", displayName = "GLM-5.2", model = model("glm-5.2", 1_000_000)),
        ModelDescriptor(providerId = "zai", id = "glm-5.2-highspeed", displayName = "GLM-5.2 Highspeed", model = model("glm-5.2-highspeed", 1_000_000)),
        ModelDescriptor(providerId = "zai", id = "glm-5.3", displayName = "GLM-5.3", model = model("glm-5.3", 1_000_000)),
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
        ModelDescriptor(providerId = "kimi", id = "k3", displayName = "Kimi K3", model = model("k3", 1_048_576L, 131_072L)),
        ModelDescriptor(providerId = "kimi", id = "k3-256k", displayName = "Kimi K3-256K", model = model("k3-256k", 262_144L, 131_072L)),
        ModelDescriptor(providerId = "kimi", id = "kimi-for-coding", displayName = "Kimi K2.7 Code", model = model("kimi-for-coding", 262_144L, 32_768L)),
        ModelDescriptor(
            providerId = "kimi",
            id = "kimi-for-coding-highspeed",
            displayName = "Kimi For Coding HighSpeed",
            model = model("kimi-for-coding-highspeed", 262_144L, 32_768L),
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
            providerId = "openai-codex",
            id = "gpt-5.3-codex-spark",
            displayName = "GPT-5.3 Codex Spark",
            model = model("gpt-5.3-codex-spark", 128_000, CAPABILITIES),
        ),
        ModelDescriptor(providerId = "openai-codex", id = "gpt-5.4", displayName = "GPT-5.4", model = model("gpt-5.4", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor(providerId = "openai-codex", id = "gpt-5.4-mini", displayName = "GPT-5.4 mini", model = model("gpt-5.4-mini", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor(providerId = "openai-codex", id = "gpt-5.5", displayName = "GPT-5.5", model = model("gpt-5.5", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor(providerId = "openai-codex", id = "gpt-5.6-luna", displayName = "GPT-5.6 Luna", model = model("gpt-5.6-luna", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor(providerId = "openai-codex", id = "gpt-5.6-sol", displayName = "GPT-5.6 Sol", model = model("gpt-5.6-sol", 272_000, IMAGE_CAPABILITIES)),
        ModelDescriptor(providerId = "openai-codex", id = "gpt-5.6-terra", displayName = "GPT-5.6 Terra", model = model("gpt-5.6-terra", 272_000, IMAGE_CAPABILITIES)),
    )
}

/** All ChatGPT-backend models share pi's catalog output cap of 128000 tokens. */
private const val CODEX_MAX_OUTPUT_TOKENS = 128_000L
