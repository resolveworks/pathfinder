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
 * App-layer [UnknownHostKeyCallback]: publishes the pending request as UI
 * state and suspends until the user answers. Fails closed everywhere — an
 * unanswered request (dismissed dialog, cancelled creation coroutine)
 * never completes, so the suspended connect is cancelled instead.
 *
 * The callback signature carries no host identity, and connects happen
 * inside the agent factory; the session being created is published here
 * ([setSessionContext]) by the single factory-call seam so the prompt can
 * name the host.
 */
class TofuHostKeyConfirmer(
    private val hostStore: SshHostStore,
    private val sessionHosts: SshSessionHostStore
) : UnknownHostKeyCallback {

    private val _pending = MutableStateFlow<HostKeyRequest?>(null)

    /** The request awaiting a Trust/Refuse answer, or null when none. */
    val pending: StateFlow<HostKeyRequest?> = _pending.asStateFlow()

    private var response: CompletableDeferred<Boolean>? = null

    @Volatile
    private var sessionContextId: String? = null

    @Volatile
    private var hostContextId: String? = null

    /** Session whose creation the next callback runs inside; resolves the prompt's host label. */
    fun setSessionContext(sessionId: String?) {
        sessionContextId = sessionId
    }

    /** Host being dialed outside a session (the host form's connection test); the label fallback. */
    fun setHostContext(hostId: String?) {
        hostContextId = hostId
    }

    /** Answers the pending request; a no-op (implicitly refusing) when none is pending. */
    fun answer(trust: Boolean) {
        response?.complete(trust)
    }

    override suspend fun confirm(fingerprint: String, keyType: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        response = deferred
        _pending.value = HostKeyRequest(
            hostLabel = resolveHostLabel(),
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

    private suspend fun resolveHostLabel(): String {
        val hostId = sessionContextId?.let { sessionHosts.hostId(it) }
            ?: hostContextId
            ?: return ""
        val host = hostStore.host(hostId) ?: return hostId
        return "${host.username}@${host.address}:${host.port}"
    }
}
