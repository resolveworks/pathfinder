package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OpenAI to Anthropic session migration for Copilot Claude. Ports
 * transform-messages-copilot-openai-to-anthropic.test.ts @ b8b873b98.
 */
class TransformMessagesTest {

    // Normalize function matching what AnthropicMessagesApi uses
    private val anthropicNormalizeToolCallId: (id: String, source: AssistantMessage) -> String = { id, _ ->
        id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    }

    private fun makeCopilotClaudeModel(): Model = Model(
        id = "claude-sonnet-4.6",
        name = "Claude Sonnet 4.6",
        api = "anthropic-messages",
        provider = "github-copilot",
        baseUrl = "https://api.individual.githubcopilot.com",
        reasoning = true,
        input = listOf(InputModality.TEXT, InputModality.IMAGE),
        contextWindow = 128000,
        maxTokens = 16000,
    )

    private fun makeAssistantMessage(content: List<Content>): AssistantMessage = AssistantMessage(
        content = content,
        api = "openai-responses",
        provider = "github-copilot",
        model = "gpt-5",
        stopReason = StopReason.TOOL_USE,
    )

    @Test
    fun `converts thinking blocks to plain text when source model differs`() {
        val model = makeCopilotClaudeModel()
        val messages: List<Message> = listOf(
            UserMessage.ofText("hello"),
            AssistantMessage(
                content = listOf(
                    ThinkingContent(
                        thinking = "Let me think about this...",
                        thinkingSignature = "reasoning_content",
                    ),
                    TextContent("Hi there!"),
                ),
                api = "openai-completions",
                provider = "github-copilot",
                model = "gpt-4o",
                stopReason = StopReason.STOP,
            ),
        )

        val result = transformMessages(messages, model, anthropicNormalizeToolCallId)
        val assistantMsg = result.filterIsInstance<AssistantMessage>().single()

        // Thinking block should be converted to text since models differ
        val textBlocks = assistantMsg.content.filterIsInstance<TextContent>()
        val thinkingBlocks = assistantMsg.content.filterIsInstance<ThinkingContent>()
        assertEquals(0, thinkingBlocks.size)
        assertTrue(textBlocks.size >= 2)
    }

    @Test
    fun `removes thoughtSignature from tool calls when migrating between models`() {
        val model = makeCopilotClaudeModel()
        val messages: List<Message> = listOf(
            UserMessage.ofText("run a command"),
            makeAssistantMessage(
                listOf(
                    ToolCall(
                        id = "call_123",
                        name = "bash",
                        arguments = """{"command":"ls"}""",
                        thoughtSignature =
                            """{"type":"reasoning.encrypted","id":"call_123","data":"encrypted"}""",
                    ),
                ),
            ),
            ToolResultMessage(
                toolCallId = "call_123",
                toolName = "bash",
                content = listOf(TextContent("output")),
            ),
        )

        val result = transformMessages(messages, model, anthropicNormalizeToolCallId)
        val assistantMsg = result.filterIsInstance<AssistantMessage>().single()
        val toolCall = assistantMsg.content.filterIsInstance<ToolCall>().single()

        assertNull(toolCall.thoughtSignature)
    }

    @Test
    fun `adds synthetic tool results for trailing orphaned tool calls`() {
        val model = makeCopilotClaudeModel()
        val messages: List<Message> = listOf(
            UserMessage.ofText("read the file"),
            makeAssistantMessage(
                listOf(
                    ToolCall(
                        id = "call_123|fc_123",
                        name = "read",
                        arguments = """{"path":"README.md"}""",
                    ),
                ),
            ),
        )

        val result = transformMessages(messages, model, anthropicNormalizeToolCallId)
        val lastMessage = assertIs<ToolResultMessage>(result.last())

        assertEquals("call_123_fc_123", lastMessage.toolCallId)
        assertEquals("read", lastMessage.toolName)
        assertTrue(lastMessage.isError)
        assertEquals(listOf(TextContent("No result provided")), lastMessage.content)
    }

    @Test
    fun `adds synthetic results only for trailing tool calls that are still missing results`() {
        val model = makeCopilotClaudeModel()
        val messages: List<Message> = listOf(
            UserMessage.ofText("run commands"),
            makeAssistantMessage(
                listOf(
                    ToolCall(
                        id = "call_1|fc_1",
                        name = "read",
                        arguments = """{"path":"README.md"}""",
                    ),
                    ToolCall(
                        id = "call_2|fc_2",
                        name = "bash",
                        arguments = """{"command":"pwd"}""",
                    ),
                ),
            ),
            ToolResultMessage(
                toolCallId = "call_1|fc_1",
                toolName = "read",
                content = listOf(TextContent("done")),
            ),
        )

        val result = transformMessages(messages, model, anthropicNormalizeToolCallId)
        val syntheticResults = result.filterIsInstance<ToolResultMessage>().filter { it.isError }

        assertEquals(1, syntheticResults.size)
        val synthetic = syntheticResults.single()
        assertEquals("call_2_fc_2", synthetic.toolCallId)
        assertEquals("bash", synthetic.toolName)
        assertEquals(listOf(TextContent("No result provided")), synthetic.content)
    }

    @Test
    fun `drops textSignature from text blocks when source model differs`() {
        val model = makeCopilotClaudeModel()
        val signed = TextContent("Hi there!", textSignature = "sig-123")
        val crossModel: List<Message> = listOf(
            UserMessage.ofText("hello"),
            AssistantMessage(
                content = listOf(signed),
                api = "openai-completions",
                provider = "github-copilot",
                model = "gpt-4o",
                stopReason = StopReason.STOP,
            ),
        )
        val sameModel: List<Message> = listOf(
            UserMessage.ofText("hello"),
            AssistantMessage(
                content = listOf(signed.copy()),
                api = "anthropic-messages",
                provider = "github-copilot",
                model = model.id,
                stopReason = StopReason.STOP,
            ),
        )

        val cross = transformMessages(crossModel, model, anthropicNormalizeToolCallId)
        val crossText = cross.filterIsInstance<AssistantMessage>().single().content.single()
        assertIs<TextContent>(crossText)
        assertEquals("Hi there!", crossText.text)
        assertNull(crossText.textSignature)

        val same = transformMessages(sameModel, model, anthropicNormalizeToolCallId)
        val sameText = same.filterIsInstance<AssistantMessage>().single().content.single()
        assertIs<TextContent>(sameText)
        assertEquals("sig-123", sameText.textSignature)
    }
}
