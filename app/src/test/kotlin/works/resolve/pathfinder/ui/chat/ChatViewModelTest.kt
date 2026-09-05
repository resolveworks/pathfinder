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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainDispatcherRule : org.junit.rules.TestWatcher() {
    val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(scheduler)
    override fun starting(description: Description?) = Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description?) = Dispatchers.resetMain()
}

class ChatViewModelTest {

    /** Live harnesses; [disposeHarnesses] tears them all down even when a test failed mid-body, so a still-alive ViewModel scope never leaks into a later test. */
    private val harnesses = CopyOnWriteArrayList<Harness>()

    /** Safety net: passing tests join via [closeForTest]; a test that failed first leaves live ViewModel scopes to cancel and join here. */
    @After
    fun disposeHarnesses() {
        for (harness in harnesses) {
            for (vm in harness.viewModels) {
                val job = vm.viewModelScope.coroutineContext[Job]!!
                if (!job.isCancelled) {
                    runBlocking { withTimeout(10_000) { job.cancelAndJoin() } }
                }
            }
            harness.dataStoreScope.cancel()
        }
        harnesses.clear()
    }

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testModel = Model(
        id = "glm-4.7",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid"
    )

    private class FakeCredentialStore : CredentialStore {
        val creds = mutableMapOf<String, Credential>()
        var failWrites = false
        private fun check() {
            if (failWrites) throw java.io.IOException("credential store failed")
        }
        override suspend fun read(providerId: String): Credential? {
            check()
            return creds[providerId]
        }
        override suspend fun list(): List<CredentialInfo> {
            check()
            return creds.map { CredentialInfo(it.key, it.value.type) }
        }
        override suspend fun modify(
            providerId: String,
            update: suspend (Credential?) -> Credential?
        ): Credential? {
            check()
            val next = update(creds[providerId])
            if (next != null) creds[providerId] = next
            return creds[providerId]
        }
        override suspend fun delete(providerId: String) {
            check()
            creds.remove(providerId)
        }
    }

    private class FakeOAuthAuth(
        override val name: String = "Z.AI Account",
        override val loginLabel: String = "Sign in with a Z.AI account",
        override val isSubscription: Boolean = true
    ) : OAuthAuth {
        var loginFn: suspend (AuthInteraction) -> OAuthCredential = {
            OAuthCredential(access = "access-1", refresh = "refresh-1", expires = Long.MAX_VALUE)
        }
        override suspend fun login(interaction: AuthInteraction): OAuthCredential =
            loginFn(interaction)
        override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential
        override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
            ModelAuth(apiKey = credential.access)
    }

    class FailingSettingsStore(private val delegate: SettingsStore) : SettingsStore by delegate {
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
        override suspend fun setActiveSessionId(sessionId: String?) {
            if (failActiveSessionWrites) throw java.io.IOException("active session write failed")
            delegate.setActiveSessionId(sessionId)
        }
        override suspend fun setShowThinking(showThinking: Boolean) {
            if (failWrites) throw java.io.IOException("settings write failed")
            delegate.setShowThinking(showThinking)
        }
    }

    /**
     * Real managers over a temp dir (the source is a thin seam). All manager
     * IO runs on [Dispatchers.Unconfined] — inline on the caller, as in the
     * ported SessionManagerTest — so nothing escapes the test scheduler onto
     * a real dispatcher the virtual clock cannot see (the certain-hang
     * combination). `denyWrites` flips the directory read-only so the next
     * assistant commit fails with SessionError(STORAGE), like a full disk;
     * `managers` keeps the live instance per id so buffered (never-flushed)
     * entries stay inspectable.
     */
    private inner class TestSessionSource : SessionSource {
        // Created eagerly: denyWrites's read-only flip is a no-op on a
        // nonexistent dir (the manager would just mkdirs a writable one at
        // the first flush).
        val dir = File(tmpFolder.root, "sessions_${'$'}{System.nanoTime()}").apply { mkdirs() }
        val managers = java.util.concurrent.ConcurrentHashMap<String, SessionManager>()
        var nextId = 0
        var failList = false
        var listCalls = 0
            private set
        var denyWrites = false
            set(value) {
                field = value
                dir.setWritable(!value)
            }

        override suspend fun create(): SessionManager {
            val manager = SessionManager.create(
                dir,
                idFactory = { "sess-" + nextId++ },
                ioDispatcher = Dispatchers.Unconfined
            )
            managers[manager.sessionId] = manager
            return manager
        }

        override suspend fun open(id: String): SessionManager? = SessionManager.openById(
            dir,
            id,
            idFactory = { "sess-" + nextId++ },
            ioDispatcher = Dispatchers.Unconfined
        )?.also { managers[it.sessionId] = it }

        override suspend fun list(): List<SessionInfo> {
            listCalls += 1
            if (failList) throw SessionError(SessionErrorCode.STORAGE, "list failed")
            return SessionManager.list(dir, ioDispatcher = Dispatchers.Unconfined)
        }

        /** Re-reads a session from disk; null while it has never been flushed. */
        suspend fun stored(id: String): Conversation? =
            SessionManager.openById(dir, id, ioDispatcher = Dispatchers.Unconfined)?.conversation
    }

