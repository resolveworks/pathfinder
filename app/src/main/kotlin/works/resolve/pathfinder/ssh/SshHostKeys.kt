package works.resolve.pathfinder.ssh

import android.content.Context
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.SshKeys
import org.connectbot.sshlib.SshSigning
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.data.credentials.EncryptedCredentialStore
import works.resolve.pathfinder.data.credentials.KeystoreAeadCipher

/** The generated per-host keypair: PEM private key plus authorized_keys line. */
class SshHostKeypair(val privateKey: SshPrivateKeyPem, val publicKeyLine: String)

/**
 * Generates and persists per-host SSH client keypairs.
 *
 * The key is ECDSA P-256 from the platform JCE: Android's only stock
 * Ed25519 generator is AndroidKeyStore (non-exportable, incompatible with
 * PEM-based public key auth), and cbssh's internal Ed25519 generator is not
 * public API (`ensureEd25519Support()` is a deprecated no-op).
 */
object SshHostKeys {

    /**
     * The private key is encoded UNENCRYPTED (`password = null`); at-rest
     * protection comes from [store]. The comment field pins the line to this
     * app so the server's authorized_keys stays self-describing.
     */
    suspend fun generate(): SshHostKeypair = withContext(Dispatchers.Default) {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair: KeyPair = generator.generateKeyPair()
        val pem = SshKeys.encodeOpenSshPrivateKey(keyPair, password = null)
        val encoded = SshSigning.encodePublicKey(keyPair)
        val blob = Base64.getEncoder().encodeToString(encoded.publicKeyBlob)
        SshHostKeypair(
            privateKey = SshPrivateKeyPem(pem),
            publicKeyLine = "${encoded.algorithmName} $blob $KEY_COMMENT"
        )
    }

    const val KEY_COMMENT = "pathfinder"
}

/**
 * Encrypted at-rest storage for the per-host private keys, reusing
 * [EncryptedCredentialStore] over a dedicated directory so host keys never
 * appear among provider credentials. Entries are keyed by a
 * provider-id-regex-safe derivation of the host id.
 */
class SshHostKeyStore(context: Context, cipher: KeystoreAeadCipher) {

    private val credentials =
        EncryptedCredentialStore(
            dir = File(context.filesDir, DIRECTORY),
            encrypt = cipher::encrypt,
            decrypt = cipher::decrypt
        )

    suspend fun write(hostId: String, key: SshPrivateKeyPem) {
        credentials.modify(credentialKey(hostId)) { ApiKeyCredential(key = key.pem) }
    }

    suspend fun read(hostId: String): SshPrivateKeyPem? =
        (credentials.read(credentialKey(hostId)) as? ApiKeyCredential)
            ?.key
            ?.let(::SshPrivateKeyPem)

    suspend fun delete(hostId: String) {
        credentials.delete(credentialKey(hostId))
    }

    private fun credentialKey(hostId: String): String {
        val key = "$KEY_PREFIX$hostId"
        require(key.length <= MAX_KEY_LENGTH && hostId.all { it.isDigit() || it in 'a'..'f' }) {
            "Invalid host id"
        }
        return key
    }

    private companion object {
        const val DIRECTORY = "ssh-host-keys"
        const val KEY_PREFIX = "sshhost-"
        const val MAX_KEY_LENGTH = 64
    }
}
