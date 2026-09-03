package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.ProviderAuthException
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.SimpleToolChoice
import works.resolve.pathfinder.ai.core.ToolChoice
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.transport.NetworkException
import works.resolve.pathfinder.ai.utils.ProviderRetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MistralConversationsApiTest {

    private val model = mistralModel()
    private val context = Context(messages = listOf(UserMessage.ofText("hello")))

    private fun api(transport: FakeTransport) = MistralConversationsApi(
        transport,
        clock = FakeClock(1_770_000_000_000L),
    )

    private fun terminalEvent(finishReason: String = "stop") = """
        {"id":"mistral-response-id","model":"${model.id}",
         "choices":[{"index":0,"finish_reason":"$finishReason","delta":{}}],
         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
    """.trimIndent()

    @Test
    fun `serializes payloads to the Mistral wire format`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))
        val imageModel = mistralModel(input = listOf(InputModality.TEXT, InputModality.IMAGE))

        val done = api(transport).stream(
            imageModel,
            Context(
                systemPrompt = "Be precise",
                messages = listOf(
                    UserMessage(
                        listOf(
                            TextContent("describe"),
                            ImageContent("aGVsbG8=", "image/png"),
                        ),
                    ),
                ),
                tools = listOf(
                    Tool(
                        name = "lookup",
                        description = "Look something up",
                        parameters = Json.parseToJsonElement(
                            """{"type":"object","properties":{"query":{"type":"string"}}}""",
                        ),
                    ),
                ),
            ),
            MistralOptions(
                apiKey = "secret",
                sessionId = "session-1",
                headers = mapOf("x-custom" to "value"),
                maxTokens = 123,
                promptMode = MistralPromptMode.REASONING,
                reasoningEffort = "high",
                toolChoice = ToolChoice.Function("lookup"),
            ),
        ).toList().last()
        assertIs<AssistantMessageEvent.Done>(done)

        val request = transport.requests.single()
        assertEquals("https://api.mistral.ai/v1/chat/completions", request.url)
        assertEquals("secret", request.bearerToken)
        assertEquals("text/event-stream", request.headers["accept"])
        assertEquals("session-1", request.headers["x-affinity"])
        assertEquals("value", request.headers["x-custom"])

        val wire = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertEquals(model.id, wire["model"]!!.jsonPrimitive.content)
        assertEquals(123, wire["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("reasoning", wire["prompt_mode"]!!.jsonPrimitive.content)
        assertEquals("high", wire["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("session-1", wire["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals(
            """{"type":"function","function":{"name":"lookup"}}""",
            wire["tool_choice"].toString(),
        )
        assertFalse(wire.containsKey("maxTokens"))
        assertFalse(wire.containsKey("promptMode"))
        assertFalse(wire.containsKey("reasoningEffort"))
        assertFalse(wire.containsKey("promptCacheKey"))

        val tools = wire["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals(
            """{"type":"function","function":{"name":"lookup","description":"Look something up",""" +
                """"parameters":{"type":"object","properties":{"query":{"type":"string"}}},"strict":false}}""",
            tools[0].toString(),
        )

        assertEquals(
            """[{"role":"system","content":"Be precise"},{"role":"user","content":[""" +
                """{"type":"text","text":"describe"},""" +
                """{"type":"image_url","image_url":"data:image/png;base64,aGVsbG8="}]}]""",
            wire["messages"].toString(),
        )
    }

    @Test
    fun `serializes assistant thinking, tool calls, and tool results for replay`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))
        val imageModel = mistralModel(input = listOf(InputModality.TEXT, InputModality.IMAGE))

        api(transport).stream(
            imageModel,
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("reason"),
                            TextContent("answer"),
                            ToolCall("abc123456", "lookup", """{"query":"pi"}"""),
                        ),
                        api = model.api,
                        provider = model.provider,
                        model = model.id,
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage(
                        toolCallId = "abc123456",
                        toolName = "lookup",
                        content = listOf(
                            TextContent("found"),
                            ImageContent("aGVsbG8=", "image/png"),
                        ),
                    ),
                ),
            ),
            MistralOptions(apiKey = "test"),
        ).toList()

        val wire = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals(
            """[{"role":"assistant","prefix":false,"content":[""" +
                """{"type":"thinking","thinking":[{"type":"text","text":"reason"}]},""" +
                """{"type":"text","text":"answer"}],""" +
                """"tool_calls":[{"id":"abc123456","type":"function",""" +
                """"function":{"name":"lookup","arguments":"{\"query\":\"pi\"}"},"index":0}]},""" +
                """{"role":"tool","tool_call_id":"abc123456","name":"lookup","content":[""" +
                """{"type":"text","text":"found"},""" +
                """{"type":"image_url","image_url":"data:image/png;base64,aGVsbG8="}]}]""",
            wire["messages"].toString(),
        )
    }

    @Test
    fun `normalizes foreign tool call ids on replay`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))

        api(transport).stream(
            model,
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ToolCall("resp_abc|with-pipes-and-more", "lookup", """{"q":1}"""),
                        ),
                        api = "openai-responses",
                        provider = "openai",
                        model = "gpt-x",
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage(
                        toolCallId = "resp_abc|with-pipes-and-more",
                        toolName = "lookup",
                        content = listOf(TextContent("ok")),
                    ),
                ),
            ),
            MistralOptions(apiKey = "test"),
        ).toList()

        val messages = Json.parseToJsonElement(transport.requests.single().body.decodeToString())
            .jsonObject["messages"]!!.jsonArray
        val callId = messages[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(9, callId.length)
        assertTrue(callId.all { it.isLetterOrDigit() })
        assertEquals(callId, messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses native thinking, text, tool calls, and cached-token usage`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"response-1","choices":[{"index":0,"finish_reason":null,
                   "delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"reason"}]}]}}]}""",
                """{"id":"response-1","choices":[{"index":0,"finish_reason":null,
                   "delta":{"content":[{"type":"text","text":"answer"}]}}]}""",
                """{"id":"response-1","choices":[{"index":0,"finish_reason":null,
                   "delta":{"tool_calls":[{"id":"abc123456","index":0,
                   "function":{"name":"lookup","arguments":"{\"query\":"}}]}}]}""",
                """{"id":"response-1","choices":[{"index":0,"finish_reason":"tool_calls",
                   "delta":{"tool_calls":[{"id":"abc123456","index":0,
                   "function":{"name":"lookup","arguments":"\"pi\"}"}}]}}],
                   "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14,
                   "prompt_tokens_details":{"cached_tokens":3}}}""",
                "[DONE]",
            ),
        )

        val events = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        val message = done.message
        assertEquals(StopReason.TOOL_USE, message.stopReason)
        assertEquals("tool_calls", message.rawStopReason)
        assertEquals("response-1", message.responseId)
        assertEquals(
            listOf(
                ThinkingContent("reason"),
                TextContent("answer"),
                ToolCall("abc123456", "lookup", """{"query":"pi"}"""),
            ),
            message.content,
        )
        assertEquals(7, message.usage.input)
        assertEquals(4, message.usage.output)
        assertEquals(3, message.usage.cacheRead)
        assertEquals(0, message.usage.cacheWrite)
        assertEquals(14, message.usage.totalTokens)
        assertEquals(7 * 2.0 / 1_000_000 + 4 * 6.0 / 1_000_000 + 3 * 0.5 / 1_000_000, message.usage.cost.total)

        // The open text block closes when the tool call starts; toolcall_end comes last.
        val kinds = events.drop(1).dropLast(1).map { it::class.simpleName }
        assertEquals(
            listOf(
                "ThinkingStart", "ThinkingDelta", "ThinkingEnd",
                "TextStart", "TextDelta", "TextEnd",
                "ToolCallStart", "ToolCallDelta", "ToolCallDelta", "ToolCallEnd",
            ),
            kinds,
        )
    }

    @Test
    fun `indexed tool call chunks merge even when later fragments carry no id (pi 6c87d9a02, #8387)`() = runTest {
        // #8387 gateway shape: only the first indexed chunk carries the id;
        // continuation chunks carry index alone.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"response-1","choices":[{"index":0,"finish_reason":null,
                   "delta":{"tool_calls":[{"id":"abc123456","index":0,
                   "function":{"name":"lookup","arguments":"{\"query\":"}}]}}]}""",
                """{"id":"response-1","choices":[{"index":0,"finish_reason":null,
                   "delta":{"tool_calls":[{"index":0,
                   "function":{"name":"","arguments":"\"pi\"}"}}]}}]}""",
                terminalEvent(finishReason = "tool_calls"),
            ),
        )

        val events = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(
            listOf(ToolCall("abc123456", "lookup", """{"query":"pi"}""")),
            done.message.content,
        )
        // One tool block: exactly one start and one end event.
        assertEquals(1, events.count { it is AssistantMessageEvent.ToolCallStart })
        assertEquals(1, events.count { it is AssistantMessageEvent.ToolCallEnd })
    }

    @Test
    fun `indexed chunks sharing an id stay distinct tool calls`() = runTest {
        // pi keys by `toolCall.index ?? callId`, so the index dominates: a
        // shared id across two indexes is two tool calls.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"response-1","choices":[{"index":0,"finish_reason":null,
                   "delta":{"tool_calls":[{"id":"shared1","index":0,
                   "function":{"name":"lookup","arguments":"{\"q\":"}}]}}]}""",
                """{"id":"response-1","choices":[{"index":0,"finish_reason":"tool_calls",
                   "delta":{"tool_calls":[{"id":"shared1","index":1,
                   "function":{"name":"lookup","arguments":"\"x\""}}]}}]}""",
                terminalEvent(finishReason = "tool_calls"),
            ),
        )

        val events = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(
            listOf(
                ToolCall("shared1", "lookup", "{\"q\":"),
                ToolCall("shared1", "lookup", "\"x\""),
            ),
            done.message.content,
        )
    }

    @Test
    fun `parses plain string content deltas`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"response-bytewise","choices":[{"index":0,"finish_reason":"stop",
                   "delta":{"content":"héllo 🌍"}}],
                   "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""",
                "[DONE]",
            ),
        )
        val done = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Done>(done)
        assertEquals(listOf(TextContent("héllo 🌍")), done.message.content)
    }

    @Test
    fun `honors case-insensitive header overrides and explicit affinity suppression`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))
        val headerModel = model.copy(
            headers = mapOf("Authorization" to "Bearer model-key", "X-Affinity" to "model-affinity"),
        )

        api(transport).stream(
            headerModel,
            context,
            MistralOptions(
                apiKey = "request-key",
                sessionId = "automatic-affinity",
                headers = mapOf(
                    "authorization" to null,
                    "x-affinity" to null,
                    "User-Agent" to "custom-agent",
                ),
            ),
        ).toList()

        val headers = transport.requests.single().headers
        assertNull(transport.requests.single().bearerToken)
        assertFalse(headers.keys.any { it.lowercase() == "authorization" })
        assertFalse(headers.keys.any { it.lowercase() == "x-affinity" })
        assertEquals("custom-agent", headers["User-Agent"])
    }

    @Test
    fun `applies the request timeout while waiting for a chunk`() = runTest {
        val transport = FakeTransport()
        transport.outcomes.add {
            throw NetworkException(java.io.IOException("timeout"))
        }
        val error = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test", timeoutMs = 5))
            .toList()
            .last()
        val errorEvent = assertIs<AssistantMessageEvent.Error>(error)
        assertEquals(StopReason.ERROR, errorEvent.error.stopReason)
        assertContains(errorEvent.error.errorMessage ?: "", "timeout")
    }

    @Test
    fun `default request timeout is 60s when timeoutMs is unset`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent()))
        api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()
        assertEquals(60_000L, transport.requests.single().timeoutMs)
    }

    @Test
    fun `retryable failures are not retried even with maxRetries greater than zero`() = runTest {
        val transport = FakeTransport()
        transport.outcomes.add {
            throw NetworkException(java.io.IOException("connection reset"))
        }
        val error = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test", maxRetries = 3))
            .toList()
            .last()
        // pi streams via a raw fetch with no retry wrapper.
        assertIs<AssistantMessageEvent.Error>(error)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `empty text chunk opens a text block with an empty delta`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"index":0,"delta":{"content":[{"type":"text","text":""}]}}]}""",
                terminalEvent(),
            ),
        )
        val events = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()
        val start = events.filterIsInstance<AssistantMessageEvent.TextStart>().single()
        val delta = events.filterIsInstance<AssistantMessageEvent.TextDelta>().single()
        assertEquals(start.contentIndex, delta.contentIndex)
        assertEquals("", delta.delta)
        assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(listOf(TextContent("")), (events.last() as AssistantMessageEvent.Done).message.content)
    }

    @Test
    fun `preserves HTTP status and response bodies in errors`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(403, """{"message":"blocked by gateway"}""")
        val error = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()
            .last()
        val errorEvent = assertIs<AssistantMessageEvent.Error>(error)
        assertEquals(StopReason.ERROR, errorEvent.error.stopReason)
        assertEquals(
            """Mistral API error (403): {"message":"blocked by gateway"}""",
            errorEvent.error.errorMessage,
        )
    }

    @Test
    fun `missing api key fails without emitting a start event`() = runTest {
        val transport = FakeTransport()
        val events = api(transport)
            .stream(model, context, MistralOptions())
            .toList()
        val error = assertIs<AssistantMessageEvent.Error>(events.single())
        assertEquals("No API key for provider: mistral", error.error.errorMessage)
    }

    @Test
    fun `missing finish reason is an error`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"r","choices":[{"index":0,"finish_reason":null,"delta":{"content":"hi"}}]}""",
                "[DONE]",
            ),
        )
        val error = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Error>(error)
        assertEquals("Mistral stream ended without a finish reason", error.error.errorMessage)
    }

    @Test
    fun `empty finish reason is absent like pi truthiness`() = runTest {
        // pi guards with `if (choice.finish_reason)`: "" is falsy, so the
        // raw-stop-reason mapping never sees it and the stream ends pending.
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"r","choices":[{"index":0,"finish_reason":"","delta":{"content":"hi"}}]}""",
                "[DONE]",
            ),
        )
        val error = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Error>(error)
        assertEquals("Mistral stream ended without a finish reason", error.error.errorMessage)
        assertNull(error.error.rawStopReason)
    }

    @Test
    fun `preserves raw finish reasons for successful stops`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent("stop"), "[DONE]"))
        val done = api(transport)
            .stream(model, context, MistralOptions(apiKey = "test"))
            .toList()
            .last()
        assertIs<AssistantMessageEvent.Done>(done)
        assertEquals(StopReason.STOP, done.reason)
        assertEquals("stop", done.message.rawStopReason)
        assertNull(done.message.errorMessage)
    }

    @Test
    fun `preserves raw finish reasons for provider error stops`() = runTest {
        for (reason in listOf("error", "unmapped_error")) {
            val transport = FakeTransport()
            transport.enqueueResponse(sse(terminalEvent(reason), "[DONE]"))
            val error = api(transport)
                .stream(model, context, MistralOptions(apiKey = "test"))
                .toList()
                .last()
            val errorEvent = assertIs<AssistantMessageEvent.Error>(error)
            assertEquals(StopReason.ERROR, errorEvent.error.stopReason)
            assertEquals(reason, errorEvent.error.rawStopReason)
            assertEquals("Provider stopped with: $reason", errorEvent.error.errorMessage)
        }
    }

    @Test
    fun `maps length and model_length to LENGTH`() = runTest {
        for (reason in listOf("length", "model_length")) {
            val transport = FakeTransport()
            transport.enqueueResponse(sse(terminalEvent(reason), "[DONE]"))
            val done = api(transport)
                .stream(model, context, MistralOptions(apiKey = "test"))
                .toList()
                .last()
            assertIs<AssistantMessageEvent.Done>(done)
            assertEquals(StopReason.LENGTH, done.reason)
        }
    }

    @Test
    fun `streamSimple uses reasoning_effort for Mistral Small and Medium`() = runTest {
        for (id in listOf("mistral-small-2603", "mistral-small-latest", "mistral-medium-3.5")) {
            val payload = captureStreamSimplePayload(
                mistralModel(id = id, reasoning = true),
                SimpleStreamOptions(apiKey = "test", reasoning = ThinkingLevel.MEDIUM),
            )
            assertNull(payload["prompt_mode"])
            assertEquals("high", payload["reasoning_effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `streamSimple omits reasoning controls when thinking is off`() = runTest {
        for (id in listOf("mistral-small-2603", "mistral-medium-3.5")) {
            val payload = captureStreamSimplePayload(
                mistralModel(id = id, reasoning = true),
                SimpleStreamOptions(apiKey = "test"),
            )
            assertNull(payload["reasoning_effort"])
            assertNull(payload["prompt_mode"])
        }
    }

    @Test
    fun `streamSimple uses prompt_mode for Magistral reasoning models`() = runTest {
        val payload = captureStreamSimplePayload(
            mistralModel(id = "magistral-medium-latest", reasoning = true),
            SimpleStreamOptions(apiKey = "test", reasoning = ThinkingLevel.MEDIUM),
        )
        assertEquals("reasoning", payload["prompt_mode"]!!.jsonPrimitive.content)
        assertNull(payload["reasoning_effort"])
    }

    @Test
    fun `streamSimple uses the session id as prompt cache key`() = runTest {
        val payload = captureStreamSimplePayload(
            model,
            SimpleStreamOptions(apiKey = "test", sessionId = "session-123"),
        )
        assertEquals("session-123", payload["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streamSimple omits prompt cache key when cache retention is disabled`() = runTest {
        val payload = captureStreamSimplePayload(
            model,
            SimpleStreamOptions(
                apiKey = "test",
                sessionId = "session-123",
                cacheRetention = works.resolve.pathfinder.ai.core.CacheRetention.NONE,
            ),
        )
        assertNull(payload["prompt_cache_key"])
    }

    @Test
    fun `streamSimple maps tool choice and requires an api key`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))
        api(transport).streamSimple(
            model,
            context,
            SimpleStreamOptions(apiKey = "k", toolChoice = SimpleToolChoice.Auto),
        ).toList()
        val wire = Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
        assertEquals("auto", wire["tool_choice"]!!.jsonPrimitive.content)

        val transport2 = FakeTransport()
        // pi throws synchronously before opening the stream.
        val thrown = kotlin.test.assertFailsWith<ProviderAuthException> {
            api(transport2).streamSimple(model, context, SimpleStreamOptions())
        }
        assertEquals("No API key for provider: mistral", thrown.message)
        assertTrue(transport2.requests.isEmpty())
    }

    @Test
    fun `non-image models replace images with in-place deduplicated placeholders`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))
        api(transport).stream(
            mistralModel(input = listOf(InputModality.TEXT)),
            Context(
                messages = listOf(
                    UserMessage(
                        listOf(
                            TextContent("look"),
                            ImageContent("aGVsbG8=", "image/png"),
                            ImageContent("aGVsbG8=", "image/png"),
                            TextContent("again"),
                            ImageContent("aGVsbG8=", "image/jpeg"),
                        ),
                    ),
                ),
            ),
            MistralOptions(apiKey = "test"),
        ).toList()
        val messages = Json.parseToJsonElement(transport.requests.single().body.decodeToString())
            .jsonObject["messages"]!!.jsonArray
        // pi's transformMessages inserts the omission notice in place of each
        // image run, deduplicated.
        assertEquals(
            """[{"type":"text","text":"look"},""" +
                """{"type":"text","text":"(image omitted: model does not support images)"},""" +
                """{"type":"text","text":"again"},""" +
                """{"type":"text","text":"(image omitted: model does not support images)"}]""",
            messages[0].jsonObject["content"].toString(),
        )
    }

    @Test
    fun `non-image models downgrade tool result images to a placeholder line`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))
        api(transport).stream(
            mistralModel(input = listOf(InputModality.TEXT)),
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(ToolCall("abc123456", "lookup", "{}")),
                        api = model.api,
                        provider = model.provider,
                        model = model.id,
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage(
                        toolCallId = "abc123456",
                        toolName = "lookup",
                        content = listOf(TextContent("found"), ImageContent("aGVsbG8=", "image/png")),
                    ),
                    UserMessage.ofText("thanks"),
                ),
            ),
            MistralOptions(apiKey = "test"),
        ).toList()
        val messages = Json.parseToJsonElement(transport.requests.single().body.decodeToString())
            .jsonObject["messages"]!!.jsonArray
        // pi's transformMessages replaces the image with the non-vision tool
        // placeholder, which buildToolResultText joins into the tool text.
        assertEquals(
            "found\n(tool image omitted: model does not support images)",
            messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `orphaned tool calls get synthetic error results and errored turns are skipped`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))
        api(transport).stream(
            model,
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            TextContent("partial"),
                            ToolCall("abc123456", "lookup", "{}"),
                        ),
                        api = "openai-responses",
                        provider = "openai",
                        model = "gpt-x",
                        stopReason = StopReason.ERROR,
                        errorMessage = "boom",
                    ),
                    AssistantMessage(
                        content = listOf(
                            TextContent("will call"),
                            ToolCall("resp_orphan|with-pipes", "lookup", "{}"),
                        ),
                        api = "openai-responses",
                        provider = "openai",
                        model = "gpt-x",
                        stopReason = StopReason.TOOL_USE,
                    ),
                    UserMessage.ofText("interrupted"),
                ),
            ),
            MistralOptions(apiKey = "test"),
        ).toList()
        val messages = Json.parseToJsonElement(transport.requests.single().body.decodeToString())
            .jsonObject["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals("assistant", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val callId = messages[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(9, callId.length)
        assertTrue(callId.all { it.isLetterOrDigit() })
        assertEquals("tool", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(callId, messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(
            "[tool error] No result provided",
            messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("user", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cross-model thinking replays as plain text`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(terminalEvent(), "[DONE]"))
        api(transport).stream(
            model,
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("deep thought", thinkingSignature = "sig"),
                            ThinkingContent("redacted", redacted = true),
                            TextContent("answer"),
                        ),
                        api = "anthropic-messages",
                        provider = "anthropic",
                        model = "claude-x",
                        stopReason = StopReason.STOP,
                    ),
                    UserMessage.ofText("continue"),
                ),
            ),
            MistralOptions(apiKey = "test"),
        ).toList()
        val messages = Json.parseToJsonElement(transport.requests.single().body.decodeToString())
            .jsonObject["messages"]!!.jsonArray
        // pi's transformMessages: foreign thinking becomes text; redacted thinking is dropped.
        assertEquals(
            """[{"role":"assistant","prefix":false,""" +
                """"content":[{"type":"text","text":"deep thought"},{"type":"text","text":"answer"}]},""" +
                """{"role":"user","content":"continue"}]""",
            messages.toString(),
        )
    }

    private fun captureStreamSimplePayload(
        streamModel: Model,
        options: SimpleStreamOptions,
    ): kotlinx.serialization.json.JsonObject {
        val transport = FakeTransport()
        // The request is allowed to fail after the payload is captured.
        transport.outcomes.add { throw NetworkException(java.io.IOException("no route")) }
        val result = kotlinx.coroutines.runBlocking {
            api(transport).streamSimple(streamModel, context, options).toList()
        }
        assertTrue(result.last() is AssistantMessageEvent.Error)
        assertEquals(1, transport.requests.size)
        return Json.parseToJsonElement(transport.requests.single().body.decodeToString()).jsonObject
    }
}

internal fun mistralModel(
    id: String = "mistral-large-latest",
    reasoning: Boolean = false,
    input: List<InputModality> = listOf(InputModality.TEXT),
): Model = Model(
    id = id,
    name = id,
    api = "mistral-conversations",
    provider = "mistral",
    baseUrl = "https://api.mistral.ai",
    reasoning = reasoning,
    input = input,
    cost = ModelCost(input = 2.0, output = 6.0, cacheRead = 0.5),
    contextWindow = 131_000,
    maxTokens = 131_000,
)
