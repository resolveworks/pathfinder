package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.ModelThinkingLevel
import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ThinkingContent
import works.resolve.aletheia.ai.core.ToolCall
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.models.Models
import works.resolve.aletheia.ai.models.Provider
import works.resolve.aletheia.ai.providers.ZaiModels
import works.resolve.aletheia.ai.transport.ProviderHttpException
import works.resolve.aletheia.ai.transport.SseEvent
import works.resolve.aletheia.ai.transport.TransportRequest
import works.resolve.aletheia.ai.transport.TransportResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

/** Scripted transport; records requests and replays scripted outcomes as complete SSE events. */
private class FakeTransport : works.resolve.aletheia.ai.transport.HttpStreamingTransport {
    val requests = mutableListOf<TransportRequest>()
    var outcomes: MutableList<suspend () -> TransportResponse> = mutableListOf()

    /** Set when the caller stopped consuming a response's events. */
    var cancelled = MutableStateFlow(false)

    override suspend fun post(request: TransportRequest): TransportResponse {
        requests.add(request)
        check(outcomes.isNotEmpty()) { "unexpected request" }
        return outcomes.removeAt(0)()
    }

    fun enqueueResponse(chunks: List<String>, status: Int = 200) {
        outcomes.add {
            val events = flow {
                try {
                    chunks.forEach { emit(SseEvent(it)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // take() aborts the flow without necessarily flipping
                    // Job.isActive, so observe cancellation via the exception.
                    cancelled.value = true
                    throw e
                }
            }
            TransportResponse(
                status = status,
                headers = mapOf("content-type" to listOf("text/event-stream")),
                events = events,
            )
        }
    }

    /** A stream that never ends server-side after the given chunks. */
    fun enqueueHangingResponse(vararg chunks: String) {
        outcomes.add {
            val events = flow {
                try {
                    chunks.forEach { emit(SseEvent(it)) }
                    awaitCancellation()
                } finally {
                    cancelled.value = true
                }
            }
            TransportResponse(
                status = 200,
                headers = mapOf("content-type" to listOf("text/event-stream")),
                events = events,
            )
        }
    }

    fun enqueueError(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) {
        outcomes.add { throw ProviderHttpException(status, headers, body) }
    }
}

private fun sse(vararg payloads: String): List<String> = payloads.toList()

class OpenAiCompletionsStreamTest {

    private val model = ZaiModels.GLM_5_2
    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun api(transport: FakeTransport) = OpenAiCompletionsApi(
        transport,
        works.resolve.aletheia.ai.utils.ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 }),
        nowMs = { 1_770_000_000_000L },
    )

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
            works.resolve.aletheia.ai.utils.clampMaxTokensToContext(model, context, 1_000_000).toLong(),
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
            ZaiModels.GLM_4_7,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.aletheia.ai.core.ThinkingLevel.LOW),
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
            ZaiModels.GLM_5_3,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.aletheia.ai.core.ThinkingLevel.MEDIUM),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)

        val nonReasoning = ZaiModels.GLM_4_7.copy(reasoning = false)
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).streamSimple(
            nonReasoning,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.aletheia.ai.core.ThinkingLevel.HIGH),
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
            ZaiModels.GLM_5_3,
            context,
            OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = ModelThinkingLevel.MINIMAL),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(!body.containsKey("reasoning_effort"))
    }
}

class ModelsRegistryTest {

    private fun models(transport: FakeTransport, storedKey: String? = null): Models =
        Models(
            listOf(
                Provider(
                    id = ZaiModels.PROVIDER_ID,
                    name = "Z.AI",
                    baseUrl = ZaiModels.BASE_URL,
                    apiKeyResolver = { storedKey },
                    models = ZaiModels.ALL,
                    api = OpenAiCompletionsApi(
                        transport,
                        works.resolve.aletheia.ai.utils.ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 }),
                    ),
                ),
            ),
        )

    @Test
    fun `explicit api key wins over resolver`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        models(transport, storedKey = "stored").stream(
            "zai",
            "glm-4.7",
            Context(messages = listOf(UserMessage.ofText("hi"))),
            SimpleStreamOptions(apiKey = "explicit"),
        ).toList()
        assertEquals("explicit", transport.requests.single().bearerToken)
    }

    @Test
    fun `resolver supplies key when option absent`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        models(transport, storedKey = "stored").stream(
            "zai",
            "glm-4.7",
            Context(messages = listOf(UserMessage.ofText("hi"))),
        ).toList()
        assertEquals("stored", transport.requests.single().bearerToken)
    }

    @Test
    fun `missing key surfaces as error event`() = runTest {
        val transport = FakeTransport()
        val events = models(transport, storedKey = null).stream(
            "zai",
            "glm-4.7",
            Context(messages = listOf(UserMessage.ofText("hi"))),
        ).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertTrue("No API key" in (error.error.errorMessage ?: ""))
    }

    @Test
    fun `unknown provider or model throws`() {
        val transport = FakeTransport()
        val models = models(transport)
        assertFailsWithMessage<IllegalArgumentException>("Unknown provider") {
            models.stream("nope", "x", Context(messages = emptyList()))
        }
        assertFailsWithMessage<IllegalArgumentException>("Unknown model") {
            models.stream("zai", "nope", Context(messages = emptyList()))
        }
    }

    @Test
    fun `registry exposes providers and models`() {
        val models = models(FakeTransport())
        val provider = models.getProvider("zai")!!
        assertEquals("Z.AI", provider.name)
        assertEquals(
            listOf("glm-4.7", "glm-5-turbo", "glm-5.3", "glm-5.2", "glm-5.2-highspeed"),
            provider.models.map { it.id },
        )
        assertEquals("glm-5.3", models.getModel("zai", "glm-5.3")!!.id)
    }

    private inline fun <reified T : Throwable> assertFailsWithMessage(
        fragment: String,
        block: () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName} but call succeeded")
        } catch (error: Throwable) {
            if (error::class != T::class) throw error
            assertTrue(fragment in (error.message ?: ""), "expected '$fragment' in: ${error.message}")
        }
    }
}
