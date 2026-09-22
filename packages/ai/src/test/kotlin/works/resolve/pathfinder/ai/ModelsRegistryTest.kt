package works.resolve.pathfinder.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.api.OpenAiCompletionsApi
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.TestCatalogs
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry

/** Real Z.AI provider configuration against a scripted [FakeTransport]; no network I/O. */
class ModelsRegistryTest {

    private fun models(transport: FakeTransport, storedKey: String? = null): Models = Models(
        listOf(
            Provider(
                id = "zai",
                name = "Z.AI",
                baseUrl = TestCatalogs.ZAI.baseUrl,
                authResolver = { explicitKey, _ ->
                    explicitKey?.let { ResolvedAuth(it) }
                        ?: storedKey?.let { ResolvedAuth(it) }
                },
                models = TestCatalogs.MODELS,
                apiId = "openai-completions",
                api = OpenAiCompletionsApi(
                    transport,
                    ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 })
                )
            )
        )
    )

    @Test
    fun `explicit api key wins over resolver`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]")
        )
        models(transport, storedKey = "stored").stream(
            TestCatalogs.GLM_4_7,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(apiKey = "explicit")
        ).toList()
        assertEquals("explicit", transport.requests.single().bearerToken)
    }

    @Test
    fun `resolver supplies key when option absent`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]")
        )
        models(transport, storedKey = "stored").stream(
            TestCatalogs.GLM_4_7,
            Context(messages = listOf(UserMessage.ofText("hi")))
        ).toList()
        assertEquals("stored", transport.requests.single().bearerToken)
    }

    @Test
    fun `missing key surfaces as error event`() = runTest {
        val transport = FakeTransport()
        val events = models(transport, storedKey = null).stream(
            TestCatalogs.GLM_4_7,
            Context(messages = listOf(UserMessage.ofText("hi")))
        ).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue("Provider is not configured: zai" in (error.error.errorMessage ?: ""))
    }

    @Test
    fun `unknown provider surfaces as error event`() = runTest {
        val transport = FakeTransport()
        val models = models(transport)
        val alien = TestCatalogs.GLM_4_7.copy(provider = "nope")
        val events = models.stream(alien, Context(messages = emptyList())).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue("Unknown provider: nope" in (error.error.errorMessage ?: ""))
        assertTrue(models.getModel("zai", "nope") == null, "unknown model id is not in the catalog")
    }

    @Test
    fun `registry exposes providers and models`() {
        val models = models(FakeTransport())
        val provider = models.getProvider("zai")!!
        assertEquals("Z.AI", provider.name)
        assertEquals(
            listOf("glm-4.7", "glm-5-turbo", "glm-5.3", "glm-5.2", "glm-5.2-highspeed"),
            provider.models.map { it.id }
        )
        assertEquals("glm-5.3", models.getModel("zai", "glm-5.3")!!.id)
    }
}
