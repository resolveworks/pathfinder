package works.resolve.pathfinder.ai.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.GrammarFormat
import works.resolve.pathfinder.ai.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * pi's Tool.parameters is a TypeBox TSchema; this port's [Tool.parameters] is
 * a [JsonElement]. pi's rewriting mutates a `structuredClone` of the schema,
 * so this port rewrites a mutable deep copy of the JSON tree and freezes it
 * back afterwards.
 */

internal class UnsupportedStrictJsonSchemaError(message: String) : Exception(message)

/**
 * pi throws plain `Error` for constrained-sampling failures; this port throws
 * a domain [Exception] instead (never [kotlin.Error] for domain logic).
 * Messages match pi exactly.
 */
internal class ConstrainedSamplingError(message: String) : Exception(message)

internal val UNSUPPORTED_STRICT_SCHEMA_KEYS = listOf(
    "\$ref",
    "\$defs",
    "definitions",
    "allOf",
    "oneOf",
    "patternProperties",
    "dependentSchemas",
    "dependencies",
    "unevaluatedProperties",
    "propertyNames",
    "contains",
    "prefixItems",
    "not",
    "if",
    "then",
    "else"
)

/**
 * Mutable JSON-schema node: values are [MutableSchema] maps, [MutableList]s
 * (arrays), or [JsonElement] leaves.
 */
private typealias MutableSchema = LinkedHashMap<String, Any>

private fun isJsonSchemaObject(value: Any?): Boolean = value is LinkedHashMap<*, *>

private fun isStructuredSchema(schema: Any?): Boolean {
    if (!isJsonSchemaObject(schema)) return false
    schema as MutableSchema
    val type = schema["type"]
    val types = when (type) {
        is JsonPrimitive -> if (type.isString) listOf(type.content) else emptyList()
        is MutableList<*> -> type.mapNotNull { (it as? JsonElement).stringOrNull() }
        else -> emptyList()
    }
    return "object" in types ||
        "array" in types ||
        schema.containsKey("properties") ||
        schema.containsKey("items")
}

private fun schemaAllowsNull(schema: Any?): Boolean {
    if (!isJsonSchemaObject(schema)) return false
    schema as MutableSchema
    val type = schema["type"]
    if ((type as? JsonElement).stringOrNull() == "null") return true
    if (type is MutableList<*> &&
        type.any { (it as? JsonElement).stringOrNull() == "null" }
    ) {
        return true
    }
    val const = schema["const"]
    if (const is JsonPrimitive && const === JsonNull) return true
    val enum = schema["enum"]
    if (enum is MutableList<*> && enum.any { it is JsonPrimitive && it === JsonNull }) return true
    val anyOf = schema["anyOf"]
    return anyOf is MutableList<*> && anyOf.any { schemaAllowsNull(it) }
}

private fun toMutableNode(element: JsonElement): Any = when (element) {
    is JsonObject -> MutableSchema(element.size).apply {
        for ((key, value) in element) put(key, toMutableValue(value))
    }

    else -> toMutableValue(element)
}

private fun toMutableValue(element: JsonElement): Any = when (element) {
    is JsonObject -> toMutableNode(element)
    is JsonArray -> ArrayList<Any>(element.map { toMutableValue(it) })
    else -> element
}

private fun freezeNode(node: Any): JsonElement = when (node) {
    is LinkedHashMap<*, *> ->
        JsonObject(node.entries.associate { (key, value) -> key.toString() to freezeNode(value!!) })

    is MutableList<*> -> JsonArray(node.map { freezeNode(it!!) })

    is JsonElement -> node

    is String -> JsonPrimitive(node)

    else -> JsonPrimitive(node.toString())
}

