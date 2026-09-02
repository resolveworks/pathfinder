package works.resolve.pathfinder.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentSession
import works.resolve.pathfinder.ai.api.ChatApi
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.models.Provider
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.agent.NativeAgentFactory
import works.resolve.pathfinder.agent.catalogAuthResolver
import works.resolve.pathfinder.ai.models.ResolvedAuth
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentFactory
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolResultMessage
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
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.ModelChangeEntry
import works.resolve.pathfinder.data.sessions.SessionRepository
import works.resolve.pathfinder.data.sessions.SessionErrorCode
import works.resolve.pathfinder.data.sessions.SessionStore
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.junit.After
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

    /**
     * Live harnesses (pi's `harnesses[]` array in
     * packages/coding-agent/test/suite/harness.ts): every [Harness] registers
     * itself on construction, and [disposeHarnesses] tears them all down even
     * when a test failed mid-body — so a still-alive ViewModel scope can never
     * leak into a later test (the Dispatchers.Main /
     * UncaughtExceptionsBeforeTest cascade).
     */
    private val harnesses = CopyOnWriteArrayList<Harness>()

    /**
     * pi's `cleanup`/afterEach discipline: cancel AND join every ViewModel
     * scope the harnesses created, then dispose their store scopes. Passing
     * tests already joined deterministically via [closeForTest]; this rule is
     * the safety net for tests that failed before reaching it.
     */
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
        baseUrl = "https://example.invalid",
    )

    /** In-memory [CredentialStore] whose reads/writes can be made to fail. */
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
            update: suspend (Credential?) -> Credential?,
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

    /** Scriptable fake OAuth flow (never any real networking). */
    private class FakeOAuthAuth(
        override val name: String = "Z.AI Account",
        override val loginLabel: String = "Sign in with a Z.AI account",
        override val isSubscription: Boolean = true,
    ) : OAuthAuth {
        var loginFn: suspend (AuthInteraction) -> OAuthCredential = {
            OAuthCredential(access = "access-1", refresh = "refresh-1", expires = Long.MAX_VALUE)
        }
        override suspend fun login(interaction: AuthInteraction): OAuthCredential = loginFn(interaction)
        override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential
        override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
            ModelAuth(apiKey = credential.access)
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
        /** Every record appended through the ViewModel's lifecycle recorder. */
        val appendedRecords = ConcurrentLinkedQueue<works.resolve.pathfinder.data.sessions.LaneRecord>()
        /** When set, completed as each save is entered (before the gate). */
        var saveEntered: CompletableDeferred<Unit>? = null
        /** When set, every save suspends on it before delegating. */
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun appendRecord(sessionId: String, record: works.resolve.pathfinder.data.sessions.LaneRecord): works.resolve.pathfinder.data.sessions.LaneRecord {
            appendedRecords.add(record)
            return delegate.appendRecord(sessionId, record)
        }

        override suspend fun save(session: works.resolve.pathfinder.data.sessions.Session): works.resolve.pathfinder.data.sessions.Session {
            totalSaves += 1
            if (failSave) {
                failedSaves += 1
                throw works.resolve.pathfinder.data.sessions.SessionError(SessionErrorCode.STORAGE, "save failed")
            }
            saveEntered?.complete(Unit)
            saveGate?.await()
            return delegate.save(session)
        }
    }

    /**
     * Test harness wiring real repositories/stores and scripted real Agents.
     *
     * Follows pi's test architecture
     * (packages/coding-agent/test/model-runtime-test-utils.ts and
     * packages/coding-agent/test/suite/harness.ts): real implementations run
     * above the storage boundaries, and substitution happens ONLY there —
     * [FakeCredentialStore] mirrors pi's `AuthStorage.inMemory()`, the
     * DataStore/SessionStore live on real files in a tempdir
     * (`SessionManager.inMemory`-adjacent), and the Models stack + model
     * resolver are the real production code paths
     * ([CatalogProvider.toRuntimeProvider] + [catalogAuthResolver], real
     * [NativeAgentFactory.resolveModel]) over those in-memory stores, like
     * pi running a real ModelRuntime over `AuthStorage.inMemory`. The two
     * remaining seams — the scripted [factory] ([AgentFactory] standing in
     * for pi's `registerFauxProvider`) and [rejectedModelIds] — keep
     * signature fidelity and are the only behavior fakes.
     */
    private inner class Harness {
        init {
            harnesses += this
        }

        /** Every ViewModel this harness created; joined by [disposeHarnesses]. */
        val viewModels = CopyOnWriteArrayList<ChatViewModel>()

        val credentials = FakeCredentialStore()

        /** Records the ViewModel's diagnostics spans for boundary assertions. */
        val telemetry = works.resolve.pathfinder.telemetry.InMemoryTelemetryContext()
        val diagnostics = works.resolve.pathfinder.logging.PathfinderDiagnostics(telemetry)

        /** Registered OAuth flows: zai (also has API-key prompts → both methods) and the promptless oauth-only provider. */
        val oauthZai = FakeOAuthAuth()
        val oauthOnly = FakeOAuthAuth(
            name = "OAuth Only Account",
            loginLabel = "Sign in with an account",
        )
        val oauthCopilot = FakeOAuthAuth(
            name = "GitHub Copilot",
            loginLabel = "Sign in with GitHub",
            isSubscription = true,
        )
        /**
         * Shared auth registry, wired through BOTH [ProviderAuthService] and
         * the runtime [catalogAuthResolver] exactly like PathfinderApplication
         * shares `authRegistry` between them.
         */
        val authRegistry = MapCatalogAuthRegistry(
            mapOf("zai" to oauthZai, "oauth-only" to oauthOnly, "github-copilot" to oauthCopilot),
        )
        val authService = ProviderAuthService(
            catalog = works.resolve.pathfinder.ai.testing.TestCatalogs.CATALOG,
            registry = authRegistry,
            credentials = credentials,
        )
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

        /**
         * Fake summarization stack for compaction tests: a faux provider
         * serving [summaryResponses] through [Models.completeSimple]
         * (CompactionLlmTest pattern), optionally gated mid-summary.
         */
        var compactionModels: Models? = null
        val summaryResponses = ConcurrentLinkedQueue<AssistantMessage>()
        var summaryGate: CompletableDeferred<Unit>? = null

        fun installCompactionModels() {
            val api = object : ChatApi {
                override fun streamSimple(model: Model, context: Context, options: SimpleStreamOptions) = flow {
                    summaryGate?.await()
                    val response = summaryResponses.poll() ?: error("No summary response queued")
                    if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
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
                        apis = mapOf(testModel.api to api),
                    ),
                ),
            )
        }

        /** When set, agents are built with auto-compaction disabled (isolates branch summarization). */
        var disableCompaction = false

        /** Model ids rejected by the fake resolver (validation-error path). */
        val rejectedModelIds = mutableSetOf<String>()

        /** When set, the factory rejects every configuration. */
        var rejectAll = false
        val createdAgents = mutableListOf<AgentSession>()

        /** The settings each created agent was built from (fold-seed assertions). */
        val createdSettings = mutableListOf<ModelSettings>()

        /** The request model of every scripted stream invocation (live-switch assertions). */
        val streamedModels = CopyOnWriteArrayList<Model>()

        /**
         * Network-boundary seam (pi's harnesses never touch the network): the
         * real provider APIs in [switchModels] are constructed over this
         * transport, which must never be reached — agent streams are scripted
         * at the [factory] seam, so only [Models.checkAuth] resolution runs
         * against the stack. This is a boundary fake, not a behavior fake.
         */
        val transport = object : HttpStreamingTransport {
            override suspend fun post(request: TransportRequest): TransportResponse =
                error("network transport reached: scripted streams must bypass it")
        }

        /**
         * The live-switch model stack, built through the REAL production path
         * (pi's harness runs a real ModelRuntime over `AuthStorage.inMemory`):
         * every catalog provider via [CatalogProvider.toRuntimeProvider] with
         * the factory's [catalogAuthResolver] over the in-memory credential
         * store and shared auth registry — so setModel's checkAuth resolves
         * stored credentials (completeness, OAuth flows, everything) exactly
         * like production.
         */
        val switchModels = Models(
            works.resolve.pathfinder.ai.testing.TestCatalogs.CATALOG.providers.map { entry ->
                entry.toRuntimeProvider(
                    transport = transport,
                    authResolver = catalogAuthResolver(entry, credentials, NoopAuthContext, authRegistry),
                )
            },
        )

        /**
         * The production resolver seam: a REAL [NativeAgentFactory] over the
         * test catalog, in-memory credential store, and shared auth registry —
         * [modelResolver] is `nativeFactory::resolveModel` composed with the
         * [rejectedModelIds] injection, exactly the function
         * PathfinderApplication passes as ChatViewModel's `modelResolver`.
         */
        private val nativeFactory = NativeAgentFactory(
            credentials = credentials,
            catalog = works.resolve.pathfinder.ai.testing.TestCatalogs.CATALOG,
            transport = transport,
            authRegistry = authRegistry,
        )

        val modelResolver: (String, String) -> Model = { providerId, modelId ->
            if (modelId in rejectedModelIds) {
                throw IllegalArgumentException("model rejected (harness-injected validation failure)")
            }
            nativeFactory.resolveModel(providerId, modelId)
        }

        val factory = AgentFactory { settings, _, conversation ->
            check(!rejectAll) { "factory unavailable" }
            require(settings.modelId !in rejectedModelIds) { "model rejected" }
            createdSettings += settings
            AgentSession(
                // pi's harness registers its faux models in the ModelRuntime
                // and every consumer — resolver, session, setModel — resolves
                // the same objects; here the test catalog plays that role,
                // so the agent's model resolves through the same production
                // seam as modelResolver. Never a parallel hand-written
                // Model: capabilities (reasoning, thinkingLevelMap) are
                // behavior, and a duplicate shape diverges silently.
                agent = Agent(
                    model = nativeFactory.resolveModel(settings.providerId, settings.modelId),
                    streamFn = StreamFn { requestedModel, _, _ ->
                        streamedModels.add(requestedModel)
                        scriptedStreams.poll() ?: flow { kotlinx.coroutines.awaitCancellation() }
                    },
                ),
                conversation = conversation,
                retrySettings = settings.retry,
                compactionSettings = if (disableCompaction) settings.compaction.copy(enabled = false) else settings.compaction,
                models = compactionModels ?: switchModels,
            ).also { session -> createdAgents += session }
        }

        fun newViewModel(): ChatViewModel = ChatViewModel(
            settingsRepository = settingsStore,
            catalog = works.resolve.pathfinder.ai.testing.TestCatalogs.CATALOG,
            authService = authService,
            sessionStore = sessions,
            agentFactory = factory,
            modelResolver = modelResolver,
            diagnostics = diagnostics,
        ).also { viewModels += it }

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

        /** The stored API-key credential's key for [providerId], cast-safe. */
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

    /** Cancels the ViewModel scope and waits until all in-flight work has settled. */
    private suspend fun ChatViewModel.closeForTest() {
        val job = viewModelScope.coroutineContext[Job]!!
        job.cancel()
        job.join()
    }

    /**
     * Configures zai: an optional credential save (which alone completes
     * configuration through the derived initial model), then an optional
     * live model switch away from the derived glm-4.7 default.
     */
    private fun ChatViewModel.configure(
        modelId: String = "glm-4.7",
        apiKey: String = "",
    ) {
        if (apiKey.isNotEmpty()) saveProviderCredential("zai", apiKey, emptyMap())
        if (modelId != "glm-4.7") selectModel("zai", modelId)
    }

    /** Stored Copilot OAuth credential with the given availableModelIds extra. */
    private fun copilotCredential(availableModelIds: JsonElement? = null): OAuthCredential =
        OAuthCredential(
            access = "copilot-access",
            refresh = "copilot-refresh",
            expires = Long.MAX_VALUE,
            extras = availableModelIds?.let { mapOf("availableModelIds" to it) } ?: emptyMap(),
        )

    private fun stringArray(vararg ids: String): JsonArray = JsonArray(ids.map { JsonPrimitive(it) })

    /** Primitive JSON payload value as a string (record intent assertions). */
    private val JsonElement.jsonPrimitiveContent: String get() = (this as JsonPrimitive).content

    private fun ChatViewModel.copilotModelOptions(): List<String> =
        uiState.value.modelOptions.filter { it.providerId == "github-copilot" }.map { it.modelId }

    // ---- tests ----

    @Test
    fun unconfiguredInit_showsNeedsConfiguration_andKeepsKeyPrivate() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()

        val state = vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        assertEquals(ProvidersNavKey, state.startKey)
        assertFalse(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertNull(state.activeSessionId)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.modelOptions.isEmpty())

        // Configure a stored key but no model settings: the initial model is
        // derived (pi's findInitialModel: first available of a configured
        // provider) and the app enters the chat directly — and the key never
        // appears anywhere in the UI state.
        h.credentials.creds["zai"] = ApiKeyCredential("SECRET-KEY-123")
        val vm2 = h.newViewModel()
        val state2 = vm2.uiState.first { it.status == ChatStatus.Ready }
        assertTrue(state2.providerOptions.first { o -> o.id == "zai" }.configured)
        assertEquals("glm-4.7", state2.selectedModel?.modelId)
        assertNotNull(state2.activeSessionId)
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

        // While unconfigured the reset signal pins the forced first-run root:
        // the UI back stack is rebuilt to exactly [ProvidersNavKey] and back
        // is a no-op.
        assertEquals(ProvidersNavKey, vm.uiState.value.startKey)

        // A credential save completes configuration through the derived
        // initial model: the epoch bumps and the app enters the chat root.
        vm.saveProviderCredential("zai", "k", emptyMap())
        val configured = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(ChatNavKey, configured.startKey)
        assertEquals("glm-4.7", configured.selectedModel?.modelId)
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

        // A live model switch is NOT navigation: same agent, same session,
        // no epoch bump or stack reset — only the live model changes.
        val epochBefore = vm.uiState.value.navigationEpoch
        vm.selectModel("zai", "glm-5.3")
        val switched2 = vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        assertEquals(epochBefore, switched2.navigationEpoch)
        assertEquals(ChatNavKey, switched2.startKey)

        // Status changes stay atomic with the signal: every Ready observation
        // pairs with the chat root, every NeedsConfiguration one with the
        // providers root.
        vm.uiState.value.let {
            assertTrue(
                it.status != ChatStatus.NeedsConfiguration ||
                    it.startKey == ProvidersNavKey,
            )
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

        // The derived initial model is NOT persisted as the startup default
        // (pi's findInitialModel picks; only Ctrl+S persists) — but the
        // active session id is.
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
        assertEquals("Hello", mid.messages[0].singleText())
        assertFalse(mid.canSend)
        assertEquals("", mid.draft)

        gate.complete(Unit)

        // Done: user + assistant committed, no duplicates, not streaming.
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        val done = vm.uiState.value
        assertNull(done.streamingMessage)
        assertEquals(ChatRole.Assistant, done.messages[1].role)
        assertEquals("world", done.messages[1].singleText())
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
        val assistant = session.messages[1] as works.resolve.pathfinder.ai.core.AssistantMessage
        assertEquals(StopReason.ABORTED, assistant.stopReason)

        vm.closeForTest()
    }

    @Test
    fun autoCompaction_showsStatus_persistsEntry_andProjectsMarker() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        h.installCompactionModels()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        // Threshold trigger: usage beyond contextWindow - reserveTokens.
        val bigUsage = works.resolve.pathfinder.ai.core.Usage(input = 190_000, output = 10, totalTokens = 190_010)
        h.scriptedStreams.add(
            flowOf(
                AssistantMessageEvent.Start(h.assistant("")),
                AssistantMessageEvent.Done(StopReason.STOP, h.assistant("long").copy(usage = bigUsage)),
            )
        )
        h.summaryResponses.add(h.assistant("SUMMARY"))
        val gate = CompletableDeferred<Unit>()
        h.summaryGate = gate
        vm.onDraftChange("Hello")
        vm.send()

        // Mid-compaction: the transient status is visible while the turn's
        // final message is already committed.
        vm.uiState.first { it.isCompacting }
        gate.complete(Unit)

        vm.uiState.first { !it.isCompacting && !it.isStreaming }
        val state = vm.uiState.value
        assertFalse(state.isCompacting)
        assertNull(state.retryStatus)
        // The compaction cut projects as a divider marker in the transcript.
        assertTrue(state.messages.any { it.isCompactionMarker })

        // The compaction entry is persisted with the tree and survives adoption.
        val sessionId = state.activeSessionId!!
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        vm.closeForTest()
        val vm2 = h.newViewModel()
        val restored = vm2.uiState.first { it.status == ChatStatus.Ready }
        assertTrue(restored.messages.any { it.isCompactionMarker })
        val loaded = h.sessionStore.load(sessionId)!!
        assertTrue(loaded.entries.any { it is works.resolve.pathfinder.data.sessions.CompactionEntry })
        vm2.closeForTest()
    }

    @Test
    fun autoRetry_removesErrorFromAgentTranscript_butKeepsItInSession() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            val vm = h.newViewModel()
            vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.uiState.first { it.status == ChatStatus.Ready }

            // Transient error, then a successful retry (pi's auto-retry).
            h.scriptedStreams.add(h.errorStream(h.assistant("", StopReason.ERROR, "terminated")))
            h.scriptedStreams.add(h.gatedStream("recovered", CompletableDeferred<Unit>().apply { complete(Unit) }))
            vm.onDraftChange("Hello")
            vm.send()

            vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
            val state = vm.uiState.value
            // Agent transcript dropped the error message; the retry's answer
            // replaced it in the chat surface, and no retry status remains.
            assertNull(state.retryStatus)
            assertEquals(ChatRole.Assistant, state.messages[1].role)
            assertNull(state.messages[1].error)

            val sessionId = state.activeSessionId!!
            vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 3 }

            // The session tree keeps the error message in history (pi keeps
            // it in the session while removing it from agent state).
            val session = h.sessionStore.load(sessionId)!!
            assertEquals(3, session.messages.size)
            val failed = session.messages[1] as works.resolve.pathfinder.ai.core.AssistantMessage
            assertEquals(StopReason.ERROR, failed.stopReason)
            assertEquals("terminated", failed.errorMessage)
            val recovered = session.messages[2] as works.resolve.pathfinder.ai.core.AssistantMessage
            assertEquals(StopReason.STOP, recovered.stopReason)

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
        assertEquals("Hello", restored.messages[0].singleText())

        vm.closeForTest()
    }

    @Test
    fun toolResultMessages_neverRenderAsChatMessages() = runTest(mainDispatcherRule.scheduler) {
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

        // Regression for the generic streamingMessage: a tool-result
        // message committed through the agent must not render as a blank
        // chat message (projectCommitted's else -> null).
        val session = h.createdAgents.single()
        val result = ToolResultMessage(
            toolCallId = "call-1",
            toolName = "get_weather",
            content = listOf(TextContent("sunny")),
            timestamp = System.nanoTime(),
        )
        session.agent.processEvent(AgentEvent.MessageStart(result))
        session.agent.processEvent(AgentEvent.MessageEnd(result))

        vm.uiState.first { it.messages.size == 2 && it.streamingMessage == null }
        val state = vm.uiState.value
        assertEquals(2, state.messages.size)
        assertNull(state.streamingMessage)

        vm.closeForTest()
    }

    @Test
    fun credentialSave_success_bumpsSuccessEpoch_failedOrIncompleteDoesNot() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // Incomplete save (blank key, nothing stored): error, no epoch bump.
        vm.saveProviderCredential("zai", "   ", emptyMap())
        vm.uiState.first { it.error != null }
        assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
        vm.dismissError()

        // Storage failure: error, no epoch bump, form inputs conceptually kept.
        h.credentials.failWrites = true
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.uiState.first { it.error != null }
        assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
        assertNull(h.credentials.creds["zai"])
        vm.dismissError()
        h.credentials.failWrites = false

        // Confirmed persistence bumps exactly once per successful save.
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.uiState.first { it.credentialSuccessEpoch == 1L }
        assertEquals("k", h.storedApiKey("zai"))

        // A second successful save bumps again (monotonic).
        vm.saveProviderCredential("zai", "k2", emptyMap())
        vm.uiState.first { it.credentialSuccessEpoch == 2L }
        assertEquals("k2", h.storedApiKey("zai"))

        vm.closeForTest()
    }

    @Test
    fun invalidModel_andResolverValidation_areRejectedSafely() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // Without a bound session there is nothing to switch: safe error.
        vm.selectModel("zai", "glm-4.7")
        vm.uiState.first { it.error != null }
        assertEquals(ChatStatus.NeedsConfiguration, vm.uiState.value.status)
        assertEquals(0, h.countSessions())
        vm.dismissError()

        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val agentsBefore = h.createdAgents.size

        // An id the catalog has never carried is rejected by the resolver.
        vm.selectModel("zai", "not-a-model")
        vm.uiState.first { it.error != null }
        assertEquals("Unknown model", vm.uiState.value.error)
        assertEquals(agentsBefore, h.createdAgents.size)
        assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
        vm.dismissError()

        // A resolver-rejected model (the factory-validation seam) keeps the
        // bound agent and its model.
        h.rejectedModelIds += "glm-5.3"
        vm.selectModel("zai", "glm-5.3")
        vm.uiState.first { it.error != null }
        assertEquals(agentsBefore, h.createdAgents.size)
        assertTrue(vm.uiState.value.status == ChatStatus.Ready)
        assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
        // No startup default was ever persisted.
        assertEquals("", h.settings.currentSettings().modelId)

        vm.closeForTest()
    }

    @Test
    fun blankKeySave_isRejected_andCompleteSaveReplacesStoredKey() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "first-key")
        vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals("first-key", h.storedApiKey("zai"))

        // Pi's logins replace wholesale, so a blank key input no longer keeps
        // the stored key: it is a missing required value. The save is rejected
        // with an error naming the missing prompt — never its value — and the
        // stored credential and status are left untouched.
        vm.saveProviderCredential("zai", "   ", emptyMap())
        vm.uiState.first { it.error != null }
        val state = vm.uiState.value
        val error = checkNotNull(state.error)
        assertTrue(error.contains("API key"))
        assertFalse(error.contains("first-key"))
        assertFalse(state.toString().contains("first-key"))
        assertEquals(ChatStatus.Ready, state.status)
        assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertEquals("first-key", h.storedApiKey("zai"))
        vm.dismissError()

        // A complete re-save replaces the stored key wholesale (replace, not
        // merge) and the app keeps working.
        vm.saveProviderCredential("zai", "second-key", emptyMap())
        vm.uiState.first { it.credentialSuccessEpoch == 2L }
        assertEquals("second-key", h.storedApiKey("zai"))
        assertEquals(1, h.createdAgents.size)

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

        // A live model switch is NOT busy-rejected (pi: a mid-stream pick
        // applies to the next prompt): no error, and the model_change lands.
        vm.selectModel("zai", "glm-5.3")
        mainDispatcherRule.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals("glm-5.3", vm.uiState.value.selectedModel?.modelId)
        assertTrue(vm.uiState.value.isStreaming)

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
    fun initFactoryFailure_isFailed_neverReady_andRejectedConfigNotPersisted() = runTest(mainDispatcherRule.scheduler) {
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

        // Restart after a factory-rejected model: the invalid selection was
        // never persisted, so the previous valid configuration still works.
        val h2 = Harness()
        val vm2 = h2.newViewModel()
        vm2.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm2.configure(apiKey = "k")
        vm2.uiState.first { it.status == ChatStatus.Ready }
        h2.rejectedModelIds += "glm-5.3"
        vm2.selectModel("zai", "glm-5.3")
        vm2.uiState.first { it.error != null }
        assertEquals("glm-4.7", vm2.uiState.value.selectedModel?.modelId)
        vm2.closeForTest()

        val vm3 = h2.newViewModel()
        val state3 = vm3.uiState.first { it.status == ChatStatus.Ready }
        assertNull(state3.error)
        assertEquals("glm-4.7", state3.selectedModel?.modelId)
        vm3.closeForTest()
    }

    @Test
    fun storedCredential_survivesFailedReSave_completeRetryReplaces() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // The key is stored, but the resolver rejects glm-5.3 afterwards.
        h.rejectedModelIds += "glm-5.3"
        vm.saveProviderCredential("zai", "first-key", emptyMap())
        // The derived initial model (glm-4.7) is unaffected by the rejection.
        vm.uiState.first { it.status == ChatStatus.Ready }
        val state = vm.uiState.value
        assertEquals("first-key", h.storedApiKey("zai"))
        assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertFalse(state.toString().contains("first-key"))

        // A live switch to the rejected model fails safely and mutates nothing.
        vm.selectModel("zai", "glm-5.3")
        vm.uiState.first { it.error != null }
        assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
        vm.dismissError()

        // An incomplete re-save (blank key: pi logins re-prompt everything,
        // nothing is merged) is rejected; the stored credential survives and
        // the key never leaks into state.
        vm.saveProviderCredential("zai", "  ", emptyMap())
        vm.uiState.first { it.error != null }
        assertFalse(checkNotNull(vm.uiState.value.error).contains("first-key"))
        assertEquals("first-key", h.storedApiKey("zai"))
        assertFalse(vm.uiState.value.toString().contains("first-key"))
        vm.dismissError()

        // A complete re-save (everything re-entered) keeps the app working.
        vm.saveProviderCredential("zai", "second-key", emptyMap())
        vm.uiState.first { it.credentialSuccessEpoch == 2L }
        assertEquals("second-key", h.storedApiKey("zai"))
        assertEquals(ChatStatus.Ready, vm.uiState.value.status)

        vm.closeForTest()
    }

    @Test
    fun sameTimestampMessages_getDistinctKeys() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // A persisted session whose user and assistant messages share a timestamp.
        val session = h.sessionStore.create("Collide")
        val saved = h.sessionStore.save(
            session.withMessages(listOf(
                works.resolve.pathfinder.ai.core.UserMessage.ofText("Hello", 123L),
                h.assistant("World").copy(timestamp = 123L),
            )),
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
    fun settingsWriteFailure_liveSwitchUnaffected_startupDefaultSurfacesError() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        h.settingsStore.failWrites = true

        // A live model switch touches no settings write: it succeeds even
        // while settings writes fail.
        vm.selectModel("zai", "glm-5.3")
        vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        assertNull(vm.uiState.value.error)

        // The startup default save is the explicit persistence action: its
        // failure surfaces a safe error and persists nothing.
        vm.saveStartupDefault("zai", "glm-5.3")
        vm.uiState.first { it.error != null }
        val state = vm.uiState.value
        assertEquals(ChatStatus.Ready, state.status)
        assertEquals("", h.settings.currentSettings().modelId)
        vm.dismissError()

        // The still-bound agent keeps chatting and persisting transcripts.
        h.settingsStore.failWrites = false
        h.scriptedStreams.add(h.gatedStream("world", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }
        val sessionId = vm.uiState.value.activeSessionId!!
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        assertEquals(2, h.sessionStore.load(sessionId)!!.messages.size)

        // Recovery: a retry with a working store persists the default.
        vm.saveStartupDefault("zai", "glm-5.3")
        vm.uiState.first { h.settings.currentSettings().modelId == "glm-5.3" }
        assertNull(vm.uiState.value.error)

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
            other.withMessages(listOf(
                works.resolve.pathfinder.ai.core.UserMessage.ofText("Old", 1L),
                h.assistant("Stock").copy(timestamp = 2L),
            )),
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
        // Let the initial seed model_change save settle before gating, so the
        // gated save is deterministically the user-message snapshot.
        vm.uiState.first { h.sessionStore.load(sessionId)!!.entries.isNotEmpty() }

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
        // Three saves happened: the seed model_change snapshot settled at
        // configuration, the gated user-message snapshot drained, and the
        // coalesced final snapshot was then dequeued and written — proving the
        // loop drains accepted pendings rather than skipping to the last one.
        assertEquals(3, h.sessions.totalSaves)
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
        assertEquals(
            listOf("Cloudflare AI Gateway", "GitHub Copilot", "OAuth Only", "OpenAI", "Z.AI"),
            state.providerOptions.map { it.name },
        )
        assertTrue(state.providerOptions.none { it.configured })
        assertTrue(state.modelOptions.isEmpty())

        vm.saveProviderCredential("zai", "SECRET-KEY-777", emptyMap())
        val after = vm.uiState.first { it.status == ChatStatus.Ready }
        assertTrue(after.providerOptions.first { it.id == "cloudflare-ai-gateway" }.let { !it.configured })
        assertTrue(after.modelOptions.isNotEmpty())
        assertTrue(after.modelOptions.all { it.providerId == "zai" })
        assertEquals("GLM-4.7", after.modelOptions.first { it.modelId == "glm-4.7" }.name)
        // The derived initial model runs; with no scope curated the picker's
        // scoped view is everything offered.
        assertEquals("glm-4.7", after.selectedModel?.modelId)
        assertEquals(after.modelOptions, after.scopedModelOptions)
        assertNull(after.enabledModels)
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
    fun saveProviderCredential_replacesCredentialWholesale() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // A key-only save is incomplete for Cloudflare (account/gateway ids
        // are required too): rejected, nothing persisted, safe error naming
        // the missing prompts — never the submitted values.
        vm.saveProviderCredential("cloudflare-ai-gateway", "cf-key", emptyMap())
        val state = vm.uiState.first { it.error != null }
        val error = checkNotNull(state.error)
        assertTrue(error.contains("account ID"))
        assertTrue(error.contains("gateway ID"))
        assertFalse(error.contains("cf-key"))
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
        val filled = h.credentials.creds["cloudflare-ai-gateway"] as ApiKeyCredential
        assertEquals("cf-key", filled.key)
        assertEquals(mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"), filled.env)

        // A complete re-save with different values fully replaces key and
        // env (pi logins never merge: no stale old values survive).
        vm.saveProviderCredential(
            "cloudflare-ai-gateway",
            "cf-key-2",
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-2", "CLOUDFLARE_GATEWAY_ID" to "gw-2"),
        )
        vm.uiState.first { it.credentialSuccessEpoch == 2L }
        val rotated = h.credentials.creds["cloudflare-ai-gateway"] as ApiKeyCredential
        assertEquals("cf-key-2", rotated.key)
        assertEquals(
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-2", "CLOUDFLARE_GATEWAY_ID" to "gw-2"),
            rotated.env,
        )

        // An incomplete re-save (missing env: replace semantics, nothing is
        // merged from the stored credential) is rejected; the old credential
        // is untouched and the error never echoes values.
        vm.saveProviderCredential("cloudflare-ai-gateway", "cf-key-3", mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-3"))
        vm.uiState.first { it.error != null }
        val retryError = checkNotNull(vm.uiState.value.error)
        assertTrue(retryError.contains("gateway ID"))
        assertFalse(retryError.contains("cf-key"))
        assertFalse(retryError.contains("acc-3"))
        assertEquals(rotated, h.credentials.creds["cloudflare-ai-gateway"])
        vm.dismissError()

        // A save with a blank key is rejected (the key is always required).
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
    fun selectModel_rejectsUnauthenticatedProvider_safely() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!
        val entriesBefore = h.createdAgents.single().conversation.entries.size

        // A key-only credential is incomplete for Cloudflare (account/gateway
        // ids required): the provider never counts as configured, and
        // pi's checkAuth ("No API key for provider/id") rejects the live
        // switch with a safe error — nothing appended, model unchanged.
        h.credentials.creds["cloudflare-ai-gateway"] = ApiKeyCredential("cf", emptyMap())
        vm.refreshProviderStatus()
        assertFalse(vm.uiState.value.providerOptions.first { o -> o.id == "cloudflare-ai-gateway" }.configured)
        assertTrue(vm.uiState.value.modelOptions.none { it.providerId == "cloudflare-ai-gateway" })

        vm.selectModel("cloudflare-ai-gateway", "workers-ai/test-model")
        vm.uiState.first { it.error != null }
        assertEquals(ChatStatus.Ready, vm.uiState.value.status)
        assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
        assertEquals(entriesBefore, h.createdAgents.single().conversation.entries.size)
        assertEquals(1, h.countSessions())

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
        assertTrue(state.navigationEpoch >= 1L)
        assertNotNull(state.activeSessionId)
        assertEquals("zai", state.selectedModel?.providerId)
        assertTrue(state.modelOptions.all { it.providerId == "zai" })
        // The saved default was already usable: it became the running model.
        assertEquals("glm-4.7", state.selectedModel?.modelId)

        vm.closeForTest()
    }

    @Test
    fun saveProviderCredential_withoutModelSettings_derivesInitialModel_andGoesReady() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        assertEquals(ProvidersNavKey, vm.uiState.value.startKey)

        // A credential save without model settings derives the initial model
        // (first available of a configured provider) and enters the chat.
        vm.saveProviderCredential("zai", "k", emptyMap())
        val ready = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(ChatNavKey, ready.startKey)
        assertTrue(ready.navigationEpoch >= 1L)
        assertNotNull(ready.activeSessionId)
        assertEquals(1, h.countSessions())
        assertEquals("zai", ready.selectedModel?.providerId)
        assertEquals("glm-4.7", ready.selectedModel?.modelId)
        assertTrue(ready.modelOptions.all { it.providerId == "zai" })

        // A second credential save (another provider) keeps the app Ready and
        // widens the offered models.
        vm.saveProviderCredential(
            "cloudflare-ai-gateway",
            "cf",
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
        )
        val both = vm.uiState.first { it.modelOptions.any { o -> o.providerId == "cloudflare-ai-gateway" } }
        assertEquals(ChatStatus.Ready, both.status)
        assertEquals(ChatNavKey, both.startKey)

        vm.closeForTest()
    }

    @Test
    fun unconfiguredInit_withStoredCredential_entersChatWithDerivedModel() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Restoration case: a complete stored credential but no model
        // settings — initialization derives the first available model and
        // enters the chat directly (pi's findInitialModel reduced).
        h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

        val vm = h.newViewModel()
        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(ChatNavKey, state.startKey)
        assertTrue(state.modelOptions.isNotEmpty())
        assertTrue(state.modelOptions.all { it.providerId == "zai" })
        assertTrue(state.providerOptions.first { it.id == "zai" }.configured)
        assertFalse(state.toString().contains("stored-key"))
        assertNotNull(state.activeSessionId)
        assertEquals("glm-4.7", state.selectedModel?.modelId)
        // The derivation seeded the new session with a model_change.
        vm.uiState.first { h.sessionStore.load(state.activeSessionId!!)!!.entries.isNotEmpty() }
        val seeded = h.sessionStore.load(state.activeSessionId!!)!!
        val change = seeded.entries.filterIsInstance<ModelChangeEntry>().single()
        assertEquals("zai", change.provider)
        assertEquals("glm-4.7", change.modelId)

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
        val state = vm.uiState.first { !it.providerOptions.first { o -> o.id == "zai" }.configured }
        // Status stays Ready and the agent is untouched: credentials are read
        // per request, sessions are never torn down.
        assertEquals(ChatStatus.Ready, state.status)
        assertEquals(agentsBefore, h.createdAgents.size)
        assertNotNull(state.activeSessionId)
        assertFalse(state.providerOptions.first { o -> o.id == "zai" }.configured)
        assertTrue(state.modelOptions.isEmpty())
        // The live session model stays visible for the model chip.
        assertEquals("glm-4.7", state.selectedModel?.modelId)
        assertNull(h.credentials.creds["zai"])

        // Sending still works through the still-bound agent.
        h.scriptedStreams.add(h.gatedStream("world", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }

        // Re-login restores configured status.
        vm.saveProviderCredential("zai", "k2", emptyMap())
        vm.uiState.first { it.providerOptions.first { o -> o.id == "zai" }.configured }

        vm.closeForTest()
    }

    @Test
    fun unknownProviderSettings_deriveAvailableModel_andRejectUnknownPicks() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        h.settings.setProviderId("not-a-provider")
        h.settings.setModelId("glm-4.7")
        h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

        val vm = h.newViewModel()
        // The unusable saved default is replaced by the first available model
        // of a configured provider; the app enters the chat directly.
        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals("glm-4.7", state.selectedModel?.modelId)
        assertTrue(state.modelOptions.all { it.providerId == "zai" })
        assertTrue(state.modelOptions.isNotEmpty())

        // Picking for an unknown provider is rejected safely.
        vm.selectModel("not-a-provider", "glm-4.7")
        vm.uiState.first { it.error != null }
        assertEquals(ChatStatus.Ready, vm.uiState.value.status)
        assertEquals("Unknown model", vm.uiState.value.error)

        vm.closeForTest()
    }

    // ---- provider auth methods & interactive account login ----

    @Test
    fun authMethods_apiKeyOnly_bothMethods_oauthOnly_andScreenModes() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        // Sole API-key method (Cloudflare): catalog label, not a subscription.
        val cloudflare = vm.providerAuthMethods("cloudflare-ai-gateway")
        assertEquals(listOf(AuthType.API_KEY), cloudflare.map { it.type })
        assertEquals("Cloudflare API key", cloudflare.single().label)
        assertFalse(cloudflare.single().isSubscription)

        // Both methods (zai): the API key first, then the account login.
        val zai = vm.providerAuthMethods("zai")
        assertEquals(listOf(AuthType.API_KEY, AuthType.OAUTH), zai.map { it.type })
        assertEquals("Z.AI API key", zai[0].label)
        assertFalse(zai[0].isSubscription)
        assertEquals("Sign in with a Z.AI account", zai[1].label)
        assertTrue(zai[1].isSubscription)

        // Sole OAuth method (promptless provider: pi's openai-codex shape).
        val only = vm.providerAuthMethods("oauth-only")
        assertEquals(listOf(AuthType.OAUTH), only.map { it.type })
        assertTrue(only.single().isSubscription)

        // Screen-mode routing (pi's startProviderLogin):
        // sole API-key → form, more than one → method choice, sole OAuth → flow.
        assertEquals(ProviderAuthScreenMode.API_KEY_FORM, providerAuthScreenMode(cloudflare))
        assertEquals(ProviderAuthScreenMode.METHOD_CHOICE, providerAuthScreenMode(zai))
        assertEquals(ProviderAuthScreenMode.START_OAUTH, providerAuthScreenMode(only))
        assertEquals(ProviderAuthScreenMode.NO_METHODS, providerAuthScreenMode(emptyList()))

        // Unknown providers list no methods.
        assertTrue(vm.providerAuthMethods("no-such-provider").isEmpty())

        vm.closeForTest()
    }

    @Test
    fun projectAuthPrompt_mapsKinds_metadataOnly() {
        // Prompt metadata crosses the boundary; answers never do.
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.TEXT, "message", "placeholder"),
            projectAuthPrompt(AuthInteractionPrompt.Text("message", "placeholder")),
        )
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.SECRET, "paste token"),
            projectAuthPrompt(AuthInteractionPrompt.Secret("paste token")),
        )
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.MANUAL_CODE, "enter code"),
            projectAuthPrompt(AuthInteractionPrompt.ManualCode("enter code")),
        )
        val select = projectAuthPrompt(
            AuthInteractionPrompt.Select(
                "choose",
                listOf(AuthInteractionPrompt.Select.Option("a", "A", "first")),
            ),
        )
        assertEquals(AuthPromptKind.SELECT, select.kind)
        assertEquals(listOf(AuthPromptOption("a", "A", "first")), select.options)
    }

    @Test
    fun storedOAuthCredential_configuresProvider_onlyWithRegisteredFlow() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Account-vs-key stored status: a stored OAuth credential marks the
        // provider configured where a flow is registered (zai)...
        h.credentials.creds["zai"] =
            OAuthCredential("access-token-9", "refresh-token-9", Long.MAX_VALUE)
        // ...but resolves as unconfigured without a handler (cloudflare has
        // key prompts and no registered flow — pi's handler-less credential).
        h.credentials.creds["cloudflare-ai-gateway"] =
            OAuthCredential("access-token-9", "refresh-token-9", Long.MAX_VALUE)

        val vm = h.newViewModel()
        // zai has a registered flow, so its stored OAuth credential configures
        // the provider and the app enters the chat directly on the derived
        // model (the new first-run flow — no model-settings step).
        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertTrue(state.providerOptions.first { it.id == "zai" }.configured)
        assertFalse(state.providerOptions.first { it.id == "cloudflare-ai-gateway" }.configured)
        assertTrue(state.modelOptions.all { it.providerId == "zai" })
        assertTrue(state.modelOptions.isNotEmpty())
        assertFalse(state.toString().contains("access-token-9"))

        // Logout removes the stored credential and unconfigures.
        vm.removeProviderCredential("zai")
        vm.uiState.first { !it.providerOptions.first { o -> o.id == "zai" }.configured }
        assertNull(h.credentials.creds["zai"])

        vm.closeForTest()
    }

    @Test
    fun accountLogin_eventAndPromptProgression_successClosesWithEpoch() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }
        var chosen: String? = null
        h.oauthZai.loginFn = { interaction ->
            interaction.notify(AuthEvent.Info("Choose an account"))
            chosen = interaction.prompt(
                AuthInteractionPrompt.Select(
                    "Select account",
                    listOf(
                        AuthInteractionPrompt.Select.Option("personal", "Personal"),
                        AuthInteractionPrompt.Select.Option("work", "Work", "Company account"),
                    ),
                ),
            )
            interaction.notify(AuthEvent.AuthUrl("https://auth.test/authorize", "Approve access"))
            interaction.notify(AuthEvent.DeviceCode("ABCD-1234", "https://verify.test/device", intervalSeconds = 5))
            interaction.notify(AuthEvent.Progress("Waiting for approval"))
            val code = interaction.prompt(AuthInteractionPrompt.ManualCode("Enter the code from the browser"))
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

        // Events accumulate in order while the manual-code prompt is pending.
        vm.uiState.first { it.authFlow?.pendingPrompt?.kind == AuthPromptKind.MANUAL_CODE }
        val events = vm.uiState.value.authFlow!!.events
        assertTrue(events[0] is AuthEvent.Info)
        assertEquals("https://auth.test/authorize", (events[1] as AuthEvent.AuthUrl).url)
        assertEquals("ABCD-1234", (events[2] as AuthEvent.DeviceCode).userCode)
        assertTrue(events[3] is AuthEvent.Progress)

        vm.submitAuthPrompt("654321")

        // Success: the flow clears, the epoch bumps exactly once (the UI
        // closes the auth screen on it, like a key save), derived state
        // refreshes, and no token material ever entered the state.
        val done = vm.uiState.first { it.authFlow == null && it.credentialSuccessEpoch == 1L }
        assertTrue(done.providerOptions.first { it.id == "zai" }.configured)
        assertTrue(done.modelOptions.any { it.providerId == "zai" })
        assertFalse(done.toString().contains("access-token-1"))
        assertNull(done.error)

        // The stored credential is the OAuth one (account login replaced nothing
        // else — the store was empty before).
        assertEquals(CredentialType.OAUTH, h.credentials.creds["zai"]?.type)

        vm.closeForTest()
    }

    @Test
    fun accountLogin_failure_recordsTelemetryAtBothBoundaries() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

        h.oauthZai.loginFn = { throw IllegalStateException("token exchange failed (400)") }
        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.uiState.first { it.authFlow == null && it.error != null }

        // The login operation itself is one error span (recorded by the
        // ViewModel at the pf.auth.login app boundary).
        val loginSpan = h.telemetry.getSpans().single { it.name == "pf.auth.login" }
        assertEquals(
            works.resolve.pathfinder.telemetry.attr("zai"),
            loginSpan.attributes["pf.auth.provider"],
        )
        assertTrue(loginSpan.status is works.resolve.pathfinder.telemetry.SpanStatus.Error)

        // The UI error boundary records the swallowed exception with the
        // generic UI message — the only trace of what actually failed.
        vm.uiState.first { _ ->
            h.telemetry.getSpans().any { it.name == "pf.chat.error" }
        }
        val errorSpan = h.telemetry.getSpans().single { it.name == "pf.chat.error" }
        assertEquals(
            works.resolve.pathfinder.telemetry.attr("Could not complete sign-in"),
            errorSpan.attributes["pf.error.ui_message"],
        )
        val status = errorSpan.status as works.resolve.pathfinder.telemetry.SpanStatus.Error
        // The UI boundary records the wrapped ModelsError (ProviderAuthService wraps
        // flow failures) type-only: short class name, never the free-form message.
        assertEquals("ModelsError", status.error?.name)
        assertEquals("", status.error?.message)

        vm.closeForTest()
    }

    @Test
    fun apiKeyLogin_recordsPersistedLoginSpanAtAppBoundary() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.uiState.first { it.credentialSuccessEpoch > 0 }

        // The pf.auth.login span moved out of the ported ProviderAuthService
        // to the caller boundary; the API-key save path records it too.
        val span = h.telemetry.getSpans().single { it.name == "pf.auth.login" }
        assertEquals(works.resolve.pathfinder.telemetry.attr("zai"), span.attributes["pf.auth.provider"])
        assertEquals(works.resolve.pathfinder.telemetry.attr("api_key"), span.attributes["pf.auth.type"])
        assertEquals(works.resolve.pathfinder.telemetry.attr("persisted"), span.attributes["pf.auth.outcome"])
        assertTrue(span.status is works.resolve.pathfinder.telemetry.SpanStatus.Ok)

        vm.closeForTest()
    }

    @Test
    fun credentialReadFailure_degradesWithTelemetryNotSilence() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Persist a valid provider+model selection, then make credential reads
        // fail: the restoration path must degrade to NeedsConfiguration
        // (pi's semantics) while recording why — the failure must be
        // distinguishable on-device from an actually-missing credential.
        h.settings.setProviderId("zai")
        h.settings.setModelId(testModel.id)
        h.credentials.failWrites = true
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }

        val degraded = h.telemetry.getSpans().filter { it.name == "pf.chat.degraded" }
        assertTrue(degraded.isNotEmpty())
        assertTrue(degraded.all { it.status is works.resolve.pathfinder.telemetry.SpanStatus.Error })
        // The credential-read failure is named, not silently absorbed into
        // "unconfigured" (the init projection and the options refresh each
        // record their own degraded operation).
        assertTrue(
            degraded.any { it.attributes["pf.degraded.operation"] == works.resolve.pathfinder.telemetry.attr("available_models") } ||
                degraded.any { it.attributes["pf.degraded.operation"] == works.resolve.pathfinder.telemetry.attr("provider_status") },
        )

        vm.closeForTest()
    }

    @Test
    fun accountLogin_cancelOrFailure_mutatesNothing_andFlowRestartsCleanly() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

        // Cancel while a secret prompt is suspended.
        h.oauthZai.loginFn = { interaction ->
            interaction.prompt(AuthInteractionPrompt.Secret("Paste token"))
            OAuthCredential("never-stored", "never-stored", Long.MAX_VALUE)
        }
        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.uiState.first { it.authFlow?.pendingPrompt?.kind == AuthPromptKind.SECRET }
        vm.cancelProviderAuthLogin()
        vm.uiState.first { it.authFlow == null }
        assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
        assertNull(h.credentials.creds["zai"])
        assertNull(vm.uiState.value.error)

        // Failure surfaces only the safe generic error (never the cause).
        h.oauthZai.loginFn = { throw IllegalStateException("token endpoint returned access-token-2") }
        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.uiState.first { it.authFlow == null && it.error != null }
        assertEquals("Could not complete sign-in", vm.uiState.value.error)
        assertFalse(vm.uiState.value.toString().contains("access-token-2"))
        assertNull(h.credentials.creds["zai"])
        assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
        vm.dismissError()

        // After cancel/failure a fresh login succeeds (nothing stuck).
        h.oauthZai.loginFn = { OAuthCredential("access-token-3", "refresh-token-3", Long.MAX_VALUE) }
        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.uiState.first { it.authFlow == null && it.credentialSuccessEpoch == 1L }
        assertTrue(vm.uiState.value.providerOptions.first { o -> o.id == "zai" }.configured)
        assertFalse(vm.uiState.value.toString().contains("access-token-3"))

        vm.closeForTest()
    }

    @Test
    fun concurrentAuthFlows_areRejected() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

        val promptGate = CompletableDeferred<Unit>()
        h.oauthZai.loginFn = { interaction ->
            interaction.prompt(AuthInteractionPrompt.Text("Enter anything"))
                .also { promptGate.complete(Unit) }
            OAuthCredential("never-stored", "never-stored", Long.MAX_VALUE)
        }
        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.uiState.first { it.authFlow?.pendingPrompt != null }

        // A second login and a key save are both rejected while a flow runs.
        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.uiState.first { it.error != null }
        vm.dismissError()
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.uiState.first { it.error != null }
        assertNull(h.credentials.creds["zai"])
        vm.dismissError()

        vm.cancelProviderAuthLogin()
        vm.uiState.first { it.authFlow == null }
        // Now the key save works through the normal form path.
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.uiState.first { it.credentialSuccessEpoch == 1L }
        assertEquals("k", (h.credentials.creds["zai"] as ApiKeyCredential).key)

        vm.closeForTest()
    }

    // ---- tree navigation (pi's navigateTree, reduced) ----

    /** Runs one scripted exchange and waits for its persistence. */
    private suspend fun ChatViewModel.exchange(
        h: Harness,
        text: String,
        reply: String,
    ) {
        h.scriptedStreams.add(h.gatedStream(reply, CompletableDeferred<Unit>().apply { complete(Unit) }))
        onDraftChange(text)
        send()
        uiState.first { !it.isStreaming && it.messages.size >= 2 }
    }

    @Test
    fun navigateToAssistantEntry_truncatesTranscript_andRoundtripPreservesBranches() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.exchange(h, "Again", "fine")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 4 }
        assertEquals(4, vm.uiState.value.treeRows.size)
        assertTrue(vm.uiState.value.treeRows.last().isCurrentLeaf)

        // Navigate to the first assistant answer: transcript truncates to the
        // root..that-entry path, tree rows keep every entry.
        val assistantEntryId = vm.uiState.value.treeRows[1].id
        vm.navigateToTreeEntry(assistantEntryId)
        val truncated = vm.uiState.first { it.messages.size == 2 }
        assertEquals(4, truncated.treeRows.size)
        assertEquals(assistantEntryId, truncated.treeRows.first { it.isCurrentLeaf }.id)
        assertTrue(truncated.treeRows[0].isOnActivePath)
        assertFalse(truncated.treeRows[3].isOnActivePath)
        assertEquals("world", truncated.messages[1].singleText())

        // Entries + leafId persist (branch structure survives the save); the
        // seed model_change and thinking_level_change entries ride along as
        // sixth and seventh (elided) entries.
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        val saved = h.sessionStore.load(sessionId)!!
        assertEquals(6, saved.entries.size)
        assertEquals(assistantEntryId, saved.leafId)

        // A new exchange from here forks: the second user message becomes a
        // SIBLING of the old one under the same assistant entry.
        vm.exchange(h, "Third", "forked")
        vm.uiState.first { it.messages.size == 4 && it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 4 }
        val forked = h.sessionStore.load(sessionId)!!
        assertEquals(8, forked.entries.size)
        assertEquals(4, forked.messages.size)
        val childrenOfTarget = forked.entries.filter { it.parentId == assistantEntryId }
        assertEquals(2, childrenOfTarget.map { it.id }.toSet().size)

        // Roundtrip: a fresh ViewModel restores the branched session intact.
        vm.closeForTest()
        val vm2 = h.newViewModel()
        val restored = vm2.uiState.first { it.status == ChatStatus.Ready && it.activeSessionId == sessionId }
        assertEquals(4, restored.messages.size)
        assertEquals("Third", restored.messages[2].singleText())
        assertEquals(6, restored.treeRows.size)
        val forkParent = restored.treeRows.first { it.id == assistantEntryId }        // Active branch reads first among the fork's children.
        val thirdId = (forked.entries.first { e ->
            e is works.resolve.pathfinder.data.sessions.MessageEntry &&
                e.message is works.resolve.pathfinder.ai.core.UserMessage &&
                e.message.content.filterIsInstance<TextContent>().first().text == "Third"
        }).id
        val childIds = restored.treeRows.filter { it.path.contains(assistantEntryId) && it.path.size == forkParent.path.size + 1 }.map { it.id }
        assertEquals(2, childIds.size)
        assertEquals(thirdId, childIds.first())
        vm2.closeForTest()
    }

    @Test
    fun navigateToUserMessage_restoresDraft_andNextSendForksAsSibling() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }

        // Re-edit the user prompt: draft is restored, active path empties
        // (the leaf resets to the root), and the tree keeps both entries.
        val userEntryId = vm.uiState.value.treeRows[0].id
        vm.navigateToTreeEntry(userEntryId)
        val reedit = vm.uiState.first { it.draft == "Hello" }
        assertEquals(0, reedit.messages.size)
        assertEquals(2, reedit.treeRows.size)
        assertTrue(reedit.canSend)

        // The next send appends as a sibling (a second root), not a child.
        vm.exchange(h, "Hello edited", "rewritten")
        vm.uiState.first { it.messages.size == 2 && it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        val saved = h.sessionStore.load(sessionId)!!
        // Six entries: the seed model_change + thinking_level_change roots,
        // the original pair, and the re-sent sibling pair (its user message
        // forking under the thinking seed).
        assertEquals(6, saved.entries.size)
        // The seed model_change is the only root: the re-edited user message
        // forks as a sibling of the original under the thinking seed entry
        // (re-edit branches to the target's parent, which now exists).
        val seedChange = saved.entries.filterIsInstance<ModelChangeEntry>().single()
        val roots = saved.entries.filter { it.parentId == null }
        assertEquals(listOf(seedChange.id), roots.map { it.id })
        assertEquals("Hello edited", (saved.messages[0] as works.resolve.pathfinder.ai.core.UserMessage).content.filterIsInstance<TextContent>().first().text)

        // Tree rows show both roots, active one first.
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
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        vm.exchange(h, "Hello", "world")
        vm.uiState.first { it.treeRows.size == 2 }

        vm.onDraftChange("half-typed draft")
        val userEntryId = vm.uiState.value.treeRows[0].id
        vm.navigateToTreeEntry(userEntryId)

        // pi's navigateTree loads the re-edit text into the editor only when
        // it is empty; a typed draft is never clobbered by navigation.
        val state = vm.uiState.first { it.messages.isEmpty() }
        assertEquals("half-typed draft", state.draft)

        vm.closeForTest()
    }

    /**
     * pi's setModel records every model selection as a model_change entry on
     * the session tree (agent-session.ts ~1665), and sdk.ts seeds new
     * sessions with the initial selection. Both must persist: the seed entry
     * on first configuration, and a recorded switch appended to the active
     * leaf when the user re-selects while Ready.
     */
    @Test
    fun modelSelection_recordsModelChangeEntries_andPersistsThem() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        // Initial configuration seeds the new session with the selection.
        vm.uiState.first { h.sessionStore.load(sessionId)!!.entries.isNotEmpty() }
        val seeded = h.sessionStore.load(sessionId)!!
        val seedChange = seeded.entries.filterIsInstance<ModelChangeEntry>().single()
        assertEquals("zai", seedChange.provider)
        assertEquals("glm-4.7", seedChange.modelId)
        assertNull(seedChange.parentId)

        vm.exchange(h, "Hello", "world")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        val agentsBefore = h.createdAgents.size

        // A live re-selection (pi's /model pick) switches the SAME session —
        // no agent rebuild — and appends a model_change after the leaf.
        // (The thinking re-application after the switch appends nothing:
        // no stored default, so the session's current level is re-applied.)
        vm.selectModel("zai", "glm-5.3")
        vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        assertEquals(agentsBefore, h.createdAgents.size)
        vm.uiState.first { h.sessionStore.load(sessionId)!!.entries.size == 6 }
        val saved = h.sessionStore.load(sessionId)!!
        val switch = saved.entries[4] as ModelChangeEntry
        assertEquals("zai", switch.provider)
        assertEquals("glm-5.3", switch.modelId)
        assertEquals(saved.entries[3].id, switch.parentId)
        // pi's setModel re-applies the session's thinking level for the new
        // model (_getThinkingLevelForModelSwitch → setThinkingLevel), clamped
        // to its capabilities: glm-5.3's map supports only low/high/max, so
        // the session's seeded medium clamps up to high and a
        // thinking_level_change lands beneath the model_change.
        val switchThinking = saved.entries[5] as works.resolve.pathfinder.data.sessions.ThinkingLevelEntry
        assertEquals("high", switchThinking.thinkingLevel)
        assertEquals(switch.id, switchThinking.parentId)
        assertEquals(switchThinking.id, saved.leafId)
        // The next prompt runs on the switched model (no rebuild involved).
        vm.exchange(h, "Again", "fine")
        assertEquals(listOf("glm-4.7", "glm-5.3"), h.streamedModels.map { it.id })

        vm.closeForTest()
    }

    // ---- thinking level (pi's thinking selector + setThinkingLevel) ----

    /**
     * pi's sdk.ts session initialization: a new session seeds the default
     * thinking level (getDefaultThinkingLevel() ?? DEFAULT "medium"),
     * clamped to the model; the chip surfaces fold onto the live session
     * state (footer reads state.thinkingLevel and the model's supported
     * levels).
     */
    @Test
    fun newSession_seedsDefaultThinkingLevel_andProjectsTheChipSurfaces() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        val ready = vm.uiState.first { it.status == ChatStatus.Ready }
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
                ModelThinkingLevel.HIGH,
            ),
            ready.availableThinkingLevels,
        )
        assertNull(ready.defaultThinkingLevel)

        // The seed lands on the tree and persists (pi's appendModelChange +
        // appendThinkingLevelChange new-session path).
        vm.uiState.first { h.sessionStore.load(sessionId)!!.entries.size == 2 }
        val seeded = h.sessionStore.load(sessionId)!!
        assertEquals(
            listOf("medium"),
            seeded.entries.filterIsInstance<works.resolve.pathfinder.data.sessions.ThinkingLevelEntry>()
                .map { it.thinkingLevel },
        )

        vm.closeForTest()
    }

    /**
     * pi's selector pick (Enter): one setThinkingLevel call switches the
     * session, appends thinking_level_change only when the level changes,
     * and never persists the default.
     */
    @Test
    fun selectThinkingLevel_switchesTheSession_appendingOnlyOnChange() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.selectThinkingLevel(ModelThinkingLevel.HIGH)
        val switched = vm.uiState.first { it.thinkingLevel == ModelThinkingLevel.HIGH }
        assertNull("no default persisted by a pick (pi persists only via Ctrl+S)", switched.defaultThinkingLevel)
        assertNull(h.settings.currentSettings().defaultThinkingLevel)
        vm.uiState.first { h.sessionStore.load(sessionId)!!.entries.size == 3 }

        // Re-picking the current level appends nothing (pi: only when the
        // level actually changes).
        vm.selectThinkingLevel(ModelThinkingLevel.HIGH)
        vm.uiState.first { it.thinkingLevel == ModelThinkingLevel.HIGH }
        assertTrue(h.sessionStore.load(sessionId)!!.entries.size == 3)
        assertEquals(
            listOf("medium", "high"),
            h.sessionStore.load(sessionId)!!.entries
                .filterIsInstance<works.resolve.pathfinder.data.sessions.ThinkingLevelEntry>()
                .map { it.thinkingLevel },
        )

        vm.closeForTest()
    }

    /**
     * pi's setThinkingLevel clamps to the model's capabilities
     * (agent-session.ts:1795): glm-5.3's map supports only low/high/max, so
     * a minimal pick rounds up to low.
     */
    @Test
    fun selectThinkingLevel_clampsToTheModelSupportedLevels() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        vm.selectModel("zai", "glm-5.3")
        val switched = vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        // The switch re-applied the session's medium clamped to the new map.
        assertEquals(ModelThinkingLevel.HIGH, switched.thinkingLevel)
        assertEquals(
            listOf(ModelThinkingLevel.LOW, ModelThinkingLevel.HIGH, ModelThinkingLevel.MAX),
            switched.availableThinkingLevels,
        )

        vm.selectThinkingLevel(ModelThinkingLevel.MINIMAL)
        vm.uiState.first { it.thinkingLevel == ModelThinkingLevel.LOW }

        vm.closeForTest()
    }

    /**
     * pi's Ctrl+S (setThinkingLevel(level, { persist: true })): applies to
     * the live session clamped, but "persists the requested default thinking
     * level even when the current model clamps it" — glm-4.7 supports at
     * most high, so xhigh runs as high while the setting stores xhigh.
     */
    @Test
    fun setThinkingLevelDefault_persistsTheRequestedLevel_andRunsItClamped() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        vm.setThinkingLevelDefault(ModelThinkingLevel.XHIGH)
        val defaulted = vm.uiState.first { it.defaultThinkingLevel == ModelThinkingLevel.XHIGH }

        assertEquals(ModelThinkingLevel.XHIGH, h.settings.currentSettings().defaultThinkingLevel)
        assertEquals("the session runs the clamped level", ModelThinkingLevel.HIGH, defaulted.thinkingLevel)
        assertEquals("the thinking chip projects the clamped session level", ModelThinkingLevel.HIGH, h.createdAgents.last().thinkingLevel)

        vm.closeForTest()
    }

    /**
     * pi's model-switch thinking re-application
     * (_getThinkingLevelForModelSwitch): the stored global default wins over
     * the session's current level (pi's "switch back to faux-1 → uses global
     * default"), clamped to the new model.
     */
    @Test
    fun selectModel_reappliesTheStoredDefaultThinkingLevel() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.setThinkingLevelDefault(ModelThinkingLevel.LOW)
        vm.uiState.first { it.thinkingLevel == ModelThinkingLevel.LOW }
        vm.selectThinkingLevel(ModelThinkingLevel.HIGH)
        vm.uiState.first { it.thinkingLevel == ModelThinkingLevel.HIGH }
        vm.uiState.first { h.sessionStore.load(sessionId)!!.entries.size == 4 }

        vm.selectModel("zai", "glm-5.3")
        vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        // The switch re-applied the stored default (low), clamped into the
        // new model's low/high/max map, appending beneath the model_change.
        val reapply = vm.uiState.first { it.thinkingLevel == ModelThinkingLevel.LOW }
        assertEquals(ModelThinkingLevel.LOW, reapply.thinkingLevel)
        vm.uiState.first { h.sessionStore.load(sessionId)!!.entries.size == 6 }
        val saved = h.sessionStore.load(sessionId)!!
        val reapplyEntry = saved.entries.last() as works.resolve.pathfinder.data.sessions.ThinkingLevelEntry
        assertEquals("low", reapplyEntry.thinkingLevel)
        assertEquals((saved.entries[4] as ModelChangeEntry).id, reapplyEntry.parentId)

        vm.closeForTest()
    }

    /**
     * pi's session-load restore (sdk.ts: hasThinkingEntry → the branch's
     * level, not the global default): a reload keeps the recorded branch
     * level and does not re-seed over it.
     */
    @Test
    fun sessionReload_restoresTheBranchThinkingLevel() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.selectThinkingLevel(ModelThinkingLevel.HIGH)
        vm.uiState.first { h.sessionStore.load(sessionId)!!.entries.size == 3 }
        vm.closeForTest()

        val vm2 = h.newViewModel()
        val restored = vm2.uiState.first { it.status == ChatStatus.Ready && it.activeSessionId == sessionId }
        assertEquals(ModelThinkingLevel.HIGH, restored.thinkingLevel)
        assertEquals(
            "the branch entry survives reload; no re-seed over it",
            listOf("medium", "high"),
            h.sessionStore.load(sessionId)!!.entries
                .filterIsInstance<works.resolve.pathfinder.data.sessions.ThinkingLevelEntry>()
                .map { it.thinkingLevel },
        )

        vm2.closeForTest()
    }

    /**
     * prompt (agent.ts snapshots the model per run).
     */
    @Test
    fun selectModel_midStream_appliesToTheNextPrompt() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("first", gate))
        vm.onDraftChange("Hello")
        vm.send()
        vm.uiState.first { it.isStreaming }

        // The mid-stream pick is accepted without complaint.
        vm.selectModel("zai", "glm-5.3")
        mainDispatcherRule.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals("glm-5.3", vm.uiState.value.selectedModel?.modelId)
        assertTrue(vm.uiState.value.isStreaming)

        gate.complete(Unit)
        vm.uiState.first { !it.isStreaming && it.messages.size == 2 }

        // The in-flight run kept its start-of-run model; the next one switches.
        h.scriptedStreams.add(h.gatedStream("second", CompletableDeferred<Unit>().apply { complete(Unit) }))
        vm.onDraftChange("Again")
        vm.send()
        vm.uiState.first { !it.isStreaming && it.messages.size == 4 }
        assertEquals(listOf("glm-4.7", "glm-5.3"), h.streamedModels.map { it.id })

        vm.closeForTest()
    }

    /**
     * Default persistence is pi's explicit Ctrl+S action — never part of a
     * pick — and appends the default to a non-empty scope when missing
     * (pi's _addPersistedDefaultToNonEmptyScope).
     */
    @Test
    fun saveStartupDefault_separateAction_appendsToNonEmptyScope() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        // No scope curated: the default save persists provider+model only.
        vm.saveStartupDefault("zai", "glm-4.7")
        vm.uiState.first { h.settings.currentSettings().modelId == "glm-4.7" }
        assertNull(h.settings.currentSettings().enabledModels)
        assertNull(vm.uiState.value.enabledModels)

        // Curate a scope: uncheck glm-4.7 (materializes the explicit list in
        // display order — all remaining models, everything offered minus it).
        vm.toggleModelScope("zai", "glm-4.7", false)
        val scoped = vm.uiState.first { it.enabledModels != null }
        assertTrue(scoped.enabledModels!!.none { it == "zai/glm-4.7" })
        assertEquals(h.settings.currentSettings().enabledModels, scoped.enabledModels)
        // The picker's scoped view narrowed; the running model is untouched.
        assertTrue(scoped.scopedModelOptions.none { it.modelId == "glm-4.7" })
        assertEquals("glm-4.7", scoped.selectedModel?.modelId)

        // Saving a default that is not in the non-empty scope appends it
        // (order-preserving append, pi's behavior).
        vm.saveStartupDefault("zai", "glm-4.7")
        val grown = vm.uiState.first { it.enabledModels?.contains("zai/glm-4.7") == true }.enabledModels!!
        assertEquals("zai/glm-4.7", grown.last())
        assertEquals(h.settings.currentSettings().enabledModels, grown)
        assertTrue(vm.uiState.value.scopedModelOptions.any { it.modelId == "glm-4.7" })
        assertNull(vm.uiState.value.error)

        vm.closeForTest()
    }

    /**
     * [ChatUiState.defaultModel] mirrors only the stored startup default
     * (pi's `defaultModel` setting): null before one is saved, set by the
     * Settings save, and never moved by live model switches — unlike
     * [ChatUiState.selectedModel], which follows the running session.
     */
    @Test
    fun defaultModel_mirrorFollowsStoredDefault_notLiveSwitches() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        val ready = vm.uiState.first { it.status == ChatStatus.Ready }
        assertNull(ready.defaultModel)

        // A live switch (pi's Enter) never touches the stored-default mirror.
        vm.selectModel("zai", "glm-5.3")
        vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        assertNull(vm.uiState.value.defaultModel)

        vm.saveStartupDefault("zai", "glm-5.3")
        vm.uiState.first { it.defaultModel?.modelId == "glm-5.3" }
        assertEquals("glm-5.3", h.settings.currentSettings().modelId)

        vm.selectModel("zai", "glm-4.7")
        vm.uiState.first { it.selectedModel?.modelId == "glm-4.7" }
        assertEquals("glm-5.3", vm.uiState.value.defaultModel?.modelId)

        vm.closeForTest()
    }

    /** The curator persists the ordered list; removing everything keeps an
     * empty scope that behaves as no scope downstream (pi's !length). */
    @Test
    fun toggleModelScope_persistsOrderedList_emptyBehavesAsNoScope() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val all = vm.uiState.value.modelOptions
        assertTrue(all.size >= 2)

        // First edit materializes the explicit list in display order.
        vm.toggleModelScope(all[0].providerId, all[0].modelId, false)
        val curated = vm.uiState.first { it.enabledModels != null }.enabledModels!!
        assertEquals(all.drop(1).map { "${it.providerId}/${it.modelId}" }, curated)

        // Unchecking every remaining row writes an empty list.
        all.drop(1).forEach { vm.toggleModelScope(it.providerId, it.modelId, false) }
        val emptied = vm.uiState.first { it.enabledModels?.isEmpty() == true }
        assertEquals(emptyList<String>(), h.settings.currentSettings().enabledModels)
        // Downstream it behaves as no scope: the picker shows everything.
        assertEquals(emptied.modelOptions, emptied.scopedModelOptions)

        // Re-checking from the empty scope re-adds just that ref.
        vm.toggleModelScope(all[1].providerId, all[1].modelId, true)
        vm.uiState.first { it.enabledModels == listOf("${all[1].providerId}/${all[1].modelId}") }

        vm.closeForTest()
    }

    /** Picker projections follow credential filtering (pi's getAvailable). */
    @Test
    fun scopedModelOptions_followCredentialFiltering() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Copilot narrowed to gpt-4.1; zai full.
        h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
        h.credentials.creds["zai"] = ApiKeyCredential("z")
        val vm = h.newViewModel()
        val state = vm.uiState.first { it.status == ChatStatus.Ready }

        // Display order: GitHub Copilot before Z.AI; name sort puts
        // "glm-5-turbo" before "glm-5.2" ('-' sorts before '.').
        assertEquals(
            listOf("gpt-4.1", "glm-4.7", "glm-5-turbo", "glm-5.2", "glm-5.2-highspeed", "glm-5.3"),
            state.modelOptions.map { it.modelId },
        )
        // Scope the Copilot model and one zai model: the scoped view keeps
        // display order and drops the filtered-out models.
        vm.toggleModelScope("github-copilot", "gpt-4.1", false)
        vm.toggleModelScope("zai", "glm-4.7", false)
        val scoped = vm.uiState.first { it.enabledModels != null }
        assertEquals(
            listOf("glm-5-turbo", "glm-5.2", "glm-5.2-highspeed", "glm-5.3"),
            scoped.scopedModelOptions.map { it.modelId },
        )

        vm.closeForTest()
    }

    /**
     * The branch's configuration fold (pi's deriveSessionContextState)
     * decides the running model: navigating back before a model_change makes
     * the older selection effective again (agent rebuilt on it), and a fresh
     * ViewModel restores the same folded model on session load even though
     * the persisted global default differs.
     */
    @Test
    fun effectiveModel_isSeededFromBranchFold_onNavigationAndRestore() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        // Switch the model (records model_change glm-5.3 after the leaf) and
        // persist it as the startup default (pi's Ctrl+S).
        vm.selectModel("zai", "glm-5.3")
        vm.uiState.first { it.selectedModel?.modelId == "glm-5.3" }
        vm.saveStartupDefault("zai", "glm-5.3")
        vm.uiState.first { h.settings.currentSettings().modelId == "glm-5.3" }

        // Navigate back to the first assistant answer: the branch fold is
        // seed(glm-4.7) + assistant(glm-4.7), so the agent rebuilds on 4.7.
        val assistantEntryId = vm.uiState.value.treeRows[1].id
        vm.navigateToTreeEntry(assistantEntryId)
        vm.uiState.first { h.createdSettings.last().modelId == "glm-4.7" }
        vm.uiState.first { h.sessionStore.load(sessionId)!!.leafId == assistantEntryId }

        // Restore: persisted global default stays glm-5.3, but the loaded
        // branch's fold seeds the running agent on glm-4.7.
        vm.closeForTest()
        val vm2 = h.newViewModel()
        vm2.uiState.first { it.status == ChatStatus.Ready && it.activeSessionId == sessionId }
        assertEquals("glm-5.3", h.settings.currentSettings().modelId)
        assertEquals("glm-4.7", h.createdSettings.last().modelId)

        vm2.closeForTest()
    }

    @Test
    fun navigateToCurrentLeaf_orUnknownEntry_isRejectedSafely() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        vm.exchange(h, "Hello", "world")
        val leafId = vm.uiState.value.treeRows.last().id

        vm.navigateToTreeEntry(leafId)
        vm.uiState.first { it.error == "Already at this point" }
        assertEquals(2, vm.uiState.value.messages.size)
        vm.dismissError()

        vm.navigateToTreeEntry("no-such-entry")
        vm.uiState.first { it.error != null }
        assertEquals(2, vm.uiState.value.messages.size)

        vm.closeForTest()
    }

    @Test
    fun navigateWhileStreaming_isBusyRejected() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        vm.exchange(h, "Hello", "world")
        val firstEntry = vm.uiState.value.treeRows[0].id

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(h.gatedStream("slow", gate))
        vm.onDraftChange("Second")
        vm.send()
        vm.uiState.first { it.isStreaming }

        vm.navigateToTreeEntry(firstEntry)
        vm.uiState.first { it.error != null }
        assertEquals(3, vm.uiState.value.messages.size) // user message already committed
        vm.dismissError()

        gate.complete(Unit)
        vm.uiState.first { !it.isStreaming && it.messages.size == 4 }
        vm.uiState.first { it.sessionSummaries.first().messageCount == 4 }

        vm.closeForTest()
    }

    @Test
    fun setTreeFilter_reprojectsRowsInMemory() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        vm.exchange(h, "Hello", "world")
        vm.setTreeFilter(TreeFilter.USER_ONLY)
        val filtered = vm.uiState.first { it.treeFilter == TreeFilter.USER_ONLY }.treeRows
        assertEquals(1, filtered.size)
        assertEquals("You: Hello", filtered[0].preview)
        // Default view restored on demand; nothing persisted.
        vm.setTreeFilter(TreeFilter.DEFAULT)
        assertEquals(2, vm.uiState.value.treeRows.size)

        vm.closeForTest()
    }

    // ---- thinking block projection ----

    /** Text-only convenience for single-text-block assertions. */
    private fun ChatMessage.singleText(): String =
        blocks.single().let { it as ChatBlock.Text }.text

    @Test
    fun projection_mergesThinkingRuns_dropsBlanks_preservesOrder() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        val assistant = h.assistant("").copy(
            content = listOf(
                ThinkingContent("alpha"),
                ThinkingContent("beta"),
                TextContent("first"),
                ThinkingContent("   "),
                TextContent("  "),
                TextContent("second"),
                ThinkingContent(" lone "),
            ),
        )
        h.createdAgents.last().replaceConversation(
            works.resolve.pathfinder.data.sessions.Conversation.fromMessages(
                listOf(works.resolve.pathfinder.ai.core.UserMessage.ofText("hi"), assistant),
            ),
        )

        val state = vm.uiState.first { it.messages.size == 2 }
        val blocks = state.messages[1].blocks
        assertEquals(
            listOf(
                ChatBlock.Thinking("alpha\n\nbeta"),
                ChatBlock.Text("first"),
                ChatBlock.Text("second"),
                ChatBlock.Thinking("lone"),
            ),
            blocks,
        )
        // User message → a single Text block.
        assertEquals(listOf(ChatBlock.Text("hi")), state.messages[0].blocks)
        // The display preference is untouched by projection.
        assertEquals(state.showThinking, h.settings.currentSettings().showThinking)

        vm.closeForTest()
    }

    @Test
    fun projection_thinkingOnlyStreaming_yieldsThinkingBlock() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }

        val gate = CompletableDeferred<Unit>()
        h.scriptedStreams.add(
            flow {
                emit(AssistantMessageEvent.Start(h.assistant("")))
                val partial = h.assistant("").copy(content = listOf(ThinkingContent("reasoning so far")))
                emit(AssistantMessageEvent.ThinkingDelta(0, "reasoning", partial))
                gate.await()
                emit(AssistantMessageEvent.Done(StopReason.STOP, partial))
            },
        )
        vm.onDraftChange("hi")
        vm.send()

        vm.uiState.first { it.streamingMessage?.blocks?.isNotEmpty() == true }
        val streaming = vm.uiState.value.streamingMessage!!
        assertEquals(listOf(ChatBlock.Thinking("reasoning so far")), streaming.blocks)

        // Let the stream finish so teardown never abandons it.
        gate.complete(Unit)
        vm.uiState.first { !it.isStreaming }

        vm.closeForTest()
    }

    // ---- GitHub Copilot credential-based model filtering (pi's filterModels) ----

    @Test
    fun copilotOAuthAvailableModelIds_narrowsModelOptions() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
        val vm = h.newViewModel()

        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(listOf("gpt-4.1"), vm.copilotModelOptions())
        assertTrue(state.providerOptions.first { it.id == "github-copilot" }.configured)

        vm.closeForTest()
    }

    @Test
    fun copilotOAuthMalformedAvailableModelIds_showsAllModels() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // Mixed (string + number) array: not entirely strings, so pi keeps
        // the full static list.
        h.credentials.creds["github-copilot"] = copilotCredential(
            JsonArray(listOf(JsonPrimitive("gpt-4.1"), JsonPrimitive(7))),
        )
        val vm = h.newViewModel()

        vm.uiState.first { it.status == ChatStatus.Ready }
        assertEquals(listOf("claude-haiku-4.5", "gpt-4.1", "gpt-4.5"), vm.copilotModelOptions())

        vm.closeForTest()
    }

    @Test
    fun copilotOAuthEmptyAvailableModelIds_showsNoModelsButStaysConfigured() =
        runTest(mainDispatcherRule.scheduler) {
            val h = Harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray())
            val vm = h.newViewModel()

            val state = vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
            assertEquals(emptyList<String>(), vm.copilotModelOptions())
            assertTrue(state.providerOptions.first { it.id == "github-copilot" }.configured)

            vm.closeForTest()
        }

    @Test
    fun copilotLogoutThenApiKeySwitch_showsAllModelsAgain() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.Ready }

        // Logout: no credential ⇒ the provider is unconfigured, so pi's
        // getAvailable contributes nothing (not even unfiltered models).
        vm.removeProviderCredential("github-copilot")
        mainDispatcherRule.scheduler.advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.copilotModelOptions())
        assertFalse(vm.uiState.value.providerOptions.first { it.id == "github-copilot" }.configured)

        // Switching to an API-key credential (COPILOT_GITHUB_TOKEN prompt) is
        // complete and never filtered ⇒ every static model returns.
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

            // The saved default is credential-filtered out (pi's getAvailable
            // drops it): a safe availability error surfaces, but the derived
            // replacement (the only available model) runs — chat is usable.
            val state = vm.uiState.first { it.status == ChatStatus.Ready }
            assertEquals("gpt-4.1", state.selectedModel?.modelId)
            assertEquals(ChatNavKey, state.startKey)
            assertNotNull(state.error)
            assertEquals(listOf("gpt-4.1"), vm.copilotModelOptions())
            vm.dismissError()

            // The unavailable model is rejected exactly like an unknown one.
            vm.selectModel("github-copilot", "gpt-4.5")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals("Unknown model", vm.uiState.value.error)
            vm.dismissError()

            // A live pick of an available model switches.
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
            vm.uiState.first { it.status == ChatStatus.Ready }

            // Unknown provider: unknown-model error, never a credential error.
            vm.selectModel("no-such-provider", "gpt-4.1")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals("Unknown model", vm.uiState.value.error)
            vm.dismissError()

            // Static id the catalog has never carried, on a known provider.
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
        // Corrupt id the static catalog never carried: not "no longer
        // available for this account" — no availability error, the derived
        // replacement just runs.
        h.settings.setModelId("corrupt-model-id")
        val vm = h.newViewModel()

        val state = vm.uiState.first { it.status == ChatStatus.Ready }
        assertNull(state.error)
        assertEquals("gpt-4.1", state.selectedModel?.modelId)
        vm.closeForTest()
    }
    // ---- append-only persistence (pi's JSONL v4 mutation log, P0-2) ----

    /** Locates the session's .jsonl log file under the harness root. */
    private fun sessionLogFile(sessionId: String): File =
        tmpFolder.root.walkTopDown().filter { it.isFile && it.name == "$sessionId.jsonl" }.first()

    @Test
    fun persistFlow_isAppendOnly_snapshotsNeverRewriteTheFile() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        val afterFirst = sessionLogFile(sessionId).readText()
        assertTrue(afterFirst.startsWith("{\"kind\":\"header\",\"version\":4"))

        vm.exchange(h, "Again", "fine")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 4 }
        val afterSecond = sessionLogFile(sessionId).readText()

        // The second save only appended mutation lines: the first file's
        // bytes are a strict prefix of the grown log.
        assertTrue(afterSecond.length > afterFirst.length)
        assertEquals(afterFirst, afterSecond.substring(0, afterFirst.length))

        vm.closeForTest()
    }

    @Test
    fun navigationOnly_persistsLaneMoveWithoutNewEntries() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        val before = sessionLogFile(sessionId).readText()

        // Re-edit the user message: the leaf resets to root (a lane move)
        // without appending any entry.
        val userEntryId = vm.uiState.value.treeRows.first { it.isOnActivePath }.id
        vm.navigateToTreeEntry(userEntryId)
        vm.uiState.first { it.messages.isEmpty() && it.draft == "Hello" }
        vm.closeForTest()

        val after = sessionLogFile(sessionId).readText()
        assertTrue(after.length > before.length)
        assertTrue(after.startsWith(before))
        // Three appended lines: the navigation operation's durable record
        // pair (operation_started navigation + operation_finished) plus the
        // lane mutation back to the seed thinking_level_change entry (the
        // re-edit target's parent).
        assertEquals(before.count { it == '\n' } + 3, after.count { it == '\n' })
        val appended = after.substring(before.length)
        assertTrue(appended.contains("\"kind\":\"lane\""))
        assertTrue(appended.contains("\"type\":\"operation_started\""))
        assertTrue(appended.contains("\"kind\":\"navigation\""))
        assertTrue(appended.contains("\"type\":\"operation_finished\""))

        // A fresh store (process restart) replays the lane move: same
        // entries, empty active transcript, leaf on the seed thinking entry.
        val reloaded = h.sessionStore.load(sessionId)!!
        assertEquals(4, reloaded.entries.size) // seed model_change + thinking_level_change + user + assistant
        assertEquals(reloaded.entries[1].id, reloaded.leafId)
        assertTrue(reloaded.messages.isEmpty())
    }

    // ---- navigation trigger: branch summarization (audit P1-4/P1-5) ----

    @Test
    fun navigateWithSummarize_appendsNavigationRecordAndBranchSummary() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        // The summarization stack must exist before the agent is created;
        // auto-compaction is disabled so the queued response belongs to the
        // navigation summarization alone.
        h.disableCompaction = true
        h.installCompactionModels()
        h.summaryResponses.add(h.assistant("## Goal\nexplored the branch"))
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.exchange(h, "Again", "fine")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 4 }

        // Navigate back to the first assistant answer, summarizing the
        // abandoned second exchange.
        val assistantEntryId = vm.uiState.value.treeRows[1].id
        vm.navigateToTreeEntry(assistantEntryId, summarize = true)

        // The branch summary entry lands on the target branch and persists.
        waitUntil { h.sessionStore.load(sessionId)!!.entries.any { it is works.resolve.pathfinder.data.sessions.BranchSummaryEntry } }
        val saved = h.sessionStore.load(sessionId)!!
        val summary = saved.entries.filterIsInstance<works.resolve.pathfinder.data.sessions.BranchSummaryEntry>().single()
        assertEquals(assistantEntryId, summary.parentId)
        assertTrue(summary.summary.contains("explored the branch"))
        assertEquals(summary.id, saved.leafId)

        // The durable navigation operation: started (navigation intent with
        // the summarize flag and pre-minted summaryEntryId) + finished
        // completed.
        waitUntil {
            h.sessions.appendedRecords.count { it is works.resolve.pathfinder.data.sessions.LaneRecord.OperationFinishedRecord } >= 3
        }
        val records = h.sessions.appendedRecords.toList()
        val navStart = records.filterIsInstance<works.resolve.pathfinder.data.sessions.LaneRecord.OperationStartedRecord>()
            .last { it.intent.kind == works.resolve.pathfinder.data.sessions.OperationIntent.Kind.NAVIGATION }
        assertEquals(assistantEntryId, navStart.intent.payload["targetId"]!!.jsonPrimitiveContent)
        assertEquals("true", navStart.intent.payload["summarize"]!!.jsonPrimitiveContent)
        assertEquals(summary.id, navStart.intent.payload["summaryEntryId"]!!.jsonPrimitiveContent)
        val navFinish = records.filterIsInstance<works.resolve.pathfinder.data.sessions.LaneRecord.OperationFinishedRecord>().last()
        assertEquals(navStart.id, navFinish.runId)
        assertEquals(works.resolve.pathfinder.data.sessions.OperationOutcome.COMPLETED, navFinish.outcome)

        vm.closeForTest()
    }

    @Test
    fun load_classifiesSuspendedVsFinishedOperations() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        // A finished exchange: its run operation closed. Drain the async
        // record appends before reloading.
        vm.exchange(h, "Hello", "world")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        waitUntil { h.sessionStore.openOperations(sessionId, "main", null).isEmpty() }
        vm.closeForTest()

        val vm2 = h.newViewModel()
        val finished = vm2.uiState.first { it.status == ChatStatus.Ready && it.activeSessionId == sessionId }
        assertEquals(works.resolve.pathfinder.agent.LaneRecovery.Idle, finished.laneRecovery)
        vm2.closeForTest()

        // An unfinished operation record makes the same session suspend:
        // the run's operation_finished never persisted.
        h.sessionStore.appendRecord(
            sessionId,
            works.resolve.pathfinder.data.sessions.LaneRecord.OperationStartedRecord(
                id = "suspended-op",
                lane = "main",
                sourceLeafId = null,
                intent = works.resolve.pathfinder.data.sessions.OperationIntent.run(),
            ),
        )
        val vm3 = h.newViewModel()
        val suspended = vm3.uiState.first { it.status == ChatStatus.Ready && it.activeSessionId == sessionId }
        assertEquals(
            works.resolve.pathfinder.agent.LaneRecovery.Suspended(works.resolve.pathfinder.data.sessions.OperationIntent.Kind.RUN),
            suspended.laneRecovery,
        )
        vm3.closeForTest()
    }

    @Test
    fun load_classifiesCorruptRecordLogsViaReducer() = runTest(mainDispatcherRule.scheduler) {
        val h = Harness()
        val vm = h.newViewModel()
        vm.uiState.first { it.status == ChatStatus.NeedsConfiguration }
        vm.configure(apiKey = "k")
        vm.uiState.first { it.status == ChatStatus.Ready }
        val sessionId = vm.uiState.value.activeSessionId!!

        vm.exchange(h, "Hello", "world")
        vm.uiState.first { it.sessionSummaries.first { s -> s.id == sessionId }.messageCount == 2 }
        waitUntil { h.sessionStore.openOperations(sessionId, "main", null).isEmpty() }
        vm.closeForTest()

        // A record referencing an operation the log never opened: only the
        // reducer's full validation classifies this as corruption.
        h.sessionStore.appendRecord(
            sessionId,
            works.resolve.pathfinder.data.sessions.LaneRecord.AbortRequestedRecord(
                id = "ghost-abort",
                lane = "main",
                runId = "no-such-operation",
            ),
        )
        val vm2 = h.newViewModel()
        val corrupt = vm2.uiState.first { it.status == ChatStatus.Ready && it.activeSessionId == sessionId }
        assertEquals(
            works.resolve.pathfinder.agent.LaneRecovery.Corrupt(works.resolve.pathfinder.agent.RecordLogCorruptionReason.UNKNOWN_OPERATION),
            corrupt.laneRecovery,
        )
        vm2.closeForTest()
    }
}

