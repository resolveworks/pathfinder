package com.aletheia.data.credentials

/**
 * Narrow credential boundary used by UI-layer code: get/set/delete an API key
 * by provider id. Keeping this interface separate from [CredentialStore] lets
 * JVM tests substitute an in-memory fake instead of the Android Keystore.
 *
 * Implementations must never log key material.
 */
interface ApiKeyStore {
    /** The stored API key for [providerId], or null when none is stored. */
    suspend fun getApiKey(providerId: String): String?

    suspend fun setApiKey(providerId: String, apiKey: String)

    suspend fun deleteApiKey(providerId: String)
}
