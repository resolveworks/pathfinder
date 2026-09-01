package works.resolve.pathfinder.ui.chat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientAPI
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.emitEnd
import ai.koog.prompt.streaming.emitTextDelta
import ai.koog.prompt.streaming.streamFrameFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import works.resolve.pathfinder.data.credentials.Credential
import works.resolve.pathfinder.data.credentials.CredentialStore
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.ModelChangeEntry
import works.resolve.pathfinder.data.sessions.Session
import works.resolve.pathfinder.data.sessions.SessionRepository
import works.resolve.pathfinder.data.sessions.userMessage
import works.resolve.pathfinder.data.sessions.SessionSummary
import works.resolve.pathfinder.runtime.ChatRuntime
import works.resolve.pathfinder.runtime.ChatRuntimeSession
import works.resolve.pathfinder.runtime.ChatRuntimeState
import works.resolve.pathfinder.runtime.CodexOAuthClient
import works.resolve.pathfinder.runtime.KoogChatRuntime
import works.resolve.pathfinder.runtime.ProviderAuthKind
import works.resolve.pathfinder.runtime.ProviderDescriptors
import works.resolve.pathfinder.runtime.ThinkingOption

/**
 * ViewModel-level regression tests for model switching in an active chat,
 * through the real [KoogChatRuntime] with a recording client factory, so
 * the whole ViewModel⇄runtime seam is exercised.
 */
class ChatViewModelModelSwitchTest {

    private class FakeSettings(var settings: ModelSettings = ModelSettings()) : SettingsStore {
        override suspend fun currentSettings(): ModelSettings = settings
        override suspend fun setProviderId(providerId: String) {
            settings = settings.copy(providerId = providerId)
        }
        override suspend fun setModelId(modelId: String) {
            settings = settings.copy(modelId = modelId)
        }
        override suspend fun setActiveSessionId(sessionId: String?) {
            settings = settings.copy(activeSessionId = sessionId)
        }
        override suspend fun setShowThinking(showThinking: Boolean) {
            settings = settings.copy(showThinking = showThinking)
        }
        override suspend fun setEnabledModels(models: Set<String>?) {
            settings = settings.copy(enabledModels = models)
        }
        override suspend fun setThinkingPref(modelRef: String, label: String) {
            settings = settings.copy(thinkingPrefs = settings.thinkingPrefs + (modelRef to label))
        }
    }

    private class FakeSessions : SessionRepository {
        var nextId = 0
        val sessions = mutableListOf<Session>()
        override suspend fun create(title: String): Session {
            val session = Session(
                id = "s${nextId++}",
                title = title,
                createdAt = 0,
                updatedAt = 0,
                entries = emptyList(),
                leafId = null,
            )
            sessions += session
            return session
        }
        override suspend fun summaries(): List<SessionSummary> =
            sessions.map { SessionSummary(it.id, it.title, it.createdAt, it.updatedAt, it.entries.size) }
        override suspend fun load(id: String): Session? = sessions.firstOrNull { it.id == id }
        override suspend fun save(session: Session): Session {
            sessions.replaceAll { if (it.id == session.id) session else it }
            return session
        }
    }

    private class FakeCredentials(ids: Set<String>) : CredentialStore {
        private val stored = ids.associateWith { Credential.ApiKey("test-key") as Credential }.toMutableMap()
        override suspend fun read(providerId: String): Credential? = stored[providerId]
        override suspend fun set(providerId: String, credential: Credential) { stored[providerId] = credential }
        override suspend fun list(): List<String> = stored.keys.toList()
        override suspend fun delete(providerId: String) { stored.remove(providerId) }
    }

