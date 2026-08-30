package works.resolve.pathfinder.data.credentials

/**
 * A per-provider credential — the type-tagged `auth.json` contract of pi
 * (`packages/ai/src/auth/types.ts`), held as a sealed hierarchy. One
 * credential per provider. Secret material never leaves the credential
 * boundary in plaintext and is never logged.
 */
sealed interface Credential {
    /** Redacts all secret material in toString; never log credentials. */
    data class ApiKey(
        val key: String,
    ) : Credential {
        override fun toString(): String = "ApiKey(key=<redacted>)"
    }

    /**
     * ChatGPT/Codex OAuth token set; expiresAt is wall-clock epoch millis.
     * accountId is not secret and is shown in toString; tokens never are.
     */
    data class ChatGptOAuth(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochMillis: Long,
        val accountId: String,
    ) : Credential {
        override fun toString(): String =
            "ChatGptOAuth(accessToken=<redacted>, refreshToken=<redacted>, " +
                "expiresAtEpochMillis=$expiresAtEpochMillis, accountId=$accountId)"
    }
}

/**
 * Keystore-backed credential storage, keyed by provider id. Key material
 * never leaves the credential boundary in plaintext and is never logged.
 * Errors are storage failures; a missing entry reads as null.
 */
interface CredentialStore {
    /** Reads the stored credential for [providerId], or null when none is stored. */
    suspend fun read(providerId: String): Credential?

    /** Stores [credential] for [providerId], replacing any previous value wholesale. */
    suspend fun set(providerId: String, credential: Credential)

    /** Lists provider ids that have a stored credential (no secret material). */
    suspend fun list(): List<String>

    /** Removes the stored credential for [providerId], if any. */
    suspend fun delete(providerId: String)
}
