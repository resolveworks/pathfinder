package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.UserMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates

/**
 * Request-construction tests for the anthropic-messages port, mirroring pi's
 * packages/ai/test/anthropic-*.test.ts coverage of buildParams,
 * convertMessages, convertTools, and transform-messages.
 */
class AnthropicMessagesPayloadTest {

    private val claude = Model(
        id = "claude-sonnet-4-5",
        name = "Claude Sonnet 4.5",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "https://api.anthropic.com",
        reasoning = true,
        input = listOf(InputModality.TEXT, InputModality.IMAGE),
        cost = ModelCost(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
        contextWindow = 200_000,
        maxTokens = 64_000,
    )

    private val tool = Tool(
        name = "edit",
        description = "Edit a file.",
        parameters = Json.parseToJsonElement(
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
        ),
    )

    private fun body(
        context: Context,
        options: AnthropicMessagesOptions = AnthropicMessagesOptions(apiKey = "k"),
        model: Model = claude,
    ): JsonObject = buildRequestBody(model, context, isOAuthToken = false, options)

    @Test
    fun `basic request shape matches pi buildParams`() {
        val context = Context(
            systemPrompt = "Be helpful.",
            messages = listOf(UserMessage.ofText("hi")),
        )
        val json = body(context)
        assertEquals("claude-sonnet-4-5", json["model"]!!.jsonPrimitive.content)
        assertEquals(64_000L, json["max_tokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(true, json["stream"]!!.jsonPrimitive.content.toBoolean())
        // System prompt carries a default (short-retention) cache_control marker.
        val system = json["system"]!!.jsonArray.single().jsonObject
        assertEquals("Be helpful.", system["text"]!!.jsonPrimitive.content)
        assertEquals(
            "ephemeral",
            system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        // Text-only user messages replay as text content blocks.
        val userContent = json["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("text", userContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hi", userContent[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("temperature"))
        assertFalse(json.containsKey("tools"))
        assertFalse(json.containsKey("thinking"))
    }

    @Test
    fun `long retention uses the 1h ttl when supported`() {
        val context = Context(systemPrompt = "s", messages = listOf(UserMessage.ofText("hi")))
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.LONG))
        assertEquals(
            "1h",
            json["system"]!!.jsonArray.single().jsonObject["cache_control"]!!
                .jsonObject["ttl"]!!.jsonPrimitive.content,
        )
        // Explicit PI_CACHE_RETENTION=long env behaves the same.
        val envJson = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", env = mapOf("PI_CACHE_RETENTION" to "long")),
        )
        assertEquals(
            "1h",
            envJson["system"]!!.jsonArray.single().jsonObject["cache_control"]!!
                .jsonObject["ttl"]!!.jsonPrimitive.content,
        )
        // A model without long-retention support keeps the default marker.
        val shortOnly = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.LONG),
            model = claude.copy(anthropicCompat = claude.anthropicCompat.copy(supportsLongCacheRetention = false)),
        )
        assertNull(
            shortOnly["system"]!!.jsonArray.single().jsonObject["cache_control"]!!
                .jsonObject["ttl"],
        )
    }

