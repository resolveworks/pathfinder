package works.resolve.pathfinder.ai.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive

/**
 * Twin of pi's `packages/ai/src/utils/json-parse.ts`. The partial parser
 * below is a vendored port of the `partial-json` package (0.1.7) in its
 * Allow.ALL mode, pi's only mode.
 */

private val STRICT_JSON: Json = Json

/** JSON.parse parity: kotlinx accepts loose number literals ("1e"), so
 * unquoted primitives are validated against the JSON number grammar. */
private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

private fun jsonParse(text: String): JsonElement {
    val parsed = STRICT_JSON.parseToJsonElement(text)
    parsed.checkStrictLiterals()
    return parsed
}

private fun JsonElement.checkStrictLiterals() {
    when (this) {
        is JsonObject -> values.forEach { it.checkStrictLiterals() }

        is JsonArray -> forEach { it.checkStrictLiterals() }

        is JsonPrimitive ->
            if (!isString &&
                this !is JsonNull &&
                content != "true" &&
                content != "false" &&
                !JSON_NUMBER.matches(content)
            ) {
                throw IllegalArgumentException("Invalid JSON literal: $content")
            }

        else -> {}
    }
}

private class PartialJsonException(message: String) : Exception(message)

private val VALID_JSON_ESCAPES = setOf('"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u')

private fun isControlCharacter(char: Char): Boolean = char.code in 0x00..0x1f

private fun escapeControlCharacter(char: Char): String = when (char) {
    '\b' -> "\\b"
    '\u000C' -> "\\f"
    '\n' -> "\\n"
    '\r' -> "\\r"
    '\t' -> "\\t"
    else -> "\\u" + char.code.toString(16).padStart(4, '0')
}

/**
 * Repairs malformed JSON string literals by:
 * - escaping raw control characters inside strings
 * - doubling backslashes before invalid escape characters
 */
