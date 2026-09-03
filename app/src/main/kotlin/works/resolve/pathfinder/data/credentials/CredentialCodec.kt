package works.resolve.pathfinder.data.credentials

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.requireString
import works.resolve.pathfinder.ai.utils.strictLong
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * Codec between [Credential] and its on-disk form: pi's type-tagged
 * `auth.json` shape.
 *
 * Encoded shapes:
 * - `{"type":"api_key","key":...,"env":{...}}`
 * - `{"type":"oauth","access":...,"refresh":...,"expires":...,...extras}` —
 *   provider-specific extra JSON fields are preserved verbatim and round trip
 *   through [OAuthCredential.extras].
 */
object CredentialCodec {

    fun encode(credential: Credential): String = when (credential) {
        is ApiKeyCredential -> {
            val fields = buildMap {
                put("type", JsonPrimitive(TYPE_API_KEY))
                credential.key?.let { put("key", JsonPrimitive(it)) }
                if (credential.env.isNotEmpty()) {
                    put("env", JsonObject(credential.env.mapValues { (_, v) -> JsonPrimitive(v) }))
                }
            }
            JsonObject(fields).toString()
        }
        is OAuthCredential -> {
            val fields = buildMap {
                put("type", JsonPrimitive(TYPE_OAUTH))
                put("access", JsonPrimitive(credential.access))
                put("refresh", JsonPrimitive(credential.refresh))
                put("expires", JsonPrimitive(credential.expires))
                credential.extras.forEach { (name, value) -> put(name, value) }
            }
            JsonObject(fields).toString()
        }
    }

    /** Decodes a type-tagged JSON object; throws [CredentialFormatException] on any other input. */
    fun decode(raw: String): Credential {
        val element = try {
            lenientJson.parseToJsonElement(raw)
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
        return when (type) {
            TYPE_API_KEY -> decodeApiKey(obj)
            TYPE_OAUTH -> decodeOAuth(obj)
            null -> throw CredentialFormatException("Missing credential type")
            else -> throw CredentialFormatException("Unknown credential type: $type")
        }
    }

    private fun decodeApiKey(obj: JsonObject): ApiKeyCredential = ApiKeyCredential(
        key = obj["key"]?.let { key ->
            key.stringOrNull() ?: throw CredentialFormatException("Key is not a string")
        },
        env = obj["env"]?.let { env ->
            (env as? JsonObject)?.entries?.associate { (name, value) ->
                name to (value.stringOrNull()
                    ?: throw CredentialFormatException("Non-string env value for $name"))
            } ?: throw CredentialFormatException("Env is not an object")
        } ?: emptyMap(),
    )

    private fun decodeOAuth(obj: JsonObject): OAuthCredential = OAuthCredential(
        access = obj.requireString("access") { name -> CredentialFormatException("Missing or non-string $name") },
        refresh = obj.requireString("refresh") { name -> CredentialFormatException("Missing or non-string $name") },
        expires = obj.strictLong("expires")
            ?: throw CredentialFormatException("Missing or non-numeric expires"),
        extras = obj.filterKeys { it !in OAuthCredential.RESERVED_FIELDS },
    )

    private const val TYPE_API_KEY = "api_key"
    private const val TYPE_OAUTH = "oauth"
}

/** Malformed persisted credential (no secret material in the message). */
class CredentialFormatException(message: String) : Exception(message)
