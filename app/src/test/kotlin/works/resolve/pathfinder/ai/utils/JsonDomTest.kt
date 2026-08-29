package works.resolve.pathfinder.ai.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Semantics of the shared JSON-DOM access surface: lenient reads accept any
 * primitive's content, strict reads require the matching primitive kind, and
 * codec reads throw. kotlinx numeric semantics are the standard: quoted
 * numerals parse, floats do not coerce to int.
 */
class JsonDomTest {

    private val obj: JsonObject = Json.parseToJsonElement(
        """
        {
          "s": "text", "q": "12", "n": 12, "f": 7.9, "b": true,
          "null": null,
          "numString": "1.5", "empty": "",
          "obj": {"inner": "v"}, "arr": [1, 2]
        }
        """,
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
        assertEquals(7.9, obj.double("f"))
        assertEquals(true, obj.boolean("b"))
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
        // A quoted numeral is still a string primitive; strictness is about
        // primitive kind, not content.
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
    }

    @Test
    fun numberOrNullIsFiniteNumericDouble() {
        assertEquals(7.9, obj["f"].numberOrNull())
        assertNull(obj["numString"].numberOrNull()) // string-encoded numbers rejected
        assertNull(obj["s"].numberOrNull())    }

    @Test
    fun codecReadsThrowTheCallersException() {
        class CodecError(val field: String) : Exception("bad $field")

        assertEquals("text", obj.requireString("s") { CodecError(it) })
        assertFailsWith<CodecError> { obj.requireString("n") { CodecError(it) } }
        assertFailsWith<CodecError> { obj.requireString("missing") { CodecError(it) } }
        assertEquals(12, obj.requireInt("n") { CodecError(it) })
        assertFailsWith<CodecError> { obj.requireLong("q") { CodecError(it) } }
        assertEquals(7.9, obj.requireDouble("f") { CodecError(it) })
        assertEquals(true, obj.requireBoolean("b") { CodecError(it) })
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
