package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.CacheControlFormat
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.core.DeferredToolsMode
import works.resolve.pathfinder.ai.core.StrictJsonSchemaMode
import kotlin.test.assertFailsWith
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.shortHash

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
    fun `strict is omitted when compat disables strict mode`() {
        // Some providers reject unknown fields.
        val tool = Tool(name = "read_file", description = "Reads a file", parameters = schema)
        val strictless = model.copy(compat = model.compat.copy(supportsStrictMode = false))
        val b = body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool)), model = strictless)
        val function = b["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertFalse(function.containsKey("strict"))
    }

    @Test
    fun `strict tool schema is rewritten with strict true`() {
        val parameters = Json.parseToJsonElement(
            """{"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"number"}},"required":["path"]}""",
        )
        val tool = Tool(
            name = "read_file",
            description = "Reads a file",
            parameters = parameters,
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.REQUIRE),
        )
        val b = body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool)))
        val function = b["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals(true, function["strict"]!!.jsonPrimitive.content.toBoolean())
        val sent = function["parameters"]!!.jsonObject
        assertEquals(false, sent["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("path", "offset"),
            sent["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            """{"anyOf":[{"type":"number"},{"type":"null"}]}""",
            sent["properties"]!!.jsonObject["offset"].toString(),
        )
        assertTrue(!parameters.jsonObject.containsKey("additionalProperties"))
    }

    @Test
    fun `prefer mode downgrades unsupported schema to non-strict original parameters`() {
        val parameters = Json.parseToJsonElement(
            """{"type":"object","allOf":[{"type":"object","properties":{"a":{"type":"string"}}}]}""",
        )
        val tool = Tool(
            name = "read_file",
            description = "Reads a file",
            parameters = parameters,
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.PREFER),
        )
        val b = body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool)))
        val function = b["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals(false, function["strict"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(parameters, function["parameters"])
    }

    @Test
    fun `require mode rejection surfaces from buildRequestBody`() {
        val tool = Tool(
            name = "read_file",
            description = "Reads a file",
            parameters = Json.parseToJsonElement(
                """{"type":"object","allOf":[{"type":"object","properties":{"a":{"type":"string"}}}]}""",
            ),
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.REQUIRE),
        )
        val error = assertFailsWith<ConstrainedSamplingError> {
            body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool)))
        }
        assertEquals(
            "Tool \"read_file\" requires JSON-schema constrained sampling, but allOf schemas are unsupported.",
            error.message,
        )
    }

    @Test
    fun `require mode rejects when compat disables strict mode`() {
        val tool = Tool(
            name = "read_file",
            description = "Reads a file",
            parameters = schema,
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.REQUIRE),
        )
        val strictless = model.copy(compat = model.compat.copy(supportsStrictMode = false))
        val error = assertFailsWith<ConstrainedSamplingError> {
            body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool)), model = strictless)
        }
        assertEquals(
            "Tool \"read_file\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
            error.message,
        )
    }

    @Test
    fun `opencode-go replay remaps a reasoning signature to reasoning_content`() {
        val goModel = model.copy(provider = "opencode-go")
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("let me think", thinkingSignature = "reasoning"),
                            TextContent("answer"),
                        ),
                        api = "openai-completions",
                        provider = "opencode-go",
                        model = "glm-5.2",
                    ),
                ),
            ),
            model = goModel,
        )
        val assistant = b["messages"]!!.jsonArray.single().jsonObject
        assertEquals("let me think", assistant["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse(assistant.containsKey("reasoning"))
    }

    @Test
    fun `non opencode-go replay keeps the literal reasoning field`() {
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("let me think", thinkingSignature = "reasoning"),
                            TextContent("answer"),
                        ),
                        api = "openai-completions",
                        provider = "chutes",
                        model = "glm-5.2",
                    ),
                ),
            ),
            model = model.copy(provider = "chutes"),
        )
        val assistant = b["messages"]!!.jsonArray.single().jsonObject
        assertEquals("let me think", assistant["reasoning"]!!.jsonPrimitive.content)
        assertFalse(assistant.containsKey("reasoning_content"))
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
        val sanitized = sanitizeSurrogates(lone)
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
        // Intentional parity: pi replays the raw reasoning field without sanitizeSurrogates.
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
    fun `reasoning_content is sent empty on compat assistant replay without reasoning (pi 1344-1349)`() {
        // DeepSeek-style endpoints reject assistant replays without reasoning_content.
        val deepseekModel = TestCatalogs.GLM_5_2.copy(
            compat = TestCatalogs.GLM_5_2.compat.copy(requiresReasoningContentOnAssistantMessages = true),
        )
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(ToolCall("call_1", "read_file", "{}")),
                    api = "openai-completions",
                    provider = deepseekModel.provider,
                    model = deepseekModel.id,
                ),
            ),
        )
        val assistant = body(context, model = deepseekModel)["messages"]!!.jsonArray[0].jsonObject
        assertEquals("", assistant["reasoning_content"]!!.jsonPrimitive.content)

        val plain = body(context, model = TestCatalogs.GLM_5_2)["messages"]!!.jsonArray[0].jsonObject
        assertNull(plain["reasoning_content"])

        val nonReasoning = deepseekModel.copy(reasoning = false)
        val gated = body(context, model = nonReasoning)["messages"]!!.jsonArray[0].jsonObject
        assertNull(gated["reasoning_content"])
    }

    @Test
    fun `reasoning_content injection never overwrites a replayed reasoning field`() {
        val deepseekModel = TestCatalogs.GLM_5_2.copy(
            compat = TestCatalogs.GLM_5_2.compat.copy(requiresReasoningContentOnAssistantMessages = true),
        )
        val b = body(
            Context(
                messages = listOf(
                    AssistantMessage(
                        content = listOf(
                            ThinkingContent("let me think", thinkingSignature = "reasoning_content"),
                            TextContent("answer"),
                        ),
                        api = "openai-completions",
                        provider = deepseekModel.provider,
                        model = deepseekModel.id,
                    ),
                ),
            ),
            model = deepseekModel,
        )
        assertEquals("let me think", b["messages"]!!.jsonArray[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deferredToolsMode kimi re-announces loaded tools as a bare tools system message (pi 834, 1396-1451)`() {
        val kimiModel = TestCatalogs.GPT_4O.copy(
            compat = TestCatalogs.GPT_4O.compat.copy(deferredToolsMode = DeferredToolsMode.KIMI),
        )
        fun deferredTool(name: String) = Tool(
            name = name,
            description = "$name tool",
            parameters = schema,
        )
        val context = Context(
            systemPrompt = "system",
            tools = listOf(deferredTool("search"), deferredTool("read_file")),
            messages = listOf(
                UserMessage.ofText("hi"),
                AssistantMessage(
                    content = listOf(ToolCall("call_1", "search", "{}")),
                    api = "openai-completions",
                    provider = "moonshotai",
                    model = kimiModel.id,
                ),
                ToolResultMessage(
                    toolCallId = "call_1",
                    toolName = "search",
                    content = listOf(TextContent("results")),
                    addedToolNames = listOf("search"),
                ),
            ),
        )
        val b = body(context, model = kimiModel)

        val tools = b["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("read_file", tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val messages = b["messages"]!!.jsonArray
        assertEquals(5, messages.size)
        val kimiSystem = messages[4].jsonObject
        assertEquals("system", kimiSystem["role"]!!.jsonPrimitive.content)
        assertNull(kimiSystem["content"])
        val kimiTools = kimiSystem["tools"]!!.jsonArray
        assertEquals(1, kimiTools.size)
        assertEquals("search", kimiTools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `kimi mode sends an empty tools array when every tool is deferred`() {
        val kimiModel = TestCatalogs.GPT_4O.copy(
            compat = TestCatalogs.GPT_4O.compat.copy(deferredToolsMode = DeferredToolsMode.KIMI),
        )
        val tool = Tool(name = "search", description = "search tool", parameters = schema)
        val context = Context(
            tools = listOf(tool),
            messages = listOf(
                AssistantMessage(
                    content = listOf(ToolCall("call_1", "search", "{}")),
                    api = "openai-completions",
                    provider = "moonshotai",
                    model = kimiModel.id,
                ),
                ToolResultMessage(
                    toolCallId = "call_1",
                    toolName = "search",
                    content = listOf(TextContent("results")),
                    addedToolNames = listOf("search"),
                ),
            ),
        )
        val b = body(context, model = kimiModel)
        assertEquals(0, b["tools"]!!.jsonArray.size)
        val messages = b["messages"]!!.jsonArray
        assertEquals("system", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(1, messages[2].jsonObject["tools"]!!.jsonArray.size)
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
        // transformMessages already replaced the image with its non-vision placeholder.
        assertEquals(
            "(tool image omitted: model does not support images)",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `user image downgraded to placeholder for non-vision model and deduped`() {
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
        val hash = shortHash(id).take(8)
        val expected = "call_123_${hash}"
        assertTrue(expected.length <= 40, "combined id must respect the OpenAI 40-char limit")
        val assistant = b["messages"]!!.jsonArray[0].jsonObject
        assertEquals(
            expected,
            assistant["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content,
        )
        val tool = b["messages"]!!.jsonArray[1].jsonObject
        assertEquals(expected, tool["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plain foreign id truncated to 40 chars only for openai provider`() {
        val longId = "call_" + "a".repeat(40)
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
        assertEquals(longId, idFor(model))
    }

    @Test
    fun `same-model tool call ids are not normalized`() {
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

    private val openrouterAnthropic = works.resolve.pathfinder.ai.core.Model(
        id = "anthropic/claude-sonnet-4",
        name = "Claude Sonnet 4",
        api = "openai-completions",
        provider = "openrouter",
        baseUrl = "https://example.com/v1",
        reasoning = true,
        compat = works.resolve.pathfinder.ai.core.OpenAiCompletionsCompat(
            cacheControlFormat = CacheControlFormat.ANTHROPIC,
        ),
    )

    private val cacheTool = Tool(name = "read", description = "Read a file", parameters = schema)

    private fun cacheControlOf(element: JsonElement): JsonObject? = (element as? JsonObject)?.get("cache_control") as? JsonObject

    private fun assertAnthropicCacheMarkers(b: JsonObject, expectedTtl: String?) {
        val expected = buildJsonObject {
            put("type", "ephemeral")
            expectedTtl?.let { put("ttl", it) }
        }

        val messages = b["messages"]!!.jsonArray
        val instruction = messages.first { (it as? JsonObject)?.get("role")?.jsonPrimitive?.content in listOf("system", "developer") }.jsonObject
        val instructionContent = instruction["content"]!!.jsonArray
        assertEquals("text", instructionContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("System prompt", instructionContent[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(expected, cacheControlOf(instructionContent[0]))

        val tools = b["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals(expected, cacheControlOf(tools[0]))

        val last = messages.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertEquals(expected, cacheControlOf(last["content"]!!.jsonArray[0]))
    }

    @Test
    fun `anthropic cache markers applied when compat enables them`() {
        val b = body(
            Context(
                systemPrompt = "System prompt",
                messages = listOf(UserMessage.ofText("Hello")),
                tools = listOf(cacheTool),
            ),
            model = openrouterAnthropic,
        )
        // Default retention is short: ephemeral marker without a ttl.
        assertAnthropicCacheMarkers(b, expectedTtl = null)
    }

    @Test
    fun `anthropic cache markers carry ttl 1h for long retention when supported`() {
        val b = body(
            Context(
                systemPrompt = "System prompt",
                messages = listOf(UserMessage.ofText("Hello")),
                tools = listOf(cacheTool),
            ),
            OpenAiCompletionsOptions(apiKey = "k", cacheRetention = CacheRetention.LONG),
            openrouterAnthropic,
        )
        assertAnthropicCacheMarkers(b, expectedTtl = "1h")
    }

    @Test
    fun `anthropic cache markers omit ttl for long retention without long retention support`() {
        val model = openrouterAnthropic.copy(
            compat = openrouterAnthropic.compat.copy(supportsLongCacheRetention = false),
        )
        val b = body(
            Context(
                systemPrompt = "System prompt",
                messages = listOf(UserMessage.ofText("Hello")),
                tools = listOf(cacheTool),
            ),
            OpenAiCompletionsOptions(apiKey = "k", cacheRetention = CacheRetention.LONG),
            model,
        )
        assertAnthropicCacheMarkers(b, expectedTtl = null)
    }

    @Test
    fun `conversation cache marker moves to a tool result`() {
        val b = body(
            Context(
                systemPrompt = "System prompt",
                messages = listOf(
                    UserMessage.ofText("Read the file"),
                    AssistantMessage(
                        content = listOf(ToolCall("call_1", "read", """{"path":"README.md"}""")),
                        api = "openai-completions",
                        provider = "openrouter",
                        model = openrouterAnthropic.id,
                    ),
                    ToolResultMessage(
                        toolCallId = "call_1",
                        toolName = "read",
                        content = listOf(TextContent("file contents")),
                    ),
                ),
                tools = listOf(cacheTool),
            ),
            model = openrouterAnthropic,
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals("Read the file", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        val toolMessage = messages.last().jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertEquals(
            buildJsonObject { put("type", "ephemeral") },
            cacheControlOf(toolMessage["content"]!!.jsonArray[0]),
        )
    }

    @Test
    fun `anthropic cache markers omitted when cache retention is none`() {
        val b = body(
            Context(
                systemPrompt = "System prompt",
                messages = listOf(UserMessage.ofText("Hello")),
                tools = listOf(cacheTool),
            ),
            OpenAiCompletionsOptions(apiKey = "k", cacheRetention = CacheRetention.NONE),
            openrouterAnthropic,
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals("System prompt", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertNull(cacheControlOf(b["tools"]!!.jsonArray[0]))
    }

    @Test
    fun `anthropic cache markers omitted for models without anthropic format`() {
        val b = body(
            Context(
                systemPrompt = "System prompt",
                messages = listOf(UserMessage.ofText("Hello")),
                tools = listOf(cacheTool),
            ),
            OpenAiCompletionsOptions(apiKey = "k", cacheRetention = CacheRetention.LONG),
            openaiModel,
        )
        val messages = b["messages"]!!.jsonArray
        assertEquals("System prompt", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertNull(cacheControlOf(b["tools"]!!.jsonArray[0]))
    }
}
