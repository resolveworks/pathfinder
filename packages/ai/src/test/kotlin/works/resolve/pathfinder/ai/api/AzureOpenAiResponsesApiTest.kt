package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCost
import works.resolve.pathfinder.ai.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry

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
        responsesCompat = OpenAiResponsesCompat()
    )

    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun api(transport: FakeTransport) = AzureOpenAiResponsesApi(
        transport,
        ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }),
        clock = FakeClock(1_770_000_000_000L)
    )

    private fun completed() = listOf(
        """{"type":"response.completed","response":{"id":"r1","status":"completed",
            "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        "[DONE]"
    )

    private fun bodyOf(transport: FakeTransport) = responsesJson.parseToJsonElement(
        transport.requests.single().body.decodeToString()
    ).jsonObject

    @Test
    fun `normalizes bare azure hosts to openai v1 and appends responses path`() = runTest {
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.openai.azure.com/")
        )
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.openai.azure.com/openai")
        )
        assertEquals(
            "https://my-resource.services.ai.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.services.ai.azure.com/openai/v1/responses")
        )
        assertEquals(
            "https://proxy.example.com/azure",
            normalizeAzureBaseUrl("https://proxy.example.com/azure/")
        )
    }

    @Test
    fun `invalid base url fails fast`() {
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            normalizeAzureBaseUrl("not-a-url")
        }
        assertTrue(error.message!!.contains("Invalid Azure OpenAI base URL: not-a-url"))
    }

    @Test
    fun `requests hit responses with api-version and the api-key header`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        api(
            transport
        ).stream(model, context, AzureOpenAiResponsesOptions(apiKey = "az-key")).toList()
        val request = transport.requests.single()
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1/responses?api-version=v1",
            request.url
        )
        assertEquals("az-key", request.headers["api-key"])
        assertNull(request.bearerToken)
        assertTrue(request.headers.keys.none { it.equals("authorization", ignoreCase = true) })
    }

    @Test
    fun `resource name and env base url and api version resolve in priority order`() = runTest {
        assertEquals(
            AzureConfig("https://res.openai.azure.com/openai/v1", "v1"),
            resolveAzureConfig(model, AzureOpenAiResponsesOptions(azureResourceName = "res"))
        )
        assertEquals(
            AzureConfig("https://env.openai.azure.com/openai/v1", "2025-04-01"),
            resolveAzureConfig(
                model,
                AzureOpenAiResponsesOptions(
                    env = mapOf(
                        "AZURE_OPENAI_BASE_URL" to "https://env.openai.azure.com/openai/v1",
                        "AZURE_OPENAI_API_VERSION" to "2025-04-01"
                    )
                )
            )
        )
        assertEquals(
            AzureConfig("https://opt.openai.azure.com/openai/v1", "v2"),
            resolveAzureConfig(
                model,
                AzureOpenAiResponsesOptions(
                    azureBaseUrl = "https://opt.openai.azure.com/openai/v1",
                    azureApiVersion = "v2",
                    env = mapOf("AZURE_OPENAI_BASE_URL" to "https://env.openai.azure.com")
                )
            )
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
                AzureOpenAiResponsesOptions(
                    env = mapOf("AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "gpt-4o-mini=dep-1")
                )
            )
        )
        assertEquals(
            "dep-2",
            resolveDeploymentName(
                model,
                AzureOpenAiResponsesOptions(azureDeploymentName = "dep-2")
            )
        )
        assertEquals("gpt-4o-mini", resolveDeploymentName(model, AzureOpenAiResponsesOptions()))
        assertEquals(
            mapOf("a" to "b", "c" to "d"),
            parseDeploymentNameMap("a=b, c=d ,")
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
                sessionId = "session-1"
            )
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
        val events = api(
            transport
        ).stream(model, context, AzureOpenAiResponsesOptions(apiKey = "k")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(
            """Azure OpenAI API error (401): {"error":{"message":"bad key"}}""",
            error.error.errorMessage
        )
    }

    @Test
    fun `model headers override the default user agent`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        api(transport).stream(
            model.copy(headers = mapOf("User-Agent" to "provider-agent")),
            context,
            AzureOpenAiResponsesOptions(apiKey = "k")
        ).toList()
        assertEquals("provider-agent", transport.requests.single().headers["User-Agent"])
    }

    @Test
    fun `custom gateway base urls keep their query string`() {
        // Only the Azure-host branch strips the query; other hosts keep it, as in pi.
        assertEquals(
            "https://my-proxy.example.com/v1?custom=true",
            normalizeAzureBaseUrl("https://my-proxy.example.com/v1?custom=true")
        )
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1",
            normalizeAzureBaseUrl(
                "https://my-resource.openai.azure.com/openai?api-version=2024-12-01"
            )
        )
    }

    @Test
    fun `custom gateway requests keep the base url query string`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        api(transport).stream(
            model,
            context,
            AzureOpenAiResponsesOptions(
                apiKey = "k",
                azureBaseUrl = "https://my-proxy.example.com/v1?custom=true"
            )
        ).toList()
        assertEquals(
            "https://my-proxy.example.com/v1?custom=true/responses?api-version=v1",
            transport.requests.single().url
        )
    }

    @Test
    fun `tools and tool choice land in the payload`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        val tool = Tool("get_weather", "Get weather", buildJsonObject { put("type", "object") })
        api(transport).stream(
            model,
            context.copy(tools = listOf(tool)),
            AzureOpenAiResponsesOptions(apiKey = "k", toolChoice = "required")
        ).toList()
        val body = bodyOf(transport)
        assertEquals(
            "get_weather",
            body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content
        )
        assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancellation mid-stream rethrows and never emits an error event`() = runTest {
        val transport = FakeTransport()
        transport.enqueueHangingResponse(
            """{"type":"response.output_item.added","output_index":0,
                "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
            """{"type":"response.output_text.delta","output_index":0,"delta":"partial"}"""
        )
        val collected = mutableListOf<AssistantMessageEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            api(transport)
                .stream(model, context, AzureOpenAiResponsesOptions(apiKey = "k"))
                .toList(collected)
        }
        assertTrue(collected.any { it is AssistantMessageEvent.Start })
        job.cancelAndJoin()
        assertTrue(collected.none { it is AssistantMessageEvent.Error })
        assertTrue(transport.cancelled.value)
    }

    @Test
    fun `terminal event completes the stream`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        val events = api(
            transport
        ).stream(model, context, AzureOpenAiResponsesOptions(apiKey = "k")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("r1", done.message.responseId)
        assertEquals(10, done.message.usage.input)
    }

    @Test
    fun `normalizes the remaining azure host and path shapes`() {
        // pi b8b873b98 azure-openai-base-url: Cognitive Services roots,
        // Microsoft Foundry roots, and already-normalized /openai/v1 paths.
        assertEquals(
            "https://res.cognitiveservices.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://res.cognitiveservices.azure.com")
        )
        assertEquals(
            "https://res.ai.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://res.ai.azure.com")
        )
        assertEquals(
            "https://res.cognitiveservices.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://res.cognitiveservices.azure.com/openai/v1")
        )
    }

    @Test
    fun `session id clamps the prompt cache key and keeps the default user agent`() = runTest {
        // pi b8b873b98 azure-openai-base-url: "clamps prompt_cache_key to
        // OpenAI's 64-character limit" + "uses pi's User-Agent by default".
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        api(transport).stream(
            model,
            context,
            AzureOpenAiResponsesOptions(apiKey = "k", sessionId = "x".repeat(67))
        ).toList()
        assertEquals("x".repeat(64), bodyOf(transport)["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals(
            works.resolve.pathfinder.ai.utils.getPiUserAgent(),
            transport.requests.single().headers["User-Agent"]
        )
    }

    @Test
    fun `tools carry strict false by default and can disable strict`() = runTest {
        // pi b8b873b98 azure-openai-base-url: "honors supportsStrictMode:
        // false" — azure defaults strict mode on (compat absent), so ordinary
        // tools carry an explicit strict:false until disabled.
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        api(transport).stream(
            model.copy(responsesCompat = null),
            context.copy(tools = listOf(Tool("t", "T", buildJsonObject { put("type", "object") }))),
            AzureOpenAiResponsesOptions(apiKey = "k")
        ).toList()
        assertEquals(
            false,
            bodyOf(
                transport
            )["tools"]!!.jsonArray.single().jsonObject["strict"]!!.jsonPrimitive.content.toBoolean()
        )

        val strictOff = FakeTransport()
        strictOff.enqueueResponse(sse(*completed().toTypedArray()))
        api(strictOff).stream(
            model.copy(responsesCompat = OpenAiResponsesCompat(supportsStrictMode = false)),
            context.copy(tools = listOf(Tool("t", "T", buildJsonObject { put("type", "object") }))),
            AzureOpenAiResponsesOptions(apiKey = "k")
        ).toList()
        assertNull(bodyOf(strictOff)["tools"]!!.jsonArray.single().jsonObject["strict"])
    }

    @Test
    fun `streamSimple forwards provider-neutral tool choice`() = runTest {
        // pi b8b873b98 azure-openai-tool-choice: "forwards provider-neutral
        // tool choice from simple options".
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*completed().toTypedArray()))
        api(transport).streamSimple(
            model,
            context.copy(
                tools = listOf(
                    Tool(
                        "read",
                        "Read a file",
                        buildJsonObject {
                            put("type", "object")
                        }
                    )
                )
            ),
            works.resolve.pathfinder.ai.SimpleStreamOptions(
                apiKey = "k",
                toolChoice = works.resolve.pathfinder.ai.SimpleToolChoice.None
            )
        ).toList()
        val body = bodyOf(transport)
        assertEquals("none", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(1, body["tools"]!!.jsonArray.size)
    }
}
