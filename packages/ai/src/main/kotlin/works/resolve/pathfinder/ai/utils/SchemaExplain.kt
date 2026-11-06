package works.resolve.pathfinder.ai.utils

import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One failure of a value: a JSON Pointer, the failing keyword and pi-schema's message. */
data class SchemaError(val path: String, val keyword: String, val message: String)

/** The errors of a value that failed [SchemaValidator.check], as pi-schema's `explain.ts`. */
class SchemaExplainer(private val validator: SchemaValidator, private val schema: JsonElement) {
    fun errors(value: JsonElement): List<SchemaError> {
        val out = mutableListOf<SchemaError>()
        walk(schema, validator.resolver.rootBase, value, "", out)
        if (out.isEmpty()) out.add(SchemaError("", "schema", "invalid"))
        return out
    }

    private fun ok(node: JsonElement, base: String, value: JsonElement): Boolean =
        validator.checkNode(node, base, value)

    private fun walk(
        node: JsonElement,
        base: String,
        value: JsonElement,
        path: String,
        out: MutableList<SchemaError>
    ) {
        if (node is JsonPrimitive) {
            if (node.isString) return
            if (node.content == "false") {
                out.add(SchemaError(path, "false", "no value is allowed here"))
            }
            return
        }
        if (node !is JsonObject) return
        val b = validator.baseFor(node, base)
        if (ok(node, b, value)) return
        val start = out.size
        fun push(keyword: String, message: String, at: String = path) {
            out.add(SchemaError(at, keyword, message))
        }

        node["type"]?.let { type ->
            val types = typeNames(type)
            if (types.none { matchesType(value, it) }) {
                push("type", "expected ${types.joinToString(" or ")}, got ${typeName(value)}")
                return
            }
        }
        val enum = node["enum"] as? JsonArray
        if (enum != null && enum.none { jsonEquals(it, value) }) {
            push("enum", "expected one of ${listValues(enum)}")
        }
        if (node.containsKey("const") && !jsonEquals(node.getValue("const"), value)) {
            push("const", "expected ${jsJsonStringify(node.getValue("const"))}")
        }
        if (value is JsonPrimitive && value.isString) walkString(node, value.content, path, ::push)
        if (numberValue(value) != null) walkNumber(node, numberValue(value)!!, path, ::push)
        if (value is JsonObject) walkObject(node, b, value, path, out, ::push)
        if (value is JsonArray) walkArray(node, b, value, path, out, ::push)
        (node["allOf"] as? JsonArray)?.forEach { walk(it, b, value, path, out) }
        (node["anyOf"] as? JsonArray)?.let { walkUnion(it, b, value, path, out, false) }
        (node["oneOf"] as? JsonArray)?.let { walkUnion(it, b, value, path, out, true) }
        node["not"]?.let { if (ok(it, b, value)) push("not", "must not match the schema in not") }
        node["if"]?.let { condition ->
            val branch = if (ok(condition, b, value)) node["then"] else node["else"]
            if (branch != null) walk(branch, b, value, path, out)
        }
        node.stringValue("${'$'}ref")?.let { walkRef(it, b, value, path, out) }
        if (out.size == start && (
                node.containsKey("unevaluatedProperties") ||
                    node.containsKey("unevaluatedItems")
                )
        ) {
            // requireSchema rejects these keywords; this is unreachable until they are ported.
            push("unevaluated", "unsupported")
        }
    }

    private fun walkRef(
        ref: String,
        base: String,
        value: JsonElement,
        path: String,
        out: MutableList<SchemaError>
    ) {
        val target = validator.resolver.resolve(ref, base)
        if (target.node is JsonPrimitive &&
            !target.node.isString &&
            target.node.content == "false" &&
            validator.resolver.isUnresolved(ref, base)
        ) {
            out.add(SchemaError(path, "${'$'}ref", "unresolved reference $ref"))
            return
        }
        walk(target.node, target.base, value, path, out)
    }

