package com.aletheia.data.credentials

/**
 * Secure storage boundary for provider API keys. Implementations must keep key
 * material encrypted at rest and must never log values.
 */
interface CredentialStore {

    /** Returns the stored API key for [providerId], or null if none is set. */
    suspend fun getApiKey(providerId: String): String?

    suspend fun setApiKey(providerId: String, apiKey: String)

    suspend fun deleteApiKey(providerId: String)
}
