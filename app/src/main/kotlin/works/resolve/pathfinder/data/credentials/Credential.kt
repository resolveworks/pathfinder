package works.resolve.pathfinder.data.credentials

/**
 * Per-provider API-key credential — the only credential shape Pathfinder
 * keeps (the pi-ported `auth.json` type-tagged contract, reduced to its
 * API-key variant; `packages/ai/src/auth/types.ts`). One credential per
 * provider; OAuth variants were removed with the pi runtime port.
 *
 * `toString` redacts the key; never log credentials.
 */
data class ApiKeyCredential(
    val key: String,
) {
    override fun toString(): String = "ApiKeyCredential(key=<redacted>)"
}

/**
 * Keystore-backed credential storage, keyed by provider id. Key material
 * never leaves the credential boundary in plaintext and is never logged.
 * Errors are storage failures; a missing entry reads as null.
 */
interface CredentialStore {
    /** Reads the stored credential for [providerId], or null when none is stored. */
    suspend fun read(providerId: String): ApiKeyCredential?

    /** Stores [credential] for [providerId], replacing any previous value wholesale. */
    suspend fun set(providerId: String, credential: ApiKeyCredential)

    /** Lists provider ids that have a stored credential (no secret material). */
    suspend fun list(): List<String>

    /** Removes the stored credential for [providerId], if any. */
    suspend fun delete(providerId: String)
}
