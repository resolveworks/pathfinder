package works.resolve.pathfinder.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientAPI
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.emitEnd
import ai.koog.prompt.streaming.emitReasoningDelta
import ai.koog.prompt.streaming.emitTextDelta
import ai.koog.prompt.streaming.streamFrameFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import works.resolve.pathfinder.ai.providers.ProviderDescriptor
import works.resolve.pathfinder.ai.providers.ProviderDescriptors
import works.resolve.pathfinder.data.credentials.ApiKeyCredential
import works.resolve.pathfinder.data.credentials.CredentialStore
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Fake Koog client: [executeStreaming] returns a caller-controlled
 * [Flow] of [StreamFrame]s; nothing else is implemented.
 */
private class FakeStreamingClient(
    private val frames: Flow<StreamFrame>,
) : LLMClientAPI {

    var closed = false
        private set

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = error("unused")

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = frames

    override suspend fun moderate(prompt: Prompt, model: LLModel): Nothing = error("unused")

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override fun close() {
        closed = true
    }
}

/** In-memory [CredentialStore]; the key is only ever seen by the factory. */
private class FakeCredentialStore(
    private val keys: Map<String, String> = emptyMap(),
) : CredentialStore {
    override suspend fun read(providerId: String): ApiKeyCredential? = keys[providerId]?.let(::ApiKeyCredential)
    override suspend fun set(providerId: String, credential: ApiKeyCredential) = error("unused")
    override suspend fun list(): List<String> = keys.keys.toList()
    override suspend fun delete(providerId: String) = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class KoogChatRuntimeTest {

    private val anthropic = ProviderDescriptors.byId("anthropic")!!
    private val modelId = anthropic.models.first().id
    private val settings = ModelSettings(providerId = "anthropic", modelId = modelId)

    private fun runtime(
        keys: Map<String, String> = mapOf("anthropic" to "test-key"),
        seenKeys: MutableList<String> = mutableListOf(),
        frames: () -> Flow<StreamFrame>,
    ) = KoogChatRuntime(
        credentials = FakeCredentialStore(keys),
        scope = CoroutineScope(Dispatchers.Unconfined),
        clientFactory = { _, apiKey ->
            seenKeys += apiKey
            FakeStreamingClient(frames())
        },
    )

    private fun newSession(runtime: ChatRuntime) =
        runtime.createSession(settings, sessionId = "s1", conversation = Conversation(emptyList(), null))

    @Test
    fun promptStreamsDeltasAndCommitsFinalAssistantMessage() = runTest {
        val runtime = runtime {
            streamFrameFlow {
                emitReasoningDelta(id = "r", text = "thinking")
                emitTextDelta("Hel")
                emitTextDelta("lo")
                emitEnd(finishReason = "stop")
            }
        }
        val session = newSession(runtime)

        session.prompt("hi")
        val state = session.state.value

        assertFalse(state.isStreaming)
        assertNull(state.streamingMessage)
        assertNull(state.error)
        assertEquals(2, state.commitCount) // user message + assistant message
        assertEquals(2, state.committedMessages.size)
        val assistant = state.committedMessages.last() as Message.Assistant
        assertEquals("Hello", assistant.textContent())
        assertEquals("thinking", assistant.parts.filterIsInstance<MessagePart.Reasoning>().single().content.single())
        assertEquals("stop", assistant.finishReason)
        assertEquals(2, session.conversation.entries.size)
    }

    @Test
    fun missingCredentialSurfacesUserSafeErrorAndKeepsUserMessage() = runTest {
        val runtime = runtime(keys = emptyMap(), frames = { error("not reached") })
        val session = newSession(runtime)

        session.prompt("hi")
        val state = session.state.value

        assertFalse(state.isStreaming)
        val error = assertNotNull(state.error)
        assertTrue("Anthropic API key" in error)
        assertFalse("test-key" in error)
        assertEquals(1, state.commitCount) // user message committed, no assistant
        assertEquals(1, session.conversation.entries.size)
    }

    @Test
    fun streamFailureSurfacesFixedErrorWithoutProviderPayload() = runTest {
        val runtime = runtime {
            streamFrameFlow {
                emitTextDelta("par")
                throw RuntimeException("secret provider payload")
            }
        }
        val session = newSession(runtime)

        session.prompt("hi")
        val state = session.state.value

        assertFalse(state.isStreaming)
        val error = state.error
        assertNotNull(error)
        assertFalse("secret provider payload" in error)
        assertTrue(error.startsWith("The request to Anthropic failed")) // fixed text
        assertNull(state.streamingMessage) // partial discarded on failure
        assertEquals(1, session.conversation.entries.size) // user message stays
    }

    @Test
    fun abortCancelsStreamAndCommitsPartialContent() = runTest {
        val runtime = runtime {
            streamFrameFlow {
                emitTextDelta("par")
                awaitCancellation()
            }
        }
        val session = newSession(runtime)

        session.prompt("hi")
        assertTrue(session.state.value.isStreaming)
        assertEquals("par", session.state.value.streamingMessage?.textContent())

        session.abort()
        val state = session.state.value

        assertFalse(state.isStreaming)
        assertNull(state.error)
        assertEquals(2, state.commitCount) // user + committed partial
        assertEquals("par", (state.committedMessages.last() as Message.Assistant).textContent())
        assertEquals(2, session.conversation.entries.size)
    }

    @Test
    fun createSessionRejectsUnknownProviderAndModel() = runTest {
        val runtime = runtime(frames = { error("not reached") })

        assertFailsWith<IllegalArgumentException> {
            runtime.createSession(
                ModelSettings(providerId = "nope", modelId = modelId),
                sessionId = "s1",
                conversation = Conversation(emptyList(), null),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            runtime.createSession(
                ModelSettings(providerId = "anthropic", modelId = "nope"),
                sessionId = "s1",
                conversation = Conversation(emptyList(), null),
            )
        }
    }
}
