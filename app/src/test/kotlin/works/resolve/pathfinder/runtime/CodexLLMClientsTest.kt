package works.resolve.pathfinder.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.models.ReasoningSummary
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexLLMClientsTest {

    private val expectedExtras = mapOf(
        "chatgpt-account-id" to "account-123",
        "originator" to "pathfinder",
        "OpenAI-Beta" to "responses=experimental",
    )

    /** Stub [KoogHttpClient] capturing the headers and body seen by each method. */
    private class RecordingHttpClient : KoogHttpClient {
        override val clientName = "recording"

        val getHeaders = mutableListOf<Map<String, String>>()
        val postHeaders = mutableListOf<Map<String, String>>()
        val sseHeaders = mutableListOf<Map<String, String>>()
        val linesHeaders = mutableListOf<Map<String, String>>()
        val sseCalls = mutableListOf<Pair<String, String>>() // path to body
        val linesCalls = mutableListOf<Pair<String, String>>() // path to body
        var lineResponses: List<String> = emptyList()

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R {
            getHeaders.add(headers)
            error("unused")
        }

        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R {
            postHeaders.add(headers)
            error("unused")
        }

        override fun <T : Any, R : Any, O : Any> sse(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<O> {
            sseHeaders.add(headers)
            sseCalls.add(path to requestBody.toString())
            return emptyFlow()
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> {
            linesHeaders.add(headers)
            linesCalls.add(path to requestBody.toString())
            return flowOf(*lineResponses.toTypedArray())
        }

        override fun close() {}
    }

    /** Factory recording its configuration and handing out [RecordingHttpClient]s. */
    private class RecordingFactory : KoogHttpClient.Factory {
        var baseUrl: String? = null
            private set
        var headers: Map<String, String> = emptyMap()
            private set
        val clients = mutableListOf<RecordingHttpClient>()

        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient {
            this.baseUrl = baseUrl
            this.headers = headers
            return RecordingHttpClient().also(clients::add)
        }
    }

    // ------------------------------------------------------------------
    // 1. Decorator header merging
    // ------------------------------------------------------------------

    @Test
    fun decoratorMergesExtraHeadersIntoEveryMethodAndPerCallHeadersWin() = runTest {
        val recording = RecordingHttpClient()
        val decorated = CodexLLMClients.ChatGPTBackendHeaderDecorator(recording, expectedExtras)

        runCatching { decorated.get("p", String::class, headers = mapOf("per-call" to "1")) }
        runCatching { decorated.post("p", "b", String::class, String::class, headers = mapOf("per-call" to "1")) }
        runCatching { decorated.sse("p", "b", String::class, { true }, { it }, { it }, headers = mapOf("per-call" to "1")).toList() }
        runCatching { decorated.lines("p", "b", String::class, headers = mapOf("per-call" to "1")).toList() }

        val calls = recording.getHeaders + recording.postHeaders + recording.sseHeaders + recording.linesHeaders
        assertEquals(4, calls.size)
        calls.forEach { headers ->
            expectedExtras.forEach { (name, value) ->
                assertEquals(value, headers[name], "missing $name in $headers")
            }
            assertEquals("1", headers["per-call"])
        }

        // A per-call value overrides the decorator's value for the same name.
        runCatching { decorated.get("p", String::class, headers = mapOf("originator" to "override-me")) }
        assertEquals("override-me", recording.getHeaders.last()["originator"])
    }

    @Test
    fun codexSseUsesRawLinesAndParsesDataRecordsWithoutContentTypeValidation() = runTest {
        val recording = RecordingHttpClient().apply {
            lineResponses = listOf(
                ": keep-alive",
                "event: response.output_text.delta",
                "data: one",
                "data: skip",
                "data: [DONE]",
            )
        }
        val decorated = CodexLLMClients.ChatGPTBackendHeaderDecorator(recording, expectedExtras)

        val values = decorated.sse(
            path = "codex/responses",
            requestBody = "body",
            requestBodyType = String::class,
            dataFilter = { it != "skip" },
            decodeStreamingResponse = { it.uppercase() },
            processStreamingChunk = { "<$it>" },
        ).toList()

        assertEquals(listOf("<ONE>"), values)
        assertTrue(recording.sseCalls.isEmpty(), "Ktor-style SSE must be bypassed")
        assertEquals(listOf("codex/responses" to "body"), recording.linesCalls)
        assertEquals("text/event-stream", recording.linesHeaders.single()["Accept"])
        assertEquals("application/json", recording.linesHeaders.single()["Content-Type"])
    }

    // ------------------------------------------------------------------
    // 2. Wire-level request through OpenAILLMClient.executeStreaming
    // ------------------------------------------------------------------

    @Test
    fun streamingRequestTargetsCodexResponsesPathWithCodexHeadersAndBody() = runTest {
        val factory = RecordingFactory()
        val client = CodexLLMClients.create(
            accessToken = "test-token",
            accountId = "account-123",
            httpClientFactory = factory,
        )

        // Factory configuration carries the base URL and bearer token.
        assertEquals("https://chatgpt.com/backend-api", factory.baseUrl)
        assertEquals("Bearer test-token", factory.headers["Authorization"])

        val prompt = Prompt(
            messages = listOf(Message.User("hello", RequestMetaInfo.Empty)),
            id = "test-prompt",
            params = CodexLLMClients.promptParams("session-42"),
        )

        // A hand-declared catalog model (see CodingPlanModels.kt): its
        // Responses capability is what routes the client to the codex path.
        val model = CodexModels.descriptors.last().model

        // Collecting may throw on the canned empty stream; the wire arguments
        // are captured at flow construction inside executeStreaming.
        runCatching { client.executeStreaming(prompt, model).toList() }

        val http = factory.clients.single()
        assertTrue(http.sseCalls.isEmpty(), "Codex must bypass strict Content-Type SSE validation")
        val headers = http.linesHeaders.single()
        expectedExtras.forEach { (name, value) ->
            assertEquals(value, headers[name], "missing $name in streaming headers")
        }
        assertEquals("text/event-stream", headers["Accept"])
        assertEquals("application/json", headers["Content-Type"])

        val (path, body) = http.linesCalls.single()
        assertEquals("codex/responses", path)
        assertTrue(body.contains("\"store\":false"), "expected store:false in body: $body")
        assertTrue(body.contains("You are a helpful assistant."), "expected instructions in body")
        assertTrue(body.contains("reasoning.encrypted_content"), "expected reasoning include in body")
        assertTrue(body.contains("\"summary\":\"auto\""), "expected reasoning summary request in body: $body")
        assertTrue(body.contains("session-42"), "expected prompt cache key in body")
    }

    // ------------------------------------------------------------------
    // 3. promptParams fields
    // ------------------------------------------------------------------

    @Test
    fun promptParamsCarriesStoreIncludeCacheKeyAndInstructions() {
        val params = CodexLLMClients.promptParams("s1") as OpenAIResponsesParams

        assertEquals(false, params.store)
        assertEquals(listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT), params.include)
        // Summaries are requested (AUTO) but effort is left to the backend
        // default — Pathfinder has no thinking-level setting to map onto it.
        assertNull(params.reasoning?.effort)
        assertEquals(ReasoningSummary.AUTO, params.reasoning?.summary)
        assertEquals("s1", params.promptCacheKey)
        assertEquals(
            mapOf<String, JsonElement>("instructions" to JsonPrimitive("You are a helpful assistant.")),
            params.additionalProperties,
        )
    }
}
