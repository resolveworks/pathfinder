package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.SimpleToolChoice
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.providers.ProviderCatalog
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assume.assumeTrue
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import java.io.File

class OpenAiCompletionsStreamTest {

    private val model = TestCatalogs.GLM_5_2
    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun api(transport: FakeTransport) = OpenAiCompletionsApi(
        transport,
        works.resolve.pathfinder.ai.utils.ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }),
        clock = FakeClock(1_770_000_000_000L),
    )

    @Test
    fun `cloudflare placeholders resolve from env before transport`() = runTest {
        val transport = FakeTransport()
        val cfModel = TestCatalogs.CLOUDFLARE.models.single()
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
        // Header matching is case-insensitive: the request value replaced the
        // model's differently-cased header.
        assertEquals("request-value", request.headers["x-model-header"])
        assertTrue(request.headers.keys.none { it.equals("authorization", ignoreCase = true) })
        // The mandatory Accept header cannot be overridden.
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
    fun `reasoning_details accumulate into the thinking signature and replay on the next request`() = runTest {
        val detail = """{"type":"reasoning.encrypted","id":"call_1","data":"encrypted-signature"}"""
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"reasoning_details":[$detail]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{\"path\":\"README.md\"}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        // reasoning_details deltas open the thinking block but never emit deltas.
        assertEquals(0, events.filterIsInstance<AssistantMessageEvent.ThinkingDelta>().size)
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val thinking = assertIs<ThinkingContent>(done.message.content[0])
        assertEquals("", thinking.thinking)
        assertEquals(Json.parseToJsonElement("[$detail]"), Json.parseToJsonElement(thinking.thinkingSignature!!))
        val toolCall = assertIs<ToolCall>(done.message.content[1])
        assertEquals("call_1", toolCall.id)
        assertEquals("read", toolCall.name)
        assertEquals("""{"path":"README.md"}""", toolCall.arguments)

        api(transport)
            .stream(
                model,
                Context(messages = listOf(UserMessage.ofText("hi"), done.message)),
                OpenAiCompletionsOptions(apiKey = "test-key"),
            )
            .toList()
        val replayBody = Json.parseToJsonElement(transport.requests[1].body.decodeToString()).jsonObject
        val assistant = replayBody["messages"]!!.jsonArray
            .first { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }.jsonObject
        assertEquals(Json.parseToJsonElement("[$detail]"), assistant["reasoning_details"])
    }

    @Test
    fun `consecutive text and summary reasoning_details deltas merge before replay`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"The","index":0}]}}]}""",
                """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":" user wants the time.","signature":"sha256:text-signature","format":"openai-responses-v1","index":0}]}}]}""",
                """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","summary":"Looked","index":0}]}}]}""",
                """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","summary":" up time.","format":"openai-responses-v1","index":0}]}}]}""",
                """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.encrypted","id":"call_1","data":"encrypted-signature"}]}}]}""",
                """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","summary":"After encrypted block.","format":"openai-responses-v1","index":0}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val thinking = assertIs<ThinkingContent>(done.message.content[0])
        assertEquals("", thinking.thinking)
        val expected = """
            [
              {"type":"reasoning.text","text":"The user wants the time.","index":0,"signature":"sha256:text-signature","format":"openai-responses-v1"},
              {"type":"reasoning.summary","summary":"Looked up time.","index":0,"format":"openai-responses-v1"},
              {"type":"reasoning.encrypted","id":"call_1","data":"encrypted-signature"},
              {"type":"reasoning.summary","summary":"After encrypted block.","format":"openai-responses-v1","index":0}
            ]
        """.trimIndent()
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(thinking.thinkingSignature!!))
    }

    @Test
    fun `reasoning field plus reasoning_details keep visible thinking and structured signature`() = runTest {
        val signedText =
            """{"type":"reasoning.text","text":"I should call the read tool.","signature":"sha256:signed-text","id":"reasoning-text-1","format":"anthropic-claude-v1","index":0}"""
        val encrypted = """{"type":"reasoning.encrypted","id":"call_1","data":"encrypted-signature"}"""
        val summary =
            """{"type":"reasoning.summary","summary":"Decided to inspect the requested file.","id":"reasoning-summary-1","format":"anthropic-claude-v1","index":1}"""
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"reasoning":"I should call the read tool.","reasoning_details":[$signedText]}}]}""",
                """{"choices":[{"delta":{"reasoning_details":[$encrypted,$summary]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val thinking = assertIs<ThinkingContent>(done.message.content[0])
        assertEquals("I should call the read tool.", thinking.thinking)
        assertEquals(
            Json.parseToJsonElement("[$signedText,$encrypted,$summary]"),
            Json.parseToJsonElement(thinking.thinkingSignature!!),
        )

        // Replay: the structured details replace the raw reasoning field.
        api(transport)
            .stream(
                model,
                Context(messages = listOf(UserMessage.ofText("hi"), done.message)),
                OpenAiCompletionsOptions(apiKey = "test-key"),
            )
            .toList()
        val replayBody = Json.parseToJsonElement(transport.requests[1].body.decodeToString()).jsonObject
        val assistant = replayBody["messages"]!!.jsonArray
            .first { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }.jsonObject
        assertEquals(Json.parseToJsonElement("[$signedText,$encrypted,$summary]"), assistant["reasoning_details"])
        assertNull(assistant["reasoning"])
        assertNull(assistant["reasoning_content"])
        assertNull(assistant["reasoning_text"])
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
    fun `non-2xx response produces error event with whole body`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            401,
            """{"error":{"message":"Invalid API key","type":"auth_error"}}""",
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals(
            """401: {"error":{"message":"Invalid API key","type":"auth_error"}}""",
            error.error.errorMessage,
        )
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
    fun `empty finish reason is absent like pi truthiness`() = runTest {
        // pi guards with `if (choice.finish_reason)`: "" is falsy, so the raw
        // stop-reason mapping never sees it and the stream ends pending — for
        // finish-reason-supporting models that is the missing-finish-reason error,
        // not an error-stop with an empty raw reason.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"hi"},"finish_reason":""}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("Stream ended without finish_reason", error.error.errorMessage)
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
            .streamSimple(model, context, SimpleStreamOptions(apiKey = "k", toolChoice = SimpleToolChoice.None))
            .toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("none", body["tool_choice"]!!.jsonPrimitive.content)
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
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.pathfinder.ai.ThinkingLevel.LOW),
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
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.pathfinder.ai.ThinkingLevel.MEDIUM),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)

        val nonReasoning = TestCatalogs.GLM_4_7.copy(reasoning = false)
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).streamSimple(
            nonReasoning,
            context,
            SimpleStreamOptions(apiKey = "k", reasoning = works.resolve.pathfinder.ai.ThinkingLevel.HIGH),
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
        // glm-5.3 maps MINIMAL to explicit null: thinking enabled but no
        // reasoning_effort sent.
        api(transport).stream(
            TestCatalogs.GLM_5_3,
            context,
            OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = ModelThinkingLevel.MINIMAL),
        ).toList()
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(!body.containsKey("reasoning_effort"))
    }

    private fun affinityModel(
        format: works.resolve.pathfinder.ai.SessionAffinityFormat? = null,
        provider: String = "openai",
        baseUrl: String = "https://api.openai.com/v1",
    ): works.resolve.pathfinder.ai.Model = TestCatalogs.GPT_4O.copy(
        provider = provider,
        baseUrl = baseUrl,
        compat = TestCatalogs.GPT_4O.compat.copy(
            sendSessionAffinityHeaders = true,
            sessionAffinityFormat = format,
        ),
    )

    private suspend fun headersFor(
        model: works.resolve.pathfinder.ai.Model = TestCatalogs.GPT_4O,
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
                format = works.resolve.pathfinder.ai.SessionAffinityFormat.OPENROUTER,
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
            format = works.resolve.pathfinder.ai.SessionAffinityFormat.OPENAI_NOSESSION,
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

    @Test
    fun `default user agent is sent and explicit request headers override it`() = runTest {
        val default = headersFor()
        assertEquals(getPiUserAgent(), default["User-Agent"])

        val overridden = headersFor(
            options = OpenAiCompletionsOptions(
                apiKey = "k",
                headers = mapOf("User-Agent" to "custom-agent/1"),
            ),
        )
        assertEquals("custom-agent/1", overridden["User-Agent"])
    }

    @Test
    fun `model headers override the default user agent`() = runTest {
        val headers = headersFor(
            model = TestCatalogs.GPT_4O.copy(headers = mapOf("User-Agent" to "model-agent")),
        )
        assertEquals("model-agent", headers["User-Agent"])
    }

    @Test
    fun `opencode-go reasoning delta is stored under the reasoning_content signature`() = runTest {
        val goModel = model.copy(provider = "opencode-go")
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"reasoning":"hmm","content":"answer"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(goModel, context, OpenAiCompletionsOptions(apiKey = "k")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val thinking = assertIs<ThinkingContent>(done.message.content.first { it is ThinkingContent })
        assertEquals("hmm", thinking.thinking)
        assertEquals("reasoning_content", thinking.thinkingSignature)

        // Replay: opencode-go round-trips the stored signature as the literal
        // reasoning_content wire field.
        val replay = OpenAiCompletionsPayload.convertMessages(
            goModel,
            Context(messages = listOf(context.messages.single(), done.message)),
        )
        val assistant = replay.last()
        assertEquals(
            "hmm",
            assistant["reasoning_content"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
        assertTrue("reasoning" !in assistant)
    }

    @Test
    fun `non opencode-go reasoning delta keeps the literal reasoning signature`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"reasoning":"hmm"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "k")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val thinking = assertIs<ThinkingContent>(done.message.content.single())
        assertEquals("reasoning", thinking.thinkingSignature)
    }

    @Test
    fun `openrouter metadata raw is appended when missing from the error message`() = runTest {
        // The long body is truncated at the cap before the raw metadata is
        // appended, so the append is what surfaces it exactly once.
        val padding = "x".repeat(5000)
        val transport = FakeTransport()
        transport.enqueueError(
            403,
            """{"error":{"message":"$padding","code":403,"metadata":{"raw":"upstream WAF blocked policy XYZ"}}}""",
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        val message = error.error.errorMessage!!
        assertEquals(1, Regex("upstream WAF blocked policy XYZ").findAll(message).count())
        assertTrue(message.endsWith("upstream WAF blocked policy XYZ"))
    }

    @Test
    fun `openrouter metadata raw is not double-printed`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            403,
            """{"error":{"message":"Provider returned error","code":403,"metadata":{"raw":"upstream WAF blocked policy XYZ"}}}""",
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test-key")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        val message = error.error.errorMessage!!
        assertEquals(1, Regex("upstream WAF blocked policy XYZ").findAll(message).count())
    }

    // ------------------------------------------------------------------
    // Cases from pi test/openai-completions-tool-choice.test.ts (stream side)
    // ------------------------------------------------------------------

    @Test
    fun `ignores null stream chunks from openai-compatible providers`() = runTest {
        // Some providers send `data: null` keep-alives; pi skips them
        // (`if (!chunk || typeof chunk !== "object") continue`).
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                "null",
                """{"id":"chatcmpl-test","choices":[{"delta":{"content":"OK"},"finish_reason":null}]}""",
                """{"id":"chatcmpl-test","choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        assertNull(done.message.errorMessage)
        assertEquals("chatcmpl-test", done.message.responseId)
        assertEquals(4, done.message.usage.totalTokens)
        assertEquals("OK", assertIs<TextContent>(done.message.content.single()).text)
    }

    @Test
    fun `accepts streams without finish_reason when compat disables it`() = runTest {
        val lenient = model.copy(compat = model.compat.copy(supportsFinishReason = false))
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"chatcmpl-no-finish-reason","choices":[{"delta":{"content":"complete answer"},"finish_reason":null}]}""",
            ),
        )
        var events = api(transport).stream(lenient, context, OpenAiCompletionsOptions(apiKey = "test")).toList()
        var done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.STOP, done.reason)
        assertNull(done.message.errorMessage)
        assertEquals("complete answer", assertIs<TextContent>(done.message.content.single()).text)

        // Tool calls map to toolUse on the same lenient path.
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read","arguments":"{}"}}]}}]}""",
            ),
        )
        events = api(transport).stream(lenient, context, OpenAiCompletionsOptions(apiKey = "test")).toList()
        done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.TOOL_USE, done.reason)
    }

    @Test
    fun `network_error finish reason maps to provider error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}""",
                """{"choices":[{"delta":{},"finish_reason":"network_error"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test")).toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals("Provider finish_reason: network_error", error.error.errorMessage)
        assertEquals("network_error", error.error.rawStopReason)
    }

    @Test
    fun `usage falls back to choice-level usage when chunk usage is absent`() = runTest {
        // Some providers (e.g. Moonshot) return usage inside the choice.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"OK"},"finish_reason":null}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop","usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":50,"cache_write_tokens":30}}}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test")).toList()
        val usage = assertIs<AssistantMessageEvent.Done>(events.last()).message.usage
        assertEquals(20, usage.input)
        assertEquals(50, usage.cacheRead)
        assertEquals(30, usage.cacheWrite)
        assertEquals(105, usage.totalTokens)
    }

    @Test
    fun `coalesces tool call deltas by stable index when provider mutates ids mid-stream`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"chatcmpl-kimi-bad-stream","choices":[{"delta":{"tool_calls":[{"index":0,"id":"functions.read:0","type":"function","function":{"name":"read","arguments":""}}]},"finish_reason":null}]}""",
                """{"id":"chatcmpl-kimi-bad-stream","choices":[{"delta":{"tool_calls":[{"index":0,"id":"chatcmpl-tool-a","type":"function","function":{"name":null,"arguments":"{\"path\":\"README"}}]},"finish_reason":null}]}""",
                """{"id":"chatcmpl-kimi-bad-stream","choices":[{"delta":{"tool_calls":[{"index":0,"id":"chatcmpl-tool-b","type":"function","function":{"name":null,"arguments":".md\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test")).toList()
        val toolEventIndexes = events.mapNotNull { e ->
            when (e) {
                is AssistantMessageEvent.ToolCallStart -> e.contentIndex
                is AssistantMessageEvent.ToolCallDelta -> e.contentIndex
                is AssistantMessageEvent.ToolCallEnd -> e.contentIndex
                else -> null
            }
        }
        assertEquals(List(5) { 0 }, toolEventIndexes)
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.TOOL_USE, done.reason)
        val toolCall = assertIs<ToolCall>(done.message.content.single())
        assertEquals("functions.read:0", toolCall.id)
        assertEquals("read", toolCall.name)
        assertEquals("""{"path":"README.md"}""", toolCall.arguments)
    }

    @Test
    fun `accumulates mixed content reasoning and parallel tool call deltas independently`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"chatcmpl-mixed-deltas","choices":[{"delta":{"content":"answer 1","reasoning_content":"think 1","tool_calls":[{"index":0,"id":"tc_read_initial","type":"function","function":{"name":"read","arguments":"{\"path\":\"README"}},{"index":1,"id":"tc_grep_initial","type":"function","function":{"name":"grep","arguments":"{\"pattern\":\"TODO"}},{"id":"tc_list_no_index","type":"function","function":{"name":"list","arguments":"{\"path\":\"packages"}},{"id":"tc_write_no_index","type":"function","function":{"name":"write","arguments":"{\"path\":\"out"}}]},"finish_reason":null}]}""",
                """{"id":"chatcmpl-mixed-deltas","choices":[{"delta":{"content":" answer 2","tool_calls":[{"index":1,"id":"tc_grep_changed","type":"function","function":{"arguments":"\",\"path\":\"src"}},{"id":"tc_write_no_index","type":"function","function":{"arguments":".txt\",\"content\":\"ok\"}"}},{"id":"tc_list_no_index","type":"function","function":{"arguments":"/ai\"}"}}]},"finish_reason":null}]}""",
                """{"id":"chatcmpl-mixed-deltas","choices":[{"delta":{"content":"\n","reasoning_content":" think 2","tool_calls":[{"index":0,"id":"tc_read_changed","type":"function","function":{"arguments":".md\"}"}},{"index":1,"type":"function","function":{"arguments":"\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":8}}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test")).toList()

        fun count(events: List<AssistantMessageEvent>, type: Class<out AssistantMessageEvent>) =
            events.count { type.isInstance(it) }
        assertEquals(1, count(events, AssistantMessageEvent.TextStart::class.java))
        assertEquals(3, count(events, AssistantMessageEvent.TextDelta::class.java))
        assertEquals(1, count(events, AssistantMessageEvent.TextEnd::class.java))
        assertEquals(1, count(events, AssistantMessageEvent.ThinkingStart::class.java))
        assertEquals(2, count(events, AssistantMessageEvent.ThinkingDelta::class.java))
        assertEquals(1, count(events, AssistantMessageEvent.ThinkingEnd::class.java))
        assertEquals(4, count(events, AssistantMessageEvent.ToolCallStart::class.java))
        assertEquals(9, count(events, AssistantMessageEvent.ToolCallDelta::class.java))
        assertEquals(4, count(events, AssistantMessageEvent.ToolCallEnd::class.java))

        fun eventsFor(index: Int) = events.mapNotNull { e ->
            when (e) {
                is AssistantMessageEvent.ToolCallStart -> "ToolCallStart".takeIf { e.contentIndex == index }
                is AssistantMessageEvent.ToolCallDelta -> "ToolCallDelta".takeIf { e.contentIndex == index }
                is AssistantMessageEvent.ToolCallEnd -> "ToolCallEnd".takeIf { e.contentIndex == index }
                else -> null
            }
        }
        assertEquals(
            listOf("ToolCallStart", "ToolCallDelta", "ToolCallDelta", "ToolCallEnd"),
            eventsFor(2),
        )
        assertEquals(
            listOf("ToolCallStart", "ToolCallDelta", "ToolCallDelta", "ToolCallDelta", "ToolCallEnd"),
            eventsFor(3),
        )
        assertEquals(
            listOf("ToolCallStart", "ToolCallDelta", "ToolCallDelta", "ToolCallEnd"),
            eventsFor(4),
        )
        assertEquals(
            listOf("ToolCallStart", "ToolCallDelta", "ToolCallDelta", "ToolCallEnd"),
            eventsFor(5),
        )

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(StopReason.TOOL_USE, done.reason)
        assertEquals("answer 1 answer 2\n", assertIs<TextContent>(done.message.content[0]).text)
        val thinking = assertIs<ThinkingContent>(done.message.content[1])
        assertEquals("think 1 think 2", thinking.thinking)
        assertEquals("reasoning_content", thinking.thinkingSignature)
        val read = assertIs<ToolCall>(done.message.content[2])
        val grep = assertIs<ToolCall>(done.message.content[3])
        val list = assertIs<ToolCall>(done.message.content[4])
        val write = assertIs<ToolCall>(done.message.content[5])
        assertEquals("tc_read_initial", read.id)
        assertEquals("""{"path":"README.md"}""", read.arguments)
        assertEquals("tc_grep_initial", grep.id)
        assertEquals("""{"pattern":"TODO","path":"src"}""", grep.arguments)
        assertEquals("tc_list_no_index", list.id)
        assertEquals("""{"path":"packages/ai"}""", list.arguments)
        assertEquals("tc_write_no_index", write.id)
        assertEquals("""{"path":"out.txt","content":"ok"}""", write.arguments)
    }

    @Test
    fun `ignores empty custom objects on function tool call deltas`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"chatcmpl-empty-custom","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{\"path\":\"README.md\"}"},"custom":{}}]},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )
        val events = api(transport).stream(model, context, OpenAiCompletionsOptions(apiKey = "test")).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val toolCall = assertIs<ToolCall>(done.message.content.single())
        assertEquals("call_1", toolCall.id)
        assertEquals("read", toolCall.name)
        assertEquals("""{"path":"README.md"}""", toolCall.arguments)
    }

    // ---------------------------------------------------------------------
    // Cases from pi test/openai-completions-retry.test.ts
    // ---------------------------------------------------------------------

    @Test
    fun `provider retries continue through consecutive retryable failures`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(429, "rate limited", mapOf("retry-after-ms" to listOf("10")))
        transport.enqueueError(500, "server error", mapOf("retry-after-ms" to listOf("10")))
        transport.enqueueResponse(sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        val events = api(transport)
            .stream(model, context, OpenAiCompletionsOptions(apiKey = "test", maxRetries = 2, maxRetryDelayMs = 100))
            .toList()
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun `fails immediately when a provider-requested retry delay exceeds the limit`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(429, "rate limited", mapOf("retry-after" to listOf("277403")))
        val events = api(transport)
            .stream(model, context, OpenAiCompletionsOptions(apiKey = "test", maxRetries = 2, maxRetryDelayMs = 1000))
            .toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(StopReason.ERROR, error.reason)
        // Pin-identical prefix; the suffix carries the transport exception
        // message ("Provider returned HTTP 429") where pi appends the SDK
        // error text including the body.
        assertTrue("Server requested 277403s retry delay (max: 1s)" in (error.error.errorMessage ?: ""))
        assertEquals(1, transport.requests.size)
    }

    // ---------------------------------------------------------------------
    // Cases from pi test/openai-completions-empty-tools.test.ts (Cloudflare
    // AI Gateway /compat shaping) and test/openai-completions-prompt-cache.test.ts
    // (Fireworks affinity), driven by the generated catalog asset.
    // ---------------------------------------------------------------------

    private var realCatalog: ProviderCatalog? = null

    /** The generated asset, mirroring ProviderCatalogTest's realAsset(). */
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

    private fun cloudflareKimi() = realAsset()
        .getProvider("cloudflare-ai-gateway")!!
        .model("workers-ai/@cf/moonshotai/kimi-k2.6")!!

    private fun cloudflareEnv() = mapOf(
        "CLOUDFLARE_ACCOUNT_ID" to "account-id",
        "CLOUDFLARE_GATEWAY_ID" to "gateway-id",
    )

    @Test
    fun `cloudflare compat gateway model uses conservative openai-compatible fields`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport)
            .stream(
                cloudflareKimi(),
                Context(systemPrompt = "You are helpful.", messages = listOf(UserMessage.ofText("hi"))),
                OpenAiCompletionsOptions(
                    headers = mapOf(
                        "cf-aig-authorization" to "Bearer cf-token",
                        "Authorization" to null,
                    ),
                    env = cloudflareEnv(),
                    maxTokens = 1234,
                    reasoningEffort = ModelThinkingLevel.HIGH,
                ),
            )
            .toList()
        val request = transport.requests.single()
        assertEquals(
            "https://gateway.ai.cloudflare.com/v1/account-id/gateway-id/compat/chat/completions",
            request.url,
        )
        assertNull(request.bearerToken)
        assertEquals("Bearer cf-token", request.headers["cf-aig-authorization"])
        assertTrue(request.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(1234L, body["max_tokens"]!!.jsonPrimitive.longOrNull)
        assertNull(body["max_completion_tokens"])
        assertNull(body["reasoning_effort"])
        assertNull(body["store"])
    }

    @Test
    fun `cloudflare byok keeps inline upstream authorization alongside gateway auth`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport)
            .stream(
                cloudflareKimi(),
                context,
                OpenAiCompletionsOptions(
                    headers = mapOf(
                        "Authorization" to "Bearer upstream-token",
                        "cf-aig-authorization" to "Bearer cf-token",
                    ),
                    env = cloudflareEnv(),
                ),
            )
            .toList()
        val headers = transport.requests.single().headers
        assertEquals("Bearer upstream-token", headers["Authorization"])
        assertEquals("Bearer cf-token", headers["cf-aig-authorization"])
        assertNull(transport.requests.single().bearerToken)
    }

    @Test
    fun `cloudflare workers ai model sends session affinity headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport)
            .stream(
                cloudflareKimi(),
                context,
                OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-1", env = cloudflareEnv()),
            )
            .toList()
        val headers = transport.requests.single().headers
        assertEquals("session-1", headers["session_id"])
        assertEquals("session-1", headers["x-client-request-id"])
        assertEquals("session-1", headers["x-session-affinity"])
    }

    @Test
    fun `fireworks catalog model sends session affinity header`() = runTest {
        val fireworks = realAsset().getProvider("fireworks")!!.model("accounts/fireworks/models/glm-5p2")!!
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport)
            .stream(fireworks, context, OpenAiCompletionsOptions(apiKey = "k", sessionId = "fireworks-session"))
            .toList()
        val headers = transport.requests.single().headers
        assertEquals("fireworks-session", headers["x-session-affinity"])
    }

    // ---------------------------------------------------------------------
    // Cases from pi test/openai-completions-empty-tools.test.ts
    // (max_completion_tokens field for stock OpenAI models)
    // ---------------------------------------------------------------------

    @Test
    fun `streamSimple uses max_completion_tokens for openai models`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport).streamSimple(TestCatalogs.GPT_4O, context, SimpleStreamOptions(apiKey = "k")).toList()
        var body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(TestCatalogs.GPT_4O.maxTokens.toLong(), body["max_completion_tokens"]!!.jsonPrimitive.longOrNull)
        assertNull(body["max_tokens"])

        transport.enqueueResponse(sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        api(transport)
            .streamSimple(TestCatalogs.GPT_4O, context, SimpleStreamOptions(apiKey = "k", maxTokens = 1234))
            .toList()
        body = Json.parseToJsonElement(transport.requests.last().body.decodeToString()).jsonObject
        assertEquals(1234L, body["max_completion_tokens"]!!.jsonPrimitive.longOrNull)
        assertNull(body["max_tokens"])
    }

    // ---------------------------------------------------------------------
    // Cases from pi test/openai-completions-thinking-as-text.test.ts
    // ---------------------------------------------------------------------

    @Test
    fun `requiresThinkingAsText replay reaches the endpoint`() = runTest {
        val asText = TestCatalogs.GPT_4O.copy(compat = TestCatalogs.GPT_4O.compat.copy(requiresThinkingAsText = true))
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":null}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                "[DONE]",
            ),
        )
        val events = api(transport)
            .stream(
                asText,
                Context(
                    messages = listOf(
                        UserMessage.ofText("hello"),
                        AssistantMessage(
                            content = listOf(
                                ThinkingContent("internal reasoning"),
                                TextContent("visible answer"),
                            ),
                            api = "openai-completions",
                            provider = "openai",
                            model = asText.id,
                        ),
                        UserMessage.ofText("continue"),
                    ),
                ),
                OpenAiCompletionsOptions(apiKey = "test-key"),
            )
            .toList()
        assertEquals(1, transport.requests.size)
        val body = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals(
            Json.parseToJsonElement(
                """[{"type":"text","text":"internal reasoning"},{"type":"text","text":"visible answer"}]""",
            ),
            assistant["content"],
        )
        assertIs<AssistantMessageEvent.Done>(events.last())
    }
}
