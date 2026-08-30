package works.resolve.pathfinder.ai.providers

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.llm.LLMCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The provider surface: exactly five providers, models derived from Koog's
 * own model-definition objects, stable ids.
 */
class ProviderDescriptorTest {

    @Test
    fun `five providers exist with stable ids`() {
        assertEquals(
            listOf("anthropic", "openai", "google", "openrouter", "mistral"),
            ProviderDescriptors.all.map { it.id },
        )
        assertEquals("Anthropic", ProviderDescriptors.byId("anthropic")!!.displayName)
        assertNotNull(ProviderDescriptors.byId("openai"))
        assertNotNull(ProviderDescriptors.byId("openrouter"))
    }

    @Test
    fun `models are enumerated from Koog definitions and non-empty`() {
        val koogDefinitions = mapOf(
            "anthropic" to AnthropicModels.models,
            "openai" to OpenAIModels.models,
            "google" to GoogleModels.models,
            "openrouter" to OpenRouterModels.models,
            "mistral" to MistralAIModels.models,
        )
        for (provider in ProviderDescriptors.all) {
            // The exact chat-completion subset of Koog's definitions.
            val koog = koogDefinitions.getValue(provider.id)
                .filter { it.supports(LLMCapability.Completion) }
            assertTrue(provider.models.isNotEmpty(), "${provider.id} has no models")
            // Enumeration, not hand-copied: exact set equality with Koog.
            assertEquals(koog.map { it.id }.toSet(), provider.models.map { it.id }.toSet(), provider.id)
            assertEquals(koog, provider.models.map { it.model }, provider.id)
            // Every model carries the provider tag of its Koog definition family.
            provider.models.forEach { descriptor ->
                assertEquals(
                    koog.firstOrNull { it.id == descriptor.id }!!.provider,
                    descriptor.model.provider,
                    "${provider.id}/${descriptor.id}",
                )
            }
        }
    }

    @Test
    fun `only chat-completion models are offered`() {
        for (provider in ProviderDescriptors.all) {
            assertTrue(
                provider.models.all { it.model.supports(LLMCapability.Completion) },
                "${provider.id} offers a model the runtime cannot execute",
            )
        }
        // Koog's definition families ship models the product cannot run; the
        // catalog must not offer them (the streaming path requires
        // [LLMCapability.Completion]).
        val google = ProviderDescriptors.byId("google")!!
        assertFalse(google.models.any { it.id == "gemini-embedding-001" })
        val mistral = ProviderDescriptors.byId("mistral")!!
        assertFalse(mistral.models.any { it.id in EMBEDDING_AND_MODERATION_IDS })
    }

    private companion object {
        val EMBEDDING_AND_MODERATION_IDS = setOf("mistral-embed", "codestral-embed", "mistral-moderation-2411")
    }
}
