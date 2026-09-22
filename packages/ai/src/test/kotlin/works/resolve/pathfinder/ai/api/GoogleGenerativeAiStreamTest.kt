package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ThinkingLevel
import works.resolve.pathfinder.ai.ThinkingLevelMap
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import works.resolve.pathfinder.ai.utils.normalizeContext

class GoogleGenerativeAiStreamTest {

    private val model = geminiModel()
    private val context = normalizeContext(Context(messages = listOf(UserMessage.ofText("hi"))))

    private fun geminiModel(
        id: String = "gemini-2.5-flash",
        baseUrl: String = "",
        thinkingLevelMap: ThinkingLevelMap? = null
    ) = Model(
        id = id, name = id, api = "google-generative-ai", provider = "google",
        baseUrl = baseUrl, reasoning = true,
        thinkingLevelMap = thinkingLevelMap,
        input = listOf(
            InputModality.TEXT,
            InputModality.IMAGE
        ),
        contextWindow = 128000, maxTokens = 8192
    )

    /** The regenerated catalog map for gemini-3.1-pro-preview. */
    private val gemini31ProMap = ThinkingLevelMap.of(
        ModelThinkingLevel.OFF to null,
        ModelThinkingLevel.MINIMAL to null,
        ModelThinkingLevel.LOW to "low",
        ModelThinkingLevel.MEDIUM to "medium",
        ModelThinkingLevel.HIGH to "high"
    )

    /** The regenerated catalog map for gemma-4-31b-it. */
    private val gemma4Map = ThinkingLevelMap.of(
        ModelThinkingLevel.OFF to null,
        ModelThinkingLevel.MINIMAL to "MINIMAL",
        ModelThinkingLevel.LOW to null,
        ModelThinkingLevel.MEDIUM to null,
        ModelThinkingLevel.HIGH to "HIGH"
    )

    private fun api(transport: FakeTransport) = GoogleGenerativeAiApi(
        transport,
        works.resolve.pathfinder.ai.utils.ProviderRetry(sleep = {
        }, clock = FakeClock(0L), random = { 0.0 }),
        clock = FakeClock(startEpochMs = 1_770_000_000_000L)
    )

    private suspend fun events(transport: FakeTransport) = api(transport)
        .stream(model, context, GoogleGenerativeAiApi.GoogleOptions(apiKey = "test-key"))
        .toList()

