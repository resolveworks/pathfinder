package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.InputModality
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.ThinkingLevel
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.testing.FakeTransport
import works.resolve.aletheia.ai.testing.sse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Canned tests for the Vertex AI adapter: endpoint/project/location shaping,
 * API key resolution (pi's resolveApiKey marker/placeholder rules and
 * resolveProject/resolveLocation env fallbacks, from
 * test/google-vertex-api-key-resolution.test.ts and google-vertex.ts), the
 * documented ADC divergence, and the thinking payload rules that differ from
 * the Gemini API adapter (no Gemma branches, no flash-lite budget table).
 */
class GoogleVertexApiTest {

    private fun vertexModel(id: String = "gemini-2.5-flash", baseUrl: String = "") = Model(
        id = id, name = id, api = "google-vertex", provider = "google-vertex",
        baseUrl = baseUrl, reasoning = true, input = listOf(InputModality.TEXT),
        contextWindow = 128000, maxTokens = 8192,
    )

    private fun api(transport: FakeTransport) = GoogleVertexApi(
        transport,
        works.resolve.aletheia.ai.utils.ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 }),
        nowMs = { 1_770_000_000_000L },
    )

    private val context = Context(messages = listOf(UserMessage.ofText("hello")))

    private fun opts(
        apiKey: String? = "k",
        project: String? = "test-project",
        location: String? = "us-central1",
        env: Map<String, String> = emptyMap(),
        baseUrl: String = "",
    ) = GoogleVertexApi.GoogleVertexOptions(
        apiKey = apiKey, project = project, location = location, env = env,
    ) to vertexModel(baseUrl = baseUrl)

    @Test
    fun `builds the regional publishers endpoint and sends the api key header`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"responseId":"vertex-response-id","candidates":[
                    {"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                    "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}""",
            ),
        )
        val (options, model) = opts()
        val done = api(transport).stream(model, context, options).toList().last()
        assertIs<AssistantMessageEvent.Done>(done)
        assertEquals(StopReason.STOP, done.reason)

        val request = transport.requests.single()
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/publishers/google/models/" +
                "gemini-2.5-flash:streamGenerateContent?alt=sse",
            request.url,
        )
        assertEquals("k", request.headers["x-goog-api-key"])
        assertNull(request.bearerToken, "no ADC bearer is available")
        assertEquals(GoogleRequest.USER_AGENT, request.headers["User-Agent"])
    }

    @Test
    fun `project and location fall back to env then options`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        val (options, model) = opts(
            project = null,
            location = null,
            env = mapOf("GOOGLE_CLOUD_PROJECT" to "env-project", "GCLOUD_PROJECT" to "ignored", "GOOGLE_CLOUD_LOCATION" to "europe-west1"),
        )
        api(transport).stream(model, context, options).toList()
        assertTrue(
            transport.requests.single().url.startsWith("https://europe-west1-aiplatform.googleapis.com/v1/"),
        )
        // env-project is not on the wire URL body; the URL only carries location.
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("hello", body["contents"]!!.jsonArray[0].jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing project and location keep upstream error messages`() = runTest {
        val transport = FakeTransport()
        val events = api(transport).stream(
            vertexModel(), context,
            GoogleVertexApi.GoogleVertexOptions(apiKey = "k"),
        ).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals(
            "Vertex AI requires a project ID. Set GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT or pass project in options.",
            error.error.errorMessage,
        )

        val transport2 = FakeTransport()
        val events2 = api(transport2).stream(
            vertexModel(), context,
            GoogleVertexApi.GoogleVertexOptions(apiKey = "k", project = "p"),
        ).toList()
        val error2 = assertIs<AssistantMessageEvent.Error>(events2.single())
        assertEquals(
            "Vertex AI requires a location. Set GOOGLE_CLOUD_LOCATION or pass location in options.",
            error2.error.errorMessage,
        )
    }

    @Test
    fun `placeholder and marker api keys fall back to adc, which diverges with an explicit error`() = runTest {
        for (badKey in listOf("<authenticated>", "gcp-vertex-credentials", "  ", "")) {
            val transport = FakeTransport()
            val events = api(transport).stream(
                vertexModel(), context,
                GoogleVertexApi.GoogleVertexOptions(
                    apiKey = badKey,
                    project = "test-project",
                    location = "us-central1",
                ),
            ).toList()
            val error = assertIs<AssistantMessageEvent.Error>(events.single())
            assertTrue(
                error.error.errorMessage!!.contains("Application Default Credentials"),
                "unexpected message for key '$badKey': ${error.error.errorMessage}",
            )
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun `custom base url without location placeholder replaces the root`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        val (options, model) = opts(baseUrl = "https://vertex-proxy.example")
        api(transport).stream(model, context, options).toList()
        assertEquals(
            "https://vertex-proxy.example/v1/publishers/google/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            transport.requests.single().url,
        )
    }

    @Test
    fun `custom base url with a version segment is used verbatim`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        val (options, model) = opts(baseUrl = "https://vertex-proxy.example/v1beta")
        api(transport).stream(model, context, options).toList()
        assertEquals(
            "https://vertex-proxy.example/v1beta/publishers/google/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            transport.requests.single().url,
        )
    }

    @Test
    fun `base url with a location placeholder falls back to the regional default`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        val (options, model) = opts(baseUrl = "https://{location}-vertex.example/v1")
        api(transport).stream(model, context, options).toList()
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/publishers/google/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            transport.requests.single().url,
        )
    }

    @Test
    fun `thinking budgets follow the vertex table without a flash-lite entry`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            vertexModel(id = "gemini-2.5-flash-lite"),
            context,
            SimpleStreamOptions(
                apiKey = "k",
                reasoning = ThinkingLevel.HIGH,
                env = mapOf("GOOGLE_CLOUD_PROJECT" to "p", "GOOGLE_CLOUD_LOCATION" to "us-central1"),
            ),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        // google-vertex.ts getGoogleBudget has no 2.5-flash-lite branch: it
        // matches the 2.5-flash table.
        assertEquals(24576, thinkingConfig["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `gemini3 flash resolves a thinking level and cannot fully disable thinking`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            vertexModel(id = "gemini-3-flash-preview"),
            context,
            SimpleStreamOptions(
                apiKey = "k",
                reasoning = ThinkingLevel.MINIMAL,
                env = mapOf("GOOGLE_CLOUD_PROJECT" to "p", "GOOGLE_CLOUD_LOCATION" to "us-central1"),
            ),
        ).toList()
        var thinkingConfig = Json.parseToJsonElement(transport.requests.single().body.decodeToString())
            .jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("MINIMAL", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        assertTrue(thinkingConfig["includeThoughts"]!!.jsonPrimitive.content.toBoolean())

        val transport2 = FakeTransport()
        transport2.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport2).streamSimple(
            vertexModel(id = "gemini-3-flash-preview"),
            context,
            SimpleStreamOptions(
                apiKey = "k",
                env = mapOf("GOOGLE_CLOUD_PROJECT" to "p", "GOOGLE_CLOUD_LOCATION" to "us-central1"),
            ),
        ).toList()
        thinkingConfig = Json.parseToJsonElement(transport2.requests.single().body.decodeToString())
            .jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        // Disabled thinking on Gemini 3 Flash: lowest level, no includeThoughts.
        assertEquals("MINIMAL", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals(null, thinkingConfig["includeThoughts"])
    }

    @Test
    fun `thinking budgets honor per-request overrides`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            vertexModel(),
            context,
            SimpleStreamOptions(
                apiKey = "k",
                reasoning = ThinkingLevel.LOW,
                env = mapOf("GOOGLE_CLOUD_PROJECT" to "p", "GOOGLE_CLOUD_LOCATION" to "us-central1"),
                thinkingBudgets = mapOf(ThinkingLevel.LOW to 1234),
            ),
        ).toList()
        val thinkingConfig = Json.parseToJsonElement(transport.requests.single().body.decodeToString())
            .jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(1234, thinkingConfig["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }
}