    /** Records the Koog provider of every constructed client, then streams the next queued frames. */
    private class RecordingRuntime(
        credentials: CredentialStore,
        val seenProviders: MutableList<LLMProvider>,
        framesQueue: ArrayDeque<() -> Flow<StreamFrame>>,
    ) : ChatRuntime by KoogChatRuntime(
        credentials = credentials,
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        clientFactory = { provider, _ ->
            seenProviders += provider
            val frames = framesQueue.removeFirstOrNull()
                ?: { streamFrameFlow { emitTextDelta("ok"); emitEnd(finishReason = "stop") } }
            object : LLMClientAPI {
                override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("unused")
                override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = frames()
                override suspend fun moderate(prompt: Prompt, model: LLModel): Nothing = error("unused")
                override fun llmProvider(): LLMProvider = LLMProvider.OpenAI
                override fun close() {}
            }
        },
        oauthRefresher = { error("not reached") },
        codexClientFactory = { _, _ -> error("not reached") },
    )

    private fun completedFrames(): Flow<StreamFrame> =
        streamFrameFlow { emitTextDelta("ok"); emitEnd(finishReason = "stop") }

    private fun neverEndingFrames(): Flow<StreamFrame> =
        kotlinx.coroutines.flow.flow<StreamFrame> { awaitCancellation() }

