package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JsonDomTest {

    private val obj: JsonObject = Json.parseToJsonElement(
        """
        {
          "s": "text", "q": "12", "qb": "true", "n": 12, "f": 7.9, "b": true,
          "null": null,
          "numString": "1.5", "empty": "",
          "obj": {"inner": "v"}, "arr": [1, 2]
        }
        """
    ) as JsonObject

    @Test
    fun lenientStrReadsAnyPrimitiveAndTreatsNullAsAbsent() {
        assertEquals("text", obj.str("s"))
        assertEquals("12", obj.str("q"))
        assertEquals("true", obj.str("b"))
        assertNull(obj.str("null"))
        assertNull(obj.str("missing"))
        assertNull((null as JsonObject?).str("s"))
    }

    @Test
    fun lenientIntUsesKotlinxSemantics() {
        assertEquals(12, obj.int("n"))
        assertEquals(12, obj.int("q")) // quoted numerals accepted
        assertNull(obj.int("f")) // floats rejected for int reads
        assertNull(obj.int("null"))
        assertNull(obj.int("missing"))
        assertNull(obj.int("s"))
    }

    @Test
    fun lenientNumericsAndBoolean() {
        assertEquals(12L, obj.long("n"))
        assertEquals(12L, obj.long("q")) // quoted numerals accepted
        assertEquals(7.9, obj.double("f"))
        assertEquals(true, obj.boolean("b"))
        assertEquals(true, obj.boolean("qb")) // quoted booleans accepted
        assertNull(obj.boolean("s"))
    }

    @Test
    fun structureReadsReturnKindOrNull() {
        assertEquals("v", obj.obj("obj")?.str("inner"))
        assertEquals(2, obj.arr("arr")?.size)
        assertNull(obj.obj("arr"))
        assertNull(obj.arr("s"))
        assertNull(obj.obj("missing"))
    }

    @Test
    fun strOrNullMatchesLenientStrOnElements() {
        assertEquals("text", obj["s"].strOrNull())
        assertNull(obj["null"].strOrNull())
        assertNull(obj["missing"].strOrNull())
    }

    @Test
    fun strictStringRequiresStringPrimitive() {
        assertEquals("text", obj.string("s"))
        assertEquals("", obj.string("empty")) // empty string is a valid string primitive
        assertNull(obj.string("n"))
        assertNull(obj.string("b"))
        assertNull(obj.string("null"))
        assertNull(obj.string("missing"))
        assertEquals("1.5", obj["numString"].stringOrNull())
        assertNull(obj["n"].stringOrNull())
    }

    @Test
    fun strictStringAlsoAcceptsQuotedNumeralsAsStrings() {
        // Strictness is about primitive kind, not content.
        assertEquals("12", obj.string("q"))
    }

    @Test
    fun strictNumericsRejectStringEncoding() {
        assertNull(obj.strictInt("q"))
        assertEquals(12, obj.strictInt("n"))
        assertNull(obj.strictDouble("numString"))
        assertEquals(7.9, obj.strictDouble("f"))
        assertNull(obj.strictLong("null"))
        assertTrue(obj.strictBoolean("b") == true)
        assertNull(obj.strictBoolean("q"))
        assertNull(obj.strictBoolean("qb"))
    }

    @Test
    fun elementFormStrictReadsMirrorTypeof() {
        assertEquals(7.9, obj["f"].strictDoubleOrNull())
        assertEquals(12.0, obj["n"].strictDoubleOrNull())
        assertNull(obj["q"].strictDoubleOrNull()) // quoted numerals are strings
        assertNull(obj["null"].strictDoubleOrNull()) // typeof null is "object"
        assertNull(obj["obj"].strictDoubleOrNull())
        assertNull((null as JsonElement?).strictDoubleOrNull())
        assertEquals(true, obj["b"].strictBooleanOrNull())
        assertNull(obj["qb"].strictBooleanOrNull())
        assertNull(obj["null"].strictBooleanOrNull())
    }

    @Test
    fun strictDoubleOrNullAppliesNoFiniteFilter() {
        // `typeof x === "number"` admits the non-finite literals a streaming
        // parse can hold; finiteness, when the upstream site needs it, is a
        // separate guard.
        assertEquals(
            Double.POSITIVE_INFINITY,
            JsonUnquotedLiteral("Infinity").strictDoubleOrNull()
        )
        assertEquals(
            Double.NEGATIVE_INFINITY,
            JsonUnquotedLiteral("-Infinity").strictDoubleOrNull()
        )
        val nan = JsonUnquotedLiteral("NaN").strictDoubleOrNull()
        assertTrue(nan != null && nan.isNaN())
    }

    @Test
    fun jsonEqualsUsesJsStrictEquality() {
        fun parse(text: String) = Json.parseToJsonElement(text)

        // Numeric spelling compares by parsed value, unlike kotlinx `==`.
        assertTrue(jsonEquals(parse("5"), parse("5.0")))
        assertTrue(jsonEquals(parse("1e3"), parse("1000")))
        assertTrue(jsonEquals(parse("-0"), parse("0")))
        // Kind mismatches never compare equal.
        assertFalse(jsonEquals(parse("\"5\""), parse("5")))
        assertFalse(jsonEquals(parse("true"), parse("\"true\"")))
        assertFalse(jsonEquals(parse("0"), parse("false")))
        assertFalse(jsonEquals(parse("null"), parse("\"null\"")))
        // NaN equals nothing, like JS `===`.
        assertFalse(jsonEquals(JsonUnquotedLiteral("NaN"), JsonUnquotedLiteral("NaN")))
        assertTrue(
            jsonEquals(JsonUnquotedLiteral("Infinity"), JsonUnquotedLiteral("Infinity"))
        )
    }

    @Test
    fun jsonEqualsComparesStructuresRecursively() {
        fun parse(text: String) = Json.parseToJsonElement(text)

        assertTrue(jsonEquals(parse("{}"), parse("{}")))
        assertTrue(jsonEquals(parse("[]"), parse("[]")))
        assertTrue(jsonEquals(parse("null"), parse("null")))
        assertTrue(jsonEquals(parse("[1, {\"a\": \"x\"}]"), parse("[1.0, {\"a\": \"x\"}]")))
        // Object key order is irrelevant; missing keys and length are not.
        assertTrue(jsonEquals(parse("{\"a\": 1, \"b\": 2}"), parse("{\"b\": 2, \"a\": 1}")))
        assertFalse(
            jsonEquals(parse("{\"a\": 1, \"b\": 2}"), parse("{\"a\": 1, \"b\": 2, \"c\": 3}"))
        )
        assertFalse(jsonEquals(parse("{\"a\": 1, \"b\": 2}"), parse("{\"a\": 1, \"b\": 3}")))
        assertFalse(jsonEquals(parse("[1, 2]"), parse("[2, 1]")))
        assertFalse(jsonEquals(parse("[1, 2]"), parse("[1, 2, 3]")))
        assertFalse(jsonEquals(parse("[1]"), parse("{}")))
    }

    @Test
    fun codecReadsThrowTheCallersException() {
        class CodecError(val field: String) : Exception("bad $field")

        assertEquals("text", obj.requireString("s") { CodecError(it) })
        assertFailsWith<CodecError> { obj.requireString("n") { CodecError(it) } }
        assertFailsWith<CodecError> { obj.requireString("missing") { CodecError(it) } }
    }

    @Test
    fun lenientJsonIgnoresUnknownKeys() {
        @kotlinx.serialization.Serializable
        data class D(val s: String)
        assertEquals(D("x"), lenientJson.decodeFromString("""{"s":"x","extra":1}"""))
        assertFalse(buildJsonObject { put("a", 1) }.isEmpty())
        assertTrue(Json.parseToJsonElement("{}") is JsonObject)
    }
}
