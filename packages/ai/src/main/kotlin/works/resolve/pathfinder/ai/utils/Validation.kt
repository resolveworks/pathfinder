package works.resolve.pathfinder.ai.utils

import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall

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
 * Validates parsed tool-call arguments against the tool's JSON Schema
 * `parameters`, coercing primitive mismatches before checking — the port of
 * pi's `validateToolArguments` (packages/ai/src/utils/validation.ts), which
 * the agent loop applies to every tool call.
 *
 * pi's pipeline is `normalizeOptionalNulls` → typebox `Value.Convert` →
 * JSON-Schema coercion → compiled-schema validation. `Value.Convert` only
 * acts on TypeBox-annotated schemas; pathfinder tool parameters are plain
 * JSON Schema, so the port is exactly the branch pi applies to those:
 * optional nulls are dropped, then AJV-style primitive coercion runs, then
 * the arguments must validate against the schema. Supported schemas are
 * limited to the keywords pi's tool schemas use — `type` (including type
 * arrays), `properties`, `required`, `items` (single or tuple), `enum`, and
 * `additionalProperties: true`; annotation keywords are ignored like in pi.
 * Any other constraint keyword throws rather than silently validating less
 * than pi would.
 *
 * @return the coerced, schema-valid arguments
 * @throws IllegalArgumentException with pi's multi-line message when
 * validation fails
 */
fun validateToolArguments(tool: Tool, toolCall: ToolCall): JsonObject {
    val schema =
        tool.parameters as? JsonObject
            ?: throw IllegalArgumentException(
                "Tool \"${tool.name}\" parameters must be a JSON Schema object"
            )
    requireSupportedSchema(tool.name, schema)
    val normalized = normalizeOptionalNulls(toolCall.arguments, schema)
    val coerced = coerceWithJsonSchema(normalized, schema) as JsonObject
    val errors = collectSchemaErrors(coerced, schema, path = "")
    if (errors.isEmpty()) return coerced
    val errorLines = errors.joinToString("\n") { "  - ${it.formattedPath}: ${it.message}" }
    throw IllegalArgumentException(
        "Validation failed for tool \"${toolCall.name}\":\n$errorLines\n\nReceived arguments:" +
            "\n${renderReceivedArguments(toolCall.arguments)}"
    )
}

// --- Schema support check (pi compiles the schema up front) ---

private val CONSTRAINT_KEYWORDS = setOf("type", "properties", "required", "items", "enum")

private val IGNORED_KEYWORDS =
    setOf(
        "description",
        "title",
        "default",
        "examples",
        // No registered formats in pi's typebox setup, so format is unconstrained there too.
        "format",
        "${'$'}defs",
        "definitions"
    )

private fun unsupportedKeyword(toolName: String, keyword: String): Nothing =
    throw IllegalArgumentException(
        "Unsupported schema keyword \"$keyword\" in tool \"$toolName\" parameters"
    )

private fun requireSupportedSchema(toolName: String, schema: JsonObject) {
    for (key in schema.keys) {
        if (key in CONSTRAINT_KEYWORDS || key in IGNORED_KEYWORDS) continue
        if (key == "additionalProperties" && schema.getValue(key) == JsonPrimitive(true)) continue
        unsupportedKeyword(toolName, key)
    }
    if (schemaTypes(schema) == null) unsupportedKeyword(toolName, "type")
    when (schema["enum"]) {
        null, is JsonArray -> {}
        else -> unsupportedKeyword(toolName, "enum")
    }
    (schema["required"] as? JsonArray)?.forEach {
        if (it !is JsonPrimitive || !it.isString) unsupportedKeyword(toolName, "required")
    }
    (schema["properties"] as? JsonObject)?.forEach { (_, propertySchema) ->
        if (propertySchema !is JsonObject) {
            unsupportedKeyword(toolName, "properties")
        } else {
            requireSupportedSchema(toolName, propertySchema)
        }
    }
    when (val items = schema["items"]) {
        null, is JsonArray, is JsonObject -> {}
        else -> unsupportedKeyword(toolName, "items")
    }
    (schema["items"] as? JsonArray)?.forEach {
        if (it !is JsonObject) {
            unsupportedKeyword(toolName, "items")
        } else {
            requireSupportedSchema(toolName, it)
        }
    }
    (schema["items"] as? JsonObject)?.let { requireSupportedSchema(toolName, it) }
}

