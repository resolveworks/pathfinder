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
import works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.session.SessionError
import works.resolve.pathfinder.codingagent.core.session.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.session.SessionInfo
import works.resolve.pathfinder.codingagent.core.session.SessionManager
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry
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

/** Drawer session search: corpus scan, filtering, and sort. */
internal class SessionSearchViewModelTest : ChatHarnessTest() {

    // ---- session search ----

    @Test
    fun sessionSearch_scansOnce_perQueryStretch_andFiltersResults() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            vm.exchange(h, "Hello", "world")
            val firstId = vm.uiState.value.activeSessionId!!
            vm.newSession()
            val secondId = vm.awaitState { it.activeSessionId != firstId }.activeSessionId!!
            vm.exchange(h, "zebra facts", "reply")
            vm.awaitState {
                it.sessionSummaries.sumOf { s -> s.messageCount } == 4
            }

            vm.onSessionSearchQueryChange("zebra")
            waitUntil {
                !vm.uiState.value.isSessionSearching &&
                    vm.uiState.value.sessionSearchResults.isNotEmpty()
            }
            assertEquals(listOf(secondId), vm.uiState.value.sessionSearchResults.map { it.id })

            // Further keystrokes filter in memory: no rescan, no flicker.
            vm.onSessionSearchQueryChange("zebrax")
            assertEquals(0, vm.uiState.value.sessionSearchResults.size)
            vm.onSessionSearchQueryChange("zebra")
            assertEquals(listOf(secondId), vm.uiState.value.sessionSearchResults.map { it.id })

            // Clearing the query clears results and drops the corpus: a new
            // query rescans.
            vm.onSessionSearchQueryChange("")
            assertEquals(0, vm.uiState.value.sessionSearchResults.size)
            assertFalse(vm.uiState.value.isSessionSearching)
            vm.onSessionSearchQueryChange("Hello")
            waitUntil {
                !vm.uiState.value.isSessionSearching &&
                    vm.uiState.value.sessionSearchResults.isNotEmpty()
            }
            assertEquals(listOf(firstId), vm.uiState.value.sessionSearchResults.map { it.id })

            vm.closeForTest()
        }

    @Test
    fun sessionSearch_sortChangeReorders_recentVsRelevance() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            vm.exchange(h, "zebra", "ok")
            val tightId = vm.uiState.value.activeSessionId!!
            vm.newSession()
            val looseId = vm.awaitState { it.activeSessionId != tightId }.activeSessionId!!
            vm.exchange(h, "a long unrelated preamble before mentioning zebra", "ok")
            vm.awaitState { it.sessionSummaries.sumOf { s -> s.messageCount } == 4 }

            vm.onSessionSearchQueryChange("zebra")
            waitUntil {
                !vm.uiState.value.isSessionSearching &&
                    vm.uiState.value.sessionSearchResults.size == 2
            }
            // Default RELEVANCE: the exact-match session ranks first.
            assertEquals(
                listOf(tightId, looseId),
                vm.uiState.value.sessionSearchResults.map { it.id }
            )

            vm.setSessionSearchSort(SessionSearchSort.RECENT)
            assertEquals(
                listOf(looseId, tightId),
                vm.uiState.value.sessionSearchResults.map { it.id }
            )
            vm.setSessionSearchSort(SessionSearchSort.RELEVANCE)
            assertEquals(
                listOf(tightId, looseId),
                vm.uiState.value.sessionSearchResults.map { it.id }
            )

            vm.closeForTest()
        }

    @Test
    fun sessionSearch_scanFailure_degradesToEmptyResults() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        vm.exchange(h, "Hello", "world")
        vm.awaitState { it.sessionSummaries.firstOrNull()?.messageCount == 2 }

        h.sessions.failList = true
        vm.onSessionSearchQueryChange("Hello")
        waitUntil { !vm.uiState.value.isSessionSearching }
        assertEquals(0, vm.uiState.value.sessionSearchResults.size)
        assertNull(vm.uiState.value.error)

        vm.closeForTest()
    }
}
