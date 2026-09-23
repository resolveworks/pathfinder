package works.resolve.pathfinder.ai.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Shared JSON DOM reads. Read JSON the way the upstream site reads it:
 * lenient family where pi reads the field through TS typing with no runtime
 * guard (JS coerces at use); strict family where pi guards with
 * `typeof`/`Number.isFinite`; element-form strict reads mirror `typeof` on a
 * held value.
 */

val lenientJson: Json = Json { ignoreUnknownKeys = true }

private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

// --- Lenient reads: TS `String(x)` semantics ---

/** [key]'s content regardless of primitive kind; null when absent or JSON null. */
fun JsonObject?.str(key: String): String? = this?.get(key).primitiveOrNull()?.contentOrNull

/** [key] as an Int (kotlinx semantics: quoted numerals accepted, floats rejected). */
fun JsonObject?.int(key: String): Int? = this?.get(key).primitiveOrNull()?.intOrNull

/** [key] as a Long (kotlinx semantics: quoted numerals accepted, floats rejected). */
fun JsonObject?.long(key: String): Long? = this?.get(key).primitiveOrNull()?.longOrNull

fun JsonObject?.double(key: String): Double? = this?.get(key).primitiveOrNull()?.doubleOrNull

fun JsonObject?.boolean(key: String): Boolean? = this?.get(key).primitiveOrNull()?.booleanOrNull

/**
 * pi truthiness read (`!json?.field`): a non-empty string primitive. Absent,
 * JSON null, the empty string, and non-string primitives all yield null.
 */
fun JsonObject?.truthyString(key: String): String? = string(key)?.takeIf { it.isNotEmpty() }

/** Nested object at [key]; null when absent or of another kind. */
fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

/** Nested array at [key]; null when absent or of another kind. */
fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

/** Element form of [str] for reads off an already-extracted element. */
fun JsonElement?.strOrNull(): String? = primitiveOrNull()?.contentOrNull

// --- Strict reads: TS `typeof` semantics for auth/protocol fields ---

/** [key] as a string primitive only; numbers/booleans/null yield null (TS `typeof`). */
fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Element form of [string]. */
fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** [key] as a numeric (never string-encoded) Int; null when absent/malformed. */
fun JsonObject?.strictInt(key: String): Int? = strictNumeric(key) { it.intOrNull }

/** [key] as a numeric (never string-encoded) Long; null when absent/malformed. */
fun JsonObject?.strictLong(key: String): Long? = strictNumeric(key) { it.longOrNull }

/** [key] as a numeric (never string-encoded) Double; null when absent/malformed. */
fun JsonObject?.strictDouble(key: String): Double? = strictNumeric(key) { it.doubleOrNull }

/**
 * Element form of [strictDouble] with no finite filter: `typeof x === "number"`
 * accepts the Infinity/NaN literals a streaming parse can hold.
 */
fun JsonElement?.strictDoubleOrNull(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** [key] as a boolean primitive (never string-encoded); null when absent/malformed. */
fun JsonObject?.strictBoolean(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** Element form of [strictBoolean]. */
fun JsonElement?.strictBooleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private inline fun <N> JsonObject?.strictNumeric(key: String, parse: (JsonPrimitive) -> N?): N? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.let(parse)

// --- Codec reads: strict, throw on missing/malformed ---
// [error] receives the field name and returns the codec's own exception, so
// this surface never depends on codec exception types.

fun JsonObject.requireString(key: String, error: (String) -> Throwable): String =
    string(key) ?: throw error(key)

// --- Equality ---

/**
 * JS `===` over JSON values: string primitives by content, numeric
 * primitives by parsed double value (5, 5.0, and 1e3-vs-1000 are equal; NaN,
 * like in JS, equals nothing), booleans by value, and objects/arrays
 * structurally with the same primitive rules; kind mismatches (string vs
 * number) are always unequal. Exists because kotlinx `==` compares numeric
 * content lexically (verified at kotlinx.serialization 1.11.0), so
 * JsonPrimitive(5) != JsonPrimitive(5.0) and a parsed 1e3 != 1000 there.
 */
fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull

    a is JsonObject && b is JsonObject ->
        a.size == b.size && a.keys.all { key ->
            key in b && jsonEquals(a.getValue(key), b.getValue(key))
        }

    a is JsonArray && b is JsonArray ->
        a.size == b.size && a.zip(b).all { (x, y) -> jsonEquals(x, y) }

    a is JsonPrimitive && b is JsonPrimitive && a.isString == b.isString ->
        if (a.isString) {
            a.content == b.content
        } else {
            val numberA = a.strictDoubleOrNull()
            val numberB = b.strictDoubleOrNull()
            if (numberA != null && numberB != null) {
                numberA == numberB
            } else {
                val booleanA = a.strictBooleanOrNull()
                booleanA != null && booleanA == b.strictBooleanOrNull()
            }
        }

    else -> false
}
