package com.aletheia.data.credentials

/**
 * Authenticated encryption of byte arrays. Abstracts Android Keystore so the
 * file-based storage logic can be tested on the JVM with a fake.
 */
interface AeadCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}
