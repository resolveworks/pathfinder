package works.resolve.aletheia.data.credentials

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores credentials ([ApiKeyCredential]: key + provider env) as AES-GCM
 * ciphertext (via [KeystoreAeadCipher], backed by the Android Keystore) in
 * per-provider files under the app's private storage, serialized with
 * [CredentialCodec] (legacy bare-key entries decode via its fallback). Key
 * material never leaves the credential boundary in plaintext form and is
 * never logged.
 */
class CredentialStore(
    private val context: Context,
    private val cipher: KeystoreAeadCipher,
) : ApiKeyStore {

    private val dir: File
        get() = File(context.filesDir, DIRECTORY)

    private fun fileFor(providerId: String): File {
        require(PROVIDER_ID_REGEX.matches(providerId)) {
            "Invalid provider id"
        }
        return File(dir, "$providerId.bin")
    }

    override suspend fun getCredential(providerId: String): ApiKeyCredential? = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        if (!file.exists()) return@withContext null
        CredentialCodec.decode(String(cipher.decrypt(file.readBytes()), Charsets.UTF_8))
    }

    override suspend fun setCredential(providerId: String, credential: ApiKeyCredential): Unit = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(cipher.encrypt(CredentialCodec.encode(credential).toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not persist credential" }
        }
    }

    override suspend fun deleteCredential(providerId: String): Unit = withContext(Dispatchers.IO) {
        fileFor(providerId).delete()
    }

    private companion object {
        const val DIRECTORY = "credentials"
        val PROVIDER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
