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

/** Model and thinking configuration: live switches, startup defaults, model scope, and session-configuration seeding. */
internal class ModelConfigurationViewModelTest : ChatHarnessTest() {

    @Test
    fun invalidModel_andResolverValidation_areRejectedSafely() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            // No bound session: nothing to switch, safe error.
            vm.selectModel("zai", "glm-4.7")
            vm.awaitState { it.error != null }
            assertEquals(ChatStatus.NeedsConfiguration, vm.uiState.value.status)
            assertEquals(0, h.countSessions())
            vm.dismissError()

            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val agentsBefore = h.createdAgents.size

            vm.selectModel("zai", "not-a-model")
            vm.awaitState { it.error != null }
            assertEquals("Unknown model", vm.uiState.value.error)
            assertEquals(agentsBefore, h.createdAgents.size)
            assertEquals("glm-5.3", vm.uiState.value.selectedModel?.modelId)
            vm.dismissError()

            h.rejectedModelIds += "glm-5.3"
            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.error != null }
            assertEquals(agentsBefore, h.createdAgents.size)
            assertTrue(vm.uiState.value.status == ChatStatus.Ready)
            assertEquals("glm-5.3", vm.uiState.value.selectedModel?.modelId)
            assertEquals("", h.settings.currentSettings().modelId)

            vm.closeForTest()
        }

    @Test
    fun settingsWriteFailure_liveSwitchUnaffected_startupDefaultSurfacesError() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            h.settingsStore.failWrites = true

            // A pick is one gesture: the live switch commits, the default
            // persist fails and surfaces its own error (pi: setModel with
            // persist).
            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            vm.awaitState { it.error != null }
            val state = vm.uiState.value
            assertEquals(ChatStatus.Ready, state.status)
            assertEquals("", h.settings.currentSettings().modelId)
            vm.dismissError()

            h.settingsStore.failWrites = false
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
            vm.awaitState { !it.isStreaming && it.messages.size == 2 }
            val sessionId = vm.uiState.value.activeSessionId!!
            vm.awaitState {
                it.sessionSummaries.firstOrNull { s -> s.id == sessionId }?.messageCount ==
                    2
            }

            vm.saveStartupDefault("zai", "glm-5.3")
            vm.awaitState { h.settings.currentSettings().modelId == "glm-5.3" }
            assertNull(vm.uiState.value.error)

            vm.closeForTest()
        }

    @Test
    fun switchingToAFlushedSession_appendsTheMissingThinkingSeed_inPlace() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val firstId = vm.uiState.value.activeSessionId!!

            // A pre-existing session without a thinking entry (as written by
            // an older process, hand-built here): switching to it appends the
            // clamped-default thinking_level_change in place through the
            // manager — no rewrite, no cross-session leakage.
            val other = kotlinx.coroutines.runBlocking { h.sessions.create() }
            kotlinx.coroutines.runBlocking {
                other.appendModelChange("zai", "glm-4.7")
                other.appendMessage(works.resolve.pathfinder.ai.UserMessage.ofText("Old", 1L))
                other.appendMessage(h.assistant("Stock").copy(timestamp = 2L))
            }
            // The switch entry point addresses listed sessions (the drawer
            // renders from the summaries), so the pre-existing file must
            // first appear there — as it would after the committed-message
            // refresh.
            vm.exchange(h, "hi", "ok")
            val firstBefore = h.sessions.stored(firstId)!!.getEntries()

            vm.switchSession(other.getSessionId())
            val state = vm.awaitState { it.activeSessionId == other.getSessionId() }
            assertEquals(2, state.messages.size)
            waitUntil {
                h.sessions.stored(other.getSessionId())!!
                    .getEntries()
                    .filterIsInstance<ThinkingLevelEntry>().isNotEmpty()
            }
            assertEquals(firstBefore, h.sessions.stored(firstId)!!.getEntries())
            val reloaded = h.sessions.stored(other.getSessionId())!!
            assertEquals(2, reloaded.buildSessionContext().messages.size)
            assertEquals(
                listOf("medium"),
                reloaded.getEntries().filterIsInstance<ThinkingLevelEntry>()
                    .map { it.thinkingLevel }
            )

            vm.closeForTest()
        }

    @Test
    fun selectModel_rejectsUnauthenticatedProvider_safely() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val sessionId = vm.uiState.value.activeSessionId!!
            val entriesBefore = h.createdAgents.single().sessionManager.getEntries().size

            // A key-only credential is incomplete for Cloudflare (account/gateway
            // ids required): the provider never counts as configured, and
            // checkAuth rejects the live switch — nothing appended, model
            // unchanged.
            h.credentials.creds["cloudflare-ai-gateway"] = ApiKeyCredential("cf", emptyMap())
            vm.refreshProviderStatus()
            assertFalse(
                vm.uiState.value.providerOptions.first { o ->
                    o.id == "cloudflare-ai-gateway"
                }.configured
            )
            assertTrue(
                vm.uiState.value.modelOptions.none {
                    it.providerId == "cloudflare-ai-gateway"
                }
            )

            vm.selectModel("cloudflare-ai-gateway", "workers-ai/test-model")
            vm.awaitState { it.error != null }
            assertEquals(ChatStatus.Ready, vm.uiState.value.status)
            assertEquals("glm-5.3", vm.uiState.value.selectedModel?.modelId)
            assertEquals(entriesBefore, h.createdAgents.single().sessionManager.getEntries().size)
            assertEquals(0, h.countSessions())

            vm.closeForTest()
        }

    @Test
    fun unknownProviderSettings_deriveAvailableModel_andRejectUnknownPicks() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.settings.setProviderId("not-a-provider")
            h.settings.setModelId("glm-4.7")
            h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

            val vm = h.newViewModel()
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals("glm-5.3", state.selectedModel?.modelId)
            assertTrue(state.modelOptions.all { it.providerId == "zai" })
            assertTrue(state.modelOptions.isNotEmpty())

            vm.selectModel("not-a-provider", "glm-4.7")
            vm.awaitState { it.error != null }
            assertEquals(ChatStatus.Ready, vm.uiState.value.status)
            assertEquals("Unknown model", vm.uiState.value.error)

            vm.closeForTest()
        }

    // ---- thinking level ----

    /**
     * A new session seeds the default thinking level ("medium" default),
     * clamped to the model; the chip surfaces fold onto the live session
     * state.
     */

    @Test
    fun newSession_seedsDefaultThinkingLevel_andProjectsTheChipSurfaces() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            val ready = vm.awaitState { it.status == ChatStatus.Ready }
            val sessionId = ready.activeSessionId!!

            // glm-5.3 has a thinkingLevelMap: low/high/max supported, the
            // "medium" default clamps up to high before seeding.
            assertEquals(ModelThinkingLevel.HIGH, ready.thinkingLevel)
            assertEquals(
                listOf(
                    ModelThinkingLevel.LOW,
                    ModelThinkingLevel.HIGH,
                    ModelThinkingLevel.MAX
                ),
                ready.availableThinkingLevels
            )
            assertNull(ready.defaultThinkingLevel)

            waitUntil { h.sessions.managers[sessionId]!!.getEntries().size == 2 }
            val seeded = h.sessions.managers[sessionId]!!
            assertEquals(
                listOf("high"),
                seeded.getEntries().filterIsInstance<ThinkingLevelEntry>()
                    .map { it.thinkingLevel }
            )

            vm.closeForTest()
        }

    /**
     * One setThinkingLevel call switches the session chip, and a pick never
     * persists the default (pi persists only via a separate action). The
     * append-only-on-change entry behavior is AgentSessionThinkingTest's.
     */

    @Test
    fun selectThinkingLevel_switchesTheChip_neverPersistingTheDefault() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            vm.selectThinkingLevel(ModelThinkingLevel.HIGH)
            val switched = vm.awaitState { it.thinkingLevel == ModelThinkingLevel.HIGH }
            assertNull(
                "no default persisted by a pick (pi persists only via Ctrl+S)",
                switched.defaultThinkingLevel
            )
            assertNull(h.settings.currentSettings().defaultThinkingLevel)

            // Re-picking the current level is a quiet no-op.
            vm.selectThinkingLevel(ModelThinkingLevel.HIGH)
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals(ModelThinkingLevel.HIGH, vm.uiState.value.thinkingLevel)
            assertNull(vm.uiState.value.error)

            vm.closeForTest()
        }

    /**
     * setThinkingLevel clamps to the model's capabilities: glm-5.3's map
     * supports only low/high/max, so a minimal pick rounds up to low.
     */

    @Test
    fun selectThinkingLevel_clampsToTheModelSupportedLevels() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            vm.selectModel("zai", "glm-5.3")
            val switched = vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            // The switch re-applied the session's medium clamped to the new map.
            assertEquals(ModelThinkingLevel.HIGH, switched.thinkingLevel)
            assertEquals(
                listOf(ModelThinkingLevel.LOW, ModelThinkingLevel.HIGH, ModelThinkingLevel.MAX),
                switched.availableThinkingLevels
            )

            vm.selectThinkingLevel(ModelThinkingLevel.MINIMAL)
            vm.awaitState { it.thinkingLevel == ModelThinkingLevel.LOW }

            vm.closeForTest()
        }

    /**
     * Applies to the live session clamped, but persists the requested
     * default thinking level even when the current model clamps it:
     * glm-4.7 supports at most high, so xhigh runs as high while the setting
     * stores xhigh.
     */

    @Test
    fun setThinkingLevelDefault_persistsTheRequestedLevel_andRunsItClamped() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            vm.setThinkingLevelDefault(ModelThinkingLevel.XHIGH)
            val defaulted = vm.awaitState { it.defaultThinkingLevel == ModelThinkingLevel.XHIGH }

            assertEquals(
                ModelThinkingLevel.XHIGH,
                h.settings.currentSettings().defaultThinkingLevel
            )
            assertEquals(
                "the session runs the clamped level",
                ModelThinkingLevel.MAX,
                defaulted.thinkingLevel
            )
            assertEquals(
                "the thinking chip projects the clamped session level",
                ModelThinkingLevel.MAX,
                h.createdAgents.last().thinkingLevel
            )

            vm.closeForTest()
        }

    /**
     * On model switch the stored global default wins over the session's
     * current level, clamped to the new model.
     */

    @Test
    fun selectModel_reappliesTheStoredDefaultThinkingLevel() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            vm.setThinkingLevelDefault(ModelThinkingLevel.LOW)
            vm.awaitState { it.thinkingLevel == ModelThinkingLevel.LOW }
            vm.selectThinkingLevel(ModelThinkingLevel.HIGH)
            vm.awaitState { it.thinkingLevel == ModelThinkingLevel.HIGH }

            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            val reapply = vm.awaitState { it.thinkingLevel == ModelThinkingLevel.LOW }
            assertEquals(ModelThinkingLevel.LOW, reapply.thinkingLevel)

            vm.closeForTest()
        }

    /**
     * On session load the branch's recorded level wins over the global
     * default: a reload keeps it and does not re-seed over it.
     */

    @Test
    fun sessionReload_restoresTheBranchThinkingLevel() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        // Flush the session first: without an assistant message there is no
        // file, and a reload could not restore anything.
        vm.exchange(h, "Hello", "world")
        vm.selectThinkingLevel(ModelThinkingLevel.MAX)
        waitUntil { h.sessions.stored(sessionId)!!.getEntries().size == 5 }
        vm.closeForTest()

        val vm2 = h.newViewModel()
        val restored = vm2.awaitState {
            it.status == ChatStatus.Ready &&
                it.activeSessionId == sessionId
        }
        assertEquals(ModelThinkingLevel.MAX, restored.thinkingLevel)
        assertEquals(
            "the branch entry survives reload; no re-seed over it",
            listOf("high", "max"),
            h.sessions.stored(sessionId)!!.getEntries()
                .filterIsInstance<ThinkingLevelEntry>()
                .map { it.thinkingLevel }
        )

        vm2.closeForTest()
    }

    /**
     * Default persistence is a separate action — never part of a pick — and
     * appends the default to a non-empty scope when missing.
     */

    @Test
    fun saveStartupDefault_separateAction_appendsToNonEmptyScope() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            vm.selectModel("zai", "glm-4.7")
            vm.awaitState { it.selectedModel?.modelId == "glm-4.7" }

            vm.saveStartupDefault("zai", "glm-4.7")
            vm.awaitState { h.settings.currentSettings().modelId == "glm-4.7" }
            assertNull(h.settings.currentSettings().enabledModels)
            assertNull(vm.uiState.value.enabledModels)

            // Unchecking materializes the explicit scope list in display order.
            vm.toggleModelScope("zai", "glm-4.7", false)
            val scoped = vm.awaitState { it.enabledModels != null }
            assertTrue(scoped.enabledModels!!.none { it == "zai/glm-4.7" })
            assertEquals(h.settings.currentSettings().enabledModels, scoped.enabledModels)
            assertTrue(scoped.scopedModelOptions.none { it.modelId == "glm-4.7" })
            assertEquals("glm-4.7", scoped.selectedModel?.modelId)

            // Saving a default missing from the non-empty scope order-preservingly
            // appends it.
            vm.saveStartupDefault("zai", "glm-4.7")
            val grown = vm.awaitState {
                it.enabledModels?.contains("zai/glm-4.7") == true
            }.enabledModels!!
            assertEquals("zai/glm-4.7", grown.last())
            assertEquals(h.settings.currentSettings().enabledModels, grown)
            assertTrue(vm.uiState.value.scopedModelOptions.any { it.modelId == "glm-4.7" })
            assertNull(vm.uiState.value.error)

            vm.closeForTest()
        }

    /**
     * [ChatUiState.defaultModel] mirrors only the stored startup default:
     * null before one is saved, set by the save, never moved by live model
     * switches — unlike [ChatUiState.selectedModel], which follows the
     * running session.
     */

    @Test
    fun defaultModel_mirrorFollowsTheStoredDefault_picksPersistInOneGesture() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            val ready = vm.awaitState { it.status == ChatStatus.Ready }
            assertNull(ready.defaultModel)

            // A pick persists the default in the same gesture (pi's picker),
            // so the mirror follows picks; saveStartupDefault persists
            // without switching.
            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.defaultModel?.modelId == "glm-5.3" }
            assertEquals("glm-5.3", h.settings.currentSettings().modelId)

            vm.saveStartupDefault("zai", "glm-4.7")
            vm.awaitState { it.defaultModel?.modelId == "glm-4.7" }
            // Persist-only never switches the live session.
            assertEquals("glm-5.3", vm.uiState.value.selectedModel?.modelId)

            vm.selectModel("zai", "glm-4.7")
            vm.awaitState { it.selectedModel?.modelId == "glm-4.7" }
            assertEquals("glm-4.7", vm.uiState.value.defaultModel?.modelId)

            vm.closeForTest()
        }

    /** Persists the ordered list; a full or empty selection collapses to the unset scope, as in pi. */

    @Test
    fun toggleModelScope_persistsOrderedList_emptyOrFullSelectionCollapsesToUnset() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val all = vm.uiState.value.modelOptions
            assertTrue(all.size >= 2)

            vm.toggleModelScope(all[0].providerId, all[0].modelId, false)
            val curated = vm.awaitState { it.enabledModels != null }.enabledModels!!
            assertEquals(all.drop(1).map { "${it.providerId}/${it.modelId}" }, curated)

            // Unchecking everything persists the unset scope.
            all.drop(1).forEach { vm.toggleModelScope(it.providerId, it.modelId, false) }
            val emptied = vm.awaitState { it.enabledModels == null }
            assertNull(h.settings.currentSettings().enabledModels)
            assertEquals(emptied.modelOptions, emptied.scopedModelOptions)

            // Re-checking the last missing model collapses back to unset.
            vm.toggleModelScope(all[0].providerId, all[0].modelId, false)
            val rematerialized = all.drop(1).map { "${it.providerId}/${it.modelId}" }
            vm.awaitState { it.enabledModels == rematerialized }
            vm.toggleModelScope(all[0].providerId, all[0].modelId, true)
            vm.awaitState { it.enabledModels == null }
            assertNull(h.settings.currentSettings().enabledModels)

            vm.closeForTest()
        }

    @Test
    fun scopedModelOptions_followCredentialFiltering() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
        h.credentials.creds["zai"] = ApiKeyCredential("z")
        val vm = h.newViewModel()
        val state = vm.awaitState { it.status == ChatStatus.Ready }

        // Display order: GitHub Copilot before Z.AI; name sort puts
        // "glm-5-turbo" before "glm-5.2" ('-' sorts before '.').
        assertEquals(
            listOf("gpt-4.1", "glm-4.7", "glm-5-turbo", "glm-5.2", "glm-5.2-highspeed", "glm-5.3"),
            state.modelOptions.map { it.modelId }
        )
        vm.toggleModelScope("github-copilot", "gpt-4.1", false)
        vm.toggleModelScope("zai", "glm-4.7", false)
        val scoped = vm.awaitState { it.enabledModels != null }
        assertEquals(
            listOf("glm-5-turbo", "glm-5.2", "glm-5.2-highspeed", "glm-5.3"),
            scoped.scopedModelOptions.map { it.modelId }
        )

        vm.closeForTest()
    }

    /**
     * Navigation never changes the running model (pi's navigateTree
     * rebuilds only the transcript), and — because pi's classic format
     * persists no leaf pointer — a reload resumes at the last entry in file
     * order, so the resumed branch fold includes the live-switched model.
     */

    @Test
    fun navigationKeepsTheLiveModel_reloadResumesAtTheFileEndFold() =
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
            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            vm.saveStartupDefault("zai", "glm-5.3")
            vm.awaitState { h.settings.currentSettings().modelId == "glm-5.3" }

            // Navigating back before the model_change truncates the
            // transcript but keeps the live glm-5.3 agent — no rebuild, the
            // chip still shows the running model.
            val agentsBefore = h.createdAgents.size
            val assistantEntryId = vm.uiState.value.treeRows[1].id
            vm.navigateToTreeEntry(assistantEntryId)
            vm.awaitState {
                h.sessions.managers[sessionId]!!.getLeafId() == assistantEntryId
            }
            assertEquals(agentsBefore, h.createdAgents.size)
            assertEquals("glm-5.3", vm.uiState.value.selectedModel?.modelId)

            // Reload: navigation persisted nothing, so the leaf is the last
            // entry in file order — the branch fold (seed glm-4.7, then the
            // glm-5.3 model_change) seeds the running agent on glm-5.3.
            vm.closeForTest()
            val vm2 = h.newViewModel()
            val restored = vm2.awaitState {
                it.status == ChatStatus.Ready && it.activeSessionId == sessionId
            }
            assertEquals("glm-5.3", h.settings.currentSettings().modelId)
            assertEquals("glm-5.3", h.createdSettings.last().modelId)
            assertEquals("glm-5.3", restored.selectedModel?.modelId)

            vm2.closeForTest()
        }

    /**
     * A new chat starts on pi's findInitialModel order — the first scoped
     * model, else the saved default, else the first available model — never
     * on the previously active session's running model, and its seed
     * model_change records that initial selection.
     */

    @Test
    fun newSession_startsOnTheStartupDefault_notTheResumedBranchModel() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.settings.setProviderId("zai")
            h.settings.setModelId("glm-4.7")
            h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.Ready }
            val firstId = vm.uiState.value.activeSessionId!!

            // The branch runs glm-5.3 via a live switch, then a restart
            // resumes it on the branch fold.
            vm.exchange(h, "Hello", "world")
            vm.awaitState {
                it.sessionSummaries.firstOrNull { s -> s.id == firstId }?.messageCount == 2
            }
            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            // Restore the 4.7 default without switching (pi: editing the
            // settings field), so the branch model differs from the default.
            vm.saveStartupDefault("zai", "glm-4.7")
            vm.awaitState { it.defaultModel?.modelId == "glm-4.7" }
            vm.closeForTest()

            val vm2 = h.newViewModel()
            vm2.awaitState { it.status == ChatStatus.Ready }
            assertEquals("glm-5.3", vm2.uiState.value.selectedModel?.modelId)

            // The new chat starts on the saved default, not the resumed
            // branch's glm-5.3, and records it as its seed model_change.
            vm2.newSession()
            val fresh = vm2.awaitState { it.activeSessionId != firstId }
            assertEquals("glm-4.7", fresh.selectedModel?.modelId)
            assertEquals("glm-4.7", h.createdSettings.last().modelId)
            waitUntil {
                h.sessions.managers[fresh.activeSessionId!!]!!.getEntries().isNotEmpty()
            }
            val seed = h.sessions.managers[fresh.activeSessionId!!]!!
                .getEntries().filterIsInstance<ModelChangeEntry>().single()
            assertEquals("zai", seed.provider)
            assertEquals("glm-4.7", seed.modelId)

            vm2.closeForTest()
        }

    /** With a curated scope, a new chat starts on the first scoped model (pi's --models rule), ahead of the default. */

    @Test
    fun newSession_withScope_startsOnTheFirstScopedModel() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val firstId = vm.uiState.value.activeSessionId!!

        vm.saveStartupDefault("zai", "glm-4.7")
        vm.awaitState { it.defaultModel?.modelId == "glm-4.7" }
        // Curate the scope down to glm-5.3 only.
        vm.uiState.value.modelOptions.forEach { option ->
            if (!(option.providerId == "zai" && option.modelId == "glm-5.3")) {
                vm.toggleModelScope(option.providerId, option.modelId, false)
            }
        }
        vm.awaitState {
            it.scopedModelOptions.map { option -> option.modelId } == listOf("glm-5.3")
        }

        vm.newSession()
        val fresh = vm.awaitState { it.activeSessionId != firstId }
        assertEquals("glm-5.3", fresh.selectedModel?.modelId)
        assertEquals("glm-5.3", h.createdSettings.last().modelId)

        vm.closeForTest()
    }

    /** The model chip always mirrors the bound session's running model across new chats and switches. */

    @Test
    fun selectedModel_followsTheBoundSessionAcrossSwitches() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val firstId = vm.uiState.value.activeSessionId!!

            // First session records a transcript, then switches to glm-5.2
            // on-branch; the fold carries the switch across loads.
            vm.exchange(h, "Hello", "world")
            vm.awaitState {
                it.sessionSummaries.firstOrNull { s -> s.id == firstId }?.messageCount == 2
            }
            vm.selectModel("zai", "glm-5.2")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.2" }

            // The pick persisted glm-5.2 as the default, so the fresh
            // session seeds it too (pi: new sessions start on the default).
            vm.newSession()
            vm.awaitState {
                it.activeSessionId != firstId &&
                    it.selectedModel?.modelId == "glm-5.2"
            }
            val secondId = vm.uiState.value.activeSessionId!!

            // Switching back restores the branch fold; switching away again
            // re-runs the fresh session's seed — the chip follows each time.
            // (Only flushed sessions are switchable: no file, no drawer row.)
            vm.exchange(h, "Second", "reply")
            vm.awaitState {
                it.sessionSummaries.firstOrNull { s -> s.id == secondId }?.messageCount == 2
            }
            vm.switchSession(firstId)
            vm.awaitState {
                it.activeSessionId == firstId &&
                    it.selectedModel?.modelId == "glm-5.2"
            }
            vm.switchSession(secondId)
            vm.awaitState {
                it.activeSessionId == secondId &&
                    it.selectedModel?.modelId == "glm-5.2"
            }

            vm.closeForTest()
        }

    @Test
    fun unknownProviderAndStaticUnknownModel_rejectedAsUnknownModel() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            // A configured Copilot credential so a credential read would
            // otherwise succeed — these must still fail statically.
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.Ready }

            vm.selectModel("no-such-provider", "gpt-4.1")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals("Unknown model", vm.uiState.value.error)
            vm.dismissError()

            vm.selectModel("github-copilot", "not-a-catalog-model")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals("Unknown model", vm.uiState.value.error)

            vm.closeForTest()
        }

    @Test
    fun persistedUnknownModelId_isNotMarkedUnavailable() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
        h.settings.setProviderId("github-copilot")
        // A corrupt id the catalog never carried is not "unavailable for this
        // account": no availability error, the derived replacement just runs.
        h.settings.setModelId("corrupt-model-id")
        val vm = h.newViewModel()

        val state = vm.awaitState { it.status == ChatStatus.Ready }
        assertNull(state.error)
        assertEquals("gpt-4.1", state.selectedModel?.modelId)
        vm.closeForTest()
    }
}
