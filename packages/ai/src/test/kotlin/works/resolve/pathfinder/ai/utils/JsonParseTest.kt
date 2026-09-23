package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsonParseTest {

    private fun parseObject(text: String?): JsonObject = parseStreamingJson(text)

    // --- repairJson: pi's escape repair over malformed string literals ---

    @Test
    fun repairEscapesRawControlCharactersInsideStrings() {
        // Escaped Kotlin "\t"/"\n" are the raw control characters a stream
        // carries; the raw-string expectations hold a literal backslash.
        assertEquals("""{"a": "x\ty"}""", repairJson("{\"a\": \"x\ty\"}"))
        assertEquals("""{"a": "x\ny"}""", repairJson("{\"a\": \"x\ny\"}"))
        assertEquals("""{"a": "x\u0001y"}""", repairJson("{\"a\": \"x\u0001y\"}"))
        // Control characters outside string literals are left alone.
        assertEquals("\u0001", repairJson("\u0001"))
    }

    @Test
    fun repairDoublesBackslashesBeforeInvalidEscapes() {
        assertEquals("""{"a": "x\\qy"}""", repairJson("""{"a": "x\qy"}"""))
        // Valid escapes pass through untouched, including \uXXXX.
        assertEquals("""{"a": "x\ny"}""", repairJson("""{"a": "x\ny"}"""))
        assertEquals("""{"a": "A"}""", repairJson("""{"a": "A"}"""))
        // 'u' is itself a valid escape, so a truncated \uXXXX passes the
        // escape through unchanged instead of doubling the backslash.
        assertEquals("""{"a": "x\u12"}""", repairJson("""{"a": "x\u12"}"""))
        // A trailing lone backslash is doubled.
        assertEquals("""{"a": "x\\""", repairJson("""{"a": "x\"""))
    }

    // --- parseJsonWithRepair: strict JSON.parse parity ---

    @Test
    fun parseWithRepairRetriesRepairedInputAndRethrowsWhenUnrepairable() {
        assertEquals(
            "x\ty",
            parseJsonWithRepair("{\"a\": \"x\ty\"}").jsonObject["a"]!!
                .jsonPrimitive.content
        )
        assertEquals(
            "x\\qy",
            parseJsonWithRepair("""{"a": "x\qy"}""").jsonObject["a"]!!
                .jsonPrimitive.content
        )
        // Nothing repairable: the original parse error propagates.
        assertFailsWith<Exception> { parseJsonWithRepair("nope") }
    }

    @Test
    fun parseWithRepairRejectsLooseLiteralsKotlinxWouldAccept() {
        // kotlinx accepts "1e" and the non-finite literals as unquoted
        // literals; JSON.parse does not.
        assertFailsWith<Exception> { parseJsonWithRepair("[1e]") }
        assertFailsWith<Exception> { parseJsonWithRepair("""{"a": 1e}""") }
        assertFailsWith<Exception> { parseJsonWithRepair("[Infinity]") }
    }

    @Test
    fun duplicateKeysKeepLastValueAtFirstPositionLikeJsObjects() {
        val obj = Json.parseToJsonElement("""{"a": 1, "b": 2, "a": 3}""").jsonObject
        // JS JSON.parse: the later assignment wins, the key keeps its first
        // insertion position.
        assertEquals("3", obj["a"]!!.jsonPrimitive.content)
        assertEquals(listOf("a", "b"), obj.keys.toList())
    }

    // --- partial parse: the vendored partial-json Allow.ALL behavior ---

    @Test
    fun partialObjectsKeepCompletedEntries() {
        assertEquals("{}", parseObject("{").toString())
        assertEquals("{}", parseObject("""{"a":""").toString())
        assertEquals("""{"a":1,"b":2}""", parseObject("""{"a": 1, "b": 2""").toString())
        assertEquals("""{"a":1}""", parseObject("""{"a": 1, "b": """).toString())
        assertEquals("""{"a":{"b":1}}""", parseObject("""{"a": {"b": 1""").toString())
    }

    @Test
    fun partialArraysKeepCompletedElements() {
        assertEquals("[]", parseStreamingJson<JsonArray>("[").toString())
        assertEquals("[1,2]", parseStreamingJson<JsonArray>("[1, 2").toString())
        assertEquals(
            """[1,2,{"a":"v"}]""",
            parseStreamingJson<JsonArray>("[1, 2, {\"a\": \"v").toString()
        )
    }

    @Test
    fun partialStringsCloseTheirLiteral() {
        assertEquals("abc", parseStreamingJson<JsonPrimitive>("\"abc").content)
        assertEquals("""{"a":"va"}""", parseObject("""{"a": "va""").toString())
    }

    @Test
    fun nonFiniteLiteralsSurviveAsUnquotedLiterals() {
        assertEquals("Infinity", parseObject("""{"a": Infinity""")["a"].toString())
        assertEquals("-Infinity", parseObject("""{"a": -Infinity""")["a"].toString())
        assertEquals("NaN", parseObject("""{"a": NaN""")["a"].toString())
    }

    @Test
    fun incompleteExponentsDropTheExponent() {
        assertEquals("1", partialJsonParse("1e").toString())
        assertEquals("""{"a":1}""", parseObject("""{"a": 1e""").toString())
        // Full-parse failure falls through to the partial parser.
        assertEquals("[1]", parseStreamingJson<JsonArray>("[1e]").toString())
    }

    @Test
    fun loneMinusIsNotAPartialNumber() {
        // partial-json: "Not sure what '-' is"; streaming falls back to {}.
        assertFailsWith<Exception> { partialJsonParse("-") }
        assertEquals("{}", parseObject("-").toString())
    }

    @Test
    fun blankInputParsesAsEmptyObject() {
        assertTrue(parseObject(null) is JsonObject)
        assertEquals("{}", parseObject(null).toString())
        assertEquals("{}", parseObject("").toString())
        assertEquals("{}", parseObject("   ").toString())
    }
}
