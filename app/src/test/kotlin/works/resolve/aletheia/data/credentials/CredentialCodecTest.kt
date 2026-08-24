package works.resolve.aletheia.data.credentials

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CredentialCodecTest {

    @Test
    fun `round trips key and env`() {
        val credential = ApiKeyCredential(
            key = "sk-test",
            env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
        )
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `round trips bare key with empty env`() {
        val credential = ApiKeyCredential(key = "sk-plain")
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `legacy bare key entry decodes as key`() {
        assertEquals(ApiKeyCredential("sk-legacy"), CredentialCodec.decode("sk-legacy"))
    }

    @Test
    fun `json object without key field falls back to legacy key`() {
        // Not the codec's shape: treated as a bare key.
        val raw = """{"not":"key"}"""
        assertEquals(ApiKeyCredential(raw), CredentialCodec.decode(raw))
    }

    @Test
    fun `key containing braces still round trips`() {
        val credential = ApiKeyCredential(key = "{weird} key")
        val decoded = CredentialCodec.decode(CredentialCodec.encode(credential))
        assertEquals(credential, decoded)
    }

    @Test
    fun `decode tolerates unknown fields`() {
        val raw = """{"key":"sk-x","env":{},"extra":1}"""
        assertEquals(ApiKeyCredential("sk-x"), CredentialCodec.decode(raw))
    }

    @Test
    fun `credential toString never contains the key`() {
        val credential = ApiKeyCredential(key = "sk-SECRET", env = mapOf("A" to "1"))
        assertFalse(credential.toString().contains("sk-SECRET"))
        assertEquals("ApiKeyCredential(key=<redacted>, env=[A])", credential.toString())
    }
}
