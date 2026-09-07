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
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.utils.ProviderRetry

/**
 * Partial snapshots must reflect the full accumulated message at each delta
 * (pi's live `partial` reference observes the same state) while reusing the
 * immutable content instances of blocks a delta did not touch. Only the
 * changed block may be re-rendered per delta.
 */
class PartialSnapshotReuseTest {

    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun retry() = ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 })
    private fun clock() = FakeClock(1_770_000_000_000L)

    @Test
    fun `anthropic reuses unchanged block instances across deltas`() = runTest {
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
        // The thinking block preceding the text is the same immutable
        // instance in every text-delta snapshot.
        val first = deltas[0].partial.content[0]
        val second = deltas[1].partial.content[0]
        assertTrue(first === second, "unchanged block must reuse its content instance")
        val thinking = assertIs<ThinkingContent>(first)
        assertEquals("deep", thinking.thinking)
        assertEquals("sig", thinking.thinkingSignature)
        // The changed block reflects full accumulated state at each delta.
        assertEquals("Hel", assertIs<TextContent>(deltas[0].partial.content[1]).text)
        assertEquals("Hello", assertIs<TextContent>(deltas[1].partial.content[1]).text)
        // Earlier snapshots are unaffected by later deltas.
        assertEquals("Hel", assertIs<TextContent>(deltas[0].partial.content[1]).text)
    }

    @Test
    fun `openai completions reuses unchanged block instances across deltas`() = runTest {
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
        val textBlock = toolDeltas[0].partial.content[0]
        assertTrue(
            textBlock === toolDeltas[1].partial.content[0],
            "unchanged text block must reuse its content instance"
        )
        assertEquals("pre ", assertIs<TextContent>(textBlock).text)
        assertEquals(
            """{"path":"/x"}""",
            assertIs<ToolCall>(toolDeltas[1].partial.content[1]).arguments
        )
        // Accumulated arguments on the first snapshot, then still intact later.
        assertEquals(
            """{"pa""",
            assertIs<ToolCall>(toolDeltas[0].partial.content[1]).arguments
        )
        assertEquals("pre ", assertIs<TextContent>(toolDeltas[0].partial.content[0]).text)
    }

    @Test
    fun `openai responses reuses unchanged block instances across deltas`() = runTest {
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
        val thinking = textDeltas[0].partial.content[0]
        assertTrue(
            thinking === textDeltas[1].partial.content[0],
            "unchanged reasoning block must reuse its content instance"
        )
        assertEquals("hmm", assertIs<ThinkingContent>(thinking).thinking)
        assertEquals("He", assertIs<TextContent>(textDeltas[0].partial.content[1]).text)
        assertEquals("Hello", assertIs<TextContent>(textDeltas[1].partial.content[1]).text)
        assertEquals("He", assertIs<TextContent>(textDeltas[0].partial.content[1]).text)
    }

    @Test
    fun `mistral reuses unchanged block instances across deltas`() = runTest {
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
        assertEquals("a", assertIs<TextContent>(textDeltas[0].partial.content.single()).text)
        assertEquals("ab", assertIs<TextContent>(textDeltas[1].partial.content.single()).text)
        val toolDelta = events.filterIsInstance<AssistantMessageEvent.ToolCallDelta>().single()
        val textAfter = toolDelta.partial.content[0]
        assertTrue(
            textAfter === textDeltas[1].partial.content[0],
            "closed text block must reuse its content instance"
        )
        assertEquals("ab", assertIs<TextContent>(textAfter).text)
    }

    @Test
    fun `google reuses closed block instances across deltas`() = runTest {
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
        val thinking = textDeltas[0].partial.content[0]
        assertTrue(
            thinking === textDeltas[1].partial.content[0],
            "closed thinking block must reuse its content instance"
        )
        assertEquals("think", assertIs<ThinkingContent>(thinking).thinking)
        assertEquals("He", assertIs<TextContent>(textDeltas[0].partial.content[1]).text)
        assertEquals("Hello", assertIs<TextContent>(textDeltas[1].partial.content[1]).text)
        assertEquals("He", assertIs<TextContent>(textDeltas[0].partial.content[1]).text)
    }
}
