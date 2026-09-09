package works.resolve.pathfinder.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pending unknown-host-key request the dialog renders: the connection
 * target (username@address:port), the key type, and the SHA-256
 * fingerprint. Public data only.
 */
data class HostKeyRequest(val hostLabel: String, val keyType: String, val fingerprint: String)

/**
 * App-layer unknown-host-key decision: publishes the pending request as UI
 * state and suspends until the user answers. Fails closed everywhere — an
 * unanswered request (dismissed dialog, cancelled connect coroutine) never
 * completes, so the suspended connect is cancelled instead.
 */
class TofuHostKeyConfirmer {

    private val _pending = MutableStateFlow<HostKeyRequest?>(null)

    /** The request awaiting a Trust/Refuse answer, or null when none. */
    val pending: StateFlow<HostKeyRequest?> = _pending.asStateFlow()

    private var response: CompletableDeferred<Boolean>? = null

    /** Answers the pending request; a no-op (implicitly refusing) when none is pending. */
    fun answer(trust: Boolean) {
        response?.complete(trust)
    }

    /** Publishes the prompt for [host] and suspends for the user's Trust/Refuse answer. */
    suspend fun confirm(host: SshHost, fingerprint: String, keyType: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        response = deferred
        _pending.value = HostKeyRequest(
            hostLabel = "${host.username}@${host.address}:${host.port}",
            keyType = keyType,
            fingerprint = fingerprint
        )
        try {
            return deferred.await()
        } finally {
            _pending.value = null
            response = null
        }
    }
}
