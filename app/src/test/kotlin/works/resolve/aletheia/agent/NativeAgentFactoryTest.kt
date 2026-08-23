package works.resolve.aletheia.agent

import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.transport.SseEvent
import works.resolve.aletheia.ai.transport.TransportRequest
import works.resolve.aletheia.ai.transport.TransportResponse
import works.resolve.aletheia.data.credentials.ApiKeyStore
import works.resolve.aletheia.data.settings.ModelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

/**
 * Focused JVM tests for the production factory: validation, transcript
 * copying, and end-to-end wiring through the native stack against a fake
 * transport. The fake key is a distinctive test value and is never printed.
 */
class NativeAgentFactoryTest {

    private class FakeApiKeyStore(initialKey: String? = null) : ApiKeyStore {
        val key = MutableStateFlow(initialKey)
        var getApiKeyCalls = 0
        var setApiKeyCalls = 0

        override suspend fun getApiKey(providerId: String): String? {
            getApiKeyCalls++
            return key.value
        }

        override suspend fun setApiKey(providerId: String, apiKey: String) {
            setApiKeyCalls++
            key.value = apiKey
        }

        override suspend fun deleteApiKey(providerId: String) {
            key.value = null
        }
    }

    private class RecordingTransport : HttpStreamingTransport {
        val requests = mutableListOf<TransportRequest>()

        override suspend fun post(request: TransportRequest): TransportResponse {
            requests.add(request)
            return TransportResponse(
                status = 200,
                headers = mapOf("content-type" to listOf("text/event-stream")),
                events = flowOf(
                    SseEvent("""{"choices":[{"delta":{"content":"Hi"}}]}"""),
                    SseEvent("""{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""),
                    SseEvent("[DONE]"),
                ),
            )
        }
    }

    private fun factory(store: FakeApiKeyStore, transport: RecordingTransport): AgentFactory =
        NativeAgentFactory(credentials = store, transport = transport)

    private fun settings(
        providerId: String = "zai",
        modelId: String = "glm-4.7",
        baseUrl: String? = null,
    ) = ModelSettings(providerId = providerId, modelId = modelId, baseUrl = baseUrl)

    // ---- validation ----

    @Test
    fun `rejects an unsupported provider`() {
        assertFailsWith<IllegalArgumentException> {
            factory(FakeApiKeyStore("k"), RecordingTransport())
                .create(settings(providerId = "openai"), "s1", emptyList())
        }
    }

    @Test
    fun `rejects an unknown model`() {
        assertFailsWith<IllegalArgumentException> {
            factory(FakeApiKeyStore("k"), RecordingTransport())
                .create(settings(modelId = "gpt-4"), "s1", emptyList())
        }
    }

    @Test
    fun `rejects a blank base URL`() {
        assertFailsWith<IllegalArgumentException> {
            factory(FakeApiKeyStore("k"), RecordingTransport())
                .create(settings(baseUrl = "   "), "s1", emptyList())
        }
    }

    // ---- transcript ----

    @Test
    fun `copies the initial transcript into the agent`() {
        val transcript: MutableList<works.resolve.aletheia.ai.core.Message> = mutableListOf(
            UserMessage.ofText("hello"),
            UserMessage.ofText("again"),
        )
        val agent = factory(FakeApiKeyStore("k"), RecordingTransport())
            .create(settings(), "s1", transcript)
        assertEquals(transcript, agent.state.value.messages)
        // Copied defensively: later mutation of the source list is invisible.
        transcript.clear()
        assertEquals(2, agent.state.value.messages.size)
    }

    // ---- wiring through the native stack ----

    @Test
    fun `prompt uses the selected model, effective base URL, and the lazily resolved current key`() {
        runBlocking {
            val store = FakeApiKeyStore("factory-test-key-1")
            val transport = RecordingTransport()
            val agent = factory(store, transport)
                .create(settings(baseUrl = "https://example.test/api/v4//"), "s1", emptyList())

            // Rotating the stored key after construction must be observed at
            // prompt time: the resolver stays lazy and reads the store per request.
            store.key.value = "factory-test-key-2"

            agent.prompt("ping")

            val state = agent.state.value
            assertEquals(false, state.isStreaming)
            val last = state.messages.last()
            assertTrue(last is works.resolve.aletheia.ai.core.AssistantMessage)
            assertEquals("Hi", (last.content.single() as TextContent).text)

            assertEquals(1, store.getApiKeyCalls)
            val request = transport.requests.single()
            // Overridden base URL, normalized (trailing slashes dropped) + endpoint.
            assertEquals("https://example.test/api/v4/chat/completions", request.url)
            assertEquals("Bearer factory-test-key-2", request.bearerToken?.let { "Bearer $it" })
            assertTrue(request.timeoutMs != null && request.timeoutMs > 0)

            val body = Json.parseToJsonElement(String(request.body)).jsonObject
            assertEquals("glm-4.7", body["model"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `default base URL is used when no override is set`() {
        runBlocking {
            val store = FakeApiKeyStore("factory-test-key-1")
            val transport = RecordingTransport()
            val agent = factory(store, transport).create(settings(), "s1", emptyList())
            agent.prompt("ping")
            assertEquals("https://api.z.ai/api/coding/paas/v4/chat/completions", transport.requests.single().url)
        }
    }
}