private fun makeJsonSchemaNodeStrict(schema: Any?) {
    if (!isJsonSchemaObject(schema)) {
        throw UnsupportedStrictJsonSchemaError("boolean schemas are unsupported")
    }
    schema as MutableSchema
    for (key in UNSUPPORTED_STRICT_SCHEMA_KEYS) {
        if (schema.containsKey(key)) {
            throw UnsupportedStrictJsonSchemaError("$key schemas are unsupported")
        }
    }

    if (schema.containsKey("anyOf")) {
        val anyOf = schema["anyOf"]
        if (anyOf !is MutableList<*> || anyOf.isEmpty()) {
            throw UnsupportedStrictJsonSchemaError("anyOf must contain at least one schema")
        }
        for (variant in anyOf) {
            if (isStructuredSchema(variant)) {
                throw UnsupportedStrictJsonSchemaError("object and array unions are unsupported")
            }
            makeJsonSchemaNodeStrict(variant)
        }
    }

    if (schema.containsKey("items")) {
        val items = schema["items"]!!
        if (items is MutableList<*>) {
            throw UnsupportedStrictJsonSchemaError("tuple schemas are unsupported")
        }
        makeJsonSchemaNodeStrict(items)
    }

    val isObjectSchema = (schema["type"] as? JsonElement).stringOrNull() == "object"
    if (schema.containsKey("properties") && !isObjectSchema) {
        throw UnsupportedStrictJsonSchemaError("properties require type object")
    }
    if (!isObjectSchema) return
    val additionalProperties = schema["additionalProperties"]
    if (schema.containsKey("additionalProperties") &&
        !(additionalProperties is JsonPrimitive && additionalProperties.booleanOrNull == false)
    ) {
        throw UnsupportedStrictJsonSchemaError(
            "schema-valued or true additionalProperties is unsupported"
        )
    }
    if (schema.containsKey("properties") && !isJsonSchemaObject(schema["properties"])) {
        throw UnsupportedStrictJsonSchemaError("object properties must be a schema map")
    }
    val requiredValue = schema["required"]
    if (schema.containsKey("required")) {
        val requiredOk = requiredValue is MutableList<*> &&
            requiredValue.all { (it as? JsonElement).stringOrNull() != null }
        if (!requiredOk) {
            throw UnsupportedStrictJsonSchemaError("object required must be a string array")
        }
    }

    val properties = (schema["properties"] as? MutableSchema) ?: MutableSchema()
    val propertyNames = properties.keys.toList()
    val required = (requiredValue as? MutableList<*>)
        ?.mapNotNull { (it as? JsonElement).stringOrNull() }
        ?.toHashSet() ?: hashSetOf<String>()
    if (required.any { it !in propertyNames }) {
        throw UnsupportedStrictJsonSchemaError("required contains an unknown property")
    }
    for ((key, property) in properties.entries) {
        makeJsonSchemaNodeStrict(property)
        if (key !in required && !schemaAllowsNull(property)) {
            val nullSchema = MutableSchema(1).apply { put("type", JsonPrimitive("null")) }
            properties[key] =
                MutableSchema(1).apply {
                    put("anyOf", ArrayList<Any>(listOf(property, nullSchema)))
                }
        }
    }
    schema["required"] = ArrayList(propertyNames.map { JsonPrimitive(it) })
    schema["additionalProperties"] = JsonPrimitive(false)
}

/** Convert a tool schema to the strict subset expected by provider constrained sampling. */
fun makeStrictJsonSchema(schema: JsonElement): JsonObject {
    val cloned: Any = toMutableNode(schema)
    if (!isJsonSchemaObject(cloned)) {
        throw UnsupportedStrictJsonSchemaError("root schema must have type object")
    }
    makeJsonSchemaNodeStrict(cloned)
    if (((cloned as MutableSchema)["type"] as? JsonElement).stringOrNull() != "object") {
        throw UnsupportedStrictJsonSchemaError("root schema must have type object")
    }
    return freezeNode(cloned) as JsonObject
}

fun getJsonSchemaToolParameters(tool: Tool, strict: Boolean?): JsonElement =
    if (strict == true) makeStrictJsonSchema(tool.parameters) else tool.parameters

data class GrammarConstrainedSampling(
    val format: GrammarConstrainedFormat,
    val definition: String,
    val inputProperty: String
)

enum class GrammarConstrainedFormat { LARK, REGEX }

class GrammarToolInputJsonBuffer(
    var input: String = "",
    var started: Boolean = false,
    var closed: Boolean = false
)

fun getGrammarToolInput(toolName: String, arguments: JsonObject, inputProperty: String): String {
    val input = arguments[inputProperty].stringOrNull()
        ?: throw ConstrainedSamplingError(
            "Grammar tool call \"$toolName\" requires argument \"$inputProperty\" to be a string."
        )
    return input
}

