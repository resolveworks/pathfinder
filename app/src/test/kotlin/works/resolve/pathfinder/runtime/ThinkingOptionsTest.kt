package works.resolve.pathfinder.runtime

import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The thinking surface is a thin wrapper over Koog: options are Koog's own
 * provider-parameter values, gated on [ai.koog.prompt.llm.LLMCapability.Thinking];
 * params pass those values through verbatim.
 */
class ThinkingOptionsTest {

    private fun model(providerId: String, thinking: Boolean): LLModel {
        val provider = ProviderDescriptors.byId(providerId)!!
        val candidate = provider.models.firstOrNull { it.model.supports(ai.koog.prompt.llm.LLMCapability.Thinking) }
        return (if (thinking) candidate else provider.models.firstOrNull() ?: candidate)!!.model
    }

    @Test
    fun `openai protocol providers offer Koog reasoning efforts`() {
        val thinking = model("openai", thinking = true)
        assertEquals(
            listOf("default", "none", "minimal", "low", "medium", "high"),
            ThinkingOptions.forModel("openai", thinking).map { it.label },
        )
        // Same parameter space for the coding plan and the ChatGPT backend.
        assertEquals(
            ThinkingOptions.forModel("openai", thinking).map { it.label },
            ThinkingOptions.forModel("zai", model("zai", thinking = true)).map { it.label },
        )
        assertEquals(
            ThinkingOptions.forModel("openai", thinking).map { it.label },
            ThinkingOptions.forModel("openai-codex", model("openai-codex", thinking = true)).map { it.label },
        )
    }

    @Test
    fun `google offers Koog thinking levels`() {
        assertEquals(
            listOf("default", "low", "high"),
            ThinkingOptions.forModel("google", model("google", thinking = true)).map { it.label },
        )
    }

    @Test
    fun `anthropic protocol providers offer default and off`() {
        assertEquals(
            listOf("default", "off"),
            ThinkingOptions.forModel("anthropic", model("anthropic", thinking = true)).map { it.label },
        )
        assertEquals(
            listOf("default", "off"),
            ThinkingOptions.forModel("kimi", model("kimi", thinking = true)).map { it.label },
        )
    }

    @Test
    fun `models without the thinking capability offer only the default`() {
        val openai = ProviderDescriptors.byId("openai")!!
        val nonThinking = openai.models.firstOrNull {
            !it.model.supports(ai.koog.prompt.llm.LLMCapability.Thinking)
        } ?: return // all current OpenAI models reason; nothing to assert
        assertEquals(
            listOf(ThinkingOption.Default),
            ThinkingOptions.forModel("openai", nonThinking.model),
        )
    }

    @Test
    fun `providers without a Koog thinking param offer only the default`() {
        for (providerId in listOf("deepseek", "mistral", "dashscope", "openrouter")) {
            val provider = ProviderDescriptors.byId(providerId)!!
            val reasoning = provider.models.firstOrNull {
                it.model.supports(ai.koog.prompt.llm.LLMCapability.Thinking)
            } ?: continue
            assertEquals(
                listOf(ThinkingOption.Default),
                ThinkingOptions.forModel(providerId, reasoning.model),
                "$providerId must not invent options Koog cannot send",
            )
        }
    }

    @Test
    fun `parse reads back labels and falls back to default`() {
        val openai = model("openai", thinking = true)
        assertEquals(
            ThinkingOption.Effort(ReasoningEffort.MEDIUM),
            ThinkingOptions.parse("openai", openai, "medium"),
        )
        assertEquals(ThinkingOption.Default, ThinkingOptions.parse("openai", openai, null))
        // Unknown labels (and labels from another provider's space) never fail.
        assertEquals(ThinkingOption.Default, ThinkingOptions.parse("openai", openai, "bogus"))
        assertEquals(ThinkingOption.Default, ThinkingOptions.parse("openai", openai, "off"))
        assertEquals(
            ThinkingOption.Off,
            ThinkingOptions.parse("anthropic", model("anthropic", thinking = true), "off"),
        )
    }

    @Test
    fun `params pass Koog values through verbatim`() {
        val openaiParams = ThinkingOptions.params("openai", ThinkingOption.Effort(ReasoningEffort.MEDIUM))
        val openaiChat = assertIs<OpenAIChatParams>(openaiParams)
        assertEquals(ReasoningEffort.MEDIUM, openaiChat.reasoningEffort)
        assertNull((assertIs<OpenAIChatParams>(ThinkingOptions.params("openai", ThinkingOption.Default))).reasoningEffort)

        val googleParams = assertIs<GoogleParams>(
            ThinkingOptions.params("google", ThinkingOption.GeminiLevel(GoogleThinkingLevel.HIGH)),
        )
        assertEquals(GoogleThinkingLevel.HIGH, googleParams.thinkingConfig?.thinkingLevel)
        assertNull((assertIs<GoogleParams>(ThinkingOptions.params("google", ThinkingOption.Default))).thinkingConfig)

        val anthropicOff = assertIs<AnthropicParams>(ThinkingOptions.params("anthropic", ThinkingOption.Off))
        assertTrue(anthropicOff.thinking is AnthropicThinking.Disabled)
        assertNull(
            (assertIs<AnthropicParams>(ThinkingOptions.params("anthropic", ThinkingOption.Default))).thinking,
        )

        // Providers with nothing to configure get plain params.
        assertIs<LLMParams>(ThinkingOptions.params("openrouter", ThinkingOption.Default))
    }
}
