package works.resolve.pathfinder.data.credentials

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure (JVM-testable) codec between [Credential] and its on-disk form, the
 * type-tagged shape of pi's `auth.json` (`packages/ai/src/auth/types.ts`):
 *
 * - API key: `{"type":"api_key","key":...}`
 * - OAuth: `{"type":"oauth","access":...,"refresh":...,"expires":<epoch millis>,
 *   "accountId":...}`
 *
 * Decoded input must be a type-tagged JSON object with all fields present and
 * correctly typed; any other shape — unknown type, missing fields, mistyped
 * fields, JSON null, bare key strings, untagged objects — throws
 * [CredentialFormatException]. Old persisted formats fail fast and cleanly;
 * nothing is converted.
 */
object CredentialCodec {

    private val json: Json = Json { ignoreUnknownKeys = true }

    fun encode(credential: Credential): String = when (credential) {
        is Credential.ApiKey ->
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive(TYPE_API_KEY),
                    "key" to JsonPrimitive(credential.key),
                ),
            )
        is Credential.ChatGptOAuth ->
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive(TYPE_OAUTH),
                    "access" to JsonPrimitive(credential.accessToken),
                    "refresh" to JsonPrimitive(credential.refreshToken),
                    "expires" to JsonPrimitive(credential.expiresAtEpochMillis),
                    "accountId" to JsonPrimitive(credential.accountId),
                ),
            )
    }.toString()

    /** Decodes a type-tagged JSON object; throws [CredentialFormatException] on any other input. */
    fun decode(raw: String): Credential {
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
        return when (type) {
            TYPE_API_KEY -> decodeApiKey(obj)
            TYPE_OAUTH -> decodeOAuth(obj)
            null -> throw CredentialFormatException("Missing credential type")
            else -> throw CredentialFormatException("Unknown credential type: $type")
        }
    }

    private fun decodeApiKey(obj: JsonObject): Credential.ApiKey {
        val key = obj.stringField("key") ?: throw CredentialFormatException("Missing api key")
        return Credential.ApiKey(key = key)
    }

    private fun decodeOAuth(obj: JsonObject): Credential.ChatGptOAuth {
        val access = obj.stringField("access") ?: throw CredentialFormatException("Missing oauth access token")
        val refresh = obj.stringField("refresh") ?: throw CredentialFormatException("Missing oauth refresh token")
        val accountId = obj.stringField("accountId") ?: throw CredentialFormatException("Missing oauth accountId")
        val expiresField = obj["expires"]
            ?: throw CredentialFormatException("Missing oauth expires")
        val expires = (expiresField as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.longOrNull
            ?: throw CredentialFormatException("Oauth expires is not a number")
        return Credential.ChatGptOAuth(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMillis = expires,
            accountId = accountId,
        )
    }

    /** Returns the string value of [name]; null when missing, throws on non-string (incl. JSON null). */
    private fun JsonObject.stringField(name: String): String? {
        val field = this[name] ?: return null
        return field.stringOrNull() ?: throw CredentialFormatException("$name is not a string")
    }

    private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val TYPE_API_KEY = "api_key"
    private const val TYPE_OAUTH = "oauth"
}

/** Malformed persisted credential (no secret material in the message). */
class CredentialFormatException(message: String) : Exception(message)
