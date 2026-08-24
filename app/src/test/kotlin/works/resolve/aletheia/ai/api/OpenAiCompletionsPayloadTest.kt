package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.ImageContent
import works.resolve.aletheia.ai.core.ModelThinkingLevel
import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ThinkingContent
import works.resolve.aletheia.ai.core.Tool
import works.resolve.aletheia.ai.core.ToolCall
import works.resolve.aletheia.ai.core.ToolResultMessage
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.testing.TestCatalogs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class OpenAiCompletionsPayloadTest {

    private val model = TestCatalogs.GLM_5_2
    private val schema = Json.parseToJsonElement(
        """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    )

    private fun body(
        context: Context,
        options: OpenAiCompletionsOptions = OpenAiCompletionsOptions(apiKey = "test-key"),
        model: works.resolve.aletheia.ai.core.Model = this.model,
    ): JsonObject = OpenAiCompletionsPayload.buildRequestBody(model, context, options)

    @Test
    fun `zai compat sets endpoint params`() {
        val b = body(Context(messages = listOf(UserMessage.ofText("hi"))))
        assertEquals("glm-5.2", b["model"]!!.jsonPrimitive.content)
        assertEquals(true, b["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, b["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(b.containsKey("store"), "ZAI must not receive store")
        assertTrue(b.containsKey("tools") == false, "no tools -> no tools key")
    }

    @Test
    fun `uses max_tokens not max_completion_tokens`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", maxTokens = 512),
        )
        assertEquals(512, b["max_tokens"]!!.jsonPrimitive.longOrNull)
        assertFalse(b.containsKey("max_completion_tokens"))
    }

    @Test
    fun `temperature included when set`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", temperature = 0.2),
        )
        assertEquals(0.2, b["temperature"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `thinking disabled without reasoning effort`() {
        val b = body(Context(messages = listOf(UserMessage.ofText("hi"))))
        val thinking = b["thinking"]!!.jsonObject
        assertEquals("disabled", thinking["type"]!!.jsonPrimitive.content)
        assertFalse(thinking.containsKey("clear_thinking"))
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `thinking enabled with clear_thinking false and mapped effort`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = ModelThinkingLevel.HIGH),
        )
        val thinking = b["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(false, thinking["clear_thinking"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("high", b["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toggle-only model sends thinking without reasoning_effort`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = ModelThinkingLevel.HIGH),
            model = TestCatalogs.GLM_4_7,
        )
        assertEquals("enabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(b.containsKey("reasoning_effort"), "glm-4.7 has no effort support")
    }

    @Test
    fun `off maps to provider off value for glm-5_2`() {
        // clampThinkingLevel keeps OFF only when the map has an off value.
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = ModelThinkingLevel.OFF),
        )
        assertEquals("disabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools converted with json schema and tool_stream flag`() {
        val tool = Tool(name = "read_file", description = "Reads a file", parameters = schema)
        val b = body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool)))
        val converted = b["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", converted["type"]!!.jsonPrimitive.content)
        val function = converted["function"]!!.jsonObject
        assertEquals("read_file", function["name"]!!.jsonPrimitive.content)
        assertEquals("Reads a file", function["description"]!!.jsonPrimitive.content)
        assertEquals(schema, function["parameters"])
        assertEquals(true, b["tool_stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `tool history without active tools sends empty tools array`() {
        val context = Context(
            messages = listOf(
                UserMessage.ofText("hi"),
                AssistantMessage(
                    content = listOf(ToolCall("call_1", "read_file", "{}")),
                    api = "openai-completions",
                    provider = "zai",
                    model = "glm-5.2",
                ),
                ToolResultMessage(
                    toolCallId = "call_1",
                    toolName = "read_file",
                    content = listOf(TextContent("contents")),
                ),
            ),
        )
        val b = body(context)
        val messages = b["messages"]!!.jsonArray
        // system-less: user, assistant with tool_calls, tool result
        assertEquals(3, messages.size)
        assertEquals(
            "assistant",
            messages[1].jsonObject["role"]!!.jsonPrimitive.content,
        )
        val toolCalls = messages[1].jsonObject["tool_calls"]!!.jsonArray
        assertEquals("call_1", toolCalls[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(
            "read_file",
            toolCalls[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "tool",
            messages[2].jsonObject["role"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "call_1",
            messages[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content,
        )
        assertEquals("contents", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool call arguments replay as raw json string with exact escaping`() {
        val raw = """{"path":"/tmp/a\\"b","n":1}"""
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(ToolCall("call_1", "read_file", raw)),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                    ),
                    ToolResultMessage("call_1", "read_file", listOf(TextContent("ok"))),
                ),
            ),
        )
        val function = b["messages"]!!.jsonArray[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject
        // The raw string is sent verbatim as the JSON string value.
        assertEquals(raw, function["arguments"]!!.jsonPrimitive.content)
        assertEquals(raw, Json.parseToJsonElement(function.toString()).jsonObject["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `function tools carry explicit strict false`() {
        val tool = Tool(name = "read_file", description = "Reads a file", parameters = schema)
        val b = body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool)))
        val function = b["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals(false, function["strict"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `whitespace-only assistant text blocks are dropped`() {
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("hmm", thinkingSignature = "reasoning_content"),
                            TextContent("  "),
                            TextContent("answer"),
                        ),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                    ),
                ),
            ),
        )
        assertEquals("answer", b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant message with only blank text and no tool calls is skipped`() {
        val b = body(
            Context(
                messages = listOf(
                    UserMessage.ofText("hi"),
                    AssistantMessage(
                        content = listOf(TextContent(" \t ")),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                    ),
                    UserMessage.ofText("again"),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("user", messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }.distinct().single())
    }

    @Test
    fun `empty user content array is skipped`() {
        val b = body(Context(messages = listOf(UserMessage(content = emptyList()), UserMessage.ofText("hi"))))
        val messages = b["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("hi", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty system prompt is skipped`() {
        val b = body(Context(systemPrompt = "", messages = listOf(UserMessage.ofText("hi"))))
        val messages = b["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unpaired surrogates are stripped but valid pairs kept`() {
        val lone = buildString { append("a"); append(0xD83D.toChar()); append(0xDC00.toChar()); append(0xD800.toChar()) }
        val sanitized = OpenAiCompletionsPayload.sanitizeSurrogates(lone)
        assertEquals("a\uD83D\uDC00", sanitized)

        val b = body(Context(systemPrompt = lone, messages = listOf(UserMessage.ofText(lone))))
        val messages = b["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("a\uD83D\uDC00", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("a\uD83D\uDC00", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `raw reasoning field replayed verbatim without surrogate sanitization`() {
        // pi's openai-completions.ts joins thinking blocks into the reasoning
        // field without sanitizeSurrogates; exact-parity port.
        val lone = buildString { append("think "); append(0xD800.toChar()) }
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent(lone, thinkingSignature = "reasoning_content"),
                            TextContent("answer"),
                        ),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                    ),
                ),
            ),
        )
        val assistant = b["messages"]!!.jsonArray[0].jsonObject
        assertTrue(assistant.containsKey("reasoning_content"))
        assertEquals(lone, assistant["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `system prompt sent with system role not developer`() {
        val b = body(Context(systemPrompt = "You are helpful.", messages = listOf(UserMessage.ofText("hi"))))
        val first = b["messages"]!!.jsonArray[0].jsonObject
        assertEquals("system", first["role"]!!.jsonPrimitive.content)
        assertEquals("You are helpful.", first["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user image content becomes data url part`() {
        val b = body(
            Context(
                messages = listOf(
                    UserMessage(
                        listOf(
                            TextContent("what is this?"),
                            ImageContent(data = "aGVsbG8=", mimeType = "image/png"),
                        ),
                    ),
                ),
            ),
        )
        val content = b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val imageUrl = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/png;base64,aGVsbG8=", imageUrl)
    }

    @Test
    fun `assistant thinking replayed in signature wire field`() {
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("let me think", thinkingSignature = "reasoning_content"),
                            TextContent("answer"),
                        ),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                    ),
                ),
            ),
        )
        val assistant = b["messages"]!!.jsonArray[0].jsonObject
        assertEquals("answer", assistant["content"]!!.jsonPrimitive.content)
        assertEquals("let me think", assistant["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty assistant messages are skipped`() {
        val b = body(
            Context(
                messages = listOf(
                    UserMessage.ofText("hi"),
                    AssistantMessage(
                        content = emptyList(),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                    ),
                    UserMessage.ofText("again"),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool result without content gets placeholder`() {
        val b = body(
            Context(
                messages = listOf(
                    ToolResultMessage(
                        toolCallId = "c1",
                        toolName = "t",
                        content = emptyList(),
                    ),
                ),
            ),
        )
        assertEquals(
            "(no tool output)",
            b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `tool result image not attached for text-only model`() {
        val b = body(
            Context(
                messages = listOf(
                    ToolResultMessage(
                        toolCallId = "c1",
                        toolName = "t",
                        content = listOf(ImageContent("aGVsbG8=", "image/png")),
                    ),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals(1, messages.size, "no follow-up user image message for text-only model")
        assertEquals("(see attached image)", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }
}
