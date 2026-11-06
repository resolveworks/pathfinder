package works.resolve.pathfinder.ai.utils

import java.math.BigInteger
import java.util.IdentityHashMap
import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * pi-schema's `Coercer` (`packages/schema/src/coerce.ts`): pass 1 drops
 * optional nulls, pass 2 applies TypeBox's conversion rules, pass 3 applies
 * Ajv-style primitive coercion. Every pass is copy-on-write.
 */
class SchemaCoercer(private val validator: SchemaValidator, private val schema: JsonElement) {
    private val entered = ArrayList<Pair<JsonObject, JsonElement>>()
    private var cuts = 0
    private var memo: IdentityHashMap<JsonObject, IdentityHashMap<JsonElement, JsonElement>>? = null

    fun coerce(value: JsonElement): JsonElement {
        val base = validator.resolver.rootBase
        val withoutNulls = dropOptionalNulls(value, schema, base)
        return coerceTypes(convertOnly(withoutNulls), schema, base)
    }

    fun convertOnly(value: JsonElement): JsonElement = try {
        convert(schema, validator.resolver.rootBase, value)
    } finally {
        memo = null
        cuts = 0
    }

    private fun reenter(
        node: JsonObject,
        base: String,
        value: JsonElement,
        memoize: Boolean
    ): JsonElement {
        for ((enteredNode, enteredValue) in entered) {
            if (enteredNode === node && sameJson(enteredValue, value)) {
                cuts++
                return value
            }
        }
        val memo = if (memoize) memo?.get(node) else null
        memo?.get(value)?.let { return it }
        val cutsBefore = cuts
        entered.add(node to value)
        val result = try {
            convert(node, base, value)
        } finally {
            entered.removeAt(entered.size - 1)
        }
        if (memoize && cuts == cutsBefore) {
            val values = memo ?: IdentityHashMap<JsonElement, JsonElement>().also {
                if (this.memo == null) this.memo = IdentityHashMap()
                this.memo!![node] = it
            }
            values[value] = result
        }
        return result
    }

