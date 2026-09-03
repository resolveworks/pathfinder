package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AnthropicAllowedFallbackModel
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
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
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import org.junit.Assume.assumeTrue
import java.io.File

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

    /** Deferred-tool OAuth cases run with the Claude Code name canonicalizer. */
    private fun oauthBody(context: Context, model: Model = claude): JsonObject =
        buildRequestBody(
            model,
            context,
            isOAuthToken = true,
            AnthropicMessagesOptions(apiKey = "sk-ant-oat-fake", cacheRetention = CacheRetention.NONE),
        )

    private var realCatalog: ProviderCatalog? = null

    /** The generated asset, mirroring ProviderCatalogTest's realAsset(). */
    private fun realAsset(): ProviderCatalog {
        val file = File("src/main/assets/models-catalog.json")
        assumeTrue("real catalog asset not found at ${file.absolutePath}", file.isFile)
        var cached = realCatalog
        if (cached == null) {
            cached = ProviderCatalog.parse(file.readText())
            realCatalog = cached
        }
        return cached
    }

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
        val system = json["system"]!!.jsonArray.single().jsonObject
        assertEquals("Be helpful.", system["text"]!!.jsonPrimitive.content)
        assertEquals(
            "ephemeral",
            system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
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
        val envJson = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", env = mapOf("PI_CACHE_RETENTION" to "long")),
        )
        assertEquals(
            "1h",
            envJson["system"]!!.jsonArray.single().jsonObject["cache_control"]!!
                .jsonObject["ttl"]!!.jsonPrimitive.content,
        )
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

    /**
     * Ports cache-retention's default-TTL case: with no explicit retention and
     * no PI_CACHE_RETENTION env, cache_control carries the type only — no ttl.
     */
    @Test
    fun `default cache retention sends cache_control without a ttl`() {
        val context = Context(systemPrompt = "s", messages = listOf(UserMessage.ofText("hi")))
        val cacheControl = body(context)["system"]!!.jsonArray.single().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("type"), cacheControl.keys)
    }

    /**
     * Ports cache-retention's proxy-baseUrl case: a non-api.anthropic.com
     * baseUrl keeps the default supportsLongCacheRetention=true, so a long
     * retention resolves to the 1h ttl for proxied models too.
     */
    @Test
    fun `long retention applies the 1h ttl for proxy base urls`() {
        val context = Context(systemPrompt = "s", messages = listOf(UserMessage.ofText("hi")))
        val proxy = claude.copy(baseUrl = "https://my-proxy.example.com/v1")
        val json = body(
            context,
            AnthropicMessagesOptions(apiKey = "fake-key", env = mapOf("PI_CACHE_RETENTION" to "long")),
            model = proxy,
        )
        assertEquals(
            "1h",
            json["system"]!!.jsonArray.single().jsonObject["cache_control"]!!
                .jsonObject["ttl"]!!.jsonPrimitive.content,
        )
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
        // No "(see attached image)" placeholder: pi applies convertContentBlocks
        // (which adds it) to tool-result content only.
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

        val allowModel = claude.copy(anthropicCompat = claude.anthropicCompat.copy(allowEmptySignature = true))
        val allowJson = body(Context(messages = listOf(abortedThinking, UserMessage.ofText("next"))), model = allowModel)
        val allowBlock = allowJson["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("thinking", allowBlock["type"]!!.jsonPrimitive.content)
        assertEquals("", allowBlock["signature"]!!.jsonPrimitive.content)
    }

    /**
     * Ports anthropic-empty-thinking-signature-compat: an empty *thinking*
     * text is preserved as a thinking block as long as the signature is
     * present, and whitespace-only signatures count as absent (normalized to
     * "" when allowEmptySignature keeps the block).
     */
    @Test
    fun `empty thinking text is preserved when the signature is present`() {
        val signedEmptyThinking = AssistantMessage(
            content = listOf(ThinkingContent("", "signed-thinking")),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            stopReason = StopReason.STOP,
        )
        val json = body(Context(messages = listOf(signedEmptyThinking, UserMessage.ofText("next"))))
        val block = json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("thinking", block["type"]!!.jsonPrimitive.content)
        assertEquals("", block["thinking"]!!.jsonPrimitive.content)
        assertEquals("signed-thinking", block["signature"]!!.jsonPrimitive.content)

        val whitespaceSignature = signedEmptyThinking.copy(
            content = listOf(ThinkingContent("internal reasoning", " ")),
        )
        val allowModel = claude.copy(anthropicCompat = claude.anthropicCompat.copy(allowEmptySignature = true))
        val whitespaceBlock = body(
            Context(messages = listOf(whitespaceSignature, UserMessage.ofText("next"))),
            model = allowModel,
        )["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("thinking", whitespaceBlock["type"]!!.jsonPrimitive.content)
        assertEquals("internal reasoning", whitespaceBlock["thinking"]!!.jsonPrimitive.content)
        assertEquals("", whitespaceBlock["signature"]!!.jsonPrimitive.content)
    }

    /** Ports anthropic-empty-thinking-signature-compat's catalog case: Kimi
     * Coding models are marked allowEmptySignature, so their empty-signature
     * thinking blocks replay as thinking instead of degrading to text. */
    @Test
    fun `catalog kimi coding models keep empty-signature thinking blocks`() {
        val k3 = realAsset().getModel("kimi-coding", "k3")!!
        assertTrue(k3.anthropicCompat.allowEmptySignature)

        val assistant = AssistantMessage(
            content = listOf(ThinkingContent("internal reasoning", " ")),
            api = "anthropic-messages",
            provider = "kimi-coding",
            model = "k3",
            stopReason = StopReason.STOP,
        )
        val block = body(
            Context(
                messages = listOf(
                    UserMessage.ofText("first"),
                    assistant,
                    UserMessage.ofText("second"),
                ),
            ),
            model = k3,
        )["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("thinking", block["type"]!!.jsonPrimitive.content)
        assertEquals("internal reasoning", block["thinking"]!!.jsonPrimitive.content)
        assertEquals("", block["signature"]!!.jsonPrimitive.content)
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

    /**
     * Ports anthropic-temperature-compat against the generated catalog:
     * Claude Opus 4.7/4.8 disable supportsTemperature (temperature omitted,
     * including the default 1), while Opus 4.6 and Sonnet 4.6 keep it.
     */
    @Test
    fun `catalog opus 4 7 and 4 8 omit temperature while opus 4 6 keeps it`() {
        val catalog = realAsset()
        val context = Context(messages = listOf(UserMessage.ofText("hi")))

        for (modelId in listOf("claude-opus-4-7", "claude-opus-4-8")) {
            val model = catalog.getModel("anthropic", modelId)!!
            for (temperature in listOf(0.0, 1.0)) {
                assertFalse(
                    body(
                        context,
                        AnthropicMessagesOptions(apiKey = "k", temperature = temperature),
                        model = model,
                    ).containsKey("temperature"),
                    "$modelId temperature=$temperature",
                )
            }
        }
        for (modelId in listOf("claude-opus-4-6", "claude-sonnet-4-6")) {
            val model = catalog.getModel("anthropic", modelId)!!
            assertEquals(
                0.0,
                body(
                    context,
                    AnthropicMessagesOptions(apiKey = "k", temperature = 0.0),
                    model = model,
                )["temperature"]!!.jsonPrimitive.content.toDouble(),
                modelId,
            )
        }
    }

    @Test
    fun `thinking maps to adaptive budget and disabled forms`() {
        val context = Context(messages = listOf(UserMessage.ofText("hi")))

        val budget = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true),
        )["thinking"]!!.jsonObject
        assertEquals("enabled", budget["type"]!!.jsonPrimitive.content)
        assertEquals(1024, budget["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("summarized", budget["display"]!!.jsonPrimitive.content)

        val explicit = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true, thinkingBudgetTokens = 8192),
        )["thinking"]!!.jsonObject
        assertEquals(8192, explicit["budget_tokens"]!!.jsonPrimitive.content.toInt())

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

    @Test
    fun `xhigh and max clamp to the high thinking budget`() {
        for (level in listOf(ThinkingLevel.XHIGH, ThinkingLevel.MAX)) {
            val (maxTokens, thinkingBudget) =
                adjustMaxTokensForThinking(baseMaxTokens = null, modelMaxTokens = 64_000, level)
            assertEquals(64_000, maxTokens, "$level maxTokens")
            assertEquals(DEFAULT_THINKING_BUDGETS.getValue(ThinkingLevel.HIGH), thinkingBudget, "$level budget")
        }
    }

    @Test
    fun `custom thinking budgets override defaults per level`() {
        val custom = mapOf(ThinkingLevel.MEDIUM to 1000, ThinkingLevel.XHIGH to 2000)
        assertEquals(1000, thinkingBudgetForLevel(ThinkingLevel.MEDIUM, custom))
        // xhigh clamps to high, which has no custom override, so the default high budget applies.
        assertEquals(
            DEFAULT_THINKING_BUDGETS.getValue(ThinkingLevel.HIGH),
            thinkingBudgetForLevel(ThinkingLevel.XHIGH, custom),
        )
        assertEquals(2000, thinkingBudgetForLevel(ThinkingLevel.HIGH, mapOf(ThinkingLevel.HIGH to 2000)))
    }

    @Test
    fun `thinking budget tokens of zero coerces to 1024 like pi`() {
        val context = Context(messages = listOf(UserMessage.ofText("hi")))
        val budget = body(
            context,
            AnthropicMessagesOptions(apiKey = "k", thinkingEnabled = true, thinkingBudgetTokens = 0),
        )["thinking"]!!.jsonObject
        assertEquals(1024, budget["budget_tokens"]!!.jsonPrimitive.content.toInt())
    }

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

    @Test
    fun `require strict tools reject when compat is false`() {
        val requireTool = tool.copy(
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.REQUIRE),
        )
        val failure = assertFailsWith<ConstrainedSamplingError> {
            body(Context(messages = listOf(UserMessage.ofText("hi")), tools = listOf(requireTool)))
        }
        assertEquals(
            "Tool \"edit\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
            failure.message,
        )
    }

    // Anthropic rejects the fallbacks field for models with no permitted
    // fallback targets, so an empty catalog list omits it entirely.
    @Test
    fun `fallbacks carry model ids only when the catalog list is non-empty`() {
        val context = Context(messages = listOf(UserMessage.ofText("hi")))
        val fable = claude.copy(
            anthropicCompat = claude.anthropicCompat.copy(
                allowedFallbackModels = listOf(
                    AnthropicAllowedFallbackModel(
                        provider = "anthropic",
                        model = "claude-opus-4-8",
                        cost = ModelCost(input = 5.0, output = 25.0, cacheRead = 0.5, cacheWrite = 6.25),
                    ),
                    AnthropicAllowedFallbackModel(
                        provider = "anthropic",
                        model = "claude-opus-5",
                        cost = ModelCost(input = 5.0, output = 25.0, cacheRead = 0.5, cacheWrite = 6.25),
                    ),
                ),
            ),
        )
        val json = body(context, model = fable)
        val fallbacks = json["fallbacks"]!!.jsonArray
        assertEquals("claude-opus-4-8", fallbacks[0].jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals("claude-opus-5", fallbacks[1].jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(setOf("model"), fallbacks[0].jsonObject.keys)
        assertEquals(setOf("model"), fallbacks[1].jsonObject.keys)
        assertNull(body(context)["fallbacks"])
    }

    // ---- Mid-conversation effort (ports anthropic-mid-conversation-effort.test.ts) ----

    // Upstream "generates exact model and transport gates": the regenerated
    // catalog gates the managed-effort path — direct anthropic and openrouter
    // registrations carry supportsMidConvoEffort, older models do not.
    @Test
    fun `generates exact model and transport gates`() {
        val catalog = realAsset()
        val direct = catalog.getModel("anthropic", "claude-fable-5-1")!!
        val openRouter = catalog.getModel("openrouter", "anthropic/claude-fable-5.1")!!
        val unsupported = catalog.getModel("anthropic", "claude-opus-4-8")!!
        assertTrue(direct.anthropicCompat.supportsMidConvoEffort)
        assertTrue(direct.thinkingLevelMap!!.isSpecified(ModelThinkingLevel.OFF))
        assertNull(direct.thinkingLevelMap.forLevel(ModelThinkingLevel.OFF))
        assertEquals("anthropic-messages", openRouter.api)
        assertEquals("https://openrouter.ai/api", openRouter.baseUrl)
        assertTrue(openRouter.anthropicCompat.supportsMidConvoEffort)
        assertFalse(unsupported.anthropicCompat.supportsMidConvoEffort)
        assertTrue(
            catalog.getModel("anthropic", "claude-opus-5")!!.anthropicCompat.allowedFallbackModels.isEmpty(),
        )
    }

    private fun managedModel(provider: String = "anthropic"): Model = claude.copy(
        id = "claude-fable-5-1",
        provider = provider,
        anthropicCompat = claude.anthropicCompat.copy(
            forceAdaptiveThinking = true,
            supportsMidConvoEffort = true,
        ),
    )

    private fun managedAssistant(model: Model, level: String? = null): AssistantMessage {
        val providerLevel = level
        return AssistantMessage(
            content = listOf(
                ThinkingContent("reasoning", thinkingSignature = "signature"),
                TextContent("answer"),
            ),
            api = "anthropic-messages",
            provider = model.provider,
            model = model.id,
            providerThinkingLevel = providerLevel,
            stopReason = StopReason.STOP,
            timestamp = 1,
        )
    }

    private fun managedOptions(effort: AnthropicEffort? = null) = AnthropicMessagesOptions(
        apiKey = "k",
        cacheRetention = CacheRetention.NONE,
        thinkingEnabled = true,
        effort = effort,
    )

    private fun List<JsonObject>.effortMarkers(): List<JsonObject> =
        filter { it["role"]!!.jsonPrimitive.content == "system" }

    private fun JsonObject.wire(): String = toString()

    @Test
    fun `managed effort reconstructs the historical marker prefix and appends the current marker`() {
        val model = managedModel()
        val first = body(
            Context(messages = listOf(UserMessage.ofText("one", timestamp = 1))),
            managedOptions(AnthropicEffort.LOW),
            model,
        )
        val second = body(
            Context(
                messages = listOf(
                    UserMessage.ofText("one", timestamp = 1),
                    managedAssistant(model, "low"),
                    UserMessage.ofText("two", timestamp = 2),
                ),
            ),
            managedOptions(AnthropicEffort.HIGH),
            model,
        )

        val firstMessages = first["messages"]!!.jsonArray.map { it.jsonObject }
        val secondMessages = second["messages"]!!.jsonArray.map { it.jsonObject }
        val lowMarker = """{"role":"system","content":[],"output_config":{"effort":"low"}}"""
        val highMarker = """{"role":"system","content":[],"output_config":{"effort":"high"}}"""
        assertEquals(
            listOf("""{"role":"user","content":[{"type":"text","text":"one"}]}""", lowMarker),
            firstMessages.map { it.wire() },
        )
        // The exact historical prefix reconstructs, and the current marker is last.
        assertEquals(firstMessages.map { it.wire() }, secondMessages.take(firstMessages.size).map { it.wire() })
        assertEquals(highMarker, secondMessages.last().wire())
        // Top-level output_config stays "high"; active effort travels per-message.
        assertEquals("""{"effort":"high"}""", second["output_config"].toString())
        assertEquals("""{"effort":"high"}""", first["output_config"].toString())
        assertEquals(
            """{"type":"adaptive","display":"summarized","block_binding":{"prefix_mismatch_behavior":"drop_block"}}""",
            second["thinking"].toString(),
        )
    }

    @Test
    fun `managed effort preserves each native effort level`() {
        for (effort in AnthropicEffort.entries) {
            val json = body(
                Context(messages = listOf(UserMessage.ofText("one", timestamp = 1))),
                managedOptions(effort),
                managedModel(),
            )
            val markers = json["messages"]!!.jsonArray.map { it.jsonObject }.effortMarkers()
            assertEquals(
                listOf("""{"role":"system","content":[],"output_config":{"effort":"${effort.name.lowercase()}"}}"""),
                markers.map { it.wire() },
                "effort $effort",
            )
        }
    }

    @Test
    fun `managed effort defaults omitted effort to high and still enables drop_block`() {
        val json = body(
            Context(messages = listOf(UserMessage.ofText("one", timestamp = 1))),
            managedOptions(),
            managedModel(),
        )
        assertEquals(
            """{"role":"system","content":[],"output_config":{"effort":"high"}}""",
            json["messages"]!!.jsonArray.map { it.jsonObject }.last().wire(),
        )
        assertEquals(
            "drop_block",
            json["thinking"]!!.jsonObject["block_binding"]!!.jsonObject["prefix_mismatch_behavior"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `managed effort invents no markers for legacy or other-provider assistants`() {
        val model = managedModel()
        val legacy = managedAssistant(model)
        val otherProvider = managedAssistant(model, "low").copy(provider = "other-provider")
        val json = body(
            Context(
                messages = listOf(
                    UserMessage.ofText("one", timestamp = 1),
                    legacy,
                    UserMessage.ofText("two", timestamp = 2),
                    otherProvider,
                    UserMessage.ofText("three", timestamp = 3),
                ),
            ),
            managedOptions(AnthropicEffort.MEDIUM),
            model,
        )
        assertEquals(
            listOf("""{"role":"system","content":[],"output_config":{"effort":"medium"}}"""),
            json["messages"]!!.jsonArray.map { it.jsonObject }.effortMarkers().map { it.wire() },
        )
    }

    @Test
    fun `unsupported models stay on top-level effort`() {
        val model = managedModel().copy(
            anthropicCompat = managedModel().anthropicCompat.copy(supportsMidConvoEffort = false),
        )
        val json = body(
            Context(messages = listOf(UserMessage.ofText("one", timestamp = 1))),
            managedOptions(AnthropicEffort.LOW),
            model,
        )
        assertEquals(
            listOf("""{"role":"user","content":[{"type":"text","text":"one"}]}"""),
            json["messages"]!!.jsonArray.map { it.jsonObject }.map { it.wire() },
        )
        assertEquals("""{"effort":"low"}""", json["output_config"].toString())
        assertEquals("""{"type":"adaptive","display":"summarized"}""", json["thinking"].toString())
    }

    @Test
    fun `managed effort suppresses temperature`() {
        val json = body(
            Context(messages = listOf(UserMessage.ofText("one", timestamp = 1))),
            managedOptions().copy(temperature = 0.5),
            managedModel(),
        )
        assertNull(json["temperature"])
        // The same options still send temperature on an unmanaged model.
        val unmanaged = body(
            Context(messages = listOf(UserMessage.ofText("one", timestamp = 1))),
            managedOptions().copy(temperature = 0.5, thinkingEnabled = null),
            managedModel().copy(anthropicCompat = claude.anthropicCompat.copy(forceAdaptiveThinking = true)),
        )
        assertEquals(0.5, unmanaged["temperature"]!!.jsonPrimitive.content.toDouble())
    }

    // ---- Deferred tools (ports the anthropic wiring of splitDeferredTools) ----

    @Test
    fun `defaultSupportsToolReferences gates on provider family and version`() {
        fun model(id: String, provider: String = "anthropic") =
            claude.copy(id = id, provider = provider)

        assertTrue(defaultSupportsToolReferences(model("claude-sonnet-4-5")))
        assertTrue(defaultSupportsToolReferences(model("claude-opus-4-6")))
        assertTrue(defaultSupportsToolReferences(model("claude-fable-5-1")))
        assertTrue(defaultSupportsToolReferences(model("claude-sonnet-5")))
        // Haiku rejects client-side tool references.
        assertFalse(defaultSupportsToolReferences(model("claude-haiku-4-5")))
        // Claude 3.x and pre-4.5 Opus predate tool search.
        assertFalse(defaultSupportsToolReferences(model("claude-3-7-sonnet-20250219")))
        assertFalse(defaultSupportsToolReferences(model("claude-opus-4-1-20250805")))
        assertFalse(defaultSupportsToolReferences(model("claude-opus-4-0")))
        // Unknown families and other providers stay off.
        assertFalse(defaultSupportsToolReferences(model("claude-mythos-preview")))
        assertFalse(defaultSupportsToolReferences(model("claude-sonnet-4-5", provider = "openrouter")))
        // An explicit compat value overrides the default in both directions.
        assertTrue(
            supportsToolReferences(
                model("claude-haiku-4-5").copy(
                    anthropicCompat = claude.anthropicCompat.copy(supportsToolReferences = true),
                ),
            ),
        )
        assertFalse(
            supportsToolReferences(
                model("claude-sonnet-4-5").copy(
                    anthropicCompat = claude.anthropicCompat.copy(supportsToolReferences = false),
                ),
            ),
        )
    }

    @Test
    fun `deferred tools split into defer_loading definitions and tool_reference results`() {
        // Ports deferred-tools.test.ts "loads an Anthropic tool at its tool-result
        // marker": the loaded tool must differ from the called tool — a tool the
        // assistant already used stays immediate ("keeps a tool immediate when
        // it was used before its marker").
        val search = tool.copy(name = "search", description = "Search the web.")
        val call = ToolCall(id = "toolu_edit", name = "edit", arguments = "{}")
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(call),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-sonnet-4-5",
                    stopReason = StopReason.TOOL_USE,
                ),
                ToolResultMessage(
                    toolCallId = "toolu_edit",
                    toolName = "edit",
                    content = listOf(TextContent("search results")),
                    addedToolNames = listOf("search"),
                ),
                UserMessage.ofText("next"),
            ),
            tools = listOf(tool, search),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        val tools = json["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("edit", "search"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertNull(tools[0]["defer_loading"])
        assertEquals(true, tools[1]["defer_loading"]!!.jsonPrimitive.content.toBoolean())

        val messages = json["messages"]!!.jsonArray.map { it.jsonObject }
        // Assistant tool_use for the base tool, then the grouped tool-result
        // user message: the tool_reference replaces the ordinary content and
        // the displaced text follows after the tool_result block.
        val resultBlocks = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("edit", messages[0]["content"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(
            """{"type":"tool_result","tool_use_id":"toolu_edit","content":[{"type":"tool_reference","tool_name":"search"}],"is_error":false}""",
            resultBlocks[0].wire(),
        )
        assertEquals("""{"type":"text","text":"search results"}""", resultBlocks[1].wire())
        // No marker system messages: unmanaged model.
        assertNull(json["output_config"])
    }

    @Test
    fun `only the first load emits a tool_reference`() {
        val search = tool.copy(name = "search")
        val context = Context(
            messages = listOf(
                ToolResultMessage(
                    toolCallId = "toolu_1",
                    toolName = "search",
                    content = listOf(TextContent("first load")),
                    addedToolNames = listOf("search"),
                ),
                ToolResultMessage(
                    toolCallId = "toolu_2",
                    toolName = "search",
                    content = listOf(TextContent("plain result")),
                ),
            ),
            tools = listOf(tool, search),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        val messages = json["messages"]!!.jsonArray.map { it.jsonObject }
        val blocks = messages[0]["content"]!!.jsonArray.map { it.jsonObject }
        // The second result has no addedToolNames and stays ordinary content.
        assertEquals(
            """{"type":"tool_result","tool_use_id":"toolu_2","content":"plain result","is_error":false}""",
            blocks[1].wire(),
        )
    }

    @Test
    fun `all-deferred tools fall back to immediate`() {
        val search = tool.copy(name = "search")
        // A tool result that loaded "search" without a preceding assistant
        // call leaves no immediate tools; everything is sent immediate.
        val context = Context(
            messages = listOf(
                ToolResultMessage(
                    toolCallId = "toolu_1",
                    toolName = "search",
                    content = listOf(TextContent("loaded")),
                    addedToolNames = listOf("search"),
                ),
            ),
            tools = listOf(search),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        val tools = json["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("search"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertNull(tools[0]["defer_loading"])
    }

    @Test
    fun `preserves tool output as sibling content after emitting references across a batch`() {
        // Ports deferred-tools.test.ts "preserves tool output as sibling content
        // after emitting references": reference-bearing results emit only
        // tool_reference content, and the displaced text/image follows every
        // tool_result block of the consecutive run.
        val search = tool.copy(name = "search")
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(ToolCall("toolu_1", "edit", "{}"), ToolCall("toolu_2", "edit", "{}")),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-sonnet-4-5",
                    stopReason = StopReason.TOOL_USE,
                ),
                ToolResultMessage(
                    toolCallId = "toolu_1",
                    toolName = "edit",
                    content = listOf(TextContent("work completed"), ImageContent("aW1hZ2U=", "image/png")),
                    addedToolNames = listOf("search"),
                ),
                ToolResultMessage("toolu_2", "edit", listOf(TextContent("second result"))),
                UserMessage.ofText("next"),
            ),
            tools = listOf(tool, search),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        val grouped = json["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject.wire() }
        assertEquals(
            listOf(
                """{"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"tool_reference","tool_name":"search"}],"is_error":false}""",
                """{"type":"tool_result","tool_use_id":"toolu_2","content":"second result","is_error":false}""",
                """{"type":"text","text":"work completed"}""",
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aW1hZ2U="}}""",
            ),
            grouped,
        )
    }

    @Test
    fun `does not resurrect a marked tool missing from Context tools`() {
        // Ports deferred-tools.test.ts "does not resurrect a marked tool missing
        // from Context.tools": without an active definition the marker stays
        // inert and the ordinary result content survives.
        val context = Context(
            messages = listOf(
                ToolResultMessage(
                    toolCallId = "toolu_1",
                    toolName = "edit",
                    content = listOf(TextContent("done")),
                    addedToolNames = listOf("search"),
                ),
            ),
            tools = listOf(tool),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        assertEquals(
            listOf("edit"),
            json["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
        val resultContent = json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("done", (resultContent["content"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `keeps a tool immediate when it was used before its marker`() {
        // Ports deferred-tools.test.ts "keeps a tool immediate when it was used
        // before its marker": the usedNames guard keeps an already-called tool
        // out of the deferred set.
        val search = tool.copy(name = "search")
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(ToolCall("toolu_1", "search", "{}")),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-sonnet-4-5",
                    stopReason = StopReason.TOOL_USE,
                ),
                ToolResultMessage(
                    toolCallId = "toolu_1",
                    toolName = "search",
                    content = listOf(TextContent("loaded")),
                    addedToolNames = listOf("search"),
                ),
            ),
            tools = listOf(tool, search),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        val tools = json["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("edit", "search"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertTrue(tools.none { it.containsKey("defer_loading") })
    }

    @Test
    fun `loads a tool introduced by OpenAI history after switching to Anthropic`() {
        // Ports deferred-tools.test.ts "loads a tool introduced by OpenAI
        // history after switching to Anthropic": the marker travels with the
        // message list, not the assistant's provider.
        val search = tool.copy(name = "search")
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(ToolCall("call_1", "edit", "{}")),
                    api = "openai-responses",
                    provider = "openai",
                    model = "gpt-5.4",
                    stopReason = StopReason.TOOL_USE,
                ),
                ToolResultMessage(
                    toolCallId = "call_1",
                    toolName = "edit",
                    content = listOf(TextContent("done")),
                    addedToolNames = listOf("search"),
                ),
            ),
            tools = listOf(tool, search),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        val tools = json["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("edit", "search"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertNull(tools[0]["defer_loading"])
        assertEquals(true, tools[1]["defer_loading"]!!.jsonPrimitive.content.toBoolean())
        val resultBlock = json["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(
            """{"type":"tool_reference","tool_name":"search"}""",
            resultBlock["content"]!!.jsonArray[0].jsonObject.wire(),
        )
    }

    @Test
    fun `normalizes OAuth names before checking prior tool usage`() {
        // Ports deferred-tools.test.ts "normalizes OAuth names before checking
        // prior tool usage": Read/read canonicalize to one Claude Code name, so
        // a tool the assistant already used stays immediate.
        val read = tool.copy(name = "read", description = "Read a file.")
        val context = Context(
            messages = listOf(
                AssistantMessage(
                    content = listOf(ToolCall("toolu_1", "Read", "{}")),
                    api = "anthropic-messages",
                    provider = "anthropic",
                    model = "claude-sonnet-4-5",
                    stopReason = StopReason.TOOL_USE,
                ),
                ToolResultMessage(
                    toolCallId = "toolu_1",
                    toolName = "Read",
                    content = listOf(TextContent("done")),
                    addedToolNames = listOf("read"),
                ),
            ),
            tools = listOf(tool, read),
        )
        val json = oauthBody(context)
        val tools = json["tools"]!!.jsonArray.map { it.jsonObject }
        // "edit" itself canonicalizes to the Claude Code "Edit" under OAuth.
        assertEquals(listOf("Edit", "Read"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertTrue(tools.none { it.containsKey("defer_loading") })
        val resultContent = json["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("done", (resultContent["content"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `matches OAuth-canonicalized markers to active tools`() {
        // Ports deferred-tools.test.ts "matches OAuth-canonicalized markers to
        // active tools": a "Read" marker defers the "read" tool and emits the
        // canonical name in the reference.
        val read = tool.copy(name = "read")
        val context = Context(
            messages = listOf(
                ToolResultMessage(
                    toolCallId = "toolu_1",
                    toolName = "edit",
                    content = listOf(TextContent("done")),
                    addedToolNames = listOf("Read"),
                ),
            ),
            tools = listOf(tool, read),
        )
        val json = oauthBody(context)
        val tools = json["tools"]!!.jsonArray.map { it.jsonObject }
        // "edit" itself canonicalizes to the Claude Code "Edit" under OAuth.
        assertEquals(listOf("Edit", "Read"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertNull(tools[0]["defer_loading"])
        assertEquals(true, tools[1]["defer_loading"]!!.jsonPrimitive.content.toBoolean())
        val resultBlock = json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(
            """{"type":"tool_reference","tool_name":"Read"}""",
            resultBlock["content"]!!.jsonArray[0].jsonObject.wire(),
        )
    }

    @Test
    fun `deduplicates active tools after OAuth canonicalization`() {
        // Ports deferred-tools.test.ts "deduplicates active tools after OAuth
        // canonicalization": read/Read collapse to the later canonical entry.
        val context = Context(
            messages = listOf(UserMessage.ofText("hi")),
            tools = listOf(
                tool.copy(name = "read"),
                tool.copy(name = "Read", description = "Canonical definition"),
            ),
        )
        val json = oauthBody(context)
        val tools = json["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, tools.size)
        assertEquals("Read", tools[0]["name"]!!.jsonPrimitive.content)
        assertEquals("Canonical definition", tools[0]["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prefilled pipe-separated tool call ids normalize identically on tool_use and tool_result`() {
        // Ports the unit essence of tool-call-id-normalization.test.ts
        // "Prefilled Context" (pi-mono #1022): OpenAI Responses ids
        // (`{call_id}|{id}`, 400+ chars with +/=) must normalize on both sides
        // of the exchange when replayed to Anthropic.
        val failingId =
            "call_pAYbIr76hXIjncD9UE4eGfnS|t5nnb2qYMFWGSsr13fhCd1CaCu3t3qONEPuOudu4HSVEtA8YJSL6FAZUxvoOoD792VIJWl91g87EdqsCWp9krVsdBysQoDaf9lMCLb8BS4EYi4gQd5kBQBYLlgD71PYwvf+TbMD9J9/5OMD42oxSRj8H+vRf78/l2Xla33LWz4nOgsddBlbvabICRs8GHt5C9PK5keFtzyi3lsyVKNlfduK3iphsZqs4MLv4zyGJnvZo/+QzShyk5xnMSQX/f98+aEoNflEApCdEOXipipgeiNWnpFSHbcwmMkZoJhURNu+JEz3xCh1mrXeYoN5o+trLL3IXJacSsLYXDrYTipZZbJFRPAucgbnjYBC+/ZzJOfkwCs+Gkw7EoZR7ZQgJ8ma+9586n4tT4cI8DEhBSZsWMjrCt8dxKg=="
        val context = Context(
            messages = listOf(
                UserMessage.ofText("Use the echo tool to echo 'hello'"),
                AssistantMessage(
                    content = listOf(ToolCall(failingId, "echo", """{"message":"hello"}""")),
                    api = "openai-responses",
                    provider = "github-copilot",
                    model = "gpt-5.2-codex",
                    stopReason = StopReason.TOOL_USE,
                ),
                ToolResultMessage(failingId, "echo", listOf(TextContent("hello"))),
                UserMessage.ofText("Say hi"),
            ),
        )
        val json = body(context, AnthropicMessagesOptions(apiKey = "k", cacheRetention = CacheRetention.NONE))
        val messages = json["messages"]!!.jsonArray
        val toolUseId = messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        val toolResultId =
            messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content
        assertTrue(Regex("^[a-zA-Z0-9_-]{1,64}$").matches(toolUseId))
        assertEquals(toolUseId, toolResultId)
    }
}
