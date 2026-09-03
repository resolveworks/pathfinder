package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GoogleSharedConvertMessagesTest {

    private fun model(
        id: String = "gemini-3-pro-preview",
        input: List<InputModality> = listOf(InputModality.TEXT),
    ) = Model(
        id = id, name = id, api = "google-generative-ai", provider = "google",
        baseUrl = "https://example.com", reasoning = true, input = input,
        contextWindow = 128000, maxTokens = 8192,
    )

    private fun contextFor(model: Model, content: List<works.resolve.pathfinder.ai.Content>) =
        Context(
            messages = listOf(
                UserMessage.ofText("Hi"),
                AssistantMessage(
                    content = content,
                    api = model.api, provider = model.provider, model = model.id,
                    stopReason = StopReason.TOOL_USE,
                ),
            ),
        )

    private fun contents(model: Model, context: Context): List<JsonObject> =
        GoogleShared.convertMessages(model, context).map { it.jsonObject }

    private fun partsOf(turn: JsonObject) = turn["parts"]!!.jsonArray.map { it.jsonObject }

    private val validSig = "AAAAAAAAAAAAAAAAAAAAAA=="

    @Test
    fun `keeps a signed empty thinking block so its signature is echoed back`() {
        val model = model()
        val turns = contents(
            model,
            contextFor(
                model,
                listOf(
                    ThinkingContent("", validSig),
                    ToolCall("call_1", "bash", """{"command":"ls"}"""),
                ),
            ),
        )
        val modelTurn = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
        val signed = partsOf(modelTurn).filter { it["thoughtSignature"]?.jsonPrimitive?.content == validSig }
        assertEquals(1, signed.size)
        assertEquals("true", signed[0]["thought"]!!.jsonPrimitive.content)
    }

    @Test
    fun `keeps a signed empty text block the same way`() {
        val model = model()
        val turns = contents(
            model,
            contextFor(
                model,
                listOf(
                    TextContent("", validSig),
                    ToolCall("call_1", "bash", """{"command":"ls"}"""),
                ),
            ),
        )
        val modelTurn = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
        assertEquals(1, partsOf(modelTurn).filter { it["thoughtSignature"]?.jsonPrimitive?.content == validSig }.size)
    }

    @Test
    fun `still drops unsigned empty blocks`() {
        val model = model()
        val turns = contents(
            model,
            contextFor(
                model,
                listOf(
                    ThinkingContent(""),
                    TextContent("   "),
                    ToolCall("call_1", "bash", """{"command":"ls"}"""),
                ),
            ),
        )
        val modelTurn = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
        val parts = partsOf(modelTurn)
        assertEquals(1, parts.size)
        assertTrue(parts[0].containsKey("functionCall"))
    }

    @Test
    fun `still drops signed empty blocks from a different model`() {
        val model = model()
        val turns = contents(
            model,
            contextFor(
                model.copy(id = "other-model"),
                listOf(
                    ThinkingContent("", validSig),
                    TextContent("", validSig),
                    ToolCall("call_1", "bash", """{"command":"ls"}"""),
                ),
            ),
        )
        val modelTurn = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
        val parts = partsOf(modelTurn)
        assertEquals(1, parts.size)
        assertTrue(parts[0].containsKey("functionCall"))
        // The signature is unusable for a different model and must not leak.
        assertTrue(validSig !in modelTurn.toString())
    }

    @Test
    fun `invalid base64 signatures are dropped even for the same model`() {
        val model = model()
        val turns = contents(
            model,
            contextFor(
                model,
                listOf(TextContent("text", "not!valid!base64!!!")),
            ),
        )
        val modelTurn = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
        assertNull(partsOf(modelTurn).single()["thoughtSignature"])
    }

    @Test
    fun `cross-provider thinking converts to plain text without tags`() {
        val model = model()
        val foreign = AssistantMessage(
            content = listOf(ThinkingContent("foreign reasoning")),
            api = "openai-completions", provider = "zai", model = "glm-4.7",
            stopReason = StopReason.STOP,
        )
        val turns = contents(model, Context(messages = listOf(UserMessage.ofText("Hi"), foreign)))
        val modelTurn = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
        val part = partsOf(modelTurn).single()
        assertEquals("foreign reasoning", part["text"]!!.jsonPrimitive.content)
        assertNull(part["thought"])
    }

    private fun imageToolContext() = Context(
        messages = listOf(
            UserMessage.ofText("read the files"),
            AssistantMessage(
                content = listOf(
                    ToolCall("call_a", "read", """{"path":"a.txt"}"""),
                    ToolCall("call_img", "read", """{"path":"image.png"}"""),
                    ToolCall("call_b", "read", """{"path":"b.txt"}"""),
                ),
                api = "google-generative-ai", provider = "google", model = "x",
                stopReason = StopReason.TOOL_USE,
            ),
            ToolResultMessage("call_a", "read", listOf(TextContent("alpha text"))),
            ToolResultMessage("call_img", "read", listOf(ImageContent("abc", "image/png"))),
            ToolResultMessage("call_b", "read", listOf(TextContent("beta text"))),
        ),
    )

    @Test
    fun `keeps separate synthetic image turn for Gemini 2 dot x models`() {
        val model = model(id = "gemini-2.5-flash", input = listOf(InputModality.TEXT, InputModality.IMAGE))
        val turns = contents(model, imageToolContext())

        assertEquals(5, turns.size)
        assertTrue(partsOf(turns[2]).all { it.containsKey("functionResponse") })
        assertEquals("Tool result image:", partsOf(turns[3])[0]["text"]!!.jsonPrimitive.content)
        assertTrue(partsOf(turns[3])[1].containsKey("inlineData"))
        assertTrue(partsOf(turns[4])[0].containsKey("functionResponse"))
    }

    @Test
    fun `nests image tool results for Gemini 3 models`() {
        val model = model(id = "gemini-3-pro-preview", input = listOf(InputModality.TEXT, InputModality.IMAGE))
        val turns = contents(model, imageToolContext())

        assertEquals(3, turns.size)
        val toolResultTurn = turns[2]
        assertEquals(3, partsOf(toolResultTurn).size)
        val imageResponse = partsOf(toolResultTurn)[1]["functionResponse"]!!.jsonObject
        assertEquals(1, imageResponse["parts"]!!.jsonArray.size)
        assertTrue(imageResponse["parts"]!!.jsonArray[0].jsonObject.containsKey("inlineData"))
    }

    @Test
    fun `success and error tool results use output and error keys`() {
        val model = model(id = "gemini-3-pro-preview")
        val turns = contents(
            model,
            Context(
                messages = listOf(
                    UserMessage.ofText("go"),
                    AssistantMessage(
                        content = listOf(ToolCall("c1", "ok", "{}"), ToolCall("c2", "bad", "{}")),
                        api = model.api, provider = model.provider, model = model.id,
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage("c1", "ok", listOf(TextContent("fine"))),
                    ToolResultMessage("c2", "bad", listOf(TextContent("boom")), isError = true),
                ),
            ),
        )
        val userTurn = turns.last { it["role"]!!.jsonPrimitive.content == "user" }
        val responses = partsOf(userTurn).map { it["functionResponse"]!!.jsonObject }
        assertEquals("fine", responses[0]["response"]!!.jsonObject["output"]!!.jsonPrimitive.content)
        assertEquals("boom", responses[1]["response"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `consecutive tool results merge into a single user turn`() {
        val model = model()
        val turns = contents(
            model,
            Context(
                messages = listOf(
                    UserMessage.ofText("go"),
                    AssistantMessage(
                        content = listOf(ToolCall("c1", "t", "{}"), ToolCall("c2", "t", "{}")),
                        api = model.api, provider = model.provider, model = model.id,
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage("c1", "t", listOf(TextContent("one"))),
                    ToolResultMessage("c2", "t", listOf(TextContent("two"))),
                ),
            ),
        )
        assertEquals(3, turns.size)
        assertEquals(2, partsOf(turns[2]).size)
    }

    @Test
    fun `gemini3 requires explicit tool call ids, sanitized to the wire charset`() {
        val model = model(id = "gemini-3-flash-preview")
        val weird = "call|with|symbols|and-a-very-long-id-that-exceeds-sixty-four-characters-1234567890"
        val turns = contents(
            model,
            Context(
                messages = listOf(
                    UserMessage.ofText("go"),
                    // Tool call IDs are normalized only when replaying across
                    // models, hence the foreign assistant message.
                    AssistantMessage(
                        content = listOf(ToolCall(weird, "t", "{}")),
                        api = "openai-completions", provider = "openai", model = "gpt-x",
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage(weird, "t", listOf(TextContent("ok"))),
                ),
            ),
        )
        val callPart = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
            .let { partsOf(it).single() }["functionCall"]!!.jsonObject
        assertTrue(callPart.containsKey("id"))
        val expected = weird.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        assertEquals(expected, callPart["id"]!!.jsonPrimitive.content)
        val responsePart = turns.last().let { partsOf(it).single() }["functionResponse"]!!.jsonObject
        assertEquals(expected, responsePart["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini2 omits explicit tool call ids`() {
        val model = model(id = "gemini-2.5-flash")
        val turns = contents(
            model,
            Context(
                messages = listOf(
                    UserMessage.ofText("go"),
                    AssistantMessage(
                        content = listOf(ToolCall("call_1", "t", "{}")),
                        api = model.api, provider = model.provider, model = model.id,
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ToolResultMessage("call_1", "t", listOf(TextContent("ok"))),
                ),
            ),
        )
        val callPart = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
            .let { partsOf(it).single() }["functionCall"]!!.jsonObject
        assertNull(callPart["id"])
        assertNull(partsOf(turns.last()).single()["functionResponse"]!!.jsonObject["id"])
    }

    @Test
    fun `preserves same-model tool call ids in function calls and responses`() {
        for (modelId in listOf("gemini-3-pro-preview", "gemini-3.6-flash")) {
            val model = model(id = modelId)
            val turns = contents(
                model,
                Context(
                    messages = listOf(
                        UserMessage.ofText("Hi"),
                        AssistantMessage(
                            content = listOf(
                                ToolCall("call_1", "bash", """{"command":"echo hi"}"""),
                                ToolCall("call_2", "bash", """{"command":"ls -la"}"""),
                            ),
                            api = model.api, provider = model.provider, model = model.id,
                            stopReason = StopReason.TOOL_USE,
                        ),
                        ToolResultMessage("call_1", "bash", listOf(TextContent("hi"))),
                        ToolResultMessage("call_2", "bash", listOf(TextContent("files"))),
                    ),
                ),
            )
            val functionCallIds = turns.flatMap { partsOf(it) }
                .mapNotNull { it["functionCall"]?.jsonObject?.get("id")?.jsonPrimitive?.content }
            val functionResponseIds = turns.flatMap { partsOf(it) }
                .mapNotNull { it["functionResponse"]?.jsonObject?.get("id")?.jsonPrimitive?.content }
            assertEquals(listOf("call_1", "call_2"), functionCallIds)
            assertEquals(listOf("call_1", "call_2"), functionResponseIds)
        }
    }

    @Test
    fun `unsigned gemini3 tool calls get no thought signature and no validator skip`() {
        val model = model()
        val turns = contents(
            model,
            contextFor(
                model,
                listOf(
                    ToolCall("call_1", "bash", """{"command":"echo hi"}"""),
                    ToolCall("call_2", "bash", """{"command":"ls -la"}"""),
                ),
            ),
        )
        val modelTurn = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
        val functionCallParts = partsOf(modelTurn).filter { it.containsKey("functionCall") }
        assertEquals(2, functionCallParts.size)
        assertNull(functionCallParts[0]["thoughtSignature"])
        assertNull(functionCallParts[1]["thoughtSignature"])
        assertTrue("skip_thought_signature_validator" !in modelTurn.toString())
        assertTrue("Historical context" !in modelTurn.toString())
    }

    @Test
    fun `preserves tool call thought signature for the same model`() {
        val model = model()
        val turns = contents(
            model,
            contextFor(
                model,
                listOf(
                    ToolCall("call_1", "bash", """{"command":"ls"}""", thoughtSignature = validSig),
                    ToolCall("call_2", "bash", """{"command":"ls"}"""),
                ),
            ),
        )
        val modelTurn = turns.first { it["role"]!!.jsonPrimitive.content == "model" }
        val functionCallParts = partsOf(modelTurn).filter { it.containsKey("functionCall") }
        assertEquals(validSig, functionCallParts[0]["thoughtSignature"]!!.jsonPrimitive.content)
        assertNull(functionCallParts[1]["thoughtSignature"])
    }

    @Test
    fun `empty-string thinking signature falls through to the blank-drop path`() {
        val model = model()
        val transformed = transformMessages(
            listOf(
                AssistantMessage(
                    content = listOf(
                        ThinkingContent("", thinkingSignature = ""),
                        ThinkingContent("real", thinkingSignature = "sig"),
                    ),
                    api = model.api, provider = model.provider, model = model.id,
                    stopReason = StopReason.STOP,
                ),
            ),
            model,
        )
        val content = (transformed.single() as AssistantMessage).content
        assertEquals(listOf<ThinkingContent>(ThinkingContent("real", thinkingSignature = "sig")), content)
    }

    @Test
    fun `orphaned tool calls get synthetic error results`() {
        val model = model()
        val transformed = transformMessages(
            listOf(
                UserMessage.ofText("go"),
                AssistantMessage(
                    content = listOf(ToolCall("call_x", "t", "{}")),
                    api = model.api, provider = model.provider, model = model.id,
                    stopReason = StopReason.TOOL_USE,
                ),
                UserMessage.ofText("interrupt"),
            ),
            model,
        )
        val synthetic = transformed.filterIsInstance<ToolResultMessage>().single()
        assertEquals("call_x", synthetic.toolCallId)
        assertEquals("No result provided", (synthetic.content.single() as TextContent).text)
        assertTrue(synthetic.isError)
    }

    @Test
    fun `errored assistant turns are dropped from replay`() {
        val model = model()
        val transformed = transformMessages(
            listOf(
                UserMessage.ofText("go"),
                AssistantMessage(
                    content = listOf(TextContent("partial")),
                    api = model.api, provider = model.provider, model = model.id,
                    stopReason = StopReason.ERROR,
                    errorMessage = "boom",
                ),
                UserMessage.ofText("again"),
            ),
            model,
        )
        assertEquals(2, transformed.size)
        assertTrue(transformed.none { it is AssistantMessage })
    }

    @Test
    fun `images downgrade to placeholders for non-vision models`() {
        val model = model(id = "gemini-2.5-flash", input = listOf(InputModality.TEXT))
        val transformed = transformMessages(
            listOf(
                UserMessage(
                    listOf(TextContent("look"), ImageContent("aa", "image/png"), ImageContent("bb", "image/png")),
                ),
            ),
            model,
        )
        val user = transformed.single() as UserMessage
        val texts = user.content.filterIsInstance<TextContent>().map { it.text }
        assertEquals(listOf("look", "(image omitted: model does not support images)"), texts)
    }
}

class GoogleSharedConvertToolsTest {

    private val tool = works.resolve.pathfinder.ai.Tool(
        name = "bash",
        description = "run",
        parameters = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"object","properties":{"cmd":{"type":"string"}},"required":["cmd"]}""",
        ),
    )

    @Test
    fun `uses parametersJsonSchema by default`() {
        val tools = GoogleShared.convertTools(listOf(tool))!!
        val declaration = tools[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertEquals("bash", declaration["name"]!!.jsonPrimitive.content)
        assertEquals("run", declaration["description"]!!.jsonPrimitive.content)
        assertNull(declaration["parameters"])
        assertTrue(declaration.containsKey("parametersJsonSchema"))
    }

    @Test
    fun `legacy parameters strips json schema meta declarations`() {
        val strictTool = tool.copy(
            parameters = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"${'$'}schema":"https://example.com/schema","type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/a"}},"${'$'}defs":{"a":{"type":"string"}}}""",
            ),
        )
        val tools = GoogleShared.convertTools(listOf(strictTool), useParameters = true)!!
        val declaration = tools[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        val parameters = declaration["parameters"]!!.jsonObject
        assertNull(parameters["schema"])
        assertNull(parameters["defs"])
        assertTrue(parameters.containsKey("properties"))
    }

    @Test
    fun `empty tools return null`() {
        assertNull(GoogleShared.convertTools(emptyList()))
    }

    @Test
    fun `mode resolution ports resolveGoogleFunctionCallingMode`() {
        assertNull(GoogleShared.resolveGoogleFunctionCallingMode(listOf(tool), null, true))
        assertEquals("NONE", GoogleShared.resolveGoogleFunctionCallingMode(listOf(tool), "none", true))
        assertEquals("ANY", GoogleShared.resolveGoogleFunctionCallingMode(listOf(tool), "any", true))
        assertEquals("AUTO", GoogleShared.resolveGoogleFunctionCallingMode(listOf(tool), "auto", true))
        assertEquals("AUTO", GoogleShared.mapToolChoice("unknown"))
    }

    @Test
    fun `strict tool sampling requires gemini3 or newer`() {
        assertTrue(GoogleShared.supportsGoogleStrictToolSampling("gemini-3-pro-preview"))
        assertTrue(GoogleShared.supportsGoogleStrictToolSampling("gemini-live-3.0"))
        assertTrue(!GoogleShared.supportsGoogleStrictToolSampling("gemini-2.5-flash"))
        assertTrue(!GoogleShared.supportsGoogleStrictToolSampling("gpt-oss-120b"))
    }

    /** Mirrors the requiresToolCallId table in pi's gemini3-unsigned-tool-call suite. */
    @Test
    fun `requiresToolCallId ports the upstream model table`() {
        assertTrue(!GoogleShared.requiresToolCallId("gemini-2.5-flash"))
        assertTrue(GoogleShared.requiresToolCallId("gemini-3.6-flash"))
        assertTrue(GoogleShared.requiresToolCallId("claude-sonnet-4-5"))
        assertTrue(GoogleShared.requiresToolCallId("gpt-oss-120b"))
    }

    @Test
    fun `uses validated function calling for strict tools on gemini3`() {
        val strictTool = tool.copy(
            constrainedSampling = works.resolve.pathfinder.ai.ConstrainedSamplingConfig.JsonSchema(
                works.resolve.pathfinder.ai.StrictJsonSchemaMode.REQUIRE,
            ),
        )

        assertTrue(GoogleShared.supportsGoogleStrictToolSampling("gemini-3.1-pro-preview"))
        assertTrue(!GoogleShared.supportsGoogleStrictToolSampling("gemini-2.5-pro"))
        assertEquals("VALIDATED", GoogleShared.resolveGoogleFunctionCallingMode(listOf(strictTool), null, true))
        val failure = assertFailsWith<ConstrainedSamplingError> {
            GoogleShared.resolveGoogleFunctionCallingMode(listOf(strictTool), null, false)
        }
        assertTrue(
            failure.message!!.startsWith("Tool \"bash\" requires JSON-schema constrained sampling"),
        )
    }

    @Test
    fun `convertTools wraps parametersJsonSchema strict for strict tools`() {
        val strictTool = tool.copy(
            constrainedSampling = works.resolve.pathfinder.ai.ConstrainedSamplingConfig.JsonSchema(
                works.resolve.pathfinder.ai.StrictJsonSchemaMode.PREFER,
            ),
        )

        val plain = GoogleShared.convertTools(listOf(tool))!!
            .let { it[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject }
        assertEquals(
            tool.parameters,
            plain["parametersJsonSchema"],
        )

        val strict = GoogleShared.convertTools(listOf(strictTool), useParameters = false, supportsStrictMode = true)!!
            .let { it[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject }
        val parameters = strict["parametersJsonSchema"]!!.jsonObject
        assertEquals(false, parameters["additionalProperties"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("cmd", parameters["required"]!!.jsonArray[0].jsonPrimitive.content)

        val downgraded = GoogleShared.convertTools(listOf(strictTool), useParameters = false, supportsStrictMode = false)!!
            .let { it[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject }
        assertEquals(tool.parameters, downgraded["parametersJsonSchema"])
    }

    @Test
    fun `convertTools propagates require rejection for unsupported strict mode`() {
        val strictTool = tool.copy(
            constrainedSampling = works.resolve.pathfinder.ai.ConstrainedSamplingConfig.JsonSchema(
                works.resolve.pathfinder.ai.StrictJsonSchemaMode.REQUIRE,
            ),
        )
        val failure = assertFailsWith<ConstrainedSamplingError> {
            GoogleShared.convertTools(listOf(strictTool), supportsStrictMode = false)
        }
        assertEquals(
            "Tool \"bash\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
            failure.message,
        )
    }

    @Test
    fun `mapStopReason ports finish reason mapping`() {
        assertEquals(StopReason.STOP, GoogleShared.mapStopReason("STOP"))
        assertEquals(StopReason.LENGTH, GoogleShared.mapStopReason("MAX_TOKENS"))
        assertEquals(StopReason.ERROR, GoogleShared.mapStopReason("SAFETY"))
        assertEquals(StopReason.ERROR, GoogleShared.mapStopReason("MALFORMED_FUNCTION_CALL"))
        assertEquals(StopReason.ERROR, GoogleShared.mapStopReason("WHATEVER"))
    }

    @Test
    fun `resolveGoogleThinkingLevel ports mappings and errors`() {
        val base = works.resolve.pathfinder.ai.Model(
            id = "gemini-3.7-flash", name = "", api = "google-generative-ai", provider = "test-google",
            baseUrl = "https://example.invalid/v1beta", reasoning = true,
        )
        assertEquals(
            GoogleShared.ResolvedGoogleThinkingLevel.HIGH,
            GoogleShared.resolveGoogleThinkingLevel(base, works.resolve.pathfinder.ai.ModelThinkingLevel.OFF),
        )
        for ((level, mapped) in listOf(
            "MINIMAL" to GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL,
            "LOW" to GoogleShared.ResolvedGoogleThinkingLevel.LOW,
            "MEDIUM" to GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM,
            "HIGH" to GoogleShared.ResolvedGoogleThinkingLevel.HIGH,
        )) {
            val model = base.copy(
                thinkingLevelMap = works.resolve.pathfinder.ai.ThinkingLevelMap.of(
                    works.resolve.pathfinder.ai.ModelThinkingLevel.HIGH to mapped.name,
                    works.resolve.pathfinder.ai.ModelThinkingLevel.XHIGH to mapped.name,
                ),
            )
            assertEquals(mapped, GoogleShared.resolveGoogleThinkingLevel(model, works.resolve.pathfinder.ai.ModelThinkingLevel.HIGH))
            assertEquals(mapped, GoogleShared.resolveGoogleThinkingLevel(model, works.resolve.pathfinder.ai.ModelThinkingLevel.XHIGH))
        }

        val invalid = base.copy(
            thinkingLevelMap = works.resolve.pathfinder.ai.ThinkingLevelMap.of(
                works.resolve.pathfinder.ai.ModelThinkingLevel.XHIGH to "extreme",
            ),
        )
        val error = assertFailsWith<IllegalStateException> {
            GoogleShared.resolveGoogleThinkingLevel(invalid, works.resolve.pathfinder.ai.ModelThinkingLevel.XHIGH)
        }
        assertEquals(
            "Unsupported Google thinking level mapping for test-google/gemini-3.7-flash: xhigh -> extreme",
            error.message,
        )
    }
}