    /**
     * Test harness wiring real repositories/stores and scripted real Agents:
     * real implementations run above the storage boundaries and substitution
     * happens only there (in-memory credentials, real tempdir files, real
     * Models/resolver paths). The scripted [factory] and [rejectedModelIds]
     * are the only behavior fakes.
     */
    private inner class Harness {
        init {
            harnesses += this
        }

        val viewModels = CopyOnWriteArrayList<ChatViewModel>()

        val credentials = FakeCredentialStore()

        val searchProviders = SearchProviderService(credentials)

        private val fakeWebSearchTool: AgentTool = object : AgentTool {
            override val definition =
                Tool(BraveWebSearchTool.NAME, "fake web search", JsonPrimitive("object"))
            override val label = "Web Search"
            override fun validateArguments(arguments: JsonObject) = arguments
            override suspend fun execute(
                toolCallId: String,
                arguments: JsonObject,
                onUpdate: (AgentToolResult) -> Unit
            ) = AgentToolResult(content = listOf(TextContent("no results")))
        }

        val oauthZai = FakeOAuthAuth()
        val oauthOnly = FakeOAuthAuth(
            name = "OAuth Only Account",
            loginLabel = "Sign in with an account"
        )
        val oauthCopilot = FakeOAuthAuth(
            name = "GitHub Copilot",
            loginLabel = "Sign in with GitHub",
            isSubscription = true
        )
        val authRegistry = MapCatalogAuthRegistry(
            mapOf("zai" to oauthZai, "oauth-only" to oauthOnly, "github-copilot" to oauthCopilot)
        )
        val authService = ProviderAuthService(
            catalog = works.resolve.pathfinder.ai.testing.TestCatalogs.CATALOG,
            registry = authRegistry,
            credentials = credentials
        )
        val dataStoreScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher)
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = {
                    File(tmpFolder.root, "settings_${System.nanoTime()}.preferences_pb")
                }
            )
        )
        val settingsStore = FailingSettingsStore(settings)
        val sessions = TestSessionSource()

        val scriptedStreams = ConcurrentLinkedQueue<Flow<AssistantMessageEvent>>()

        /** Fake summarization stack for compaction tests: serves [summaryResponses] through [Models.completeSimple], optionally gated mid-summary. */
        var compactionModels: Models? = null
        val summaryResponses = ConcurrentLinkedQueue<AssistantMessage>()
        var summaryGate: CompletableDeferred<Unit>? = null

        fun installCompactionModels() {
            val api = object : ChatApi {
                override fun streamSimple(
                    model: Model,
                    context: Context,
                    options: SimpleStreamOptions
                ) = flow {
                    summaryGate?.await()
                    val response = summaryResponses.poll() ?: error("No summary response queued")
                    if (response.stopReason == StopReason.ERROR ||
                        response.stopReason == StopReason.ABORTED
                    ) {
                        emit(AssistantMessageEvent.Error(response.stopReason, response))
                    } else {
                        emit(AssistantMessageEvent.Done(response.stopReason, response))
                    }
                }
            }
            compactionModels = Models(
                listOf(
                    Provider(
                        testModel.provider,
                        testModel.provider,
                        "https://faux.test",
                        authResolver = { _, _ -> ResolvedAuth(apiKey = "faux-key") },
                        models = listOf(testModel),
                        apis = mapOf(testModel.api to api)
                    )
                )
            )
        }

        /** When set, agents are built with auto-compaction disabled (isolates branch summarization). */
        var disableCompaction = false

        val rejectedModelIds = mutableSetOf<String>()

        var rejectAll = false
        val createdAgents = mutableListOf<AgentSession>()

        val createdSettings = mutableListOf<ModelSettings>()

        val streamedModels = CopyOnWriteArrayList<Model>()

        /**
         * Boundary fake: must never be reached — agent streams are scripted
         * at the [factory] seam, so only [Models.checkAuth] resolution runs
         * against the stack built over this transport.
         */
        val transport = object : HttpStreamingTransport {
            override suspend fun post(request: TransportRequest): TransportResponse =
                error("network transport reached: scripted streams must bypass it")
        }

        /**
         * Live-switch model stack built through the real production path
         * ([CatalogProvider.toRuntimeProvider] + [catalogAuthResolver]), so
         * setModel's checkAuth resolves stored credentials exactly like
         * production.
         */
        val switchModels = Models(
            works.resolve.pathfinder.ai.testing.TestCatalogs.CATALOG.providers.map { entry ->
                entry.toRuntimeProvider(
                    transport = transport,
                    authResolver = catalogAuthResolver(
                        entry,
                        credentials,
                        NoopAuthContext,
                        authRegistry
                    )
                )
            }
        )

        private val nativeFactory = NativeAgentFactory(
            credentials = credentials,
            catalog = works.resolve.pathfinder.ai.testing.TestCatalogs.CATALOG,
            transport = transport,
            authRegistry = authRegistry
        )

        val modelResolver: (String, String) -> Model = { providerId, modelId ->
            if (modelId in rejectedModelIds) {
                throw IllegalArgumentException(
                    "model rejected (harness-injected validation failure)"
                )
            }
            nativeFactory.resolveModel(providerId, modelId)
        }

        val factory = AgentFactory { settings, sessionManager ->
            check(!rejectAll) { "factory unavailable" }
            require(settings.modelId !in rejectedModelIds) { "model rejected" }
            createdSettings += settings
            AgentSession(
                // Resolves through the same production seam as modelResolver —
                // never a parallel hand-written Model: capabilities (reasoning,
                // thinkingLevelMap) are behavior, and a duplicate shape
                // diverges silently.
                agent = Agent(
                    model = nativeFactory.resolveModel(settings.providerId, settings.modelId),
                    streamFn = StreamFn { requestedModel, _, _ ->
                        streamedModels.add(requestedModel)
                        scriptedStreams.poll() ?: flow { kotlinx.coroutines.awaitCancellation() }
                    }
                ),
                sessionManager = sessionManager,
                tools = listOf(fakeWebSearchTool),
                retrySettings = settings.retry,
                compactionSettings = if (disableCompaction) {
                    settings.compaction.copy(
                        enabled = false
                    )
                } else {
                    settings.compaction
                },
                models = compactionModels ?: switchModels
            ).also { session -> createdAgents += session }
        }

        fun newViewModel(): ChatViewModel = ChatViewModel(
            settingsRepository = settingsStore,
            catalog = works.resolve.pathfinder.ai.testing.TestCatalogs.CATALOG,
            authService = authService,
            sessionSource = sessions,
            agentFactory = factory,
            modelResolver = modelResolver,
            searchProviderService = searchProviders
        ).also { viewModels += it }

        fun assistant(
            text: String,
            stopReason: StopReason = StopReason.STOP,
            error: String? = null
        ) = AssistantMessage(
            content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
            api = testModel.api,
            provider = testModel.provider,
            model = testModel.id,
            stopReason = stopReason,
            errorMessage = error,
            timestamp = System.nanoTime()
        )

        fun gatedStream(
            text: String,
            gate: CompletableDeferred<Unit>
        ): Flow<AssistantMessageEvent> = flow {
            emit(AssistantMessageEvent.Start(assistant("")))
            gate.await()
            val full = assistant(text)
            emit(AssistantMessageEvent.TextDelta(0, text, full))
            emit(AssistantMessageEvent.Done(StopReason.STOP, full))
        }

        fun errorStream(message: AssistantMessage) =
            flowOf(AssistantMessageEvent.Error(StopReason.ERROR, message))

        suspend fun countSessions(): Int = sessions.list().size

        /** The live manager the ViewModel holds for [id] (includes buffered entries). */
        fun liveManager(id: String): SessionManager = sessions.managers.getValue(id)

        fun storedApiKey(providerId: String): String? =
            (credentials.creds[providerId] as? ApiKeyCredential)?.key
    }

    /**
     * Waits in real time (off the test scheduler) until [condition] holds:
     * the store's IO appends run on real Dispatchers.IO, which runTest's
     * virtual clock cannot see (it would idle-advance into the timeout).
     */
    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(timeoutMs) {
                while (!condition()) delay(10)
            }
        }
    }

    /**
     * Bounded state wait: an unmet condition fails fast with a timeout
     * instead of wedging the run until runTest's own minute-long backstop.
     */
    private suspend fun ChatViewModel.awaitState(
        timeoutMs: Long = 5_000,
        predicate: suspend (ChatUiState) -> Boolean
    ): ChatUiState = withTimeout(timeoutMs) { uiState.first { predicate(it) } }

    private suspend fun ChatViewModel.closeForTest() {
        val job = viewModelScope.coroutineContext[Job]!!
        job.cancel()
        job.join()
    }

    private fun ChatViewModel.configure(modelId: String = "glm-4.7", apiKey: String = "") {
        if (apiKey.isNotEmpty()) saveProviderCredential("zai", apiKey, emptyMap())
        if (modelId != "glm-4.7") selectModel("zai", modelId)
    }

    private fun copilotCredential(availableModelIds: JsonElement? = null): OAuthCredential =
        OAuthCredential(
            access = "copilot-access",
            refresh = "copilot-refresh",
            expires = Long.MAX_VALUE,
            extras = availableModelIds?.let { mapOf("availableModelIds" to it) } ?: emptyMap()
        )

    private fun stringArray(vararg ids: String): JsonArray = JsonArray(
        ids.map {
            JsonPrimitive(it)
        }
    )

    private val JsonElement.jsonPrimitiveContent: String get() = (this as JsonPrimitive).content

    private fun ChatViewModel.copilotModelOptions(): List<String> =
        uiState.value.modelOptions.filter { it.providerId == "github-copilot" }.map { it.modelId }

    @Test
    fun unconfiguredInit_showsNeedsConfiguration_andKeepsKeyPrivate() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()

            val state = vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertEquals(ProvidersNavKey, state.startKey)
            assertFalse(state.providerOptions.first { o -> o.id == "zai" }.configured)
            assertNull(state.activeSessionId)
            assertTrue(state.messages.isEmpty())
            assertTrue(state.modelOptions.isEmpty())

            // A stored key with no model settings: the initial model is derived
            // (first available of a configured provider) and the app enters the
            // chat directly — while the key never appears anywhere in the UI state.
            h.credentials.creds["zai"] = ApiKeyCredential("SECRET-KEY-123")
            val vm2 = h.newViewModel()
            val state2 = vm2.awaitState { it.status == ChatStatus.Ready }
            assertTrue(state2.providerOptions.first { o -> o.id == "zai" }.configured)
            assertEquals("glm-4.7", state2.selectedModel?.modelId)
            assertNotNull(state2.activeSessionId)
            assertFalse(state2.toString().contains("SECRET-KEY-123"))

            vm.closeForTest()
            vm2.closeForTest()
        }

    @Test
    fun showThinking_persists_andInitProjectsPersistedValue() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
            val h = Harness()
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
        val h = Harness()
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
        val h = Harness()
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
            val h = Harness()
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
        val h = Harness()
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
        val h = Harness()
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
            val h = Harness()
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
        val h = Harness()
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
        val h = Harness()
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
        val h = Harness()
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
        val h = Harness()
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
        val h = Harness()
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
            val h = Harness()
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
        val h = Harness()
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
            val h = Harness()
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
            val h = Harness()
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
    fun credentialSave_success_bumpsSuccessEpoch_failedOrIncompleteDoesNot() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            vm.saveProviderCredential("zai", "   ", emptyMap())
            vm.awaitState { it.error != null }
            assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
            vm.dismissError()

            h.credentials.failWrites = true
            vm.saveProviderCredential("zai", "k", emptyMap())
            vm.awaitState { it.error != null }
            assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
            assertNull(h.credentials.creds["zai"])
            vm.dismissError()
            h.credentials.failWrites = false

            vm.saveProviderCredential("zai", "k", emptyMap())
            vm.awaitState { it.credentialSuccessEpoch == 1L }
            assertEquals("k", h.storedApiKey("zai"))

            vm.saveProviderCredential("zai", "k2", emptyMap())
            vm.awaitState { it.credentialSuccessEpoch == 2L }
            assertEquals("k2", h.storedApiKey("zai"))

            vm.closeForTest()
        }

    @Test
    fun searchInit_unconfiguredBraveRow_andWebSearchAbsent() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
            val h = Harness()
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
    fun searchSave_blankAndFailedSaves_neverBumpOrActivate() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val agent = h.createdAgents.single()

            vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "   ")
            vm.awaitState { it.error != null }
            assertEquals(0L, vm.uiState.value.searchCredentialSuccessEpoch)
            assertNull(h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID))
            vm.dismissError()

            vm.saveSearchProviderCredential("nope", "k")
            vm.awaitState { it.error != null }
            assertEquals(0L, vm.uiState.value.searchCredentialSuccessEpoch)
            vm.dismissError()

            h.credentials.failWrites = true
            vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
            vm.awaitState { it.error != null }
            assertEquals(0L, vm.uiState.value.searchCredentialSuccessEpoch)
            assertFalse(vm.uiState.value.searchProviderOptions.single().configured)
            assertFalse(BraveWebSearchTool.NAME in agent.getActiveToolNames())
            vm.dismissError()
            h.credentials.failWrites = false

            // Confirmed save enables web_search on the SAME session.
            vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
            vm.awaitState { it.searchCredentialSuccessEpoch == 1L }
            assertEquals("brave-key", h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID))
            assertTrue(vm.uiState.value.searchProviderOptions.single().configured)
            assertEquals(1, h.createdAgents.size)
            assertTrue(BraveWebSearchTool.NAME in agent.getActiveToolNames())
            assertFalse(vm.uiState.value.toString().contains("brave-key"))

            vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key-2")
            vm.awaitState { it.searchCredentialSuccessEpoch == 2L }
            assertEquals("brave-key-2", h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID))

            vm.closeForTest()
        }

    @Test
    fun searchRemove_deletesKey_andDisablesWebSearch() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val agent = h.createdAgents.single()

        vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
        vm.awaitState { it.searchCredentialSuccessEpoch == 1L }
        assertTrue(BraveWebSearchTool.NAME in agent.getActiveToolNames())

        vm.removeSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID)
        vm.awaitState { !it.searchProviderOptions.single().configured }
        assertNull(h.storedApiKey(SearchProviderService.BRAVE_CREDENTIAL_ID))
        assertFalse(BraveWebSearchTool.NAME in agent.getActiveToolNames())
        assertEquals(1, h.createdAgents.size)
        assertEquals(1L, vm.uiState.value.searchCredentialSuccessEpoch)

        vm.closeForTest()
    }

    @Test
    fun searchStatusReadFailure_degradesWithoutBreakingReady() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val agent = h.createdAgents.single()
            vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
            vm.awaitState { it.searchCredentialSuccessEpoch == 1L }
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
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val firstId = vm.uiState.value.activeSessionId!!
            vm.saveSearchProviderCredential(SearchProviderService.BRAVE_PROVIDER_ID, "brave-key")
            vm.awaitState { it.searchCredentialSuccessEpoch == 1L }
            assertTrue(BraveWebSearchTool.NAME in h.createdAgents.single().getActiveToolNames())

            // Every tryCreateAgent path synchronizes web_search.
            vm.newSession()
            vm.awaitState { it.activeSessionId != firstId }
            val newAgent = h.createdAgents.single { it !== h.createdAgents.first() }
            assertEquals(1, newAgent.getActiveToolNames().count { it == BraveWebSearchTool.NAME })
            assertEquals(BraveWebSearchTool.NAME, newAgent.getActiveToolNames().last())

            vm.closeForTest()
        }

    @Test
    fun invalidModel_andResolverValidation_areRejectedSafely() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
            assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
            vm.dismissError()

            h.rejectedModelIds += "glm-5.3"
            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.error != null }
            assertEquals(agentsBefore, h.createdAgents.size)
            assertTrue(vm.uiState.value.status == ChatStatus.Ready)
            assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
            assertEquals("", h.settings.currentSettings().modelId)

            vm.closeForTest()
        }

    @Test
    fun blankKeySave_isRejected_andCompleteSaveReplacesStoredKey() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "first-key")
            vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals("first-key", h.storedApiKey("zai"))

            // A blank key is a missing required value (logins replace wholesale):
            // rejected with an error naming the missing prompt — never its value —
            // leaving the stored credential untouched.
            vm.saveProviderCredential("zai", "   ", emptyMap())
            vm.awaitState { it.error != null }
            val state = vm.uiState.value
            val error = checkNotNull(state.error)
            assertTrue(error.contains("API key"))
            assertFalse(error.contains("first-key"))
            assertFalse(state.toString().contains("first-key"))
            assertEquals(ChatStatus.Ready, state.status)
            assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
            assertEquals("first-key", h.storedApiKey("zai"))
            vm.dismissError()

            vm.saveProviderCredential("zai", "second-key", emptyMap())
            vm.awaitState { it.credentialSuccessEpoch == 2L }
            assertEquals("second-key", h.storedApiKey("zai"))
            assertEquals(1, h.createdAgents.size)

            vm.closeForTest()
        }

    @Test
    fun busyIntents_areRejectedWhileStreaming() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
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
        val h = Harness()
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
            val h = Harness()
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
            val h2 = Harness()
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
    fun storedCredential_survivesFailedReSave_completeRetryReplaces() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            h.rejectedModelIds += "glm-5.3"
            vm.saveProviderCredential("zai", "first-key", emptyMap())
            // The derived initial model is unaffected by the rejection.
            vm.awaitState { it.status == ChatStatus.Ready }
            val state = vm.uiState.value
            assertEquals("first-key", h.storedApiKey("zai"))
            assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
            assertFalse(state.toString().contains("first-key"))

            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.error != null }
            assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
            vm.dismissError()

            // An incomplete re-save (blank key: logins re-prompt everything,
            // nothing is merged) is rejected; the stored credential survives.
            vm.saveProviderCredential("zai", "  ", emptyMap())
            vm.awaitState { it.error != null }
            assertFalse(checkNotNull(vm.uiState.value.error).contains("first-key"))
            assertEquals("first-key", h.storedApiKey("zai"))
            assertFalse(vm.uiState.value.toString().contains("first-key"))
            vm.dismissError()

            vm.saveProviderCredential("zai", "second-key", emptyMap())
            vm.awaitState { it.credentialSuccessEpoch == 2L }
            assertEquals("second-key", h.storedApiKey("zai"))
            assertEquals(ChatStatus.Ready, vm.uiState.value.status)

            vm.closeForTest()
        }

    @Test
    fun sameTimestampMessages_getDistinctKeys() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
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
    fun settingsWriteFailure_liveSwitchUnaffected_startupDefaultSurfacesError() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

            h.settingsStore.failWrites = true

            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            assertNull(vm.uiState.value.error)

            vm.saveStartupDefault("zai", "glm-5.3")
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
    fun initActiveSessionWriteFailure_isFailed_neverReady() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
    fun switchingToAFlushedSession_appendsTheMissingThinkingSeed_inPlace() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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

            vm.switchSession(other.sessionId)
            val state = vm.awaitState { it.activeSessionId == other.sessionId }
            assertEquals(2, state.messages.size)
            waitUntil {
                h.sessions.stored(other.sessionId)!!
                    .entries.filterIsInstance<ThinkingLevelEntry>().isNotEmpty()
            }
            assertNull(h.sessions.stored(firstId))
            val reloaded = h.sessions.stored(other.sessionId)!!
            assertEquals(2, reloaded.activeMessages().size)
            assertEquals(
                listOf("medium"),
                reloaded.entries.filterIsInstance<ThinkingLevelEntry>()
                    .map { it.thinkingLevel }
            )

            vm.closeForTest()
        }

    @Test
    fun newSession_isAbsentFromSummaries_untilTheFirstAssistantCommits() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
            val h = Harness()
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

    @Test
    fun providerAndModelOptions_followCredentialState() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        val state = vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

        // All catalog providers listed, all unconfigured: only configured
        // providers contribute model options.
        assertEquals(
            listOf("Cloudflare AI Gateway", "GitHub Copilot", "OAuth Only", "OpenAI", "Z.AI"),
            state.providerOptions.map { it.name }
        )
        assertTrue(state.providerOptions.none { it.configured })
        assertTrue(state.modelOptions.isEmpty())

        vm.saveProviderCredential("zai", "SECRET-KEY-777", emptyMap())
        val after = vm.awaitState { it.status == ChatStatus.Ready }
        assertTrue(
            after.providerOptions.first {
                it.id == "cloudflare-ai-gateway"
            }.let { !it.configured }
        )
        assertTrue(after.modelOptions.isNotEmpty())
        assertTrue(after.modelOptions.all { it.providerId == "zai" })
        assertEquals("GLM-4.7", after.modelOptions.first { it.modelId == "glm-4.7" }.name)
        assertEquals("glm-4.7", after.selectedModel?.modelId)
        assertEquals(after.modelOptions, after.scopedModelOptions)
        assertNull(after.enabledModels)
        assertFalse(after.toString().contains("SECRET-KEY-777"))

        vm.saveProviderCredential(
            "cloudflare-ai-gateway",
            "cf",
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw")
        )
        val both = vm.awaitState {
            it.providerOptions.first { o ->
                o.id ==
                    "cloudflare-ai-gateway"
            }.configured
        }
        assertTrue(
            both.modelOptions.any {
                it.providerId == "cloudflare-ai-gateway" &&
                    it.modelId == "workers-ai/test-model"
            }
        )
        // Provider-name-then-model-name sort.
        assertEquals("Cloudflare AI Gateway", both.modelOptions.first().providerName)

        vm.closeForTest()
    }

    @Test
    fun saveProviderCredential_replacesCredentialWholesale() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            // A key-only save is incomplete for Cloudflare (account/gateway ids
            // are required too); the error names the missing prompts — never the
            // submitted values.
            vm.saveProviderCredential("cloudflare-ai-gateway", "cf-key", emptyMap())
            val state = vm.awaitState { it.error != null }
            val error = checkNotNull(state.error)
            assertTrue(error.contains("account ID"))
            assertTrue(error.contains("gateway ID"))
            assertFalse(error.contains("cf-key"))
            assertNull(h.credentials.creds["cloudflare-ai-gateway"])
            assertFalse(
                vm.uiState.value.providerOptions.first { o ->
                    o.id == "cloudflare-ai-gateway"
                }.configured
            )
            vm.dismissError()

            vm.saveProviderCredential(
                "cloudflare-ai-gateway",
                "cf-key",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw")
            )
            vm.awaitState {
                it.providerOptions.first { o -> o.id == "cloudflare-ai-gateway" }.configured
            }
            val filled = h.credentials.creds["cloudflare-ai-gateway"] as ApiKeyCredential
            assertEquals("cf-key", filled.key)
            assertEquals(
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
                filled.env
            )

            // A complete re-save fully replaces key and env — no stale values
            // survive.
            vm.saveProviderCredential(
                "cloudflare-ai-gateway",
                "cf-key-2",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-2", "CLOUDFLARE_GATEWAY_ID" to "gw-2")
            )
            vm.awaitState { it.credentialSuccessEpoch == 2L }
            val rotated = h.credentials.creds["cloudflare-ai-gateway"] as ApiKeyCredential
            assertEquals("cf-key-2", rotated.key)
            assertEquals(
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-2", "CLOUDFLARE_GATEWAY_ID" to "gw-2"),
                rotated.env
            )

            // An incomplete re-save (replace semantics, nothing merged from the
            // stored credential) is rejected; the old credential is untouched.
            vm.saveProviderCredential(
                "cloudflare-ai-gateway",
                "cf-key-3",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-3")
            )
            vm.awaitState { it.error != null }
            val retryError = checkNotNull(vm.uiState.value.error)
            assertTrue(retryError.contains("gateway ID"))
            assertFalse(retryError.contains("cf-key"))
            assertFalse(retryError.contains("acc-3"))
            assertEquals(rotated, h.credentials.creds["cloudflare-ai-gateway"])
            vm.dismissError()

            vm.saveProviderCredential("zai", "   ", emptyMap())
            vm.awaitState { it.error != null }
            assertFalse(vm.uiState.value.providerOptions.first { o -> o.id == "zai" }.configured)
            vm.dismissError()

            h.credentials.failWrites = true
            vm.saveProviderCredential("zai", "k", emptyMap())
            vm.awaitState { it.error != null }
            vm.closeForTest()
        }

    @Test
    fun selectModel_rejectsUnauthenticatedProvider_safely() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val sessionId = vm.uiState.value.activeSessionId!!
            val entriesBefore = h.createdAgents.single().conversation.entries.size

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
            assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
            assertEquals(entriesBefore, h.createdAgents.single().conversation.entries.size)
            assertEquals(0, h.countSessions())

            vm.closeForTest()
        }

    @Test
    fun saveProviderCredential_withValidSettings_adoptsSession_andGoesReady() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            // Valid model settings persisted, but the key is missing: logging in
            // completes configuration.
            h.settings.setProviderId("zai")
            h.settings.setModelId("glm-4.7")

            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertNull(vm.uiState.value.activeSessionId)

            vm.saveProviderCredential("zai", "k", emptyMap())
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(ChatNavKey, state.startKey)
            assertTrue(state.navigationEpoch >= 1L)
            assertNotNull(state.activeSessionId)
            assertEquals("zai", state.selectedModel?.providerId)
            assertTrue(state.modelOptions.all { it.providerId == "zai" })
            assertEquals("glm-4.7", state.selectedModel?.modelId)

            vm.closeForTest()
        }

    @Test
    fun saveProviderCredential_withoutModelSettings_derivesInitialModel_andGoesReady() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertEquals(ProvidersNavKey, vm.uiState.value.startKey)

            vm.saveProviderCredential("zai", "k", emptyMap())
            val ready = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(ChatNavKey, ready.startKey)
            assertTrue(ready.navigationEpoch >= 1L)
            assertNotNull(ready.activeSessionId)
            assertEquals(0, h.countSessions())
            assertEquals("zai", ready.selectedModel?.providerId)
            assertEquals("glm-4.7", ready.selectedModel?.modelId)
            assertTrue(ready.modelOptions.all { it.providerId == "zai" })

            vm.saveProviderCredential(
                "cloudflare-ai-gateway",
                "cf",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw")
            )
            val both = vm.awaitState {
                it.modelOptions.any { o ->
                    o.providerId ==
                        "cloudflare-ai-gateway"
                }
            }
            assertEquals(ChatStatus.Ready, both.status)
            assertEquals(ChatNavKey, both.startKey)

            vm.closeForTest()
        }

    @Test
    fun unconfiguredInit_withStoredCredential_entersChatWithDerivedModel() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

            val vm = h.newViewModel()
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(ChatNavKey, state.startKey)
            assertTrue(state.modelOptions.isNotEmpty())
            assertTrue(state.modelOptions.all { it.providerId == "zai" })
            assertTrue(state.providerOptions.first { it.id == "zai" }.configured)
            assertEquals(AuthType.API_KEY, state.providerOptions.first { it.id == "zai" }.authType)
            assertFalse(state.toString().contains("stored-key"))
            assertNotNull(state.activeSessionId)
            assertEquals("glm-4.7", state.selectedModel?.modelId)
            // The derivation seeds the session with a buffered model_change;
            // the file appears only at the first assistant commit.
            waitUntil {
                h.sessions.managers[state.activeSessionId!!]!!.conversation.entries.isNotEmpty()
            }
            val seeded = h.sessions.managers[state.activeSessionId!!]!!.conversation
            val change = seeded.entries.filterIsInstance<ModelChangeEntry>().single()
            assertEquals("zai", change.provider)
            assertEquals("glm-4.7", change.modelId)
            assertNull(h.sessions.stored(state.activeSessionId!!))

            vm.closeForTest()
        }

    @Test
    fun removeProviderCredential_unconfigures_butNeverTearsDownSessions() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val agentsBefore = h.createdAgents.size

            vm.removeProviderCredential("zai")
            val state = vm.awaitState {
                !it.providerOptions.first { o -> o.id == "zai" }.configured
            }
            // Credentials are read per request: status stays Ready and the agent
            // is untouched.
            assertEquals(ChatStatus.Ready, state.status)
            assertEquals(agentsBefore, h.createdAgents.size)
            assertNotNull(state.activeSessionId)
            assertFalse(state.providerOptions.first { o -> o.id == "zai" }.configured)
            assertTrue(state.modelOptions.isEmpty())
            // The live session model stays visible for the model chip.
            assertEquals("glm-4.7", state.selectedModel?.modelId)
            assertNull(h.credentials.creds["zai"])

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

            vm.saveProviderCredential("zai", "k2", emptyMap())
            vm.awaitState { it.providerOptions.first { o -> o.id == "zai" }.configured }

            vm.closeForTest()
        }

    @Test
    fun unknownProviderSettings_deriveAvailableModel_andRejectUnknownPicks() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            h.settings.setProviderId("not-a-provider")
            h.settings.setModelId("glm-4.7")
            h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

            val vm = h.newViewModel()
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals("glm-4.7", state.selectedModel?.modelId)
            assertTrue(state.modelOptions.all { it.providerId == "zai" })
            assertTrue(state.modelOptions.isNotEmpty())

            vm.selectModel("not-a-provider", "glm-4.7")
            vm.awaitState { it.error != null }
            assertEquals(ChatStatus.Ready, vm.uiState.value.status)
            assertEquals("Unknown model", vm.uiState.value.error)

            vm.closeForTest()
        }

    // ---- provider auth methods & interactive account login ----

    @Test
    fun authMethods_apiKeyOnly_bothMethods_oauthOnly_andScreenModes() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            val cloudflare = vm.providerAuthMethods("cloudflare-ai-gateway")
            assertEquals(listOf(AuthType.API_KEY), cloudflare.map { it.type })
            assertEquals("Cloudflare API key", cloudflare.single().label)
            assertFalse(cloudflare.single().isSubscription)

            val zai = vm.providerAuthMethods("zai")
            assertEquals(listOf(AuthType.API_KEY, AuthType.OAUTH), zai.map { it.type })
            assertEquals("Z.AI API key", zai[0].label)
            assertFalse(zai[0].isSubscription)
            assertEquals("Sign in with a Z.AI account", zai[1].label)
            assertTrue(zai[1].isSubscription)

            val only = vm.providerAuthMethods("oauth-only")
            assertEquals(listOf(AuthType.OAUTH), only.map { it.type })
            assertTrue(only.single().isSubscription)

            assertEquals(ProviderAuthScreenMode.API_KEY_FORM, providerAuthScreenMode(cloudflare))
            assertEquals(ProviderAuthScreenMode.METHOD_CHOICE, providerAuthScreenMode(zai))
            assertEquals(ProviderAuthScreenMode.START_OAUTH, providerAuthScreenMode(only))
            assertEquals(ProviderAuthScreenMode.NO_METHODS, providerAuthScreenMode(emptyList()))

            assertTrue(vm.providerAuthMethods("no-such-provider").isEmpty())

            vm.closeForTest()
        }

    @Test
    fun projectAuthPrompt_mapsKinds_metadataOnly() {
        // Prompt metadata crosses the boundary; answers never do.
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.TEXT, "message", "placeholder"),
            projectAuthPrompt(AuthInteractionPrompt.Text("message", "placeholder"))
        )
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.SECRET, "paste token"),
            projectAuthPrompt(AuthInteractionPrompt.Secret("paste token"))
        )
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.MANUAL_CODE, "enter code"),
            projectAuthPrompt(AuthInteractionPrompt.ManualCode("enter code"))
        )
        val select = projectAuthPrompt(
            AuthInteractionPrompt.Select(
                "choose",
                listOf(AuthInteractionPrompt.Select.Option("a", "A", "first"))
            )
        )
        assertEquals(AuthPromptKind.SELECT, select.kind)
        assertEquals(listOf(AuthPromptOption("a", "A", "first")), select.options)
    }

    @Test
    fun storedOAuthCredential_configuresProvider_onlyWithRegisteredFlow() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            // A stored OAuth credential marks the provider configured where a
            // flow is registered (zai)...
            h.credentials.creds["zai"] =
                OAuthCredential("access-token-9", "refresh-token-9", Long.MAX_VALUE)
            // ...but resolves as unconfigured without a handler (cloudflare has
            // no registered flow).
            h.credentials.creds["cloudflare-ai-gateway"] =
                OAuthCredential("access-token-9", "refresh-token-9", Long.MAX_VALUE)

            val vm = h.newViewModel()
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertTrue(state.providerOptions.first { it.id == "zai" }.configured)
            assertEquals(AuthType.OAUTH, state.providerOptions.first { it.id == "zai" }.authType)
            assertFalse(state.providerOptions.first { it.id == "cloudflare-ai-gateway" }.configured)
            assertNull(state.providerOptions.first { it.id == "cloudflare-ai-gateway" }.authType)
            assertTrue(state.modelOptions.all { it.providerId == "zai" })
            assertTrue(state.modelOptions.isNotEmpty())
            assertFalse(state.toString().contains("access-token-9"))

            vm.removeProviderCredential("zai")
            vm.awaitState { !it.providerOptions.first { o -> o.id == "zai" }.configured }
            assertNull(h.credentials.creds["zai"])

            vm.closeForTest()
        }

    @Test
    fun accountLogin_eventAndPromptProgression_successClosesWithEpoch() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }
            var chosen: String? = null
            h.oauthZai.loginFn = { interaction ->
                interaction.notify(AuthEvent.Info("Choose an account"))
                chosen = interaction.prompt(
                    AuthInteractionPrompt.Select(
                        "Select account",
                        listOf(
                            AuthInteractionPrompt.Select.Option("personal", "Personal"),
                            AuthInteractionPrompt.Select.Option("work", "Work", "Company account")
                        )
                    )
                )
                interaction.notify(
                    AuthEvent.AuthUrl("https://auth.test/authorize", "Approve access")
                )
                interaction.notify(
                    AuthEvent.DeviceCode(
                        "ABCD-1234",
                        "https://verify.test/device",
                        intervalSeconds = 5
                    )
                )
                interaction.notify(AuthEvent.Progress("Waiting for approval"))
                val code = interaction.prompt(
                    AuthInteractionPrompt.ManualCode("Enter the code from the browser")
                )
                assertEquals("654321", code)
                OAuthCredential("access-token-1", "refresh-token-1", Long.MAX_VALUE)
            }

            vm.beginProviderAuthLogin("zai", oauthMethod)

            // The Select prompt projects ids/labels/descriptions — never values.
            val selectPending = vm.uiState
                .first { it.authFlow?.pendingPrompt?.kind == AuthPromptKind.SELECT }
                .authFlow!!.pendingPrompt!!
            assertEquals(listOf("personal", "work"), selectPending.options.map { it.id })
            assertEquals(listOf("Personal", "Work"), selectPending.options.map { it.label })
            assertEquals("Company account", selectPending.options[1].description)

            vm.submitAuthPrompt("work")
            assertEquals("work", chosen)

            vm.awaitState { it.authFlow?.pendingPrompt?.kind == AuthPromptKind.MANUAL_CODE }
            val events = vm.uiState.value.authFlow!!.events
            assertTrue(events[0] is AuthEvent.Info)
            assertEquals("https://auth.test/authorize", (events[1] as AuthEvent.AuthUrl).url)
            assertEquals("ABCD-1234", (events[2] as AuthEvent.DeviceCode).userCode)
            assertTrue(events[3] is AuthEvent.Progress)

            vm.submitAuthPrompt("654321")

            // Success: the flow clears, the epoch bumps exactly once (the UI
            // closes the auth screen on it), and no token material ever entered
            // the state.
            val done = vm.awaitState { it.authFlow == null && it.credentialSuccessEpoch == 1L }
            assertTrue(done.providerOptions.first { it.id == "zai" }.configured)
            assertEquals(AuthType.OAUTH, done.providerOptions.first { it.id == "zai" }.authType)
            assertTrue(done.modelOptions.any { it.providerId == "zai" })
            assertFalse(done.toString().contains("access-token-1"))
            assertNull(done.error)

            // The stored credential is the OAuth one (the store was empty before).
            assertEquals(CredentialType.OAUTH, h.credentials.creds["zai"]?.type)

            vm.closeForTest()
        }

    @Test
    fun accountLogin_failure_surfacesSafeError_andClearsFlow() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

            h.oauthZai.loginFn = { throw IllegalStateException("token exchange failed (400)") }
            vm.beginProviderAuthLogin("zai", oauthMethod)
            val failed = vm.awaitState { it.authFlow == null && it.error != null }
            assertEquals("Could not complete sign-in", failed.error)

            vm.closeForTest()
        }

    @Test
    fun apiKeyLogin_persistsCredential_andBumpsEpoch() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.awaitState { it.credentialSuccessEpoch > 0 }
        assertEquals("k", h.storedApiKey("zai"))

        vm.closeForTest()
    }

    @Test
    fun credentialReadFailure_degradesToNeedsConfiguration() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            // The restoration path must degrade to NeedsConfiguration rather
            // than crash: a failing credential read never blocks startup.
            h.settings.setProviderId("zai")
            h.settings.setModelId(testModel.id)
            h.credentials.failWrites = true
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            vm.closeForTest()
        }

    @Test
    fun accountLogin_cancelOrFailure_mutatesNothing_andFlowRestartsCleanly() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

            h.oauthZai.loginFn = { interaction ->
                interaction.prompt(AuthInteractionPrompt.Secret("Paste token"))
                OAuthCredential("never-stored", "never-stored", Long.MAX_VALUE)
            }
            vm.beginProviderAuthLogin("zai", oauthMethod)
            vm.awaitState { it.authFlow?.pendingPrompt?.kind == AuthPromptKind.SECRET }
            vm.cancelProviderAuthLogin()
            vm.awaitState { it.authFlow == null }
            assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
            assertNull(h.credentials.creds["zai"])
            assertNull(vm.uiState.value.error)

            h.oauthZai.loginFn =
                { throw IllegalStateException("token endpoint returned access-token-2") }
            vm.beginProviderAuthLogin("zai", oauthMethod)
            vm.awaitState { it.authFlow == null && it.error != null }
            assertEquals("Could not complete sign-in", vm.uiState.value.error)
            assertFalse(vm.uiState.value.toString().contains("access-token-2"))
            assertNull(h.credentials.creds["zai"])
            assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
            vm.dismissError()

            h.oauthZai.loginFn =
                { OAuthCredential("access-token-3", "refresh-token-3", Long.MAX_VALUE) }
            vm.beginProviderAuthLogin("zai", oauthMethod)
            vm.awaitState { it.authFlow == null && it.credentialSuccessEpoch == 1L }
            assertTrue(vm.uiState.value.providerOptions.first { o -> o.id == "zai" }.configured)
            assertFalse(vm.uiState.value.toString().contains("access-token-3"))

            vm.closeForTest()
        }

    @Test
    fun concurrentAuthFlows_areRejected() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

        val promptGate = CompletableDeferred<Unit>()
        h.oauthZai.loginFn = { interaction ->
            interaction.prompt(AuthInteractionPrompt.Text("Enter anything"))
                .also { promptGate.complete(Unit) }
            OAuthCredential("never-stored", "never-stored", Long.MAX_VALUE)
        }
        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.awaitState { it.authFlow?.pendingPrompt != null }

        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.awaitState { it.error != null }
        vm.dismissError()
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.awaitState { it.error != null }
        assertNull(h.credentials.creds["zai"])
        vm.dismissError()

        vm.cancelProviderAuthLogin()
        vm.awaitState { it.authFlow == null }
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.awaitState { it.credentialSuccessEpoch == 1L }
        assertEquals("k", (h.credentials.creds["zai"] as ApiKeyCredential).key)

        vm.closeForTest()
    }

    // ---- session search ----

    @Test
    fun sessionSearch_scansOnce_perQueryStretch_andFiltersResults() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
            val h = Harness()
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
        val h = Harness()
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

    // ---- tree navigation ----

    private suspend fun ChatViewModel.exchange(h: Harness, text: String, reply: String) {
        h.scriptedStreams.add(
            h.gatedStream(
                reply,
                CompletableDeferred<Unit>().apply {
                    complete(Unit)
                }
            )
        )
        onDraftChange(text)
        send()
        awaitState { !it.isStreaming && it.messages.size >= 2 }
    }

    @Test
    fun navigateToAssistantEntry_truncatesTranscript_andRoundtripPreservesBranches() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
            val h = Harness()
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
        val h = Harness()
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

    // ---- thinking level ----

    /**
     * A new session seeds the default thinking level ("medium" default),
     * clamped to the model; the chip surfaces fold onto the live session
     * state.
     */
    @Test
    fun newSession_seedsDefaultThinkingLevel_andProjectsTheChipSurfaces() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            val ready = vm.awaitState { it.status == ChatStatus.Ready }
            val sessionId = ready.activeSessionId!!

            // glm-4.7 is a reasoning model without a thinkingLevelMap: every
            // level off..high is supported (xhigh/max need explicit mappings).
            assertEquals(ModelThinkingLevel.MEDIUM, ready.thinkingLevel)
            assertEquals(
                listOf(
                    ModelThinkingLevel.OFF,
                    ModelThinkingLevel.MINIMAL,
                    ModelThinkingLevel.LOW,
                    ModelThinkingLevel.MEDIUM,
                    ModelThinkingLevel.HIGH
                ),
                ready.availableThinkingLevels
            )
            assertNull(ready.defaultThinkingLevel)

            waitUntil { h.sessions.managers[sessionId]!!.conversation.entries.size == 2 }
            val seeded = h.sessions.managers[sessionId]!!.conversation
            assertEquals(
                listOf("medium"),
                seeded.entries.filterIsInstance<ThinkingLevelEntry>()
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
            val h = Harness()
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
            val h = Harness()
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
            val h = Harness()
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
                ModelThinkingLevel.HIGH,
                defaulted.thinkingLevel
            )
            assertEquals(
                "the thinking chip projects the clamped session level",
                ModelThinkingLevel.HIGH,
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
            val h = Harness()
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
        val h = Harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.awaitState { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        // Flush the session first: without an assistant message there is no
        // file, and a reload could not restore anything.
        vm.exchange(h, "Hello", "world")
        vm.selectThinkingLevel(ModelThinkingLevel.HIGH)
        waitUntil { h.sessions.stored(sessionId)!!.entries.size == 5 }
        vm.closeForTest()

        val vm2 = h.newViewModel()
        val restored = vm2.awaitState {
            it.status == ChatStatus.Ready &&
                it.activeSessionId == sessionId
        }
        assertEquals(ModelThinkingLevel.HIGH, restored.thinkingLevel)
        assertEquals(
            "the branch entry survives reload; no re-seed over it",
            listOf("medium", "high"),
            h.sessions.stored(sessionId)!!.entries
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
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }

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
    fun defaultModel_mirrorFollowsStoredDefault_notLiveSwitches() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            val ready = vm.awaitState { it.status == ChatStatus.Ready }
            assertNull(ready.defaultModel)

            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }
            assertNull(vm.uiState.value.defaultModel)

            vm.saveStartupDefault("zai", "glm-5.3")
            vm.awaitState { it.defaultModel?.modelId == "glm-5.3" }
            assertEquals("glm-5.3", h.settings.currentSettings().modelId)

            vm.selectModel("zai", "glm-4.7")
            vm.awaitState { it.selectedModel?.modelId == "glm-4.7" }
            assertEquals("glm-5.3", vm.uiState.value.defaultModel?.modelId)

            vm.closeForTest()
        }

    /** Persists the ordered list; an emptied scope behaves as no scope downstream. */
    @Test
    fun toggleModelScope_persistsOrderedList_emptyBehavesAsNoScope() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val all = vm.uiState.value.modelOptions
            assertTrue(all.size >= 2)

            vm.toggleModelScope(all[0].providerId, all[0].modelId, false)
            val curated = vm.awaitState { it.enabledModels != null }.enabledModels!!
            assertEquals(all.drop(1).map { "${it.providerId}/${it.modelId}" }, curated)

            all.drop(1).forEach { vm.toggleModelScope(it.providerId, it.modelId, false) }
            val emptied = vm.awaitState { it.enabledModels?.isEmpty() == true }
            assertEquals(emptyList<String>(), h.settings.currentSettings().enabledModels)
            assertEquals(emptied.modelOptions, emptied.scopedModelOptions)

            vm.toggleModelScope(all[1].providerId, all[1].modelId, true)
            vm.awaitState {
                it.enabledModels == listOf("${all[1].providerId}/${all[1].modelId}")
            }

            vm.closeForTest()
        }

    @Test
    fun scopedModelOptions_followCredentialFiltering() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
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
            val h = Harness()
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
                h.sessions.managers[sessionId]!!.conversation.leafId == assistantEntryId
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
            val h = Harness()
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
                h.sessions.managers[fresh.activeSessionId!!]!!.conversation.entries.isNotEmpty()
            }
            val seed = h.sessions.managers[fresh.activeSessionId!!]!!.conversation
                .entries.filterIsInstance<ModelChangeEntry>().single()
            assertEquals("zai", seed.provider)
            assertEquals("glm-4.7", seed.modelId)

            vm2.closeForTest()
        }

    /** With a curated scope, a new chat starts on the first scoped model (pi's --models rule), ahead of the default. */
    @Test
    fun newSession_withScope_startsOnTheFirstScopedModel() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
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
            val h = Harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val firstId = vm.uiState.value.activeSessionId!!

            // First session records a transcript, then switches to glm-5.3
            // on-branch; the fold carries the switch across loads.
            vm.exchange(h, "Hello", "world")
            vm.awaitState {
                it.sessionSummaries.firstOrNull { s -> s.id == firstId }?.messageCount == 2
            }
            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.selectedModel?.modelId == "glm-5.3" }

            vm.newSession()
            vm.awaitState {
                it.activeSessionId != firstId &&
                    it.selectedModel?.modelId == "glm-4.7"
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
                    it.selectedModel?.modelId == "glm-5.3"
            }
            vm.switchSession(secondId)
            vm.awaitState {
                it.activeSessionId == secondId &&
                    it.selectedModel?.modelId == "glm-4.7"
            }

            vm.closeForTest()
        }

    @Test
    fun navigateToCurrentLeaf_orUnknownEntry_isRejectedSafely() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
        val h = Harness()
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
        val h = Harness()
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
            val h = Harness()
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
            val h = Harness()
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

    // ---- GitHub Copilot credential-based model filtering ----

    @Test
    fun copilotOAuthAvailableModelIds_narrowsModelOptions() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
            val vm = h.newViewModel()

            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(listOf("gpt-4.1"), vm.copilotModelOptions())
            assertTrue(state.providerOptions.first { it.id == "github-copilot" }.configured)

            vm.closeForTest()
        }

    @Test
    fun copilotOAuthMalformedAvailableModelIds_showsAllModels() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            // Mixed (string + number) array: not entirely strings, so the full
            // static list applies.
            h.credentials.creds["github-copilot"] = copilotCredential(
                JsonArray(listOf(JsonPrimitive("gpt-4.1"), JsonPrimitive(7)))
            )
            val vm = h.newViewModel()

            vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(listOf("claude-haiku-4.5", "gpt-4.1", "gpt-4.5"), vm.copilotModelOptions())

            vm.closeForTest()
        }

    @Test
    fun copilotOAuthEmptyAvailableModelIds_showsNoModelsButStaysConfigured() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray())
            val vm = h.newViewModel()

            val state = vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertEquals(emptyList<String>(), vm.copilotModelOptions())
            assertTrue(state.providerOptions.first { it.id == "github-copilot" }.configured)

            vm.closeForTest()
        }

    @Test
    fun copilotLogoutThenApiKeySwitch_showsAllModelsAgain() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.Ready }

            // Logout: no credential ⇒ unconfigured ⇒ no model options at all
            // (not even unfiltered ones).
            vm.removeProviderCredential("github-copilot")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.copilotModelOptions())
            assertFalse(
                vm.uiState.value.providerOptions.first {
                    it.id == "github-copilot"
                }.configured
            )

            // An API-key credential is complete and never filtered ⇒ every
            // static model returns.
            h.credentials.creds["github-copilot"] = ApiKeyCredential(key = "tok")
            vm.refreshProviderStatus()
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals(listOf("claude-haiku-4.5", "gpt-4.1", "gpt-4.5"), vm.copilotModelOptions())

            vm.closeForTest()
        }

    @Test
    fun persistedCopilotSelectionUnavailable_derivesAvailableModel_andSurfacesError() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
            h.settings.setProviderId("github-copilot")
            h.settings.setModelId("gpt-4.5")
            val vm = h.newViewModel()

            // The saved default is credential-filtered out: a safe
            // availability error surfaces, but the derived replacement runs —
            // chat is usable.
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals("gpt-4.1", state.selectedModel?.modelId)
            assertEquals(ChatNavKey, state.startKey)
            assertNotNull(state.error)
            assertEquals(listOf("gpt-4.1"), vm.copilotModelOptions())
            vm.dismissError()

            vm.selectModel("github-copilot", "gpt-4.5")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals("Unknown model", vm.uiState.value.error)
            vm.dismissError()

            vm.selectModel("github-copilot", "gpt-4.1")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertNull(vm.uiState.value.error)
            assertEquals("gpt-4.1", vm.uiState.value.selectedModel?.modelId)

            vm.closeForTest()
        }

    @Test
    fun unknownProviderAndStaticUnknownModel_rejectedAsUnknownModel() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
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
        val h = Harness()
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
    // ---- navigation-trigger branch summarization ----

    /**
     * VM wiring only — the summarization itself, its failure modes, and the
     * entry shape are AgentNavigationTest/BranchSummarizationTest's: the
     * summarize flag must reach the session from the tree panel intent.
     */
    @Test
    fun navigateWithSummarize_reachesTheSummarizer() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
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
