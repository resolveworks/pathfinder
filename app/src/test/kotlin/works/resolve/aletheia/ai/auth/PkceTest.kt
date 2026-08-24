package works.resolve.aletheia.ai.auth

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [PkceGenerator], ported from pi's
 * packages/ai/src/auth/oauth/pkce.ts (`generatePKCE`).
 */
class PkceTest {

    @Test
    fun `rfc 7636 appendix B reference vector`() {
        // RFC 7636 Appendix B, S256 example
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(challenge, PkceGenerator.challengeFor(verifier))
    }

    @Test
    fun `32 random bytes yield a 43-character verifier and 43-character challenge`() {
        // pi encodes exactly 32 random bytes -> 43 unpadded base64url chars;
        // SHA-256 digest is 32 bytes -> 43 chars as well.
        val deterministic = PkceGenerator { count -> ByteArray(count) { it.toByte() } }
        val pkce = deterministic.generate()
        assertEquals(43, pkce.verifier.length)
        assertEquals(43, pkce.challenge.length)
    }

    @Test
    fun `verifier and challenge use only the unreserved base64url charset`() {
        repeat(20) {
            val pkce = PkceGenerator(SecureRandom()).generate()
            for (s in listOf(pkce.verifier, pkce.challenge)) {
                assertTrue(s.matches(Regex("^[A-Za-z0-9-_]+$")), "bad charset: $s")
                assertTrue(!s.contains('='), "must be unpadded")
            }
        }
    }

    @Test
    fun `challenge matches SHA-256 over ASCII verifier`() {
        val pkce = PkceGenerator(SecureRandom()).generate()
        assertEquals(PkceGenerator.challengeFor(pkce.verifier), pkce.challenge)
    }

    @Test
    fun `injected entropy makes generation deterministic`() {
        val bytes = ByteArray(32) { (it * 7 + 3).toByte() }
        val a = PkceGenerator { bytes.copyOf(it) }.generate()
        val b = PkceGenerator { bytes.copyOf(it) }.generate()
        assertEquals(a, b)
        // And differs from a different entropy source.
        assertNotEquals(a.verifier, PkceGenerator { ByteArray(it) { 0 }.copyOf(it) }.generate().verifier)
    }
}
