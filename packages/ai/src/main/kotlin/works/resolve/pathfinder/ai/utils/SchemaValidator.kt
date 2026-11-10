package works.resolve.pathfinder.ai.utils

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A selective port of pi-schema's compiler, checks and resolver
 * (`packages/schema/src/{compiler,checks,resolver}.ts`) for the JSON-valued
 * subset pi-ai's tool validation reaches.
 *
 * Values never pass through TypeBox's extra JavaScript types (`bytes`,
 * `bigint`, `undefined`); schemas naming them reject every JSON value.
 */
const val DEFAULT_SCHEMA_BASE = "https://json-schema.invalid/root.json"

/** pi's `unresolvedRefs` option. pi-ai compiles with [Reject]. */
enum class UnresolvedRefs { THROW, REJECT }

/** A schema and the base URI its references resolve against. */
internal data class ResolvedSchema(val node: JsonElement, val base: String)

internal class SchemaResolver(
    private val root: JsonElement,
    private val unresolvedRefs: UnresolvedRefs
) {
    val rootBase: String = DEFAULT_SCHEMA_BASE

    private val baseOf = IdentityHashMap<JsonObject, String>()
    private val resources = HashMap<String, JsonElement>()
    private val anchors = HashMap<String, JsonElement>()
    private val unresolved = HashSet<String>()
    private var indexed = false

    fun baseFor(node: JsonObject, base: String): String {
        baseOf[node]?.let { return it }
        val id = node.stringValue("${'$'}id") ?: return base
        if (!indexed) {
            ensureIndexed()
            baseOf[node]?.let { return it }
        }
        return documentOf(resolveUri(id, base))
    }

    fun ensureIndexed() {
        if (indexed) return
        indexed = true
        resources[DEFAULT_SCHEMA_BASE] = root
        index(root, DEFAULT_SCHEMA_BASE)
    }

    fun resolve(ref: String, base: String): ResolvedSchema {
        ensureIndexed()
        if (unresolvedRefs == UnresolvedRefs.REJECT) {
            return try {
                find(ref, base)
            } catch (_: IllegalArgumentException) {
                unresolved.add(resolveUri(ref, base))
                ResolvedSchema(JsonPrimitive(false), base)
            }
        }
        return find(ref, base)
    }

    fun isUnresolved(ref: String, base: String): Boolean =
        unresolved.contains(resolveUri(ref, base))

    private fun find(ref: String, base: String): ResolvedSchema {
        val uri = resolveUri(ref, base)
        val doc = documentOf(uri)
        val fragment = if (uri.length >
            doc.length
        ) {
            percentDecode(uri.substring(doc.length + 1))
        } else {
            ""
        }
        val resource = resources[doc]
            ?: throw IllegalArgumentException(
                "Unresolved ${'$'}ref $ref (no schema registered for $doc)"
            )
        if (fragment.isEmpty()) return ResolvedSchema(resource, doc)
        if (fragment.startsWith("/")) return pointer(resource, doc, fragment)
        val target = anchors["$doc#$fragment"]
            ?: throw IllegalArgumentException(
                "Unresolved ${'$'}ref $ref (no anchor $fragment in $doc)"
            )
        return ResolvedSchema(target, (target as? JsonObject)?.let { baseOf[it] } ?: doc)
    }

    private fun index(node: JsonElement, base: String) {
        if (node !is JsonObject || baseOf.containsKey(node)) return
        var b = base
        node.stringValue("${'$'}id")?.let { id ->
            val uri = resolveUri(id, b)
            b = documentOf(uri)
            resources[b] = node
            if (uri.length > b.length + 1) anchors[uri] = node
        }
        baseOf[node] = b
        node.stringValue("${'$'}anchor")?.let { anchors["$b#$it"] = node }
        node.stringValue("${'$'}dynamicAnchor")?.let { anchors["$b#$it"] = node }
        for (key in SCHEMA_KEYWORDS) node[key]?.let { index(it, b) }
        node["items"]?.let { if (it !is JsonArray) index(it, b) }
        for (key in MAP_KEYWORDS) {
            val map = node[key] as? JsonObject ?: continue
            for ((_, schema) in map) index(schema, b)
        }
        for (key in ARRAY_KEYWORDS) {
            val list = node[key] as? JsonArray ?: continue
            for (schema in list) index(schema, b)
        }
    }

    private fun pointer(root: JsonElement, base: String, fragment: String): ResolvedSchema {
        var node: JsonElement = root
        var b = base
        for (raw in fragment.split("/").drop(1)) {
            val key = raw.replace("~1", "/").replace("~0", "~")
            node = (node as? JsonObject)?.get(key)
                ?: throw IllegalArgumentException("Unresolved ${'$'}ref #$fragment in $base")
            if (node is JsonObject) b = baseOf[node] ?: b
        }
        return ResolvedSchema(node, b)
    }
}

