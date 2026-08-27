package works.resolve.pathfinder.ai.api

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry

/**
 * Canned tests for [AzureOpenAiResponsesApi], ported alongside pi's
 * azure-openai-responses.ts (base-URL normalization from
 * azure-openai-base-url.test.ts).
 */
class AzureOpenAiResponsesApiTest {

    private val model = Model(
        id = "gpt-4o-mini",
        name = "GPT-4o mini",
        api = "azure-openai-responses",
        provider = "azure-openai-responses",
        baseUrl = "https://my-resource.openai.azure.com/openai/v1",
        cost = ModelCost(input = 0.15, output = 0.6, cacheRead = 0.075, cacheWrite = 0.0),
        contextWindow = 128_000,
        maxTokens = 16_384,
        responsesCompat = OpenAiResponsesCompat(),
    )

    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun api(transport: FakeTransport) = AzureOpenAiResponsesApi(
        transport,
        ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 }),
        nowMs = { 1_770_000_000_000L },
    )

    private fun completed() = listOf(
        """{"type":"response.completed","response":{"id":"r1","status":"completed",
            "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        "[DONE]",
    )

    private fun bodyOf(transport: FakeTransport) =
        responsesJson.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject

    @Test
    fun `normalizes bare azure hosts to openai v1 and appends responses path`() = runTest {
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1",
            normalizeAzureBaseUrlFor("https://my-resource.openai.azure.com/"),
        )
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1",
            normalizeAzureBaseUrlFor("https://my-resource.openai.azure.com/openai"),
        )
        assertEquals(
            "https://my-resource.services.ai.azure.com/openai/v1",
            normalizeAzureBaseUrlFor("https://my-resource.services.ai.azure.com/openai/v1/responses"),
        )
        // Non-Azure hosts are preserved as-is.
        assertEquals(
            "https://proxy.example.com/azure",
            normalizeAzureBaseUrlFor("https://proxy.example.com/azure/"),
        )
    }

    @Test
    fun `invalid base url fails fast`() {
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            normalizeAzureBaseUrlFor("not-a-url")
        }
        assertTrue(error.message!!.contains("Invalid Azure OpenAI base URL: not-a-url"))
    }

    @Test
    fun `requests hit responses with api-version and the api-key header`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        api(transport).stream(model, context, AzureOpenAiResponsesOptions(apiKey = "az-key")).toList()
        val request = transport.requests.single()
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1/responses?api-version=v1",
            request.url,
        )
        assertEquals("az-key", request.headers["api-key"])
        assertNull(request.bearerToken)
        assertTrue(request.headers.keys.none { it.equals("authorization", ignoreCase = true) })
    }

    @Test
    fun `resource name and env base url and api version resolve in priority order`() = runTest {
        assertEquals(
            AzureConfig("https://res.openai.azure.com/openai/v1", "v1"),
            resolveAzureConfig(model, AzureOpenAiResponsesOptions(azureResourceName = "res")),
        )
        assertEquals(
            AzureConfig("https://env.openai.azure.com/openai/v1", "2025-04-01"),
            resolveAzureConfig(
                model,
                AzureOpenAiResponsesOptions(
                    env = mapOf(
                        "AZURE_OPENAI_BASE_URL" to "https://env.openai.azure.com/openai/v1",
                        "AZURE_OPENAI_API_VERSION" to "2025-04-01",
                    ),
                ),
            ),
        )
        // Explicit options beat env.
        assertEquals(
            AzureConfig("https://opt.openai.azure.com/openai/v1", "v2"),
            resolveAzureConfig(
                model,
                AzureOpenAiResponsesOptions(
                    azureBaseUrl = "https://opt.openai.azure.com/openai/v1",
                    azureApiVersion = "v2",
                    env = mapOf("AZURE_OPENAI_BASE_URL" to "https://env.openai.azure.com"),
                ),
            ),
        )
    }

    @Test
    fun `missing base url is an error`() {
        val bare = model.copy(baseUrl = "")
        val error = kotlin.test.assertFailsWith<IllegalStateException> {
            resolveAzureConfig(bare, AzureOpenAiResponsesOptions())
        }
        assertTrue(error.message!!.contains("Azure OpenAI base URL is required"))
    }

    @Test
    fun `deployment names map from env and options`() {
        assertEquals(
            "dep-1",
            resolveDeploymentName(
                model,
                AzureOpenAiResponsesOptions(env = mapOf("AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "gpt-4o-mini=dep-1")),
            ),
        )
        assertEquals(
            "dep-2",
            resolveDeploymentName(model, AzureOpenAiResponsesOptions(azureDeploymentName = "dep-2")),
        )
        assertEquals("gpt-4o-mini", resolveDeploymentName(model, AzureOpenAiResponsesOptions()))
        assertEquals(
            mapOf("a" to "b", "c" to "d"),
            parseDeploymentNameMap("a=b, c=d ,"),
        )
    }

    @Test
    fun `payload uses the deployment name as model`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        api(transport).stream(
            model,
            context,
            AzureOpenAiResponsesOptions(
                apiKey = "k",
                azureDeploymentName = "my-deployment",
                sessionId = "session-1",
            ),
        ).toList()
        val body = bodyOf(transport)
        assertEquals("my-deployment", body["model"]!!.jsonPrimitive.content)
        assertEquals("session-1", body["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `missing api key is an error event`() = runTest {
        val transport = FakeTransport()
        val events = api(transport).stream(model, context, AzureOpenAiResponsesOptions()).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals("No API key for provider: azure-openai-responses", error.error.errorMessage)
    }

    @Test
    fun `http errors carry the azure prefix`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(401, """{"error":{"message":"bad key"}}""")
        val events = api(transport).stream(model, context, AzureOpenAiResponsesOptions(apiKey = "k")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("Azure OpenAI API error (401): bad key", error.error.errorMessage)
    }

    @Test
    fun `terminal event completes the stream`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        val events = api(transport).stream(model, context, AzureOpenAiResponsesOptions(apiKey = "k")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("r1", done.message.responseId)
        assertEquals(10, done.message.usage.input)
    }
}
