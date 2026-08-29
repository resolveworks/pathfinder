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

/**
 * Shared JSON-DOM access surface (TS→Kotlin translation conventions, see the
 * AGENTS.md at the Kotlin source root). All hand-ported wire formats and
 * persisted codecs read JSON through these helpers — never through private
 * per-file accessor families; extend this surface instead.
 *
 * The surface composes kotlinx.serialization's own primitive accessors
 * (`contentOrNull`, `intOrNull`, …) rather than re-implementing parsing. It
 * adds only key-based access, the strict/lenient distinction, and the
 * throwing reads codecs need:
 *
 * - **Lenient** reads mirror TS `String(x)` / pi's SDK-typed field reads:
 *   any primitive's content counts, quoted numerals parse as numbers, and
 *   floats are rejected for int reads (kotlinx semantics).
 * - **Strict** reads mirror TS `typeof x === "string"` / `Number.isFinite`
 *   guards used by pi's auth code: string reads require a string primitive,
 *   numeric reads reject string-encoded numbers.
 * - **Codec** (`require*`) reads are strict and throw the caller's exception
 *   on anything missing or malformed — nothing is silently dropped.
 */

/** The shared Json instance for wire and codec DOM parsing (no per-file builders). */
internal val lenientJson: Json = Json { ignoreUnknownKeys = true }

private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

// --- Lenient reads: TS `String(x)` semantics; any primitive's content counts ---

/** [key]'s content as a string regardless of primitive kind; null when absent or JSON null. */
internal fun JsonObject?.str(key: String): String? = this?.get(key).primitiveOrNull()?.contentOrNull

/** [key] as an Int (kotlinx semantics: quoted numerals accepted, floats rejected). */
internal fun JsonObject?.int(key: String): Int? = this?.get(key).primitiveOrNull()?.intOrNull

/** [key] as a Long (kotlinx semantics: quoted numerals accepted, floats rejected). */
internal fun JsonObject?.long(key: String): Long? = this?.get(key).primitiveOrNull()?.longOrNull

/** [key] as a Double (kotlinx semantics). */
internal fun JsonObject?.double(key: String): Double? = this?.get(key).primitiveOrNull()?.doubleOrNull

/** [key] as a boolean primitive per kotlinx semantics. */
internal fun JsonObject?.boolean(key: String): Boolean? = this?.get(key).primitiveOrNull()?.booleanOrNull

/**
 * pi truthiness read (`!json?.field`): a non-empty string primitive. Absent,
 * JSON null, the empty string, and non-string primitives all yield null.
 */
internal fun JsonObject?.truthyString(key: String): String? =
    string(key)?.takeIf { it.isNotEmpty() }

/** Nested object at [key]; null when absent or of another kind. */
internal fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

/** Nested array at [key]; null when absent or of another kind. */
internal fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

/** Element form of [str] for reads off an already-extracted element. */
internal fun JsonElement?.strOrNull(): String? = primitiveOrNull()?.contentOrNull

// --- Strict reads: TS `typeof` semantics for auth/protocol fields ---

/** [key] as a string primitive only; numbers/booleans/null yield null (TS `typeof`). */
internal fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Element form of [string]. */
internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Finite numeric double that is not string-encoded (TS `Number.isFinite` shape). */
internal fun JsonElement?.numberOrNull(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

/** [key] as a numeric (never string-encoded) Int; null when absent/malformed. */
internal fun JsonObject?.strictInt(key: String): Int? =
    strictNumeric(key) { it.intOrNull }

/** [key] as a numeric (never string-encoded) Long; null when absent/malformed. */
internal fun JsonObject?.strictLong(key: String): Long? =
    strictNumeric(key) { it.longOrNull }

/** [key] as a numeric (never string-encoded) Double; null when absent/malformed. */
internal fun JsonObject?.strictDouble(key: String): Double? =
    strictNumeric(key) { it.doubleOrNull }

/** [key] as a boolean primitive (never string-encoded); null when absent/malformed. */
internal fun JsonObject?.strictBoolean(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private inline fun <N> JsonObject?.strictNumeric(key: String, parse: (JsonPrimitive) -> N?): N? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.let(parse)

// --- Codec reads: strict, throw on missing/malformed ---
// [error] receives the field name and returns the codec's own exception, so
// SessionCodec/CredentialCodec keep their exception types without the shared
// surface depending on them.

/** Strict string field; throws [error]'s result when absent or not a string primitive. */
internal fun JsonObject.requireString(key: String, error: (String) -> Throwable): String =
    string(key) ?: throw error(key)

/** Strict Int field; throws [error]'s result when absent or malformed. */
internal fun JsonObject.requireInt(key: String, error: (String) -> Throwable): Int =
    strictInt(key) ?: throw error(key)

/** Strict Long field; throws [error]'s result when absent or malformed. */
internal fun JsonObject.requireLong(key: String, error: (String) -> Throwable): Long =
    strictLong(key) ?: throw error(key)

/** Strict Double field; throws [error]'s result when absent or malformed. */
internal fun JsonObject.requireDouble(key: String, error: (String) -> Throwable): Double =
    strictDouble(key) ?: throw error(key)

/** Strict Boolean field; throws [error]'s result when absent or malformed. */
internal fun JsonObject.requireBoolean(key: String, error: (String) -> Throwable): Boolean =
    strictBoolean(key) ?: throw error(key)
