package works.resolve.pathfinder.ai.models

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.transport.OkHttpTransport

class ModelsAuthBaseUrlTest {

    private val catalogServer = MockWebServer()
    private val authServer = MockWebServer()
    private val transport = OkHttpTransport(
        client = okhttp3.OkHttpClient.Builder().build(),
    )

    private val model = Model(
        id = "claude-sonnet-5",
        name = "Claude Sonnet 5",
        api = "anthropic-messages",
        provider = "github-copilot",
        baseUrl = "", // replaced per-test
        reasoning = true,
        input = listOf(InputModality.TEXT),
        cost = ModelCost(input = 1.0, output = 5.0, cacheRead = 0.1, cacheWrite = 1.25),
        contextWindow = 200_000,
        maxTokens = 64_000,
    )

    private val context = Context(
        systemPrompt = "Be terse.",
        messages = listOf(UserMessage.ofText("hi")),
    )

    private val sseBody = sequence {
        yield("message_start" to """{"type":"message_start","message":{"id":"m","model":"claude-sonnet-5","usage":{"input_tokens":1,"output_tokens":0}}}""")
        yield("content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        yield("content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""")
        yield("content_block_stop" to """{"type":"content_block_stop","index":0}""")
        yield("message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""")
        yield("message_stop" to """{"type":"message_stop"}""")
    }.joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }

    private fun sse(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody)

    @AfterTest
    fun tearDown() {
        catalogServer.shutdown()
        authServer.shutdown()
    }

    @Test
    fun `auth-derived base URL replaces the request endpoint`() = runTest {
        catalogServer.start(); authServer.start()
        authServer.enqueue(sse())
        val modelOnCatalogServer = model.copy(baseUrl = catalogServer.url("/").toString().trimEnd('/'))

        val models = Models(
            listOf(
                Provider(
                    id = "github-copilot",
                    name = "GitHub Copilot",
                    baseUrl = catalogServer.url("/").toString(),
                    authResolver = { _, _ ->
                        // GitHub Copilot toAuth: API key + per-account base URL.
                        ResolvedAuth(apiKey = "copilot-token", baseUrl = authServer.url("/").toString().trimEnd('/'))
                    },
                    models = listOf(modelOnCatalogServer),
                    apis = mapOf("anthropic-messages" to ChatApiRegistry.create("anthropic-messages", transport, ProviderRetry())!!),
                ),
            ),
        )

        models.stream(modelOnCatalogServer, context, SimpleStreamOptions(apiKey = null)).toList()

        assertEquals(1, authServer.requestCount)
        val recorded = authServer.takeRequest()
        assertTrue(recorded.path!!.endsWith("/v1/messages"))
        // pi's Copilot branch of anthropic-messages createClient: Bearer auth, no x-api-key.
        assertEquals("Bearer copilot-token", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("x-api-key"))
        assertEquals(0, catalogServer.requestCount)
    }

    @Test
    fun `null auth base URL keeps the model endpoint`() = runTest {
        catalogServer.start(); authServer.start()
        catalogServer.enqueue(sse())
        val modelOnCatalogServer = model.copy(baseUrl = catalogServer.url("/").toString().trimEnd('/'))

        val models = Models(
            listOf(
                Provider(
                    id = "github-copilot",
                    name = "GitHub Copilot",
                    baseUrl = catalogServer.url("/").toString(),
                    authResolver = { _, _ -> ResolvedAuth(apiKey = "copilot-token") },
                    models = listOf(modelOnCatalogServer),
                    apis = mapOf("anthropic-messages" to ChatApiRegistry.create("anthropic-messages", transport, ProviderRetry())!!),
                ),
            ),
        )

        models.stream(modelOnCatalogServer, context, SimpleStreamOptions(apiKey = null)).toList()

        assertEquals(1, catalogServer.requestCount)
        assertEquals(0, authServer.requestCount)
    }
}
