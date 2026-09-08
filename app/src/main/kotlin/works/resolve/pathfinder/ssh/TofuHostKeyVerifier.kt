package works.resolve.pathfinder.ssh

import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyFingerprint
import org.connectbot.sshlib.PublicKey

/**
 * Callback for an unseen host key: receives the SHA-256 fingerprint, returns
 * whether the caller (the UI, in a later phase) trusts it. The default
 * implementation refuses, so verification fails closed when no UI is wired.
 */
fun interface UnknownHostKeyCallback {
    suspend fun confirm(fingerprint: String, keyType: String): Boolean

    companion object {
        val REFUSE = UnknownHostKeyCallback { _, _ -> false }
    }
}

/**
 * Minimal TOFU host-key verification for one host. The first connect
 * captures [KeyFingerprint.sha256] and persists it; later connects compare
 * and a mismatch is a hard rejection. An unknown key is accepted only if the
 * caller-supplied callback confirms, and is then persisted.
 */
class TofuHostKeyVerifier(
    private val store: SshHostStore,
    private val hostId: String,
    private val onUnknownKey: UnknownHostKeyCallback = UnknownHostKeyCallback.REFUSE
) : HostKeyVerifier {

    override suspend fun verify(key: PublicKey): Boolean {
        val fingerprint = KeyFingerprint.sha256(key.encoded)
        val trusted = store.trustedHostKeyFingerprint(hostId)
        return when {
            trusted == null -> {
                if (onUnknownKey.confirm(fingerprint, key.type)) {
                    store.trustHostKeyFingerprint(hostId, fingerprint)
                    true
                } else {
                    false
                }
            }

            trusted == fingerprint -> true

            // Host key changed: treat as a possible MITM and never overwrite
            // the pinned fingerprint. Re-trusting requires explicit store action.
            else -> false
        }
    }
}
