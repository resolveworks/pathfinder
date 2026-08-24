package works.resolve.aletheia.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderEnvTest {

    @Test
    fun `substitutes known placeholder`() {
        assertEquals(
            "https://gateway.acc.example/v1",
            substituteEnvPlaceholders(
                "https://gateway.{CLOUDFLARE_ACCOUNT_ID}.example/v1",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc"),
            ),
        )
    }

    @Test
    fun `substitutes multiple placeholders`() {
        assertEquals(
            "https://acc.gw.cloudflare.com/v1",
            substituteEnvPlaceholders(
                "https://{CLOUDFLARE_ACCOUNT_ID}.{CLOUDFLARE_GATEWAY_ID}.cloudflare.com/v1",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
            ),
        )
    }

    @Test
    fun `missing key leaves placeholder intact`() {
        assertEquals(
            "https://{CLOUDFLARE_ACCOUNT_ID}.example/v1",
            substituteEnvPlaceholders(
                "https://{CLOUDFLARE_ACCOUNT_ID}.example/v1",
                mapOf("OTHER" to "x"),
            ),
        )
    }

    @Test
    fun `non-env braces untouched`() {
        assertEquals(
            "https://x.example/{model}",
            substituteEnvPlaceholders("https://{ID}.example/{model}", mapOf("ID" to "x")),
        )
    }

    @Test
    fun `empty env returns input unchanged`() {
        val url = "https://{A}.example/v1"
        assertEquals(url, substituteEnvPlaceholders(url, emptyMap()))
    }
}
