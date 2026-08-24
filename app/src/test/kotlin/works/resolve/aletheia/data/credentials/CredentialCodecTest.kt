package works.resolve.aletheia.data.credentials

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import works.resolve.aletheia.ai.auth.ApiKeyCredential
import works.resolve.aletheia.ai.auth.OAuthCredential

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
    fun `malformed object-intended json is rejected, not treated as a bare key`() {
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("{bad}") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("{}") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("{\"type\":\"api_key\",\"key\":}") }
        // Truncated object JSON (missing closing brace) must throw too.
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("{\"type\":\"oauth\"") }
    }

    @Test
    fun `genuine non-object legacy strings still migrate as bare keys`() {
        assertEquals(ApiKeyCredential("sk-legacy"), CredentialCodec.decode("sk-legacy"))
        assertEquals(ApiKeyCredential("weird {braces} inside"), CredentialCodec.decode("weird {braces} inside"))
    }

    @Test
    fun `non-string type tag is rejected`() {
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":123}""") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":["api_key"]}""") }
    }

    @Test
    fun `non-string api key is rejected instead of silently becoming null`() {
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":"api_key","key":123}""") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"key":null,"env":{}}""") }
    }

    @Test
    fun `numeric-string oauth expires is rejected, json number is required`() {
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"oauth","access":"a","refresh":"r","expires":"123"}""")
        }
        val decoded = CredentialCodec.decode("""{"type":"oauth","access":"a","refresh":"r","expires":123}""")
        assertEquals(123L, (decoded as OAuthCredential).expires)
    }

    @Test
    fun `round trips keyless env-only credential`() {
        val credential = ApiKeyCredential(env = mapOf("A" to "1"))
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `legacy bare key entry decodes as key`() {
        assertEquals(ApiKeyCredential("sk-legacy"), CredentialCodec.decode("sk-legacy"))
    }

    @Test
    fun `legacy key-env record without type tag decodes as api key`() {
        val raw = """{"key":"sk-legacy","env":{"A":"1"}}"""
        assertEquals(ApiKeyCredential("sk-legacy", mapOf("A" to "1")), CredentialCodec.decode(raw))
    }

    @Test
    fun `json object without key field or type tag is rejected`() {
        // Neither the current nor a legacy shape: malformed, not a bare key.
        val raw = """{"not":"key"}"""
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode(raw) }
    }

    @Test
    fun `key containing braces still round trips`() {
        val credential = ApiKeyCredential(key = "{weird} key")
        val decoded = CredentialCodec.decode(CredentialCodec.encode(credential))
        assertEquals(credential, decoded)
    }

    @Test
    fun `decode tolerates unknown api-key fields`() {
        val raw = """{"type":"api_key","key":"sk-x","env":{},"extra":1}"""
        assertEquals(ApiKeyCredential("sk-x"), CredentialCodec.decode(raw))
    }

    @Test
    fun `round trips oauth credential`() {
        val credential = OAuthCredential(
            access = "access-token",
            refresh = "refresh-token",
            expires = 1234567890L,
        )
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `oauth provider-specific extra fields round trip`() {
        val credential = OAuthCredential(
            access = "a",
            refresh = "r",
            expires = 42L,
            extras = mapOf(
                "enterpriseUrl" to kotlinx.serialization.json.JsonPrimitive("https://company.ghe.com"),
                "scopes" to kotlinx.serialization.json.Json.parseToJsonElement("""["read","write"]"""),
            ),
        )
        val encoded = CredentialCodec.encode(credential)
        assertTrue(encoded.contains("enterpriseUrl"))
        assertEquals(credential, CredentialCodec.decode(encoded))
    }

    @Test
    fun `oauth decode preserves unknown fields into extras`() {
        val raw = """{"type":"oauth","access":"a","refresh":"r","expires":1,"futureField":{"x":true}}"""
        val decoded = CredentialCodec.decode(raw) as OAuthCredential
        assertEquals(OAuthCredential("a", "r", 1, extras = mapOf("futureField" to kotlinx.serialization.json.Json.parseToJsonElement("""{"x":true}"""))), decoded)
    }

    @Test
    fun `unknown credential type is rejected`() {
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"mtls","key":"k"}""")
        }
    }

    @Test
    fun `oauth with missing required fields is rejected`() {
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"oauth","refresh":"r","expires":1}""")
        }
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"oauth","access":"a","refresh":"r"}""")
        }
    }

    @Test
    fun `api key credential toString never contains the key`() {
        val credential = ApiKeyCredential(key = "sk-SECRET", env = mapOf("A" to "1"))
        assertFalse(credential.toString().contains("sk-SECRET"))
        assertEquals("ApiKeyCredential(key=<redacted>, env=[A])", credential.toString())
    }

    @Test
    fun `oauth reserved extra keys are rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            OAuthCredential(
                access = "a",
                refresh = "r",
                expires = 1L,
                extras = mapOf("refresh" to kotlinx.serialization.json.JsonPrimitive("evil")),
            )
        }
        assertTrue(error.message.orEmpty().contains("refresh"))
    }

    @Test
    fun `oauth credential toString never contains tokens`() {
        val credential = OAuthCredential(
            access = "SECRET-ACCESS",
            refresh = "SECRET-REFRESH",
            expires = 1L,
            extras = mapOf("enterpriseUrl" to kotlinx.serialization.json.JsonPrimitive("https://secret.example")),
        )
        val text = credential.toString()
        assertFalse(text.contains("SECRET-ACCESS"))
        assertFalse(text.contains("SECRET-REFRESH"))
        assertFalse(text.contains("secret.example"))
        assertTrue(text.contains("expires=1"))
    }
}
