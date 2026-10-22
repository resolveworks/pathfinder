package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ACME = ClientAttribution(name = "acme", url = "https://acme.example")

private data class Target(override val provider: String, override val baseUrl: String) :
    ClientIdentityTarget

private fun target(provider: String, baseUrl: String) = Target(provider, baseUrl)

private fun on(attribution: ClientAttribution) = ClientAttributionSetting.On(attribution)

class IdentityTest {

    @Test
    fun `has pathfinder user agent shape by default`() {
        val ua = formatUserAgent()
        assertTrue(ua.startsWith("pathfinder (android "), ua)
        assertTrue(ua.endsWith(")"), ua)
        val fields = ua.removePrefix("pathfinder (").removeSuffix(")").split("; ")
        assertEquals(2, fields.size, ua)
    }

    @Test
    fun `formats user agent modes and version`() {
        assertEquals(formatUserAgent(), formatUserAgent(ClientIdentity()))
        assertEquals("pathfinder", formatUserAgent(ClientIdentity(userAgent = UserAgentMode.Short)))
        assertEquals(
            "acme/1.0",
            formatUserAgent(ClientIdentity(userAgent = UserAgentMode.Custom("acme/1.0")))
        )
        val versioned = formatUserAgent(ClientIdentity(version = "9.9.9"))
        assertTrue(versioned.startsWith("pathfinder/9.9.9 (android "), versioned)
    }

    @Test
    fun `merges identities field by field`() {
        assertNull(mergeClientIdentity(null, null))
        assertEquals(
            ClientIdentity(
                attribution = on(PATHFINDER_ATTRIBUTION),
                userAgent = UserAgentMode.Short,
                version = "1.0.0"
            ),
            mergeClientIdentity(
                ClientIdentity(
                    attribution = on(PATHFINDER_ATTRIBUTION),
                    userAgent = UserAgentMode.Short
                ),
                ClientIdentity(version = "1.0.0")
            )
        )
        assertEquals(
            ClientIdentity(attribution = ClientAttributionSetting.Off),
            mergeClientIdentity(
                ClientIdentity(attribution = on(PATHFINDER_ATTRIBUTION)),
                ClientIdentity(attribution = ClientAttributionSetting.Off)
            )
        )
    }

    @Test
    fun `sends no attribution unless enabled`() {
        val openRouter = target("openrouter", "https://openrouter.ai/api/v1")
        assertEquals(emptyMap(), getAttributionHeaders(openRouter))
        assertEquals(
            emptyMap(),
            getAttributionHeaders(
                openRouter,
                ClientIdentity(attribution = ClientAttributionSetting.Off)
            )
        )
        assertEquals(mapOf("User-Agent" to formatUserAgent()), getIdentityHeaders(openRouter))
    }

    @Test
    fun `attributes OpenRouter requests`() {
        val openRouter = target("openrouter", "https://openrouter.ai/api/v1")
        assertEquals(
            mapOf(
                "HTTP-Referer" to "https://github.com/resolveworks/pathfinder",
                "X-OpenRouter-Title" to "pathfinder"
            ),
            getAttributionHeaders(
                openRouter,
                ClientIdentity(attribution = on(PATHFINDER_ATTRIBUTION))
            )
        )
        assertEquals(
            mapOf(
                "HTTP-Referer" to "https://acme.example",
                "X-OpenRouter-Title" to "acme",
                "X-OpenRouter-Categories" to "cli-agent,cloud-agent"
            ),
            getAttributionHeaders(
                openRouter,
                ClientIdentity(
                    attribution = on(ACME.copy(categories = listOf("cli-agent", "cloud-agent")))
                )
            )
        )
        assertEquals(
            mapOf(
                "HTTP-Referer" to "https://acme.example",
                "X-OpenRouter-Title" to "acme"
            ),
            getAttributionHeaders(
                openRouter,
                ClientIdentity(attribution = on(ACME.copy(categories = emptyList())))
            )
        )
        // Custom providers routed through OpenRouter, including base URLs that are not valid URLs.
        assertEquals(
            "pathfinder",
            getAttributionHeaders(
                target("custom", "not-a-url-openrouter.ai"),
                ClientIdentity(attribution = on(PATHFINDER_ATTRIBUTION))
            )["X-OpenRouter-Title"]
        )
    }

    @Test
    fun `attributes NVIDIA NIM with a capitalized name and OpenCode with the name`() {
        assertEquals(
            mapOf("X-BILLING-INVOKE-ORIGIN" to "Pathfinder"),
            getAttributionHeaders(
                target("nvidia", "https://example.test/v1"),
                ClientIdentity(attribution = on(PATHFINDER_ATTRIBUTION))
            )
        )
        assertEquals(
            mapOf("X-BILLING-INVOKE-ORIGIN" to "Acme"),
            getAttributionHeaders(
                target("custom-nim", "https://integrate.api.nvidia.com/v1"),
                ClientIdentity(attribution = on(ACME))
            )
        )
        assertEquals(
            mapOf("x-opencode-client" to "pathfinder"),
            getAttributionHeaders(
                target("opencode", "https://opencode.ai/zen/v1"),
                ClientIdentity(attribution = on(PATHFINDER_ATTRIBUTION))
            )
        )
        assertEquals(
            mapOf("x-opencode-client" to "acme"),
            getAttributionHeaders(
                target("custom", "https://opencode.ai/zen/v1"),
                ClientIdentity(attribution = on(ACME))
            )
        )
    }

    @Test
    fun `does not attribute NVIDIA models routed through gateways to NVIDIA`() {
        assertEquals(
            emptyMap(),
            getAttributionHeaders(
                target("vercel-ai-gateway", "https://ai-gateway.vercel.sh/v1"),
                ClientIdentity(attribution = on(PATHFINDER_ATTRIBUTION))
            )
        )
    }

    @Test
    fun `adds the OpenCode session header only for OpenCode targets`() {
        assertEquals(
            mapOf("x-opencode-session" to "session-1"),
            getOpenCodeSessionHeaders(target("custom", "https://opencode.ai/zen/v1"), "session-1")
        )
        assertNull(
            getOpenCodeSessionHeaders(target("custom", "https://example.test/v1"), "session-1")
        )
        assertNull(
            getOpenCodeSessionHeaders(target("opencode", "https://opencode.ai/zen/v1"), null)
        )
    }
}
