package works.resolve.pathfinder.agent

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClientAPI
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import works.resolve.pathfinder.ai.providers.ProviderDescriptor
import works.resolve.pathfinder.ai.providers.ProviderDescriptors

/**
 * The default construction path maps every provider id to its Koog client,
 * and no credential value leaks into client string representations.
 * Protocol behavior itself is owned by Koog's own test suite.
 */
class KoogClientsTest {

    private val factory = KtorKoogHttpClient.Factory()

    @Test
    fun everyProviderDescriptorMapsToItsKoogClient() {
        val expected: Map<String, (ProviderDescriptor) -> LLMClientAPI> = mapOf(
            "anthropic" to { AnthropicLLMClient(API_KEY, httpClientFactory = factory) },
            "openai" to { OpenAILLMClient(API_KEY, httpClientFactory = factory) },
            "google" to { GoogleLLMClient(API_KEY, httpClientFactory = factory) },
            "openrouter" to { OpenRouterLLMClient(API_KEY, httpClientFactory = factory) },
            "mistral" to { MistralAILLMClient(API_KEY, httpClientFactory = factory) },
        )

        assertEquals(expected.keys, ProviderDescriptors.all.map { it.id }.toSet())
        for (descriptor in ProviderDescriptors.all) {
            val client = KoogClients.create(descriptor, API_KEY, factory)
            assertEquals(expected.getValue(descriptor.id)(descriptor)::class, client::class)
            client.close()
        }
    }

    @Test
    fun clientStringRepresentationsContainNoCredential() {
        val client = KoogClients.create(ProviderDescriptors.byId("anthropic")!!, API_KEY, factory)
        val rendered = client.toString() + " " + client.llmProvider().toString()
        assertFalse(API_KEY in rendered)
        assertContains(client::class.simpleName!!, "Anthropic")
        client.close()
    }

    @Test
    fun unknownProviderIdIsRejected() {
        val unknown = ProviderDescriptor("nope", "Nope", "key", emptyList())
        assertFailsWith<IllegalArgumentException> { KoogClients.create(unknown, API_KEY, factory) }
    }

    private companion object {
        const val API_KEY = "unit-test-secret-key"
    }
}
