package works.resolve.pathfinder.ai.auth

import kotlinx.serialization.json.JsonElement

/**
 * One type-tagged credential per provider — the shape of pi's `auth.json`.
 *
 * `toString` of every subtype redacts secret material; never log credentials.
 */
sealed interface Credential {
    val type: CredentialType
}

enum class CredentialType {
    API_KEY,
    OAUTH,
}

/**
 * Stored api-key credential. `env` holds provider-scoped environment/config
 * values such as Cloudflare account or gateway ids; `key` is null for
 * env-only credentials.
 */
data class ApiKeyCredential(
    val key: String? = null,
    val env: Map<String, String> = emptyMap(),
) : Credential {
    override val type: CredentialType = CredentialType.API_KEY

    override fun toString(): String =
        "ApiKeyCredential(key=<redacted>, env=${env.keys})"
}

/**
 * Stored canonical OAuth credential: `access`, `refresh`, `expires` (epoch
 * milliseconds) plus provider-specific extra fields, preserved verbatim in
 * [extras] so unknown JSON round trips safely. Pi models extras as an index
 * signature; the sealed type keeps them in an explicit map of raw JSON
 * elements.
 */
data class OAuthCredential(
    val access: String,
    val refresh: String,
    val expires: Long,
    val extras: Map<String, JsonElement> = emptyMap(),
) : Credential {
    init {
        // Extra fields are written verbatim next to the canonical fields;
        // reserved names would corrupt the record on encode.
        val reserved = extras.keys intersect RESERVED_FIELDS
        require(reserved.isEmpty()) { "OAuth extra fields must not use reserved names: $reserved" }
    }

    override val type: CredentialType = CredentialType.OAUTH

    override fun toString(): String =
        "OAuthCredential(access=<redacted>, refresh=<redacted>, expires=$expires, extras=${extras.keys})"

    companion object {
        val RESERVED_FIELDS: Set<String> = setOf("type", "access", "refresh", "expires")
    }
}

/** Non-secret credential metadata for account/status enumeration. */
data class CredentialInfo(
    val providerId: String,
    val type: CredentialType,
)
