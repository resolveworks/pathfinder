package works.resolve.pathfinder.ai.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ThinkingLevel
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry

class AzureOpenAiCompletionsTest {

    private var realCatalog: ProviderCatalog? = null

    private fun azureProvider() = realAsset().getProvider("azure")!!

    private fun realAsset(): ProviderCatalog {
        val file = File("src/main/assets/models-catalog.json")
        assumeTrue("real catalog asset not found at ${file.absolutePath}", file.isFile)
        var cached = realCatalog
        if (cached == null) {
            cached = ProviderCatalog.parse(file.readText())
            realCatalog = cached
        }
        return cached
    }

    private fun deepSeekModel() = azureProvider().model("deepseek-v4-pro")!!

    private fun models(transport: FakeTransport) = Models(
        listOf(
            azureProvider().toRuntimeProvider(
                transport,
                ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 })
            )
        )
    )

    private fun context() = Context(
        systemPrompt = "sys",
        messages = listOf(UserMessage.ofText("hi"))
    )

    private fun completed() = sse(
        """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
        "[DONE]"
    )

    private fun baseEnv(vararg extra: Pair<String, String>) = mapOf(
        "AZURE_OPENAI_BASE_URL" to "https://my-resource.services.ai.azure.com"
    ) + extra

    private fun bodyOf(transport: FakeTransport): JsonObject = Json.parseToJsonElement(
        transport.requests.last().body.decodeToString()
    ).jsonObject

    private fun run(
        transport: FakeTransport,
        options: SimpleStreamOptions,
        model: works.resolve.pathfinder.ai.Model = deepSeekModel(),
        context: Context = context()
    ): AssistantMessage = kotlinx.coroutines.runBlocking {
        models(transport).completeSimple(model, context, options)
    }

    @Test
    fun `turns thinking on with reasoning_effort instead of deepseek's thinking field`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        run(
            transport,
            SimpleStreamOptions(
                apiKey = "test-key",
                env = baseEnv(),
                reasoning = ThinkingLevel.HIGH
            )
        )

        val body = bodyOf(transport)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertNull(body["thinking"])
    }

    @Test
    fun `clamps thinking levels the deployment does not accept`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        run(
            transport,
            SimpleStreamOptions(apiKey = "test-key", env = baseEnv(), reasoning = ThinkingLevel.MAX)
        )

        assertEquals("high", bodyOf(transport)["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sends no reasoning_effort when no thinking level is requested`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        run(transport, SimpleStreamOptions(apiKey = "test-key", env = baseEnv()))

        val body = bodyOf(transport)
        assertNull(body["reasoning_effort"])
        assertNull(body["thinking"])
    }

    @Test
    fun `omits prompt cache parameters when long retention comes from env`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        run(
            transport,
            SimpleStreamOptions(
                apiKey = "test-key",
                sessionId = "session-env",
                env = baseEnv("PI_CACHE_RETENTION" to "long")
            )
        )

        val body = bodyOf(transport)
        assertNull(body["prompt_cache_key"])
        assertNull(body["prompt_cache_retention"])
    }

    @Test
    fun `omits prompt cache parameters even when long retention is requested`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        run(
            transport,
            SimpleStreamOptions(
                apiKey = "test-key",
                sessionId = "session-1",
                cacheRetention = CacheRetention.LONG,
                env = baseEnv()
            )
        )

        val body = bodyOf(transport)
        assertNull(body["prompt_cache_key"])
        assertNull(body["prompt_cache_retention"])
    }

    @Test
    fun `sends the system prompt under the system role`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        run(transport, SimpleStreamOptions(apiKey = "test-key", env = baseEnv()))

        val first = bodyOf(transport)["messages"]!!.jsonArray.first().jsonObject
        assertEquals("system", first["role"]?.jsonPrimitive?.content)
        assertEquals("sys", first["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `keeps mid-conversation system messages in place`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())
        val resumed = Context(
            systemPrompt = "first",
            messages = listOf(
                UserMessage.ofText("hi"),
                SystemMessage(listOf(TextContent("second"))),
                UserMessage.ofText("again")
            )
        )

        run(
            transport,
            SimpleStreamOptions(apiKey = "test-key", env = baseEnv()),
            context = resumed
        )

        assertEquals(
            listOf("system", "user", "system", "user"),
            bodyOf(transport)["messages"]!!.jsonArray.map {
                it.jsonObject["role"]!!.jsonPrimitive.content
            }
        )
    }

    @Test
    fun `replays reasoning_content on assistant turns so the cached prefix is unchanged`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())
        val assistant = AssistantMessage(
            content = listOf(
                ThinkingContent("internal reasoning", thinkingSignature = "reasoning_content"),
                TextContent("answer")
            ),
            api = "openai-completions",
            provider = "azure",
            model = "deepseek-v4-pro",
            stopReason = StopReason.STOP
        )
        val resumed = Context(
            systemPrompt = "sys",
            messages = listOf(
                UserMessage.ofText("first"),
                assistant,
                UserMessage.ofText("second")
            )
        )

        run(
            transport,
            SimpleStreamOptions(apiKey = "test-key", env = baseEnv()),
            context = resumed
        )

        val assistantBody = bodyOf(transport)["messages"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["role"]?.jsonPrimitive?.content == "assistant" }
        assertEquals(
            "internal reasoning",
            assistantBody["reasoning_content"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `normalizes the azure endpoint the completions client is built with`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        run(transport, SimpleStreamOptions(apiKey = "test-key", env = baseEnv()))

        assertEquals(
            "https://my-resource.services.ai.azure.com/openai/v1/chat/completions",
            transport.requests.last().url
        )
    }

    @Test
    fun `surfaces an unconfigured endpoint as an error event rather than throwing`() {
        val transport = FakeTransport()

        val result = run(
            transport,
            SimpleStreamOptions(apiKey = "test-key", env = emptyMap())
        )

        assertEquals(StopReason.ERROR, result.stopReason)
        assertTrue(result.errorMessage!!.contains("Azure OpenAI base URL is required"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `sends the catalog id as the request model`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        val result = run(transport, SimpleStreamOptions(apiKey = "test-key", env = baseEnv()))

        assertEquals("deepseek-v4-pro", bodyOf(transport)["model"]?.jsonPrimitive?.content)
        assertEquals("deepseek-v4-pro", result.model)
    }

    @Test
    fun `sends the mapped deployment name while keeping the catalog id on the message`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        val result = run(
            transport,
            SimpleStreamOptions(
                apiKey = "test-key",
                env = baseEnv(
                    "AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "deepseek-v4-pro=my-deepseek"
                )
            )
        )

        assertEquals("my-deepseek", bodyOf(transport)["model"]?.jsonPrimitive?.content)
        assertEquals("deepseek-v4-pro", result.model)
    }

    @Test
    fun `passes the deployment name through a caller's onPayload`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())
        var seenModel: String? = null

        run(
            transport,
            SimpleStreamOptions(
                apiKey = "test-key",
                env = baseEnv(
                    "AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "deepseek-v4-pro=my-deepseek"
                ),
                onPayload = { payload, _ ->
                    seenModel = payload["model"]?.jsonPrimitive?.content
                    JsonObject(payload + ("temperature" to JsonPrimitive(0.1)))
                }
            )
        )

        assertEquals("my-deepseek", seenModel)
        assertEquals("my-deepseek", bodyOf(transport)["model"]?.jsonPrimitive?.content)
        assertEquals(0.1, bodyOf(transport)["temperature"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `still routes responses models to the responses api`() {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"type":"response.completed","response":{"id":"r1","status":"completed",
                    "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
                "[DONE]"
            )
        )

        val result = run(
            transport,
            SimpleStreamOptions(apiKey = "test-key", env = baseEnv()),
            model = azureProvider().model("gpt-4o-mini")!!
        )

        assertEquals(StopReason.STOP, result.stopReason)
        assertEquals(
            "https://my-resource.services.ai.azure.com/openai/v1/responses?api-version=v1",
            transport.requests.last().url
        )
    }

    @Test
    fun `routes openai-completions models to chat completions`() {
        val transport = FakeTransport()
        transport.enqueueResponse(completed())

        run(transport, SimpleStreamOptions(apiKey = "test-key", env = baseEnv()))

        assertTrue(transport.requests.last().url.endsWith("/chat/completions"))
        assertEquals("deepseek-v4-pro", bodyOf(transport)["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `catalog azure deepseek carries foundry completions compat`() {
        val model = deepSeekModel()
        assertEquals("openai-completions", model.api)
        assertEquals("azure", model.provider)
        assertEquals(false, model.compat.supportsDeveloperRole)
        assertEquals(false, model.compat.supportsLongCacheRetention)
        assertTrue(model.compat.supportsMidConvoSystemMessages)
        assertFalse(model.compat.requiresThinkingAsText)
        assertEquals(
            null,
            model.thinkingLevelMap?.forLevel(works.resolve.pathfinder.ai.ModelThinkingLevel.MAX)
        )
    }
}
