package works.resolve.pathfinder.tools.websearch

import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.InMemoryCredentialStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchProviderServiceTest {

    private fun service() = SearchProviderService(InMemoryCredentialStore())

    @Test
    fun `only brave is listed as a provider`() {
        val providers = service().providers
        assertEquals(listOf(SearchProvider(SearchProviderService.BRAVE_PROVIDER_ID, "Brave Search")), providers)
    }

    @Test
    fun `credential is namespaced under search_brave`() = runBlocking<Unit> {
        val store = InMemoryCredentialStore()
        SearchProviderService(store).saveApiKey("brave", "key-1")
        val stored = store.read(SearchProviderService.BRAVE_CREDENTIAL_ID)
        assertTrue(stored is ApiKeyCredential && stored.key == "key-1")
        assertNull(store.read("brave"))
    }

    @Test
    fun `isConfigured reflects save and remove`() = runBlocking<Unit> {
        val service = service()
        assertFalse(service.isConfigured("brave"))
        service.saveApiKey("brave", "key-1")
        assertTrue(service.isConfigured("brave"))
        assertEquals("key-1", service.apiKey("brave"))
        service.remove("brave")
        assertFalse(service.isConfigured("brave"))
        assertNull(service.apiKey("brave"))
    }

    @Test
    fun `saveApiKey rejects blank keys`() = runBlocking<Unit> {
        val service = service()
        assertFailsWith<IllegalArgumentException> { service.saveApiKey("brave", "") }
        assertFailsWith<IllegalArgumentException> { service.saveApiKey("brave", "   ") }
        assertFalse(service.isConfigured("brave"))
    }

    @Test
    fun `unknown provider ids are rejected`() = runBlocking<Unit> {
        val service = service()
        assertFailsWith<IllegalArgumentException> { service.isConfigured("google") }
        assertFailsWith<IllegalArgumentException> { service.saveApiKey("google", "key") }
        assertFailsWith<IllegalArgumentException> { service.remove("google") }
        assertFailsWith<IllegalArgumentException> { service.apiKey("google") }
    }

    @Test
    fun `remove is a no-op when not configured`() = runBlocking<Unit> {
        val service = service()
        service.remove("brave")
        assertFalse(service.isConfigured("brave"))
    }

    @Test
    fun `blank stored credential key counts as unconfigured`() = runBlocking<Unit> {
        // saveApiKey prevents blanks, but the shared credential store can
        // already contain one (e.g. written elsewhere).
        val store = InMemoryCredentialStore()
        store.modify(SearchProviderService.BRAVE_CREDENTIAL_ID) { ApiKeyCredential(key = "   ") }
        val service = SearchProviderService(store)
        assertFalse(service.isConfigured("brave"))
        assertNull(service.apiKey("brave"))
    }
}
