package works.resolve.pathfinder.data.credentials

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure (JVM-testable) codec between [ApiKeyCredential] and its on-disk form,
 * the type-tagged shape of pi's `auth.json`
 * (`packages/ai/src/auth/types.ts`) reduced to the API-key variant:
 * `{"type":"api_key","key":...}`.
 *
 * Decoded input must be a type-tagged JSON object with a string key; any
 * other shape — including the removed `oauth` type, bare key strings, and
 * untagged objects — throws [CredentialFormatException]. Old persisted
 * formats fail fast and cleanly; nothing is converted.
 */
object CredentialCodec {

    private val json: Json = Json { ignoreUnknownKeys = true }

    fun encode(credential: ApiKeyCredential): String =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive(TYPE_API_KEY),
                "key" to JsonPrimitive(credential.key),
            ),
        ).toString()

    /** Decodes a type-tagged JSON object; throws [CredentialFormatException] on any other input. */
    fun decode(raw: String): ApiKeyCredential {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (_: Exception) {
            throw CredentialFormatException("Malformed credential JSON")
        }
        val obj = element as? JsonObject ?: throw CredentialFormatException("Credential JSON is not an object")
        val typeField = obj["type"]
        // JSON null is a non-string primitive and must hit the "not a string"
        // branch, not the missing branch — hence the explicit null check.
        val type = when {
            typeField == null -> null
            typeField.stringOrNull() != null -> typeField.stringOrNull()
            else -> throw CredentialFormatException("Credential type is not a string")
        }
        if (type != TYPE_API_KEY) {
            throw CredentialFormatException(
                if (type == null) "Missing credential type" else "Unknown credential type: $type",
            )
        }
        val keyField = obj["key"]
            ?: throw CredentialFormatException("Missing api key")
        val key = keyField.stringOrNull()
            ?: throw CredentialFormatException("Key is not a string")
        return ApiKeyCredential(key = key)
    }

    private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val TYPE_API_KEY = "api_key"
}

/** Malformed persisted credential (no secret material in the message). */
class CredentialFormatException(message: String) : Exception(message)