/** Declared type names; null when `type` is present but malformed. */
private fun schemaTypes(schema: JsonObject): List<String>? = when (val type = schema["type"]) {
    null, is JsonNull -> emptyList()

    is JsonPrimitive -> if (type.isString) listOf(type.content) else null

    is JsonArray ->
        if (type.all { it is JsonPrimitive && it.isString }) {
            type.map { (it as JsonPrimitive).content }
        } else {
            null
        }

    else -> null
}

// --- JSON type matching (pi's matchesJsonType) ---

private fun matchesJsonType(value: JsonElement, type: String): Boolean = when (type) {
    "number" -> value.strictDoubleOrNull() != null
    "integer" -> value.strictDoubleOrNull()?.let { it.isFinite() && it == floor(it) } == true
    "boolean" -> value.strictBooleanOrNull() != null
    "string" -> value is JsonPrimitive && value.isString
    "null" -> value is JsonNull
    "array" -> value is JsonArray
    "object" -> value is JsonObject
    else -> false
}

// --- Primitive coercion (pi's coercePrimitiveByType, AJV-compatible rules) ---

private fun coercePrimitiveByType(value: JsonElement, type: String): JsonElement = when (type) {
    "number" -> when {
        value is JsonNull -> JsonPrimitive(0)

        value is JsonPrimitive && value.isString ->
            value.content.toJsNumberOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
                ?: value

        value.strictBooleanOrNull() == true -> JsonPrimitive(1)

        value.strictBooleanOrNull() == false -> JsonPrimitive(0)

        else -> value
    }

    "integer" -> when {
        value is JsonNull -> JsonPrimitive(0)

        value is JsonPrimitive && value.isString ->
            value.content.toJsNumberOrNull()
                ?.takeIf { it.isFinite() && it == floor(it) }
                ?.let { JsonPrimitive(it) }
                ?: value

        value.strictBooleanOrNull() == true -> JsonPrimitive(1)

        value.strictBooleanOrNull() == false -> JsonPrimitive(0)

        else -> value
    }

    "boolean" -> when {
        value is JsonNull -> JsonPrimitive(false)
        value is JsonPrimitive && value.isString && value.content == "true" -> JsonPrimitive(true)
        value is JsonPrimitive && value.isString && value.content == "false" -> JsonPrimitive(false)
        value.strictDoubleOrNull() == 1.0 -> JsonPrimitive(true)
        value.strictDoubleOrNull() == 0.0 -> JsonPrimitive(false)
        else -> value
    }

    "string" -> when {
        value is JsonNull -> JsonPrimitive("")
        value.strictDoubleOrNull() != null -> JsonPrimitive(jsNumber(value.strictDoubleOrNull()))
        value.strictBooleanOrNull() != null -> JsonPrimitive(value.strictBooleanOrNull().toString())
        else -> value
    }

    "null" -> when {
        value is JsonPrimitive && value.isString && value.content.isEmpty() -> JsonNull
        value.strictDoubleOrNull() == 0.0 -> JsonNull
        value.strictBooleanOrNull() == false -> JsonNull
        else -> value
    }

    else -> value
}

/**
 * JS `Number(string)` behind pi's `trim() !== ""` guard: trims whitespace
 * and accepts decimal, exponent, radix-prefixed (0x/0o/0b, unsigned only),
 * and signed Infinity literals; null when the string is blank or not a
 * numeric literal (JS NaN). Blank strings stay uncoerced like in pi.
 */
