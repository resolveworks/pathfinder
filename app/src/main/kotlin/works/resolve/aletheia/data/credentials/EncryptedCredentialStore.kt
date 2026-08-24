package works.resolve.aletheia.data.credentials

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import works.resolve.aletheia.ai.auth.Credential
import works.resolve.aletheia.ai.auth.CredentialInfo
import works.resolve.aletheia.ai.auth.CredentialStore

/**
 * Persistent [CredentialStore] (pi contract from
 * `packages/ai/src/auth/types.ts`): stores one credential per provider as
 * AES-GCM ciphertext (via [KeystoreAeadCipher], backed by the Android
 * Keystore) in per-provider files under the app's private storage, serialized
 * with [CredentialCodec] (legacy bare-key and `{key,env}` entries migrate via
 * its decode fallbacks).
 *
 * Writes are serialized per provider with an in-process mutex — the app is a
 * single Android process, so pi's cross-process file-lock requirement
 * collapses to this. Key material never leaves the credential boundary in
 * plaintext and is never logged. Reads that hit a legacy (untyped) record
 * transparently rewrite it in the current tagged shape on next write.
 */
class EncryptedCredentialStore(
    private val dir: File,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray,
) : CredentialStore {

    constructor(context: Context, cipher: KeystoreAeadCipher) : this(
        dir = File(context.filesDir, DIRECTORY),
        encrypt = cipher::encrypt,
        decrypt = cipher::decrypt,
    )

    private val locks = mutableMapOf<String, Mutex>()

    private fun lockFor(providerId: String): Mutex = synchronized(locks) {
        locks.getOrPut(providerId) { Mutex() }
    }

    private fun fileFor(providerId: String): File {
        require(PROVIDER_ID_REGEX.matches(providerId)) { "Invalid provider id" }
        return File(dir, "$providerId.bin")
    }

    private suspend fun readRaw(providerId: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        if (!file.exists()) return@withContext null
        String(decrypt(file.readBytes()), Charsets.UTF_8)
    }

    private suspend fun writeRaw(providerId: String, encoded: String): Unit = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(encrypt(encoded.toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not persist credential" }
        }
    }

    private suspend fun decodeRaw(providerId: String): Credential? =
        readRaw(providerId)?.let { raw ->
            try {
                CredentialCodec.decode(raw)
            } catch (error: CredentialFormatException) {
                throw CredentialFormatException("Stored credential for $providerId is malformed: ${error.message}")
            }
        }

    override suspend fun read(providerId: String): Credential? = lockFor(providerId).withLock {
        decodeRaw(providerId)
    }

    override suspend fun list(): List<CredentialInfo> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) } ?: emptyArray())
            .mapNotNull { file ->
                // Metadata only: the type tag requires decryption, but no
                // secret is exposed and no key command is executed.
                val raw = try {
                    String(decrypt(file.readBytes()), Charsets.UTF_8)
                } catch (_: Exception) {
                    return@mapNotNull null // Unreadable/corrupt entry: skip, never throw secrets-bearing errors.
                }
                val type = try {
                    CredentialCodec.decode(raw).type
                } catch (_: CredentialFormatException) {
                    return@mapNotNull null
                }
                CredentialInfo(file.name.removeSuffix(FILE_SUFFIX), type)
            }
            .sortedBy { it.providerId }
    }

    override suspend fun modify(
        providerId: String,
        update: suspend (current: Credential?) -> Credential?,
    ): Credential? = lockFor(providerId).withLock {
        val current = decodeRaw(providerId)
        val next = update(current)
        if (next != null) writeRaw(providerId, CredentialCodec.encode(next))
        next ?: current
    }

    override suspend fun delete(providerId: String): Unit = lockFor(providerId).withLock {
        withContext(Dispatchers.IO) { fileFor(providerId).delete() }
        Unit
    }

    private companion object {
        const val DIRECTORY = "credentials"
        const val FILE_SUFFIX = ".bin"
        val PROVIDER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
