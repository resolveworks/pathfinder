package works.resolve.aletheia.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import works.resolve.aletheia.agent.Agent
import works.resolve.aletheia.agent.AgentFactory
import works.resolve.aletheia.agent.StreamFn
import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.data.credentials.ApiKeyCredential
import works.resolve.aletheia.data.credentials.ApiKeyStore
import works.resolve.aletheia.data.settings.SettingsRepository
import works.resolve.aletheia.data.settings.SettingsStore
import works.resolve.aletheia.data.sessions.SessionRepository
import works.resolve.aletheia.data.sessions.SessionStore
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestDispatcher
import org.junit.runner.Description
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Replaces Dispatchers.Main so viewModelScope runs on a shared test scheduler. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainDispatcherRule : org.junit.rules.TestWatcher() {
    val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(scheduler)
    override fun starting(description: Description?) = Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description?) = Dispatchers.resetMain()
}

class ChatViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testModel = Model(
        id = "glm-4.7",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid",
    )

    private class FakeApiKeyStore : ApiKeyStore {
        val creds = mutableMapOf<String, ApiKeyCredential>()
        var failWrites = false
        override suspend fun getCredential(providerId: String): ApiKeyCredential? {
            if (failWrites) throw java.io.IOException("credential store failed")
            return creds[providerId]
        }
        override suspend fun setCredential(providerId: String, credential: ApiKeyCredential) {
            if (failWrites) throw java.io.IOException("credential store failed")
            creds[providerId] = credential
        }
        override suspend fun deleteCredential(providerId: String) {
            if (failWrites) throw java.io.IOException("credential store failed")
            creds.remove(providerId)
        }
    }

    /** SettingsStore wrapper whose writes can be made to fail deterministically. */
    class FailingSettingsStore(
        private val delegate: SettingsStore,
    ) : SettingsStore by delegate {
        var failWrites = false
        var failActiveSessionWrites = false
        override suspend fun setProviderId(providerId: String) {
            if (failWrites) throw java.io.IOException("settings write failed")
            delegate.setProviderId(providerId)
        }
        override suspend fun setModelId(modelId: String) {
            if (failWrites) throw java.io.IOException("settings write failed")
            delegate.setModelId(modelId)
        }
        override suspend fun setBaseUrl(baseUrl: String?) {
            if (failWrites) throw java.io.IOException("settings write failed")
            delegate.setBaseUrl(baseUrl)
        }
        override suspend fun setActiveSessionId(sessionId: String?) {
            if (failActiveSessionWrites) throw java.io.IOException("active session write failed")
            delegate.setActiveSessionId(sessionId)
        }
        override suspend fun setShowThinking(showThinking: Boolean) {
            if (failWrites) throw java.io.IOException("settings write failed")
            delegate.setShowThinking(showThinking)
        }
    }

    /** SessionRepository wrapper whose saves can be made to fail deterministically. */
    class FailingSessionRepository(
        private val delegate: SessionRepository,
    ) : SessionRepository by delegate {
        var failSave = false
        var failedSaves = 0
            private set
        var totalSaves = 0
            private set
        /** When set, completed as each save is entered (before the gate). */
        var saveEntered: CompletableDeferred<Unit>? = null
        /** When set, every save suspends on it before delegating. */
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun save(session: works.resolve.aletheia.data.sessions.Session): works.resolve.aletheia.data.sessions.Session {
            totalSaves += 1
            if (failSave) {
                failedSaves += 1
                throw works.resolve.aletheia.data.sessions.SessionDataException("save failed")
            }
            saveEntered?.complete(Unit)
            saveGate?.await()
            return delegate.save(session)
        }
    }

    /** Test harness wiring real repositories/stores and scripted real Agents. */
    private inner class Harness {
        val credentials = FakeApiKeyStore()
        val dataStoreScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher)
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tmpFolder.root, "settings_${System.nanoTime()}.preferences_pb") },
            ),
        )
        val settingsStore = FailingSettingsStore(settings)
        var nextSessionId = 0
        val sessionStore = SessionStore(
            root = File(tmpFolder.root, "sessions_${System.nanoTime()}"),
            idFactory = { "sess-${nextSessionId++}" },
        )
        val sessions = FailingSessionRepository(sessionStore)

        /** Scripted stream flows per prompt; queued before send(). */
        val scriptedStreams = ConcurrentLinkedQueue<Flow<AssistantMessageEvent>>()

        /** Base URLs rejected by the fake factory (validation-error path). */
        val rejectedBaseUrls = mutableSetOf<String>()

        /** When set, the factory rejects every configuration. */
        var rejectAll = false
        val createdAgents = mutableListOf<Agent>()

        val factory = AgentFactory { settings, _, transcript ->
            check(!rejectAll) { "factory unavailable" }
            settings.baseUrl?.let { require(it !in rejectedBaseUrls) { "baseUrl rejected" } }
            Agent(
                model = testModel,
                streamFn = StreamFn { _, _, _ ->
                    scriptedStreams.poll() ?: flow { kotlinx.coroutines.awaitCancellation() }
                },
            ).also { agent ->
                agent.replaceTranscript(transcript)
                createdAgents += agent
            }
        }

        fun newViewModel(): ChatViewModel = ChatViewModel(
            settingsRepository = settingsStore,
            credentials = credentials,
            catalog = works.resolve.aletheia.ai.testing.TestCatalogs.CATALOG,
            sessionStore = sessions,
            agentFactory = factory,
        )

        fun assistant(text: String, stopReason: StopReason = StopReason.STOP, error: String? = null) =
            AssistantMessage(
                content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
                api = testModel.api,
                provider = testModel.provider,
                model = testModel.id,
                stopReason = stopReason,
                errorMessage = error,
                timestamp = System.nanoTime(),
            )

        /** Stream that emits a partial, then waits for [gate] before finishing. */
        fun gatedStream(text: String, gate: CompletableDeferred<Unit>): Flow<AssistantMessageEvent> = flow {
            emit(AssistantMessageEvent.Start(assistant("")))
            gate.await()
            val full = assistant(text)
            emit(AssistantMessageEvent.TextDelta(0, text, full))
            emit(AssistantMessageEvent.Done(StopReason.STOP, full))
        }

        /** Stream that fails before any content. */
        fun errorStream(message: AssistantMessage) =
            flowOf(AssistantMessageEvent.Error(StopReason.ERROR, message))

        suspend fun countSessions(): Int = sessionStore.summaries().size
    }

    /** Cancels the ViewModel scope and waits until all in-flight work has settled. */
    private suspend fun ChatViewModel.closeForTest() {
        val job = viewModelScope.coroutineContext[Job]!!
        job.cancel()
        job.join()
    }

    /** Configures zai in two intents: optional credential save, then model selection. */
    private fun ChatViewModel.configure(
        modelId: String = "glm-4.7",
        baseUrl: String? = null,
        apiKey: String = "",
    ) {
        if (apiKey.isNotEmpty()) saveProviderCredential("zai", apiKey, emptyMap())
        saveModelSelection("zai", modelId, baseUrl)
    }

    // ---- tests ----

    @Test
    fun unconfiguredInit_showsNeedsConfiguration_andKeepsKeyPrivate() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()

        val state = vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        assertEquals(SettingsNavKey, state.startKey)
        assertFalse(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertNull(state.activeSessionId)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.modelOptions.isEmpty())

        // Configure a stored key but no model settings: still unconfigured,
        // and the key never appears anywhere in the UI state.
        h.credentials.creds["zai"] = ApiKeyCredential("SECRET-KEY-123")
        val vm2 = h.newViewModel()
        val state2 = vm2.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        assertTrue(state2.providerOptions.first { o -> o.id == "zai" }.configured)
        assertFalse(state2.toString().contains("SECRET-KEY-123"))

        vm.closeForTest()
        vm2.closeForTest()
    }

    @Test
    fun showThinking_persists_andInitProjectsPersistedValue() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        assertFalse(vm.uiState.value.showThinking)

        vm.setShowThinking(true)
        vm.uiState.first { it.showThinking }
        assertTrue(h.settings.currentSettings().showThinking)
        assertNull(vm.uiState.value.error)

        // A failing store surfaces the error and leaves the state unchanged.
        h.settingsStore.failWrites = true
        vm.setShowThinking(false)
        vm.uiState.first { it.error != null }
        assertTrue(vm.uiState.value.showThinking)
        assertTrue(h.settings.currentSettings().showThinking)
        vm.dismissError()

        // setShowThinking is display-only: the configuration flow is unaffected.
        h.settingsStore.failWrites = false
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        vm.setShowThinking(false)
        vm.uiState.first { !it.showThinking }
        assertFalse(h.settings.currentSettings().showThinking)

        // Reconfiguration preserves the user's preference in the candidate.
        vm.configure(modelId = "glm-5.3")
        vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        assertFalse(vm.uiState.value.showThinking)

        vm.closeForTest()

        // A fresh init projects the persisted value into the Ready state.
        h.settings.setShowThinking(true)
        val vm2 = h.newViewModel()
        vm2.uiState.first { it.status == ChatStatus.Ready }
        assertTrue(vm2.uiState.value.showThinking)
        vm2.closeForTest()
    }

    @Test
    fun resetSignal_followsSuccessfulIntents_andNeverGetsStale() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // While unconfigured the reset signal pins the settings root: the UI
        // back stack is rebuilt to exactly [SettingsNavKey] and back is a no-op.
        assertEquals(SettingsNavKey, vm.uiState.value.startKey)

        // Completing configuration bumps the epoch and returns to the chat root.
        vm.configure(apiKey = "k")
        val configured = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(ChatNavKey, configured.startKey)
        assertTrue(configured.navigationEpoch >= 1L)
        val firstId = configured.activeSessionId!!

        vm.newSession()
        val secondId = vm.uiState.first { it.activeSessionId != firstId }.activeSessionId!!
        // Each successful session adoption bumps the epoch again (reset to chat).
        assertTrue(vm.uiState.value.navigationEpoch >= 2L)

        vm.switchSession(firstId)
        val switched = vm.uiState.first { it.activeSessionId == firstId }
        assertEquals(ChatNavKey, switched.startKey)
        assertTrue(switched.navigationEpoch >= 3L)

        vm.newSession()
        val created = vm.uiState.first { it.activeSessionId !in setOf(firstId, secondId) }
        assertEquals(ChatNavKey, created.startKey)
        assertTrue(created.navigationEpoch >= 4L)

        // Reconfiguration also bumps the epoch (returns the user to the chat).
        vm.configure(modelId = "glm-5.3")
        val reconfigured = vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        assertEquals(ChatStatus.Ready, reconfigured.status)
        assertEquals(ChatNavKey, reconfigured.startKey)
        assertTrue(reconfigured.navigationEpoch >= 5L)

        // Status changes stay atomic with the signal: every Ready observation
        // pairs with the chat root, every NeedsConfiguration one with settings.
        vm.uiState.value.let {
            assertTrue(it.status != ChatStatus.NeedsConfiguration || it.startKey == SettingsNavKey)
        }

        vm.closeForTest()
    }

    @Test
    fun configure_createsSession_andGoesReady() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        vm.configure(apiKey = "SECRET-KEY-123")

        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(ChatNavKey, state.startKey)
        assertTrue(state.navigationEpoch >= 1L)
        assertNotNull(state.activeSessionId)
        assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertEquals("glm-4.7", state.selectedModel?.modelId)
        assertFalse(state.toString().contains("SECRET-KEY-123"))
        assertEquals(1, h.countSessions())

        val persisted = h.settings.currentSettings()
        assertEquals("zai", persisted.providerId)
        assertEquals("glm-4.7", persisted.modelId)
        assertEquals(state.activeSessionId, persisted.activeSessionId)

        vm.closeForTest()
    }

    @Test
    fun send_streamsPersists_andDerivesTitle() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("world", gate))

        vm.onDraftChange("  Hello  ")
        assertTrue(vm.uiState.value.canSend)
        vm.send()

        // Mid-stream: partial visible, committed transcript has only the user message.
        vm.uiState.first { it.isStreaming && it.streamingMessage != null }
        val mid = vm.uiState.value
        assertEquals(1, mid.messages.size)
        assertEquals(ChatRole.User, mid.messages[0].role)
        assertEquals("Hello", mid.messages[0].text)
        assertFalse(mid.canSend)
        assertEquals("", mid.draft)

        gate.complete(Unit)

        // Done: user + assistant committed, no duplicates, not streaming.
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        val done = vm.uiState.value
        assertNull(done.streamingMessage)
        assertEquals(ChatRole.Assistant, done.messages[1].role)
        assertEquals("world", done.messages[1].text)
        assertNull(done.error)

        // Session persisted with both messages and a derived title.
        vm.uiState.first {
            it.sessionSummaries.firstOrNull()?.title == "Hello" &&
                it.sessionSummaries.firstOrNull()?.messageCount == 2
        }
        val sessionId = done.activeSessionId!!
        val session = h.sessionStore.load(sessionId)!!
        assertEquals(2, session.messages.size)
        assertEquals("Hello", session.title)
        vm.onDraftChange("next")
        assertTrue(vm.uiState.value.canSend)

        vm.closeForTest()
    }

    @Test
    fun abort_persistsUserAndAbortedAssistant() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("never", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { it.isStreaming }

        vm.stop()

        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        val state = vm.uiState.value
        assertEquals(ChatRole.Assistant, state.messages[1].role)
        assertNotNull(state.messages[1].error)
        val sessionId = state.activeSessionId!!
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }

        val session = h.sessionStore.load(sessionId)!!
        assertEquals(2, session.messages.size)
        val assistant = session.messages[1] as works.resolve.aletheia.ai.core.AssistantMessage
        assertEquals(StopReason.ABORTED, assistant.stopReason)

        vm.closeForTest()
    }

    @Test
    fun streamError_surfacesError_andPersists() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        h.scriptedStreams.add(h.errorStream(h.assistant("", StopReason.ERROR, "boom")))
        vm.onDraftChange("Hello")
        vm.send()

        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        val state = vm.uiState.value
        assertNotNull(state.error)
        assertNotNull(state.messages[1].error)
        val sessionId = state.activeSessionId!!
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        assertEquals(2, h.sessionStore.load(sessionId)!!.messages.size)

        vm.dismissError()
        assertNull(vm.uiState.value.error)

        // A new successful run clears the (re-surfaced) agent error for good.
        vm.onDraftChange("Again")
        vm.uiState.first { it.canSend }
        h.scriptedStreams.add(h.gatedStream("fine", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 4 }
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.messages[3].error)

        vm.closeForTest()
    }

    @Test
    fun restart_restoresActiveSession_andTranscript() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        h.scriptedStreams.add(h.gatedStream("world", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        val originalId = vm.uiState.value.activeSessionId
        // Wait until persistence of both messages is observable.
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == originalId }.messageCount == 2 }
        vm.closeForTest()

        val vm2 = h.newViewModel()
        val state = vm2.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(originalId, state.activeSessionId)
        assertEquals(2, state.messages.size)
        assertEquals("Hello", state.sessionSummaries.first { it.id == originalId!! }.title)
        // No save loop: restart alone does not bump the file beyond content.
        assertEquals(2, h.sessionStore.load(originalId!!)!!.messages.size)

        vm2.closeForTest()
    }

    @Test
    fun newAndSwitchSession_swapTranscripts() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val firstId = vm.uiState.value.activeSessionId!!

        // Populate the first session.
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        h.scriptedStreams.add(h.gatedStream("world", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == firstId }.messageCount == 2 }

        vm.newSession()
        val fresh = vm.uiState.first { it.activeSessionId != firstId }
        assertTrue(fresh.messages.isEmpty())
        assertNull(fresh.streamingMessage)
        assertEquals(2, fresh.sessionSummaries.size)
        assertTrue(fresh.sessionSummaries.none { it.id == fresh.activeSessionId && it.messageCount > 0 })

        vm.switchSession(firstId)
        val restored = vm.uiState.first { it.activeSessionId == firstId && it.messages.size == 2 }
        assertEquals("Hello", restored.messages[0].text)

        vm.closeForTest()
    }

    @Test
    fun invalidModel_andFactoryValidation_areRejectedSafely() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // Missing key on initial configuration (before any credential is saved).
        vm.saveModelSelection("zai", "glm-4.7", null)
        vm.uiState.first { it.error != null }
        assertEquals(ChatStatus.NeedsConfiguration, vm.uiState.value.status)
        assertEquals(0, h.countSessions())
        vm.dismissError()

        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.saveModelSelection("zai", "not-a-model", null)
        vm.uiState.first { it.error != null }
        assertEquals(ChatStatus.NeedsConfiguration, vm.uiState.value.status)
        vm.dismissError()

        // Complete configuration, then a factory-invalid base URL keeps the old agent.
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val agentsBefore = h.createdAgents.size

        h.rejectedBaseUrls += "https://bad.example"
        vm.configure(baseUrl = "https://bad.example")
        vm.uiState.first { it.error != null }
        assertEquals(agentsBefore, h.createdAgents.size)
        // Same agent still bound: sending still works through the old agent.
        assertTrue(vm.uiState.value.status == ChatStatus.Ready)
        // The invalid base URL was never persisted.
        assertNull(h.settings.currentSettings().baseUrl)
        assertEquals("glm-4.7", h.settings.currentSettings().modelId)

        vm.closeForTest()
    }

    @Test
    fun blankKeyRetention_keepsStoredKey() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(baseUrl = "https://api.example.com/v1/", apiKey = "first-key")
        vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals("first-key", h.credentials.creds["zai"]!!.key)
        assertEquals(1, h.createdAgents.size)

        // Blank key input retains the stored key; blank base URL clears it.
        vm.configure(modelId = "glm-5.3", baseUrl = "   ")
        vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }

        val state = vm.uiState.value
        assertEquals(ChatStatus.Ready, state.status)
        assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertEquals("first-key", h.credentials.creds["zai"]!!.key)
        assertNull(state.selectedModel?.baseUrlOverride)
        assertNull(h.settings.currentSettings().baseUrl)
        // Agent rebuilt for the same session/transcript with new settings.
        assertEquals(2, h.createdAgents.size)

        vm.closeForTest()
    }

    @Test
    fun busyIntents_areRejectedWhileStreaming() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("world", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { it.isStreaming }
        val sessionId = vm.uiState.value.activeSessionId
        val sessionsBefore = h.countSessions()

        vm.newSession()
        vm.uiState.first { it.error != null }
        assertEquals(sessionId, vm.uiState.value.activeSessionId)
        assertEquals(sessionsBefore, h.countSessions())
        vm.dismissError()

        vm.switchSession("other")
        vm.uiState.first { it.error != null }
        assertEquals(sessionId, vm.uiState.value.activeSessionId)
        vm.dismissError()

        vm.saveModelSelection("zai", "glm-5.3", null)
        vm.uiState.first { it.error != null }
        assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
        vm.dismissError()

        // Blank send is a no-op even when idle; blank draft cannot send.
        vm.onDraftChange("   ")
        assertFalse(vm.uiState.value.canSend)
        vm.send()
        assertEquals(1, vm.uiState.value.messages.size)

        gate.complete(Unit)
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        // Wait for the final persistence before tearing the scope down.
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }

        vm.closeForTest()
    }

    @Test
    fun switchAfterCompletion_awaitsPersistence_andCannotCrossSessions() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val firstId = vm.uiState.value.activeSessionId!!

        // Stream completes; the persistence job for the final assistant
        // message may still be pending when a new session is requested.
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        h.scriptedStreams.add(h.gatedStream("world", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }

        vm.newSession()
        val state = vm.uiState.first { it.activeSessionId != firstId }
        val secondId = state.activeSessionId!!

        // The finished transcript stayed with the old session...
        val oldSession = h.sessionStore.load(firstId)!!
        assertEquals(2, oldSession.messages.size)
        assertEquals("Hello", oldSession.title)
        // ...and cannot overwrite the freshly adopted one.
        assertTrue(state.messages.isEmpty())
        assertEquals(0, h.sessionStore.load(secondId)!!.messages.size)
        assertEquals(2, state.sessionSummaries.first { s -> s.id == firstId }.messageCount)

        // A later exchange in the new session only touches the new session file.
        h.scriptedStreams.add(h.gatedStream("second", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Second")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == secondId }.messageCount == 2 }
        assertEquals(2, h.sessionStore.load(firstId)!!.messages.size)
        assertEquals(2, h.sessionStore.load(secondId)!!.messages.size)

        vm.closeForTest()
    }

    @Test
    fun initFactoryFailure_isFailed_neverReady_andInvalidBaseUrlNotPersisted() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Persist a fully valid-looking configuration, but the factory is down.
        h.settings.setProviderId("zai")
        h.settings.setModelId("glm-4.7")
        h.credentials.creds["zai"] = ApiKeyCredential("stored-key")
        h.rejectAll = true

        val vm = h.newViewModel()
        val state = vm.uiState.first { it.status != ChatStatus.Loading }
        assertEquals(ChatStatus.Failed, state.status)
        assertNotNull(state.error)
        assertNull(state.activeSessionId)
        assertNull(h.settings.currentSettings().activeSessionId)
        vm.closeForTest()

        // Restart after a rejected base URL: the invalid value was never
        // persisted, so the previous valid configuration still works.
        val h2 = Harness()
        val vm2 = h2.newViewModel()
        vm2.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm2.configure(apiKey = "k")
        vm2.uiState.first { it.status == ChatStatus.Ready }
        h2.rejectedBaseUrls += "https://bad.example"
        vm2.configure(baseUrl = "https://bad.example")
        vm2.uiState.first { it.error != null }
        assertEquals("glm-4.7", vm2.uiState.value.selectedModel?.modelId)
        vm2.closeForTest()

        val vm3 = h2.newViewModel()
        val state3 = vm3.uiState.first { it.status == ChatStatus.Ready }
        assertNull(state3.error)
        assertEquals("glm-4.7", state3.selectedModel?.modelId)
        assertNull(h2.settings.currentSettings().baseUrl)
        vm3.closeForTest()
    }

    @Test
    fun keyStoredBeforeLaterFailure_isRetainedOnBlankKeyRetry() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // The key is stored, but the factory rejects the base URL afterwards.
        h.rejectedBaseUrls += "https://bad.example"
        vm.saveProviderCredential("zai", "first-key", emptyMap())
        vm.configure(baseUrl = "https://bad.example")
        vm.uiState.first { it.error != null }
        val state = vm.uiState.value
        assertEquals(ChatStatus.NeedsConfiguration, state.status)
        assertEquals("first-key", h.credentials.creds["zai"]!!.key)
        assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertFalse(state.toString().contains("first-key"))

        // Blank-key retry retains the stored key and completes.
        vm.configure()
        vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals("first-key", h.credentials.creds["zai"]!!.key)

        vm.closeForTest()
    }

    @Test
    fun sameTimestampMessages_getDistinctKeys() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // A persisted session whose user and assistant messages share a timestamp.
        val session = h.sessionStore.create("Collide")
        val saved = h.sessionStore.save(
            session.copy(
                messages = listOf(
                    works.resolve.aletheia.ai.core.UserMessage.ofText("Hello", 123L),
                    h.assistant("World").copy(timestamp = 123L),
                ),
            ),
        )
        h.settings.setProviderId("zai")
        h.settings.setModelId("glm-4.7")
        h.settings.setActiveSessionId(saved.id)
        h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

        val vm = h.newViewModel()
        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(2, state.messages.size)
        assertEquals(123L, h.sessionStore.load(saved.id)!!.messages[0].timestamp)
        val keys = state.messages.map { it.id }
        assertEquals(2, keys.toSet().size)

        vm.closeForTest()
    }

    @Test
    fun settingsWriteFailure_initialConfig_staysUnconfigured_andKeepsKeyPrivate() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        h.settingsStore.failWrites = true
        vm.configure(apiKey = "SECRET-KEY-9")

        vm.uiState.first { it.error != null }
        val state = vm.uiState.value
        // No false Ready: still unconfigured, no session adopted, nothing persisted.
        assertEquals(ChatStatus.NeedsConfiguration, state.status)
        assertNull(state.activeSessionId)
        assertTrue(state.messages.isEmpty())
        assertEquals("", h.settings.currentSettings().modelId)
        assertNull(h.settings.currentSettings().activeSessionId)
        // The key was stored before the failure; the safe boolean reflects it.
        assertEquals("SECRET-KEY-9", h.credentials.creds["zai"]!!.key)
        assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertFalse(state.toString().contains("SECRET-KEY-9"))

        // Recovery: a retry with a working store succeeds.
        h.settingsStore.failWrites = false
        vm.configure()
        vm.uiState.first { it.status == ChatStatus.Ready }

        vm.closeForTest()
    }

    @Test
    fun settingsWriteFailure_reconfigure_retainsPreviousAgentAndSettings() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        h.settingsStore.failWrites = true
        vm.configure(modelId = "glm-5.3", baseUrl = "https://new.example")
        vm.uiState.first { it.error != null }

        val state = vm.uiState.value
        assertEquals(ChatStatus.Ready, state.status)
        assertEquals("glm-4.7", state.selectedModel?.modelId)
        assertNull(state.selectedModel?.baseUrlOverride)
        // Persisted settings unchanged.
        assertEquals("glm-4.7", h.settings.currentSettings().modelId)
        assertNull(h.settings.currentSettings().baseUrl)

        // The previous agent is still bound: chatting still works and persists.
        h.settingsStore.failWrites = false
        h.scriptedStreams.add(h.gatedStream("world", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        val sessionId = vm.uiState.value.activeSessionId!!
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        assertEquals(2, h.sessionStore.load(sessionId)!!.messages.size)

        vm.closeForTest()
    }

    @Test
    fun initActiveSessionWriteFailure_isFailed_neverReady() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Fully valid persisted configuration, but the active-id write fails.
        h.settings.setProviderId("zai")
        h.settings.setModelId("glm-4.7")
        h.credentials.creds["zai"] = ApiKeyCredential("stored-key")
        h.settingsStore.failActiveSessionWrites = true

        val vm = h.newViewModel()
        val state = vm.uiState.first { it.status != ChatStatus.Loading }
        assertEquals(ChatStatus.Failed, state.status)
        assertNotNull(state.error)
        assertNull(state.activeSessionId)
        assertNull(h.settings.currentSettings().activeSessionId)
        assertTrue(state.messages.isEmpty())

        vm.closeForTest()
    }

    @Test
    fun switchBinding_alone_doesNotEnqueueOrCrossWriteSaves() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val firstId = vm.uiState.value.activeSessionId!!
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == firstId }.messageCount == 0 }

        // Seed a second session with a longer transcript in the store.
        val other = h.sessionStore.create("Other")
        h.sessionStore.save(
            other.copy(
                messages = listOf(
                    works.resolve.aletheia.ai.core.UserMessage.ofText("Old", 1L),
                    h.assistant("Stock").copy(timestamp = 2L),
                ),
            ),
        )
        val savesBefore = h.sessions.totalSaves

        // Switching binds an agent over the longer transcript; binding alone
        // must not observe it against the previous (empty) session.
        vm.switchSession(other.id)
        val state = vm.uiState.first { it.activeSessionId == other.id }
        assertEquals(2, state.messages.size)
        assertEquals(savesBefore, h.sessions.totalSaves)
        // No cross-write: the empty session is untouched on disk.
        assertEquals(0, h.sessionStore.load(firstId)!!.messages.size)
        assertEquals(2, h.sessionStore.load(other.id)!!.messages.size)

        vm.closeForTest()
    }

    @Test
    fun firstConfiguredInitialization_exposesCreatedSessionSummary() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Persisted settings + key, but no sessions yet: initialization must
        // create the first session and expose it in the summaries.
        h.settings.setProviderId("zai")
        h.settings.setModelId("glm-4.7")
        h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

        val vm = h.newViewModel()
        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(1, state.sessionSummaries.size)
        assertEquals(state.activeSessionId, state.sessionSummaries.single().id)

        vm.closeForTest()
    }

    @Test
    fun scopeTeardownMidSave_stillPersistsAcceptedSnapshots() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        // The first save (user message) suspends inside the gate; the final
        // snapshot (user + assistant) is accepted while that save is in flight.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.sessions.saveEntered = entered
        h.sessions.saveGate = release
        h.scriptedStreams.add(h.gatedStream("world", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        entered.await()

        // Teardown mid-save: neither the in-flight nor the accepted pending
        // snapshot may be silently dropped.
        val job = vm.viewModelScope.coroutineContext[Job]!!
        job.cancel()
        release.complete(Unit)
        job.join()

        val session = h.sessionStore.load(sessionId)!!
        assertEquals(2, session.messages.size)
        assertEquals("Hello", session.title)
        // Both saves happened: the gated user-message snapshot drained *and* the
        // coalesced final snapshot was then dequeued and written — proving the
        // loop drains accepted pendings rather than skipping to the last one.
        assertEquals(2, h.sessions.totalSaves)
    }

    @Test
    fun persistenceFailure_blocksSessionSwitch_untilRetrySucceeds() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val firstId = vm.uiState.value.activeSessionId!!

        // First exchange succeeds; then saves start failing.
        h.scriptedStreams.add(h.gatedStream("one", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("First")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == firstId }.messageCount == 2 }

        h.sessions.failSave = true
        h.scriptedStreams.add(h.gatedStream("two", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Second")
        vm.send()
        vm.uiState.first { it.error != null && !it.isStreaming }
        assertTrue(h.sessions.failedSaves > 0)

        // The failed save must block abandoning the session: the switch is
        // rejected with the session error and the active session is kept.
        vm.dismissError()
        vm.newSession()
        vm.uiState.first { it.error != null }
        assertEquals(firstId, vm.uiState.value.activeSessionId)
        assertEquals(4, vm.uiState.value.messages.size)
        // The blocked intent explicitly retried the unsaved snapshot once.
        val savesAfterFailedExchange = h.sessions.failedSaves
        vm.dismissError()
        vm.newSession()
        vm.uiState.first { it.error != null }
        assertTrue(h.sessions.failedSaves > savesAfterFailedExchange)
        assertEquals(firstId, vm.uiState.value.activeSessionId)
        vm.dismissError()

        // A later agent transition retries the snapshot: after another
        // exchange with saves working again, the full transcript persists.
        h.sessions.failSave = false
        h.scriptedStreams.add(h.gatedStream("three", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Third")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 6 }
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == firstId }.messageCount == 6 }
        assertEquals(6, h.sessionStore.load(firstId)!!.messages.size)

        // Now switching is allowed again.
        vm.newSession()
        val state = vm.uiState.first { it.activeSessionId != firstId }
        assertTrue(state.messages.isEmpty())
        assertEquals(6, h.sessionStore.load(firstId)!!.messages.size)

        vm.closeForTest()
    }

    @Test
    fun providerAndModelOptions_followCredentialState() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        val state = vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // All catalog providers listed (name-sorted), all unconfigured, so the
        // model picker is empty: pi's "only configured providers" rule.
        assertEquals(listOf("Cloudflare AI Gateway", "Z.AI"), state.providerOptions.map { it.name })
        assertTrue(state.providerOptions.none { it.configured })
        assertTrue(state.modelOptions.isEmpty())

        vm.saveProviderCredential("zai", "SECRET-KEY-777", emptyMap())
        val after = vm.uiState.first { it.providerOptions.first { o -> o.id == "zai" }.configured }
        assertTrue(after.providerOptions.first { it.id == "cloudflare-ai-gateway" }.let { !it.configured })
        assertTrue(after.modelOptions.isNotEmpty())
        assertTrue(after.modelOptions.all { it.providerId == "zai" })
        assertEquals("GLM-4.7", after.modelOptions.first { it.modelId == "glm-4.7" }.name)
        assertNull(after.selectedModel)
        assertFalse(after.configured)
        assertFalse(after.toString().contains("SECRET-KEY-777"))

        vm.saveProviderCredential(
            "cloudflare-ai-gateway",
            "cf",
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
        )
        val both = vm.uiState.first { it.providerOptions.first { o -> o.id == "cloudflare-ai-gateway" }.configured }
        assertTrue(both.modelOptions.any { it.providerId == "cloudflare-ai-gateway" && it.modelId == "workers-ai/test-model" })
        // Provider-name-then-model-name sort: Cloudflare options come first.
        assertEquals("Cloudflare AI Gateway", both.modelOptions.first().providerName)

        vm.closeForTest()
    }

    @Test
    fun saveProviderCredential_mergesKeyAndEnvValues() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // A key-only save is incomplete for Cloudflare (account/gateway ids
        // are required too): rejected, nothing persisted, safe error naming
        // the missing prompts — never the submitted values.
        vm.saveProviderCredential("cloudflare-ai-gateway", "cf-key", emptyMap())
        val state = vm.uiState.first { it.error != null }
        assertTrue(state.error!!.contains("account ID"))
        assertTrue(state.error!!.contains("gateway ID"))
        assertFalse(state.error!!.contains("cf-key"))
        assertNull(h.credentials.creds["cloudflare-ai-gateway"])
        assertFalse(vm.uiState.value.providerOptions.first { o -> o.id == "cloudflare-ai-gateway" }.configured)
        vm.dismissError()

        // A complete save persists key plus env.
        vm.saveProviderCredential(
            "cloudflare-ai-gateway",
            "cf-key",
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
        )
        vm.uiState.first { it.providerOptions.first { o -> o.id == "cloudflare-ai-gateway" }.configured }
        val filled = h.credentials.creds["cloudflare-ai-gateway"]!!
        assertEquals("cf-key", filled.key)
        assertEquals(mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"), filled.env)

        // Blank key and blank env values keep the stored ones; a new key rotates.
        vm.saveProviderCredential("cloudflare-ai-gateway", "cf-key-2", mapOf("CLOUDFLARE_ACCOUNT_ID" to " "))
        val rotated = h.credentials.creds["cloudflare-ai-gateway"]!!
        assertEquals("cf-key-2", rotated.key)
        assertEquals(mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"), rotated.env)

        // The very first save with a blank key is rejected (no existing key).
        vm.saveProviderCredential("zai", "   ", emptyMap())
        vm.uiState.first { it.error != null }
        assertFalse(vm.uiState.value.providerOptions.first { o -> o.id == "zai" }.configured)
        vm.dismissError()

        // A credential-store failure surfaces a safe error.
        h.credentials.failWrites = true
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.uiState.first { it.error != null }
        vm.closeForTest()
    }

    @Test
    fun saveModelSelection_rejectsIncompleteCloudflareCredential() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // A key-only credential is incomplete for Cloudflare (account/gateway
        // ids required): model selection is gated with a safe error and the
        // provider never counts as configured.
        h.credentials.creds["cloudflare-ai-gateway"] = ApiKeyCredential("cf", emptyMap())
        vm.refreshProviderStatus()
        assertFalse(vm.uiState.value.providerOptions.first { o -> o.id == "cloudflare-ai-gateway" }.configured)
        assertTrue(vm.uiState.value.modelOptions.none { it.providerId == "cloudflare-ai-gateway" })

        vm.saveModelSelection("cloudflare-ai-gateway", "workers-ai/test-model", null)
        vm.uiState.first { it.error != null }
        assertEquals(ChatStatus.NeedsConfiguration, vm.uiState.value.status)
        assertEquals(0, h.countSessions())

        vm.closeForTest()
    }

    @Test
    fun saveProviderCredential_withValidSettings_adoptsSession_andGoesReady() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Valid model settings persisted, but the key is missing: pi's
        // completeProviderAuthentication semantics — logging in completes it.
        h.settings.setProviderId("zai")
        h.settings.setModelId("glm-4.7")

        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        assertNull(vm.uiState.value.activeSessionId)

        vm.saveProviderCredential("zai", "k", emptyMap())
        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(ChatNavKey, state.startKey)
        assertTrue(state.configured)
        assertTrue(state.navigationEpoch >= 1L)
        assertNotNull(state.activeSessionId)
        assertEquals("zai", state.selectedModel?.providerId)
        assertTrue(state.modelOptions.all { it.providerId == "zai" })
        assertEquals("zai", h.settings.currentSettings().providerId)

        vm.closeForTest()
    }

    @Test
    fun removeProviderCredential_unconfigures_butNeverTearsDownSessions() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val agentsBefore = h.createdAgents.size

        vm.removeProviderCredential("zai")
        val state = vm.uiState.first { !it.configured }
        // Status stays Ready and the agent is untouched: credentials are read
        // per request, sessions are never torn down.
        assertEquals(ChatStatus.Ready, state.status)
        assertEquals(agentsBefore, h.createdAgents.size)
        assertNotNull(state.activeSessionId)
        assertFalse(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertTrue(state.modelOptions.isEmpty())
        // The committed selection stays visible for the model screen.
        assertEquals("glm-4.7", state.selectedModel?.modelId)
        assertNull(h.credentials.creds["zai"])

        // Sending still works through the still-bound agent.
        h.scriptedStreams.add(h.gatedStream("world", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }

        // Re-login restores configured status.
        vm.saveProviderCredential("zai", "k2", emptyMap())
        vm.uiState.first { it.configured }

        vm.closeForTest()
    }

    @Test
    fun unknownProviderSettings_initNeedsConfiguration() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        h.settings.setProviderId("not-a-provider")
        h.settings.setModelId("glm-4.7")
        h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

        val vm = h.newViewModel()
        val state = vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        assertNull(state.selectedModel)
        assertFalse(state.configured)
        // The valid zai key still drives the model picker.
        assertTrue(state.modelOptions.all { it.providerId == "zai" })
        assertTrue(state.modelOptions.isNotEmpty())

        // Model selection for an unknown provider is rejected safely.
        vm.saveModelSelection("not-a-provider", "glm-4.7", null)
        vm.uiState.first { it.error != null }
        assertEquals(ChatStatus.NeedsConfiguration, vm.uiState.value.status)
        assertEquals(0, h.countSessions())

        vm.closeForTest()
    }
}
