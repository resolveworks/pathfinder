package works.resolve.pathfinder.agent

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClientAPI
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMProvider
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import works.resolve.pathfinder.ai.providers.ProviderDescriptors

/**
 * The default construction path maps every shipped Koog [LLMProvider] to its
 * Koog client, and no credential value leaks into client string
 * representations. Protocol behavior itself is owned by Koog's own test
 * suite.
 */
class KoogClientsTest {

    private val factory = KtorKoogHttpClient.Factory()

    @Test
    fun everyShippedKoogProviderMapsToItsClient() {
        val expected: Map<LLMProvider, KClass<out LLMClientAPI>> = mapOf(
            LLMProvider.Anthropic to AnthropicLLMClient::class,
            LLMProvider.OpenAI to OpenAILLMClient::class,
            LLMProvider.Google to GoogleLLMClient::class,
            LLMProvider.OpenRouter to OpenRouterLLMClient::class,
            LLMProvider.MistralAI to MistralAILLMClient::class,
        )

        // The catalog's Koog provider set (each model carries its provider) is
        // exactly the shipped client set: no provider without a client, no
        // client without a provider.
        val catalogProviders = ProviderDescriptors.all
            .map { descriptor -> descriptor.models.first().model.provider }
            .toSet()
        assertEquals(expected.keys, catalogProviders)

        for ((provider, clientClass) in expected) {
            val client = KoogClients.create(provider, API_KEY, factory)
            assertEquals(clientClass, client::class)
            // Koog's own round-trip: the client knows its provider.
            assertEquals(provider, client.llmProvider())
            client.close()
        }
    }

    @Test
    fun clientStringRepresentationsContainNoCredential() {
        val client = KoogClients.create(LLMProvider.Anthropic, API_KEY, factory)
        val rendered = client.toString() + " " + client.llmProvider().toString()
        assertFalse(API_KEY in rendered)
        assertContains(client::class.simpleName!!, "Anthropic")
        client.close()
    }

    @Test
    fun unshippedKoogProviderIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            KoogClients.create(LLMProvider.Ollama, API_KEY, factory)
        }
    }

    private companion object {
        const val API_KEY = "unit-test-secret-key"
    }
}
