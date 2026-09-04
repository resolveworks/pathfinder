package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals

class CloudflareTest {

    @Test
    fun `substitutes account id placeholder`() {
        assertEquals(
            "https://gateway.acc.cloudflare.com/v1",
            resolveCloudflareBaseUrl(
                "https://gateway.{CLOUDFLARE_ACCOUNT_ID}.cloudflare.com/v1",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc")
            )
        )
    }

    @Test
    fun `substitutes both cloudflare placeholders`() {
        assertEquals(
            "https://acc.gw.cloudflare.com/v1",
            resolveCloudflareBaseUrl(
                "https://{CLOUDFLARE_ACCOUNT_ID}.{CLOUDFLARE_GATEWAY_ID}.cloudflare.com/v1",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw")
            )
        )
    }

    @Test
    fun `missing value leaves placeholder intact like pi`() {
        assertEquals(
            "https://{CLOUDFLARE_ACCOUNT_ID}.example/v1",
            resolveCloudflareBaseUrl(
                "https://{CLOUDFLARE_ACCOUNT_ID}.example/v1",
                mapOf("OTHER" to "x")
            )
        )
    }

    @Test
    fun `non-cloudflare braces untouched`() {
        val url = "https://{ID}.example/{model}"
        assertEquals(url, resolveCloudflareBaseUrl(url, mapOf("ID" to "x")))
    }

    @Test
    fun `empty env returns input unchanged`() {
        val url = "https://{CLOUDFLARE_ACCOUNT_ID}.example/v1"
        assertEquals(url, resolveCloudflareBaseUrl(url, emptyMap()))
    }
}