    private fun walkString(
        node: JsonObject,
        value: String,
        path: String,
        push: (String, String, String) -> Unit
    ) {
        val length = value.codePointCount(0, value.length)
        number(node["minLength"])?.let {
            if (length < it) push("minLength", "expected at least ${jsNumber(it)} characters", path)
        }
        number(node["maxLength"])?.let {
            if (length > it) push("maxLength", "expected at most ${jsNumber(it)} characters", path)
        }
        node.stringValue("pattern")?.let {
            if (!SharedRegex.of(it).containsMatchIn(value)) {
                push("pattern", "expected to match $it", path)
            }
        }
        node.stringValue("format")?.let { name ->
            validator.formats[name]?.let {
                if (!it(value)) push("format", "expected format $name", path)
            }
        }
    }

    private fun walkNumber(
        node: JsonObject,
        value: Double,
        path: String,
        push: (String, String, String) -> Unit
    ) {
        number(node["minimum"])?.let {
            if (value < it) push("minimum", "expected >= ${jsNumber(it)}", path)
        }
        number(node["maximum"])?.let {
            if (value > it) push("maximum", "expected <= ${jsNumber(it)}", path)
        }
        number(node["exclusiveMinimum"])?.let {
            if (value <= it) push("exclusiveMinimum", "expected > ${jsNumber(it)}", path)
        }
        number(node["exclusiveMaximum"])?.let {
            if (value >= it) push("exclusiveMaximum", "expected < ${jsNumber(it)}", path)
        }
        number(node["multipleOf"])?.let {
            if (!isMultipleForExplain(value, it)) {
                push("multipleOf", "expected a multiple of ${jsNumber(it)}", path)
            }
        }
    }

    private fun walkObject(
        node: JsonObject,
        base: String,
        value: JsonObject,
        path: String,
        out: MutableList<SchemaError>,
        push: (String, String, String) -> Unit
    ) {
        val properties = node["properties"] as? JsonObject ?: JsonObject(emptyMap())
        for (key in stringList(node["required"])) {
            if (!value.containsKey(key)) push("required", "missing", pointer(path, key))
        }
        for ((key, property) in properties) {
            value[key]?.let { walk(property, base, it, pointer(path, key), out) }
        }
        val patterns = (node["patternProperties"] as? JsonObject)?.entries ?: emptyList()
        for ((key, member) in value) {
            var matched = properties.containsKey(key)
            for ((pattern, schema) in patterns) {
                if (SharedRegex.of(pattern).containsMatchIn(key)) {
                    matched = true
                    walk(schema, base, member, pointer(path, key), out)
                }
            }
            if (!matched) {
                node["additionalProperties"]?.let { additional ->
                    if (additional is JsonPrimitive &&
                        !additional.isString &&
                        additional.content == "false"
                    ) {
                        push("additionalProperties", "unknown field", pointer(path, key))
                    } else {
                        walk(additional, base, member, pointer(path, key), out)
                    }
                }
            }
            node["propertyNames"]?.let {
                if (!ok(it, base, JsonPrimitive(key))) {
                    push("propertyNames", "invalid property name", pointer(path, key))
                }
            }
        }
        number(node["minProperties"])?.let {
            if (value.size < it) {
                push("minProperties", "expected at least ${jsNumber(it)} properties", path)
            }
        }
        number(node["maxProperties"])?.let {
            if (value.size > it) {
                push("maxProperties", "expected at most ${jsNumber(it)} properties", path)
            }
        }
        walkDependencies(node, base, value, path, out, push)
    }