private val SCHEMA_KEYWORDS = listOf(
    "additionalProperties",
    "propertyNames",
    "contains",
    "not",
    "if",
    "then",
    "else",
    "additionalItems",
    "contentSchema"
)

private val MAP_KEYWORDS = listOf(
    "properties",
    "patternProperties",
    "${'$'}defs",
    "definitions",
    "dependentSchemas",
    "dependencies"
)

private val ARRAY_KEYWORDS = listOf("allOf", "anyOf", "oneOf", "prefixItems", "items")

/** Keywords pi-schema implements but this port does not; they throw instead of validating less. */
private val UNSUPPORTED_KEYWORDS =
    setOf("${'$'}dynamicRef", "unevaluatedProperties", "unevaluatedItems")

/**
 * JSON regexes shared by source, as pi's `sharedRegex` shares them.
 *
 * pi compiles ECMAScript `RegExp` (preferring the `u` flag); the JVM has no
 * equivalent engine, so [Regex] is a documented approximation: `.`, `$`, and
 * Unicode class escapes can differ for schema `pattern` and `patternProperties`.
 */
internal object SharedRegex {
    private val cache = HashMap<String, Regex>()

    @Synchronized
    fun of(source: String): Regex = cache.getOrPut(source) {
        try {
            Regex(source)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid regular expression pattern \"$source\": ${error.message}"
            )
        }
    }
}

/** The result of [SchemaValidator.parse]. */
sealed interface SchemaParseResult {
    data class Valid(val value: JsonElement) : SchemaParseResult
    data class Invalid(val errors: List<SchemaError>) : SchemaParseResult
}

