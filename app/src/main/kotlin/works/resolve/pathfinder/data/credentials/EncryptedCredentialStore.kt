package works.resolve.pathfinder.data.credentials

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persistent [CredentialStore]: stores one credential per provider as
 * AES-GCM ciphertext (via [KeystoreAeadCipher], backed by the Android
 * Keystore) in per-provider files under the app's private storage, serialized
 * with [CredentialCodec] (type-tagged JSON only).
 *
 * Writes are serialized per provider with an in-process mutex — the app is a
 * single Android process. Key material never leaves the credential boundary
 * in plaintext and is never logged.
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

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(providerId: String): Mutex = locks.computeIfAbsent(providerId) { Mutex() }

    private fun fileFor(providerId: String): File {
        require(PROVIDER_ID_REGEX.matches(providerId)) { "Invalid provider id" }
        return File(dir, "$providerId.bin")
    }

    private suspend fun readRaw(providerId: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        if (!file.exists()) return@withContext null
        String(decrypt(file.readBytes()), Charsets.UTF_8)
    }

    private suspend fun writeRaw(providerId: String, encoded: String) = withContext(Dispatchers.IO) {
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

    override suspend fun set(providerId: String, credential: Credential) {
        lockFor(providerId).withLock { writeRaw(providerId, CredentialCodec.encode(credential)) }
    }

    override suspend fun list(): List<String> {
        val names = withContext(Dispatchers.IO) {
            (dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) } ?: emptyArray())
                .map { it.name.removeSuffix(FILE_SUFFIX) }
        }
        val configured = mutableListOf<String>()
        for (providerId in names) {
            // Each entry is read under its provider lock so a same-provider
            // modify/delete cannot interleave, and storage/format failures
            // reject: configured credentials never silently disappear from
            // the listing.
            val credential = lockFor(providerId).withLock { decodeRaw(providerId) } ?: continue
            configured += providerId
        }
        return configured.sorted()
    }

    override suspend fun delete(providerId: String): Unit = lockFor(providerId).withLock {
        withContext(Dispatchers.IO) { fileFor(providerId).delete() }
    }

    private companion object {
        const val DIRECTORY = "credentials"
        const val FILE_SUFFIX = ".bin"
        val PROVIDER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
