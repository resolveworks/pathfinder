package works.resolve.pathfinder.ai.providers

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import kotlin.test.Test
import kotlin.test.assertEquals
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
            val koog = koogDefinitions.getValue(provider.id)
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
}