private fun String.toJsNumberOrNull(): Double? {
    val text = trim()
    if (text.isEmpty()) return null
    when {
        text == "Infinity" || text == "+Infinity" -> return Double.POSITIVE_INFINITY
        text == "-Infinity" -> return Double.NEGATIVE_INFINITY
    }
    val radix =
        if (text.length > 2 && text[0] == '0') {
            when (text[1]) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 0
            }
        } else {
            0
        }
    if (radix > 0) {
        var value = 0.0
        for (char in text.substring(2)) {
            val digit = Character.digit(char, radix)
            if (digit < 0) return null
            value = value * radix + digit
        }
        return value
    }
    return if (JS_DECIMAL.matches(text)) text.toDoubleOrNull() else null
}

private val JS_DECIMAL = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

// --- JSON-Schema-driven coercion (pi's coerceWithJsonSchema) ---

private fun coerceWithJsonSchema(value: JsonElement, schema: JsonObject): JsonElement {
    val types = schemaTypes(schema) ?: emptyList()
    var next = value
    val matchesUnionMember = types.size > 1 && types.any { matchesJsonType(next, it) }
    if (types.isNotEmpty() && !matchesUnionMember) {
        for (type in types) {
            val candidate = coercePrimitiveByType(next, type)
            if (candidate != next) {
                next = candidate
                break
            }
        }
    }
    if ("object" in types && next is JsonObject) next = coerceSchemaObject(next, schema)
    if ("array" in types && next is JsonArray) next = coerceSchemaArray(next, schema)
    return next
}

private fun coerceSchemaObject(value: JsonObject, schema: JsonObject): JsonObject {
    val properties = schema["properties"] as? JsonObject ?: return value
    val coerced = JsonObject(
        value.mapValues { (key, child) ->
            val propertySchema = properties[key]
            if (propertySchema is JsonObject) coerceWithJsonSchema(child, propertySchema) else child
        }
    )
    return if (coerced == value) value else coerced
}

private fun coerceSchemaArray(value: JsonArray, schema: JsonObject): JsonArray {
    val items = schema["items"]
    return when {
        items is JsonArray -> JsonArray(
            value.mapIndexed { index, element ->
                (items.getOrNull(index) as? JsonObject)
                    ?.let { coerceWithJsonSchema(element, it) }
                    ?: element
            }
        )

        items is JsonObject -> JsonArray(value.map { coerceWithJsonSchema(it, items) })

        else -> value
    }
}

// --- Optional-null normalization (pi's normalizeOptionalNulls) ---

private fun normalizeOptionalNulls(value: JsonElement, schema: JsonObject): JsonElement =
    when (value) {
        is JsonArray -> {
            val items = schema["items"]
            when {
                items is JsonArray -> JsonArray(
                    value.mapIndexed { index, element ->
                        (items.getOrNull(index) as? JsonObject)
                            ?.let { normalizeOptionalNulls(element, it) }
                            ?: element
                    }
                )

                items is JsonObject -> JsonArray(value.map { normalizeOptionalNulls(it, items) })

                else -> value
            }
        }

        is JsonObject -> {
            val properties = schema["properties"] as? JsonObject ?: return value
            val required =
                (schema["required"] as? JsonArray)
                    ?.map { (it as JsonPrimitive).content }
                    ?.toSet()
                    ?: emptySet()
            JsonObject(
                buildMap {
                    for ((key, child) in value) {
                        val propertySchema = properties[key]
                        if (propertySchema is JsonObject &&
                            child is JsonNull &&
                            key !in required &&
                            !schemaAcceptsNull(propertySchema)
                        ) {
                            continue
                        }
                        put(
                            key,
                            if (propertySchema is JsonObject) {
                                normalizeOptionalNulls(child, propertySchema)
                            } else {
                                child
                            }
                        )
                    }
                }
            )
        }

        else -> value
    }

/** Whether null already satisfies the schema (pi compiles the subschema and checks it). */
private fun schemaAcceptsNull(schema: JsonObject): Boolean =
    collectSchemaErrors(JsonNull, schema, path = "").isEmpty()

// --- Validation errors (pi's compiled-validator errors + en_US messages) ---

