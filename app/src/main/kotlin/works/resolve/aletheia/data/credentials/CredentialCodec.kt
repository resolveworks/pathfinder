package works.resolve.aletheia.data.credentials

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure (JVM-testable) codec between [ApiKeyCredential] and its on-disk form.
 *
 * Encoded shape is JSON: `{"key":...,"env":{...}}`. Legacy entries stored a
 * bare key string with no JSON framing; [decode] falls back to treating the
 * whole raw string as the key when it isn't the codec's JSON shape, so
 * existing single-key entries keep working.
 */
object CredentialCodec {

    @Serializable
    private data class Encoded(val key: String, val env: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    /** True when [raw] is JSON-parseable and looks like a credential object (not a bare key). */
    private fun looksEncoded(raw: String): Boolean {
        raw.trim().let { t -> if (t.isEmpty() || t.first() != '{' || t.last() != '}') return false }
        return try {
            val element = json.parseToJsonElement(raw)
            element is kotlinx.serialization.json.JsonObject && "key" in element
        } catch (_: Exception) {
            false
        }
    }

    fun encode(credential: ApiKeyCredential): String =
        json.encodeToString(Encoded.serializer(), Encoded(credential.key, credential.env))

    fun decode(raw: String): ApiKeyCredential {
        if (!looksEncoded(raw)) return ApiKeyCredential(raw)
        val encoded = json.decodeFromString(Encoded.serializer(), raw)
        return ApiKeyCredential(encoded.key, encoded.env)
    }
}
