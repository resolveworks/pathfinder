package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolChoice
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.TestCatalogs
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
    private val openaiModel = TestCatalogs.GPT_4O
    private val schema = Json.parseToJsonElement(
        """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    )

    private fun body(
        context: Context,
        options: OpenAiCompletionsOptions = OpenAiCompletionsOptions(apiKey = "test-key"),
        model: works.resolve.pathfinder.ai.core.Model = this.model,
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
    fun `tool choice serialized to chat completions wire form`() {
        // pi openai-completions.ts buildParams passes ChatCompletionToolChoiceOption
        // through as tool_choice (test/openai-completions-tool-choice.test.ts).
        val cases = mapOf(
            ToolChoice.Auto to JsonPrimitive("auto"),
            ToolChoice.None to JsonPrimitive("none"),
            ToolChoice.Any to JsonPrimitive("required"),
            ToolChoice.Required to JsonPrimitive("required"),
            ToolChoice.Function("ping") to Json.parseToJsonElement(
                """{"type":"function","function":{"name":"ping"}}""",
            ),
        )
        for ((choice, expected) in cases) {
            val b = body(
                Context(messages = listOf(UserMessage.ofText("hi"))),
                OpenAiCompletionsOptions(apiKey = "k", toolChoice = choice),
            )
            assertEquals(expected, b["tool_choice"])
        }
    }

    @Test
    fun `tool choice included without tools and omitted when absent`() {
        // pi includes tool_choice even when no tools are sent, and omits the
        // field when toolChoice is unset (openai-completions.ts:850).
        val withChoice = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", toolChoice = ToolChoice.None),
        )
        assertEquals("none", withChoice["tool_choice"]!!.jsonPrimitive.content)
        assertFalse(withChoice.containsKey("tools"))

        val without = body(Context(messages = listOf(UserMessage.ofText("hi"))))
        assertFalse(without.containsKey("tool_choice"))
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
            model = TestCatalogs.GPT_4O,
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
    fun `serialized reasoning_details replay as assistant reasoning_details and suppress the raw field`() {
        // pi openai-completions.ts:1270-1285,1300-1313,1344-1346: when a
        // thinking signature parses as reasoning details, the details are
        // replayed as reasoning_details and the raw reasoning field is not sent.
        val details =
            """[{"type":"reasoning.encrypted","id":"call_1","data":"encrypted-signature"}]"""
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("let me think", thinkingSignature = details),
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
        assertEquals(Json.parseToJsonElement(details), assistant["reasoning_details"])
        assertFalse(assistant.containsKey("reasoning"))
        assertFalse(assistant.containsKey("reasoning_content"))
        assertFalse(assistant.containsKey("reasoning_text"))
    }

    @Test
    fun `legacy encrypted tool-call thoughtSignature replays as reasoning_details`() {
        // pi test/openai-completions-reasoning-details.test.ts "falls back to
        // encrypted tool-call signatures for older stored assistant messages":
        // openai-completions.ts:1277-1283 parseLegacyEncryptedReasoningDetail.
        val detail =
            """{"type":"reasoning.encrypted","id":"call_1","data":"encrypted-signature"}"""
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ToolCall(
                                id = "call_1",
                                name = "read",
                                arguments = "{}",
                                thoughtSignature = detail,
                            ),
                        ),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                    ),
                ),
            ),
        )
        val assistant = b["messages"]!!.jsonArray[0].jsonObject
        assertEquals(Json.parseToJsonElement("[$detail]"), assistant["reasoning_details"])
    }

    @Test
    fun `signed reasoning_details take precedence over legacy tool-call signatures`() {
        val signed =
            """[{"type":"reasoning.encrypted","id":"thinking","data":"signed"}]"""
        val legacy =
            """{"type":"reasoning.encrypted","id":"call_1","data":"legacy"}"""
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("thinking", thinkingSignature = signed),
                            ToolCall(id = "call_1", name = "read", arguments = "{}", thoughtSignature = legacy),
                        ),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                    ),
                ),
            ),
        )
        val assistant = b["messages"]!!.jsonArray[0].jsonObject
        assertEquals(Json.parseToJsonElement(signed), assistant["reasoning_details"])
    }

    @Test
    fun `invalid reasoning_details signature falls back to plain replay`() {
        // Non-detail signatures keep the plain reasoning-field replay path.
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("let me think", thinkingSignature = "not json at all"),
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
        assertFalse(assistant.containsKey("reasoning_details"))
        // The invalid signature names no reasoning field, so nothing is sent.
        assertFalse(assistant.containsKey("reasoning_content"))
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
        // transformMessages already replaced the image with pi's non-vision tool
        // image placeholder (transform-messages.ts NON_VISION_TOOL_IMAGE_PLACEHOLDER),
        // so the tool message carries that text.
        assertEquals(
            "(tool image omitted: model does not support images)",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `user image downgraded to placeholder for non-vision model and deduped`() {
        // pi transform-messages.ts: images in user messages become a single
        // deduplicated "(image omitted: ...)" placeholder for non-vision models.
        val b = body(
            Context(
                messages = listOf(
                    UserMessage(
                        listOf(
                            TextContent("what is this?"),
                            ImageContent("aGVsbG8=", "image/png"),
                            ImageContent("aGVsbG8=", "image/png"),
                        ),
                    ),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals(
            "what is this?(image omitted: model does not support images)",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `aborted assistant turn with partial content is skipped`() {
        // pi transform-messages.ts drops error/aborted assistant turns entirely.
        val b = body(
            Context(
                messages = listOf(
                    UserMessage.ofText("hi"),
                    AssistantMessage(
                        content = listOf(TextContent("partial answ")),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                        stopReason = StopReason.ABORTED,
                    ),
                    UserMessage.ofText("again"),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals(
            "user",
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }.distinct().single(),
        )
    }

    @Test
    fun `orphaned tool call gets synthetic tool result`() {
        // pi transform-messages.ts inserts "No result provided" error tool
        // results for tool calls left unanswered at the next user turn.
        val b = body(
            Context(
                messages = listOf(
                    UserMessage.ofText("read the file"),
                    AssistantMessage(
                        content = listOf(ToolCall("call_1", "read", "{}")),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                        stopReason = StopReason.TOOL_USE,
                    ),
                    UserMessage.ofText("any luck?"),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals(4, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        val tool = messages[2].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("No result provided", tool["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[3].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foreign pipe tool call ids are split and applied to tool calls and results`() {
        // pi openai-completions.ts normalizeToolCallId: Responses-style
        // "{call_id}|{item_id}" ids from foreign models recombine to
        // "{callId}_{itemId}"; the matching tool result id is rewritten too.
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(ToolCall("call_123|fc_123", "read", "{}")),
                        api = "openai-responses",
                        provider = "github-copilot",
                        model = "gpt-5",
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage("call_123|fc_123", "read", listOf(TextContent("done"))),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals(
            "call_123_fc_123",
            messages[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "call_123_fc_123",
            messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `overlong combined pipe id is truncated with hash suffix`() {
        val itemId = "fc_" + "x".repeat(60)
        val id = "call_123|$itemId"
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(ToolCall(id, "read", "{}")),
                        api = "openai-responses",
                        provider = "github-copilot",
                        model = "gpt-5",
                        stopReason = StopReason.TOOL_USE,
                    ),
                ),
            ),
        )
        val hash = OpenAiResponsesShared.shortHash(id).take(8)
        val expected = "call_123_${hash}"
        assertTrue(expected.length <= 40, "combined id must respect the OpenAI 40-char limit")
        val assistant = b["messages"]!!.jsonArray[0].jsonObject
        assertEquals(
            expected,
            assistant["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content,
        )
        // The synthetic orphan tool result uses the normalized id as well.
        val tool = b["messages"]!!.jsonArray[1].jsonObject
        assertEquals(expected, tool["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plain foreign id truncated to 40 chars only for openai provider`() {
        val longId = "call_" + "a".repeat(40) // 45 chars
        fun idFor(model: works.resolve.pathfinder.ai.core.Model): String = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(ToolCall(longId, "read", "{}")),
                        api = "openai-completions",
                        provider = "other",
                        model = "other-model",
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage(longId, "read", listOf(TextContent("done"))),
                ),
            ),
            model = model,
        )["messages"]!!.jsonArray[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(longId.take(40), idFor(TestCatalogs.GPT_4O))
        // Non-openai providers pass foreign ids through untouched.
        assertEquals(longId, idFor(model))
    }

    @Test
    fun `same-model tool call ids are not normalized`() {
        // pi transform-messages.ts only normalizes ids of foreign models.
        val id = "call_123|fc_123"
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(ToolCall(id, "read", "{}")),
                        api = "openai-completions",
                        provider = "zai",
                        model = "glm-5.2",
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage(id, "read", listOf(TextContent("done"))),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals(
            id,
            messages[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content,
        )
        assertEquals(id, messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cross-model thinking replayed as plain text`() {
        // pi transform-messages.ts converts foreign thinking blocks to text
        // in place, so the completions adapter replays them concatenated with
        // the assistant text and no reasoning wire field.
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("let me think", thinkingSignature = "reasoning_content"),
                            TextContent("answer"),
                        ),
                        api = "openai-completions",
                        provider = "github-copilot",
                        model = "gpt-4o",
                        stopReason = StopReason.STOP,
                    ),
                ),
            ),
        )
        val assistant = b["messages"]!!.jsonArray[0].jsonObject
        assertEquals("let me thinkanswer", assistant["content"]!!.jsonPrimitive.content)
        assertFalse(assistant.containsKey("reasoning_content"))
    }

    // Prompt cache params, ported from pi's
    // test/openai-completions-prompt-cache.test.ts (buildParams,
    // openai-completions.ts:804-810).

    @Test
    fun `prompt cache key sent for direct openai requests when caching enabled`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-123"),
            openaiModel,
        )
        assertEquals("session-123", b["prompt_cache_key"]!!.jsonPrimitive.content)
        assertFalse(b.containsKey("prompt_cache_retention"))
    }

    @Test
    fun `prompt cache retention 24h for long cache retention on direct openai`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-456", cacheRetention = CacheRetention.LONG),
            openaiModel,
        )
        assertEquals("session-456", b["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals("24h", b["prompt_cache_retention"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompt cache key clamped to 64 code points`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "x".repeat(67)),
            openaiModel,
        )
        assertEquals("x".repeat(64), b["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompt cache fields omitted when cache retention none`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-789", cacheRetention = CacheRetention.NONE),
            openaiModel,
        )
        assertFalse(b.containsKey("prompt_cache_key"))
        assertFalse(b.containsKey("prompt_cache_retention"))
    }

    @Test
    fun `prompt cache fields omitted for proxy without long retention support`() {
        val proxy = openaiModel.copy(
            baseUrl = "https://proxy.example.com/v1",
            compat = openaiModel.compat.copy(supportsLongCacheRetention = false),
        )
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-proxy", cacheRetention = CacheRetention.LONG),
            proxy,
        )
        assertFalse(b.containsKey("prompt_cache_key"))
        assertFalse(b.containsKey("prompt_cache_retention"))
    }

    @Test
    fun `pi cache retention env resolves long for direct openai`() {
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(
                apiKey = "k",
                sessionId = "session-env",
                env = mapOf("PI_CACHE_RETENTION" to "long"),
            ),
            openaiModel,
        )
        assertEquals("session-env", b["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals("24h", b["prompt_cache_retention"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompt cache retention sent for proxy that supports long retention`() {
        val proxy = openaiModel.copy(baseUrl = "https://proxy.example.com/v1")
        val b = body(
            Context(messages = listOf(UserMessage.ofText("hi"))),
            OpenAiCompletionsOptions(apiKey = "k", sessionId = "session-proxy", cacheRetention = CacheRetention.LONG),
            proxy,
        )
        assertEquals("session-proxy", b["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals("24h", b["prompt_cache_retention"]!!.jsonPrimitive.content)
    }
}