    private fun viewModel(
        runtime: ChatRuntime,
        credentials: CredentialStore,
        settings: SettingsStore,
        sessions: SessionRepository = FakeSessions(),
    ) = ChatViewModel(
        settingsRepository = settings,
        credentials = credentials,
        sessionStore = sessions,
        runtime = runtime,
        codexOAuthClient = CodexOAuthClient(io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)),
    ) to sessions

    private fun awaitReady(viewModel: ChatViewModel) {
        awaitCondition("Ready") { viewModel.uiState.value.status == ChatStatus.Ready }
    }

    private fun awaitIdle(viewModel: ChatViewModel, phase: String) {
        awaitCondition("idle ($phase)") { !viewModel.uiState.value.isStreaming }
    }

    /** Bounded wait on UI state; fails fast instead of hanging forever. */
    private fun awaitCondition(name: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                kotlin.test.fail("Timed out waiting for $name")
            }
            Thread.sleep(10)
        }
    }

    private fun openAiOption(): ModelOption {
        val openai = ProviderDescriptors.byId("openai")!!
        val model = openai.models.first()
        return ModelOption(
            providerId = openai.id,
            providerName = openai.displayName,
            modelId = model.id,
            name = model.displayName,
        )
    }

    /** Catalog options of one provider, name-sorted like the picker list. */
    private fun providerOptions(providerId: String): List<ModelOption> {
        val provider = ProviderDescriptors.byId(providerId)!!
        return provider.models
            .map { model ->
                ModelOption(
                    providerId = provider.id,
                    providerName = provider.displayName,
                    modelId = model.id,
                    name = model.displayName,
                )
            }
            .sortedBy { it.name }
    }

    @Test
    fun switchingModelBetweenPromptsRoutesNextPromptToNewProvider() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(providerId = "anthropic", modelId = anthropic.models.first().id),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val framesQueue = ArrayDeque<() -> Flow<StreamFrame>>()
            repeat(2) { framesQueue.add(::completedFrames) }
            val runtime = RecordingRuntime(credentials, seenProviders, framesQueue)
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitReady(viewModel)
            viewModel.onDraftChange("first message")
            viewModel.send()
            awaitIdle(viewModel, "after send1")

            viewModel.selectModel(openAiOption())
            Thread.sleep(100)

            viewModel.onDraftChange("second message")
            viewModel.send()
            awaitIdle(viewModel, "after send2")

            assertEquals(
                listOf(LLMProvider.Anthropic, LLMProvider.OpenAI),
                seenProviders,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * The reported bug, as a regression test: the user picks a model while
     * a response is streaming. The pick must not be dropped — the in-flight
     * response finishes on the model it started with, and the next request
     * routes to the newly picked provider (pi's model-as-state semantics).
     */
    @Test
    fun pickingModelWhileStreamingAppliesToTheNextRequest() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(providerId = "anthropic", modelId = anthropic.models.first().id),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val framesQueue = ArrayDeque<() -> Flow<StreamFrame>>()
            framesQueue.add(::neverEndingFrames) // prompt 1: streams until stopped
            framesQueue.add(::completedFrames)   // prompt 2: after the swap
            val runtime = RecordingRuntime(credentials, seenProviders, framesQueue)
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitReady(viewModel)
            viewModel.onDraftChange("first message")
            viewModel.send()
            assertTrue(viewModel.uiState.value.isStreaming)

            // The user picks OpenAI from the sheet while the response streams.
            viewModel.selectModel(openAiOption())
            Thread.sleep(100)
            assertTrue(viewModel.uiState.value.isStreaming) // in-flight response unaffected

            // The stream is stopped and the next message is sent.
            viewModel.stop()
            awaitIdle(viewModel, "after stop")
            viewModel.onDraftChange("second message")
            viewModel.send()
            awaitIdle(viewModel, "after send2")

            assertEquals(listOf<LLMProvider>(LLMProvider.Anthropic, LLMProvider.OpenAI), seenProviders)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Model choice is conversation state: the first prompt records the
     * effective initial model as a tree entry, and an idle pick appends (and
     * persists) a ModelChangeEntry for the new model.
     */
    @Test
    fun modelSelectionIsRecordedInTheSessionTree() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val anthropicModel = anthropic.models.first()
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(providerId = "anthropic", modelId = anthropicModel.id),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val framesQueue = ArrayDeque<() -> Flow<StreamFrame>>()
            framesQueue.add(::completedFrames)
            val runtime = RecordingRuntime(credentials, seenProviders, framesQueue)
            val sessions = FakeSessions()
            val (viewModel, _) = viewModel(runtime, credentials, settings, sessions)

            awaitReady(viewModel)
            viewModel.onDraftChange("first message")
            viewModel.send()
            awaitIdle(viewModel, "after send")

            // The first prompt made the session self-describing.
            awaitCondition("initial model entry persisted") {
                sessions.sessions.singleOrNull()?.entries
                    ?.filterIsInstance<ModelChangeEntry>()
                    ?.any { it.providerId == "anthropic" && it.modelId == anthropicModel.id } == true
            }

            // An idle pick appends the new model as conversation state.
            viewModel.selectModel(openAiOption())
            awaitCondition("picked model entry persisted") {
                sessions.sessions.singleOrNull()?.entries
                    ?.filterIsInstance<ModelChangeEntry>()
                    ?.any { it.providerId == "openai" } == true
            }

            assertEquals(
                listOf<LLMProvider>(LLMProvider.Anthropic),
                seenProviders,
                "no second prompt ran; only the initial model should have executed",
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Restoring a session seeds the picker (and next request) from the
     * branch's last ModelChangeEntry instead of the device default.
     */
    @Test
    fun restoringSessionSeedsModelFromBranchFold() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val openai = ProviderDescriptors.byId("openai")!!
            val openaiModel = openai.models.first()
            val sessions = FakeSessions()
            val storedId = kotlinx.coroutines.runBlocking {
                val stored = sessions.create("saved")
                val user = MessageEntry("m0", null, 1L, userMessage("hello"))
                val change = ModelChangeEntry("c0", "m0", 2L, openai.id, openaiModel.id)
                sessions.save(stored.copy(entries = listOf(user, change), leafId = change.id))
                stored.id
            }

            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(
                    providerId = "anthropic",
                    modelId = anthropic.models.first().id,
                    activeSessionId = storedId,
                ),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val framesQueue = ArrayDeque<() -> Flow<StreamFrame>>()
            framesQueue.add(::completedFrames)
            val runtime = RecordingRuntime(credentials, seenProviders, framesQueue)
            val (viewModel, _) = viewModel(runtime, credentials, settings, sessions)

            awaitReady(viewModel)
            assertEquals(openai.id, viewModel.uiState.value.selectedModel?.providerId)
            assertEquals(openaiModel.id, viewModel.uiState.value.selectedModel?.modelId)

            viewModel.onDraftChange("continue")
            viewModel.send()
            awaitIdle(viewModel, "after send")
            assertEquals(listOf<LLMProvider>(LLMProvider.OpenAI), seenProviders)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A branch that records a model the current catalog no longer offers is
     * stale session data: switching to it is rejected with an error instead
     * of silently continuing on the device default.
     */
    @Test
    fun switchingToSessionWithUnresolvableRecordedModelIsRejected() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(providerId = "anthropic", modelId = anthropic.models.first().id),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val framesQueue = ArrayDeque<() -> Flow<StreamFrame>>()
            val runtime = RecordingRuntime(credentials, seenProviders, framesQueue)
            val sessions = FakeSessions()
            val (viewModel, _) = viewModel(runtime, credentials, settings, sessions)
            awaitReady(viewModel)
            val activeSession = viewModel.uiState.value.activeSessionId!!

            val staleId = kotlinx.coroutines.runBlocking {
                val stored = sessions.create("stale")
                val change = ModelChangeEntry("c0", null, 1L, "ghost-provider", "ghost-model")
                sessions.save(stored.copy(entries = listOf(change), leafId = change.id))
                stored.id
            }

            viewModel.switchSession(staleId)
            awaitCondition("rejection error") { viewModel.uiState.value.error != null }
            assertEquals(activeSession, viewModel.uiState.value.activeSessionId)
            assertEquals(ChatStatus.Ready, viewModel.uiState.value.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A stored default naming no current catalog model is derived around for
     * the session (first model of a configured provider) but never silently
     * rewritten in persisted settings.
     */
    @Test
    fun unusableStoredDefaultIsDerivedWithoutPersisting() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(providerId = "ghost-provider", modelId = "ghost-model"),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val framesQueue = ArrayDeque<() -> Flow<StreamFrame>>()
            val runtime = RecordingRuntime(credentials, seenProviders, framesQueue)
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitReady(viewModel)
            assertEquals("anthropic", viewModel.uiState.value.selectedModel?.providerId)
            // The stored default is untouched; only an explicit pick writes it.
            assertEquals("ghost-provider", settings.settings.providerId)
            assertEquals("ghost-model", settings.settings.modelId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * The scope-selection invariant, as a scope edit: unchecking every
     * model of the selected model's provider re-derives the selection as
     * the first remaining scoped model — swapped on the live session and
     * recorded in the tree like a pick, but (a derived, not picked, model)
     * never written to persisted model settings.
     */
    @Test
    fun removingSelectedModelFromScopeReSelectsFirstScopedModel() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val settings = FakeSettings(
                ModelSettings(providerId = "anthropic", modelId = anthropic.models.first().id),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val runtime = RecordingRuntime(credentials, mutableListOf(), ArrayDeque())
            val sessions = FakeSessions()
            val (viewModel, _) = viewModel(runtime, credentials, settings, sessions)

            awaitReady(viewModel)
            assertEquals("anthropic", viewModel.uiState.value.selectedModel?.providerId)

            // The user scopes the picker down to OpenAI only; the first edit
            // materializes the uncurated default into an explicit set.
            providerOptions("anthropic").forEach { option ->
                viewModel.toggleModelScope(option, enabled = false)
            }

            val expected = providerOptions("openai").first()
            assertEquals(expected.providerId, viewModel.uiState.value.selectedModel?.providerId)
            assertEquals(expected.modelId, viewModel.uiState.value.selectedModel?.modelId)
            assertTrue(viewModel.uiState.value.scopedModels.none { it.providerId == "anthropic" })
            assertEquals(
                providerOptions("openai").map { "${it.providerId}/${it.modelId}" }.toSet(),
                settings.settings.enabledModels,
            )

            // The swap is conversation state…
            awaitCondition("scoped model change recorded") {
                sessions.sessions.singleOrNull()?.entries
                    ?.filterIsInstance<ModelChangeEntry>()
                    ?.any { it.providerId == expected.providerId && it.modelId == expected.modelId } == true
            }
            // …but only an explicit pick persists model settings.
            assertEquals("anthropic", settings.settings.providerId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * The picker can never be emptied by editing: unchecking the last
     * scoped model is rejected with an error, leaving the scope and the
     * selection untouched.
     */
    @Test
    fun uncheckingTheLastScopedModelIsRejected() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val settings = FakeSettings(
                ModelSettings(providerId = "anthropic", modelId = anthropic.models.first().id),
            )
            val credentials = FakeCredentials(setOf("anthropic"))
            val runtime = RecordingRuntime(credentials, mutableListOf(), ArrayDeque())
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitReady(viewModel)

            // Scope down to a single model; the invariant forces the
            // selection onto it.
            val survivor = providerOptions("anthropic").first()
            providerOptions("anthropic").filter { it.modelId != survivor.modelId }.forEach { option ->
                viewModel.toggleModelScope(option, enabled = false)
            }
            awaitCondition("scoped to one model") { viewModel.uiState.value.scopedModels.size == 1 }
            assertEquals(survivor.modelId, viewModel.uiState.value.selectedModel?.modelId)

            viewModel.toggleModelScope(survivor, enabled = false)
            Thread.sleep(100)

            kotlin.test.assertNotNull(viewModel.uiState.value.error)
            assertEquals(survivor.modelId, viewModel.uiState.value.selectedModel?.modelId)
            assertEquals(setOf("anthropic/${survivor.modelId}"), settings.settings.enabledModels)
            assertEquals(listOf(survivor.modelId), viewModel.uiState.value.scopedModels.map { it.modelId })
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A scope edit while streaming follows the pick-while-streaming rule:
     * the re-derived selection applies immediately (the in-flight response
     * is unaffected) and the tree entry defers to the next prompt.
     */
    @Test
    fun removingSelectedModelFromScopeWhileStreamingAppliesToNextPrompt() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(providerId = "anthropic", modelId = anthropic.models.first().id),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val framesQueue = ArrayDeque<() -> Flow<StreamFrame>>()
            framesQueue.add(::neverEndingFrames) // prompt 1: streams until stopped
            framesQueue.add(::completedFrames)   // prompt 2: after the swap
            val runtime = RecordingRuntime(credentials, seenProviders, framesQueue)
            val sessions = FakeSessions()
            val (viewModel, _) = viewModel(runtime, credentials, settings, sessions)

            awaitReady(viewModel)
            viewModel.onDraftChange("first message")
            viewModel.send()
            assertTrue(viewModel.uiState.value.isStreaming)

            providerOptions("anthropic").forEach { option ->
                viewModel.toggleModelScope(option, enabled = false)
            }

            val expected = providerOptions("openai").first()
            assertEquals(expected.providerId, viewModel.uiState.value.selectedModel?.providerId)
            assertTrue(viewModel.uiState.value.isStreaming) // in-flight response unaffected
            // The tree entry defers while streaming.
            assertTrue(
                sessions.sessions.singleOrNull()?.entries
                    ?.filterIsInstance<ModelChangeEntry>()
                    ?.none { it.providerId == "openai" } == true,
            )

            viewModel.stop()
            awaitIdle(viewModel, "after stop")
            viewModel.onDraftChange("second message")
            viewModel.send()
            awaitIdle(viewModel, "after send2")

            assertEquals(listOf<LLMProvider>(LLMProvider.Anthropic, LLMProvider.OpenAI), seenProviders)
            awaitCondition("deferred model change recorded") {
                sessions.sessions.singleOrNull()?.entries
                    ?.filterIsInstance<ModelChangeEntry>()
                    ?.any { it.providerId == "openai" } == true
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Scope policy at startup: a stored default outside the curated scope
     * is constrained to the first scoped model (derived, never persisted).
     */
    @Test
    fun outOfScopeStoredDefaultIsConstrainedToScopeAtStartup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val scoped = providerOptions("openai").first()
            val settings = FakeSettings(
                ModelSettings(
                    providerId = "anthropic",
                    modelId = anthropic.models.first().id,
                    enabledModels = setOf("${scoped.providerId}/${scoped.modelId}"),
                ),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val runtime = RecordingRuntime(credentials, mutableListOf(), ArrayDeque())
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitReady(viewModel)
            assertEquals(scoped.providerId, viewModel.uiState.value.selectedModel?.providerId)
            assertEquals(scoped.modelId, viewModel.uiState.value.selectedModel?.modelId)
            assertEquals(listOf(scoped.modelId), viewModel.uiState.value.scopedModels.map { it.modelId })
            // Derived, not picked: the stored default is untouched.
            assertEquals("anthropic", settings.settings.providerId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Scope policy on session switch: a branch that recorded a model
     * outside the curated scope is constrained to the first scoped model
     * (recorded and routed with the next prompt) instead of silently
     * running the out-of-scope model.
     */
    @Test
    fun switchingToSessionWithOutOfScopeRecordedModelConstrainsToScope() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val scoped = providerOptions("openai").first()
            val settings = FakeSettings(
                ModelSettings(
                    providerId = "anthropic",
                    modelId = anthropic.models.first().id,
                    enabledModels = setOf("${scoped.providerId}/${scoped.modelId}"),
                ),
            )
            val credentials = FakeCredentials(setOf("anthropic", "openai"))
            val framesQueue = ArrayDeque<() -> Flow<StreamFrame>>()
            framesQueue.add(::completedFrames)
            val seenProviders = mutableListOf<LLMProvider>()
            val runtime = RecordingRuntime(credentials, seenProviders, framesQueue)
            val sessions = FakeSessions()
            val (viewModel, _) = viewModel(runtime, credentials, settings, sessions)

            awaitReady(viewModel)

            val recordedId = kotlinx.coroutines.runBlocking {
                val stored = sessions.create("recorded")
                val user = MessageEntry("m0", null, 1L, userMessage("hello"))
                val change = ModelChangeEntry("c0", "m0", 2L, "anthropic", anthropic.models.first().id)
                sessions.save(stored.copy(entries = listOf(user, change), leafId = change.id))
                stored.id
            }

            viewModel.switchSession(recordedId)
            awaitCondition("switched") { viewModel.uiState.value.activeSessionId == recordedId }
            assertEquals(ChatStatus.Ready, viewModel.uiState.value.status)
            kotlin.test.assertNull(viewModel.uiState.value.error)
            assertEquals(scoped.providerId, viewModel.uiState.value.selectedModel?.providerId)
            assertEquals(scoped.modelId, viewModel.uiState.value.selectedModel?.modelId)

            viewModel.onDraftChange("continue")
            viewModel.send()
            awaitIdle(viewModel, "after send")
            assertEquals(listOf<LLMProvider>(LLMProvider.OpenAI), seenProviders)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A curated scope whose providers all lost their credentials degrades
     * to the uncurated default: every configured provider's models are
     * offered and the selection is derived from them — never left on a
     * model whose provider cannot run.
     */
    @Test
    fun scopeLeftUnusableByCredentialLossDegradesToAllConfiguredModels() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val settings = FakeSettings(
                ModelSettings(
                    providerId = "anthropic",
                    modelId = anthropic.models.first().id,
                    enabledModels = providerOptions("anthropic")
                        .map { "${it.providerId}/${it.modelId}" }
                        .toSet(),
                ),
            )
            // Anthropic's credential is gone; only OpenAI is configured.
            val credentials = FakeCredentials(setOf("openai"))
            val runtime = RecordingRuntime(credentials, mutableListOf(), ArrayDeque())
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitReady(viewModel)
            val expected = providerOptions("openai").first()
            assertEquals(expected.providerId, viewModel.uiState.value.selectedModel?.providerId)
            assertEquals(expected.modelId, viewModel.uiState.value.selectedModel?.modelId)
            assertTrue(viewModel.uiState.value.scopedModels.isNotEmpty())
            assertTrue(viewModel.uiState.value.scopedModels.all { it.providerId == "openai" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Persisted refs from an older catalog reject initialization. */
    @Test
    fun stalePersistedScopeRefFailsInitialization() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(enabledModels = setOf("ghost-provider/ghost-model")),
            )
            val credentials = FakeCredentials(setOf("anthropic"))
            val runtime = RecordingRuntime(credentials, seenProviders, ArrayDeque())
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitCondition("Failed") { viewModel.uiState.value.status == ChatStatus.Failed }
            kotlin.test.assertNotNull(viewModel.uiState.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** A persisted thinking label no longer offered rejects initialization. */
    @Test
    fun stalePersistedThinkingPrefFailsInitialization() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val anthropic = ProviderDescriptors.byId("anthropic")!!
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(
                ModelSettings(
                    providerId = "anthropic",
                    modelId = anthropic.models.first().id,
                    thinkingPrefs = mapOf("anthropic/${anthropic.models.first().id}" to "bogus"),
                ),
            )
            val credentials = FakeCredentials(setOf("anthropic"))
            val runtime = RecordingRuntime(credentials, seenProviders, ArrayDeque())
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitCondition("Failed") { viewModel.uiState.value.status == ChatStatus.Failed }
            kotlin.test.assertNotNull(viewModel.uiState.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * First-run completion runs through initialization itself: storing the
     * first credential derives the model, enters the chat, and — per the
     * settings-write policy — persists nothing until the user picks.
     */
    @Test
    fun storingFirstCredentialCompletesFirstRunThroughInitialization() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(ModelSettings())
            val credentials = FakeCredentials(emptySet())
            val runtime = RecordingRuntime(credentials, seenProviders, ArrayDeque())
            val (viewModel, _) = viewModel(runtime, credentials, settings)

            awaitCondition("NeedsConfiguration") {
                viewModel.uiState.value.status == ChatStatus.NeedsConfiguration
            }

            viewModel.saveProviderCredential(
                ProviderOption(
                    id = "anthropic",
                    name = "Anthropic",
                    authKind = ProviderAuthKind.ApiKey("Anthropic API key"),
                    configured = false,
                ),
                apiKey = "sk-test",
            )

            awaitReady(viewModel)
            assertEquals("anthropic", viewModel.uiState.value.selectedModel?.providerId)
            assertEquals(1, viewModel.uiState.value.credentialSuccessEpoch)
            // The derived default is not written; only an explicit pick is.
            assertEquals("", settings.settings.providerId)
            assertEquals("", settings.settings.modelId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * First-run completion restores a pre-existing session's recorded model
     * via the same branch fold as startup — no bespoke first-run model
     * choice bypassing the session's own history. The recorded model must
     * be scoped (its provider credentialed): the stored default is empty,
     * so only the fold can produce the selection.
     */
    @Test
    fun firstRunCompletionRestoresTheSessionRecordedModel() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val recorded = providerOptions("anthropic").first()
            val sessions = FakeSessions()
            val storedId = kotlinx.coroutines.runBlocking {
                val stored = sessions.create("saved")
                val user = MessageEntry("m0", null, 1L, userMessage("hello"))
                val change = ModelChangeEntry("c0", "m0", 2L, recorded.providerId, recorded.modelId)
                sessions.save(stored.copy(entries = listOf(user, change), leafId = change.id))
                stored.id
            }

            val seenProviders = mutableListOf<LLMProvider>()
            val settings = FakeSettings(ModelSettings(activeSessionId = storedId))
            val credentials = FakeCredentials(emptySet())
            val runtime = RecordingRuntime(credentials, seenProviders, ArrayDeque())
            val (viewModel, _) = viewModel(runtime, credentials, settings, sessions)

            awaitCondition("NeedsConfiguration") {
                viewModel.uiState.value.status == ChatStatus.NeedsConfiguration
            }

            viewModel.saveProviderCredential(
                ProviderOption(
                    id = "anthropic",
                    name = "Anthropic",
                    authKind = ProviderAuthKind.ApiKey("Anthropic API key"),
                    configured = false,
                ),
                apiKey = "sk-test",
            )

            awaitReady(viewModel)
            assertEquals(recorded.providerId, viewModel.uiState.value.selectedModel?.providerId)
            assertEquals(recorded.modelId, viewModel.uiState.value.selectedModel?.modelId)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
