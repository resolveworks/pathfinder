package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.ToolChoice
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.TestCatalogs
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.transport.SseEvent
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class OpenAiCompletionsStreamTest {

    private val model = TestCatalogs.GLM_5_2
    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun api(transport: FakeTransport) = OpenAiCompletionsApi(
        transport,
        works.resolve.pathfinder.ai.utils.ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 }),
        nowMs = { 1_770_000_000_000L },
    )

    @Test
    fun `cloudflare placeholders resolve from env before transport`() = runTest {
        val transport = FakeTransport()
        val cfModel = TestCatalogs.CLOUDFLARE.models.single()
        // Complete credential values resolve the URL and reach the transport.
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val done = api(transport)
            .stream(
                cfModel,
                context,
                OpenAiCompletionsOptions(
                    apiKey = "cf-key",
                    env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
                ),
            )
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Done>(done)
        assertEquals(
            "https://gateway.test/v1/acc/gw/compat/chat/completions",
            transport.requests.single().url,
        )
    }

    @Test
    fun `header auth replaces model headers and needs no api key`() = runTest {
        val transport = FakeTransport()
        val headerModel = model.copy(
            headers = mapOf("X-Model-Header" to "model-value", "Accept" to "text/plain"),
        )
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val done = api(transport)
            .stream(
                headerModel,
                context,
                OpenAiCompletionsOptions(
                    headers = mapOf(
                        "cf-aig-authorization" to "Bearer cf-key",
                        "authorization" to null,
                        "x-model-header" to "request-value",
                    ),
                ),
            )
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Done>(done)
        val request = transport.requests.single()
        assertNull(request.bearerToken)
        assertEquals("Bearer cf-key", request.headers["cf-aig-authorization"])
        // Case-insensitive: the explicit request value replaced the model's
        // differently-cased header, and no Authorization header is sent.
        assertEquals("request-value", request.headers["x-model-header"])
        assertTrue(request.headers.keys.none { it.equals("authorization", ignoreCase = true) })
        // Mandatory Accept header survives and cannot be overridden.
        assertEquals("text/event-stream", request.headers["Accept"])
    }

    @Test
    fun `blank auth header does not stand in for an api key`() = runTest {
        val transport = FakeTransport()
        val events = api(transport)
            .stream(
                model,
                context,
                OpenAiCompletionsOptions(headers = mapOf("cf-aig-authorization" to " ")),
            )
            .toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue(error.partial.errorMessage!!.contains("No API key"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `ordinary api key becomes the bearer token with mandatory accept header`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val done = api(transport)
            .stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key"))
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Done>(done)
        val request = transport.requests.single()
        assertEquals("test-key", request.bearerToken)
        assertEquals("text/event-stream", request.headers["Accept"])
    }

    @Test
    fun `streams text with start delta end done`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"resp-1","choices":[{"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"delta":{"content":"lo"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()

        assertIs<AssistantMessageEvent.Start>(events.first())
        val deltas = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("Hel", "lo"), deltas.map { it.delta })
        assertIs<AssistantMessageEvent.TextEnd>(events.filter { it is AssistantMessageEvent.TextEnd }.single())
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        val text = assertIs<TextContent>(done.message.content.single())
        assertEquals("Hello", text.text)
        assertEquals("resp-1", done.message.responseId)
    }

    @Test
    fun `all snapshots share one request-start timestamp`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"a"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        assertEquals(1_770_000_000_000L, events.first().partial.timestamp)
        assertEquals(1_770_000_000_000L, events.last().partial.timestamp)
    }

    @Test
    fun `reasoning_content becomes thinking content`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"reasoning_content":"think "}}]}""",
                """{"choices":[{"delta":{"reasoning_content":"hard"}}]}""",
                """{"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val thinkingDeltas = events.filterIsInstance<AssistantMessageEvent.ThinkingDelta>()
        assertEquals(listOf("think ", "hard"), thinkingDeltas.map { it.delta })
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val thinking = assertIs<ThinkingContent>(done.message.content[0])
        assertEquals("think hard", thinking.thinking)
        assertEquals("reasoning_content", thinking.thinkingSignature)
        val text = assertIs<TextContent>(done.message.content[1])
        assertEquals("answer", text.text)
    }

    @Test
    fun `first non-empty reasoning field wins to avoid duplication`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"reasoning_content":"a","reasoning":"b"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val thinking = assertIs<ThinkingContent>(done.message.content.single())
        assertEquals("a", thinking.thinking)
    }

    @Test
    fun `fragmented tool calls accumulate raw arguments by stream index`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"/tmp\"}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                """{"usage":{"prompt_tokens":5,"completion_tokens":9},"choices":[]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.TOOL_USE, done.reason)
        val call = assertIs<ToolCall>(done.message.content.single())
        assertEquals("call_1", call.id)
        assertEquals("read_file", call.name)
        assertEquals("""{"path":"/tmp"}""", call.arguments)
        // ToolCallEnd emits the complete accumulated raw string.
        val end = assertIs<AssistantMessageEvent.ToolCallEnd>(events.filter { it is AssistantMessageEvent.ToolCallEnd }.single())
        assertEquals("""{"path":"/tmp"}""", end.toolCall.arguments)
    }

    @Test
    fun `interleaved text and tool calls keep block order`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"Let me check."}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"a","arguments":"{}"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"b","arguments":"{}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(3, done.message.content.size)
        assertIs<TextContent>(done.message.content[0])
        assertEquals("a", assertIs<ToolCall>(done.message.content[1]).name)
        assertEquals("b", assertIs<ToolCall>(done.message.content[2]).name)
        // Tool call end events carry their own block index.
        val toolEnds = events.filterIsInstance<AssistantMessageEvent.ToolCallEnd>()
        assertEquals(listOf(1, 2), toolEnds.map { it.contentIndex })
    }

    @Test
    fun `tool call fragments after a later index still route to the right block`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"a","arguments":"{\"x\":1"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"b","arguments":"{\"y\":2}"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":",\"z\":3}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("""{"x":1,"z":3}""", assertIs<ToolCall>(done.message.content[0]).arguments)
        assertEquals("""{"y":2}""", assertIs<ToolCall>(done.message.content[1]).arguments)
    }

    @Test
    fun `usage accounting maps cache and reasoning tokens with cost`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""",
                """{"usage":{"prompt_tokens":110,"completion_tokens":50,"prompt_tokens_details":{"cached_tokens":10,"cache_write_tokens":5},"completion_tokens_details":{"reasoning_tokens":30}}}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val usage = done.message.usage
        assertEquals(95, usage.input)
        assertEquals(50, usage.output)
        assertEquals(10, usage.cacheRead)
        assertEquals(5, usage.cacheWrite)
        assertEquals(30, usage.reasoning)
        assertEquals(95 + 50 + 10 + 5, usage.totalTokens)
        // glm-5.2 reference rates: input 1.4, output 4.4, cacheRead 0.26 per M
        assertEquals(95 * 1.4 / 1_000_000, usage.cost.input, 1e-12)
        assertEquals(50 * 4.4 / 1_000_000, usage.cost.output, 1e-12)
    }

    @Test
    fun `explicit zero cached_tokens stays zero instead of falling through`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                """{"usage":{"prompt_tokens":10,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":0},"prompt_cache_hit_tokens":7}}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val usage = assertIs<AssistantMessageEvent.Done>(events.last()).message.usage
        assertEquals(0, usage.cacheRead)
        assertEquals(10, usage.input)
    }

    @Test
    fun `absent nested cached_tokens falls through to prompt_cache_hit then top-level`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                """{"usage":{"prompt_tokens":10,"completion_tokens":1,"prompt_cache_hit_tokens":4}}""",
                "[DONE]",
            ),
        )
        var events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        assertEquals(4, assertIs<AssistantMessageEvent.Done>(events.last()).message.usage.cacheRead)

        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                """{"usage":{"prompt_tokens":10,"completion_tokens":1,"cached_tokens":6}}""",
                "[DONE]",
            ),
        )
        events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        assertEquals(6, assertIs<AssistantMessageEvent.Done>(events.last()).message.usage.cacheRead)
    }

    @Test
    fun `malformed sse json surfaces as protocol error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"partial"}}]}""",
                """{"choices":[{"delta":{"content":""""",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertTrue("Malformed SSE JSON" in (error.error.errorMessage ?: ""))
        assertEquals("partial", assertIs<TextContent>(error.error.content.single()).text)
    }

    @Test
    fun `non-2xx response produces error event with parsed body`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            401,
            """{"error":{"message":"Invalid API key","type":"auth_error"}}""",
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(StopReason.ERROR, error.reason)
        assertTrue("Provider returned HTTP 401" in (error.error.errorMessage ?: ""))
        assertTrue("Invalid API key" in (error.error.errorMessage ?: ""))
    }

    @Test
    fun `json error event mid-stream keeps partial content`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"partial"}}]}""",
                """{"error":{"message":"upstream overloaded","type":"server_error"}}""",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(StopReason.ERROR, error.reason)
        assertTrue("upstream overloaded" in (error.error.errorMessage ?: ""))
        assertEquals("partial", assertIs<TextContent>(error.error.content.single()).text)
    }

    @Test
    fun `unknown finish reason becomes error event`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"safety_violation"}]}""", "[DONE]"))
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertTrue("safety_violation" in (error.error.errorMessage ?: ""))
    }

    @Test
    fun `stream without finish reason fails`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{"content":"x"}}]}"""))
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertTrue("finish_reason" in (error.error.errorMessage ?: ""))
    }

    @Test
    fun `raw finish reason is preserved on stop and provider error stops`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]",
            ),
        )
        var events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "k")).toList()
        var done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        assertEquals("stop", done.message.rawStopReason)
        assertNull(done.message.errorMessage)

        transport.enqueueResponse(
            sse(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}",
                "[DONE]",
            ),
        )
        events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "k")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("content_filter", error.error.rawStopReason)
        assertEquals("Provider finish_reason: content_filter", error.error.errorMessage)
    }

    @Test
    fun `routed chunk model surfaces on responseModel only when different`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                "{\"id\":\"r1\",\"model\":\"glm-5.2-air\",\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
                "{\"model\":\"glm-5.2\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "k")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals("glm-5.2-air", done.message.responseModel)
        assertEquals("glm-5.2", done.message.model)
        assertEquals("r1", done.message.responseId)
    }

    @Test
    fun `length finish reason mapped`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"length"}]}""", "[DONE]"))
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        assertEquals(StopReason.LENGTH, assertIs<AssistantMessageEvent.Done>(events.last()).reason)
    }

    @Test
    fun `usage-only chunk with empty choices is accepted`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1}}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(3, done.message.usage.input)
        assertEquals(1, done.message.usage.output)
    }

    @Test
    fun `missing api key produces error event not throw`() = runTest {
        val transport = FakeTransport()
        val events = api(transport)
            .stream(model, context, OpenAiCompletionsOptions())
            .toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue("No API key" in (error.error.errorMessage ?: ""))
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `request posts to chat completions endpoint with bearer auth`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).stream(
            model,
            context,
            OpenAiCompletionsOptions(apiKey = "test-key", maxTokens = 64, reasoningEffort = ModelThinkingLevel.HIGH),
        ).toList()

        val request = transport.requests.single()
        assertEquals("https://api.z.ai/api/coding/paas/v4/chat/completions", request.url)
        assertEquals("test-key", request.bearerToken)
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertEquals("glm-5.2", body["model"]!!.jsonPrimitive.content)
        assertEquals(64L, body["max_tokens"]!!.jsonPrimitive.longOrNull)
        assertEquals(
            "enabled",
            body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `retries retryable http failure before content begins`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(429, "slow down", mapOf("retry-after-ms" to listOf("10")))
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        val events = api(transport)
            .stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key", maxRetries = 1))
            .toList()
        assertEquals(2, transport.requests.size)
        assertIs<AssistantMessageEvent.Done>(events.last())
    }

    @Test
    fun `done sentinel stops consuming the body`() = runTest {
        val transport = FakeTransport()
        // The stream never ends server-side: if the adapter kept draining the
        // body after [DONE] this test would time out instead of completing.
        transport.enqueueHangingResponse(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        assertTrue(done.message.content.isEmpty())
        assertTrue(transport.cancelled.value, "event collection should be cancelled after [DONE]")
    }

    @Test
    fun `cancellation never emits an error event and rethrows`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"a"}}]}""",
                """{"choices":[{"delta":{"content":"b"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        // take() cancels collection mid-stream, deterministically.
        val events = api(transport)
            .stream(model, context, OpenAiCompletionsOptions(apiKey = "k"))
            .take(3) // Start, TextStart, first TextDelta
            .toList()
        assertTrue(events.none { it is AssistantMessageEvent.Error }, "cancellation must not emit Error")
        assertTrue(transport.cancelled.value, "transport must observe cancellation")
    }

    @Test
    fun `snapshots are immutable across text deltas`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"a"}}]}""",
                """{"choices":[{"delta":{"content":"b"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val deltas = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("a", "b"), deltas.map { it.delta })
        // TextDelta snapshots carry the accumulated text at that point.
        assertEquals("a", assertIs<TextContent>(deltas[0].partial.content.single()).text)
        assertEquals("ab", assertIs<TextContent>(deltas[1].partial.content.single()).text)
        // Earlier snapshot unaffected by later deltas.
        assertEquals("a", assertIs<TextContent>(deltas[0].partial.content.single()).text)
    }

    @Test
    fun `streamSimple defaults max tokens to the model limit`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).streamSimple(model, context, SimpleStreamOptions(apiKey = "k")).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(model.maxTokens.toLong(), body["max_tokens"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `streamSimple forwards toolChoice to the payload`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport)
            .streamSimple(model, context, SimpleStreamOptions(apiKey = "k", toolChoice = ToolChoice.Required))
            .toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streamSimple retains explicit max tokens when room exists`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).streamSimple(model, context, SimpleStreamOptions(apiKey = "k", maxTokens = 512)).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(512L, body["max_tokens"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `streamSimple clamps oversized max tokens against context`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).streamSimple(
            model,
            context,
            SimpleStreamOptions(apiKey = "k", maxTokens = 1_000_000),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(
            works.resolve.pathfinder.ai.utils.clampMaxTokensToContext(model, context, 1_000_000).toLong(),
            body["max_tokens"]!!.jsonPrimitive.longOrNull,
        )
    }

    @Test
    fun `streamSimple clamps to minimum one in a tight window`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        val tiny = model.copy(contextWindow = 4097) // 1 estimated token + 4096 safety
        api(transport).streamSimple(
            tiny,
            context,
            SimpleStreamOptions(apiKey = "k", maxTokens = 5000),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(1L, body["max_tokens"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `toggle-only model enables reasoning through streamSimple without effort`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).streamSimple(
            TestCatalogs.GLM_4_7,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.pathfinder.ai.core.ThinkingLevel.LOW),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(!body.containsKey("reasoning_effort"), "toggle model must not get reasoning_effort")
    }

    @Test
    fun `streamSimple maps clamped off to disabled thinking`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        // glm-5.3 clamps MEDIUM up to HIGH; a non-reasoning model clamps to OFF.
        api(transport).streamSimple(
            TestCatalogs.GLM_5_3,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.pathfinder.ai.core.ThinkingLevel.MEDIUM),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)

        val nonReasoning = TestCatalogs.GLM_4_7.copy(reasoning = false)
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).streamSimple(
            nonReasoning,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.pathfinder.ai.core.ThinkingLevel.HIGH),
        ).toList()
        val body2 = Json.parseToJsonElement(transport.requests.last().body.decodeToString()).jsonObject
        assertTrue(!body2.containsKey("thinking"), "non-reasoning model must not get thinking")
    }

    @Test
    fun `direct off never enables zai thinking`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).stream(
            model,
            context,
            OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = ModelThinkingLevel.OFF),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(!body.containsKey("reasoning_effort"))
    }

    @Test
    fun `explicit null effort map omits reasoning_effort for unsupported level`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        // glm-5.3 has minimal explicitly null; direct request for it enables
        // thinking but omits reasoning_effort (pi's null semantics).
        api(transport).stream(
            TestCatalogs.GLM_5_3,
            context,
            OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = ModelThinkingLevel.MINIMAL),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(!body.containsKey("reasoning_effort"))
    }

    // Session-affinity headers, ported from pi's
    // test/openai-completions-prompt-cache.test.ts (createClient,
    // openai-completions.ts:760-770).

    private fun affinityModel(
        format: works.resolve.pathfinder.ai.core.SessionAffinityFormat? = null,
        provider: String = "openai",
        baseUrl: String = "https://api.openai.com/v1",
    ): works.resolve.pathfinder.ai.core.Model = TestCatalogs.GPT_4O.copy(
        provider = provider,
        baseUrl = baseUrl,
        compat = TestCatalogs.GPT_4O.compat.copy(
            sendSessionAffinityHeaders = true,
            sessionAffinityFormat = format,
        ),
    )

    private suspend fun headersFor(
        model: works.resolve.pathfinder.ai.core.Model = TestCatalogs.GPT_4O,
        options: OpenAiCompletionsOptions = OpenAiCompletionsOptions(apiKey = "k"),
    ): Map<String, String> {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        api(transport).stream(model, context, options).toList()
        return transport.requests.single().headers
    }

    @Test
    fun `openai affinity format sends session id client request id and session affinity headers`() = runTest {
        val headers = headersFor(
            affinityModel(baseUrl = "https://proxy.example.com/v1"),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-affinity"),
        )
        assertEquals("session-affinity", headers["session_id"])
        assertEquals("session-affinity", headers["x-client-request-id"])
        assertEquals("session-affinity", headers["x-session-affinity"])
        assertNull(headers["x-session-id"])
    }

    @Test
    fun `openrouter affinity format sends only x-session-id`() = runTest {
        val headers = headersFor(
            affinityModel(
                format = works.resolve.pathfinder.ai.core.SessionAffinityFormat.OPENROUTER,
                baseUrl = "https://proxy.example.com/v1",
            ),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-proxy"),
        )
        assertEquals("session-proxy", headers["x-session-id"])
        assertNull(headers["session_id"])
        assertNull(headers["x-client-request-id"])
        assertNull(headers["x-session-affinity"])
    }

    @Test
    fun `openrouter format auto-detected from provider and base url`() = runTest {
        val headers = headersFor(
            affinityModel(provider = "openrouter", baseUrl = "https://openrouter.ai/api/v1"),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-openrouter"),
        )
        assertEquals("session-openrouter", headers["x-session-id"])
        assertNull(headers["session_id"])
        assertNull(headers["x-client-request-id"])
        assertNull(headers["x-session-affinity"])
    }

    @Test
    fun `openai-nosession format omits session id header`() = runTest {
        val model = affinityModel(
            format = works.resolve.pathfinder.ai.core.SessionAffinityFormat.OPENAI_NOSESSION,
        )
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        api(transport)
            .stream(model, context, OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-nosession"))
            .toList()
        val headers = transport.requests.single().headers
        assertNull(headers["session_id"])
        assertNull(headers["x-session-id"])
        assertEquals("session-nosession", headers["x-client-request-id"])
        assertEquals("session-nosession", headers["x-session-affinity"])
        // prompt_cache_key is governed by cache retention, not the affinity format.
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("session-nosession", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no affinity headers without a session id`() = runTest {
        val headers = headersFor(
            affinityModel(baseUrl = "https://proxy.example.com/v1"),
            OpenAiCompletionsOptions(apiKey = "k"),
        )
        assertNull(headers["session_id"])
        assertNull(headers["x-client-request-id"])
        assertNull(headers["x-session-affinity"])
        assertNull(headers["x-session-id"])
    }

    @Test
    fun `no affinity headers when sendSessionAffinityHeaders is false`() = runTest {
        val headers = headersFor(
            TestCatalogs.GPT_4O.copy(
                provider = "openrouter",
                baseUrl = "https://openrouter.ai/api/v1",
            ),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-openrouter"),
        )
        assertNull(headers["x-session-id"])
        assertNull(headers["session_id"])
        assertNull(headers["x-client-request-id"])
        assertNull(headers["x-session-affinity"])
    }

    @Test
    fun `affinity headers omitted when cache retention is none`() = runTest {
        val headers = headersFor(
            affinityModel(baseUrl = "https://proxy.example.com/v1"),
            OpenAiCompletionsOptions(
                apiKey = "k",
                sessionId = "session-affinity",
                cacheRetention = CacheRetention.NONE,
            ),
        )
        assertNull(headers["session_id"])
        assertNull(headers["x-client-request-id"])
        assertNull(headers["x-session-affinity"])
    }

    @Test
    fun `explicit request headers override generated affinity headers`() = runTest {
        val headers = headersFor(
            affinityModel(baseUrl = "https://proxy.example.com/v1"),
            OpenAiCompletionsOptions(
                apiKey = "k",
                sessionId = "session-affinity",
                headers = mapOf(
                    "session_id" to "override-session",
                    "x-client-request-id" to "override-request",
                    "x-session-affinity" to "override-affinity",
                ),
            ),
        )
        assertEquals("override-session", headers["session_id"])
        assertEquals("override-request", headers["x-client-request-id"])
        assertEquals("override-affinity", headers["x-session-affinity"])
    }
}
