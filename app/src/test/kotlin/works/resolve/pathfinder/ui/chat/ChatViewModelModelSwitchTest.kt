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
import works.resolve.pathfinder.data.sessions.Session
import works.resolve.pathfinder.data.sessions.SessionRepository
import works.resolve.pathfinder.data.sessions.SessionSummary
import works.resolve.pathfinder.runtime.ChatRuntime
import works.resolve.pathfinder.runtime.ChatRuntimeSession
import works.resolve.pathfinder.runtime.ChatRuntimeState
import works.resolve.pathfinder.runtime.CodexOAuthClient
import works.resolve.pathfinder.runtime.KoogChatRuntime
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

    private fun viewModel(runtime: ChatRuntime, credentials: CredentialStore, settings: SettingsStore) =
        ChatViewModel(
            settingsRepository = settings,
            credentials = credentials,
            sessionStore = FakeSessions(),
            runtime = runtime,
            codexOAuthClient = CodexOAuthClient(io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)),
        )

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
            val viewModel = viewModel(runtime, credentials, settings)

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
            val viewModel = viewModel(runtime, credentials, settings)

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
}
