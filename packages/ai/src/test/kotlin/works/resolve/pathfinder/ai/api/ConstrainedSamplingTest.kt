package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.GrammarFormat
import works.resolve.pathfinder.ai.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.Tool

class ConstrainedSamplingTest {

    private fun schema(json: String) = Json.parseToJsonElement(json)

    private fun makeTool(
        parameters: String =
            """{"type":"object","properties":{"payload":{"type":"string"}},"required":["payload"]}""",
        constrainedSampling: ConstrainedSamplingConfig? = null
    ) = Tool(
        name = "sample_tool",
        description = "Sample tool",
        parameters = schema(parameters),
        constrainedSampling = constrainedSampling
    )

    @Test
    fun `derives strict provider schemas without changing tool definitions`() {
        val parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string"},
                "offset": {"type": "number"},
                "metadata": {
                  "type": "object",
                  "properties": {"enabled": {"type": "boolean"}}
                },
                "nullable": {"anyOf": [{"type": "string"}, {"type": "null"}]}
              },
              "required": ["path", "metadata"]
            }
            """.trimIndent()
        )

        val strict = makeStrictJsonSchema(parameters)

        assertTrue(!(parameters.jsonObject.containsKey("additionalProperties")))
        assertEquals(
            listOf("path", "metadata"),
            parameters.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(false, strict["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("path", "offset", "metadata", "nullable"),
            strict["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            """{"anyOf":[{"type":"number"},{"type":"null"}]}""",
            strict["properties"]!!.jsonObject["offset"].toString()
        )
        assertEquals(
            listOf("enabled"),
            strict["properties"]!!.jsonObject["metadata"]!!.jsonObject["required"]!!
                .jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            """{"anyOf":[{"type":"boolean"},{"type":"null"}]}""",
            strict["properties"]!!.jsonObject["metadata"]!!.jsonObject["properties"]!!
                .jsonObject["enabled"].toString()
        )
        assertEquals(
            """{"anyOf":[{"type":"string"},{"type":"null"}]}""",
            strict["properties"]!!.jsonObject["nullable"].toString()
        )
    }

    @Test
    fun `falls back or rejects schemas that cannot be safely converted`() {
        val cases = listOf(
            """{"type":"object","properties":{"metadata":{"type":"object",""" +
                """"additionalProperties":{"type":"string"}}},"required":["metadata"]}""" to
                "schema-valued or true additionalProperties is unsupported",
            """{"type":"object","allOf":[{"type":"object",""" +
                """"properties":{"a":{"type":"string"}}},""" +
                """{"type":"object","properties":{"b":{"type":"number"}}}]}""" to
                "allOf schemas are unsupported",
            """{"type":"object","properties":{"value":{"anyOf":[{"type":"object",""" +
                """"properties":{"nested":{"type":"string"}}},""" +
                """{"type":"null"}]}},"required":["value"]}""" to
                "object and array unions are unsupported",

            """{"type":"object","properties":{"child":{"${'$'}ref":""" +
                """"https://example.com/child.json"""" +
                """}},"required":["child"]}""" to
                "\$ref schemas are unsupported"
        )

        for ((parameters, error) in cases) {
            val tool = makeTool(
                parameters,
                ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.PREFER)
            )

            assertFailsWith<UnsupportedStrictJsonSchemaError>(error) {
                makeStrictJsonSchema(tool.parameters)
            }
            assertNull(resolveJsonSchemaStrictSampling(tool, supportsStrictMode = true))
            assertEquals(tool.parameters, getJsonSchemaToolParameters(tool, strict = null))

            val requiring = tool.copy(
                constrainedSampling = ConstrainedSamplingConfig.JsonSchema(
                    StrictJsonSchemaMode.REQUIRE
                )
            )
            val failure =
                assertFailsWith<ConstrainedSamplingError> {
                    resolveJsonSchemaStrictSampling(requiring, true)
                }
            assertTrue(
                failure.message!!.contains(error),
                "expected \"$error\" in \"${failure.message}\""
            )
        }
    }

    @Test
    fun `resolves prefer and require strict sampling`() {
        val prefer =
            makeTool(
                constrainedSampling = ConstrainedSamplingConfig.JsonSchema(
                    StrictJsonSchemaMode.PREFER
                )
            )
        val require = prefer.copy(
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(
                StrictJsonSchemaMode.REQUIRE
            )
        )
        val plain = makeTool()

        assertNull(resolveJsonSchemaStrictSampling(plain, supportsStrictMode = true))
        assertNull(resolveJsonSchemaStrictSampling(plain, supportsStrictMode = false))
        assertNull(
            resolveJsonSchemaStrictSampling(
                makeTool(constrainedSampling = ConstrainedSamplingConfig.Disabled),
                supportsStrictMode = true
            )
        )

        assertEquals(true, resolveJsonSchemaStrictSampling(prefer, supportsStrictMode = true))
        assertEquals(true, resolveJsonSchemaStrictSampling(require, supportsStrictMode = true))

        assertNull(resolveJsonSchemaStrictSampling(prefer, supportsStrictMode = false))
        val failure =
            assertFailsWith<ConstrainedSamplingError> {
                resolveJsonSchemaStrictSampling(require, supportsStrictMode = false)
            }
        assertEquals(
            "Tool \"sample_tool\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
            failure.message
        )
    }

    @Test
    fun `resolves grammar constrained sampling preferring lark over regex`() {
        val both = makeTool(
            constrainedSampling = ConstrainedSamplingConfig.Grammar(
                mapOf(
                    GrammarFormat.OPENAI_LARK to "start: /[a-z]+/",
                    GrammarFormat.OPENAI_REGEX to "[a-z]+"
                )
            )
        )
        assertEquals(
            GrammarConstrainedSampling(GrammarConstrainedFormat.LARK, "start: /[a-z]+/", "payload"),
            resolveGrammarConstrainedSampling(both, supportsOpenAIGrammarTools = true)
        )

        val regexOnly = makeTool(
            constrainedSampling = ConstrainedSamplingConfig.Grammar(
                mapOf(GrammarFormat.OPENAI_REGEX to "[a-z]+")
            )
        )
        assertEquals(
            GrammarConstrainedSampling(GrammarConstrainedFormat.REGEX, "[a-z]+", "payload"),
            resolveGrammarConstrainedSampling(regexOnly, supportsOpenAIGrammarTools = true)
        )

        // Blank variants do not count.
        val blank = makeTool(
            constrainedSampling = ConstrainedSamplingConfig.Grammar(
                mapOf(GrammarFormat.OPENAI_LARK to "   ")
            )
        )
        val failure = assertFailsWith<ConstrainedSamplingError> {
            resolveGrammarConstrainedSampling(blank, supportsOpenAIGrammarTools = true)
        }
        assertEquals(
            "Tool \"sample_tool\" cannot use grammar constrained sampling: no supported grammar variant was provided.",
            failure.message
        )

        assertNull(resolveGrammarConstrainedSampling(both, supportsOpenAIGrammarTools = false))
        assertNull(resolveGrammarConstrainedSampling(plain(), supportsOpenAIGrammarTools = true))
    }

    @Test
    fun `grammar input property inference errors are wrapped`() {
        val cases = listOf(
            """{"type":"string"}""" to
                "grammar constrained sampling requires an object parameter schema",
            """{"type":"object","properties":{"payload":{"type":"string"}}}""" to
                "grammar constrained sampling requires exactly one required string property",
            """{"type":"object","required":["payload"]}""" to
                "grammar constrained sampling requires a properties entry for payload",
            """{"type":"object","properties":{"payload":{"type":"number"}},""" +
                """"required":["payload"]}""" to
                "grammar constrained sampling property payload must have type string"
        )
        for ((parameters, error) in cases) {
            val tool = makeTool(
                parameters,
                ConstrainedSamplingConfig.Grammar(
                    mapOf(GrammarFormat.OPENAI_LARK to "start: /[a-z]+/")
                )
            )
            val failure = assertFailsWith<ConstrainedSamplingError> {
                resolveGrammarConstrainedSampling(tool, supportsOpenAIGrammarTools = true)
            }
            assertEquals(
                "Tool \"sample_tool\" cannot use grammar constrained sampling: $error.",
                failure.message
            )
        }
    }

    @Test
    fun `creates grammar tool input properties per tool`() {
        val grammarTool = makeTool(
            constrainedSampling = ConstrainedSamplingConfig.Grammar(
                mapOf(GrammarFormat.OPENAI_LARK to "start: /[a-z]+/")
            )
        )
        val jsonSchemaTool = makeTool(
            constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.PREFER)
        )
        assertEquals(
            mapOf("sample_tool" to "payload"),
            createGrammarToolInputProperties(
                listOf(grammarTool, jsonSchemaTool),
                supportsOpenAIGrammarTools = true
            )
        )
        assertEquals(
            emptyMap<String, String>(),
            createGrammarToolInputProperties(
                listOf(grammarTool),
                supportsOpenAIGrammarTools = false
            )
        )
        assertEquals(
            emptyMap<String, String>(),
            createGrammarToolInputProperties(null, supportsOpenAIGrammarTools = true)
        )
    }

    @Test
    fun `reads grammar tool input arguments`() {
        val arguments = JsonObject(mapOf("payload" to JsonPrimitive("abc")))
        assertEquals("abc", getGrammarToolInput("sample_tool", arguments, "payload"))
        for (invalid in listOf(
            JsonObject(emptyMap()),
            JsonObject(mapOf("payload" to JsonPrimitive(42)))
        )) {
            val failure =
                assertFailsWith<ConstrainedSamplingError> {
                    getGrammarToolInput("sample_tool", invalid, "payload")
                }
            assertEquals(
                "Grammar tool call \"sample_tool\" requires argument \"payload\" to be a string.",
                failure.message
            )
        }
    }

    @Test
    fun `keeps grammar input JSON deltas append-only`() {
        val buffer = GrammarToolInputJsonBuffer()
        val first = appendGrammarToolInputJsonDelta(buffer, "payload", "a\"", close = false)
        val second = appendGrammarToolInputJsonDelta(buffer, "payload", "a\"\nb", close = true)

        assertEquals("""{"payload":"a\"\nb"}""", first + second)
        assertNull(appendGrammarToolInputJsonDelta(buffer, "payload", "a\"\nb", close = true))
        val failure = assertFailsWith<ConstrainedSamplingError> {
            appendGrammarToolInputJsonDelta(buffer, "payload", "changed", close = true)
        }
        assertEquals(
            "grammar tool input for property \"payload\" changed after it was closed",
            failure.message
        )
    }

    @Test
    fun `rejects non-monotonic grammar input deltas`() {
        val buffer = GrammarToolInputJsonBuffer()
        appendGrammarToolInputJsonDelta(buffer, "payload", "ab", close = false)
        assertNull(appendGrammarToolInputJsonDelta(buffer, "payload", "ab", close = false))
        val failure = assertFailsWith<ConstrainedSamplingError> {
            appendGrammarToolInputJsonDelta(buffer, "payload", "ax", close = false)
        }
        assertEquals(
            "grammar tool input for property \"payload\" changed non-monotonically",
            failure.message
        )
    }

    private fun plain() = makeTool()
}
