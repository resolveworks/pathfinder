package works.resolve.pathfinder.ai.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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
fun JsonObject?.truthyString(key: String): String? =
    string(key)?.takeIf { it.isNotEmpty() }

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
fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Finite numeric double that is not string-encoded (TS `Number.isFinite` shape). */
fun JsonElement?.numberOrNull(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

/** [key] as a numeric (never string-encoded) Int; null when absent/malformed. */
fun JsonObject?.strictInt(key: String): Int? =
    strictNumeric(key) { it.intOrNull }

/** [key] as a numeric (never string-encoded) Long; null when absent/malformed. */
fun JsonObject?.strictLong(key: String): Long? =
    strictNumeric(key) { it.longOrNull }

/** [key] as a numeric (never string-encoded) Double; null when absent/malformed. */
fun JsonObject?.strictDouble(key: String): Double? =
    strictNumeric(key) { it.doubleOrNull }

/** [key] as a boolean primitive (never string-encoded); null when absent/malformed. */
fun JsonObject?.strictBoolean(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private inline fun <N> JsonObject?.strictNumeric(key: String, parse: (JsonPrimitive) -> N?): N? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.let(parse)

// --- Codec reads: strict, throw on missing/malformed ---
// [error] receives the field name and returns the codec's own exception, so
// this surface never depends on codec exception types.

fun JsonObject.requireString(key: String, error: (String) -> Throwable): String =
    string(key) ?: throw error(key)

fun JsonObject.requireInt(key: String, error: (String) -> Throwable): Int =
    strictInt(key) ?: throw error(key)

fun JsonObject.requireLong(key: String, error: (String) -> Throwable): Long =
    strictLong(key) ?: throw error(key)

fun JsonObject.requireDouble(key: String, error: (String) -> Throwable): Double =
    strictDouble(key) ?: throw error(key)

fun JsonObject.requireBoolean(key: String, error: (String) -> Throwable): Boolean =
    strictBoolean(key) ?: throw error(key)