    private fun walkDependencies(
        node: JsonObject,
        base: String,
        value: JsonObject,
        path: String,
        out: MutableList<SchemaError>,
        push: (String, String, String) -> Unit
    ) {
        (node["dependentRequired"] as? JsonObject)?.let { dependencies ->
            for ((key, list) in dependencies) {
                if (!value.containsKey(key)) continue
                for (required in stringList(list)) {
                    if (!value.containsKey(required)) {
                        push(
                            "dependentRequired",
                            "required when $key is present",
                            pointer(path, required)
                        )
                    }
                }
            }
        }
        for (keyword in listOf("dependentSchemas", "dependencies")) {
            val dependencies = node[keyword] as? JsonObject ?: continue
            for ((dependency, schema) in dependencies) {
                if (!value.containsKey(dependency)) continue
                if (schema is JsonArray) {
                    for (required in stringList(schema)) {
                        if (!value.containsKey(required)) {
                            push(
                                "dependentRequired",
                                "required when $dependency is present",
                                pointer(path, required)
                            )
                        }
                    }
                } else {
                    walk(schema, base, value, path, out)
                }
            }
        }
    }

    private fun walkArray(
        node: JsonObject,
        base: String,
        value: JsonArray,
        path: String,
        out: MutableList<SchemaError>,
        push: (String, String, String) -> Unit
    ) {
        val items = node["items"]
        val prefix = when {
            items is JsonArray -> items
            node["prefixItems"] is JsonArray -> node["prefixItems"] as JsonArray
            else -> JsonArray(emptyList())
        }
        val rest = if (items is JsonArray) node["additionalItems"] else items
        for (index in value.indices) {
            val schema = if (index < prefix.size) prefix[index] else rest ?: continue
            walk(schema, base, value[index], pointer(path, index.toString()), out)
        }
        number(node["minItems"])?.let {
            if (value.size < it) push("minItems", "expected at least ${jsNumber(it)} items", path)
        }
        number(node["maxItems"])?.let {
            if (value.size > it) push("maxItems", "expected at most ${jsNumber(it)} items", path)
        }
        val uniqueItems = (node["uniqueItems"] as? JsonPrimitive)
            ?.let { !it.isString && it.content == "true" } == true
        if (uniqueItems) {
            var unique = true
            for (i in 1 until value.size) {
                for (j in 0 until i) if (jsonEquals(value[i], value[j])) unique = false
            }
            if (!unique) push("uniqueItems", "expected unique items", path)
        }
        node["contains"]?.let { contains ->
            val min = number(node["minContains"]) ?: 1.0
            val max = number(node["maxContains"]) ?: Double.POSITIVE_INFINITY
            var matches = 0
            for (item in value) if (ok(contains, base, item)) matches++
            if (matches < min || matches > max) push("contains", containsMessage(min, max), path)
        }
    }

    private fun walkUnion(
        branches: JsonArray,
        base: String,
        value: JsonElement,
        path: String,
        out: MutableList<SchemaError>,
        exactlyOne: Boolean
    ) {
        var matches = 0
        for (branch in branches) if (ok(branch, base, value)) matches++
        if (matches == 1 || (!exactlyOne && matches > 0)) return
        val keyword = if (exactlyOne) "oneOf" else "anyOf"
        if (matches > 1) {
            out.add(SchemaError(path, keyword, "matches more than one alternative"))
            return
        }
        val tag = tagOf(branches, base)
        if (tag != null && value is JsonObject) {
            val selected = if (value.containsKey(tag.key)) {
                tag.values.indexOfFirst { jsonEquals(it, value.getValue(tag.key)) }
            } else {
                -1
            }
            if (selected < 0) {
                out.add(
                    SchemaError(
                        pointer(path, tag.key),
                        keyword,
                        "expected one of ${listValues(tag.values)}"
                    )
                )
            } else {
                walk(branches[selected], base, value, path, out)
            }
            return
        }
        var bestErrors: List<SchemaError>? = null
        var bestDeclared = -1
        for (branch in branches) {
            val errors = mutableListOf<SchemaError>()
            walk(branch, base, value, path, errors)
            if (errors.isEmpty()) continue
            val declared = if (value is JsonObject) declaredKeys(branch, base, value) else 0
            val previous = bestErrors
            val better = previous == null || declared > bestDeclared ||
                (declared == bestDeclared && errors[0].path.length > previous[0].path.length)
            if (better) {
                bestErrors = errors
                bestDeclared = declared
            }
        }
        out.addAll(bestErrors ?: listOf(SchemaError(path, keyword, "matches no alternative")))
    }

