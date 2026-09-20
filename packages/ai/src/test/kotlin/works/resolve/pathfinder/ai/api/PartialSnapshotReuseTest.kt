package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCost
import works.resolve.pathfinder.ai.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.normalizeContext

/**
 * Delta events carry no snapshot; boundary events (part start/end, done)
 * carry accurate snapshots that reuse the immutable content instances of
 * blocks no delta touched since the previous boundary — only a block a
 * delta landed in may be re-rendered between two boundaries.
 */
class PartialSnapshotReuseTest {

    private val context = normalizeContext(Context(messages = listOf(UserMessage.ofText("hi"))))

    private fun retry() = ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 })
    private fun clock() = FakeClock(1_770_000_000_000L)

    @Test
    fun `anthropic reuses unchanged block instances across boundaries`() = runTest {
        val model = Model(
            id = "claude-sonnet-4-5",
            name = "Claude Sonnet 4.5",
            api = "anthropic-messages",
            provider = "anthropic",
            baseUrl = "https://api.anthropic.com",
            reasoning = true,
            input = listOf(InputModality.TEXT),
            contextWindow = 200_000,
            maxTokens = 64_000
        )
        val transport = FakeTransport()
        transport.enqueueNamedResponse(
            listOf(
                "message_start" to
                    """{"type":"message_start","message":{"id":"m","usage":{"input_tokens":1,"output_tokens":0}}}""",
                "content_block_start" to
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                "content_block_delta" to
                    """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"deep"}}""",
                "content_block_delta" to
                    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "content_block_start" to
                    """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to
                    """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hel"}}""",
                "content_block_delta" to
                    """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"lo"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":1}""",
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":1,"output_tokens":2}}""",
                "message_stop" to """{"type":"message_stop"}"""
            )
        )
        val events = AnthropicMessagesApi(transport, retry(), clock())
            .stream(model, context, AnthropicMessagesOptions(apiKey = "k"))
            .toList()

        val deltas = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("Hel", "lo"), deltas.map { it.delta })
        val thinkingEnd = events.filterIsInstance<AssistantMessageEvent.ThinkingEnd>().single()
        assertEquals("deep", thinkingEnd.content)
        assertEquals(
            "deep",
            assertIs<ThinkingContent>(thinkingEnd.partial.content[0]).thinking
        )
        assertEquals(
            "sig",
            assertIs<ThinkingContent>(thinkingEnd.partial.content[0]).thinkingSignature
        )
        val textStart = events.filterIsInstance<AssistantMessageEvent.TextStart>().single()
        val textEnd = events.filterIsInstance<AssistantMessageEvent.TextEnd>().single()
        // The finished thinking block is the same immutable instance in every
        // later boundary snapshot.
        assertTrue(
            thinkingEnd.partial.content[0] === textStart.partial.content[0],
            "unchanged block must reuse its content instance"
        )
        assertTrue(
            thinkingEnd.partial.content[0] === textEnd.partial.content[0],
            "unchanged block must reuse its content instance"
        )
        // The text block accumulated only through deltas until its end.
        assertEquals("", assertIs<TextContent>(textStart.partial.content[1]).text)
        assertEquals("Hello", textEnd.content)
        assertEquals("Hello", assertIs<TextContent>(textEnd.partial.content[1]).text)
        val done = events.last()
        assertEquals(StopReason.STOP, (done as AssistantMessageEvent.Done).reason)
        assertEquals(done.message.content, textEnd.partial.content)
    }

    @Test
    fun `openai completions reuses unchanged block instances across boundaries`() = runTest {
        val model = Model(
            id = "glm-5.2",
            name = "GLM-5.2",
            api = "openai-completions",
            provider = "zai",
            baseUrl = "https://api.z.ai/api/coding/paas/v4",
            input = listOf(InputModality.TEXT),
            cost = ModelCost(input = 1.4, output = 4.4),
            contextWindow = 200_000,
            maxTokens = 131_072
        )
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"choices":[{"delta":{"content":"pre "}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read","arguments":"{\"pa"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"/x\"}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]"
            )
        )
        val events = OpenAiCompletionsApi(transport, retry(), clock())
            .stream(model, context, OpenAiCompletionsOptions(apiKey = "k"))
            .toList()

        val toolDeltas = events.filterIsInstance<AssistantMessageEvent.ToolCallDelta>()
        assertEquals(listOf("""{"pa""", """th":"/x"}"""), toolDeltas.map { it.delta })
        // The tool-call start scaffold carries the id and name, like pi's
        // block creation.
        val toolStart = events.filterIsInstance<AssistantMessageEvent.ToolCallStart>().single()
        val scaffold = assertIs<ToolCall>(toolStart.partial.content[1])
        assertEquals("c1", scaffold.id)
        assertEquals("read", scaffold.name)
        assertEquals("", scaffold.arguments)
        // The text block is the same immutable instance in every later
        // boundary snapshot, and the tool arguments accumulate to the final
        // call only at the end boundary.
        val textEnd = events.filterIsInstance<AssistantMessageEvent.TextEnd>().single()
        val toolEnd = events.filterIsInstance<AssistantMessageEvent.ToolCallEnd>().single()
        assertTrue(
            textEnd.partial.content[0] === toolEnd.partial.content[0],
            "unchanged text block must reuse its content instance"
        )
        assertEquals("pre ", textEnd.content)
        assertEquals(
            """{"path":"/x"}""",
            assertIs<ToolCall>(toolEnd.partial.content[1]).arguments
        )
        assertEquals("""{"path":"/x"}""", toolEnd.toolCall.arguments)
    }

    @Test
    fun `openai responses accumulates deltas into the done message`() = runTest {
        val model = Model(
            id = "gpt-5-mini",
            name = "GPT-5 Mini",
            api = "openai-responses",
            provider = "openai",
            baseUrl = "https://api.openai.com/v1",
            reasoning = true,
            input = listOf(InputModality.TEXT),
            contextWindow = 400_000,
            maxTokens = 128_000,
            responsesCompat = OpenAiResponsesCompat()
        )
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"type":"response.created","response":{"id":"resp_1"}}""",
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
                """{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"hmm"}""",
                """{"type":"response.output_item.added","output_index":1,
                    "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
                """{"type":"response.output_text.delta","output_index":1,"delta":"He"}""",
                """{"type":"response.output_text.delta","output_index":1,"delta":"llo"}""",
                """{"type":"response.completed","response":{"id":"resp_1","status":"completed",
                    "usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}}""",
                "[DONE]"
            )
        )
        val events = OpenAiResponsesApi(transport, retry(), clock())
            .stream(model, context, OpenAiResponsesOptions(apiKey = "k"))
            .toList()

        val textDeltas = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("He", "llo"), textDeltas.map { it.delta })
        // The completed response finalizes the streamed blocks; the start
        // scaffolds were empty and only the deltas carried content.
        val done = events.last() as AssistantMessageEvent.Done
        assertEquals("hmm", assertIs<ThinkingContent>(done.message.content[0]).thinking)
        assertEquals("Hello", assertIs<TextContent>(done.message.content[1]).text)
    }

    @Test
    fun `mistral reuses closed block instances across boundaries`() = runTest {
        val model = Model(
            id = "mistral-large-latest",
            name = "Mistral Large",
            api = "mistral-conversations",
            provider = "mistral",
            baseUrl = "https://api.mistral.ai",
            input = listOf(InputModality.TEXT),
            contextWindow = 131_000,
            maxTokens = 131_000
        )
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"id":"r1","choices":[{"delta":{"content":"a"}}]}""",
                """{"choices":[{"delta":{"content":"b"}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read","arguments":"{}"}}]}}]}""",
                """{"id":"r1","choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}],
                    "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                "[DONE]"
            )
        )
        val events = MistralConversationsApi(transport, clock())
            .stream(model, context, MistralOptions(apiKey = "k"))
            .toList()

        val textDeltas = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("a", "b"), textDeltas.map { it.delta })
        // The closed text block is finalized at the tool-call start boundary
        // and reused in every later boundary snapshot.
        val toolStart = events.filterIsInstance<AssistantMessageEvent.ToolCallStart>().single()
        assertEquals("ab", assertIs<TextContent>(toolStart.partial.content[0]).text)
        val toolEnd = events.filterIsInstance<AssistantMessageEvent.ToolCallEnd>().single()
        assertTrue(
            toolStart.partial.content[0] === toolEnd.partial.content[0],
            "closed text block must reuse its content instance"
        )
        assertEquals("ab", assertIs<TextContent>(toolEnd.partial.content[0]).text)
        assertEquals("{}", toolEnd.toolCall.arguments)
    }

    @Test
    fun `google reuses closed block instances across boundaries`() = runTest {
        val model = Model(
            id = "gemini-2.5-flash",
            name = "Gemini",
            api = "google-generative-ai",
            provider = "google",
            baseUrl = "https://generativelanguage.googleapis.com",
            reasoning = true,
            input = listOf(InputModality.TEXT),
            contextWindow = 128_000,
            maxTokens = 8_192
        )
        fun part(text: String, thought: Boolean = false) = if (thought) {
            """{"text":"$text","thought":true}"""
        } else {
            """{"text":"$text"}"""
        }
        val thinkPart = part("think", thought = true)
        val hePart = part("He")
        val lloPart = part("llo")
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse(
                """{"candidates":[{"content":{"role":"model","parts":[$thinkPart]}}],"responseId":"r1"}""",
                """{"candidates":[{"content":{"role":"model","parts":[$hePart]}}],
                    "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}""",
                """{"candidates":[{"content":{"role":"model","parts":[$lloPart]}}],
                    "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2,"totalTokenCount":3}}"""
            )
        )
        val events = GoogleGenerativeAiApi(transport, retry(), clock())
            .stream(model, context, GoogleGenerativeAiApi.GoogleOptions(apiKey = "k"))
            .toList()

        val textDeltas = events.filterIsInstance<AssistantMessageEvent.TextDelta>()
        assertEquals(listOf("He", "llo"), textDeltas.map { it.delta })
        // The thinking block closes at the text start boundary and its
        // instance is reused in every later boundary snapshot.
        val thinkingEnd = events.filterIsInstance<AssistantMessageEvent.ThinkingEnd>().single()
        val textEnd = events.filterIsInstance<AssistantMessageEvent.TextEnd>().single()
        assertEquals("think", thinkingEnd.content)
        assertTrue(
            thinkingEnd.partial.content[0] === textEnd.partial.content[0],
            "closed thinking block must reuse its content instance"
        )
        assertEquals("Hello", textEnd.content)
        // The stream ends without a finishReason, so the terminal event is
        // the accurate error snapshot — equal to the last boundary content.
        val error = assertIs<AssistantMessageEvent.Error>(events.last())
        assertEquals(textEnd.partial.content, error.error.content)
    }
}
