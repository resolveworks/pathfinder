package works.resolve.pathfinder.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.Description
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.CredentialInfo
import works.resolve.pathfinder.ai.auth.CredentialStore
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
import works.resolve.pathfinder.codingagent.core.SessionError
import works.resolve.pathfinder.codingagent.core.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.SessionInfo
import works.resolve.pathfinder.codingagent.core.SessionManager
import works.resolve.pathfinder.data.sessions.SessionSource
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.runtime.AgentFactory
import works.resolve.pathfinder.runtime.NativeAgentFactory
import works.resolve.pathfinder.runtime.catalogAuthResolver
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.tools.websearch.SearchProviderService

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class MainDispatcherRule : org.junit.rules.TestWatcher() {
    val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(scheduler)
    override fun starting(description: Description?) = Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description?) = Dispatchers.resetMain()
}

internal val testModel = Model(
    id = "glm-4.7",
    name = "GLM",
    api = "openai-completions",
    provider = "zai",
    baseUrl = "https://example.invalid"
)

internal class FakeCredentialStore : CredentialStore {
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

internal class FakeOAuthAuth(
    override val name: String = "Z.AI Account",
    override val loginLabel: String = "Sign in with a Z.AI account",
    override val isSubscription: Boolean = true
) : OAuthAuth {
    var loginFn: suspend (AuthInteraction) -> OAuthCredential = {
        OAuthCredential(access = "access-1", refresh = "refresh-1", expires = Long.MAX_VALUE)
    }
    override suspend fun login(interaction: AuthInteraction): OAuthCredential = loginFn(interaction)
    override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential
    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)
}

internal class FailingSettingsStore(private val delegate: SettingsStore) :
    SettingsStore by delegate {
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
internal class TestSessionSource(tmpFolder: TemporaryFolder) : SessionSource {
    // Created eagerly: denyWrites's read-only flip is a no-op on a
    // nonexistent dir (the manager would just mkdirs a writable one at
    // the first flush).
    val dir = File(tmpFolder.root, "sessions_${System.nanoTime()}").apply { mkdirs() }
    val managers = ConcurrentHashMap<String, SessionManager>()
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
        managers[manager.getSessionId()] = manager
        return manager
    }

    override suspend fun open(file: File): SessionManager? = SessionManager.open(
        file,
        idFactory = { "sess-" + nextId++ },
        ioDispatcher = Dispatchers.Unconfined
    )?.also { managers[it.getSessionId()] = it }

    override suspend fun list(): List<SessionInfo> {
        listCalls += 1
        if (failList) throw SessionError(SessionErrorCode.STORAGE, "list failed")
        return SessionManager.list(dir, ioDispatcher = Dispatchers.Unconfined)
    }

    /** Re-reads a session from disk; null while it has never been flushed. */
    suspend fun stored(id: String): SessionManager? =
        SessionManager.list(dir, ioDispatcher = Dispatchers.Unconfined)
            .firstOrNull { it.id == id }
            ?.let { open(it.path) }
}

/**
 * Test harness wiring real repositories/stores and scripted real Agents:
 * real implementations run above the storage boundaries and substitution
 * happens only there (in-memory credentials, real tempdir files, real
 * Models/resolver paths). The scripted [factory] and [rejectedModelIds]
 * are the only behavior fakes.
 */
internal class ChatHarness(tmpFolder: TemporaryFolder, testDispatcher: TestDispatcher) {
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
    val dataStoreScope = CoroutineScope(SupervisorJob() + testDispatcher)
    val settings = SettingsRepository(
        PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = {
                File(tmpFolder.root, "settings_${System.nanoTime()}.preferences_pb")
            }
        )
    )
    val settingsStore = FailingSettingsStore(settings)
    val sessions = TestSessionSource(tmpFolder)

    val scriptedStreams = ConcurrentLinkedQueue<Flow<AssistantMessageEvent>>()

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
            manager = sessionManager,
            tools = listOf(fakeWebSearchTool),
            retrySettings = settings.retry,
            compactionSettings = settings.compaction,
            models = switchModels
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

    fun assistant(text: String, stopReason: StopReason = StopReason.STOP, error: String? = null) =
        AssistantMessage(
            content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
            api = testModel.api,
            provider = testModel.provider,
            model = testModel.id,
            stopReason = stopReason,
            errorMessage = error,
            timestamp = System.nanoTime()
        )

    fun gatedStream(text: String, gate: CompletableDeferred<Unit>): Flow<AssistantMessageEvent> =
        flow {
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
 * Base for ChatViewModel behavior tests: provides the dispatcher rules and
 * live-harness tracking. [disposeHarnesses] tears every harness down even
 * when a test failed mid-body, so a still-alive ViewModel scope never
 * leaks into a later test.
 */
internal abstract class ChatHarnessTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val harnesses = CopyOnWriteArrayList<ChatHarness>()

    protected fun harness(): ChatHarness =
        ChatHarness(tmpFolder, mainDispatcherRule.testDispatcher).also { harnesses += it }

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
}

/**
 * Waits in real time (off the test scheduler) until [condition] holds:
 * the store's IO appends run on real Dispatchers.IO, which runTest's
 * virtual clock cannot see (it would idle-advance into the timeout).
 */
internal suspend fun waitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
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
internal suspend fun ChatViewModel.awaitState(
    timeoutMs: Long = 5_000,
    predicate: suspend (ChatUiState) -> Boolean
): ChatUiState = withTimeout(timeoutMs) { uiState.first { predicate(it) } }

internal suspend fun ChatViewModel.closeForTest() {
    val job = viewModelScope.coroutineContext[Job]!!
    job.cancel()
    job.join()
}

internal fun ChatViewModel.configure(modelId: String = "glm-4.7", apiKey: String = "") {
    if (apiKey.isNotEmpty()) saveProviderCredential("zai", apiKey, emptyMap())
    if (modelId != "glm-4.7") selectModel("zai", modelId)
}

/** Sends [text] with a scripted [reply] stream and waits for the committed transcript rows. */
internal suspend fun ChatViewModel.exchange(h: ChatHarness, text: String, reply: String) {
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

internal fun copilotCredential(availableModelIds: JsonElement? = null): OAuthCredential =
    OAuthCredential(
        access = "copilot-access",
        refresh = "copilot-refresh",
        expires = Long.MAX_VALUE,
        extras = availableModelIds?.let { mapOf("availableModelIds" to it) } ?: emptyMap()
    )

internal fun stringArray(vararg ids: String): JsonArray = JsonArray(
    ids.map {
        JsonPrimitive(it)
    }
)

internal val JsonElement.jsonPrimitiveContent: String get() = (this as JsonPrimitive).content

internal fun ChatViewModel.copilotModelOptions(): List<String> =
    uiState.value.modelOptions.filter { it.providerId == "github-copilot" }.map { it.modelId }
