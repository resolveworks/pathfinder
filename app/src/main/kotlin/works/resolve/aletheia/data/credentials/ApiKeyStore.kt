package works.resolve.aletheia.data.credentials

/**
 * Narrow credential boundary used by UI-layer code: get/set/delete a
 * credential (API key plus optional provider env values) by provider id.
 * Keeping this interface separate from [CredentialStore] lets JVM tests
 * substitute an in-memory fake instead of the Android Keystore.
 *
 * Implementations must never log key material.
 */
interface ApiKeyStore {
    /** The stored credential for [providerId], or null when none is stored. */
    suspend fun getCredential(providerId: String): ApiKeyCredential?

    suspend fun setCredential(providerId: String, credential: ApiKeyCredential)

    suspend fun deleteCredential(providerId: String)

    /** The stored API key for [providerId], or null when none is stored. */
    suspend fun getApiKey(providerId: String): String? =
        getCredential(providerId)?.key

    suspend fun setApiKey(providerId: String, apiKey: String) =
        setCredential(providerId, ApiKeyCredential(apiKey))

    suspend fun deleteApiKey(providerId: String) = deleteCredential(providerId)
}
