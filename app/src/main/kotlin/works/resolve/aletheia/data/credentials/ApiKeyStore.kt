package works.resolve.aletheia.data.credentials

import works.resolve.aletheia.ai.auth.ApiKeyCredential
import works.resolve.aletheia.ai.auth.CredentialStore

/**
 * Narrow legacy credential boundary used by current UI/agent code
 * (get/set/delete an [ApiKeyCredential] by provider id). Superseded by the
 * ported [CredentialStore] contract; this adapter keeps existing call sites
 * compiling until they migrate to modify-based login orchestration.
 *
 * Reads surface only API-key credentials: a provider holding an OAuth
 * credential reads as null here (the real store still holds it). Writes
 * replace whatever credential the provider had, matching the previous
 * store's semantics.
 */
interface ApiKeyStore {
    /** The stored API-key credential for [providerId], or null when none is stored. */
    suspend fun getCredential(providerId: String): ApiKeyCredential?

    suspend fun setCredential(providerId: String, credential: ApiKeyCredential)

    suspend fun deleteCredential(providerId: String)
}

/** Bridges an [ApiKeyStore] view onto any [CredentialStore]. */
class ApiKeyStoreAdapter(private val store: CredentialStore) : ApiKeyStore {
    override suspend fun getCredential(providerId: String): ApiKeyCredential? =
        store.read(providerId) as? ApiKeyCredential

    override suspend fun setCredential(providerId: String, credential: ApiKeyCredential) {
        store.modify(providerId) { credential }
    }

    override suspend fun deleteCredential(providerId: String) {
        store.delete(providerId)
    }
}