fun repairJson(json: String): String {
    val repaired = StringBuilder()
    var inString = false

    var index = 0
    while (index < json.length) {
        val char = json[index]

        if (!inString) {
            repaired.append(char)
            if (char == '"') {
                inString = true
            }
            index++
            continue
        }

        if (char == '"') {
            repaired.append(char)
            inString = false
            index++
            continue
        }

        if (char == '\\') {
            val nextChar = json.getOrNull(index + 1)
            if (nextChar == null) {
                repaired.append("\\\\")
                index++
                continue
            }

            if (nextChar == 'u') {
                val hexEnd = minOf(index + 6, json.length)
                val unicodeDigits = json.substring(index + 2, hexEnd)
                if (unicodeDigits.length == 4 && unicodeDigits.all { it.isHexDigit() }) {
                    repaired.append("\\u").append(unicodeDigits)
                    index += 6
                    continue
                }
            }

            if (nextChar in VALID_JSON_ESCAPES) {
                repaired.append('\\').append(nextChar)
                index += 2
                continue
            }

            repaired.append("\\\\")
            index++
            continue
        }

        repaired.append(if (isControlCharacter(char)) escapeControlCharacter(char) else char)
        index++
    }

    return repaired.toString()
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

fun parseJsonWithRepair(json: String): JsonElement = try {
    jsonParse(json)
} catch (error: Exception) {
    val repairedJson = repairJson(json)
    if (repairedJson != json) {
        jsonParse(repairedJson)
    } else {
        throw error
    }
}

/**
 * Attempts to parse potentially incomplete JSON during streaming.
 * Always returns a valid object, even if the JSON is incomplete.
 */
inline fun <reified T : JsonElement> parseStreamingJson(partialJson: String?): T {
    if (partialJson == null || trimJsWhitespace(partialJson).isEmpty()) {
        return JsonObject(emptyMap()) as T
    }

    return try {
        parseJsonWithRepair(partialJson) as T
    } catch (_: Exception) {
        try {
            partialJsonParse(partialJson) as T
        } catch (_: Exception) {
            try {
                partialJsonParse(repairJson(partialJson)) as T
            } catch (_: Exception) {
                JsonObject(emptyMap()) as T
            }
        }
    }
}

@PublishedApi
internal fun partialJsonParse(jsonString: String): JsonElement {
    // partial-json's `parse`: JS-trim once, reject the empty result, parse the
    // trimmed input.
    val trimmed = trimJsWhitespace(jsonString)
    if (trimmed.isEmpty()) {
        throw PartialJsonException("$jsonString is empty")
    }
    return PartialJsonParser(trimmed).parseTopLevel()
}

private class PartialJsonParser(private val json: String) {
    private var index = 0

    fun parseTopLevel(): JsonElement = parseAny()

    private fun failPartial(message: String): Nothing =
        throw PartialJsonException("$message at position $index")

    /** JS `substring`: indices clamped to the string, swapped when reversed. */
    private fun jsSubstring(begin: Int, end: Int): String {
        val from = begin.coerceIn(0, json.length)
        val to = end.coerceIn(0, json.length)
        return if (from <= to) json.substring(from, to) else json.substring(to, from)
    }

    private fun charAt(i: Int): Char? = json.getOrNull(i)

    private fun skipBlank() {
        while (index < json.length && json[index] in " \n\r\t") index++
    }

    /** The whole word, or a strict prefix of it ending the input. [minPrefix]
     * mirrors upstream's `1 < length - index` guard on "-Infinity": a lone
     * "-" must fall through to number parsing. */
    private fun literal(word: String, minPrefix: Int = 1): Boolean {
        val remaining = json.length - index
        if (remaining >= word.length) return json.regionMatches(index, word, 0, word.length)
        return remaining >= minPrefix && word.startsWith(json.substring(index))
    }

    private fun parseAny(): JsonElement {
        skipBlank()
        if (index >= json.length) failPartial("Unexpected end of input")
        return when {
            json[index] == '"' -> JsonPrimitive(parseStr())

            json[index] == '{' -> parseObj()

            json[index] == '[' -> parseArr()

            literal("null") -> {
                index += 4
                JsonNull
            }

            literal("true") -> {
                index += 4
                JsonPrimitive(true)
            }

            literal("false") -> {
                index += 5
                JsonPrimitive(false)
            }

            literal("Infinity") -> {
                index += 8
                JsonUnquotedLiteral("Infinity")
            }

            literal("-Infinity", minPrefix = 2) -> {
                index += 9
                JsonUnquotedLiteral("-Infinity")
            }

            literal("NaN") -> {
                index += 3
                JsonUnquotedLiteral("NaN")
            }

            else -> parseNum()
        }
    }

    private fun parseStr(): String {
        val start = index
        var escape = false
        index++ // skip initial quote
        while (index < json.length && (json[index] != '"' || (escape && json[index - 1] == '\\'))) {
            escape = if (json[index] == '\\') !escape else false
            index++
        }
        if (charAt(index) == '"') {
            index++
            return strictString(jsSubstring(start, index - if (escape) 1 else 0))
        }
        // Allow.STR: close the unterminated literal; an invalid escape falls
        // back to the last backslash anywhere in the input.
        val openEnd = index - if (escape) 1 else 0
        return try {
            strictString(jsSubstring(start, openEnd) + "\"")
        } catch (_: Exception) {
            strictString(jsSubstring(start, json.lastIndexOf('\\')) + "\"")
        }
    }

    private fun strictString(literal: String): String = jsonParse(literal).jsonPrimitive.content

    private fun parseObj(): JsonObject {
        index++ // skip initial brace
        skipBlank()
        val obj = LinkedHashMap<String, JsonElement>()
        try {
            while (charAt(index) != '}') {
                skipBlank()
                if (index >= json.length) return JsonObject(obj) // Allow.OBJ
                val key = parseStr()
                skipBlank()
                index++ // skip colon
                try {
                    obj[key] = parseAny()
                } catch (_: Exception) {
                    return JsonObject(obj) // Allow.OBJ
                }
                skipBlank()
                if (charAt(index) == ',') index++ // skip comma
            }
        } catch (_: Exception) {
            return JsonObject(obj) // Allow.OBJ
        }
        index++ // skip final brace
        return JsonObject(obj)
    }

    private fun parseArr(): JsonArray {
        index++ // skip initial bracket
        val arr = mutableListOf<JsonElement>()
        try {
            while (charAt(index) != ']') {
                arr.add(parseAny())
                skipBlank()
                if (charAt(index) == ',') index++ // skip comma
            }
        } catch (_: Exception) {
            return JsonArray(arr) // Allow.ARR
        }
        index++ // skip final bracket
        return JsonArray(arr)
    }

    private fun parseNum(): JsonElement {
        if (index == 0) {
            if (json == "-") failPartial("Not sure what '-' is")
            return try {
                jsonParse(json)
            } catch (error: Exception) {
                try {
                    // Allow.NUM: an incomplete exponent ("1e") drops it.
                    jsonParse(jsSubstring(0, json.lastIndexOf('e')))
                } catch (_: Exception) {
                    throw error
                }
            }
        }
        val start = index
        if (json[index] == '-') index++
        while (index < json.length && ",]}".indexOf(json[index]) == -1) index++
        val literal = json.substring(start, index)
        return try {
            jsonParse(literal)
        } catch (error: Exception) {
            if (literal == "-") failPartial("Not sure what '-' is")
            try {
                jsonParse(jsSubstring(start, json.lastIndexOf('e')))
            } catch (_: Exception) {
                throw error
            }
        }
    }
}
