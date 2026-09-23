package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall

/** Direct unit tests for the validateToolArguments port (pi: test/validation.test.ts). */
class ValidationTest {

    /** pi's helper shape: one required `value` property of the given schema. */
    private fun valueTool(schemaJson: String): Tool = Tool(
        "echo",
        "Echo tool",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject { put("value", Json.parseToJsonElement(schemaJson)) }
            )
            put("required", Json.parseToJsonElement("""["value"]"""))
        }
    )

    private fun validate(tool: Tool, arguments: JsonObject): JsonObject = validateToolArguments(
        tool,
        ToolCall(id = "tool-1", name = tool.name, arguments = arguments)
    )

    private fun validateValue(schemaJson: String, valueJson: String): JsonObject = validate(
        valueTool(schemaJson),
        buildJsonObject { put("value", Json.parseToJsonElement(valueJson)) }
    )

    /** Kind-aware rendering, so numeric results compare as their double content. */
    private fun JsonElement?.repr(): String = when {
        this == null || this is JsonNull -> "null"
        this is JsonPrimitive && isString -> "s:$content"
        this is JsonPrimitive -> content
        else -> toString()
    }

    @Test
    fun coercesPlainJsonSchemasWithAjvCompatiblePrimitiveRules() {
        // pi: "coerces serialized plain JSON schemas with AJV-compatible primitive rules"
        val expectations = listOf(
            """{"type":"number"}""" to ("\"42\"" to "42.0"),
            """{"type":"number"}""" to ("true" to "1"),
            """{"type":"number"}""" to ("null" to "0"),
            """{"type":"integer"}""" to ("\"42\"" to "42.0"),
            """{"type":"boolean"}""" to ("\"true\"" to "true"),
            """{"type":"boolean"}""" to ("\"false\"" to "false"),
            """{"type":"boolean"}""" to ("1" to "true"),
            """{"type":"boolean"}""" to ("0" to "false"),
            """{"type":"string"}""" to ("null" to "s:"),
            """{"type":"string"}""" to ("true" to "s:true"),
            """{"type":"null"}""" to ("\"\"" to "null"),
            """{"type":"null"}""" to ("0" to "null"),
            """{"type":"null"}""" to ("false" to "null"),
            """{"type":["number","string"]}""" to ("\"1\"" to "s:1"),
            """{"type":["boolean","number"]}""" to ("\"1\"" to "1.0")
        )
        for ((schemaJson, expected) in expectations) {
            val (input, output) = expected
            assertEquals(
                output,
                validateValue(schemaJson, input)["value"].repr(),
                "schema $schemaJson input $input"
            )
        }
    }

    @Test
    fun rejectsInvalidCoercionsForPlainJsonSchemas() {
        // pi: "rejects invalid coercions for serialized plain JSON schemas"
        val failingCases = listOf(
            """{"type":"boolean"}""" to "\"1\"",
            """{"type":"boolean"}""" to "\"0\"",
            """{"type":"null"}""" to "\"null\"",
            """{"type":"integer"}""" to "\"42.1\""
        )
        for ((schemaJson, input) in failingCases) {
            val error = assertFailsWith<IllegalArgumentException> {
                validateValue(schemaJson, input)
            }
            assertTrue(error.message!!.startsWith("Validation failed"), error.message)
        }
    }

    @Test
    fun treatsNullAsOmissionForOptionalNonNullableProperties() {
        // pi: "treats null as omission for optional non-nullable properties"
        val tool = Tool(
            "echo",
            "Echo tool",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                        put("offset", buildJsonObject { put("type", "number") })
                        put("nullable", Json.parseToJsonElement("""{"type":["string","null"]}"""))
                        put(
                            "metadata",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("enabled", buildJsonObject { put("type", "boolean") })
                                    }
                                )
                            }
                        )
                    }
                )
                put("required", Json.parseToJsonElement("""["path"]"""))
            }
        )

        val result = validate(
            tool,
            buildJsonObject {
                put("path", "file.txt")
                put("offset", JsonNull)
                put("nullable", JsonNull)
                put("metadata", buildJsonObject { put("enabled", JsonNull) })
            }
        )

        assertEquals("s:file.txt", result["path"].repr())
        assertEquals("null", result["offset"].repr())
        assertEquals("null", result["nullable"].repr())
        assertEquals("{}", result["metadata"].repr())
    }

    @Test
    fun preservesAValueThatAlreadyMatchesANullableUnionArm() {
        // pi: "preserves a value that already matches a nullable union arm"
        val result = validateValue("""{"type":["number","null"]}""", "null")
        assertEquals("null", result["value"].repr())
    }

    @Test
    fun coercesNullableUnionsWhenTheValueMatchesNoArm() {
        // pi: "still coerces nullable unions when the original value does not
        // match any arm" — pi exercises anyOf here; a type array is the
        // plain-schema equivalent this port supports.
        val result = validateValue("""{"type":["number","null"]}""", "\"42\"")
        assertEquals("42.0", result["value"].repr())
    }

    @Test
    fun coercesJsNumberLiteralsForNumberFields() {
        // JS Number(): radix literals parse, whitespace trims, blanks and
        // non-literals do not.
        assertEquals("16.0", validateValue("""{"type":"number"}""", "\"0x10\"")["value"].repr())
        assertEquals("5.0", validateValue("""{"type":"number"}""", "\" 5 \"")["value"].repr())
        assertEquals("100.0", validateValue("""{"type":"number"}""", "\"1e2\"")["value"].repr())
        assertFailsWith<IllegalArgumentException> {
            validateValue("""{"type":"number"}""", "\"\"")
        }
        assertFailsWith<IllegalArgumentException> {
            validateValue("""{"type":"number"}""", "\"  \"")
        }
        assertFailsWith<IllegalArgumentException> {
            validateValue("""{"type":"number"}""", "\"Infinity\"")
        }
        // JS trim (not Kotlin trim) guards the coercion, so JS-whitespace
        // around a literal still coerces — pi's `value.trim() !== ""`.
        assertEquals(
            "5.0",
            validateValue("""{"type":"number"}""", "\"\u00A05\u00A0\"")["value"].repr()
        )
    }

    @Test
    fun coercesRequiredNullsInsteadOfDroppingThem() {
        val tool = Tool(
            "echo",
            "Echo tool",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject { put("command", buildJsonObject { put("type", "string") }) }
                )
                put("required", Json.parseToJsonElement("""["command"]"""))
            }
        )
        val result = validate(tool, buildJsonObject { put("command", JsonNull) })
        assertEquals("s:", result["command"].repr())
    }

    @Test
    fun throwsPiFormattedValidationErrors() {
        val tool = Tool(
            "bash",
            "d",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("command", buildJsonObject { put("type", "string") })
                        put("timeout", buildJsonObject { put("type", "number") })
                    }
                )
                put("required", Json.parseToJsonElement("""["command"]"""))
            }
        )
        val error = assertFailsWith<IllegalArgumentException> {
            validate(
                tool,
                buildJsonObject {
                    put("command", "ls")
                    put("timeout", "abc")
                }
            )
        }
        assertEquals(
            "Validation failed for tool \"bash\":\n" +
                "  - timeout: must be number\n\n" +
                "Received arguments:\n" +
                "{\n  \"command\": \"ls\",\n  \"timeout\": \"abc\"\n}",
            error.message
        )
    }

    @Test
    fun reportsMissingRequiredPropertiesWithPiPaths() {
        val error = assertFailsWith<IllegalArgumentException> {
            validate(valueTool("""{"type":"string"}"""), JsonObject(emptyMap()))
        }
        assertEquals(
            "Validation failed for tool \"echo\":\n" +
                "  - value: must have required properties value\n\n" +
                "Received arguments:\n" +
                "{}",
            error.message
        )
    }

    @Test
    fun reportsNestedArrayErrorsWithDottedPaths() {
        val editItem = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("oldText", buildJsonObject { put("type", "string") })
                    put("newText", buildJsonObject { put("type", "string") })
                }
            )
            put("required", Json.parseToJsonElement("""["oldText","newText"]"""))
        }
        val tool = Tool(
            "edit",
            "d",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                        put(
                            "edits",
                            buildJsonObject {
                                put("type", "array")
                                put("items", editItem)
                            }
                        )
                    }
                )
                put("required", Json.parseToJsonElement("""["path","edits"]"""))
            }
        )
        val error = assertFailsWith<IllegalArgumentException> {
            validate(
                tool,
                buildJsonObject {
                    put("path", 5)
                    put(
                        "edits",
                        Json.parseToJsonElement("""[{"oldText":"a"},{"oldText":2,"newText":3}]""")
                    )
                }
            )
        }
        // The coercion repairs path and the numeric strings; only the missing
        // required property remains, exactly like pi.
        assertEquals(
            "Validation failed for tool \"edit\":\n" +
                "  - edits.0.newText: must have required properties newText\n\n" +
                "Received arguments:\n" +
                "{\n" +
                "  \"path\": 5,\n" +
                "  \"edits\": [\n" +
                "    {\n" +
                "      \"oldText\": \"a\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"oldText\": 2,\n" +
                "      \"newText\": 3\n" +
                "    }\n" +
                "  ]\n" +
                "}",
            error.message
        )
    }

    @Test
    fun reportsEnumMismatches() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateValue("""{"type":"string","enum":["pd","pw"]}""", "\"po\"")
        }
        assertEquals(
            "Validation failed for tool \"echo\":\n" +
                "  - value: must be equal to one of the allowed values",
            error.message!!.substringBefore("\n\nReceived arguments:")
        )
    }

    @Test
    fun echoesNumbersThroughJsFormatting() {
        // JSON.stringify re-formats numbers: the 5.0 token echoes as 5 even
        // though a missing required property causes the failure.
        val tool = Tool(
            "echo",
            "Echo tool",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("value", buildJsonObject { put("type", "string") })
                        put("count", buildJsonObject { put("type", "number") })
                    }
                )
                put("required", Json.parseToJsonElement("""["value","count"]"""))
            }
        )
        val error = assertFailsWith<IllegalArgumentException> {
            validate(
                tool,
                buildJsonObject {
                    put("count", Json.parseToJsonElement("5.0"))
                }
            )
        }
        assertEquals(
            "  - value: must have required properties value",
            error.message!!.substringBefore("\n\nReceived arguments:").substringAfter("\n")
        )
        assertEquals(
            "{\n  \"count\": 5\n}",
            error.message!!.substringAfter("\n\nReceived arguments:\n")
        )
    }

    @Test
    fun rejectsUnsupportedSchemaKeywordsLoudly() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateValue("""{"type":"string","minLength":2}""", "\"ab\"")
        }
        assertEquals(
            "Unsupported schema keyword \"minLength\" in tool \"echo\" parameters",
            error.message
        )
    }

    @Test
    fun ignoresAnnotationKeywordsLikePi() {
        assertEquals(
            "s:ab",
            validateValue(
                """{"type":"string","description":"d","default":"x","title":"t"}""",
                "\"ab\""
            )["value"].repr()
        )
    }

    @Test
    fun requiresObjectParameters() {
        val tool = Tool("echo", "Echo tool", JsonPrimitive("object"))
        val error = assertFailsWith<IllegalArgumentException> {
            validate(tool, JsonObject(emptyMap()))
        }
        assertEquals(
            "Tool \"echo\" parameters must be a JSON Schema object",
            error.message
        )
    }

    @Test
    fun coercesTupleItemsPerIndex() {
        val tool = Tool(
            "echo",
            "Echo tool",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "pair",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    Json.parseToJsonElement(
                                        """[{"type":"string"},{"type":"number"}]"""
                                    )
                                )
                            }
                        )
                    }
                )
                put("required", Json.parseToJsonElement("""["pair"]"""))
            }
        )
        val result = validate(
            tool,
            buildJsonObject { put("pair", Json.parseToJsonElement("[5,\"7\"]")) }
        )
        val pair = result["pair"] as kotlinx.serialization.json.JsonArray
        assertEquals("s:5", pair[0].repr())
        assertEquals("7.0", pair[1].repr())
    }
}
