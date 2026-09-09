package works.resolve.pathfinder.ssh

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

/** The generated per-machine keypair: PEM private key plus authorized_keys line. */
class MachineKeypair(val privateKey: SshPrivateKeyPem, val publicKeyLine: String)

/**
 * Generates and persists per-machine SSH client keypairs.
 *
 * The key is ECDSA P-256 from the platform JCE: Android's only stock
 * Ed25519 generator is AndroidKeyStore (non-exportable, incompatible with
 * PEM-based public key auth), and cbssh's internal Ed25519 generator is not
 * public API (`ensureEd25519Support()` is a deprecated no-op).
 */
object MachineKeys {

    /**
     * The private key is encoded UNENCRYPTED (`password = null`); at-rest
     * protection comes from [store]. The comment field pins the line to this
     * app so the server's authorized_keys stays self-describing.
     */
    suspend fun generate(): MachineKeypair = withContext(Dispatchers.Default) {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair: KeyPair = generator.generateKeyPair()
        val pem = SshKeys.encodeOpenSshPrivateKey(keyPair, password = null)
        val encoded = SshSigning.encodePublicKey(keyPair)
        val blob = Base64.getEncoder().encodeToString(encoded.publicKeyBlob)
        MachineKeypair(
            privateKey = SshPrivateKeyPem(pem),
            publicKeyLine = "${encoded.algorithmName} $blob $KEY_COMMENT"
        )
    }

    const val KEY_COMMENT = "pathfinder"
}

/**
 * Encrypted at-rest storage for the per-machine private keys, reusing
 * [EncryptedCredentialStore] over a dedicated directory so machine keys never
 * appear among provider credentials. Entries are keyed by a
 * provider-id-regex-safe derivation of the machine id. Open so the app's JVM
 * test harness can substitute an in-memory store.
 */
open class MachineKeyStore(dir: File, cipher: KeystoreAeadCipher) {

    private val credentials =
        EncryptedCredentialStore(
            dir = dir,
            encrypt = cipher::encrypt,
            decrypt = cipher::decrypt
        )

    open suspend fun write(machineId: String, key: SshPrivateKeyPem) {
        credentials.modify(credentialKey(machineId)) { ApiKeyCredential(key = key.pem) }
    }

    open suspend fun read(machineId: String): SshPrivateKeyPem? =
        (credentials.read(credentialKey(machineId)) as? ApiKeyCredential)
            ?.key
            ?.let(::SshPrivateKeyPem)

    open suspend fun delete(machineId: String) {
        credentials.delete(credentialKey(machineId))
    }

    private fun credentialKey(machineId: String): String {
        val key = "$KEY_PREFIX$machineId"
        require(key.length <= MAX_KEY_LENGTH && machineId.all { it.isDigit() || it in 'a'..'f' }) {
            "Invalid machine id"
        }
        return key
    }

    private companion object {
        const val KEY_PREFIX = "machine-"
        const val MAX_KEY_LENGTH = 64
    }
}
