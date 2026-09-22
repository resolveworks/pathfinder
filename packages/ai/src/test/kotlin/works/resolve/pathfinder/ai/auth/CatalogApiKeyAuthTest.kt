package works.resolve.pathfinder.ai.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.providers.AuthPrompt as CatalogPrompt
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderAuth as CatalogProviderAuthMetadata

class CatalogApiKeyAuthTest {

    private fun provider(vararg prompts: CatalogPrompt) = CatalogProvider(
        id = "acme",
        name = "Acme",
        baseUrl = "https://api.acme.test",
        auth = CatalogProviderAuthMetadata(
            label = "Acme API key",
            prompts = prompts.toList()
        ),
        models = emptyList()
    )

    private fun envContext(vararg values: Pair<String, String>): AuthContext =
        object : AuthContext {
            override suspend fun env(name: String): String? = values.toMap()[name]

            override suspend fun fileExists(path: String): Boolean = false
        }

    @Test
    fun `login stores blank entries like pi prompts`() = runTest {
        val interaction = object : AuthInteraction {
            override suspend fun prompt(prompt: AuthPrompt): String = " "

            override suspend fun notify(event: AuthEvent) {}
        }
        val credential = CatalogProviderAuth(
            provider(CatalogPrompt("ACME_API_KEY", "Enter Acme API key"))
        ).apiKey!!.login!!.invoke(interaction)

        assertEquals(" ", credential.key)
    }

    @Test
    fun `stored fields win over ambient env per field`() = runTest {
        val auth = CatalogProviderAuth(
            provider(
                CatalogPrompt("ACME_API_KEY", "Enter Acme API key"),
                CatalogPrompt("ACME_ACCOUNT_ID", "Enter Acme account id")
            )
        ).apiKey!!

        val resolved = auth.resolve(
            envContext("ACME_API_KEY" to "ambient-key", "ACME_ACCOUNT_ID" to "ambient-account"),
            ApiKeyCredential(key = "stored-key", env = mapOf("ACME_ACCOUNT_ID" to "stored-account"))
        )

        assertEquals("stored-key", resolved?.auth?.apiKey)
        assertEquals(mapOf("ACME_ACCOUNT_ID" to "stored-account"), resolved?.env)
        assertEquals("stored credential", resolved?.source)
    }

    @Test
    fun `missing stored fields fall through to ambient env`() = runTest {
        val auth = CatalogProviderAuth(
            provider(
                CatalogPrompt("ACME_API_KEY", "Enter Acme API key"),
                CatalogPrompt("ACME_ACCOUNT_ID", "Enter Acme account id")
            )
        ).apiKey!!

        val resolved = auth.resolve(
            envContext("ACME_API_KEY" to "ambient-key", "ACME_ACCOUNT_ID" to "ambient-account"),
            ApiKeyCredential(key = "stored-key")
        )

        assertEquals("stored-key", resolved?.auth?.apiKey)
        assertEquals(mapOf("ACME_ACCOUNT_ID" to "ambient-account"), resolved?.env)
        assertEquals("stored credential", resolved?.source)
    }

    @Test
    fun `stored blank field wins over ambient env and resolves unconfigured`() = runTest {
        val auth = CatalogProviderAuth(
            provider(
                CatalogPrompt("ACME_API_KEY", "Enter Acme API key"),
                CatalogPrompt("ACME_ACCOUNT_ID", "Enter Acme account id")
            )
        ).apiKey!!

        // pi's `fromCredential !== undefined` short-circuit: the stored blank
        // blocks the ambient value, and its JS falsiness fails resolution.
        val resolved = auth.resolve(
            envContext("ACME_API_KEY" to "ambient-key", "ACME_ACCOUNT_ID" to "ambient-account"),
            ApiKeyCredential(key = "", env = mapOf("ACME_ACCOUNT_ID" to "stored-account"))
        )
        assertNull(resolved)

        val blankEnvSlot = auth.resolve(
            envContext("ACME_ACCOUNT_ID" to "ambient-account"),
            ApiKeyCredential(key = "stored-key", env = mapOf("ACME_ACCOUNT_ID" to ""))
        )
        assertNull(blankEnvSlot)
    }

    @Test
    fun `empty ambient value resolves unconfigured like js falsiness`() = runTest {
        val auth = CatalogProviderAuth(
            provider(CatalogPrompt("ACME_API_KEY", "Enter Acme API key"))
        ).apiKey!!

        assertNull(auth.resolve(envContext("ACME_API_KEY" to ""), null))
    }
}
