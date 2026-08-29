package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Port of pi's packages/ai/test/anthropic-cache-write-1h-cost.test.ts:
 * an Anthropic stream reporting cache_creation.ephemeral_1h_input_tokens
 * populates Usage.cacheWrite1h and prices it at 2x base input.
 */
class AnthropicCacheWrite1hCostTest {

    // claude-opus-4-8: input 5, cacheWrite (5m) 6.25 per Mtok. 1h write = 2x input = 10.
    private val opus = Model(
        id = "claude-opus-4-8",
        name = "Claude Opus 4.8",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "https://api.anthropic.com",
        input = listOf(InputModality.TEXT),
        cost = ModelCost(input = 5.0, output = 25.0, cacheRead = 0.5, cacheWrite = 6.25),
    )

    private val context = Context(messages = listOf(UserMessage.ofText("hi")))

    private fun eventsWithCacheCreation(cacheCreation: String?): List<Pair<String, String>> {
        val baseUsage =
            "\"input_tokens\":100,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":1000000"
        val startUsage = cacheCreation?.let { "$baseUsage,\"cache_creation\":$it" } ?: baseUsage
        return listOf(
            "message_start" to
                """{"type":"message_start","message":{"id":"msg_test","usage":{$startUsage}}}""",
            "content_block_start" to
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":100,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":1000000}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
    }

    private suspend fun streamResult(events: List<Pair<String, String>>): Usage {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(events)
        val done = assertIs<AssistantMessageEvent.Done>(
            AnthropicMessagesApi(
                transport,
                ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 }),
                clock = FakeClock(1_770_000_000_000L),
            ).stream(opus, context, AnthropicMessagesOptions(apiKey = "test-key")).toList().last(),
        )
        return done.message.usage
    }

    @Test
    fun `prices the 1h portion at 2x input and the rest at the 5m rate`() = runTest {
        val usage = streamResult(
            eventsWithCacheCreation(
                """{"ephemeral_5m_input_tokens":600000,"ephemeral_1h_input_tokens":400000}""",
            ),
        )
        assertEquals(1_000_000, usage.cacheWrite)
        assertEquals(400_000, usage.cacheWrite1h)
        // 600k * 6.25/Mtok + 400k * 10/Mtok = 3.75 + 4.0 = 7.75
        assertEquals(7.75, usage.cost.cacheWrite, 1e-10)
    }

    @Test
    fun `falls back to the 5m rate when no breakdown is reported`() = runTest {
        val usage = streamResult(eventsWithCacheCreation(null))
        assertEquals(1_000_000, usage.cacheWrite)
        assertEquals(0, usage.cacheWrite1h)
        // 1M * 6.25/Mtok = 6.25
        assertEquals(6.25, usage.cost.cacheWrite, 1e-10)
    }
}
