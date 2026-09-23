package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsNumbersTest {
    @Test
    fun `jsParseNumberOrNull matches JS Number semantics`() {
        assertEquals(0.0, jsParseNumberOrNull(""))
        assertEquals(0.0, jsParseNumberOrNull("  "))
        // JS whitespace trims (including NBSP and BOM); NEL (\u0085) does not.
        assertEquals(5.0, jsParseNumberOrNull("\u00A0\uFEFF 5 \t"))
        assertNull(jsParseNumberOrNull("\u00855"))
        assertEquals(5.0, jsParseNumberOrNull("5"))
        assertEquals(-5.0, jsParseNumberOrNull("-5"))
        assertEquals(5.0, jsParseNumberOrNull("+5"))
        assertEquals(0.5, jsParseNumberOrNull(".5"))
        assertEquals(5.0, jsParseNumberOrNull("5."))
        assertEquals(100.0, jsParseNumberOrNull("1e2"))
        assertEquals(-0.05, jsParseNumberOrNull("-.5e-1"))
        assertTrue(jsParseNumberOrNull("1e999")!!.isInfinite())
        // Radix literals are unsigned only.
        assertEquals(16.0, jsParseNumberOrNull("0x10"))
        assertEquals(15.0, jsParseNumberOrNull("0o17"))
        assertEquals(5.0, jsParseNumberOrNull("0b101"))
        assertNull(jsParseNumberOrNull("-0x10"))
        assertNull(jsParseNumberOrNull("+0b11"))
        assertNull(jsParseNumberOrNull("0x"))
        assertNull(jsParseNumberOrNull("0b12"))
        assertTrue(jsParseNumberOrNull("Infinity")!!.isInfinite())
        assertTrue(jsParseNumberOrNull("-Infinity")!!.isInfinite())
        assertTrue(jsParseNumberOrNull("+Infinity")!!.isInfinite())
        assertNull(jsParseNumberOrNull("NaN"))
        // Non-literals and Java-only parser forms are NaN.
        assertNull(jsParseNumberOrNull("12px"))
        assertNull(jsParseNumberOrNull("5f"))
        assertNull(jsParseNumberOrNull("5d"))
        assertNull(jsParseNumberOrNull("0x1.8p1"))
        assertNull(jsParseNumberOrNull("1_000"))
        assertNull(jsParseNumberOrNull("1.5.5"))
    }

    @Test
    fun `jsParseFloatOrNull matches JS parseFloat prefix semantics`() {
        assertEquals(42.0, jsParseFloatOrNull("42px"))
        assertEquals(42.0, jsParseFloatOrNull("  42  "))
        assertEquals(-1.0, jsParseFloatOrNull("-1x"))
        assertEquals(2.5, jsParseFloatOrNull("2.5 sec"))
        assertEquals(100.0, jsParseFloatOrNull("1e2foo"))
        // The longest valid prefix: "1.e2" keeps its exponent; "0x10" stops at 0.
        assertEquals(100.0, jsParseFloatOrNull("1.e2"))
        assertEquals(0.0, jsParseFloatOrNull("0x10"))
        assertEquals(1.0, jsParseFloatOrNull("1e"))
        assertEquals(0.5, jsParseFloatOrNull(".5"))
        assertEquals(-0.5, jsParseFloatOrNull("-.5"))
        assertEquals(3.0, jsParseFloatOrNull("+3"))
        // JS: parseFloat("Infinity") is Infinity, like the other literals.
        assertTrue(jsParseFloatOrNull("Infinity")!!.isInfinite())
        assertTrue(jsParseFloatOrNull("-Infinity")!!.isInfinite())
        // No numeric prefix after JS trim is NaN.
        assertNull(jsParseFloatOrNull("soon"))
        assertNull(jsParseFloatOrNull(""))
        assertNull(jsParseFloatOrNull("e5"))
        assertNull(jsParseFloatOrNull("x42"))
        assertNull(jsParseFloatOrNull("NaN"))
    }

    @Test
    fun `jsNumber renders like JS string conversion`() {
        assertEquals("5", jsNumber(5.0))
        assertEquals("5.5", jsNumber(5.5))
        assertEquals("1000", jsNumber(1e3))
        assertEquals("null", jsNumber(null))
        assertEquals("Infinity", jsNumber(Double.POSITIVE_INFINITY))
    }
}