    private fun passes(node: JsonElement, base: String, value: JsonElement): Boolean? = try {
        validator.checkNode(node, baseOf(node, base), value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun baseOf(node: JsonElement, base: String): String =
        (node as? JsonObject)?.let { validator.baseFor(it, base) } ?: base

    // Pass 1: optional nulls.

    private fun dropOptionalNulls(
        value: JsonElement,
        schema: JsonElement,
        base: String
    ): JsonElement {
        val node = schema as? JsonObject ?: return value
        val b = validator.baseFor(node, base)
        if (value is JsonArray) {
            return mapItems(value) { item, index ->
                val itemSchema = itemSchemaAt(node, index)
                if (itemSchema == null) item else dropOptionalNulls(item, itemSchema, b)
            }
        }
        if (value !is JsonObject) return value
        val properties = node["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val patterns = (node["patternProperties"] as? JsonObject)?.entries ?: emptyList()
        val additional = node["additionalProperties"] as? JsonObject
        if (patterns.isEmpty() && additional == null &&
            node["properties"] !is JsonObject
        ) {
            return value
        }
        val required = stringList(node["required"]).toSet()
        var out: LinkedHashMap<String, JsonElement>? = null

        fun current(key: String): JsonElement? = if (out == null) value[key] else out!![key]

        fun set(key: String, next: JsonElement) {
            if (sameJson(current(key), next)) return
            out = (out ?: LinkedHashMap(value)).also { it[key] = next }
        }

        for ((key, property) in properties) {
            val member = value[key] ?: continue
            val isRef = property is JsonObject && property.stringValue("${'$'}ref") != null
            if (member is JsonNull && key !in required && !isRef &&
                passes(property, b, JsonNull) == false
            ) {
                out = (out ?: LinkedHashMap(value)).also { it.remove(key) }
                continue
            }
            set(key, dropOptionalNulls(member, property, b))
        }
        if (patterns.isNotEmpty() || additional != null) {
            for (key in value.keys) {
                var matched = properties.containsKey(key)
                for ((pattern, item) in patterns) {
                    if (!SharedRegex.of(pattern).containsMatchIn(key)) continue
                    matched = true
                    current(key)?.let { set(key, dropOptionalNulls(it, item, b)) }
                }
                if (!matched && additional != null) {
                    set(key, dropOptionalNulls(value.getValue(key), additional, b))
                }
            }
        }
        return out?.let { JsonObject(it) } ?: value
    }

    // Pass 2: TypeBox's conversion rules.

    private fun convert(node: JsonElement, base: String, value: JsonElement): JsonElement {
        val objectNode = node as? JsonObject ?: return value
        val b = validator.baseFor(objectNode, base)
        objectNode.stringValue("${'$'}ref")?.let { ref ->
            val target = try {
                validator.resolver.resolve(ref, b)
            } catch (_: IllegalArgumentException) {
                return value
            }
            val targetNode = target.node
            return if (targetNode is JsonObject) {
                reenter(
                    targetNode,
                    target.base,
                    value,
                    true
                )
            } else {
                value
            }
        }
        return convertNode(objectNode, b, value)
    }

    private fun convertNode(node: JsonObject, base: String, value: JsonElement): JsonElement {
        if (node.containsKey("const")) return convertLiteral(node.getValue("const"), value)
        (node["enum"] as? JsonArray)?.let { return convertEnum(node, it, base, value) }
        (node["anyOf"] as? JsonArray)?.let { return convertUnion(node, it, base, value) }
        (node["oneOf"] as? JsonArray)?.let { return convertUnion(node, it, base, value) }
        (node["allOf"] as? JsonArray)?.let { members ->
            var next = value
            for (member in members) next = convert(member, base, next)
            return convertMembers(node, base, next)
        }
        if (node.containsKey("type")) {
            return when (node.stringValue("type")) {
                "string" -> orValue(tryString(value), value)

                "number" -> orValue(tryNumber(value), value)

                "integer" -> tryNumber(value)?.let { jsonNumber(floor(numberValue(it)!!)) }
                    ?: value

                "boolean" -> orValue(tryBoolean(value), value)

                "null" -> orValue(tryNull(value), value)

                "array" -> convertArray(node, base, value)

                "object" -> convertObject(node, base, value)

                // A type array or non-string type falls through, as TypeBox's switch does.
                // bytes, bigint and undefined are not JSON values; conversion cannot produce them.
                else -> value
            }
        }
        return when {
            node["properties"] != null || node["patternProperties"] != null ->
                convertObject(node, base, value)

            node["items"] != null || node["prefixItems"] != null -> convertArray(node, base, value)

            else -> value
        }
    }

    private fun convertEnum(
        node: JsonObject,
        values: JsonArray,
        base: String,
        value: JsonElement
    ): JsonElement {
        if (values.any { jsonEquals(it, value) }) return value
        for (member in values) {
            val candidate = convertLiteral(member, value)
            if (!sameJson(candidate, value) &&
                passes(node, base, candidate) == true
            ) {
                return candidate
            }
        }
        return value
    }

    private fun convertUnion(
        node: JsonObject,
        branches: JsonArray,
        base: String,
        value: JsonElement
    ): JsonElement {
        if (branches.any {
                passes(it, base, value) == true
            }
        ) {
            return convertMembers(node, base, value)
        }
        for (branch in branches) {
            val candidate = convertMembers(node, base, convert(branch, base, value))
            if (passes(node, base, candidate) == true) return candidate
        }
        return convertMembers(node, base, value)
    }

    private fun convertMembers(node: JsonObject, base: String, value: JsonElement): JsonElement =
        if (node["properties"] != null || node["patternProperties"] != null ||
            node["additionalProperties"] != null
        ) {
            convertObject(node, base, value)
        } else {
            value
        }

    private fun convertArray(node: JsonObject, base: String, value: JsonElement): JsonElement {
        val items = node["items"]
        val prefix = when {
            items is JsonArray -> items
            node["prefixItems"] is JsonArray -> node["prefixItems"] as JsonArray
            else -> null
        }
        if (prefix != null) {
            if (value !is JsonArray) return value
            val rest = if (items is JsonArray) null else items
            return mapItems(value) { item, index ->
                val itemSchema = if (index < prefix.size) prefix[index] else rest
                if (itemSchema == null) item else convert(itemSchema, base, item)
            }
        }
        if (value is JsonArray) {
            val itemSchema = node["items"] ?: return value
            return mapItems(value) { item, _ -> convert(itemSchema, base, item) }
        }
        val itemSchema = node["items"]
        return JsonArray(
            listOf(
                if (itemSchema is JsonObject) reenter(itemSchema, base, value, false) else value
            )
        )
    }

    private fun convertObject(node: JsonObject, base: String, value: JsonElement): JsonElement {
        if (value !is JsonObject) return value
        val properties = node["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val patterns = (node["patternProperties"] as? JsonObject)?.entries ?: emptyList()
        val additional = node["additionalProperties"] as? JsonObject
        var out: LinkedHashMap<String, JsonElement>? = null
        for ((key, member) in value) {
            var next = member
            var matched = properties.containsKey(key)
            if (matched) next = convert(properties.getValue(key), base, next)
            for ((pattern, schema) in patterns) {
                if (!SharedRegex.of(pattern).containsMatchIn(key)) continue
                matched = true
                next = convert(schema, base, next)
            }
            if (!matched && additional != null) next = convert(additional, base, next)
            if (!sameJson(next, member)) {
                out = (out ?: LinkedHashMap(value)).also { it[key] = next }
            }
        }
        return out?.let { JsonObject(it) } ?: value
    }

    // Pass 3: primitive coercion by type.

    private fun coerceTypes(value: JsonElement, schema: JsonElement, base: String): JsonElement {
        val node = schema as? JsonObject ?: return value
        val b = validator.baseFor(node, base)
        var next = value
        (node["allOf"] as? JsonArray)?.forEach { next = coerceTypes(next, it, b) }
        (node["anyOf"] as? JsonArray)?.let { next = coerceUnion(next, it, b) }
        (node["oneOf"] as? JsonArray)?.let { next = coerceUnion(next, it, b) }
        val types = typeNames(node["type"] ?: JsonNull)
        val matchesUnionMember = types.size > 1 && types.any { matchesType(next, it) }
        if (types.isNotEmpty() && !matchesUnionMember) {
            for (type in types) {
                val candidate = coercePrimitive(next, type)
                if (!sameJson(candidate, next)) {
                    next = candidate
                    break
                }
            }
        }
        if ("object" in types && next is JsonObject) next = coerceProperties(next, node, b)
        if ("array" in types && next is JsonArray) next = coerceItems(next, node, b)
        return next
    }

    private fun coerceUnion(value: JsonElement, branches: JsonArray, base: String): JsonElement {
        for (branch in branches) if (passes(branch, base, value) == true) return value
        for (branch in branches) {
            val coerced = coerceTypes(value, branch, base)
            if (passes(branch, base, coerced) == true) return coerced
        }
        return value
    }

    private fun coerceProperties(value: JsonObject, node: JsonObject, base: String): JsonObject {
        val properties = node["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val patterns = (node["patternProperties"] as? JsonObject)?.entries ?: emptyList()
        val additional = node["additionalProperties"] as? JsonObject
        var out: LinkedHashMap<String, JsonElement>? = null

        fun current(key: String): JsonElement =
            if (out == null) value.getValue(key) else out!![key] ?: value.getValue(key)

        fun set(key: String, next: JsonElement) {
            if (sameJson(current(key), next)) return
            out = (out ?: LinkedHashMap(value)).also { it[key] = next }
        }

        for ((key, property) in properties) {
            if (value.containsKey(key)) set(key, coerceTypes(value.getValue(key), property, base))
        }
        for (key in value.keys) {
            var matched = properties.containsKey(key)
            for ((pattern, schema) in patterns) {
                if (!SharedRegex.of(pattern).containsMatchIn(key)) continue
                matched = true
                set(key, coerceTypes(current(key), schema, base))
            }
            if (!matched && additional != null) {
                set(key, coerceTypes(value.getValue(key), additional, base))
            }
        }
        return out?.let { JsonObject(it) } ?: value
    }

    private fun coerceItems(value: JsonArray, schema: JsonObject, base: String): JsonArray =
        mapItems(value) { item, index ->
            val itemSchema = itemSchemaAt(schema, index)
            if (itemSchema is JsonObject) coerceTypes(item, itemSchema, base) else item
        }
}

/** The schema of an array's item at `index`, as pi's `itemSchemaAt`. */
private fun itemSchemaAt(schema: JsonObject, index: Int): JsonElement? {
    val items = schema["items"]
    if (items is JsonArray) return items.getOrNull(index)
    val prefix = schema["prefixItems"] as? JsonArray
    if (prefix != null && index < prefix.size) return prefix[index]
    return items
}

/** `items` mapped; the same array when every item maps to itself. */
private fun mapItems(items: JsonArray, map: (JsonElement, Int) -> JsonElement): JsonArray {
    var out: MutableList<JsonElement>? = null
    for (index in items.indices) {
        val next = map(items[index], index)
        if (sameJson(next, items[index])) continue
        out = out ?: items.toMutableList()
        out[index] = next
    }
    return out?.let { JsonArray(it) } ?: items
}

/** JS `!==`: primitives by value, objects and arrays by reference. */
private fun sameJson(a: JsonElement?, b: JsonElement?): Boolean = when {
    a == null || b == null -> a == null && b == null
    a is JsonObject || b is JsonObject || a is JsonArray || b is JsonArray -> a === b
    else -> a == b
}

private fun orValue(result: JsonElement?, value: JsonElement): JsonElement = result ?: value

/** A JS number's JSON form: integral values without a fraction, as JSON.stringify writes them. */
private fun jsonNumber(value: Double): JsonPrimitive =
    if (value.isFinite() && value == floor(value) && value >= Long.MIN_VALUE.toDouble() &&
        value <= Long.MAX_VALUE.toDouble()
    ) {
        JsonPrimitive(value.toLong())
    } else {
        JsonPrimitive(value)
    }

/** A literal taken from a value of another type that converts to exactly it. */
private fun convertLiteral(literal: JsonElement, value: JsonElement): JsonElement {
    if (jsonEquals(literal, value)) return value
    val result = when {
        literal is JsonPrimitive && literal.isString -> tryString(value)
        literal is JsonPrimitive && numberValue(literal) != null -> tryNumber(value)
        literal is JsonPrimitive && literal.strictBooleanOrNull() != null -> tryBoolean(value)
        else -> null
    }
    return if (result != null && jsonEquals(result, literal)) result else value
}

private fun tryString(value: JsonElement): JsonElement? = when {
    value is JsonNull -> JsonPrimitive("null")
    value is JsonPrimitive && value.isString -> value
    value is JsonPrimitive && value.strictBooleanOrNull() != null -> JsonPrimitive(value.content)
    numberValue(value)?.let { it.isFinite() } == true -> JsonPrimitive(jsNumber(numberValue(value)))
    else -> null
}

private fun tryNumber(value: JsonElement): JsonPrimitive? {
    if (value is JsonNull) return jsonNumber(0.0)
    if (value is JsonPrimitive) {
        if (value.isString) {
            val coerced = jsParseNumberOrNull(value.content)
            if (coerced != null && coerced.isFinite()) return jsonNumber(coerced)
            val lowercase = value.content.lowercase()
            if (lowercase == "false") return jsonNumber(0.0)
            if (lowercase == "true") return jsonNumber(1.0)
            val big = bigIntOfString(value.content) ?: return null
            if (big <= MAX_SAFE_INTEGER_BIGINT && big >= MIN_SAFE_INTEGER_BIGINT) {
                return jsonNumber(big.toDouble())
            }
            return null
        }
        numberValue(value)?.let { if (it.isFinite()) return jsonNumber(it) }
        if (value.strictBooleanOrNull() != null) {
            return jsonNumber(if (value.strictBooleanOrNull() == true) 1.0 else 0.0)
        }
    }
    return null
}

private val MAX_SAFE_INTEGER_BIGINT = BigInteger.valueOf(MAX_SAFE_INTEGER)
private val MIN_SAFE_INTEGER_BIGINT = BigInteger.valueOf(-MAX_SAFE_INTEGER)

/** TypeBox's TryBigInt of a string: `12n`, `12.5` (truncated) or `12`. */
private fun bigIntOfString(value: String): BigInteger? {
    if (Regex("""^-?(0|[1-9]\d*)n$""").matches(value)) return BigInteger(value.dropLast(1))
    if (Regex(
            """^-?(0|[1-9]\d*)\.\d+$"""
        ).matches(value)
    ) {
        return BigInteger(value.substringBefore("."))
    }
    if (Regex("""^-?(0|[1-9]\d*)$""").matches(value)) return BigInteger(value)
    return null
}

private fun tryBoolean(value: JsonElement): JsonPrimitive? {
    if (value is JsonNull) return JsonPrimitive(false)
    if (value is JsonPrimitive) {
        if (!value.isString) {
            value.strictBooleanOrNull()?.let { return JsonPrimitive(it) }
            numberValue(value)?.let {
                return when (it) {
                    0.0 -> JsonPrimitive(false)
                    1.0 -> JsonPrimitive(true)
                    else -> null
                }
            }
            return null
        }
        val lowercase = value.content.lowercase()
        if (lowercase == "false" || value.content == "0") return JsonPrimitive(false)
        if (lowercase == "true" || value.content == "1") return JsonPrimitive(true)
    }
    return null
}

private fun tryNull(value: JsonElement): JsonNull? = when {
    value is JsonNull -> JsonNull

    value is JsonPrimitive && !value.isString -> when {
        value.strictBooleanOrNull() == false -> JsonNull
        numberValue(value) == 0.0 -> JsonNull
        else -> null
    }

    value is JsonPrimitive && value.isString -> {
        val lowercase = value.content.lowercase()
        if (lowercase == "undefined" || lowercase == "null" || value.content == "" ||
            value.content == "0"
        ) {
            JsonNull
        } else {
            null
        }
    }

    else -> null
}

private fun coercePrimitive(value: JsonElement, type: String): JsonElement = when (type) {
    "number", "integer" -> {
        if (value is JsonNull) {
            jsonNumber(0.0)
        } else if (value is JsonPrimitive && value.isString &&
            trimJsWhitespace(value.content).isNotEmpty()
        ) {
            val parsed = jsParseNumberOrNull(value.content)
            when {
                parsed == null || !parsed.isFinite() -> value
                type == "number" || parsed == floor(parsed) -> jsonNumber(parsed)
                else -> value
            }
        } else if (value is JsonPrimitive && value.strictBooleanOrNull() != null) {
            jsonNumber(if (value.strictBooleanOrNull() == true) 1.0 else 0.0)
        } else {
            value
        }
    }

    "boolean" -> when {
        value is JsonNull -> JsonPrimitive(false)
        value is JsonPrimitive && value.isString && value.content == "true" -> JsonPrimitive(true)
        value is JsonPrimitive && value.isString && value.content == "false" -> JsonPrimitive(false)
        numberValue(value) == 1.0 -> JsonPrimitive(true)
        numberValue(value) == 0.0 -> JsonPrimitive(false)
        else -> value
    }

    "string" -> when {
        value is JsonNull -> JsonPrimitive("")

        numberValue(value) != null -> JsonPrimitive(jsNumber(numberValue(value)))

        value is JsonPrimitive && value.strictBooleanOrNull() != null -> JsonPrimitive(
            value.content
        )

        else -> value
    }

    "null" -> when {
        value is JsonPrimitive && value.isString && value.content.isEmpty() -> JsonNull
        numberValue(value) == 0.0 -> JsonNull
        value is JsonPrimitive && value.strictBooleanOrNull() == false -> JsonNull
        else -> value
    }

    else -> value
}