private class SchemaError(
    val keyword: String,
    val path: String,
    val message: String,
    firstMissing: String? = null
) {
    /** pi's formatValidationPath: required errors address the first missing property. */
    val formattedPath: String =
        if (keyword == "required" && firstMissing != null) {
            if (path.isEmpty()) firstMissing else "$path.$firstMissing"
        } else {
            path.ifEmpty { "root" }
        }
}

private fun collectSchemaErrors(
    value: JsonElement,
    schema: JsonObject,
    path: String
): List<SchemaError> {
    val errors = mutableListOf<SchemaError>()
    val types = schemaTypes(schema) ?: emptyList()
    if (types.isNotEmpty() && types.none { matchesJsonType(value, it) }) {
        errors.add(SchemaError("type", path, typeMessage(types)))
        return errors
    }
    val enum = schema["enum"] as? JsonArray
    if (enum != null && enum.none { jsonEquals(it, value) }) {
        errors.add(SchemaError("enum", path, "must be equal to one of the allowed values"))
    }
    when (value) {
        is JsonObject -> {
            val required =
                (schema["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }
                    ?: emptyList()
            val missing = required.filter { it !in value }
            if (missing.isNotEmpty()) {
                errors.add(
                    SchemaError(
                        keyword = "required",
                        path = path,
                        message = "must have required properties ${missing.joinToString(", ")}",
                        firstMissing = missing.first()
                    )
                )
            }
            val properties = schema["properties"] as? JsonObject
            for ((key, propertySchema) in properties.orEmpty()) {
                val child = value[key] ?: continue
                errors.addAll(
                    collectSchemaErrors(child, propertySchema as JsonObject, childPath(path, key))
                )
            }
        }

        is JsonArray -> {
            val items = schema["items"]
            when {
                items is JsonObject -> value.forEachIndexed { index, element ->
                    errors.addAll(collectSchemaErrors(element, items, childPath(path, "$index")))
                }

                items is JsonArray -> value.forEachIndexed { index, element ->
                    (items.getOrNull(index) as? JsonObject)?.let {
                        errors.addAll(collectSchemaErrors(element, it, childPath(path, "$index")))
                    }
                }
            }
        }

        else -> {}
    }
    return errors
}

private fun childPath(path: String, segment: String): String =
    if (path.isEmpty()) segment else "$path.$segment"

private fun typeMessage(types: List<String>): String = if (types.size ==
    1
) {
    "must be ${types.single()}"
} else {
    "must be either ${types.joinToString(" or ")}"
}

// --- Received-arguments echo (pi: JSON.stringify(arguments, null, 2)) ---

/**
 * JSON.stringify formatting with JS number-to-string rendering, so "5.0" and
 * "1e3" tokens echo as 5 and 1000; kotlinx would print the raw token or a
 * string primitive.
 */
private fun renderReceivedArguments(arguments: JsonObject): String =
    buildString { appendJsonLikeJs(arguments, depth = 0) }

private fun StringBuilder.appendJsonLikeJs(value: JsonElement, depth: Int) {
    when (value) {
        is JsonObject -> {
            if (value.isEmpty()) {
                append("{}")
                return
            }
            append("{\n")
            value.entries.forEachIndexed { index, (key, child) ->
                repeat(depth + 1) { append("  ") }
                append(JsonPrimitive(key)).append(": ")
                appendJsonLikeJs(child, depth + 1)
                if (index < value.size - 1) append(",")
                append("\n")
            }
            repeat(depth) { append("  ") }
            append("}")
        }

        is JsonArray -> {
            if (value.isEmpty()) {
                append("[]")
                return
            }
            append("[\n")
            value.forEachIndexed { index, child ->
                repeat(depth + 1) { append("  ") }
                appendJsonLikeJs(child, depth + 1)
                if (index < value.size - 1) append(",")
                append("\n")
            }
            repeat(depth) { append("  ") }
            append("]")
        }

        is JsonNull -> append("null")

        is JsonPrimitive ->
            if (value.isString) {
                append(value)
            } else if (value.strictBooleanOrNull() != null) {
                append(value.content)
            } else {
                append(value.content.toJsNumberOrNull()?.let { jsNumber(it) } ?: value.content)
            }
    }
}