    @Test
    fun `cache retention none omits cache_control everywhere`() {
        val context = Context(
            systemPrompt = "s",
            messages = listOf(UserMessage.ofText("hi")),
            tools = listOf(tool),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        val system = json["system"]!!.jsonArray.single().jsonObject
        assertNull(system["cache_control"])
        val message = json["messages"]!!.jsonArray.single().jsonObject
        assertNull((message["content"] as JsonArray).single().jsonObject["cache_control"])
        assertNull(json["tools"]!!.jsonArray.single().jsonObject["cache_control"])
    }

    @Test
    fun `cache_control lands on the last block of the last user message`() {
        val context = Context(
            messages = listOf(
                UserMessage.ofText("first"),
                UserMessage(listOf(TextContent("a"), TextContent("b"))),
            ),
        )
        val messages = body(context)["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val first = messages[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("first", first["text"]!!.jsonPrimitive.content)
        val second = messages[1].jsonObject["content"]!!.jsonArray
        assertNull(second[0].jsonObject["cache_control"])
        assertEquals(
            "ephemeral",
            second[1].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `images become base64 source blocks with placeholder text`() {
        val context = Context(
            messages = listOf(
                UserMessage(
                    listOf(
                        ImageContent("aW1n", "image/png"),
                    ),
                ),
            ),
        )
        val content = body(context)["messages"]!!.jsonArray.single()
            .jsonObject["content"]!!.jsonArray
        // User-message images replay as source blocks; the "(see attached
        // image)" placeholder belongs to tool-result content only (pi's
        // convertContentBlocks is not applied to user messages).
        assertEquals(1, content.size)
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("base64", content[0].jsonObject["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", content[0].jsonObject["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("aW1n", content[0].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty user text is dropped and empty messages skipped`() {
        val context = Context(messages = listOf(UserMessage(listOf(TextContent("   ")))))
        val messages = body(context)["messages"]!!.jsonArray
        assertEquals(0, messages.size)
    }

    @Test
    fun `assistant thinking replays with signature and redacted payload`() {
        val sameModelAssistant = AssistantMessage(
            content = listOf(
                ThinkingContent("thoughts", "sig-1"),
                TextContent("answer"),
                ToolCall("toolu_1", "edit", """{"path":"/tmp"}"""),
            ),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            stopReason = StopReason.STOP,
        )
        val json = body(Context(messages = listOf(sameModelAssistant, UserMessage.ofText("next"))))
        val content = json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig-1", content[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("tool_use", content[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", content[2].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("edit", content[2].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(
            "/tmp",
            content[2].jsonObject["input"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )

        // Redacted thinking passes the opaque payload back.
        val redacted = sameModelAssistant.copy(
            content = listOf(ThinkingContent("[Reasoning redacted]", "opaque-data", redacted = true)),
        )
        val redactedJson = body(Context(messages = listOf(redacted, UserMessage.ofText("next"))))
        val block = redactedJson["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("redacted_thinking", block["type"]!!.jsonPrimitive.content)
        assertEquals("opaque-data", block["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing thinking signature degrades to text or empty-signature block`() {
        val abortedThinking = AssistantMessage(
            content = listOf(ThinkingContent("partial thought", "")),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
        )
        val json = body(Context(messages = listOf(abortedThinking, UserMessage.ofText("next"))))
        val block = json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("partial thought", block["text"]!!.jsonPrimitive.content)

        // allowEmptySignature models (z.ai-style) keep an empty-signature block.
        val allowModel = claude.copy(anthropicCompat = claude.anthropicCompat.copy(allowEmptySignature = true))
        val allowJson = body(Context(messages = listOf(abortedThinking, UserMessage.ofText("next"))), model = allowModel)
        val allowBlock = allowJson["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("thinking", allowBlock["type"]!!.jsonPrimitive.content)
        assertEquals("", allowBlock["signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `consecutive tool results group into one user message with cache_control`() {
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(ToolCall("toolu_1", "edit", "{}"), ToolCall("toolu_2", "read", "{}")),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-sonnet-4-5",
                    stopReason = StopReason.TOOL_USE,
                ),
                ToolResultMessage("toolu_1", "edit", listOf(TextContent("r1"))),
                ToolResultMessage("toolu_2", "read", listOf(TextContent("r2")), isError = true),
            ),
        )
        val messages = body(context)["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val grouped = messages[1].jsonObject
        assertEquals("user", grouped["role"]!!.jsonPrimitive.content)
        val results = grouped["content"]!!.jsonArray
        assertEquals(2, results.size)
        assertEquals("tool_result", results[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", results[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("r1", (results[0].jsonObject["content"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(true, results[1].jsonObject["is_error"]!!.jsonPrimitive.content.toBoolean())
        // Last user block (a tool_result) carries the history cache marker.
        assertEquals(
            "ephemeral",
            results[1].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `orphaned tool calls get synthetic error results`() {
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(ToolCall("toolu_orphan", "edit", "{}")),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-sonnet-4-5",
                    stopReason = StopReason.TOOL_USE,
                ),
                UserMessage.ofText("never answered the tool"),
            ),
        )
        val messages = body(context)["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        val synthetic = messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", synthetic["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_orphan", synthetic["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals(true, synthetic["is_error"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("No result provided", (synthetic["content"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `error and aborted assistant messages are skipped entirely`() {
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(TextContent("partial")),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-sonnet-4-5",
                    stopReason = StopReason.ABORTED,
                ),
                AssistantMessage(
                    content = listOf(TextContent("boom")),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-sonnet-4-5",
                    stopReason = StopReason.ERROR,
                ),
                UserMessage.ofText("hi"),
            ),
        )
        val messages = body(context)["messages"]!!.jsonArray
        assertEquals(1, messages.size)
    }

    @Test
    fun `cross-provider replay converts thinking to text and normalizes tool ids`() {
        // Assistant produced by another provider/API: thinking -> text, and the
        // OpenAI-style tool id must be normalized to Anthropic's pattern.
        val foreign = AssistantMessage(
            content = listOf(
                ThinkingContent("foreign thoughts"),
                ToolCall("call|ABCdefGH" + "x".repeat(70), "edit", "{}"),
            ),
            api = "openai-completions",
            provider = "openai",
            model = "gpt-5",
            stopReason = StopReason.TOOL_USE,
        )
        val json = body(Context(messages = listOf(foreign, UserMessage.ofText("next"))))
        val content = json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("foreign thoughts", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        val normalizedId = content[1].jsonObject["id"]!!.jsonPrimitive.content
        assertTrue(Regex("^[a-zA-Z0-9_-]{1,64}$").matches(normalizedId))
        assertTrue(normalizedId.startsWith("call_ABCdefGH"))
    }

    @Test
    fun `cross-provider transform strips tool thought signature while normalizing id`() {
        val foreign = AssistantMessage(
            content = listOf(ToolCall("call|foreign", "edit", "{}", thoughtSignature = "google-signature")),
            api = "google-generative-ai",
            provider = "google",
            model = "gemini",
            stopReason = StopReason.TOOL_USE,
        )

        val transformed = transformMessages(listOf(foreign), claude) { _, _ -> "normalized-id" }
        val call = (transformed.first() as AssistantMessage).content.single() as ToolCall
        assertEquals("normalized-id", call.id)
        assertNull(call.thoughtSignature)
    }

    @Test
    fun `non-vision model downgrades images to deduplicated placeholders`() {
        val textModel = claude.copy(input = listOf(InputModality.TEXT))
        val context = Context(
            messages = listOf(
                UserMessage(listOf(ImageContent("a", "image/png"), TextContent("t"), ImageContent("b", "image/png"))),
            ),
        )
        val content = body(context, model = textModel)["messages"]!!.jsonArray.single()
            .jsonObject["content"]!!
        assertEquals(
            "text",
            (content as JsonArray)[0].jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "(image omitted: model does not support images)",
            content[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        // Adjacent images share one placeholder.
        assertEquals(3, content.size)
        assertEquals("t", content[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `temperature sent only without thinking and when supported`() {
        val context = Context(messages = listOf(UserMessage.ofText("hi")))
        assertEquals(
            0.7,
            body(context, AnthropicMessagesOptions(apiKey = "k", temperature = 0.7))["temperature"]!!
                .jsonPrimitive.content.toDouble(),
        )
        assertFalse(
            body(
                context,
                AnthropicMessagesOptions(apiKey = "k", temperature = 0.7, thinkingEnabled = true),
            ).containsKey("temperature"),
        )
        val noTemp = claude.copy(anthropicCompat = claude.anthropicCompat.copy(supportsTemperature = false))
        assertFalse(body(context, AnthropicMessagesOptions(apiKey = "k", temperature = 0.7), model = noTemp).containsKey("temperature"))
    }

    @Test
    fun `thinking maps to adaptive budget and disabled forms`() {
        val context = Context(messages = listOf(UserMessage.ofText("hi")))

        // Budget-based thinking (default model): default budget 1024, summarized display.
        val budget = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true),
        )["thinking"]!!.jsonObject
        assertEquals("enabled", budget["type"]!!.jsonPrimitive.content)
        assertEquals(1024, budget["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("summarized", budget["display"]!!.jsonPrimitive.content)

        // Explicit budget and omitted display.
        val explicit = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true, thinkingBudgetTokens = 8192),
        )["thinking"]!!.jsonObject
        assertEquals(8192, explicit["budget_tokens"]!!.jsonPrimitive.content.toInt())

        // Adaptive thinking: effort via output_config.
        val adaptiveModel = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(forceAdaptiveThinking = true),
        )
        val adaptiveJson = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true, effort = AnthropicEffort.HIGH),
            model = adaptiveModel,
        )
        val adaptive = adaptiveJson["thinking"]!!.jsonObject
        assertEquals("adaptive", adaptive["type"]!!.jsonPrimitive.content)
        assertEquals("high", adaptiveJson["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        // Explicitly disabled thinking.
        val disabled = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = false),
        )["thinking"]!!.jsonObject
        assertEquals("disabled", disabled["type"]!!.jsonPrimitive.content)

        // An explicit null "off" entry means off is unsupported: no disabled block.
        val offBlocked = claude.copy(
            thinkingLevelMap = works.resolve.pathfinder.ai.core.ThinkingLevelMap.of(
                works.resolve.pathfinder.ai.core.ModelThinkingLevel.OFF to null,
            ),
        )
        assertFalse(
            body(context, AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = false), model = offBlocked)
                .containsKey("thinking"),
        )

        // Non-reasoning models never get thinking.
        assertFalse(
            body(
                context,
                AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true),
                model = claude.copy(reasoning = false),
            ).containsKey("thinking"),
        )
    }

    @Test
    fun `tool choice maps to anthropic tool_choice objects`() {
        val context = Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool))
        assertEquals(
            "any",
            body(context, AnthropicMessagesOptions(apiKey = "k", toolChoice = AnthropicToolChoice.Any))
                ["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        val forced = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", toolChoice = AnthropicToolChoice.Tool("edit")),
        )["tool_choice"]!!.jsonObject
        assertEquals("tool", forced["type"]!!.jsonPrimitive.content)
        assertEquals("edit", forced["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools convert with eager streaming and trailing cache_control`() {
        val context = Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(tool))
        val converted = body(context)["tools"]!!.jsonArray[0].jsonObject
        assertEquals("edit", converted["name"]!!.jsonPrimitive.content)
        assertEquals("Edit a file.", converted["description"]!!.jsonPrimitive.content)
        assertEquals(true, converted["eager_input_streaming"]!!.jsonPrimitive.content.toBoolean())
        val schema = converted["input_schema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals("string", schema["properties"]!!.jsonObject["path"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("path", schema["required"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(
            "ephemeral",
            converted["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )

        // No eager flag for models without it; no tool cache_control when unsupported.
        val legacy = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(
                supportsEagerToolInputStreaming = false,
                supportsCacheControlOnTools = false,
            ),
        )
        val legacyTool = body(context, model = legacy)["tools"]!!.jsonArray[0].jsonObject
        assertNull(legacyTool["eager_input_streaming"])
        assertNull(legacyTool["cache_control"])
    }

    @Test
    fun `oauth requests carry claude code identity and tool name casing`() {
        val context = Context(
            systemPrompt = "Custom prompt.",
            messages = listOf(UserMessage.ofText("hi")),
            tools = listOf(tool.copy(name = "bash"), tool.copy(name = "custom_tool")),
        )
        val json = buildRequestBody(claude, context, isOAuthToken = true, AnthropicMessagesOptions(apiKey = "sk-ant-oat-1"))
        val system = json["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertEquals(
            "You are Claude Code, Anthropic's official CLI for Claude.",
            system[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("Custom prompt.", system[1].jsonObject["text"]!!.jsonPrimitive.content)
        val names = json["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Bash", "custom_tool"), names)
    }

    @Test
    fun `sanitizeSurrogates removes only unpaired surrogates`() {
        assertEquals("Hello 🙈 World", sanitizeSurrogates("Hello 🙈 World"))
        val unpairedHigh = String(charArrayOf('t', 0xD83D.toChar(), 'x'))
        assertEquals("tx", sanitizeSurrogates(unpairedHigh))
        val unpairedLow = String(charArrayOf('t', 0xDE48.toChar(), 'x'))
        assertEquals("tx", sanitizeSurrogates(unpairedLow))
    }

    /** pi xhigh.test.ts / max-thinking.test.ts: xhigh/max clamp to the high budget. */
    @Test
    fun `xhigh and max clamp to the high thinking budget`() {
        for (level in listOf(ThinkingLevel.XHIGH, ThinkingLevel.MAX)) {
            val (maxTokens, thinkingBudget) =
                adjustMaxTokensForThinking(baseMaxTokens = null, modelMaxTokens = 64_000, level)
            assertEquals(64_000, maxTokens, "$level maxTokens")
            assertEquals(DEFAULT_THINKING_BUDGETS.getValue(ThinkingLevel.HIGH), thinkingBudget, "$level budget")
        }
    }

    /** pi thinkingBudgetForLevel: customBudgets merge over defaults. */
    @Test
    fun `custom thinking budgets override defaults per level`() {
        val custom = mapOf(ThinkingLevel.MEDIUM to 1000, ThinkingLevel.XHIGH to 2000)
        assertEquals(1000, thinkingBudgetForLevel(ThinkingLevel.MEDIUM, custom))
        // xhigh clamps to high, which has no custom override, so the default high budget applies.
        assertEquals(
            DEFAULT_THINKING_BUDGETS.getValue(ThinkingLevel.HIGH),
            thinkingBudgetForLevel(ThinkingLevel.XHIGH, custom),
        )
        // A custom high budget is used even after xhigh clamping.
        assertEquals(2000, thinkingBudgetForLevel(ThinkingLevel.HIGH, mapOf(ThinkingLevel.HIGH to 2000)))
    }

    /** pi anthropic-messages.ts: `budget_tokens: options.thinkingBudgetTokens || 1024` — 0 coerces to 1024. */
    @Test
    fun `thinking budget tokens of zero coerces to 1024 like pi`() {
        val context = Context(messages = listOf(UserMessage.ofText("hi")))
        val budget = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true, thinkingBudgetTokens = 0),
        )["thinking"]!!.jsonObject
        assertEquals(1024, budget["budget_tokens"]!!.jsonPrimitive.content.toInt())
    }

    /**
     * Port of pi's "only sends the full input schema for strict JSON-schema
     * tools" (test/anthropic-eager-tool-input-compat.test.ts): with
     * supportsStrictTools, only tools whose constrainedSampling resolves
     * strict get the rewritten full schema and the `strict: true` wire field;
     * everything else keeps the legacy input_schema shape.
     */
    @Test
    fun `only sends the full input schema for strict json-schema tools`() {
        val strictModel = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(supportsStrictTools = true),
        )
        val schemaCompatibilityTool = tool.copy(
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false,"title":"EditInput"}""",
            ),
        )
        val strictTool = tool.copy(
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"value":{"type":"string"},"optional":{"type":"number"}},"required":["value"],"title":"StrictLookupInput"}""",
            ),
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.PREFER),
        )

        fun firstTool(json: JsonObject): JsonObject = json["tools"]!!.jsonArray[0].jsonObject

        // Enabled compat, no constrained sampling: exactly the legacy shape,
        // even when the tool schema carries additionalProperties/title.
        val legacy = body(
            Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(schemaCompatibilityTool)),
            model = strictModel,
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
            ),
            firstTool(legacy)["input_schema"],
        )
        assertNull(firstTool(legacy)["strict"])

        // Enabled compat, strict config: strict field plus rewritten full schema.
        val strict = body(
            Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(strictTool)),
            model = strictModel,
        )
        val strictToolJson = firstTool(strict)
        assertEquals(true, strictToolJson["strict"]!!.jsonPrimitive.content.toBoolean())
        val inputSchema = strictToolJson["input_schema"]!!.jsonObject
        assertEquals(
            false,
            inputSchema["additionalProperties"]!!.jsonPrimitive.content.toBoolean(),
        )
        assertEquals(
            listOf("value", "optional"),
            inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            """[{"type":"number"},{"type":"null"}]""",
            inputSchema["properties"]!!.jsonObject["optional"]!!.jsonObject["anyOf"].toString(),
        )
        assertEquals("StrictLookupInput", inputSchema["title"]!!.jsonPrimitive.content)
        assertEquals("object", inputSchema["type"]!!.jsonPrimitive.content)

        // Default compat (supportsStrictTools false): prefer downgrades to the
        // legacy shape with no strict field.
        val downgraded = body(
            Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(strictTool)),
        )
        assertNull(firstTool(downgraded)["strict"])
        assertEquals(
            Json.parseToJsonElement(
                """{"type":"object","properties":{"value":{"type":"string"},"optional":{"type":"number"}},"required":["value"]}""",
            ),
            firstTool(downgraded)["input_schema"],
        )
    }

    /**
     * pi anthropic-messages.ts convertTools → resolveJsonSchemaStrictSampling:
     * `require` rejects with pi's exact error when the model has no strict
     * tool support.
     */
    @Test
    fun `require strict tools reject when compat is false`() {
        val requireTool = tool.copy(
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.REQUIRE),
        )
        val failure = assertFailsWith<Error> {
            body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(requireTool)))
        }
        assertEquals(
            "Tool \"edit\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
            failure.message,
        )
    }
}
