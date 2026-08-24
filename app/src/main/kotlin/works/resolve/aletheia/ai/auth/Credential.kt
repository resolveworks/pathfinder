package works.resolve.aletheia.ai.auth

import kotlinx.serialization.json.JsonElement

/**
 * Ported from pi `packages/ai/src/auth/types.ts` (one type-tagged credential
 * per provider — the shape of pi's `auth.json`).
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
 * Stored api-key credential (pi `ApiKeyCredential`). `env` holds
 * provider-scoped environment/config values such as Cloudflare account or
 * gateway ids. `key` may be null when only env values are stored.
 *
 * Port note: pi types `key` as optional; the previous Aletheia-only shape
 * required it, so [key] is nullable now.
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
 * Stored canonical OAuth credential (pi `OAuthCredential`): `access`,
 * `refresh`, `expires` (epoch milliseconds) plus any provider-specific extra
 * fields, preserved verbatim in [extras] so unknown JSON round trips safely.
 *
 * Port note: pi models extras as an index signature on the interface;
 * Kotlin's sealed types keep them in an explicit map of raw JSON elements.
 */
data class OAuthCredential(
    val access: String,
    val refresh: String,
    val expires: Long,
    val extras: Map<String, JsonElement> = emptyMap(),
) : Credential {
    override val type: CredentialType = CredentialType.OAUTH

    override fun toString(): String =
        "OAuthCredential(access=<redacted>, refresh=<redacted>, expires=$expires, extras=${extras.keys})"
}

/** Non-secret credential metadata for account/status enumeration (pi `CredentialInfo`). */
data class CredentialInfo(
    val providerId: String,
    val type: CredentialType,
)
