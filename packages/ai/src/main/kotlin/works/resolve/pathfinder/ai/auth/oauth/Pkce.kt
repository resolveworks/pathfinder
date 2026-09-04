package works.resolve.pathfinder.ai.auth.oauth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (RFC 7636) verifier/challenge pair: 32 random bytes unpadded
 * base64url-encode to the 43-character RFC-minimum verifier, and the
 * challenge is its S256 form, `BASE64URL(SHA256(ASCII(verifier)))`.
 */
data class Pkce(val verifier: String, val challenge: String)

/** Entropy seam mirrors pi's `crypto.getRandomValues`; tests inject deterministic bytes. */
class PkceGenerator(private val randomBytes: (Int) -> ByteArray) {
    constructor() : this(SecureRandom())
    constructor(random: SecureRandom) : this({ count ->
        ByteArray(count).also(random::nextBytes)
    })

    fun generate(): Pkce {
        val verifier = base64url(randomBytes(32))
        val digest = MessageDigest.getInstance(
            "SHA-256"
        ).digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, base64url(digest))
    }

    companion object {
        fun challengeFor(verifier: String): String = base64url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

        internal fun base64url(bytes: ByteArray): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