/** A compiled schema. `check` is pi-schema's `Validator.check`; errors live in [SchemaExplain]. */
class SchemaValidator(
    val schema: JsonElement,
    val formats: Map<String, (String) -> Boolean> = SchemaFormats.checks,
    unresolvedRefs: UnresolvedRefs = UnresolvedRefs.THROW
) {
    internal val resolver = SchemaResolver(schema, unresolvedRefs)
    private val coercer by lazy { SchemaCoercer(this, schema) }

    init {
        requireSchema(schema, resolver.rootBase, identitySet())
    }

    private fun requireSchema(node: JsonElement, base: String, seen: MutableSet<JsonObject>) {
        if (node is JsonPrimitive) {
            if (node.isString || node.content !in setOf("true", "false")) malformedSchema(node)
            return
        }
        if (node !is JsonObject) malformedSchema(node)
        if (!seen.add(node)) return
        for (keyword in UNSUPPORTED_KEYWORDS) {
            if (node.containsKey(keyword)) {
                throw IllegalArgumentException("Unsupported schema keyword \"$keyword\"")
            }
        }
        for (keyword in SCHEMA_KEYWORDS) node[keyword]?.let { requireSchema(it, base, seen) }
        node["items"]?.let { if (it !is JsonArray) requireSchema(it, base, seen) }
        for (keyword in MAP_KEYWORDS) {
            val map = node[keyword] as? JsonObject ?: continue
            for ((_, schema) in map) {
                // Legacy dependencies take a required-name array as well as a schema.
                if (keyword == "dependencies" && schema is JsonArray) continue
                requireSchema(schema, base, seen)
            }
        }
        for (keyword in ARRAY_KEYWORDS) {
            val list = node[keyword] as? JsonArray ?: continue
            for (schema in list) requireSchema(schema, base, seen)
        }
        node.stringValue("${'$'}ref")?.let { ref ->
            val target = resolver.resolve(ref, base)
            requireSchema(target.node, target.base, seen)
        }
    }

    fun check(value: JsonElement): Boolean = checkNode(schema, resolver.rootBase, value)

    /** The value coerced towards the schema (pi's `CoercingValidator.coerce`). */
    fun coerce(value: JsonElement): JsonElement = coercer.coerce(value)

    /** Why `value` is invalid; `[]` when it is valid (pi's `Validator.errors`). */
    fun errors(value: JsonElement): List<SchemaError> =
        if (check(value)) emptyList() else SchemaExplainer(this, schema).errors(value)

    /** Coerces, then checks, as pi's `CoercingValidator.parse`. */
    fun parse(value: JsonElement): SchemaParseResult {
        val coerced = coerce(value)
        return if (check(coerced)) {
            SchemaParseResult.Valid(coerced)
        } else {
            SchemaParseResult.Invalid(SchemaExplainer(this, schema).errors(coerced))
        }
    }

    internal fun baseFor(node: JsonObject, base: String): String = resolver.baseFor(node, base)

    internal fun checkNode(node: JsonElement, base: String, value: JsonElement): Boolean {
        if (node is JsonPrimitive) {
            if (node.isString) malformedSchema(node)
            return when (node.content) {
                "true" -> true
                "false" -> false
                else -> malformedSchema(node)
            }
        }
        if (node !is JsonObject) malformedSchema(node)
        val b = resolver.baseFor(node, base)

        node["type"]?.let { type ->
            val types = typeNames(type)
            if (types.none { matchesType(value, it) }) return false
        }
        val enum = node["enum"] as? JsonArray
        if (enum != null && enum.none { jsonEquals(it, value) }) return false
        if (node.containsKey("const") && !jsonEquals(node.getValue("const"), value)) return false

        (node["allOf"] as? JsonArray)?.let { list ->
            for (schema in list) if (!checkNode(schema, b, value)) return false
        }
        (node["anyOf"] as? JsonArray)?.let { list ->
            if (list.none { checkNode(it, b, value) }) return false
        }
        (node["oneOf"] as? JsonArray)?.let { list ->
            var matches = 0
            for (schema in list) if (checkNode(schema, b, value) && ++matches > 1) return false
            if (matches != 1) return false
        }
        node["not"]?.let { if (checkNode(it, b, value)) return false }
        node["if"]?.let { condition ->
            val branch = if (checkNode(condition, b, value)) node["then"] else node["else"]
            if (branch != null && !checkNode(branch, b, value)) return false
        }

        if (value is JsonPrimitive && value.isString) {
            if (!checkString(node, value.content)) return false
        }
        if (value is JsonPrimitive && !value.isString) {
            value.strictDoubleOrNull()?.let { if (!checkNumber(node, it)) return false }
        }
        if (value is JsonObject) {
            if (!checkObject(node, b, value)) return false
        }
        if (value is JsonArray) {
            if (!checkArray(node, b, value)) return false
        }

        node.stringValue("${'$'}ref")?.let { ref ->
            val target = resolver.resolve(ref, b)
            if (!checkNode(target.node, target.base, value)) return false
        }
        return true
    }

    private fun checkString(node: JsonObject, value: String): Boolean {
        val length = value.codePointCount(0, value.length)
        number(node["minLength"])?.let { if (length < it) return false }
        number(node["maxLength"])?.let { if (length > it) return false }
        node.stringValue("pattern")?.let {
            if (!SharedRegex.of(it).containsMatchIn(value)) return false
        }
        node.stringValue("format")?.let { name ->
            formats[name]?.let { if (!it(value)) return false }
        }
        return true
    }

    private fun checkNumber(node: JsonObject, value: Double): Boolean {
        number(node["minimum"])?.let { if (value < it) return false }
        number(node["maximum"])?.let { if (value > it) return false }
        number(node["exclusiveMinimum"])?.let { if (value <= it) return false }
        number(node["exclusiveMaximum"])?.let { if (value >= it) return false }
        number(node["multipleOf"])?.let { if (!isMultiple(value, it)) return false }
        return true
    }

    private fun checkObject(node: JsonObject, base: String, value: JsonObject): Boolean {
        val properties = node["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = stringList(node["required"])
        for (key in required) if (!value.containsKey(key)) return false
        for ((key, property) in properties) {
            val member = value[key] ?: continue
            if (!checkNode(property, base, member)) return false
        }
        val patterns = patternEntries(node["patternProperties"])
        val additional = node["additionalProperties"]
        for ((key, member) in value) {
            var matched = properties.containsKey(key)
            for ((pattern, schema) in patterns) {
                if (pattern.containsMatchIn(key)) {
                    matched = true
                    if (!checkNode(schema, base, member)) return false
                }
            }
            if (!matched && additional != null) {
                if (!checkNode(additional, base, member)) return false
            }
            node["propertyNames"]?.let {
                if (!checkNode(it, base, JsonPrimitive(key))) return false
            }
        }
        number(node["minProperties"])?.let { if (value.size < it) return false }
        number(node["maxProperties"])?.let { if (value.size > it) return false }
        return checkDependencies(node, base, value)
    }

    private fun checkDependencies(node: JsonObject, base: String, value: JsonObject): Boolean {
        (node["dependentRequired"] as? JsonObject)?.let { dependencies ->
            for ((key, list) in dependencies) {
                if (!value.containsKey(key)) continue
                for (required in stringList(list)) {
                    if (!value.containsKey(required)) return false
                }
            }
        }
        for (key in listOf("dependentSchemas", "dependencies")) {
            val dependencies = node[key] as? JsonObject ?: continue
            for ((dependency, schema) in dependencies) {
                if (!value.containsKey(dependency)) continue
                if (schema is JsonArray) {
                    for (required in stringList(schema)) {
                        if (!value.containsKey(required)) return false
                    }
                } else if (!checkNode(schema, base, value)) {
                    return false
                }
            }
        }
        return true
    }

    private fun checkArray(node: JsonObject, base: String, value: JsonArray): Boolean {
        val items = node["items"]
        val prefix = when {
            items is JsonArray -> items
            node["prefixItems"] is JsonArray -> node["prefixItems"] as JsonArray
            else -> JsonArray(emptyList())
        }
        val rest = if (items is JsonArray) node["additionalItems"] else items
        for (index in value.indices) {
            val schema = if (index < prefix.size) prefix[index] else rest ?: continue
            if (!checkNode(schema, base, value[index])) return false
        }
        number(node["minItems"])?.let { if (value.size < it) return false }
        number(node["maxItems"])?.let { if (value.size > it) return false }
        if ((node["uniqueItems"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } ==
            true
        ) {
            for (i in 1 until value.size) {
                for (j in 0 until i) if (jsonEquals(value[i], value[j])) return false
            }
        }
        node["contains"]?.let { contains ->
            val min = number(node["minContains"]) ?: 1.0
            val max = number(node["maxContains"]) ?: Double.POSITIVE_INFINITY
            var matches = 0
            for (item in value) if (checkNode(contains, base, item)) matches++
            if (matches < min || matches > max) return false
        }
        return true
    }
}

private fun malformedSchema(node: JsonElement): Nothing = throw IllegalArgumentException(
    "A schema must be an object or a boolean, got ${jsJsonStringify(node)}"
)

private fun identitySet(): MutableSet<JsonObject> = Collections.newSetFromMap(IdentityHashMap())

/** The type names of a `type`; a malformed value names no type, as pi's `typeCheck` does. */
internal fun typeNames(type: JsonElement): List<String> = when (type) {
    is JsonPrimitive -> if (type.isString) listOf(type.content) else emptyList()
    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    else -> emptyList()
}

internal fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
    "string" -> value is JsonPrimitive && value.isString
    "number" -> numberValue(value)?.isFinite() == true
    "integer" -> numberValue(value)?.let { it.isFinite() && it == floor(it) } == true
    "boolean" -> value is JsonPrimitive && !value.isString && value.strictBooleanOrNull() != null
    "null" -> value is JsonNull
    "array" -> value is JsonArray
    "object" -> value is JsonObject
    else -> false
}

internal fun numberValue(value: JsonElement): Double? =
    (value as? JsonPrimitive)?.takeIf { !it.isString }?.strictDoubleOrNull()

internal fun number(value: JsonElement?): Double? = value?.let { numberValue(it) }

internal fun JsonObject.stringValue(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun stringList(value: JsonElement?): List<String> = (value as? JsonArray)?.mapNotNull {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
}
    ?: emptyList()

private fun patternEntries(value: JsonElement?): List<Pair<Regex, JsonElement>> =
    (value as? JsonObject)?.map { (pattern, schema) -> SharedRegex.of(pattern) to schema }
        ?: emptyList()

internal fun resolveUri(ref: String, base: String): String = try {
    URI(base).resolve(ref).toString()
} catch (_: Exception) {
    if (ref.startsWith("#")) documentOf(base) + ref else ref
}

internal fun documentOf(uri: String): String {
    val hash = uri.indexOf('#')
    return if (hash < 0) uri else uri.substring(0, hash)
}

private fun percentDecode(value: String): String =
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

/** Whether `value` is an exact multiple of `divisor`, exactly for decimals as pi's `isMultiple`. */
private fun isMultiple(value: Double, divisor: Double): Boolean {
    if (divisor == 0.0) return false
    val quotient = value / divisor
    if (!quotient.isFinite()) return false
    return try {
        java.math.BigDecimal(value.toString())
            .remainder(java.math.BigDecimal(divisor.toString()))
            .compareTo(java.math.BigDecimal.ZERO) == 0
    } catch (_: ArithmeticException) {
        false
    }
}
