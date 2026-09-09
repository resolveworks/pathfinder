package works.resolve.pathfinder.ssh

/**
 * A configured machine reached over SSH. Authentication is always the
 * per-machine public key generated at creation; there is deliberately no
 * auth-method field.
 *
 * [publicKeyLine] is the machine's `authorized_keys` line (public data,
 * shown by the settings UI for copying to the server).
 */
data class Machine(
    val id: String,
    val address: String,
    val port: Int,
    val username: String,
    val cwd: String,
    val publicKeyLine: String
)

/**
 * An unencrypted OpenSSH-format private key PEM. At-rest protection comes
 * from the encrypted store, not a passphrase. The PEM is secret-bearing:
 * the string representation is redacted.
 */
@JvmInline
value class SshPrivateKeyPem(val pem: String) {
    override fun toString(): String = "SshPrivateKeyPem(<redacted>)"
}
