package works.resolve.pathfinder.ai.utils

import java.util.Collections
import java.util.WeakHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall

/**
 * One compiled validator per schema, as pi's `getValidator` WeakMap: tool
 * schemas are long-lived, so entries disappear with their tools. pi-ai
 * compiles with `formats`, `coerce: true` and `unresolvedRefs: "reject"`.
 */
private val validatorCache = Collections.synchronizedMap(
    WeakHashMap<JsonElement, SchemaValidator>()
)

private fun getValidator(schema: JsonElement): SchemaValidator {
    synchronized(validatorCache) {
        validatorCache[schema]?.let { return it }
    }
    val validator = SchemaValidator(schema, SchemaFormats.checks, UnresolvedRefs.REJECT)
    synchronized(validatorCache) { validatorCache[schema] = validator }
    return validator
}

/** A pi-schema error path (a JSON Pointer) as the dotted path the model sees. */
private fun formatValidationPath(pointer: String): String {
    if (pointer.isEmpty()) return "root"
    return pointer
        .substring(1)
        .split("/")
        .joinToString(".") { it.replace("~1", "/").replace("~0", "~") }
}

/**
 * Validates parsed tool-call arguments against the tool's JSON Schema
 * `parameters`, coercing them first — the port of pi's
 * `validateToolArguments` (packages/ai/src/utils/validation.ts).
 *
 * @return the coerced, schema-valid arguments
 * @throws IllegalArgumentException with pi's multi-line message when
 * validation fails
 */
fun validateToolArguments(tool: Tool, toolCall: ToolCall): JsonObject {
    toolCall.argumentsError?.let {
        throw IllegalArgumentException(
            "Invalid arguments for tool \"${toolCall.name}\": $it"
        )
    }
    val schema = tool.parameters
    if (!isSchema(schema)) {
        throw IllegalArgumentException(
            "Tool \"${tool.name}\" parameters must be a JSON Schema object"
        )
    }
    val validator = getValidator(schema)
    // A copy, so tools that modify their arguments never modify the tool call in the transcript.
    return when (val result = validator.parse(structuredClone(toolCall.arguments))) {
        is SchemaParseResult.Valid ->
            result.value as? JsonObject
                ?: throw IllegalArgumentException(
                    "Tool \"${tool.name}\" parameters must describe the argument object"
                )

        is SchemaParseResult.Invalid -> {
            val errorLines = result.errors.joinToString("\n") {
                "  - ${formatValidationPath(it.path)}: ${it.message}"
            }
            val message = "Validation failed for tool \"${toolCall.name}\":\n$errorLines\n\n" +
                "Received arguments:\n${renderReceivedArguments(toolCall.arguments)}"
            throw IllegalArgumentException(message)
        }
    }
}

/** A JSON Schema is an object or a boolean, as pi's `JsonSchema` type. */
private fun isSchema(value: JsonElement): Boolean = value is JsonObject ||
    (value is JsonPrimitive && !value.isString && value.content in setOf("true", "false"))

/** The schema is never modified, but coercion may return the input; clone at the boundary. */
private fun structuredClone(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { structuredClone(it.value) })
    is JsonArray -> JsonArray(value.map(::structuredClone))
    else -> value
}

// --- Received-arguments echo (pi: JSON.stringify(arguments, null, 2)) ---

/** JSON.stringify formatting with JS number rendering, so "5.0" and "1e3" echo as 5 and 1000. */
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
                append(
                    value.strictDoubleOrNull()?.let { jsNumber(it) } ?: value.content
                )
            }
    }
}
