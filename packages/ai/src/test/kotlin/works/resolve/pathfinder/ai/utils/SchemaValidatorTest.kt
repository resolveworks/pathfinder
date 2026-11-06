
@file:Suppress("ktlint:standard:max-line-length")

package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Validator and explainer cases ported from pi-schema's test/validator.test.ts. */
class SchemaValidatorTest {
    private fun schema(json: String): JsonElement = Json.parseToJsonElement(json)
    private fun value(json: String): JsonElement = Json.parseToJsonElement(json)
    private fun validator(json: String): SchemaValidator = SchemaValidator(schema(json))

    private fun errors(
        schemaJson: String,
        valueJson: String
    ): List<Triple<String, String, String>> = validator(schemaJson).errors(value(valueJson)).map {
        Triple(it.path, it.keyword, it.message)
    }

    @Test
    fun `reports a missing field and an unknown field at the field`() {
        val schema = """
            {"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"integer","minimum":1}},
             "required":["path"],"additionalProperties":false}
        """.trimIndent()
        assertEquals(
            listOf(
                Triple("/path", "required", "missing"),
                Triple("/offset", "minimum", "expected >= 1"),
                Triple("/extra", "additionalProperties", "unknown field")
            ),
            errors(schema, """{"offset":0,"extra":true}""")
        )
    }

    @Test
    fun `reports the branch a tagged union's tag selects`() {
        val schema = """
            {"type":"array","items":{"anyOf":[
              {"type":"object","properties":{"kind":{"const":"text"},"text":{"type":"string"}},
               "required":["kind","text"],"additionalProperties":false},
              {"type":"object","properties":{"kind":{"const":"image"},"width":{"type":"integer","minimum":0}},
               "required":["kind","width"],"additionalProperties":false}]}}
        """.trimIndent()
        val validator = validator(schema)
        assertEquals(
            listOf(Triple("/1/width", "minimum", "expected >= 0")),
            validator.errors(value("""[{"kind":"text","text":"a"},{"kind":"image","width":-1}]"""))
                .map { Triple(it.path, it.keyword, it.message) }
        )
        assertEquals(
            listOf(Triple("/0/kind", "anyOf", """expected one of ["text","image"]""")),
            validator.errors(value("""[{"kind":"video"}]"""))
                .map { Triple(it.path, it.keyword, it.message) }
        )
    }

    @Test
    fun `escapes ~ and slash in paths as JSON Pointer does`() {
        assertEquals(
            listOf(Triple("/a~1b~0c", "type", "expected string, got integer")),
            errors(
                """{"type":"object","properties":{"a/b~c":{"type":"string"}}}""",
                """{"a/b~c":1}"""
            )
        )
    }

    @Test
    fun `follows ref into the referenced schema`() {
        val schema = """
            {"${'$'}defs":{"point":{"type":"object","properties":{"x":{"type":"number"}},"required":["x"]}},
             "type":"array","items":{"${'$'}ref":"#/${'$'}defs/point"}}
        """.trimIndent()
        assertEquals(
            listOf(Triple("/1/x", "required", "missing")),
            errors(schema, """[{"x":1},{}]""")
        )
    }

    @Test
    fun `compiles a recursive schema`() {
        val schema = """
            {"type":"object","properties":{"value":{"type":"number"},
             "children":{"type":"array","items":{"${'$'}ref":"#"}}},"required":["value"]}
        """.trimIndent()
        assertEquals(
            listOf(Triple("/children/0/children/0/value", "required", "missing")),
            errors(schema, """{"value":1,"children":[{"value":2,"children":[{}]}]}""")
        )
    }

    @Test
    fun `applies a relative root id once when resolving local references`() {
        val schema = """
            {"${'$'}id":"schemas/tool.json","type":"object",
             "properties":{"count":{"${'$'}ref":"#/${'$'}defs/count"}},
             "required":["count"],"${'$'}defs":{"count":{"type":"integer"}}}
        """.trimIndent()
        val validator = validator(schema)
        assertTrue(validator.check(value("""{"count":2}""")))
        assertEquals(
            listOf(Triple("/count", "type", "expected integer, got string")),
            validator.errors(value("""{"count":"a"}""")).map {
                Triple(it.path, it.keyword, it.message)
            }
        )
    }

    @Test
    fun `throws for a ref that names nothing and rejects only its place as configured`() {
        val dangling = """{"type":"object","properties":{"t":{"${'$'}ref":"#/definitions/Missing"}}}"""
        assertFailsWith<IllegalArgumentException> { validator(dangling) }

        val rejecting = SchemaValidator(
            schema(dangling),
            unresolvedRefs = UnresolvedRefs.REJECT
        )
        assertTrue(rejecting.check(value("{}")))
        assertFalse(rejecting.check(value("""{"t":1}""")))
        assertEquals(
            listOf(Triple("/t", "${'$'}ref", "unresolved reference #/definitions/Missing")),
            rejecting.errors(value("""{"t":1}""")).map { Triple(it.path, it.keyword, it.message) }
        )
    }

