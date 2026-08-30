package works.resolve.pathfinder.runtime

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The provider surface: exactly nine providers, models derived from Koog's
 * own model-definition objects (enumerated providers) or hand-declared as
 * Koog LLModels from pi's catalogs (coding-plan providers), stable ids,
 * auth kinds.
 */
class ProviderDescriptorTest {

    @Test
    fun `nine providers exist with stable ids and auth kinds`() {
        assertEquals(
            listOf(
                "anthropic", "openai", "google", "openrouter", "mistral",
                "deepseek", "zai", "kimi", "openai-codex",
            ),
            ProviderDescriptors.all.map { it.id },
        )
        assertEquals("Anthropic", ProviderDescriptors.byId("anthropic")!!.displayName)
        assertNotNull(ProviderDescriptors.byId("openai"))
        assertNotNull(ProviderDescriptors.byId("openrouter"))
        assertEquals("Z.AI", ProviderDescriptors.byId("zai")!!.displayName)
        assertEquals("Kimi", ProviderDescriptors.byId("kimi")!!.displayName)
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
            "deepseek" to DeepSeekModels.models,
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
            // Hand-declared coding-plan catalogs are covered by their own test.
            if (provider.id !in koogDefinitions) continue
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

    @Test
    fun `hand-declared coding plan catalogs match pi's catalogs`() {
        val expected = mapOf(
            "zai" to LLMProvider.ZhipuAI to listOf(
                "glm-4.7" to "GLM-4.7",
                "glm-5-turbo" to "GLM-5-Turbo",
                "glm-5.2" to "GLM-5.2",
                "glm-5.2-highspeed" to "GLM-5.2 Highspeed",
                "glm-5.3" to "GLM-5.3",
            ),
            "kimi" to KimiProvider to listOf(
                "k3" to "Kimi K3",
                "k3-256k" to "Kimi K3-256K",
                "kimi-for-coding" to "Kimi K2.7 Code",
                "kimi-for-coding-highspeed" to "Kimi For Coding HighSpeed",
            ),
        )
        for ((key, models) in expected) {
            val (providerId, expectedProvider) = key
            val provider = ProviderDescriptors.byId(providerId)!!
            assertEquals(models, provider.models.map { it.id to it.displayName })
            provider.models.forEach { descriptor ->
                assertEquals(expectedProvider, descriptor.model.provider, "$providerId/${descriptor.id}")
                // The runtime executes streaming completions; Kimi's client
                // additionally resolves models through its version map.
                assertTrue(descriptor.model.supports(LLMCapability.Completion), descriptor.id)
            }
        }
        // The Anthropic client rejects models missing from the version map.
        assertEquals(
            ProviderDescriptors.byId("kimi")!!.models.map { it.model }.toSet(),
            KimiModels.versionMap.keys,
        )
    }

    private companion object {
        val EMBEDDING_AND_MODERATION_IDS = setOf("mistral-embed", "codestral-embed", "mistral-moderation-2411")
    }
}
