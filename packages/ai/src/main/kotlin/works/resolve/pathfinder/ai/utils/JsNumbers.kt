package works.resolve.pathfinder.ai.utils

/**
 * JS `Number.MAX_SAFE_INTEGER` (2^53 − 1): the largest integer a JS number
 * holds exactly. pi uses it both as a validation bound (settings token
 * budgets) and as a non-expiring epoch sentinel (OpenRouter keys).
 */
const val MAX_SAFE_INTEGER = 9007199254740991L

/**
 * Renders a number the way JavaScript template literals and `String(x)` do
 * (5, not 5.0); null renders as "null" like `${null}`.
 */
fun jsNumber(value: Double?): String = when {
    value == null -> "null"
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> value.toString()
}

/**
 * JS `Number(string)` semantics: trims JS whitespace, maps the empty result
 * to 0, accepts signed decimal/exponent literals, unsigned `0x`/`0o`/`0b`
 * radix literals, and `Infinity`/`NaN`; returns null where JS yields NaN.
 * JS rejects Java-only forms (`5f`, `5d`, hex-float, signed radix).
 *
 * Divergence, adversarial-only: radix literals longer than a double's
 * precision round per digit instead of once, so extreme literals can differ
 * in the last ulp from JS's single rounding.
 */
fun jsParseNumberOrNull(raw: String): Double? {
    val text = trimJsWhitespace(raw)
    if (text.isEmpty()) return 0.0
    when (text) {
        "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
        "-Infinity" -> return Double.NEGATIVE_INFINITY
        "NaN" -> return null
    }
    radixLiteral.matchEntire(text)?.let { match ->
        val literal = match.groupValues[1]
        val radix = when (literal[1].lowercaseChar()) {
            'x' -> 16
            'o' -> 8
            else -> 2
        }
        var value = 0.0
        for (char in literal.substring(2)) {
            value = value * radix + Character.digit(char, radix)
        }
        return value
    }
    return if (decimalLiteral.matches(text)) text.toDoubleOrNull() else null
}

/**
 * JS `Number.parseFloat` semantics: trims JS whitespace, then parses the
 * longest prefix that is a decimal literal — `42px` is 42, `0x10` is 0
 * (prefix `0`), `Infinity` is infinity, `1.e2` is 100 — returning null where
 * JS yields NaN (no numeric prefix).
 */
fun jsParseFloatOrNull(raw: String): Double? {
    val match = jsFloatPrefix.find(trimJsWhitespace(raw)) ?: return null
    return match.value.toDoubleOrNull()
}

/**
 * JS `String.prototype.trim()` — the WhiteSpace ∪ LineTerminator set,
 * which differs from Kotlin's `trim()` (misses U+FEFF, trims
 * U+001C-U+001F). Every ported site whose upstream trims — `Number()` /
 * `parseFloat()`, provider error bodies, SSE `data:` payloads, OAuth input
 * pastes — trims here, never with Kotlin `trim()`.
 */
fun trimJsWhitespace(raw: String): String = raw.trim(::isJsWhitespace)

/** JS `String.prototype.trimEnd()` over the same set as [trimJsWhitespace]. */
fun trimJsWhitespaceEnd(raw: String): String {
    var end = raw.length
    while (end > 0 && isJsWhitespace(raw[end - 1])) end--
    return raw.substring(0, end)
}

private fun isJsWhitespace(c: Char): Boolean =
    c in '\u0009'..'\u000D' || Character.isSpaceChar(c) || c == '\uFEFF'

private val radixLiteral = Regex("""(0[xX][0-9a-fA-F]+|0[oO][0-7]+|0[bB][01]+)""")

private val decimalLiteral = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

private val jsFloatPrefix = Regex("""^[+-]?(Infinity|(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?)""")
