package works.resolve.pathfinder.data.credentials

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialCodecTest {

    @Test
    fun `round trips api key`() {
        val credential = ApiKeyCredential(key = "sk-test")
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `key containing braces still round trips`() {
        val credential = ApiKeyCredential(key = "{weird} key")
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `decode tolerates unknown fields`() {
        val raw = """{"type":"api_key","key":"sk-x","extra":1}"""
        assertEquals(ApiKeyCredential("sk-x"), CredentialCodec.decode(raw))
    }

    @Test
    fun `malformed json is rejected`() {
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("{bad}") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("{\"type\":\"api_key\",\"key\":}") }
    }

    @Test
    fun `non-object strings are rejected`() {
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("sk-legacy") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("") }
    }

    @Test
    fun `non-string type tag is rejected`() {
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":123}""") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":["api_key"]}""") }
    }

    @Test
    fun `missing or non-string key is rejected`() {
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":"api_key"}""") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":"api_key","key":123}""") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":"api_key","key":null}""") }
    }

    @Test
    fun `removed oauth and unknown credential types are rejected`() {
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"oauth","access":"a","refresh":"r","expires":123}""")
        }
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"mtls","key":"k"}""")
        }
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"key":"sk-x"}""")
        }
    }

    @Test
    fun `toString never contains the key`() {
        val credential = ApiKeyCredential(key = "sk-SECRET")
        assertFalse(credential.toString().contains("sk-SECRET"))
        assertEquals("ApiKeyCredential(key=<redacted>)", credential.toString())
    }
}
