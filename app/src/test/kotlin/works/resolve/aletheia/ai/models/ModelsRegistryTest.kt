package works.resolve.aletheia.ai.models

import works.resolve.aletheia.ai.api.OpenAiCompletionsApi
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.testing.TestCatalogs
import works.resolve.aletheia.ai.testing.FakeTransport
import works.resolve.aletheia.ai.testing.sse
import works.resolve.aletheia.ai.utils.ProviderRetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Registry integration tests: the real Z.AI provider wired through
 * OpenAiCompletionsApi and a scripted transport, covering key resolution
 * (explicit vs resolver vs missing) and catalog lookups.
 */
class ModelsRegistryTest {

    private fun models(transport: FakeTransport, storedKey: String? = null): Models =
        Models(
            listOf(
                Provider(
                    id = "zai",
                    name = "Z.AI",
                    baseUrl = TestCatalogs.ZAI.baseUrl,
                    authResolver = { storedKey?.let { ProviderCredential(it) } },
                    models = TestCatalogs.MODELS,
                    api = OpenAiCompletionsApi(
                        transport,
                        ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 }),
                    ),
                ),
            ),
        )

    @Test
    fun `explicit api key wins over resolver`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        models(transport, storedKey = "stored").stream(
            TestCatalogs.GLM_4_7,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(apiKey = "explicit"),
        ).toList()
        assertEquals("explicit", transport.requests.single().bearerToken)
    }

    @Test
    fun `resolver supplies key when option absent`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        models(transport, storedKey = "stored").stream(
            TestCatalogs.GLM_4_7,
            Context(messages = listOf(UserMessage.ofText("hi"))),
        ).toList()
        assertEquals("stored", transport.requests.single().bearerToken)
    }

    @Test
    fun `missing key surfaces as error event`() = runTest {
        val transport = FakeTransport()
        val events = models(transport, storedKey = null).stream(
            TestCatalogs.GLM_4_7,
            Context(messages = listOf(UserMessage.ofText("hi"))),
        ).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue("Provider is not configured" in (error.error.errorMessage ?: ""))
    }

    @Test
    fun `unknown provider or model throws`() {
        val transport = FakeTransport()
        val models = models(transport)
        assertFailsWithMessage<IllegalArgumentException>("Unknown provider") {
            val alien = TestCatalogs.GLM_4_7.copy(provider = "nope")
            models.stream(alien, Context(messages = emptyList()))
        }
        assertTrue(models.getModel("zai", "nope") == null, "unknown model id is not in the catalog")
    }

    @Test
    fun `registry exposes providers and models`() {
        val models = models(FakeTransport())
        val provider = models.getProvider("zai")!!
        assertEquals("Z.AI", provider.name)
        assertEquals(
            listOf("glm-4.7", "glm-5-turbo", "glm-5.3", "glm-5.2", "glm-5.2-highspeed"),
            provider.models.map { it.id },
        )
        assertEquals("glm-5.3", models.getModel("zai", "glm-5.3")!!.id)
    }

    private inline fun <reified T : Throwable> assertFailsWithMessage(
        fragment: String,
        block: () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName} but call succeeded")
        } catch (error: Throwable) {
            if (error::class != T::class) throw error
            assertTrue(fragment in (error.message ?: ""), "expected '$fragment' in: ${error.message}")
        }
    }
}
