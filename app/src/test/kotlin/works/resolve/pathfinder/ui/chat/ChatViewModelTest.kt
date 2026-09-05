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

/** Core chat behavior: initialization, send/stream, session lifecycle, tree navigation, and transcript projection. */
internal class ChatViewModelTest : ChatHarnessTest() {

    @Test
    fun showThinking_persists_andInitProjectsPersistedValue() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertFalse(vm.uiState.value.showThinking)

            vm.setShowThinking(true)
            vm.awaitState { it.showThinking }
            assertTrue(h.settings.currentSettings().showThinking)
            assertNull(vm.uiState.value.error)

            h.settingsStore.failWrites = true
            vm.setShowThinking(false)
            vm.awaitState { it.error != null }
            assertTrue(vm.uiState.value.showThinking)
            assertTrue(h.settings.currentSettings().showThinking)
            vm.dismissError()

            // setShowThinking is display-only: configuration is unaffected.
            h.settingsStore.failWrites = false
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            vm.setShowThinking(false)
            vm.awaitState { !it.showThinking }
            assertFalse(h.settings.currentSettings().showThinking)

            vm.configure(modelId = "glm-5.3")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            assertFalse(vm.uiState.value.showThinking)

            vm.closeForTest()

            h.settings.setShowThinking(true)
            val vm2 = h.newViewModel()
            vm2.awaitState { it.status == ChatStatus.Ready }
            assertTrue(vm2.uiState.value.showThinking)
            vm2.closeForTest()
        }

    @Test
    fun resetSignal_followsSuccessfulIntents_andNeverGetsStale() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            // While unconfigured the reset signal pins the forced first-run root.
            assertEquals(ProvidersNavKey, vm.uiState.value.startKey)

            vm.saveProviderCredential("zai", "k", emptyMap())
            val configured = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(ChatNavKey, configured.startKey)
            assertEquals("glm-4.7", configured.selectedModel?.modelId)
            assertTrue(configured.navigationEpoch >= 1L)
            val firstId = configured.activeSessionId!!

            // A session exists on disk only after its first assistant commit;
            // switching back requires a flushed file.
            vm.exchange(h, "Hello", "world")

            vm.newSession()
            val secondId = vm.awaitState { it.activeSessionId != firstId }.activeSessionId!!
            assertTrue(vm.uiState.value.navigationEpoch >= 2L)

            vm.switchSession(firstId)
            val switched = vm.awaitState { it.activeSessionId == firstId }
            assertEquals(ChatNavKey, switched.startKey)
            assertTrue(switched.navigationEpoch >= 3L)

            vm.newSession()
            val created = vm.awaitState { it.activeSessionId !in setOf(firstId, secondId) }
            assertEquals(ChatNavKey, created.startKey)
            assertTrue(created.navigationEpoch >= 4L)

            // A live model switch is NOT navigation: no epoch bump or stack reset.
            val epochBefore = vm.uiState.value.navigationEpoch
            vm.selectModel("zai", "glm-5.3")
            val switched2 = vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            assertEquals(epochBefore, switched2.navigationEpoch)
            assertEquals(ChatNavKey, switched2.startKey)

            // Status changes stay atomic with the signal.
            vm.uiState.value.let {
                assertTrue(
                    it.status != ChatStatus.NeedsConfiguration ||
                        it.startKey == ProvidersNavKey
                )
            }

            vm.closeForTest()
        }

    @Test
    fun configure_createsSession_andGoesReady() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

        vm.configure(apiKey = "SECRET-KEY-123")

        val state = vm.awaitState { it.status == ChatStatus.Ready }
        assertEquals(ChatNavKey, state.startKey)
        assertTrue(state.navigationEpoch >= 1L)
        assertNotNull(state.activeSessionId)
        assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertEquals("glm-4.7", state.selectedModel?.modelId)
        assertFalse(state.toString().contains("SECRET-KEY-123"))
        // Lazy creation: the fresh session has no file and no drawer row yet.
        assertEquals(0, h.countSessions())

        // The derived initial model is NOT persisted as the startup default —
        // but the active session id is.
        val persisted = h.settings.currentSettings()
        assertEquals("", persisted.providerId)
        assertEquals("", persisted.modelId)
        assertEquals(state.activeSessionId, persisted.activeSessionId)

        vm.closeForTest()
    }

    @Test
    fun send_streamsPersists_andDerivesTitle() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("world", gate))

        vm.onDraftChange("  Hello  ")
        assertTrue(vm.uiState.value.canSend)
        vm.send()

        vm.awaitState { it.isStreaming && it.streamingMessage != null }
        val mid = vm.uiState.value
        assertEquals(1, mid.messages.size)
        assertEquals(ChatRole.User, mid.messages[0].role)
        assertEquals("Hello", mid.messages[0].singleText())
        assertFalse(mid.canSend)
        assertEquals("", mid.draft)

        gate.complete(Unit)

        vm.awaitState { !it.isStreaming && it.messages.size == 2 }
        val done = vm.uiState.value
        assertNull(done.streamingMessage)
        assertEquals(ChatRole.Assistant, done.messages[1].role)
        assertEquals("world", done.messages[1].singleText())
        assertNull(done.error)

        vm.awaitState {
            it.sessionSummaries.firstOrNull()?.firstMessage == "Hello" &&
                it.sessionSummaries.firstOrNull()?.messageCount == 2
        }
        vm.onDraftChange("next")
        assertTrue(vm.uiState.value.canSend)

        vm.closeForTest()
    }

    @Test
    fun streamingUpdates_reuseUnchangedCommittedProjection() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            val releaseSecondChunk = CompletableDeferred<Unit>()
            val releaseDone = CompletableDeferred<Unit>()
            h.scriptedStreams.add(
                flow {
                    val started = h.assistant("")
                    emit(AssistantMessageEvent.Start(started))
                    val first = started.copy(content = listOf(TextContent("first")))
                    emit(AssistantMessageEvent.TextDelta(0, "first", first))
                    releaseSecondChunk.await()
                    val second = started.copy(content = listOf(TextContent("first second")))
                    emit(AssistantMessageEvent.TextDelta(0, " second", second))
                    releaseDone.await()
                    emit(AssistantMessageEvent.Done(StopReason.STOP, second))
                }
            )

            vm.onDraftChange("Hello")
            vm.send()
            val first = vm.awaitState { it.streamingMessage?.singleText() == "first" }
            val committed = first.messages

            releaseSecondChunk.complete(Unit)
            val second = vm.awaitState { it.streamingMessage?.singleText() == "first second" }
            assertSame(committed, second.messages)

            releaseDone.complete(Unit)
            vm.awaitState { !it.isStreaming }
            vm.closeForTest()
        }

    @Test
    fun abort_showsTheAbortedRow_andListsTheSession() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("never", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.awaitState { it.isStreaming }

        vm.stop()

        vm.awaitState { !it.isStreaming && it.messages.size == 2 }
        val state = vm.uiState.value
        assertEquals(ChatRole.Assistant, state.messages[1].role)
        assertNotNull(state.messages[1].error)
        val sessionId = state.activeSessionId!!
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                2
        }

        vm.closeForTest()
    }

    /**
     * VM wiring only — trigger thresholds, summarization, and entry
     * persistence are AgentCompactionTest's: CompactionStart/End drive the
     * transient status, and a compaction entry in the tree projects as a
     * marker row on the next transcript projection.
     */

    @Test
    fun compactionEvents_projectStatus_andMarkerRow() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        vm.exchange(h, "Hello", "world")
        val session = h.createdAgents.single()

        session.agent.processEvent(
            AgentEvent.CompactionStart(AgentEvent.CompactionReason.THRESHOLD)
        )
        vm.awaitState { it.isCompacting }

        session.sessionManager.appendCompaction(
            summary = "SUMMARY",
            firstKeptEntryId = session.sessionManager.conversation.leafId!!,
            tokensBefore = 190_010,
            details = null,
            usage = null
        )
        session.agent.processEvent(
            AgentEvent.CompactionEnd(
                AgentEvent.CompactionReason.THRESHOLD,
                aborted = false,
                willRetry = false
            )
        )
        vm.awaitState { !it.isCompacting }

        // The marker joins the transcript on the next projection (in
        // production the post-compaction transcript rebuild triggers it).
        val followUp = h.assistant("after")
        session.agent.processEvent(AgentEvent.MessageStart(followUp))
        session.agent.processEvent(AgentEvent.MessageEnd(followUp))
        vm.awaitState { it.messages.any { m -> m.isCompactionMarker } }

        vm.closeForTest()
    }

    @Test
    fun autoRetry_removesErrorFromAgentTranscript_butKeepsItInSession() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            h.scriptedStreams.add(h.errorStream(h.assistant("", StopReason.ERROR, "terminated")))
            h.scriptedStreams.add(
                h.gatedStream(
                    "recovered",
                    CompletableDeferred<Unit>().apply {
                        complete(Unit)
                    }
                )
            )
            vm.onDraftChange("Hello")
            vm.send()

            vm.awaitState { !it.isStreaming && it.messages.size == 2 }
            val state = vm.uiState.value
            assertNull(state.retryStatus)
            assertEquals(ChatRole.Assistant, state.messages[1].role)
            assertNull(state.messages[1].error)

            val sessionId = state.activeSessionId!!
            vm.awaitState {
                it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                    3
            }

            vm.closeForTest()
        }

    @Test
    fun streamError_surfacesError_andPersists() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        h.scriptedStreams.add(h.errorStream(h.assistant("", StopReason.ERROR, "boom")))
        vm.onDraftChange("Hello")
        vm.send()

        vm.awaitState { !it.isStreaming && it.messages.size == 2 }
        val state = vm.uiState.value
        // Agent-run errors render as transcript rows only (pi's contract);
        // the snackbar error stays reserved for ViewModel-sourced failures.
        assertNull(state.error)
        assertNotNull(state.messages[1].error)
        val sessionId = state.activeSessionId!!
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                2
        }

        vm.onDraftChange("Again")
        vm.awaitState { it.canSend }
        h.scriptedStreams.add(
            h.gatedStream(
                "fine",
                CompletableDeferred<Unit>().apply {
                    complete(Unit)
                }
            )
        )
        vm.send()
        vm.awaitState { !it.isStreaming && it.messages.size == 4 }
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.messages[3].error)

        vm.closeForTest()
    }

    @Test
    fun restart_restoresActiveSession_andTranscript() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        h.scriptedStreams.add(h.gatedStream("world", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.awaitState { !it.isStreaming && it.messages.size == 2 }
        val originalId = vm.uiState.value.activeSessionId
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == originalId }?.messageCount ==
                2
        }
        vm.closeForTest()

        val vm2 = h.newViewModel()
        val state = vm2.awaitState { it.status == ChatStatus.Ready }
        assertEquals(originalId, state.activeSessionId)
        assertEquals(2, state.messages.size)
        assertEquals("Hello", state.sessionSummaries.first { it.id == originalId!! }.firstMessage)

        vm2.closeForTest()
    }

    @Test
    fun newAndSwitchSession_swapTranscripts() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val firstId = vm.uiState.value.activeSessionId!!

        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        h.scriptedStreams.add(h.gatedStream("world", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.awaitState { !it.isStreaming && it.messages.size == 2 }
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == firstId }?.messageCount == 2
        }

        vm.newSession()
        val fresh = vm.awaitState { it.activeSessionId != firstId }
        assertTrue(fresh.messages.isEmpty())
        assertNull(fresh.streamingMessage)
        // Only the flushed session is listed: the new one is absent until its
        // first assistant message commits.
        assertEquals(1, fresh.sessionSummaries.size)
        assertEquals(firstId, fresh.sessionSummaries.single().id)
        assertTrue(fresh.sessionSummaries.none { it.id == fresh.activeSessionId })

        vm.switchSession(firstId)
        val restored = vm.awaitState { it.activeSessionId == firstId && it.messages.size == 2 }
        assertEquals("Hello", restored.messages[0].singleText())

        vm.closeForTest()
    }

    @Test
    fun draftsArePerSession_andBlankDraftsDoNotLinger() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val firstId = vm.uiState.value.activeSessionId!!

        // Only flushed sessions can be switched to, so both sides of the
        // draft dance get an exchange first.
        vm.exchange(h, "Hello", "world")
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == firstId }?.messageCount == 2
        }

        vm.onDraftChange("typed in first")
        vm.newSession()
        val secondId = vm.awaitState { it.activeSessionId != firstId }.activeSessionId!!
        assertEquals("", vm.uiState.value.draft)

        vm.exchange(h, "Second", "reply")
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == secondId }?.messageCount == 2
        }

        vm.onDraftChange("typed in second")
        vm.switchSession(firstId)
        assertEquals("typed in first", vm.awaitState { it.activeSessionId == firstId }.draft)

        vm.switchSession(secondId)
        val secondAgain = vm.awaitState { it.activeSessionId == secondId }
        assertEquals("typed in second", secondAgain.draft)

        // Clearing the input leaves no draft to restore later.
        vm.onDraftChange("")
        vm.switchSession(firstId)
        vm.awaitState { it.activeSessionId == firstId }
        vm.switchSession(secondId)
        assertEquals("", vm.awaitState { it.activeSessionId == secondId }.draft)

        vm.closeForTest()
    }

    @Test
    fun treeReEditDraft_staysWithItsSession_acrossSwitch() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                2
        }
        val userEntryId = vm.uiState.value.treeRows.first { it.isOnActivePath }.id
        vm.navigateToTreeEntry(userEntryId)
        vm.awaitState { it.draft == "Hello" }

        vm.newSession()
        vm.awaitState { it.activeSessionId != sessionId }
        assertEquals("", vm.uiState.value.draft)

        vm.switchSession(sessionId)
        assertEquals("Hello", vm.awaitState { it.activeSessionId == sessionId }.draft)

        vm.closeForTest()
    }

    @Test
    fun toolResultMessages_renderAsToolRows_withFullOutput() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
            h.scriptedStreams.add(h.gatedStream("world", gate))
            vm.onDraftChange("Hello")
            vm.send()
            vm.awaitState { !it.isStreaming && it.messages.size == 2 }

            val session = h.createdAgents.single()
            val call = AssistantMessage(
                content = listOf(
                    ThinkingContent("Weather is external; use the tool."),
                    TextContent("Checking the weather."),
                    ToolCall(id = "call-1", name = "get_weather", arguments = "{}")
                ),
                api = testModel.api,
                provider = "zai",
                model = "glm-4.7",
                timestamp = System.nanoTime()
            )
            session.agent.processEvent(AgentEvent.MessageStart(call))
            session.agent.processEvent(AgentEvent.MessageEnd(call))
            waitUntil { vm.uiState.value.messages.size == 3 }

            val ok = ToolResultMessage(
                toolCallId = "call-1",
                toolName = "get_weather",
                content = listOf(TextContent("  21°C, sunny\n  wind 3 m/s")),
                timestamp = System.nanoTime()
            )
            session.agent.processEvent(AgentEvent.MessageStart(ok))
            session.agent.processEvent(AgentEvent.MessageEnd(ok))
            waitUntil {
                vm.uiState.value.messages.size == 4 && vm.uiState.value.streamingMessage == null
            }

            val okRow = vm.uiState.value.messages[3]
            assertEquals(ChatRole.Tool, okRow.role)
            assertTrue(okRow.blocks.isEmpty())
            assertEquals(
                ChatToolResult(
                    "call-1",
                    "get_weather",
                    isError = false,
                    output = "  21°C, sunny\n  wind 3 m/s"
                ),
                okRow.toolResult
            )
            assertTrue(vm.uiState.value.pendingTools.isEmpty())

            // Error result: output projected verbatim (line structure kept —
            // renderers, not the projection, bound the preview), error flag
            // projected.
            val failed = ToolResultMessage(
                toolCallId = "call-1",
                toolName = "get_weather",
                content = listOf(TextContent("boom"), TextContent("exit 1")),
                isError = true,
                timestamp = System.nanoTime()
            )
            session.agent.processEvent(AgentEvent.MessageStart(failed))
            session.agent.processEvent(AgentEvent.MessageEnd(failed))
            waitUntil { vm.uiState.value.messages.size == 5 }

            val errorRow = vm.uiState.value.messages[4]
            assertEquals(ChatRole.Tool, errorRow.role)
            val result = errorRow.toolResult!!
            assertTrue(result.isError)
            assertEquals("boom\nexit 1", result.output)

            vm.closeForTest()
        }

    @Test
    fun assistantToolCalls_projectInlineInContentOrder() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        vm.awaitState { !it.isStreaming && it.activeSessionId != null }

        val session = h.createdAgents.single()
        val call = AssistantMessage(
            content = listOf(
                ThinkingContent("reasoning first"),
                TextContent("Before"),
                ToolCall(id = "call-1", name = "get_weather", arguments = "{\"city\":\"secret\"}"),
                TextContent("After")
            ),
            api = testModel.api,
            provider = "zai",
            model = "glm-4.7",
            usage = Usage(reasoning = 412),
            timestamp = System.nanoTime()
        )
        session.agent.processEvent(AgentEvent.MessageStart(call))
        session.agent.processEvent(AgentEvent.MessageEnd(call))
        waitUntil { vm.uiState.value.messages.size == 1 }

        val blocks = vm.uiState.value.messages[0].blocks
        assertEquals(4, blocks.size)
        assertEquals(ChatBlock.Thinking("reasoning first"), blocks[0])
        assertEquals(412, vm.uiState.value.messages[0].reasoningTokens)
        assertEquals(ChatBlock.Text("Before"), blocks[1])
        assertEquals(ChatBlock.ToolCall("call-1", "get_weather"), blocks[2])
        assertEquals(ChatBlock.Text("After"), blocks[3])

        vm.closeForTest()
    }

    @Test
    fun pendingToolExecution_appearsRunning_andResolvesOnEnd() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            vm.awaitState { !it.isStreaming && it.activeSessionId != null }

            val session = h.createdAgents.single()
            val call = AssistantMessage(
                content = listOf(ToolCall(id = "call-1", name = "get_weather", arguments = "{}")),
                api = testModel.api,
                provider = "zai",
                model = "glm-4.7",
                timestamp = System.nanoTime()
            )
            session.agent.processEvent(AgentEvent.MessageStart(call))
            session.agent.processEvent(AgentEvent.MessageEnd(call))
            waitUntil { vm.uiState.value.messages.size == 1 }

            session.agent.processEvent(
                AgentEvent.ToolExecutionStart("call-1", "get_weather", JsonObject(emptyMap()))
            )
            waitUntil {
                vm.uiState.value.pendingTools ==
                    listOf(PendingToolExecution("call-1", "get_weather"))
            }

            // Unknown id (no committed call): generic fallback label, still listed.
            session.agent.processEvent(
                AgentEvent.ToolExecutionStart("call-x", "get_weather", JsonObject(emptyMap()))
            )
            waitUntil { vm.uiState.value.pendingTools.size == 2 }
            assertEquals(PendingToolExecution("call-x", "tool"), vm.uiState.value.pendingTools[1])

            session.agent.processEvent(
                AgentEvent.ToolExecutionEnd(
                    "call-1",
                    "get_weather",
                    AgentToolResult(content = listOf(TextContent("sunny"))),
                    isError = false
                )
            )
            waitUntil { vm.uiState.value.pendingTools.map { it.toolCallId } == listOf("call-x") }
            session.agent.processEvent(
                AgentEvent.ToolExecutionEnd(
                    "call-x",
                    "get_weather",
                    AgentToolResult(content = listOf(TextContent("sunny"))),
                    isError = false
                )
            )
            waitUntil { vm.uiState.value.pendingTools.isEmpty() }

            vm.closeForTest()
        }

    @Test
    fun titledToolRows_carryParsedInput_inPendingAndResultRows() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            vm.awaitState { !it.isStreaming && it.activeSessionId != null }

            val session = h.createdAgents.single()
            val call = AssistantMessage(
                content = listOf(
                    ToolCall(
                        id = "call-1",
                        name = BraveWebSearchTool.NAME,
                        arguments = """{"query":"kotlin flow"}"""
                    ),
                    ToolCall(
                        id = "call-2",
                        name = WebFetchTool.NAME,
                        arguments = """{"url":"https://example.com"}"""
                    ),
                    // Spec'd tool with malformed arguments: title falls back to the bare name.
                    ToolCall(id = "call-3", name = WebFetchTool.NAME, arguments = "not json")
                ),
                api = testModel.api,
                provider = "zai",
                model = "glm-4.7",
                timestamp = System.nanoTime()
            )
            session.agent.processEvent(AgentEvent.MessageStart(call))
            session.agent.processEvent(AgentEvent.MessageEnd(call))
            waitUntil { vm.uiState.value.messages.size == 1 }

            session.agent.processEvent(
                AgentEvent.ToolExecutionStart(
                    "call-1",
                    BraveWebSearchTool.NAME,
                    JsonObject(emptyMap())
                )
            )
            session.agent.processEvent(
                AgentEvent.ToolExecutionStart("call-2", WebFetchTool.NAME, JsonObject(emptyMap()))
            )
            session.agent.processEvent(
                AgentEvent.ToolExecutionStart("call-3", WebFetchTool.NAME, JsonObject(emptyMap()))
            )
            waitUntil { vm.uiState.value.pendingTools.size == 3 }
            assertEquals(
                listOf(
                    PendingToolExecution("call-1", BraveWebSearchTool.NAME, input = "kotlin flow"),
                    PendingToolExecution(
                        "call-2",
                        WebFetchTool.NAME,
                        input = "https://example.com"
                    ),
                    PendingToolExecution("call-3", WebFetchTool.NAME, input = null)
                ),
                vm.uiState.value.pendingTools
            )

            val searchResult = ToolResultMessage(
                toolCallId = "call-1",
                toolName = BraveWebSearchTool.NAME,
                content = listOf(TextContent("1. Kotlin flows")),
                timestamp = System.nanoTime()
            )
            session.agent.processEvent(AgentEvent.MessageStart(searchResult))
            session.agent.processEvent(AgentEvent.MessageEnd(searchResult))
            waitUntil { vm.uiState.value.messages.any { it.role == ChatRole.Tool } }

            val row = vm.uiState.value.messages.last { it.role == ChatRole.Tool }
            assertEquals("kotlin flow", row.toolResult?.input)

            vm.closeForTest()
        }

    @Test
    fun busyIntents_areRejectedWhileStreaming() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("world", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.awaitState { it.isStreaming }
        val sessionId = vm.uiState.value.activeSessionId
        val sessionsBefore = h.countSessions()

        vm.newSession()
        vm.awaitState { it.error != null }
        assertEquals(sessionId, vm.uiState.value.activeSessionId)
        assertEquals(sessionsBefore, h.countSessions())
        vm.dismissError()

        vm.switchSession("other")
        vm.awaitState { it.error != null }
        assertEquals(sessionId, vm.uiState.value.activeSessionId)
        vm.dismissError()

        // A live model switch is NOT busy-rejected: a mid-stream pick applies
        // to the next prompt.
        vm.selectModel("zai", "glm-5.3")
        mainDispatcherRule.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals("glm-5.3", vm.uiState.value.selectedModel?.modelId)
        assertTrue(vm.uiState.value.isStreaming)

        vm.onDraftChange("   ")
        assertFalse(vm.uiState.value.canSend)
        vm.send()
        assertEquals(1, vm.uiState.value.messages.size)

        gate.complete(Unit)
        vm.awaitState { !it.isStreaming && it.messages.size == 2 }
        // Wait for the final persistence before tearing the scope down.
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                2
        }

        vm.closeForTest()
    }

    @Test
    fun switchAfterCompletion_keepsTranscriptsSeparated() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val firstId = vm.uiState.value.activeSessionId!!

        // Stream completes; the persistence job for the final assistant
        // message may still be pending when a new session is requested.
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        h.scriptedStreams.add(h.gatedStream("world", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.awaitState { !it.isStreaming && it.messages.size == 2 }

        vm.newSession()
        val state = vm.awaitState { it.activeSessionId != firstId }
        val secondId = state.activeSessionId!!

        // The finished transcript stays with the old session; the freshly
        // adopted one starts empty and unlisted (never flushed).
        assertTrue(state.messages.isEmpty())
        assertEquals(2, state.sessionSummaries.first { s -> s.id == firstId }.messageCount)

        h.scriptedStreams.add(
            h.gatedStream(
                "second",
                CompletableDeferred<Unit>().apply {
                    complete(Unit)
                }
            )
        )
        vm.onDraftChange("Second")
        vm.send()
        vm.awaitState { !it.isStreaming && it.messages.size == 2 }
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == secondId }?.messageCount == 2
        }

        vm.closeForTest()
    }

    @Test
    fun initFactoryFailure_isFailed_neverReady_andRejectedConfigNotPersisted() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.settings.setProviderId("zai")
            h.settings.setModelId("glm-4.7")
            h.credentials.creds["zai"] = ApiKeyCredential("stored-key")
            h.rejectAll = true

            val vm = h.newViewModel()
            val state = vm.awaitState { it.status != ChatStatus.Loading }
            assertEquals(ChatStatus.Failed, state.status)
            assertNotNull(state.error)
            assertNull(state.activeSessionId)
            assertNull(h.settings.currentSettings().activeSessionId)
            vm.closeForTest()

            // Restart after a factory-rejected model: the invalid selection was
            // never persisted.
            val h2 = harness()
            val vm2 = h2.newViewModel()
            vm2.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm2.configure(apiKey = "k")
            vm2.awaitState { it.status == ChatStatus.Ready }
            h2.rejectedModelIds += "glm-5.3"
            vm2.selectModel("zai", "glm-5.3")
            vm2.awaitState { it.error != null }
            assertEquals("glm-4.7", vm2.uiState.value.selectedModel?.modelId)
            vm2.closeForTest()

            val vm3 = h2.newViewModel()
            val state3 = vm3.awaitState { it.status == ChatStatus.Ready }
            assertNull(state3.error)
            assertEquals("glm-4.7", state3.selectedModel?.modelId)
            vm3.closeForTest()
        }

    @Test
    fun sameTimestampMessages_getDistinctKeys() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val manager = kotlinx.coroutines.runBlocking { h.sessions.create() }
        kotlinx.coroutines.runBlocking {
            manager.appendMessage(works.resolve.pathfinder.ai.UserMessage.ofText("Hello", 123L))
            manager.appendMessage(h.assistant("World").copy(timestamp = 123L))
        }
        h.settings.setProviderId("zai")
        h.settings.setModelId("glm-4.7")
        h.settings.setActiveSessionId(manager.sessionId)
        h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

        val vm = h.newViewModel()
        val state = vm.awaitState { it.status == ChatStatus.Ready }
        assertEquals(2, state.messages.size)
        val keys = state.messages.map { it.id }
        assertEquals(2, keys.toSet().size)

        vm.closeForTest()
    }

    @Test
    fun initActiveSessionWriteFailure_isFailed_neverReady() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.settings.setProviderId("zai")
            h.settings.setModelId("glm-4.7")
            h.credentials.creds["zai"] = ApiKeyCredential("stored-key")
            h.settingsStore.failActiveSessionWrites = true

            val vm = h.newViewModel()
            val state = vm.awaitState { it.status != ChatStatus.Loading }
            assertEquals(ChatStatus.Failed, state.status)
            assertNotNull(state.error)
            assertNull(state.activeSessionId)
            assertNull(h.settings.currentSettings().activeSessionId)
            assertTrue(state.messages.isEmpty())

            vm.closeForTest()
        }

    @Test
    fun newSession_isAbsentFromSummaries_untilTheFirstAssistantCommits() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val firstId = vm.uiState.value.activeSessionId!!
            assertEquals(0, vm.uiState.value.sessionSummaries.size)

            vm.exchange(h, "Hello", "world")
            vm.awaitState { it.sessionSummaries.size == 1 }
            val listed = vm.uiState.value.sessionSummaries.single()
            assertEquals(firstId, listed.id)
            assertEquals(2, listed.messageCount)
            assertEquals("Hello", listed.firstMessage)

            // A new chat has no drawer row until its first assistant commit.
            vm.newSession()
            val fresh = vm.awaitState { it.activeSessionId != firstId }
            assertEquals(1, fresh.sessionSummaries.size)
            assertTrue(fresh.sessionSummaries.none { it.id == fresh.activeSessionId })

            // The first assistant commit (here via abort, which commits an
            // aborted assistant message) creates the file and the row.
            val gate = CompletableDeferred<Unit>()
            h.scriptedStreams.add(h.gatedStream("never", gate))
            vm.onDraftChange("Second")
            vm.send()
            vm.awaitState { it.isStreaming }
            vm.stop()
            vm.awaitState {
                it.sessionSummaries.any { s ->
                    s.id == fresh.activeSessionId && s.messageCount == 2
                }
            }

            vm.closeForTest()
        }

    @Test
    fun storageFailure_fromPrompt_surfacesSaveError_andTheNextPromptStillWorks() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val sessionId = vm.uiState.value.activeSessionId!!

            // The directory becomes read-only: the first assistant commit's
            // file creation fails inside prompt() and the run fails.
            h.sessions.denyWrites = true
            h.scriptedStreams.add(
                h.gatedStream(
                    "world",
                    CompletableDeferred<Unit>().apply {
                        complete(Unit)
                    }
                )
            )
            vm.onDraftChange("Hello")
            vm.send()
            vm.awaitState { it.error != null && !it.isStreaming }
            assertEquals("Could not save the chat", vm.uiState.value.error)
            assertNull(h.sessions.stored(sessionId))

            // The in-memory tree kept the run's entries; the next prompt
            // works and the recovery flush writes everything.
            h.sessions.denyWrites = false
            vm.dismissError()
            h.scriptedStreams.add(
                h.gatedStream(
                    "fine",
                    CompletableDeferred<Unit>().apply {
                        complete(Unit)
                    }
                )
            )
            vm.onDraftChange("Again")
            vm.send()
            vm.awaitState { !it.isStreaming && it.messages.size >= 4 }
            assertNull(vm.uiState.value.error)
        }

    // ---- tree navigation ----

    @Test
    fun navigateToAssistantEntry_truncatesTranscript_andRoundtripPreservesBranches() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val sessionId = vm.uiState.value.activeSessionId!!

            vm.exchange(h, "Hello", "world")
            vm.exchange(h, "Again", "fine")
            vm.awaitState {
                it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                    4
            }
            assertEquals(4, vm.uiState.value.treeRows.size)
            assertTrue(vm.uiState.value.treeRows.last().isCurrentLeaf)

            // Transcript truncates to the root..entry path; tree rows keep every
            // entry.
            val assistantEntryId = vm.uiState.value.treeRows[1].id
            vm.navigateToTreeEntry(assistantEntryId)
            val truncated = vm.awaitState { it.messages.size == 2 }
            assertEquals(4, truncated.treeRows.size)
            assertEquals(assistantEntryId, truncated.treeRows.first { it.isCurrentLeaf }.id)
            assertTrue(truncated.treeRows[0].isOnActivePath)
            assertFalse(truncated.treeRows[3].isOnActivePath)
            assertEquals("world", truncated.messages[1].singleText())

            // A new exchange from here forks: the new user message becomes a
            // sibling of the old one under the same assistant entry.
            vm.exchange(h, "Third", "forked")
            vm.awaitState {
                it.messages.size == 4 &&
                    it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount == 6
            }

            vm.closeForTest()
            val vm2 = h.newViewModel()
            val restored = vm2.awaitState {
                it.status == ChatStatus.Ready &&
                    it.activeSessionId == sessionId
            }
            // The reload resumes at the last entry in file order: the fork's
            // branch, with every entry still in the tree panel.
            assertEquals(4, restored.messages.size)
            assertEquals("Third", restored.messages[2].singleText())
            assertEquals(6, restored.treeRows.size)
            vm2.closeForTest()
        }

    @Test
    fun navigateToUserMessage_restoresDraft_andNextSendForksAsSibling() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val sessionId = vm.uiState.value.activeSessionId!!

            vm.exchange(h, "Hello", "world")
            vm.awaitState {
                it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                    2
            }

            // Re-edit: the leaf resets to the root; the tree keeps both entries.
            val userEntryId = vm.uiState.value.treeRows[0].id
            vm.navigateToTreeEntry(userEntryId)
            val reedit = vm.awaitState { it.draft == "Hello" }
            assertEquals(0, reedit.messages.size)
            assertEquals(2, reedit.treeRows.size)
            assertTrue(reedit.canSend)

            // The next send appends as a sibling (a second root), not a child.
            vm.exchange(h, "Hello edited", "rewritten")
            val resent = vm.awaitState {
                it.messages.size == 2 &&
                    it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount == 4
            }
            assertEquals("Hello edited", resent.messages[0].singleText())

            val rows = vm.uiState.value.treeRows
            assertEquals(4, rows.size)
            assertTrue(rows[0].isCurrentLeaf || rows[1].isCurrentLeaf)
            assertTrue(rows.none { it.connector != TreeConnector.NONE })

            vm.closeForTest()
        }

    @Test
    fun navigateToUserMessage_preservesNonBlankDraft() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        vm.exchange(h, "Hello", "world")
        vm.awaitState { it.treeRows.size == 2 }

        vm.onDraftChange("half-typed draft")
        val userEntryId = vm.uiState.value.treeRows[0].id
        vm.navigateToTreeEntry(userEntryId)

        // Navigation loads the re-edit text only into an empty draft; a typed
        // draft is never clobbered.
        val state = vm.awaitState { it.messages.isEmpty() }
        assertEquals("half-typed draft", state.draft)

        vm.closeForTest()
    }

    @Test
    fun navigateToCurrentLeaf_orUnknownEntry_isRejectedSafely() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            vm.exchange(h, "Hello", "world")
            val leafId = vm.uiState.value.treeRows.last().id

            vm.navigateToTreeEntry(leafId)
            vm.awaitState { it.error == "Already at this point" }
            assertEquals(2, vm.uiState.value.messages.size)
            vm.dismissError()

            vm.navigateToTreeEntry("no-such-entry")
            vm.awaitState { it.error != null }
            assertEquals(2, vm.uiState.value.messages.size)

            vm.closeForTest()
        }

    @Test
    fun navigateWhileStreaming_isBusyRejected() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        vm.exchange(h, "Hello", "world")
        val firstEntry = vm.uiState.value.treeRows[0].id

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("slow", gate))
        vm.onDraftChange("Second")
        vm.send()
        vm.awaitState { it.isStreaming }

        vm.navigateToTreeEntry(firstEntry)
        vm.awaitState { it.error != null }
        assertEquals(3, vm.uiState.value.messages.size) // user message already committed
        vm.dismissError()

        gate.complete(Unit)
        vm.awaitState { !it.isStreaming && it.messages.size == 4 }
        vm.awaitState { it.sessionSummaries.firstOrNull()?.messageCount == 4 }

        vm.closeForTest()
    }

    @Test
    fun setTreeFilter_reprojectsRowsInMemory() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }

        vm.exchange(h, "Hello", "world")
        vm.setTreeFilter(TreeFilter.USER_ONLY)
        val filtered = vm.awaitState { it.treeFilter == TreeFilter.USER_ONLY }.treeRows
        assertEquals(1, filtered.size)
        assertEquals("You: Hello", (filtered[0].body as TreeRowBody.Text).preview)
        vm.setTreeFilter(TreeFilter.DEFAULT)
        assertEquals(2, vm.uiState.value.treeRows.size)

        vm.closeForTest()
    }

    // ---- thinking block projection ----

    private fun ChatMessage.singleText(): String = blocks.single().let { it as ChatBlock.Text }.text

    @Test
    fun projection_mergesThinkingRuns_dropsBlanks_preservesOrder() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            val assistant = h.assistant("").copy(
                content = listOf(
                    ThinkingContent("alpha"),
                    ThinkingContent("beta"),
                    TextContent("first"),
                    ThinkingContent("   "),
                    TextContent("  "),
                    TextContent("second"),
                    ThinkingContent(" lone ")
                )
            )
            val session = h.createdAgents.last()
            val user = works.resolve.pathfinder.ai.UserMessage.ofText("hi")
            // Committed through the agent event sink: AgentSession appends
            // every MessageEnd to the tree in order.
            session.agent.processEvent(AgentEvent.MessageEnd(user))
            session.agent.processEvent(AgentEvent.MessageStart(assistant))
            session.agent.processEvent(AgentEvent.MessageEnd(assistant))

            val state = vm.awaitState { it.messages.size == 2 }
            val blocks = state.messages[1].blocks
            assertEquals(
                listOf(
                    ChatBlock.Thinking("alpha\n\nbeta"),
                    ChatBlock.Text("first"),
                    ChatBlock.Text("second"),
                    ChatBlock.Thinking("lone")
                ),
                blocks
            )
            assertEquals(listOf(ChatBlock.Text("hi")), state.messages[0].blocks)
            // The display preference is untouched by projection.
            assertEquals(state.showThinking, h.settings.currentSettings().showThinking)

            vm.closeForTest()
        }

    @Test
    fun projection_thinkingOnlyStreaming_yieldsThinkingBlock() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            val gate = CompletableDeferred<Unit>()
            h.scriptedStreams.add(
                flow {
                    emit(AssistantMessageEvent.Start(h.assistant("")))
                    val partial = h.assistant(
                        ""
                    ).copy(content = listOf(ThinkingContent("reasoning so far")))
                    emit(AssistantMessageEvent.ThinkingDelta(0, "reasoning", partial))
                    gate.await()
                    emit(AssistantMessageEvent.Done(StopReason.STOP, partial))
                }
            )
            vm.onDraftChange("hi")
            vm.send()

            vm.awaitState { it.streamingMessage?.blocks?.isNotEmpty() == true }
            val streaming = vm.uiState.value.streamingMessage!!
            assertEquals(listOf(ChatBlock.Thinking("reasoning so far")), streaming.blocks)

            // Let the stream finish so teardown never abandons it.
            gate.complete(Unit)
            vm.awaitState { !it.isStreaming }

            vm.closeForTest()
        }

    // ---- navigation-trigger branch summarization ----

    /**
     * VM wiring only — the summarization itself, its failure modes, and the
     * entry shape are AgentNavigationTest/BranchSummarizationTest's: the
     * summarize flag must reach the session from the tree panel intent.
     */

    @Test
    fun navigateWithSummarize_reachesTheSummarizer() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        // The summarization stack must exist before the agent is created;
        // auto-compaction is disabled so the queued response belongs to the
        // navigation summarization alone.
        h.disableCompaction = true
        h.installCompactionModels()
        h.summaryResponses.add(h.assistant("## Goal\nexplored the branch"))
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.exchange(h, "Again", "fine")
        vm.awaitState {
            it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                4
        }

        val assistantEntryId = vm.uiState.value.treeRows[1].id
        vm.navigateToTreeEntry(assistantEntryId, summarize = true)

        waitUntil {
            h.sessions.stored(sessionId)!!.entries.any { it is BranchSummaryEntry }
        }
        assertNull(vm.uiState.value.error)

        vm.closeForTest()
    }
}