    @Test
    fun `streams text with start, delta, end, done ordering`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]},"finishReason":"STOP"}],
                    "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"totalTokenCount":12},
                    "responseId":"resp-1"}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]}}],
                    "usageMetadata":{"promptTokenCount":10,"cachedContentTokenCount":4,
                    "candidatesTokenCount":4,"thoughtsTokenCount":2,"totalTokenCount":16}}"""
            )
        )
        val events = events(transport)

        assertIs<AssistantMessageEvent.Start>(events.first())
        val delta = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("Hel", "lo"), delta.map { it.delta })
        assertEquals(0, delta.first().contentIndex)
        val textEnd = events.filterIsInstance<AssistantMessageEvent.TextEnd>().single()
        assertEquals("Hello", textEnd.content)

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        // usageMetadata: input = prompt - cached, output = candidates + thoughts,
        // cacheRead = cached, reasoning = thoughts; total straight from the wire.
        assertEquals(6, done.message.usage.input)
        assertEquals(6, done.message.usage.output)
        assertEquals(4, done.message.usage.cacheRead)
        assertEquals(2, done.message.usage.reasoning)
        assertEquals(16, done.message.usage.totalTokens)
        assertEquals("resp-1", done.message.responseId)
        assertEquals(listOf(TextContent("Hello")), done.message.content)
    }

    @Test
    fun `thought parts stream as interleaved thinking and text blocks`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[
                    {"text":"thinking hard","thought":true,"thoughtSignature":"AAAAAAAAAAAAAAAAAAAAAA=="},
                    {"text":"answer"},
                    {"text":" more","thought":true}
                    ]},"finishReason":"STOP"}]}"""
            )
        )
        val events = events(transport)
        val thinkingStarts = events.filterIsInstance<AssistantMessageEvent.ThinkingStart>()
        val thinkingEnds = events.filterIsInstance<AssistantMessageEvent.ThinkingEnd>()
        val textEnd = events.filterIsInstance<AssistantMessageEvent.TextEnd>().single()
        assertEquals(listOf(0, 2), thinkingStarts.map { it.contentIndex })
        assertEquals("thinking hard", thinkingEnds[0].content)
        assertEquals(0, thinkingEnds[0].contentIndex)
        assertEquals(" more", thinkingEnds[1].content)
        assertEquals(2, thinkingEnds[1].contentIndex)
        assertEquals("answer", textEnd.content)
        assertEquals(1, textEnd.contentIndex)
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val content = done.message.content
        assertEquals(3, content.size)
        assertEquals("thinking hard", (content[0] as ThinkingContent).thinking)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", (content[0] as ThinkingContent).thinkingSignature)
        assertEquals("answer", (content[1] as TextContent).text)
        assertEquals(" more", (content[2] as ThinkingContent).thinking)
    }

    @Test
    fun `functionCall parts become complete tool calls with generated ids`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[
                    {"text":"let me check"},
                    {"functionCall":{"name":"bash","args":{"command":"ls"}}}
                    ]},"finishReason":"STOP"}]}"""
            )
        )
        val events = events(transport)
        val toolEnd = events.filterIsInstance<AssistantMessageEvent.ToolCallEnd>().single()
        val call = toolEnd.toolCall
        assertTrue(call.id.startsWith("bash_"), "generated id was ${call.id}")
        assertEquals("bash", call.name)
        assertEquals(buildJsonObject { put("command", "ls") }, call.arguments)
        val toolDelta = events.filterIsInstance<AssistantMessageEvent.ToolCallDelta>().single()
        assertEquals("""{"command":"ls"}""", toolDelta.delta)

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        // stop upgrade: STOP with tool calls becomes TOOL_USE (upstream rule).
        assertEquals(StopReason.TOOL_USE, done.reason)
        assertEquals("STOP", done.message.rawStopReason)
    }

    @Test
    fun `provided duplicate tool call ids are regenerated`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[
                    {"functionCall":{"id":"dup","name":"a","args":{}}},
                    {"functionCall":{"id":"dup","name":"b","args":{}}}
                    ]},"finishReason":"STOP"}]}"""
            )
        )
        val events = events(transport)
        val calls = events.filterIsInstance<AssistantMessageEvent.ToolCallEnd>().map { it.toolCall }
        assertEquals(2, calls.size)
        assertEquals("dup", calls[0].id)
        assertTrue(calls[1].id.startsWith("b_"))
    }

    @Test
    fun `max tokens finish reason with a tool call stays length`() = runTest {
        // Mirrors pi's google-raw-stop-reason: only STOP upgrades to toolUse;
        // MAX_TOKENS keeps length even when a function call was streamed.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[
                    {"functionCall":{"id":"call-1","name":"echo","args":{"value":"truncated"}}}
                    ]},"finishReason":"MAX_TOKENS"}]}"""
            )
        )
        val events = events(transport)
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.LENGTH, done.reason)
        assertEquals("MAX_TOKENS", done.message.rawStopReason)
        assertTrue(done.message.content.any { it is ToolCall })
    }

    @Test
    fun `error finish reason terminates with an error event carrying rawStopReason`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        val events = events(transport)
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals("Provider stopped with: SAFETY", error.error.errorMessage)
        assertEquals("SAFETY", error.error.rawStopReason)
    }

    @Test
    fun `stream without finish reason is an error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""")
        )
        val events = events(transport)
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("Google stream ended without a finish reason", error.error.errorMessage)
    }

    @Test
    fun `missing api key is a terminal error event`() = runTest {
        val transport = FakeTransport()
        val events = api(transport)
            .stream(model, context, GoogleGenerativeAiApi.GoogleOptions())
            .toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals("No API key for provider: google", error.error.errorMessage)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `http error surfaces status and whole body`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            400,
            """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""
        )
        val events = events(transport)
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals(
            """400: {"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}""",
            error.error.errorMessage
        )
    }

    @Test
    fun `request shaping uses default base url, api key header, and merged headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        val headerModel = model.copy(
            headers = mapOf("X-Model" to "model-value")
        )
        api(transport).stream(
            headerModel,
            context,
            GoogleGenerativeAiApi.GoogleOptions(
                apiKey = "k",
                headers = mapOf("x-model" to "request-value")
            )
        ).toList()

        val request = transport.requests.single()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            request.url
        )
        assertEquals("k", request.headers["x-goog-api-key"])
        assertEquals("request-value", request.headers["x-model"])
        assertEquals(getPiUserAgent(), request.headers["User-Agent"])
        assertNull(request.bearerToken)

        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertTrue(body.containsKey("contents"))
        assertEquals(
            "user",
            body["contents"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `custom base url replaces the default and keeps its version path`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            geminiModel(baseUrl = "https://proxy.example/v1beta"),
            context,
            GoogleGenerativeAiApi.GoogleOptions(apiKey = "k")
        ).toList()
        assertEquals(
            "https://proxy.example/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            transport.requests.single().url
        )
    }

    @Test
    fun `system prompt, tools, and tool choice shape the request`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            model,
            normalizeContext(
                Context(
                    systemPrompt = "be brief",
                    messages = listOf(UserMessage.ofText("hi")),
                    tools = listOf(
                        works.resolve.pathfinder.ai.Tool(
                            name = "bash",
                            description = "run",
                            parameters = Json.parseToJsonElement("""{"type":"object"}""")
                        )
                    )
                )
            ),
            GoogleGenerativeAiApi.GoogleOptions(
                apiKey = "k",
                toolChoice = "any",
                temperature = 0.5,
                maxTokens = 128
            )
        ).toList()

        val body = Json.parseToJsonElement(
            transport.requests.single().body.decodeToString()
        ).jsonObject
        // The SDK expands a string systemInstruction config into a user
        // Content on the wire (tContent → contentToMldev).
        assertEquals(
            """{"parts":[{"text":"be brief"}],"role":"user"}""",
            body["systemInstruction"].toString()
        )
        assertTrue(body.containsKey("tools"))
        assertEquals(
            "ANY",
            body["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"]!!
                .jsonPrimitive.content
        )
        val generationConfig = body["generationConfig"]!!.jsonObject
        assertEquals(0.5, generationConfig["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(128, generationConfig["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `streamSimple maps reasoning levels through google budgets`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            model,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = ThinkingLevel.MEDIUM)
        ).toList()

        val body = Json.parseToJsonElement(
            transport.requests.single().body.decodeToString()
        ).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(true, thinkingConfig["includeThoughts"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(8192, thinkingConfig["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `streamSimple without reasoning disables thinking`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(model, context, SimpleStreamOptions(apiKey = "k")).toList()

        val body = Json.parseToJsonElement(
            transport.requests.single().body.decodeToString()
        ).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(0, thinkingConfig["thinkingBudget"]!!.jsonPrimitive.content.toInt())
        assertNull(thinkingConfig["includeThoughts"])
    }

    @Test
    fun `retryable failures are retried before the stream starts`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(503, "busy")
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        val done = api(transport)
            .stream(
                model,
                context,
                GoogleGenerativeAiApi.GoogleOptions(apiKey = "k", maxRetries = 1)
            )
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Done>(done)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `chatapi options path resolves gemini3 thinking levels`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            geminiModel(id = "gemini-3.1-pro-preview", thinkingLevelMap = gemini31ProMap),
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = ThinkingLevel.MEDIUM)
        ).toList()
        val body = Json.parseToJsonElement(
            transport.requests.single().body.decodeToString()
        ).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("MEDIUM", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        assertNull(thinkingConfig["thinkingBudget"])
    }

    @Test
    fun `gemini3 pro maps low and minimal to LOW`() = runTest {
        for (level in listOf(ThinkingLevel.LOW, ThinkingLevel.MINIMAL)) {
            val transport = FakeTransport()
            transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
            api(transport).streamSimple(
                geminiModel(id = "gemini-3.1-pro-preview", thinkingLevelMap = gemini31ProMap),
                context,
                SimpleStreamOptions(apiKey = "k", reasoning = level)
            ).toList()
            val body = Json.parseToJsonElement(
                transport.requests.single().body.decodeToString()
            ).jsonObject
            val thinkingConfig =
                body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
            // Gemini 3 Pro has no MINIMAL level: minimal and low both floor to LOW.
            assertEquals("LOW", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `gemini3 flash maps minimal and low levels and disables to MINIMAL`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            geminiModel(
                id = "gemini-3-flash-preview",
                thinkingLevelMap = ThinkingLevelMap.of(
                    ModelThinkingLevel.OFF to null,
                    ModelThinkingLevel.MINIMAL to "minimal",
                    ModelThinkingLevel.LOW to "low",
                    ModelThinkingLevel.MEDIUM to "medium",
                    ModelThinkingLevel.HIGH to "high"
                )
            ),
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = ThinkingLevel.MINIMAL)
        ).toList()
        var body = Json.parseToJsonElement(
            transport.requests.single().body.decodeToString()
        ).jsonObject
        assertEquals(
            "MINIMAL",
            body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!
                .jsonPrimitive.content
        )

        // Thinking-off still sends the lowest supported level (Gemini 3 Flash
        // cannot fully disable thinking) without includeThoughts.
        val disableTransport = FakeTransport()
        disableTransport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(disableTransport).streamSimple(
            geminiModel(
                id = "gemini-3-flash-preview",
                thinkingLevelMap = ThinkingLevelMap.of(
                    ModelThinkingLevel.OFF to null,
                    ModelThinkingLevel.MINIMAL to "minimal",
                    ModelThinkingLevel.LOW to "low",
                    ModelThinkingLevel.MEDIUM to "medium",
                    ModelThinkingLevel.HIGH to "high"
                )
            ),
            context,
            SimpleStreamOptions(apiKey = "k")
        ).toList()
        body =
            Json.parseToJsonElement(
                disableTransport.requests.single().body.decodeToString()
            ).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("MINIMAL", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        assertNull(thinkingConfig["includeThoughts"])
        assertNull(thinkingConfig["thinkingBudget"])
    }

    @Test
    fun `gemini3 pro thinking-off cannot fully disable and falls back to LOW`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            geminiModel(id = "gemini-3.1-pro-preview", thinkingLevelMap = gemini31ProMap),
            context,
            SimpleStreamOptions(apiKey = "k")
        ).toList()
        val body = Json.parseToJsonElement(
            transport.requests.single().body.decodeToString()
        ).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("LOW", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        assertNull(thinkingConfig["includeThoughts"])
    }

    @Test
    fun `gemma4 maps minimal to MINIMAL and high to HIGH`() = runTest {
        for ((level, expected) in listOf(
            ThinkingLevel.MINIMAL to "MINIMAL",
            ThinkingLevel.HIGH to "HIGH"
        )) {
            val transport = FakeTransport()
            transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
            api(transport).streamSimple(
                geminiModel(id = "gemma-4-31b-it", thinkingLevelMap = gemma4Map),
                context,
                SimpleStreamOptions(apiKey = "k", reasoning = level)
            ).toList()
            val body = Json.parseToJsonElement(
                transport.requests.single().body.decodeToString()
            ).jsonObject
            assertEquals(
                expected,
                body["generationConfig"]!!.jsonObject["thinkingConfig"]!!
                    .jsonObject["thinkingLevel"]!!
                    .jsonPrimitive.content
            )
        }
    }

    @Test
    fun `gemma4 thinking-off uses MINIMAL level without includeThoughts`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).streamSimple(
            geminiModel(id = "gemma-4-31b-it", thinkingLevelMap = gemma4Map),
            context,
            SimpleStreamOptions(apiKey = "k")
        ).toList()
        val body = Json.parseToJsonElement(
            transport.requests.single().body.decodeToString()
        ).jsonObject
        val thinkingConfig = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("MINIMAL", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        assertNull(thinkingConfig["includeThoughts"])
        assertNull(thinkingConfig["thinkingBudget"])
    }

    @Test
    fun `caller supplied api key header is not overridden by the derived one`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            model.copy(headers = mapOf("X-Model" to "m")),
            context,
            GoogleGenerativeAiApi.GoogleOptions(
                apiKey = "derived-key",
                headers = mapOf("X-Goog-Api-Key" to "caller-key")
            )
        ).toList()

        val headers = transport.requests.single().headers
        // Exactly one key header, the caller's — the SDK's auth only adds its
        // derived key when none is present (case-insensitive).
        val keyHeaders = headers.filterKeys { it.lowercase() == "x-goog-api-key" }
        assertEquals(1, keyHeaders.size, "duplicate key headers: $headers")
        assertEquals("caller-key", keyHeaders.values.single())
    }

    @Test
    fun `model supplied api key header suppresses the derived one`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            model.copy(headers = mapOf("x-goog-api-key" to "model-key")),
            context,
            GoogleGenerativeAiApi.GoogleOptions(apiKey = "derived-key")
        ).toList()
        assertEquals("model-key", transport.requests.single().headers["x-goog-api-key"])
    }

    @Test
    fun `unknown finish reason fails mid stream`() = runTest {
        // pi's exhaustive FinishReason switch throws for values outside the
        // SDK enum, so the stream stops at the offending chunk.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[{"text":"a"}]}}]}""",
                """{"candidates":[{"finishReason":"SOME_NEW_REASON"}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"after"}]},"finishReason":"STOP"}]}"""
            )
        )
        val events = events(transport)
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("Unhandled stop reason: SOME_NEW_REASON", error.error.errorMessage)
        assertEquals("SOME_NEW_REASON", error.error.rawStopReason)
        // The chunk after the unknown reason was never consumed.
        assertTrue(
            events.filterIsInstance<AssistantMessageEvent.TextDelta>().map { it.delta } ==
                listOf("a"),
            "stream must stop at the unknown finish reason"
        )
    }

    @Test
    fun `empty finish reason counts as absent`() = runTest {
        // pi's truthiness guard (`if (candidate?.finishReason)`) skips "",
        // leaving the stream pending.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[{"text":"a"}]},"finishReason":""}]}"""
            )
        )
        val events = events(transport)
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("Google stream ended without a finish reason", error.error.errorMessage)
        assertNull(error.error.rawStopReason)
    }

    @Test
    fun `generated tool call ids use per call wall time`() = runTest {
        // pi reads Date.now() per generated id, so two calls in one stream
        // carry different timestamps once the clock moves.
        val clock = TickingClock(1_770_000_000_000L)
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[
                    {"functionCall":{"name":"a","args":{}}},
                    {"functionCall":{"name":"b","args":{}}}
                    ]},"finishReason":"STOP"}]}"""
            )
        )
        val calls = GoogleGenerativeAiApi(
            transport,
            works.resolve.pathfinder.ai.utils.ProviderRetry(sleep = {
            }, clock = FakeClock(0L), random = { 0.0 }),
            clock = clock
        ).stream(model, context, GoogleGenerativeAiApi.GoogleOptions(apiKey = "k"))
            .toList()
            .filterIsInstance<AssistantMessageEvent.ToolCallEnd>()
            .map { it.toolCall }

        val stamps = calls.map { it.id.split("_")[1].toLong() }
        assertEquals(2, calls.size)
        assertTrue(stamps[0] != stamps[1], "ids shared one timestamp: ${calls.map { it.id }}")
    }

    @Test
    fun `empty generationConfig is still serialized`() = runTest {
        // The SDK always serializes the config object, so the wire carries
        // "generationConfig": {} even with nothing to configure.
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            model.copy(reasoning = false),
            context,
            GoogleGenerativeAiApi.GoogleOptions(apiKey = "k")
        ).toList()
        val body = Json.parseToJsonElement(
            transport.requests.single().body.decodeToString()
        ).jsonObject
        assertEquals(JsonObject(emptyMap()), body["generationConfig"])
    }

    @Test
    fun `explicit request headers override the default User-Agent`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"candidates":[{"finishReason":"STOP"}]}"""))
        api(transport).stream(
            model,
            context,
            GoogleGenerativeAiApi.GoogleOptions(
                apiKey = "k",
                headers = mapOf("User-Agent" to "custom-agent")
            )
        ).toList()
        assertEquals("custom-agent", transport.requests.single().headers["User-Agent"])
    }

    @Test
    fun `cancellation mid-stream never emits an error event`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[{"text":"a"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"b"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"c"}]},"finishReason":"STOP"}]}"""
            )
        )
        val events = api(transport)
            .stream(model, context, GoogleGenerativeAiApi.GoogleOptions(apiKey = "k"))
            .take(3) // Start, TextStart, first TextDelta
            .toList()
        assertTrue(
            events.none {
                it is AssistantMessageEvent.Error
            },
            "cancellation must not emit Error"
        )
        assertTrue(transport.cancelled.value, "transport must observe cancellation")
    }

    @Test
    fun `usage counts use shared lenient int semantics`() = runTest {
        // Deliberately awkward fixture: quoted numerals parse, a float yields
        // null (-> 0) rather than truncating, and missing fields are 0. Google
        // sends proper JSON numbers here, so the float rejection is tightening,
        // not a regression.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}],
                    "usageMetadata":{"promptTokenCount":"10","candidatesTokenCount":2.5,
                    "totalTokenCount":12}}"""
            )
        )
        val events = events(transport)
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(10, done.message.usage.input)
        assertEquals(0, done.message.usage.output)
        assertEquals(0, done.message.usage.reasoning)
        assertEquals(12, done.message.usage.totalTokens)
    }

    /** Advances one millisecond on every read, exposing per-call clock use. */
    private class TickingClock(startEpochMs: Long) : Clock {
        private var current = startEpochMs

        override fun now(): Instant = Instant.fromEpochMilliseconds(current++)
    }
}