    private fun declaredKeys(schema: JsonElement, base: String, value: JsonObject): Int {
        val node = if (schema is JsonObject) {
            schema.stringValue("${'$'}ref")?.let {
                validator.resolver.resolve(it, validator.baseFor(schema, base)).node
            } ?: schema
        } else {
            schema
        }
        val properties = (node as? JsonObject)?.get("properties") as? JsonObject ?: return 0
        return value.keys.count { properties.containsKey(it) }
    }

    private class Tag(val key: String, val values: List<JsonElement>)

    private fun tagOf(branches: JsonArray, base: String): Tag? {
        val resolved = branches.map { branch ->
            if (branch is JsonObject) {
                branch.stringValue("${'$'}ref")?.let {
                    validator.resolver.resolve(it, validator.baseFor(branch, base)).node
                } ?: branch
            } else {
                branch
            }
        }
        val firstProperties =
            (resolved.firstOrNull() as? JsonObject)?.get("properties") as? JsonObject ?: return null
        for (key in firstProperties.keys) {
            val values = resolved.map { branch ->
                val tag = ((branch as? JsonObject)?.get("properties") as? JsonObject)?.get(key)
                (tag as? JsonObject)?.get("const")
            }
            if (values.any { it == null || (it is JsonObject || it is JsonArray) }) continue
            val distinct = values.all { candidate ->
                values.count { jsonEquals(it!!, candidate!!) } == 1
            }
            if (distinct) return Tag(key, values.map { it!! })
        }
        return null
    }
}

private fun pointer(path: String, key: String): String =
    "$path/${key.replace("~", "~0").replace("/", "~1")}"

private fun containsMessage(min: Double, max: Double): String {
    if (max == Double.POSITIVE_INFINITY) {
        return "expected at least ${jsNumber(min)} matching ${if (min == 1.0) "item" else "items"}"
    }
    if (min == 0.0) {
        return "expected at most ${jsNumber(max)} matching ${if (max == 1.0) "item" else "items"}"
    }
    return "expected between ${jsNumber(min)} and ${jsNumber(max)} matching items"
}

private const val LISTED_VALUES = 20

private fun listValues(values: List<JsonElement>): String {
    if (values.size <= LISTED_VALUES) return jsJsonStringify(JsonArray(values))
    val listed = jsJsonStringify(JsonArray(values.take(LISTED_VALUES)))
    return "${listed.dropLast(1)}, ... and ${values.size - LISTED_VALUES} more]"
}

private fun typeName(value: JsonElement): String = when (value) {
    is JsonNull -> "null"

    is JsonArray -> "array"

    is JsonObject -> "object"

    is JsonPrimitive -> when {
        value.isString -> "string"

        value.strictBooleanOrNull() != null -> "boolean"

        else -> {
            val number = value.strictDoubleOrNull()
            when {
                number == null -> "unknown"
                !number.isFinite() -> number.toString()
                number == floor(number) -> "integer"
                else -> "number"
            }
        }
    }
}

/** `JSON.stringify` for the values that reach an error message. */
internal fun jsJsonStringify(value: JsonElement): String = when (value) {
    is JsonNull -> "null"

    is JsonArray -> value.joinToString(",", "[", "]") { jsJsonStringify(it) }

    is JsonObject -> value.entries.joinToString(",", "{", "}") { (key, child) ->
        "${JsonPrimitive(key)}:${jsJsonStringify(child)}"
    }

    is JsonPrimitive -> when {
        value.isString -> JsonPrimitive(value.content).toString()
        value.strictBooleanOrNull() != null -> value.content
        else -> jsNumber(value.strictDoubleOrNull())
    }
}

private fun isMultipleForExplain(value: Double, divisor: Double): Boolean {
    if (divisor == 0.0) return false
    return try {
        java.math.BigDecimal(value.toString())
            .remainder(java.math.BigDecimal(divisor.toString()))
            .compareTo(java.math.BigDecimal.ZERO) == 0
    } catch (_: ArithmeticException) {
        false
    }
}