    @Test
    fun `leaves an unresolved ref value uncoerced`() {
        val dangling = """{"type":"object","properties":{"t":{"${'$'}ref":"#/definitions/Missing"}}}"""
        val validator = SchemaValidator(
            schema(dangling),
            unresolvedRefs = UnresolvedRefs.REJECT
        )
        val result = validator.parse(value("""{"t":"1"}"""))
        assertEquals(
            listOf(Triple("/t", "${'$'}ref", "unresolved reference #/definitions/Missing")),
            (result as SchemaParseResult.Invalid).errors.map {
                Triple(it.path, it.keyword, it.message)
            }
        )
    }

    @Test
    fun `throws for a malformed schema`() {
        assertFailsWith<IllegalArgumentException> { validator("""{"properties":{"a":1}}""") }
    }

    @Test
    fun `treats format as an annotation unless formats are given`() {
        val schema = """{"type":"string","format":"email"}"""
        assertTrue(
            SchemaValidator(schema(schema), formats = emptyMap()).check(value("\"not an email\""))
        )
        assertFalse(validator(schema).check(value("\"not an email\"")))
        assertTrue(validator(schema).check(value("\"a@example.com\"")))
    }

    @Test
    fun `checks boolean schemas`() {
        assertTrue(validator("true").check(value("""{"anything":1}""")))
        assertFalse(validator("false").check(value("null")))
        assertEquals(
            listOf(Triple("", "false", "no value is allowed here")),
            errors("false", "1")
        )
    }

    @Test
    fun `checks numeric and string bounds and patterns`() {
        val schema = """{"type":"integer","minimum":1,"exclusiveMaximum":5,"multipleOf":2}"""
        assertTrue(validator(schema).check(value("2")))
        assertFalse(validator(schema).check(value("3")))
        assertEquals(
            listOf(Triple("", "multipleOf", "expected a multiple of 2")),
            errors(schema, "3")
        )
        assertEquals(
            listOf(Triple("", "pattern", "expected to match ^a+$")),
            errors("""{"type":"string","pattern":"^a+$"}""", "\"ab\"")
        )
    }

    @Test
    fun `counts Unicode code points for string lengths`() {
        assertTrue(validator("""{"type":"string","maxLength":1}""").check(value("\"😀\"")))
        assertFalse(validator("""{"type":"string","minLength":2}""").check(value("\"😀\"")))
        assertTrue(validator("""{"type":"string","minLength":2}""").check(value("\"\\ud800A\"")))
    }

    @Test
    fun `lists at most 20 enum values`() {
        val values = (0 until 25).joinToString(",") { "\"v$it\"" }
        val error = validator("""{"enum":[$values]}""").errors(value("\"missing\"")).single()
        assertTrue(error.message.startsWith("expected one of [\"v0\""), error.message)
        assertTrue(error.message.endsWith(", ... and 5 more]"), error.message)
        assertEquals(
            listOf(Triple("", "enum", """expected one of ["a","b"]""")),
            errors("""{"enum":["a","b"]}""", "\"c\"")
        )
    }

    @Test
    fun `reports const and contains failures with pi messages`() {
        assertEquals(
            listOf(Triple("", "const", "expected 1")),
            errors("""{"const":1}""", "2")
        )
        assertEquals(
            listOf(Triple("", "contains", "expected at least 2 matching items")),
            errors("""{"type":"array","contains":{"type":"number"},"minContains":2}""", "[\"a\"]")
        )
        assertEquals(
            listOf(Triple("", "contains", "expected at most 0 matching items")),
            errors(
                """{"type":"array","contains":{"type":"string"},"minContains":0,"maxContains":0}""",
                "[\"a\"]"
            )
        )
    }

    @Test
    fun `blames a oneOf failure on the branch that declares most properties`() {
        val schema = """
            {"type":"object","properties":{"where":{"${'$'}ref":"#/${'$'}defs/F"}},
             "${'$'}defs":{"F":{"oneOf":[
               {"type":"object","properties":{"field":{"type":"string"},"eq":{"type":"string"}},
                "required":["field","eq"],"additionalProperties":false},
               {"type":"object","properties":{"and":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/F"}}},
                "required":["and"],"additionalProperties":false}]}}}
        """.trimIndent()
        assertEquals(
            listOf(
                Triple("/where/and/0/eq", "type", "expected string, got integer"),
                Triple("/where/and/0/x", "additionalProperties", "unknown field")
            ),
            errors(schema, """{"where":{"and":[{"field":"a","eq":1,"x":2}]}}""")
        )
    }

    @Test
    fun `explains the same referenced object at each path`() {
        val schema = """
            {"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/P"},"b":{"${'$'}ref":"#/${'$'}defs/P"}},
             "${'$'}defs":{"P":{"type":"object","properties":{"n":{"type":"integer"}}}}}
        """.trimIndent()
        assertEquals(
            listOf(
                Triple("/a/n", "type", "expected integer, got string"),
                Triple("/b/n", "type", "expected integer, got string")
            ),
            errors(schema, """{"a":{"n":"x"},"b":{"n":"x"}}""")
        )
    }
}
