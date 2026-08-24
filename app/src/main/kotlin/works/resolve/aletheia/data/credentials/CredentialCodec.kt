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
        val element = run {
            if (trimmed.isEmpty() || trimmed.first() != '{' || trimmed.last() != '}') return legacyApiKey(trimmed)
            try {
                json.parseToJsonElement(trimmed)
            } catch (_: Exception) {
                return legacyApiKey(trimmed)
            }
        }
        val obj = element as? JsonObject ?: return legacyApiKey(trimmed)
        return when (val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
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
        key = (obj["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
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
        expires = (obj["expires"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw CredentialFormatException("Missing or non-numeric expires"),
        extras = obj.filterKeys { it !in OAUTH_KNOWN_FIELDS },
    )

    private fun stringField(obj: JsonObject, name: String): String =
        (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw CredentialFormatException("Missing or non-string $name")

    private val OAUTH_KNOWN_FIELDS = setOf("type", "access", "refresh", "expires")

    private const val TYPE_API_KEY = "api_key"
    private const val TYPE_OAUTH = "oauth"
}

/** Malformed persisted credential (no secret material in the message). */
class CredentialFormatException(message: String) : Exception(message)
