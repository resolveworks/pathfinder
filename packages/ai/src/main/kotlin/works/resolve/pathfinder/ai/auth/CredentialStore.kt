package works.resolve.pathfinder.ai.auth

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-owned credential storage, keyed by provider id, one credential per
 * provider. `modify` is the only write path, so every mutation is a
 * serialized read-modify-write; OAuth refresh runs inside `modify` so
 * concurrent requests cannot double-refresh a rotated token. Login/logout
 * orchestration is app-owned.
 *
 * [read] returns null for missing entries; methods throw only on storage
 * failure, and pi's `AbortSignal` parameters become coroutine cancellation.
 * pi allows implementations to lock cross-process too; the single-process
 * app needs only per-provider in-process exclusion.
 */
interface CredentialStore {
    /**
     * Read the stored credential, possibly expired. Display/status use;
     * resolved request auth comes from `resolveProviderAuth`.
     */
    suspend fun read(providerId: String): Credential?

    /**
     * List stored credential metadata without resolving or exposing secrets.
     * Implementations must not execute configured API-key commands while
     * listing.
     */
    suspend fun list(): List<CredentialInfo>

    /**
     * Serialized write — the only write path. [update] sees the current
     * credential because correct writes (refresh, login-during-refresh)
     * depend on it; return the new credential, or null to leave the entry
     * unchanged. Mutual exclusion is per provider id. Returns the post-write
     * credential. Exceptions from [update] propagate.
     */
    suspend fun modify(
        providerId: String,
        update: suspend (current: Credential?) -> Credential?
    ): Credential?

    /** Remove a credential (logout). Implementations serialize this against [modify]. */
    suspend fun delete(providerId: String)
}

/** Default credential store; apps inject persistent stores. */
class InMemoryCredentialStore : CredentialStore {
    private val credentials = ConcurrentHashMap<String, Credential>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(providerId: String): Mutex = locks.computeIfAbsent(providerId) { Mutex() }

    override suspend fun read(providerId: String): Credential? = lockFor(providerId).withLock {
        credentials[providerId]
    }

    override suspend fun list(): List<CredentialInfo> =
        credentials.entries.map { (providerId, credential) ->
            CredentialInfo(providerId, credential.type)
        }

    override suspend fun modify(
        providerId: String,
        update: suspend (current: Credential?) -> Credential?
    ): Credential? = lockFor(providerId).withLock {
        val current = credentials[providerId]
        val next = update(current)
        if (next != null) credentials[providerId] = next
        next ?: current
    }

    override suspend fun delete(providerId: String): Unit = lockFor(providerId).withLock {
        credentials.remove(providerId)
        // The lock is deliberately retained: removing it here could hand a
        // queued waiter the old mutex while a new caller creates a fresh one,
        // breaking serialization.
    }
}
