package works.resolve.distill.ai.utils

import works.resolve.distill.ai.core.AssistantMessage
import works.resolve.distill.ai.core.Context
import works.resolve.distill.ai.core.ImageContent
import works.resolve.distill.ai.core.StopReason
import works.resolve.distill.ai.core.TextContent
import works.resolve.distill.ai.core.Tool
import works.resolve.distill.ai.core.ToolCall
import works.resolve.distill.ai.core.ToolResultMessage
import works.resolve.distill.ai.core.Usage
import works.resolve.distill.ai.core.UserMessage
import works.resolve.distill.ai.testing.TestCatalogs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive

class TokenEstimateTest {

    @Test
    fun `text estimation uses four chars per token with ceiling`() {
        assertEquals(1, estimateTextTokens("hi")) // 2 chars
        assertEquals(1, estimateTextTokens("abcd")) // exactly 4
        assertEquals(2, estimateTextTokens("abcde")) // 5 chars
    }

    @Test
    fun `user and tool result content estimates text and images`() {
        val user = UserMessage(
            listOf(TextContent("1234"), ImageContent(data = "x", mimeType = "image/png")),
        )
        // 4 text chars + 4800 image chars = 4804 / 4 = 1201
        assertEquals(1201, estimateMessageTokens(user))

        val result = ToolResultMessage("id", "tool", listOf(TextContent("ab")))
        assertEquals(1, estimateMessageTokens(result))
    }

    @Test
    fun `assistant tool call uses name and raw arguments string`() {
        val assistant = AssistantMessage(
            content = listOf(
                TextContent("1234"),
                ToolCall(id = "1", name = "get_weather", arguments = """{"city":"SF"}"""),
            ),
            api = "openai-completions",
            provider = "zai",
            model = "glm",
        )
        // name 11 + arguments 13 + text 4 = 28 -> ceil(28/4) = 7
        assertEquals(7, estimateMessageTokens(assistant))
    }

    @Test
    fun `latest applicable assistant usage becomes known prefix`() {
        val assistant = AssistantMessage(
            content = listOf(TextContent("answer")),
            api = "openai-completions",
            provider = "zai",
            model = "glm",
            usage = Usage(totalTokens = 100),
            stopReason = StopReason.STOP,
            timestamp = 2L,
        )
        val context = Context(
            systemPrompt = "system", // covered by usage, must not be added
            messages = listOf(UserMessage.ofText("hi", 1L), assistant, UserMessage.ofText("hello!", 3L)),
            tools = listOf(Tool("t", "d", JsonPrimitive("x"))),
        )
        val estimate = estimateContextTokens(context)
        assertEquals(100, estimate.usageTokens)
        assertEquals(2, estimate.trailingTokens) // ceil(6/4)
        assertEquals(102, estimate.tokens)
        assertEquals(1, estimate.lastUsageIndex)
    }

    @Test
    fun `aborted assistant usage is ignored`() {
        val aborted = AssistantMessage(
            content = emptyList(),
            api = "openai-completions",
            provider = "zai",
            model = "glm",
            usage = Usage(totalTokens = 999),
            stopReason = StopReason.ABORTED,
        )
        val estimate = estimateContextTokens(Context(messages = listOf(aborted)))
        assertNull(estimate.lastUsageIndex)
        assertEquals(0, estimate.usageTokens)
    }

    @Test
    fun `prefix after stale usage falls back to full estimation`() {
        // The assistant response predates an inserted prefix message (timestamp
        // ordering broken), so its usage cannot describe the current prefix.
        val assistant = AssistantMessage(
            content = emptyList(),
            api = "openai-completions",
            provider = "zai",
            model = "glm",
            usage = Usage(totalTokens = 500),
            stopReason = StopReason.STOP,
            timestamp = 1L,
        )
        val laterSummary = UserMessage.ofText("summary", 5L) // inserted after the response
        val estimate = estimateContextTokens(Context(messages = listOf(laterSummary, assistant)))
        assertNull(estimate.lastUsageIndex)
        // Both messages estimated: ceil(7/4) + 0
        assertEquals(2, estimate.tokens)
    }

    @Test
    fun `usage applies again after a newer assistant response`() {
        // Mirrors pi's context-estimate test: a stale usage is ignored, but a
        // later assistant response with its own usage describes the new prefix.
        fun assistant(timestamp: Long, totalTokens: Int) = AssistantMessage(
            content = listOf(TextContent("kept")),
            api = "openai-completions",
            provider = "zai",
            model = "glm",
            usage = Usage(totalTokens = totalTokens),
            stopReason = StopReason.STOP,
            timestamp = timestamp,
        )
        val context = Context(
            messages = listOf(
                UserMessage.ofText("summary", 200L),
                assistant(100L, 9_500), // stale: predates the inserted summary
                UserMessage.ofText("new prompt", 300L),
                assistant(400L, 2_000),
                UserMessage.ofText("tail", 500L),
            ),
        )
        val estimate = estimateContextTokens(context)
        assertEquals(2_000, estimate.usageTokens)
        assertEquals(1, estimate.trailingTokens) // ceil(4/4)
        assertEquals(2_001, estimate.tokens)
        assertEquals(3, estimate.lastUsageIndex)
    }

    @Test
    fun `without usage system prompt and tools are estimated`() {
        val context = Context(
            systemPrompt = "12345678", // 2 tokens
            messages = listOf(UserMessage.ofText("abcd")), // 1 token
            tools = listOf(Tool("t", "d", JsonPrimitive("x"))),
        )
        val estimate = estimateContextTokens(context)
        assertNull(estimate.lastUsageIndex)
        // tools stringified contributes ceil(len/4)
        assertEquals(1 + 2 + estimateTextTokens(context.tools.toString()), estimate.tokens)
    }

    @Test
    fun `clamping keeps 4096 safety tokens and minimum one output`() {
        val model = TestCatalogs.GLM_5_2
        val context = Context(messages = listOf(UserMessage.ofText("hi"))) // 1 token

        // Room exists: explicit value retained.
        assertEquals(500, clampMaxTokensToContext(model, context, 500))
        // Oversized: window 1_000_000 - 1 - 4096.
        assertEquals(1_000_000 - 1 - 4096, clampMaxTokensToContext(model, context, 1_000_000))
        // Constrained window clamps to the minimum of 1.
        assertEquals(1, clampMaxTokensToContext(model.copy(contextWindow = 4097), context, 5000))
    }

    @Test
    fun `non-positive context window skips clamping`() {
        val model = TestCatalogs.GLM_5_2.copy(contextWindow = 0)
        val context = Context(messages = listOf(UserMessage.ofText("hi")))
        assertEquals(5000, clampMaxTokensToContext(model, context, 5000))
        assertEquals(1, clampMaxTokensToContext(model, context, 0))
    }
}
