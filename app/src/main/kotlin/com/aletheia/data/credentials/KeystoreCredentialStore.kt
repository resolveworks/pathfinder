package com.aletheia.data.credentials

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores API keys as AES-GCM ciphertext (via an [AeadCipher] backed by the
 * Android Keystore) in per-provider files under the app's private storage.
 * Key material never leaves the credential boundary in plaintext form.
 */
@Singleton
class KeystoreCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: AeadCipher,
) : CredentialStore {

    private val dir: File
        get() = File(context.filesDir, DIRECTORY)

    private fun fileFor(providerId: String): File {
        require(PROVIDER_ID_REGEX.matches(providerId)) {
            "Invalid provider id"
        }
        return File(dir, "$providerId.bin")
    }

    override suspend fun getApiKey(providerId: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        if (!file.exists()) return@withContext null
        String(cipher.decrypt(file.readBytes()), Charsets.UTF_8)
    }

    override suspend fun setApiKey(providerId: String, apiKey: String) = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(cipher.encrypt(apiKey.toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not persist credential" }
        }
    }

    override suspend fun deleteApiKey(providerId: String): Unit = withContext(Dispatchers.IO) {
        fileFor(providerId).delete()
    }

    private companion object {
        const val DIRECTORY = "credentials"
        val PROVIDER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