fun appendGrammarToolInputJsonDelta(
    buffer: GrammarToolInputJsonBuffer,
    inputProperty: String,
    nextInput: String,
    close: Boolean
): String? {
    if (buffer.closed) {
        if (close && nextInput == buffer.input) return null
        throw ConstrainedSamplingError(
            "grammar tool input for property \"$inputProperty\" changed after it was closed"
        )
    }
    if (!nextInput.startsWith(buffer.input)) {
        throw ConstrainedSamplingError(
            "grammar tool input for property \"$inputProperty\" changed non-monotonically"
        )
    }

    val inputDelta = nextInput.substring(buffer.input.length)
    if (!close && inputDelta.isEmpty()) return null

    var delta = ""
    if (!buffer.started) {
        delta += "{${JsonPrimitive(inputProperty)}:\""
        buffer.started = true
    }
    delta += JsonPrimitive(inputDelta).toString().drop(1).dropLast(1)
    buffer.input = nextInput

    if (close) {
        delta += "\"}"
        buffer.closed = true
    }
    return delta
}

private fun inferGrammarInputProperty(tool: Tool): String {
    val schema = tool.parameters as? JsonObject
        ?: throw ConstrainedSamplingError(
            "grammar constrained sampling requires an object parameter schema"
        )
    if ((schema["type"] as? JsonElement).stringOrNull() != "object") {
        throw ConstrainedSamplingError(
            "grammar constrained sampling requires an object parameter schema"
        )
    }
    val required = schema["required"]
    val inputProperty = ((required as? JsonArray)?.singleOrNull() as? JsonElement).stringOrNull()
    if (inputProperty == null) {
        throw ConstrainedSamplingError(
            "grammar constrained sampling requires exactly one required string property"
        )
    }

    if (schema["properties"]?.let { (it as? JsonObject)?.get(inputProperty) } == null) {
        throw ConstrainedSamplingError(
            "grammar constrained sampling requires a properties entry for $inputProperty"
        )
    }
    val property = (schema["properties"] as JsonObject)[inputProperty] as? JsonObject
    if ((property?.get("type") as? JsonElement).stringOrNull() != "string") {
        throw ConstrainedSamplingError(
            "grammar constrained sampling property $inputProperty must have type string"
        )
    }
    return inputProperty
}

fun resolveJsonSchemaStrictSampling(tool: Tool, supportsStrictMode: Boolean): Boolean? {
    val config = tool.constrainedSampling
    if (config !is ConstrainedSamplingConfig.JsonSchema) return null

    if (supportsStrictMode) {
        try {
            makeStrictJsonSchema(tool.parameters)
            return true
        } catch (error: UnsupportedStrictJsonSchemaError) {
            if (config.strict != StrictJsonSchemaMode.REQUIRE) return null
            throw ConstrainedSamplingError(
                "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but ${error.message}."
            )
        }
    }
    if (config.strict == StrictJsonSchemaMode.REQUIRE) {
        throw ConstrainedSamplingError(
            "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but strict tools are unsupported."
        )
    }
    return null
}

fun resolveGrammarConstrainedSampling(
    tool: Tool,
    supportsOpenAIGrammarTools: Boolean
): GrammarConstrainedSampling? {
    val config = tool.constrainedSampling
    if (config !is ConstrainedSamplingConfig.Grammar) {
        return null
    }

    if (!supportsOpenAIGrammarTools) {
        return null
    }

    val larkDefinition = config.variants[GrammarFormat.OPENAI_LARK]
    val regexDefinition = config.variants[GrammarFormat.OPENAI_REGEX]
    val hasLarkDefinition = larkDefinition?.let { it.trim().isNotEmpty() } == true
    val hasRegexDefinition = regexDefinition?.let { it.trim().isNotEmpty() } == true
    if (!hasLarkDefinition && !hasRegexDefinition) {
        throw ConstrainedSamplingError(
            "Tool \"${tool.name}\" cannot use grammar constrained sampling: no supported grammar variant was provided."
        )
    }

    try {
        return GrammarConstrainedSampling(
            format = if (hasLarkDefinition) {
                GrammarConstrainedFormat.LARK
            } else {
                GrammarConstrainedFormat.REGEX
            },
            definition = (if (hasLarkDefinition) larkDefinition else regexDefinition)!!,
            inputProperty = inferGrammarInputProperty(tool)
        )
    } catch (error: Exception) {
        val message = error.message ?: error.toString()
        throw ConstrainedSamplingError(
            "Tool \"${tool.name}\" cannot use grammar constrained sampling: $message."
        )
    }
}

fun createGrammarToolInputProperties(
    tools: List<Tool>?,
    supportsOpenAIGrammarTools: Boolean
): Map<String, String> {
    val properties = LinkedHashMap<String, String>()
    for (tool in tools.orEmpty()) {
        val grammar = resolveGrammarConstrainedSampling(tool, supportsOpenAIGrammarTools)
        if (grammar != null) {
            properties[tool.name] = grammar.inputProperty
        }
    }
    return properties
}
