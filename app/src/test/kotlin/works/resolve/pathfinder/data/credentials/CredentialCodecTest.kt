package works.resolve.pathfinder.data.credentials

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialCodecTest {

    @Test
    fun `round trips api key`() {
        val credential = Credential.ApiKey(key = "sk-test")
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `api key encoding is the pinned auth dot json shape`() {
        assertEquals(
            """{"type":"api_key","key":"sk-test"}""",
            CredentialCodec.encode(Credential.ApiKey("sk-test")),
        )
    }

    @Test
    fun `key containing braces still round trips`() {
        val credential = Credential.ApiKey(key = "{weird} key")
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `decode tolerates unknown fields`() {
        val raw = """{"type":"api_key","key":"sk-x","extra":1}"""
        assertEquals(Credential.ApiKey("sk-x"), CredentialCodec.decode(raw))
    }

    @Test
    fun `round trips oauth`() {
        val credential = Credential.ChatGptOAuth(
            accessToken = "acc",
            refreshToken = "ref",
            expiresAtEpochMillis = 1_700_000_000_000,
            accountId = "acct-123",
        )
        assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
    }

    @Test
    fun `oauth encoding matches the pinned auth dot json field names`() {
        assertEquals(
            """{"type":"oauth","access":"acc","refresh":"ref","expires":1700000000000,"accountId":"acct"}""",
            CredentialCodec.encode(
                Credential.ChatGptOAuth("acc", "ref", 1_700_000_000_000, "acct"),
            ),
        )
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
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":null}""") }
    }

    @Test
    fun `missing or non-string key is rejected`() {
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":"api_key"}""") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":"api_key","key":123}""") }
        assertFailsWith<CredentialFormatException> { CredentialCodec.decode("""{"type":"api_key","key":null}""") }
    }

    @Test
    fun `oauth with missing fields is rejected`() {
        val cases = listOf(
            """{"type":"oauth","refresh":"r","expires":1,"accountId":"acct"}""",
            """{"type":"oauth","access":"a","expires":1,"accountId":"acct"}""",
            """{"type":"oauth","access":"a","refresh":"r","accountId":"acct"}""",
            """{"type":"oauth","access":"a","refresh":"r","expires":1}""",
        )
        for (raw in cases) {
            assertFailsWith<CredentialFormatException> { CredentialCodec.decode(raw) }
        }
    }

    @Test
    fun `oauth with mistyped fields is rejected`() {
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"oauth","access":1,"refresh":"r","expires":1,"accountId":"a"}""")
        }
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"oauth","access":"a","refresh":"r","expires":"1","accountId":"a"}""")
        }
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"oauth","access":"a","refresh":"r","expires":null,"accountId":"a"}""")
        }
    }

    @Test
    fun `unknown credential types are rejected`() {
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"type":"mtls","key":"k"}""")
        }
        assertFailsWith<CredentialFormatException> {
            CredentialCodec.decode("""{"key":"sk-x"}""")
        }
    }

    @Test
    fun `toString never contains secret material`() {
        val apiKey = Credential.ApiKey(key = "sk-SECRET")
        assertFalse(apiKey.toString().contains("sk-SECRET"))
        assertEquals("ApiKey(key=<redacted>)", apiKey.toString())

        val oauth = Credential.ChatGptOAuth(
            accessToken = "ACCESS-SECRET",
            refreshToken = "REFRESH-SECRET",
            expiresAtEpochMillis = 1,
            accountId = "acct-123",
        )
        assertFalse(oauth.toString().contains("ACCESS-SECRET"))
        assertFalse(oauth.toString().contains("REFRESH-SECRET"))
        assertTrue(oauth.toString().contains("acct-123"))
    }
}
