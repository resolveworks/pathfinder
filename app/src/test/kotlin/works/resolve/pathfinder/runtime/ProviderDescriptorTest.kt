package works.resolve.pathfinder.runtime

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
 * The provider surface: exactly six providers, models derived from Koog's
 * own model-definition objects, stable ids, auth kinds.
 */
class ProviderDescriptorTest {

    @Test
    fun `six providers exist with stable ids and auth kinds`() {
        assertEquals(
            listOf("anthropic", "openai", "google", "openrouter", "mistral", "openai-codex"),
            ProviderDescriptors.all.map { it.id },
        )
        assertEquals("Anthropic", ProviderDescriptors.byId("anthropic")!!.displayName)
        assertNotNull(ProviderDescriptors.byId("openai"))
        assertNotNull(ProviderDescriptors.byId("openrouter"))
        val codex = ProviderDescriptors.byId("openai-codex")!!
        assertEquals("OpenAI Codex", codex.displayName)
        assertEquals(ProviderAuthKind.ChatGptSignIn, codex.authKind)
        // API-key providers label their credential form.
        assertTrue(ProviderDescriptors.byId("anthropic")!!.authKind is ProviderAuthKind.ApiKey)
    }

    @Test
    fun `models are enumerated from Koog definitions and non-empty`() {
        val koogDefinitions = mapOf(
            "anthropic" to AnthropicModels.models,
            "openai" to OpenAIModels.models,
            "google" to GoogleModels.models,
            "openrouter" to OpenRouterModels.models,
            "mistral" to MistralAIModels.models,
            // The codex provider enumerates the codex entries of Koog's
            // OpenAIModels — the subset that runs on the ChatGPT backend.
            "openai-codex" to listOf(
                OpenAIModels.Chat.GPT5Codex,
                OpenAIModels.Chat.GPT5_1Codex,
                OpenAIModels.Chat.GPT5_1CodexMax,
                OpenAIModels.Chat.GPT5_2Codex,
                OpenAIModels.Chat.GPT5_3Codex,
            ),
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
