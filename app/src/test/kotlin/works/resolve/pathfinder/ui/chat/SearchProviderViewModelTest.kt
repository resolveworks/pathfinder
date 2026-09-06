package works.resolve.pathfinder.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.Description
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt as AuthInteractionPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.CredentialInfo
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.CredentialType
import works.resolve.pathfinder.ai.auth.MapCatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.MessageEntry
import works.resolve.pathfinder.codingagent.core.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.SessionError
import works.resolve.pathfinder.codingagent.core.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.SessionInfo
import works.resolve.pathfinder.codingagent.core.SessionManager
import works.resolve.pathfinder.codingagent.core.ThinkingLevelEntry
import works.resolve.pathfinder.data.sessions.SessionSource
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.runtime.AgentFactory
import works.resolve.pathfinder.runtime.NativeAgentFactory
import works.resolve.pathfinder.runtime.catalogAuthResolver
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.tools.websearch.SearchProviderService

/** Web-search feature: Brave search-provider credentials and the web_search tool's presence on sessions. */
internal class SearchProviderViewModelTest : ChatHarnessTest() {

    @Test
    fun searchInit_unconfiguredBraveRow_andWebSearchAbsent() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            // A search provider row alone does not satisfy LLM first-run
            // configuration.
            assertEquals(
                listOf(
                    ProviderOption(
                        SearchProviderService.BRAVE_PROVIDER_ID,
                        "Brave Search",
                        configured = false
                    )
                ),
                vm.uiState.value.searchProviderOptions
            )
            assertEquals(0, h.createdAgents.size)

            val prompts = vm.searchProviderAuthPrompts(SearchProviderService.BRAVE_PROVIDER_ID)
            assertEquals(1, prompts.size)
            assertTrue(prompts.single().secret)
            assertTrue(prompts.single().message.contains("Brave Search API key"))
            assertTrue(vm.searchProviderAuthPrompts("nope").isEmpty())

            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val agent = h.createdAgents.single()
            assertFalse(BraveWebSearchTool.NAME in agent.getActiveToolNames())

            vm.closeForTest()
        }

    @Test
    fun preStoredSearchKey_enablesWebSearch_beforeFirstReadySession() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.credentials.creds[SearchProviderService.BRAVE_CREDENTIAL_ID] =
                ApiKeyCredential(key = "brave-key")
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            // Search credentials never satisfy LLM first-run configuration.
            assertEquals(ChatStatus.NeedsConfiguration, vm.uiState.value.status)
            assertTrue(vm.uiState.value.searchProviderOptions.single().configured)

            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val names = h.createdAgents.single().getActiveToolNames()
            assertEquals(BraveWebSearchTool.NAME, names.last())
            assertEquals(1, names.count { it == BraveWebSearchTool.NAME })

            vm.closeForTest()
        }

    @Test
    fun searchSave_blankAndFailedSaves_neverConfigure() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val agent = h.createdAgents.single()

        vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "   ")
        vm.awaitState { it.error != null }
        assertFalse(vm.uiState.value.searchProviderOptions.single().configured)
        assertNull(h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID))
        vm.dismissError()

        vm.saveSearchProviderCredential("nope", "k")
        vm.awaitState { it.error != null }
        assertFalse(vm.uiState.value.searchProviderOptions.single().configured)
        vm.dismissError()

        h.credentials.failWrites = true
        vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
        vm.awaitState { it.error != null }
        assertFalse(vm.uiState.value.searchProviderOptions.single().configured)
        assertFalse(BraveWebSearchTool.NAME in agent.getActiveToolNames())
        vm.dismissError()
        h.credentials.failWrites = false

        // Confirmed save enables web_search on the SAME session.
        vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
        vm.awaitState { it.searchProviderOptions.single().configured }
        assertEquals("brave-key", h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID))
        assertEquals(1, h.createdAgents.size)
        assertTrue(BraveWebSearchTool.NAME in agent.getActiveToolNames())
        assertFalse(vm.uiState.value.toString().contains("brave-key"))

        vm.saveSearchProviderCredential(
            SearchProviderService.BRAVE_PROVIDER_ID,
            "brave-key-2"
        )
        waitUntil { h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID) == "brave-key-2" }

        vm.closeForTest()
    }

    @Test
    fun searchRemove_deletesKey_andDisablesWebSearch() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val agent = h.createdAgents.single()

        vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
        vm.awaitState { it.searchProviderOptions.single().configured }
        assertTrue(BraveWebSearchTool.NAME in agent.getActiveToolNames())

        vm.removeSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID)
        vm.awaitState { !it.searchProviderOptions.single().configured }
        assertNull(h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID))
        assertFalse(BraveWebSearchTool.NAME in agent.getActiveToolNames())
        assertEquals(1, h.createdAgents.size)

        vm.closeForTest()
    }

    @Test
    fun searchStatusReadFailure_degradesWithoutBreakingReady() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val agent = h.createdAgents.single()
            vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
            vm.awaitState { it.searchProviderOptions.single().configured }
            assertTrue(BraveWebSearchTool.NAME in agent.getActiveToolNames())

            // A credential read failure degrades search with a safe error; chat
            // stays Ready.
            h.credentials.failWrites = true
            vm.refreshSearchProviderStatus()
            vm.awaitState { it.error != null }
            assertFalse(vm.uiState.value.searchProviderOptions.single().configured)
            assertFalse(BraveWebSearchTool.NAME in agent.getActiveToolNames())
            assertEquals(ChatStatus.Ready, vm.uiState.value.status)
            assertEquals("brave-key", h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID))
            assertFalse(vm.uiState.value.toString().contains("brave-key"))
            vm.dismissError()
            h.credentials.failWrites = false

            vm.refreshSearchProviderStatus()
            vm.awaitState { it.searchProviderOptions.single().configured }
            assertTrue(BraveWebSearchTool.NAME in agent.getActiveToolNames())

            vm.closeForTest()
        }

    @Test
    fun searchEnabled_newSession_agentCreatedWithWebSearchActive() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val firstId = vm.uiState.value.activeSessionId!!
            vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
            vm.awaitState { it.searchProviderOptions.single().configured }
            assertTrue(BraveWebSearchTool.NAME in h.createdAgents.single().getActiveToolNames())

            // Every tryCreateAgent path synchronizes web_search.
            vm.newSession()
            vm.awaitState { it.activeSessionId != firstId }
            val newAgent = h.createdAgents.single { it !== h.createdAgents.first() }
            assertEquals(1, newAgent.getActiveToolNames().count { it == BraveWebSearchTool.NAME })
            assertEquals(BraveWebSearchTool.NAME, newAgent.getActiveToolNames().last())

            vm.closeForTest()
        }
}
