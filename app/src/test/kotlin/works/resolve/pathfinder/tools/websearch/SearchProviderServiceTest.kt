package works.resolve.pathfinder.tools.websearch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.InMemoryCredentialStore
import works.resolve.pathfinder.data.settings.SettingsRepository

class SearchProviderServiceTest {

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun settings() = SettingsRepository(
        PreferenceDataStoreFactory.create(
            scope = settingsScope,
            produceFile = { File.createTempFile("settings", ".preferences_pb") }
        )
    )

    @AfterTest
    fun tearDown() {
        settingsScope.cancel()
    }

    private fun service() = SearchProviderService(settings(), InMemoryCredentialStore())

    @Test
    fun `all five providers are listed`() {
        val providers = service().providers
        assertEquals(
            listOf(
                SearchProvider(SearchProviderService.BRAVE_PROVIDER_ID, "Brave Search"),
                SearchProvider(SearchProviderService.TAVILY_PROVIDER_ID, "Tavily"),
                SearchProvider(SearchProviderService.SERPER_PROVIDER_ID, "Serper"),
                SearchProvider(SearchProviderService.EXA_PROVIDER_ID, "Exa"),
                SearchProvider(SearchProviderService.SEARXNG_PROVIDER_ID, "SearXNG")
            ),
            providers
        )
    }

    @Test
    fun `credential is namespaced under search_brave`() = runBlocking<Unit> {
        val store = InMemoryCredentialStore()
        SearchProviderService(settings(), store).saveApiKey("brave", "key-1")
        val stored = store.read("search_brave")
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
    fun `searxng instance url lives in settings and is normalized`() = runBlocking<Unit> {
        val settings = settings()
        val store = InMemoryCredentialStore()
        val service = SearchProviderService(settings, store)

        assertFalse(service.isConfigured("searxng"))
        assertNull(service.apiKey("searxng"))

        service.saveApiKey("searxng", "https://searx.example.com/?q=legacy/")
        assertTrue(service.isConfigured("searxng"))
        assertEquals("https://searx.example.com", service.apiKey("searxng"))
        assertEquals("https://searx.example.com", settings.currentSettings().searxngInstanceUrl)
        assertNull(store.read("search_searxng"))

        service.remove("searxng")
        assertFalse(service.isConfigured("searxng"))
        assertNull(service.apiKey("searxng"))
    }

    @Test
    fun `searxng rejects invalid instance urls`() = runBlocking<Unit> {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.saveApiKey("searxng", "not a url")
        }
        assertFailsWith<IllegalArgumentException> {
            service.saveApiKey("searxng", "ftp://searx.example.com")
        }
        assertFalse(service.isConfigured("searxng"))
    }
}
