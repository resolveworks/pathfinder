
@file:Suppress("ktlint:standard:max-line-length")

package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** Coercion cases ported from pi-schema's test/coerce.test.ts. */
class SchemaCoercerTest {
    private fun schema(json: String): JsonElement = Json.parseToJsonElement(json)
    private fun value(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun coerce(schemaJson: String, valueJson: String): JsonElement =
        SchemaValidator(schema(schemaJson)).coerce(value(valueJson))

    private fun coerceValue(schemaJson: String, valueJson: String): JsonElement {
        val root = """
            {"type":"object","properties":{"value":$schemaJson},"required":["value"]}
        """.trimIndent()
        return coerce(root, """{"value":$valueJson}""").jsonObject.getValue("value")
    }

    @Test
    fun `never modifies the value and returns it when nothing changes`() {
        val input = value("""{"a":{"b":[1,2]},"c":"x"}""")
        val schema = """
            {"type":"object","properties":{
              "a":{"type":"object","properties":{"b":{"type":"array","items":{"type":"number"}}}},
              "c":{"type":"string"}}}
        """.trimIndent()
        assertSame(input, SchemaValidator(schema(schema)).coerce(input))
    }

    @Test
    fun `copies only the changed paths`() {
        val unchanged = value("""{"b":[1,2]}""")
        val input = value("""{"a":$unchanged,"c":{"d":"1"}}""")
        val schema = """{"type":"object","properties":{"a":{"type":"object","properties":{"b":{"type":"array","items":{"type":"number"}}}},"c":{"type":"object","properties":{"d":{"type":"number"}}}}}"""
        val out = SchemaValidator(schema(schema)).coerce(input)
        assertNotSame(input, out)
        assertSame(input.jsonObject.getValue("a"), out.jsonObject.getValue("a"))
        assertEquals("""{"a":{"b":[1,2]},"c":{"d":1}}""", out.toString())
        assertEquals("""{"a":{"b":[1,2]},"c":{"d":"1"}}""", input.toString())
    }

    @Test
    fun `converts primitives by type`() {
        assertEquals("42", coerceValue("""{"type":"number"}""", "\"42\"").toString())
        assertEquals("0", coerceValue("""{"type":"number"}""", "\"\"").toString())
        assertEquals("1", coerceValue("""{"type":"number"}""", "\"true\"").toString())
        assertEquals("3", coerceValue("""{"type":"integer"}""", "\"3.5\"").toString())
        assertEquals("1", coerceValue("""{"type":"integer"}""", "1.5").toString())
        assertEquals("true", coerceValue("""{"type":"boolean"}""", "\"1\"").toString())
        assertEquals("false", coerceValue("""{"type":"boolean"}""", "\"FALSE\"").toString())
        assertEquals("true", coerceValue("""{"type":"boolean"}""", "\"TRUE\"").toString())
        assertEquals("null", coerceValue("""{"type":"null"}""", "\"null\"").toString())
        assertEquals("\"42\"", coerceValue("""{"type":"string"}""", "42").toString())
    }

    @Test
    fun `wraps a single value into an array and converts the items`() {
        assertEquals(
            "[1]",
            coerceValue("""{"type":"array","items":{"type":"number"}}""", "\"1\"").toString()
        )
        assertEquals(
            "[\"null\"]",
            coerceValue("""{"type":"array","items":{"type":"string"}}""", "null").toString()
        )
    }

    @Test
    fun `converts tuple items in place and never wraps for a tuple`() {
        val tuple = """{"type":"array","prefixItems":[{"type":"number"},{"type":"boolean"}],"items":false}"""
        assertEquals("[1,true]", coerceValue(tuple, """["1","true"]""").toString())
        assertEquals("\"1\"", coerceValue(tuple, "\"1\"").toString())
    }

    @Test
    fun `converts const and enum values only when the result is a member`() {
        assertEquals("1", coerceValue("""{"const":1}""", "\"1\"").toString())
        assertEquals("\"2\"", coerceValue("""{"const":1}""", "\"2\"").toString())
        assertEquals("2", coerceValue("""{"enum":[1,2,"hello"]}""", "\"2\"").toString())
        assertEquals("\"world\"", coerceValue("""{"enum":[1,2,"hello"]}""", "\"world\"").toString())
    }

    @Test
    fun `keeps a value one union branch accepts else takes the first accepted conversion`() {
        val union = """{"anyOf":[{"type":"number"},{"type":"null"}]}"""
        assertEquals("null", coerceValue(union, "null").toString())
        assertEquals("42", coerceValue(union, "\"42\"").toString())
        assertEquals("null", coerceValue(union, "\"null\"").toString())
        assertEquals(
            "true",
            coerceValue("""{"oneOf":[{"type":"boolean"},{"type":"number"}]}""", "\"1\"").toString()
        )
    }

    @Test
    fun `converts allOf with each member in turn`() {
        val schema = """{"allOf":[{"type":"object","properties":{"a":{"type":"number"}}},{"type":"object","properties":{"b":{"type":"boolean"}}}]}"""
        assertEquals("""{"a":1,"b":true}""", coerce(schema, """{"a":"1","b":"true"}""").toString())
    }

    @Test
    fun `follows ref`() {
        val schema = """
            {"type":"object","properties":{"item":{"${'$'}ref":"#/${'$'}defs/item"}},
             "${'$'}defs":{"item":{"type":"object","properties":{"id":{"type":"integer"}}}}}
        """.trimIndent()
        assertEquals("""{"item":{"id":7}}""", coerce(schema, """{"item":{"id":"7"}}""").toString())
    }

    @Test
    fun `converts patternProperties and additionalProperties`() {
        val schema = """
            {"type":"object","properties":{"a":{"type":"number"}},
             "patternProperties":{"^n_":{"type":"number"}},
             "additionalProperties":{"type":"boolean"}}
        """.trimIndent()
        assertEquals(
            """{"a":1,"n_x":2,"other":true}""",
            coerce(schema, """{"a":"1","n_x":"2","other":"true"}""").toString()
        )
    }

    @Test
    fun `coerces like Ajv's coerceTypes in pass 3`() {
        assertEquals("1", coerceValue("""{"type":"number"}""", "true").toString())
        assertEquals("0", coerceValue("""{"type":"number"}""", "null").toString())
        assertEquals("42", coerceValue("""{"type":"integer"}""", "\"42\"").toString())
        assertEquals("true", coerceValue("""{"type":"boolean"}""", "\"true\"").toString())
        assertEquals("false", coerceValue("""{"type":"boolean"}""", "0").toString())
        assertEquals("\"true\"", coerceValue("""{"type":"string"}""", "true").toString())
        assertEquals("null", coerceValue("""{"type":"null"}""", "\"\"").toString())
        assertEquals("null", coerceValue("""{"type":"null"}""", "0").toString())
        assertEquals("null", coerceValue("""{"type":"null"}""", "false").toString())
    }

    @Test
    fun `leaves a value matching a type array alone else takes the first coercible type`() {
        assertEquals("\"1\"", coerceValue("""{"type":["number","string"]}""", "\"1\"").toString())
        assertEquals("1", coerceValue("""{"type":["boolean","number"]}""", "\"1\"").toString())
        assertEquals("5", coerceValue("""{"type":["integer","null"]}""", "\"5\"").toString())
    }

    @Test
    fun `accepts null for nullable array schemas with items`() {
        assertEquals(
            "null",
            coerceValue(
                """{"type":["array","null"],"items":{"type":"string"}}""",
                "null"
            ).toString()
        )
    }

    @Test
    fun `where the passes conflict the conversion rules win`() {
        assertEquals("\"null\"", coerceValue("""{"type":"string"}""", "null").toString())
        assertEquals("0", coerceValue("""{"type":"number"}""", "\"\"").toString())
        assertEquals("false", coerceValue("""{"type":"boolean"}""", "\"0\"").toString())
        assertEquals("42", coerceValue("""{"type":"integer"}""", "\"42.1\"").toString())
        assertEquals(
            "{}",
            coerce(
                """{"type":"object","properties":{"name":{"type":"string"}}}""",
                """{"name":null}"""
            ).toString()
        )
    }

    @Test
    fun `drops null in optional non-nullable properties also nested`() {
        val schema = """
            {"type":"object","properties":{
              "path":{"type":"string"},
              "offset":{"type":"number"},
              "nullable":{"type":["string","null"]},
              "metadata":{"type":"object","properties":{"enabled":{"type":"boolean"}}}}}
        """.trimIndent()
        assertEquals(
            """{"path":"file.txt","nullable":null,"metadata":{}}""",
            coerce(
                schema,
                """{"path":"file.txt","offset":null,"nullable":null,"metadata":{"enabled":null}}"""
            )
                .toString()
        )
    }

    @Test
    fun `keeps a null whose property is a ref and walks array items`() {
        val schema = """
            {"type":"object","properties":{"value":{"${'$'}ref":"#/${'$'}defs/value"}},
             "${'$'}defs":{"value":{"anyOf":[{"type":"number"},{"type":"null"}]}}}
        """.trimIndent()
        assertEquals("""{"value":null}""", coerce(schema, """{"value":null}""").toString())
        assertEquals(
            """[{"a":"x"}]""",
            coerce(
                """{"type":"array","items":{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}}}}""",
                """[{"a":"x","b":null}]"""
            ).toString()
        )
    }

    @Test
    fun `keeps a null under pattern and additional properties`() {
        assertEquals(
            """{"n":null}""",
            coerce(
                """{"type":"object","additionalProperties":{"type":["integer","null"]}}""",
                """{"n":null}"""
            )
                .toString()
        )
    }

    @Test
    fun `convert through a recursive union by its first accepting branch`() {
        val tree = """
            {"type":"object","properties":{"t":{"${'$'}ref":"#/${'$'}defs/Tree"}},
             "${'$'}defs":{"Tree":{"anyOf":[{"type":"string"},
               {"type":"array","items":{"${'$'}ref":"#/${'$'}defs/Tree"}}]}}}
        """.trimIndent()
        val validator = SchemaValidator(schema(tree))
        assertEquals(
            SchemaParseResult.Valid(value("""{"t":["a",["b"]]}""")),
            validator.parse(value("""{"t":["a",["b"]]}"""))
        )
        assertEquals(
            """{"t":"5"}""",
            validator.parse(value("""{"t":5}""")).let {
                (it as SchemaParseResult.Valid).value.toString()
            }
        )
        assertEquals(
            """{"t":["5"]}""",
            validator.parse(value("""{"t":[5]}""")).let {
                (it as SchemaParseResult.Valid).value.toString()
            }
        )
    }

    @Test
    fun `convert a scalar-or-array schema that refers to itself`() {
        val schema = """
            {"type":"object","properties":{"value":{"anyOf":[{"type":"number"},
             {"type":"array","items":{"${'$'}ref":"#/properties/value"}}]}},"required":["value"]}
        """.trimIndent()
        val validator = SchemaValidator(schema(schema))
        assertEquals(
            """{"value":42}""",
            (
                validator.parse(
                    value("""{"value":"42"}""")
                ) as SchemaParseResult.Valid
                ).value.toString()
        )
        assertEquals(
            """{"value":[1,[2]]}""",
            (
                validator.parse(
                    value("""{"value":["1",["2"]]}""")
                ) as SchemaParseResult.Valid
                ).value.toString()
        )
        assertFalse(validator.parse(value("""{"value":{}}""")) is SchemaParseResult.Valid)
    }

    @Test
    fun `reports errors of the coerced value`() {
        val validator = SchemaValidator(
            schema(
                """{"type":"object","properties":{"count":{"type":"number"},"name":{"type":"string"}}}"""
            )
        )
        val result = validator.parse(value("""{"count":"many","name":7}"""))
        assertEquals(
            listOf(Triple("/count", "type", "expected number, got string")),
            (result as SchemaParseResult.Invalid).errors.map {
                Triple(it.path, it.keyword, it.message)
            }
        )
        assertTrue(
            validator.parse(value("""{"count":"1","name":"a"}""")) is SchemaParseResult.Valid
        )
    }
}
