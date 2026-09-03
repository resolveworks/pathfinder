package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.ProviderAuthException
import works.resolve.pathfinder.ai.ProviderStreamException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.GrammarFormat
import works.resolve.pathfinder.ai.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCost
import works.resolve.pathfinder.ai.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.SessionAffinityFormat
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.shortHash

class OpenAiResponsesSharedTest {

    private val json = lenientJson

    private fun model(
        provider: String = "openai",
        api: String = "openai-responses",
        id: String = "gpt-5-mini",
        reasoning: Boolean = true,
        input: List<InputModality> = listOf(InputModality.TEXT),
        compat: OpenAiResponsesCompat? = OpenAiResponsesCompat(),
    ) = Model(
        id = id,
        name = id,
        api = api,
        provider = provider,
        baseUrl = "https://api.openai.com/v1",
        reasoning = reasoning,
        input = input,
        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.25, cacheWrite = 0.5),
        contextWindow = 400_000,
        maxTokens = 128_000,
        responsesCompat = compat,
    )

    @Test
    fun `shortHash matches pi's reference values`() {
        assertEquals("y0biex7f9bbh", shortHash("abc"))
        assertEquals("4jcgrciydsyu", shortHash("call_x|fc_abc"))
    }

    @Test
    fun `shortHash is deterministic and length-bounded`() {
        val long = "x".repeat(500)
        assertEquals(shortHash(long), shortHash(long))
        assertTrue(shortHash(long).length <= 26)
    }

    @Test
    fun `sanitizeSurrogates drops only unpaired surrogates`() {
        val paired = "Hello 🙈 World"
        assertEquals(paired, sanitizeSurrogates(paired))
        val unpairedHigh = "Text ${"\uD83D"} here"
        val unpairedLow = "Text ${"\uDC00"} here"
        assertEquals("Text  here", sanitizeSurrogates(unpairedHigh))
        assertEquals("Text  here", sanitizeSurrogates(unpairedLow))
    }

    @Test
    fun `clampOpenAIPromptCacheKey truncates at 64 characters`() {
        assertNull(clampOpenAIPromptCacheKey(null))
        assertEquals("short", clampOpenAIPromptCacheKey("short"))
        val long = "s".repeat(100)
        assertEquals(64, clampOpenAIPromptCacheKey(long)!!.length)
        // pi clamps by Unicode code points, so 64 emoji survive as 128 chars.
        val emoji = "🙈".repeat(100)
        assertEquals(128, clampOpenAIPromptCacheKey(emoji)!!.length)
    }

    @Test
    fun `text signatures round-trip v1 payloads and legacy strings`() {
        assertEquals("msg_1" to null, OpenAiResponsesShared.parseTextSignature("msg_1"))
        assertEquals(
            "msg_1" to "final_answer",
            OpenAiResponsesShared.parseTextSignature("""{"v":1,"id":"msg_1","phase":"final_answer"}"""),
        )
        assertEquals("msg_1" to null, OpenAiResponsesShared.parseTextSignature("""{"v":1,"id":"msg_1"}"""))
        assertEquals("""{"v":2}""" to null, OpenAiResponsesShared.parseTextSignature("""{"v":2}"""))
        assertNull(OpenAiResponsesShared.parseTextSignature(null))
    }

    @Test
    fun `system prompt maps to developer role for reasoning models`() {
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(systemPrompt = "You are concise.", messages = emptyList()),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        assertEquals(
            """{"role":"developer","content":"You are concise."}""",
            input.single().toString(),
        )
    }

    @Test
    fun `system prompt maps to system role when developer unsupported`() {
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(compat = OpenAiResponsesCompat(supportsDeveloperRole = false)),
            Context(systemPrompt = "sys", messages = emptyList()),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        assertEquals("system", input.single()["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user content converts to input_text and input_image parts`() {
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(input = listOf(InputModality.TEXT, InputModality.IMAGE)),
            Context(
                messages = listOf(
                    UserMessage(
                        listOf(
                            TextContent("hello"),
                            ImageContent(data = "AAAA", mimeType = "image/png"),
                        ),
                    ),
                ),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        val content = input.single()["content"]!!.jsonArray
        assertEquals("input_text", content[0]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hello", content[0]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("input_image", content[1]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            content[1]!!.jsonObject["image_url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `images downgrade to a placeholder for non-vision models`() {
        val messages = transformMessages(
            listOf(UserMessage(listOf(TextContent("a"), ImageContent("AAAA", "image/png"), ImageContent("BBBB", "image/png"), TextContent("b")))),
            model(),
        )
        val content = (messages.single() as UserMessage).content
        assertEquals(
            listOf("a", "(image omitted: model does not support images)", "b"),
            content.map { (it as TextContent).text },
        )
    }

    @Test
    fun `assistant text replays with signed message ids`() {
        val assistant = AssistantMessage(
            content = listOf(TextContent("hi", """{"v":1,"id":"msg_real"}""")),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.STOP,
        )
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(assistant)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        val item = input.single()
        assertEquals("message", item["type"]!!.jsonPrimitive.content)
        assertEquals("msg_real", item["id"]!!.jsonPrimitive.content)
        assertEquals("completed", item["status"]!!.jsonPrimitive.content)
        assertEquals(
            "output_text",
            item["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `unsigned assistant text falls back to positional msg_pi ids`() {
        val assistant = AssistantMessage(
            content = listOf(TextContent("one"), TextContent("two")),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.STOP,
        )
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(UserMessage.ofText("q"), assistant)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        assertEquals("msg_pi_1", input[1]["id"]!!.jsonPrimitive.content)
        assertEquals("msg_pi_1_1", input[2]["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `oversized signed message ids hash down to 64 characters`() {
        val longId = "msg_0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789"
        val assistant = AssistantMessage(
            content = listOf(TextContent("hi", """{"v":1,"id":"$longId"}""")),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.STOP,
        )
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(assistant)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        val id = input.single()["id"]!!.jsonPrimitive.content
        assertEquals("1lfieqfu5oau7", shortHash(longId))
        assertEquals("msg_1lfieqfu5oau7", id)
        assertTrue(id.length <= 64)
    }

    @Test
    fun `thinking signatures replay as reasoning items`() {
        val signature = """{"type":"reasoning","id":"rs_1","encrypted_content":"x"}"""
        val assistant = AssistantMessage(
            content = listOf(ThinkingContent("", signature)),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.STOP,
        )
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(assistant)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        assertEquals(json.parseToJsonElement(signature), input.single())
    }

    @Test
    fun `tool calls replay as function_call items with fc_ item ids`() {
        val assistant = AssistantMessage(
            content = listOf(ToolCall(id = "call_1|fc_2", name = "edit", arguments = """{"a":1}""")),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.TOOL_USE,
        )
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(assistant)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        // transformMessages appends a synthetic result for the orphaned call.
        val item = input.first()
        assertEquals("function_call", item["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", item["call_id"]!!.jsonPrimitive.content)
        assertEquals("fc_2", item["id"]!!.jsonPrimitive.content)
        assertEquals("""{"a":1}""", item["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non-fc item ids are dropped to avoid pairing validation`() {
        val assistant = AssistantMessage(
            content = listOf(ToolCall(id = "call_1|ctc_2", name = "edit", arguments = "{}")),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.TOOL_USE,
        )
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(assistant)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        assertNull(input.first()["id"])
    }

    @Test
    fun `foreign copilot tool item ids hash into bounded fc_ shape`() {
        val copilotItemId = "I9b95oN1wD/cHXKTw3PpRkL6KkCtzTJhUxMouMWYwHeTo2j3htzfSk7YPx2vifiIM4g3A8XXyOj8q4Bt6SLUG7gqY1E3ELkrkVQNHglRfUmWj84lqxJY+Puieb3VKyX0FB+83TUzn91cDM/4gzt990IzqVrc+nIb9RRscRD070Du16q1glyVjWR0SBJs6EJbY/esOoFpqplogQqrajm1eI++F3eLi73a6q7hVusY0QbeFySVxABCjhL0lXB04caBe1rzHjYzul6MAX/7+r17Moq+yrtyYhN12wkmfHeqTyEei6EFPfMy247cJmJlkfAOCg02WgOOn+BFcbi2ctJFSJhSjt1kSCBqCnnhw3xXjbWiT0wh3DmLScRgTHmGkaMkU+oAcQQJfic65nxvTnEk9e=="
        val rawId = "call_4VnzVawQXPB9MgYib7CiQFEY|$copilotItemId"
        val assistant = AssistantMessage(
            content = listOf(ToolCall(id = rawId, name = "edit", arguments = """{"path":"a.css"}""")),
            api = "openai-responses",
            provider = "github-copilot",
            model = "gpt-5.5",
            stopReason = StopReason.TOOL_USE,
        )
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(provider = "openai-codex", id = "gpt-5.5", api = "openai-codex-responses"),
            Context(messages = listOf(assistant)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        val item = input.first()
        val expectedItemId = "fc_${shortHash(copilotItemId)}"
        assertEquals(expectedItemId, item["id"]!!.jsonPrimitive.content)
        assertTrue(item["id"]!!.jsonPrimitive.content.length <= 64)
        assertTrue(Regex("^fc_[A-Za-z0-9]+$").matches(item["id"]!!.jsonPrimitive.content))
        assertEquals("fc_f5wk3t1myarzx", item["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool results convert to function_call_output with the call id`() {
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(
                messages = listOf(
                    ToolResultMessage(
                        toolCallId = "call_1|fc_2",
                        toolName = "edit",
                        content = listOf(TextContent("line 1"), TextContent("line 2")),
                    ),
                ),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        val item = input.single()
        assertEquals("function_call_output", item["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", item["call_id"]!!.jsonPrimitive.content)
        assertEquals("line 1\nline 2", item["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty tool results produce a placeholder`() {
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(
                messages = listOf(
                    ToolResultMessage(toolCallId = "c", toolName = "t", content = emptyList()),
                ),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        assertEquals("(no tool output)", input.single()["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool result images inline as data urls for vision models`() {
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(input = listOf(InputModality.TEXT, InputModality.IMAGE)),
            Context(
                messages = listOf(
                    ToolResultMessage(
                        toolCallId = "c",
                        toolName = "t",
                        content = listOf(TextContent("see"), ImageContent("AAAA", "image/jpeg")),
                    ),
                ),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        val output = input.single()["output"]!!.jsonArray
        assertEquals("input_text", output[0]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", output[1]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool result images become a placeholder for non-vision models`() {
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(
                messages = listOf(
                    ToolResultMessage(
                        toolCallId = "c",
                        toolName = "t",
                        content = listOf(ImageContent("AAAA", "image/jpeg")),
                    ),
                ),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        assertEquals("(tool image omitted: model does not support images)", input.single()["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `addedToolNames emit additional_tools items in additional-tools mode`() {
        val tool = Tool("deferred_tool", "A tool", buildJsonObject { put("type", "object") })
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(compat = OpenAiResponsesCompat(supportsAdditionalTools = true)),
            Context(
                messages = listOf(
                    ToolResultMessage(
                        toolCallId = "c",
                        toolName = "other",
                        content = listOf(TextContent("ok")),
                        addedToolNames = listOf("deferred_tool"),
                    ),
                ),
                tools = listOf(tool),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
            OpenAiResponsesShared.ConvertResponsesMessagesOptions(
                deferredTools = mapOf("deferred_tool" to tool),
                deferredToolsMode = OpenAiResponsesShared.DeferredToolsMode.ADDITIONAL_TOOLS,
            ),
        )
        val additional = input[1]
        assertEquals("additional_tools", additional["type"]!!.jsonPrimitive.content)
        assertEquals("developer", additional["role"]!!.jsonPrimitive.content)
        assertEquals(
            "deferred_tool",
            additional["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `addedToolNames emit tool_search items in tool-search mode`() {
        val tool = Tool("deferred_tool", "A tool", buildJsonObject { put("type", "object") })
        val toolCallId = "call_9"
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(compat = OpenAiResponsesCompat(supportsToolSearch = true)),
            Context(
                messages = listOf(
                    ToolResultMessage(
                        toolCallId = toolCallId,
                        toolName = "other",
                        content = listOf(TextContent("ok")),
                        addedToolNames = listOf("deferred_tool"),
                    ),
                ),
                tools = listOf(tool),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
            OpenAiResponsesShared.ConvertResponsesMessagesOptions(
                deferredTools = mapOf("deferred_tool" to tool),
                deferredToolsMode = OpenAiResponsesShared.DeferredToolsMode.TOOL_SEARCH,
            ),
        )
        val expectedCallId =
            "pi_tool_load_${shortHash("$toolCallId:deferred_tool")}"
        val call = input[1]
        val output = input[2]
        assertEquals("tool_search_call", call["type"]!!.jsonPrimitive.content)
        assertEquals(expectedCallId, call["call_id"]!!.jsonPrimitive.content)
        assertEquals("tool_search_output", output["type"]!!.jsonPrimitive.content)
        assertEquals(expectedCallId, output["call_id"]!!.jsonPrimitive.content)
        assertEquals(
            "deferred_tool",
            output["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals(
            true,
            output["tools"]!!.jsonArray.single().jsonObject["defer_loading"]!!.jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun `orphaned tool calls get synthetic tool results`() {
        val assistant = AssistantMessage(
            content = listOf(ToolCall(id = "call_1|fc_2", name = "edit", arguments = "{}")),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.TOOL_USE,
        )
        val transformed = transformMessages(
            listOf(assistant, UserMessage.ofText("next")),
            model(),
        )
        val synthetic = transformed[1] as ToolResultMessage
        assertEquals("call_1|fc_2", synthetic.toolCallId)
        assertEquals("edit", synthetic.toolName)
        assertTrue(synthetic.isError)
        assertEquals("No result provided", (synthetic.content.single() as TextContent).text)
    }

    @Test
    fun `cross-model replay drops redacted thinking and tool thought signatures`() {
        val assistant = AssistantMessage(
            content = listOf(
                ThinkingContent("opaque", redacted = true),
                ToolCall("foreign", "edit", "{}", thoughtSignature = "google-signature"),
            ),
            api = "google-generative-ai",
            provider = "google",
            model = "gemini",
            stopReason = StopReason.TOOL_USE,
        )
        val transformed = transformMessages(
            listOf(assistant),
            model(),
        ) { _, _ -> "normalized" }
        val content = (transformed.first() as AssistantMessage).content
        assertEquals(1, content.size)
        val call = content.single() as ToolCall
        assertEquals("normalized", call.id)
        assertNull(call.thoughtSignature)
    }

    @Test
    fun `errored assistant turns are skipped entirely`() {
        val errored = AssistantMessage(
            content = listOf(TextContent("partial")),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.ERROR,
            errorMessage = "boom",
        )
        val transformed = transformMessages(
            listOf(UserMessage.ofText("q"), errored, UserMessage.ofText("next")),
            model(),
        )
        assertEquals(2, transformed.size)
    }

    @Test
    fun `tools convert to function tools with strict only when supported`() {
        val tool = Tool("t", "desc", buildJsonObject { put("type", "object") })
        val strictOff = OpenAiResponsesShared.convertResponsesTools(
            listOf(tool),
            OpenAiResponsesShared.ConvertResponsesToolsOptions(supportsStrictMode = false),
        ).single()
        assertNull(strictOff["strict"])

        val strictOn = OpenAiResponsesShared.convertResponsesTools(
            listOf(tool),
            OpenAiResponsesShared.ConvertResponsesToolsOptions(
                strict = true,
                supportsStrictMode = true,
            ),
        ).single()
        assertEquals("function", strictOn["type"]!!.jsonPrimitive.content)
        assertEquals(true, strictOn["strict"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("t", strictOn["name"]!!.jsonPrimitive.content)
    }

    private fun event(jsonText: String): JsonObject =
        json.parseToJsonElement(jsonText).jsonObject

    private fun state(
        m: Model = model(),
        options: OpenAiResponsesShared.StreamProcessingOptions =
            OpenAiResponsesShared.StreamProcessingOptions(),
    ) = OpenAiResponsesShared.ResponsesStreamState(m, 1_770_000_000_000L, options)

    @Test
    fun `completed terminal event finalizes usage cost and stop`() {
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.completed","response":{
                    "id":"resp_completed","status":"completed",
                    "usage":{"input_tokens":20,"output_tokens":7,"total_tokens":27,
                        "input_tokens_details":{"cached_tokens":2,"cache_write_tokens":3}}}}""",
            ),
        )
        assertTrue(s.sawTerminalResponseEvent)
        assertEquals("resp_completed", s.responseId)
        assertEquals(StopReason.STOP, s.stopReason)
        assertEquals("completed", s.rawStopReason)
        assertEquals(15, s.usage.input)
        assertEquals(7, s.usage.output)
        assertEquals(2, s.usage.cacheRead)
        assertEquals(3, s.usage.cacheWrite)
        assertEquals(27, s.usage.totalTokens)
        // cost = 15*1 + 7*2 + 2*0.25 + 3*0.5 per million
        assertEquals(0.000031, s.usage.cost.total, 1e-9)
    }

    @Test
    fun `incomplete max_output_tokens maps to length`() {
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.incomplete","response":{
                    "id":"resp_i","status":"incomplete",
                    "incomplete_details":{"reason":"max_output_tokens"},
                    "usage":{"input_tokens":30,"output_tokens":12,"total_tokens":42,
                        "input_tokens_details":{"cached_tokens":5}}}}""",
            ),
        )
        assertEquals(StopReason.LENGTH, s.stopReason)
        assertEquals("incomplete.max_output_tokens", s.rawStopReason)
        assertEquals(25, s.usage.input)
        assertEquals(5, s.usage.cacheRead)
        assertEquals(42, s.usage.totalTokens)
    }

    @Test
    fun `incomplete content_filter maps to a non-retryable error`() {
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.incomplete","response":{
                    "status":"incomplete","incomplete_details":{"reason":"content_filter"}}}""",
            ),
        )
        assertEquals(StopReason.ERROR, s.stopReason)
        assertEquals("incomplete.content_filter", s.rawStopReason)
        assertEquals("Response incomplete: content_filter", s.errorMessage)
    }

    @Test
    fun `failed terminal event throws the provider error`() {
        val s = state()
        val error = assertFailsWith<ProviderStreamException> {
            s.onEvent(
                event(
                    """{"type":"response.failed","response":{
                        "status":"failed","error":{"code":"server_error","message":"boom"}}}""",
                ),
            )
        }
        assertEquals("server_error: boom", error.message)
        assertEquals("failed", s.rawStopReason)
        assertTrue(s.sawTerminalResponseEvent)
    }

    @Test
    fun `stream without a terminal event fails the terminal assertion`() {
        val s = state()
        s.onEvent(event("""{"type":"response.created","response":{"id":"r"}}"""))
        val error = assertFailsWith<ProviderStreamException> { s.assertTerminalEvent() }
        assertEquals("OpenAI Responses stream ended before a terminal response event", error.message)
    }

    @Test
    fun `provider error events throw with code and message`() {
        val s = state()
        val error = assertFailsWith<ProviderStreamException> {
            s.onEvent(event("""{"type":"error","code":"500","message":"bad"}"""))
        }
        assertEquals("Error Code 500: bad", error.message)
    }

    @Test
    fun `message phases track final_answer stop overrides`() {
        val s = state()
        val events = s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"message","id":"msg_1","role":"assistant","phase":"commentary"}}""",
            ),
        )
        assertIs<AssistantMessageEvent.TextStart>(events.single())
        assertEquals(StopReason.PENDING, s.stopReason)
        s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                        "phase":"final_answer",
                        "content":[{"type":"output_text","text":"answer","annotations":[]}]}}""",
            ),
        )
        assertEquals(StopReason.STOP, s.stopReason)
        // A later incomplete terminal reason replaces the provisional stop.
        s.onEvent(
            event(
                """{"type":"response.incomplete","response":{"status":"incomplete",
                    "incomplete_details":{"reason":"max_output_tokens"}}}""",
            ),
        )
        assertEquals(StopReason.LENGTH, s.stopReason)
    }

    @Test
    fun `text streaming emits start deltas end and signature`() {
        val s = state()
        val start = s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
            ),
        )
        assertIs<AssistantMessageEvent.TextStart>(start.single())
        val delta = s.onEvent(
            event("""{"type":"response.output_text.delta","output_index":0,"delta":"he"}"""),
        )
        assertIs<AssistantMessageEvent.TextDelta>(delta.single())
        assertEquals("he", (delta.single() as AssistantMessageEvent.TextDelta).partial.content.single().let { (it as TextContent).text })
        val delta2 = s.onEvent(
            event("""{"type":"response.output_text.delta","output_index":0,"delta":"llo"}"""),
        )
        assertIs<AssistantMessageEvent.TextDelta>(delta2.single())
        val end = s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                        "content":[{"type":"output_text","text":"hello!","annotations":[]}]}}""",
            ),
        )
        val textEnd = assertIs<AssistantMessageEvent.TextEnd>(end.single())
        assertEquals("hello!", textEnd.content)
        assertEquals("hello!", (textEnd.partial.content.single() as TextContent).text)
        assertEquals(
            """{"v":1,"id":"msg_1"}""",
            (textEnd.partial.content.single() as TextContent).textSignature,
        )
        s.onEvent(event("""{"type":"response.completed","response":{"id":"r","status":"completed"}}"""))
        assertEquals(StopReason.STOP, s.stopReason)
    }

    @Test
    fun `reasoning streaming accumulates and records the item signature`() {
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
            ),
        )
        s.onEvent(event("""{"type":"response.reasoning_text.delta","output_index":0,"delta":"think"}"""))
        s.onEvent(event("""{"type":"response.reasoning_summary_part.done","output_index":0}"""))
        val end = s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"reasoning","id":"rs_1",
                        "summary":[{"type":"summary_text","text":"sum"}]}}""",
            ),
        )
        val thinkingEnd = assertIs<AssistantMessageEvent.ThinkingEnd>(end.single())
        assertEquals("sum", thinkingEnd.content)
        val thinking = thinkingEnd.partial.content.single() as ThinkingContent
        assertEquals("sum", thinking.thinking)
        val signature = json.parseToJsonElement(thinking.thinkingSignature!!).jsonObject
        assertEquals("rs_1", signature["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `azure backfills encrypted_content from the terminal response`() {
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
            ),
        )
        s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
            ),
        )
        s.onEvent(
            event(
                """{"type":"response.completed","response":{"status":"completed",
                    "output":[{"type":"reasoning","id":"rs_1","encrypted_content":"ENC"}]}}""",
            ),
        )
        val snapshot = s.partialSnapshot()
        val thinking = snapshot.content.single() as ThinkingContent
        val signature = json.parseToJsonElement(thinking.thinkingSignature!!).jsonObject
        assertEquals("ENC", signature["encrypted_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `function calls stream arguments and finalize with done arguments`() {
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"function_call","id":"fc_1","call_id":"call_1",
                        "name":"edit","arguments":""}}""",
            ),
        )
        s.onEvent(
            event("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"p\""}"""),
        )
        s.onEvent(
            event("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":":1}"}"""),
        )
        val end = s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"function_call","id":"fc_1","call_id":"call_1",
                        "name":"edit","arguments":"{\"p\":1,\"q\":2}"}}""",
            ),
        )
        val toolEnd = assertIs<AssistantMessageEvent.ToolCallEnd>(end.single())
        assertEquals("call_1|fc_1", toolEnd.toolCall.id)
        assertEquals("""{"p":1,"q":2}""", toolEnd.toolCall.arguments)
        s.onEvent(event("""{"type":"response.completed","response":{"status":"completed"}}"""))
        // Tool calls present: stop maps to toolUse.
        assertEquals(StopReason.TOOL_USE, s.stopReason)
    }

    @Test
    fun `function namespace received only on output_item_done replays`() {
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"function_call","id":"fc_test","call_id":"call_test",
                        "name":"lookup","arguments":""}}""",
            ),
        )
        s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"function_call","id":"fc_test","call_id":"call_test",
                        "name":"lookup","arguments":"{\"value\":\"hello\"}",
                        "namespace":"dynamic_tools"}}""",
            ),
        )
        s.onEvent(event("""{"type":"response.completed","response":{"id":"resp_test","status":"completed"}}"""))
        val output = s.partialSnapshot()
        val toolCall = assertIs<ToolCall>(output.content.single())
        assertEquals("call_test|fc_test", toolCall.id)
        assertEquals("lookup", toolCall.name)
        assertEquals("""{"value":"hello"}""", toolCall.arguments)
        assertEquals("dynamic_tools", toolCall.namespace)

        val replayed = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(output)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        ).single { it["type"]!!.jsonPrimitive.content == "function_call" }
        assertEquals("fc_test", replayed["id"]!!.jsonPrimitive.content)
        assertEquals("call_test", replayed["call_id"]!!.jsonPrimitive.content)
        assertEquals("dynamic_tools", replayed["namespace"]!!.jsonPrimitive.content)

        // A different target model drops the namespace and fc_ id to avoid
        // pairing validation.
        val replayedOther = OpenAiResponsesShared.convertResponsesMessages(
            model(id = "gpt-5.2"),
            Context(messages = listOf(output)),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        ).single { it["type"]!!.jsonPrimitive.content == "function_call" }
        assertNull(replayedOther["namespace"])
        assertNull(replayedOther["id"])
    }

    @Test
    fun `text signature omits a null id like json stringify drops undefined`() {
        assertEquals("""{"v":1}""", OpenAiResponsesShared.encodeTextSignatureV1(null, null))
        assertEquals(
            """{"v":1,"id":"msg_1","phase":"final_answer"}""",
            OpenAiResponsesShared.encodeTextSignatureV1("msg_1", "final_answer"),
        )
    }

    @Test
    fun `refusal deltas accumulate as text`() {
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"message","id":"m","role":"assistant"}}""",
            ),
        )
        s.onEvent(event("""{"type":"response.refusal.delta","output_index":0,"delta":"no"}"""))
        val snapshot = s.partialSnapshot()
        assertEquals("no", (snapshot.content.single() as TextContent).text)
    }

    @Test
    fun `service tier pricing scales cost`() {
        val flex = applyServiceTierPricing(
            Usage(cost = works.resolve.pathfinder.ai.Cost(input = 10.0, output = 20.0)),
            "flex",
            "gpt-5",
        )
        assertEquals(5.0, flex.cost.input, 1e-9)
        assertEquals(10.0, flex.cost.output, 1e-9)
        assertEquals(15.0, flex.cost.total, 1e-9)

        val priority = applyServiceTierPricing(
            Usage(cost = works.resolve.pathfinder.ai.Cost(input = 10.0, output = 20.0)),
            "priority",
            "gpt-5",
        )
        assertEquals(20.0, priority.cost.input, 1e-9)

        val priorityGpt55 = applyServiceTierPricing(
            Usage(cost = works.resolve.pathfinder.ai.Cost(input = 10.0)),
            "priority",
            "gpt-5.5",
        )
        assertEquals(25.0, priorityGpt55.cost.input, 1e-9)
    }

    @Test
    fun `unknown response statuses throw`() {
        assertFailsWith<ProviderStreamException> {
            OpenAiResponsesShared.mapStopReason("bogus", null)
        }
        assertEquals(StopReason.STOP to null, OpenAiResponsesShared.mapStopReason("queued", null))
        assertEquals(StopReason.ERROR to null, OpenAiResponsesShared.mapStopReason("cancelled", null))
    }

    @Test
    fun `session affinity format detection and defaults`() {
        assertEquals(
            SessionAffinityFormat.OPENROUTER,
            getCompat(model(provider = "openrouter")).sessionAffinityFormat,
        )
        assertEquals(
            SessionAffinityFormat.OPENROUTER,
            getCompat(
                model().copy(baseUrl = "https://openrouter.ai/api/v1"),
            ).sessionAffinityFormat,
        )
        assertEquals(
            SessionAffinityFormat.OPENAI,
            getCompat(model()).sessionAffinityFormat,
        )
        val explicit = getCompat(
            model(compat = OpenAiResponsesCompat(sessionAffinityFormat = SessionAffinityFormat.OPENROUTER)),
        )
        assertEquals(SessionAffinityFormat.OPENROUTER, explicit.sessionAffinityFormat)
    }

    @Test
    fun `cache retention resolves explicit then env then short`() {
        assertEquals(
            CacheRetention.LONG,
            OpenAiResponsesApi.resolveCacheRetention(CacheRetention.LONG, emptyMap()),
        )
        assertEquals(
            CacheRetention.LONG,
            OpenAiResponsesApi.resolveCacheRetention(null, mapOf("PI_CACHE_RETENTION" to "long")),
        )
        assertEquals(
            CacheRetention.SHORT,
            OpenAiResponsesApi.resolveCacheRetention(null, mapOf("PI_CACHE_RETENTION" to "short")),
        )
        assertEquals(
            "24h",
            getPromptCacheRetention(
                getCompat(model()),
                CacheRetention.LONG,
            ),
        )
        assertNull(
            getPromptCacheRetention(
                getCompat(
                    model(compat = OpenAiResponsesCompat(supportsLongCacheRetention = false)),
                ),
                CacheRetention.LONG,
            ),
        )
    }

    @Test
    fun `client api key falls back to header auth`() {
        assertEquals("k", getClientApiKey("p", "k", emptyMap()))
        assertEquals(
            "unused",
            getClientApiKey("p", null, mapOf("authorization" to "Bearer x")),
        )
        assertFailsWith<ProviderAuthException> {
            getClientApiKey("p", null, emptyMap())
        }
    }

    @Test
    fun `affinity headers follow the compat format`() {
        val openai = sessionAffinityHeaders(
            "s1",
            getCompat(model()),
        )
        assertEquals(mapOf("session_id" to "s1", "x-client-request-id" to "s1"), openai)
        val openrouter = sessionAffinityHeaders(
            "s1",
            getCompat(model(provider = "openrouter")),
        )
        assertEquals(mapOf("x-session-id" to "s1"), openrouter)
        assertTrue(sessionAffinityHeaders(null, getCompat(model())).isEmpty())
    }

    private fun sampleTool(
        constrainedSampling: works.resolve.pathfinder.ai.ConstrainedSamplingConfig? = null,
    ): Tool = Tool(
        name = "sample_tool",
        description = "Sample tool",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("payload", buildJsonObject { put("type", "string") })
                },
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("payload"))))
            put("additionalProperties", false)
        },
        constrainedSampling = constrainedSampling,
    )

    @Test
    fun `grammar tools convert to custom tools with the grammar format object`() {
        val lark = OpenAiResponsesShared.convertResponsesTools(
            listOf(
                sampleTool(
                    ConstrainedSamplingConfig.Grammar(
                        mapOf(GrammarFormat.OPENAI_LARK to "start: /[a-z]+/"),
                    ),
                ),
            ),
            OpenAiResponsesShared.ConvertResponsesToolsOptions(supportsOpenAIGrammarTools = true),
        ).single()
        assertEquals("custom", lark["type"]!!.jsonPrimitive.content)
        assertEquals("sample_tool", lark["name"]!!.jsonPrimitive.content)
        assertEquals("Sample tool", lark["description"]!!.jsonPrimitive.content)
        val format = lark["format"]!!.jsonObject
        assertEquals("grammar", format["type"]!!.jsonPrimitive.content)
        assertEquals("lark", format["syntax"]!!.jsonPrimitive.content)
        assertEquals("start: /[a-z]+/", format["definition"]!!.jsonPrimitive.content)

        val both = OpenAiResponsesShared.convertResponsesTools(
            listOf(
                sampleTool(
                    ConstrainedSamplingConfig.Grammar(
                        mapOf(
                            GrammarFormat.OPENAI_LARK to "start: /[a-z]+/",
                            GrammarFormat.OPENAI_REGEX to "[a-z]+",
                        ),
                    ),
                ),
            ),
            OpenAiResponsesShared.ConvertResponsesToolsOptions(supportsOpenAIGrammarTools = true),
        ).single()
        assertEquals("lark", both["format"]!!.jsonObject["syntax"]!!.jsonPrimitive.content)

        val regex = OpenAiResponsesShared.convertResponsesTools(
            listOf(
                sampleTool(
                    ConstrainedSamplingConfig.Grammar(mapOf(GrammarFormat.OPENAI_REGEX to "[a-z]+")),
                ),
            ),
            OpenAiResponsesShared.ConvertResponsesToolsOptions(supportsOpenAIGrammarTools = true),
        ).single()
        assertEquals("regex", regex["format"]!!.jsonObject["syntax"]!!.jsonPrimitive.content)
        assertEquals("[a-z]+", regex["format"]!!.jsonObject["definition"]!!.jsonPrimitive.content)
    }

    @Test
    fun `grammar tools fall back to function tools when unsupported`() {
        val fallback = OpenAiResponsesShared.convertResponsesTools(
            listOf(
                sampleTool(
                    ConstrainedSamplingConfig.Grammar(
                        mapOf(GrammarFormat.OPENAI_LARK to "start: /[a-z]+/"),
                    ),
                ),
            ),
            OpenAiResponsesShared.ConvertResponsesToolsOptions(
                supportsOpenAIGrammarTools = false,
                supportsStrictMode = false,
            ),
        ).single()
        assertEquals("function", fallback["type"]!!.jsonPrimitive.content)
        assertEquals("sample_tool", fallback["name"]!!.jsonPrimitive.content)
        assertNull(fallback["strict"])
    }

    @Test
    fun `grammar tools without a supported variant are rejected`() {
        val failure = assertFailsWith<ConstrainedSamplingError> {
            OpenAiResponsesShared.convertResponsesTools(
                listOf(sampleTool(ConstrainedSamplingConfig.Grammar(emptyMap()))),
                OpenAiResponsesShared.ConvertResponsesToolsOptions(supportsOpenAIGrammarTools = true),
            )
        }
        assertEquals(
            "Tool \"sample_tool\" cannot use grammar constrained sampling: " +
                "no supported grammar variant was provided.",
            failure.message,
        )
    }

    @Test
    fun `json schema constrained tools emit strict rewritten parameters`() {
        val converted = OpenAiResponsesShared.convertResponsesTools(
            listOf(sampleTool(ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.PREFER))),
        ).single()
        assertEquals("function", converted["type"]!!.jsonPrimitive.content)
        assertEquals(true, converted["strict"]!!.jsonPrimitive.content.toBoolean())
        val parameters = converted["parameters"]!!.jsonObject
        assertEquals(false, parameters["additionalProperties"]!!.jsonPrimitive.content.toBoolean())

        val original = sampleTool(ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.PREFER))
        val strict = makeStrictJsonSchema(original.parameters)
        assertEquals(strict, converted["parameters"])
    }

    @Test
    fun `strict require rejects when strict mode is unsupported`() {
        val failure = assertFailsWith<ConstrainedSamplingError> {
            OpenAiResponsesShared.convertResponsesTools(
                listOf(sampleTool(ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.REQUIRE))),
                OpenAiResponsesShared.ConvertResponsesToolsOptions(supportsStrictMode = false),
            )
        }
        assertEquals(
            "Tool \"sample_tool\" requires JSON-schema constrained sampling, " +
                "but strict tools are unsupported.",
            failure.message,
        )
    }

    @Test
    fun `strict prefer falls back when the schema cannot be converted`() {
        val tool = Tool(
            name = "sample_tool",
            description = "Sample tool",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("child", buildJsonObject { put("\$ref", "https://example.com/child.json") })
                    },
                )
                put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("child"))))
            },
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.PREFER),
        )
        val converted = OpenAiResponsesShared.convertResponsesTools(
            listOf(tool),
            OpenAiResponsesShared.ConvertResponsesToolsOptions(supportsStrictMode = true),
        ).single()
        assertEquals(false, converted["strict"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(tool.parameters, converted["parameters"])

        val strictFailure = assertFailsWith<UnsupportedStrictJsonSchemaError> {
            makeStrictJsonSchema(tool.parameters)
        }
        assertEquals("\$ref schemas are unsupported", strictFailure.message)

        val requiring = tool.copy(
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.REQUIRE),
        )
        val resolveFailure = assertFailsWith<ConstrainedSamplingError> { resolveJsonSchemaStrictSampling(requiring, true) }
        assertContains(resolveFailure.message!!, "\$ref schemas are unsupported")
    }

    @Test
    fun `replays grammar calls as custom tool call items`() {
        fun grammarContext(arguments: String): Context {
            val assistant = AssistantMessage(
                content = listOf(ToolCall(id = "call_1|ctc_1", name = "sample_tool", arguments = arguments)),
                api = "openai-responses",
                provider = "openai",
                model = "gpt-test",
                stopReason = StopReason.TOOL_USE,
            )
            val result = ToolResultMessage(
                toolCallId = "call_1|ctc_1",
                toolName = "sample_tool",
                content = listOf(TextContent("done")),
            )
            return Context(messages = listOf(assistant, result))
        }
        val options = OpenAiResponsesShared.ConvertResponsesMessagesOptions(
            grammarToolInputProperties = mapOf("sample_tool" to "payload"),
        )
        for (invalidArguments in listOf("{}", """{"payload":42}""")) {
            val failure = assertFailsWith<ConstrainedSamplingError> {
                OpenAiResponsesShared.convertResponsesMessages(
                    model(id = "gpt-test"),
                    grammarContext(invalidArguments),
                    setOf("openai"),
                    options,
                )
            }
            assertEquals(
                "Grammar tool call \"sample_tool\" requires argument \"payload\" to be a string.",
                failure.message,
            )
        }

        val messages = OpenAiResponsesShared.convertResponsesMessages(
            model(id = "gpt-test"),
            grammarContext("""{"payload":"abc"}"""),
            setOf("openai"),
            options,
        )
        val call = messages.first()
        assertEquals("custom_tool_call", call["type"]!!.jsonPrimitive.content)
        // Custom-tool ctc_* item ids survive replay (only function_call needs fc_*).
        assertEquals("ctc_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("call_1", call["call_id"]!!.jsonPrimitive.content)
        assertEquals("sample_tool", call["name"]!!.jsonPrimitive.content)
        assertEquals("abc", call["input"]!!.jsonPrimitive.content)
        val output = messages[1]
        assertEquals("custom_tool_call_output", output["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", output["call_id"]!!.jsonPrimitive.content)
        assertEquals("done", output["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streams custom tool calls as string arguments`() {
        val s = state(
            options = OpenAiResponsesShared.StreamProcessingOptions(
                grammarToolInputProperties = mapOf("sample_tool" to "payload"),
            ),
        )
        val allEvents = mutableListOf<AssistantMessageEvent>()
        fun feed(jsonText: String) {
            allEvents += s.onEvent(event(jsonText))
        }
        feed(
            """{"type":"response.output_item.added","output_index":0,
                "item":{"type":"custom_tool_call","call_id":"call_1","id":"ctc_1",
                    "name":"sample_tool","input":""}}""",
        )
        feed("""{"type":"response.custom_tool_call_input.delta","output_index":0,"delta":"ab"}""")
        feed("""{"type":"response.custom_tool_call_input.done","output_index":0,"input":"abc"}""")
        feed(
            """{"type":"response.output_item.done","output_index":0,
                "item":{"type":"custom_tool_call","call_id":"call_1","id":"ctc_1",
                    "name":"sample_tool","input":"abc"}}""",
        )
        feed(
            """{"type":"response.completed","response":{"status":"completed",
                "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}""",
        )

        assertEquals(StopReason.TOOL_USE, s.stopReason)
        val toolCall = s.partialSnapshot().content.single() as ToolCall
        assertEquals("call_1|ctc_1", toolCall.id)
        assertEquals("sample_tool", toolCall.name)
        assertEquals("{\"payload\":\"abc\"}", toolCall.arguments)
        val deltas = allEvents.filterIsInstance<AssistantMessageEvent.ToolCallDelta>().joinToString("") { it.delta }
        assertEquals("{\"payload\":\"abc\"}", deltas)
        assertIs<AssistantMessageEvent.ToolCallEnd>(allEvents.filterIsInstance<AssistantMessageEvent.ToolCallEnd>().single())
    }

    @Test
    fun `custom tool call item done alone finalizes input and namespace`() {
        val s = state(
            options = OpenAiResponsesShared.StreamProcessingOptions(
                grammarToolInputProperties = mapOf("query" to "input"),
            ),
        )
        val events = s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"custom_tool_call","id":"ctc_test","call_id":"call_test",
                        "name":"query","input":"hello","namespace":"dynamic_tools"}}""",
            ),
        )
        val end = assertIs<AssistantMessageEvent.ToolCallEnd>(events.last())
        assertEquals("call_test|ctc_test", end.toolCall.id)
        assertEquals("dynamic_tools", end.toolCall.namespace)
        assertEquals("{\"input\":\"hello\"}", end.toolCall.arguments)
    }

    @Test
    fun `unknown provider incomplete reasons surface as non-retryable errors`() {
        // pi b8b873b98 openai-responses-terminal-event: "preserves unknown
        // provider incomplete reasons as non-retryable errors".
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.incomplete","response":{
                    "id":"resp_mtl","status":"incomplete",
                    "incomplete_details":{"reason":"max_time_limit"}}}""",
            ),
        )
        assertEquals(StopReason.ERROR, s.stopReason)
        assertEquals("incomplete.max_time_limit", s.rawStopReason)
        assertEquals("Response incomplete: max_time_limit", s.errorMessage)
    }

    @Test
    fun `final_answer phase on the added event stops immediately`() {
        // pi b8b873b98 openai-responses-terminal-event phases [final_answer,
        // final_answer] -> [stop, stop]: the phase is honored as soon as the
        // message item appears.
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"message","id":"msg_1","role":"assistant","phase":"final_answer"}}""",
            ),
        )
        assertEquals(StopReason.STOP, s.stopReason)
    }

    @Test
    fun `commentary-only messages keep the pending stop reason`() {
        // pi b8b873b98 openai-responses-terminal-event phases [commentary,
        // commentary] -> [pending, pending].
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"message","id":"msg_1","role":"assistant","phase":"commentary"}}""",
            ),
        )
        assertEquals(StopReason.PENDING, s.stopReason)
        s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                        "phase":"commentary",
                        "content":[{"type":"output_text","text":"answer","annotations":[]}]}}""",
            ),
        )
        assertEquals(StopReason.PENDING, s.stopReason)
    }

    @Test
    fun `azure keeps encrypted_content from output_item_done over the terminal response`() {
        // pi b8b873b98 azure-openai-responses-reasoning-replay: "preserves
        // existing encrypted_content from output_item.done" — the terminal
        // response never overwrites a value the done event already carried.
        val s = state()
        s.onEvent(
            event(
                """{"type":"response.output_item.added","output_index":0,
                    "item":{"type":"reasoning","id":"rs_done","summary":[]}}""",
            ),
        )
        s.onEvent(
            event(
                """{"type":"response.output_item.done","output_index":0,
                    "item":{"type":"reasoning","id":"rs_done","summary":[],
                        "encrypted_content":"from-output-item-done"}}""",
            ),
        )
        s.onEvent(
            event(
                """{"type":"response.completed","response":{"status":"completed",
                    "output":[{"type":"reasoning","id":"rs_done",
                        "encrypted_content":"from-response-completed"}]}}""",
            ),
        )
        val output = s.partialSnapshot()
        val signature = json.parseToJsonElement((output.content.single() as ThinkingContent).thinkingSignature!!).jsonObject
        assertEquals("from-output-item-done", signature["encrypted_content"]!!.jsonPrimitive.content)

        // The replayed reasoning item keeps the preserved encrypted_content.
        val replayed = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(output, UserMessage.ofText("follow-up"))),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        ).single { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals("from-output-item-done", replayed["encrypted_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `custom tool namespaces replay only where the target can load them`() {
        // pi b8b873b98 openai-responses-namespace: "round-trips a custom-tool
        // namespace" + "drops namespaces when the target cannot replay their
        // load items".
        val options = OpenAiResponsesShared.ConvertResponsesMessagesOptions(
            grammarToolInputProperties = mapOf("query" to "input"),
        )
        val assistant = AssistantMessage(
            content = listOf(
                ToolCall(
                    id = "call_custom|ctc_test",
                    name = "query",
                    arguments = """{"input":"hello"}""",
                    namespace = "dynamic_tools",
                ),
            ),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.TOOL_USE,
        )
        val sameModel = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(assistant)),
            setOf("openai"),
            options,
        ).single { it["type"]!!.jsonPrimitive.content == "custom_tool_call" }
        assertEquals("dynamic_tools", sameModel["namespace"]!!.jsonPrimitive.content)
        assertEquals("ctc_test", sameModel["id"]!!.jsonPrimitive.content)
        assertEquals("hello", sameModel["input"]!!.jsonPrimitive.content)

        val differentModel = OpenAiResponsesShared.convertResponsesMessages(
            model(id = "gpt-5.2"),
            Context(messages = listOf(assistant)),
            setOf("openai"),
            options,
        ).single { it["type"]!!.jsonPrimitive.content == "custom_tool_call" }
        assertNull(differentModel["namespace"])
    }

    @Test
    fun `ordinary function calls replay without a namespace`() {
        // pi b8b873b98 openai-responses-namespace: "does not add a namespace
        // to ordinary function calls".
        val assistant = AssistantMessage(
            content = listOf(
                ToolCall(id = "call_test|fc_test", name = "lookup", arguments = """{"value":"hello"}"""),
            ),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5-mini",
            stopReason = StopReason.TOOL_USE,
        )
        val replayed = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(messages = listOf(assistant)),
            setOf("openai"),
        ).single { it["type"]!!.jsonPrimitive.content == "function_call" }
        assertNull(replayed["namespace"])
    }

    @Test
    fun `empty text tool results use the no-output placeholder`() {
        // pi b8b873b98 openai-responses-empty-tool-result: a text block with
        // an empty string is not output — the placeholder is used and never
        // mentions an attached image.
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(),
            Context(
                messages = listOf(
                    ToolResultMessage(toolCallId = "c", toolName = "t", content = listOf(TextContent(""))),
                ),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        val output = input.single()["output"]!!.jsonPrimitive.content
        assertEquals("(no tool output)", output)
    }

    @Test
    fun `tool result image parts carry the full data url`() {
        // pi b8b873b98 openai-responses-tool-result-images (e2e upstream):
        // the assertable wire shape — image data URLs inside
        // function_call_output — without the unported images stack.
        val input = OpenAiResponsesShared.convertResponsesMessages(
            model(input = listOf(InputModality.TEXT, InputModality.IMAGE)),
            Context(
                messages = listOf(
                    ToolResultMessage(
                        toolCallId = "c",
                        toolName = "t",
                        content = listOf(TextContent("see"), ImageContent("AAAA", "image/png")),
                    ),
                ),
            ),
            OpenAiResponsesShared.BASE_TOOL_CALL_PROVIDERS,
        )
        val output = input.single()["output"]!!.jsonArray
        assertEquals(
            "data:image/png;base64,AAAA",
            output[1]!!.jsonObject["image_url"]!!.jsonPrimitive.content,
        )
    }
}
