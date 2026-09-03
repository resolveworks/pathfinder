package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.shortHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MistralConversationsPayloadTest {

    @Test
    fun `shortHash matches pi reference values`() {
        // pi iterates UTF-16 code units.
        assertEquals("k4n83c7h0j2b", shortHash(""))
        assertEquals("y0biex7f9bbh", shortHash("abc"))
        assertEquals("1nlso9v7di2pi", shortHash("toolcall:0"))
        assertEquals("1h9muox1to064d", shortHash("openai-response-id-with-pipes|and-stuff-450-chars"))
        assertEquals("ih6tp613o7wt8", shortHash("héllo 🌍"))
        assertEquals("144a7j62ld7en", shortHash("abc:1"))
    }

    @Test
    fun `deriveMistralToolCallId keeps 9-char alnum ids and hashes the rest`() {
        assertEquals("abc123456", deriveMistralToolCallId("abc123456", 0))
        assertEquals("abc123456", deriveMistralToolCallId("abc-123_456", 0))
        val derived = deriveMistralToolCallId("toolcall:0", 0)
        assertEquals("toolcall0", derived)
        assertEquals("knulnuw1n", deriveMistralToolCallId("toolcall:00", 0))
        assertEquals(shortHash("---").take(9), deriveMistralToolCallId("---", 0))
        assertEquals(shortHash("abc:1").take(9), deriveMistralToolCallId("abc", 1))
    }

    @Test
    fun `normalizer is stable and avoids collisions`() {
        val normalizer = MistralToolCallIdNormalizer()
        val a = normalizer.normalize("long-openai-style-id-with-pipes|1234567890")
        val b = normalizer.normalize("long-openai-style-id-with-pipes|1234567890")
        assertEquals(a, b)
        assertEquals(9, a.length)
        assertTrue(a.all { it.isLetterOrDigit() })

        val c = normalizer.normalize("another-long-openai-style-id|0987654321")
        assertTrue(c.length <= 9)
        assertTrue(a != c || "long-openai-style-id-with-pipes|1234567890" == "another-long-openai-style-id|0987654321")
    }

    @Test
    fun `strict tool schema is rewritten and serialized as plain json`() {
        // pi's test also asserts TypeBox symbol metadata that JsonElement
        // cannot carry; only the payload assertions port.
        val model = mistralModel(id = "devstral-medium-latest")
        val parameters = Json.parseToJsonElement(
            """{"type":"object","properties":{"nested":{"type":"object","properties":{"value":{"type":"string"}}}},"required":["nested"]}""",
        )
        val tool = Tool(
            name = "inspect_schema",
            description = "Inspect the schema",
            parameters = parameters,
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.REQUIRE),
        )
        val context = Context(
            messages = listOf(UserMessage.ofText("Hi")),
            tools = listOf(tool),
        )

        val body = MistralConversationsPayload.buildRequestBody(
            model,
            context,
            MistralConversationsPayload.toChatMessages(context.messages, supportsImages = false),
            MistralOptions(apiKey = "fake-key"),
        )

        val function = body["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals("inspect_schema", function["name"]!!.jsonPrimitive.content)
        assertEquals(true, function["strict"]!!.jsonPrimitive.content.toBoolean())
        val sent = function["parameters"]!!.jsonObject
        assertEquals(false, sent["additionalProperties"]!!.jsonPrimitive.content.toBoolean())
        val nested = sent["properties"]!!.jsonObject["nested"]!!.jsonObject
        assertEquals(
            listOf("value"),
            nested["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(false, nested["additionalProperties"]!!.jsonPrimitive.content.toBoolean())
        // pi strips TypeBox symbols before sending, so the payload is plain JSON.
        assertEquals(sent, Json.parseToJsonElement(sent.toString()))
    }

    @Test
    fun `tools without constrained sampling keep strict false`() {
        val model = mistralModel()
        val tool = Tool(
            name = "inspect_schema",
            description = "Inspect the schema",
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""",
            ),
        )
        val context = Context(messages = listOf(UserMessage.ofText("Hi")), tools = listOf(tool))
        val body = MistralConversationsPayload.buildRequestBody(
            model,
            context,
            MistralConversationsPayload.toChatMessages(context.messages, supportsImages = false),
            MistralOptions(apiKey = "fake-key"),
        )
        val function = body["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals(false, function["strict"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(tool.parameters, function["parameters"])
    }

    @Test
    fun `buildToolResultText mirrors pi placeholder wording`() {
        val f = MistralConversationsPayload::buildToolResultText
        assertEquals("found", f("found", false, true, false))
        assertEquals("[tool error] found", f(" found ", false, true, true))
        assertEquals(
            "found\n[tool image omitted: model does not support images]",
            f("found", true, false, false),
        )
        assertEquals("(see attached image)", f("", true, true, false))
        assertEquals("[tool error] (see attached image)", f("", true, true, true))
        assertEquals(
            "(image omitted: model does not support images)",
            f("", true, false, false),
        )
        assertEquals(
            "[tool error] (image omitted: model does not support images)",
            f("", true, false, true),
        )
        assertEquals("(no tool output)", f("", false, true, false))
        assertEquals("[tool error] (no tool output)", f("", false, true, true))
    }
}
