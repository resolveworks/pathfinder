package works.resolve.aletheia.data.credentials

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.aletheia.ai.auth.ApiKeyCredential
import works.resolve.aletheia.ai.auth.Credential
import works.resolve.aletheia.ai.auth.OAuthCredential

/**
 * Pure (JVM-testable) codec between [Credential] and its on-disk form, ported
 * to pi's type-tagged `auth.json` shape (`packages/ai/src/auth/types.ts`).
 *
 * Encoded shapes:
 * - `{"type":"api_key","key":...,"env":{...}}`
 * - `{"type":"oauth","access":...,"refresh":...,"expires":...,...extras}` —
 *   provider-specific extra JSON fields are preserved verbatim and round trip
 *   through [OAuthCredential.extras].
 *
 * Legacy migrations (previous Aletheia format):
 * - a bare key string (no JSON framing) decodes as [ApiKeyCredential];
 * - `{"key":...,"env":{...}}` without a `type` tag decodes as [ApiKeyCredential].
 */
object CredentialCodec {

    private val json = Json { ignoreUnknownKeys = true }

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

    /** Decodes current and legacy shapes; throws [CredentialFormatException] on malformed input. */
    fun decode(raw: String): Credential {
        val trimmed = raw.trim()
        // Input starting with `{` is object-intended: it must parse as valid
        // typed JSON (even without a closing brace) or it is malformed. Only
        // genuine non-object bare text is a legacy API-key record.
        if (trimmed.isEmpty() || trimmed.first() != '{') return legacyApiKey(trimmed)
        val element = try {
            json.parseToJsonElement(trimmed)
        } catch (_: Exception) {
            throw CredentialFormatException("Malformed credential JSON")
        }
        val obj = element as? JsonObject ?: throw CredentialFormatException("Credential JSON is not an object")
        val typeField = obj["type"]
        val type = when {
            typeField == null -> null
            typeField is JsonPrimitive && typeField.isString -> typeField.content
            else -> throw CredentialFormatException("Credential type is not a string")
        }
        return when (type) {
            TYPE_API_KEY -> decodeApiKey(obj)
            TYPE_OAUTH -> decodeOAuth(obj)
            null ->
                // Legacy {key, env} record without a type tag.
                if ("key" in obj) decodeApiKey(obj) else throw CredentialFormatException("Missing credential type")
            else -> throw CredentialFormatException("Unknown credential type: $type")
        }
    }

    private fun legacyApiKey(raw: String): Credential = ApiKeyCredential(key = raw)

    private fun decodeApiKey(obj: JsonObject): ApiKeyCredential = ApiKeyCredential(
        key = obj["key"]?.let { key ->
            (key as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw CredentialFormatException("Key is not a string")
        },
        env = obj["env"]?.let { env ->
            (env as? JsonObject)?.entries?.associate { (name, value) ->
                name to ((value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw CredentialFormatException("Non-string env value for $name"))
            } ?: throw CredentialFormatException("Env is not an object")
        } ?: emptyMap(),
    )

    private fun decodeOAuth(obj: JsonObject): OAuthCredential = OAuthCredential(
        access = stringField(obj, "access"),
        refresh = stringField(obj, "refresh"),
        expires = (obj["expires"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
            ?: throw CredentialFormatException("Missing or non-numeric expires"),
        extras = obj.filterKeys { it !in OAuthCredential.RESERVED_FIELDS },
    )

    private fun stringField(obj: JsonObject, name: String): String =
        (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw CredentialFormatException("Missing or non-string $name")

    private const val TYPE_API_KEY = "api_key"
    private const val TYPE_OAUTH = "oauth"
}

/** Malformed persisted credential (no secret material in the message). */
class CredentialFormatException(message: String) : Exception(message)
