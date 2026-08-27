package works.resolve.pathfinder.ai.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (Proof of Key for Code Exchange, RFC 7636) code verifier/challenge pair,
 * mirroring pi's shared OAuth PKCE primitive
 * (packages/ai/src/auth/oauth/pkce.ts, `generatePKCE`).
 *
 * Upstream generates 32 random bytes and base64url-encodes them without
 * padding to form the verifier (43 characters), then computes the S256
 * challenge as the unpadded base64url encoding of `SHA-256(verifier ASCII)`.
 * This port preserves those exact shapes using only platform APIs:
 * [SecureRandom] for entropy, [MessageDigest] for SHA-256, and
 * [java.util.Base64] URL-safe encoding without padding.
 */
data class Pkce(val verifier: String, val challenge: String)

/**
 * Provider-neutral PKCE generator.
 *
 * Entropy is supplied by [randomBytes]; production code uses [SecureRandom]
 * (the equivalent of pi's `crypto.getRandomValues`). Tests may inject a
 * deterministic source for reference-vector assertions.
 */
class PkceGenerator(private val randomBytes: (Int) -> ByteArray) {
    constructor() : this(SecureRandom())
    constructor(random: SecureRandom) : this({ count ->
        ByteArray(count).also(random::nextBytes)
    })

    /**
     * Generates a PKCE pair: a 32-random-byte verifier encoded as unpadded
     * base64url and its S256 challenge.
     */
    fun generate(): Pkce {
        val verifier = base64url(randomBytes(32))
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, base64url(digest))
    }

    companion object {
        /**
         * Computes the S256 challenge for a verifier, matching pi's
         * `SHA-256(base64url(verifierBytes))` derivation.
         */
        fun challengeFor(verifier: String): String = base64url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

        /** Unpadded base64url encoding, matching pi's `base64urlEncode`. */
        internal fun base64url(bytes: ByteArray): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
