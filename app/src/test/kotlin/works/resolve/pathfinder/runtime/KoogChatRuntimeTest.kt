package works.resolve.pathfinder.runtime

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientAPI
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.emitEnd
import ai.koog.prompt.streaming.emitReasoningComplete
import ai.koog.prompt.streaming.emitReasoningDelta
import ai.koog.prompt.streaming.emitTextDelta
import ai.koog.prompt.streaming.streamFrameFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.AfterTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import works.resolve.pathfinder.runtime.ProviderDescriptors
import works.resolve.pathfinder.data.credentials.Credential
import works.resolve.pathfinder.data.credentials.CredentialStore
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.diagnostics.DiagnosticEntry
import works.resolve.pathfinder.diagnostics.DiagnosticEvent
import works.resolve.pathfinder.diagnostics.Diagnostics

/**
 * Fake Koog client: [executeStreaming] returns a caller-controlled
 * [Flow] of [StreamFrame]s and records every prompt; nothing else is
 * implemented.
 */
private class FakeStreamingClient(
    private val frames: Flow<StreamFrame>,
    private val seenPrompts: MutableList<Prompt> = mutableListOf(),
) : LLMClientAPI {

    var closed = false
        private set

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = error("unused")

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
        seenPrompts += prompt
        return frames
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): Nothing = error("unused")

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override fun close() {
        closed = true
    }
}

/** In-memory mutable [CredentialStore]; credential values are only ever seen by the factories. */
private class FakeCredentialStore(
    initial: Map<String, Credential> = emptyMap(),
) : CredentialStore {
    private val stored = initial.toMutableMap()
    val writes = mutableListOf<Pair<String, Credential>>()
    override suspend fun read(providerId: String): Credential? = stored[providerId]
    override suspend fun set(providerId: String, credential: Credential) {
        stored[providerId] = credential
        writes += providerId to credential
    }
    override suspend fun list(): List<String> = stored.keys.toList()
    override suspend fun delete(providerId: String) {
        stored.remove(providerId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KoogChatRuntimeTest {

    private val anthropic = ProviderDescriptors.byId("anthropic")!!
    private val modelId = anthropic.models.first().id
    private val settings = ModelSettings(providerId = "anthropic", modelId = modelId)

    /** Recorded diagnostics entries; the sink is process-wide, so always restored. */
    private val entries = mutableListOf<DiagnosticEntry>()

    private fun installRecordingSink() {
        Diagnostics.install { entries += it }
    }

    @AfterTest
    fun restoreSink() {
        Diagnostics.install(null)
    }

    private fun runtime(
        keys: Map<String, String> = mapOf("anthropic" to "test-key"),
        seenKeys: MutableList<String> = mutableListOf(),
        seenProviders: MutableList<LLMProvider> = mutableListOf(),
        seenPrompts: MutableList<Prompt> = mutableListOf(),
        frames: () -> Flow<StreamFrame>,
    ): KoogChatRuntime {
        val store = FakeCredentialStore(keys.mapValues { (_, key) -> Credential.ApiKey(key) })
        return KoogChatRuntime(
            credentials = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clientFactory = { provider, apiKey ->
                seenKeys += apiKey
                seenProviders += provider
                FakeStreamingClient(frames(), seenPrompts)
            },
            oauthRefresher = { error("not reached") },
            codexClientFactory = { _, _ -> error("not reached") },
        )
    }

    private fun newSession(runtime: ChatRuntime) =
        runtime.createSession(settings, sessionId = "s1", conversation = Conversation(emptyList(), null))

    @Test
    fun promptStreamsDeltasAndCommitsFinalAssistantMessage() = runTest {
        val seenProviders = mutableListOf<LLMProvider>()
        val runtime = runtime(seenProviders = seenProviders) {
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

        // Client dispatch flows from the model's Koog provider, not from any
        // Pathfinder provider id.
        assertEquals(1, seenProviders.size)
        assertEquals(LLMProvider.Anthropic, seenProviders.single())
        assertFalse(state.isStreaming)
        assertNull(state.streamingMessage)
        assertNull(state.error)
        assertEquals(2, state.commitCount) // user message + assistant message
        assertEquals(2, state.committedMessages.size)
        val assistant = state.committedMessages.last() as Message.Assistant
        assertEquals("Hello", assistant.textContent())
        assertEquals("thinking", assistant.parts.filterIsInstance<MessagePart.Reasoning>().single().content.single())
        assertEquals("stop", assistant.finishReason)
        // Attribution: the committed message carries the requested model id.
        assertEquals(modelId, assistant.metaInfo.modelId)
        assertEquals(2, session.conversation.entries.size)
    }

    @Test
    fun summaryOnlyReasoningStillProducesReasoningPart() = runTest {
        // Hosted reasoning models (e.g. the ChatGPT Codex backend) stream
        // summaries only: ReasoningDelta(summary=...) plus a ReasoningComplete
        // whose content is empty. The reasoning part must survive with the
        // summary intact — the UI projection renders it when content is blank.
        val runtime = runtime {
            streamFrameFlow {
                emitReasoningDelta(id = "r", summary = "sum")
                emitReasoningComplete(id = "r", text = "", summary = "summary text")
                emitTextDelta("Answer")
                emitEnd(finishReason = "stop")
            }
        }
        val session = newSession(runtime)

        session.prompt("hi")
        val state = session.state.value

        assertNull(state.error)
        val assistant = state.committedMessages.last() as Message.Assistant
        val reasoning = assistant.parts.filterIsInstance<MessagePart.Reasoning>().single()
        assertTrue(reasoning.content.isEmpty(), "no raw reasoning for summary-only models")
        assertEquals(listOf("summary text"), reasoning.summary)
        assertEquals("Answer", assistant.textContent())
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
        installRecordingSink()
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
    fun httpFailureRecordsRequestFailedEntryWithStatusOnly() = runTest {
        installRecordingSink()
        val runtime = runtime {
            // Koog clients wrap transport failures in LLMClientException; the
            // HTTP exception's message embeds the error body — only the type
            // chain and status may reach the diagnostic entry.
            flow {
                throw LLMClientException(
                    clientName = "anthropic",
                    cause = KoogHttpClientException(
                        clientName = "anthropic",
                        statusCode = 401,
                        errorBody = "SECRET-ERROR-BODY",
                        message = "secret http message",
                    ),
                )
            }
        }
        val session = newSession(runtime)

        session.prompt("hi")
        val state = session.state.value

        // UI behavior unchanged: fixed, user-safe error string.
        assertFalse(state.isStreaming)
        val error = assertNotNull(state.error)
        assertTrue(error.startsWith("The request to Anthropic failed"))

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(DiagnosticEvent.CHAT_REQUEST_FAILED, entry.event)
        assertEquals(401, entry.httpStatus)
        // The rendered entry is fully sanitized: no error-body or message text.
        val rendered = entry.message()
        assertFalse("SECRET-ERROR-BODY" in rendered)
        assertFalse("secret http message" in rendered)
        assertTrue("chat.request_failed" in rendered)
    }

    @Test
    fun streamCompletingWithoutEndFrameRecordsStreamIncompleteEntry() = runTest {
        installRecordingSink()
        // A dropped connection: the flow completes normally, never emitting
        // the terminal End frame. Koog's requireEndFrame() turns that into a
        // failure; the runtime must not commit the truncated partial.
        val runtime = runtime {
            streamFrameFlow {
                emitTextDelta("par")
            }
        }
        val session = newSession(runtime)

        session.prompt("hi")
        val state = session.state.value

        assertFalse(state.isStreaming)
        val error = assertNotNull(state.error)
        assertTrue(error.startsWith("The request to Anthropic failed"))
        assertNull(state.streamingMessage) // truncated partial discarded
        assertEquals(1, state.commitCount) // only the user message
        assertEquals(1, session.conversation.entries.size)

        assertEquals(1, entries.size)
        assertSame(DiagnosticEvent.CHAT_STREAM_INCOMPLETE, entries.single().event)
    }

    @Test
    fun abortRecordsNoDiagnostics() = runTest {
        installRecordingSink()
        val runtime = runtime {
            streamFrameFlow {
                emitTextDelta("par")
                awaitCancellation()
            }
        }
        val session = newSession(runtime)

        session.prompt("hi")
        session.abort()

        assertNull(session.state.value.error)
        assertEquals(2, session.conversation.entries.size) // user + committed partial
        assertEquals(emptyList(), entries)
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
        // Aborted partials get attribution for free via the same commit path.
        assertEquals(
            modelId,
            (state.committedMessages.last() as Message.Assistant).metaInfo.modelId,
        )
        assertEquals(2, session.conversation.entries.size)
    }

    @Test
    fun streamCompletingWithoutEndFrameSurfacesErrorAndDiscardsPartial() = runTest {
        // A dropped connection: the flow completes normally, never emitting
        // the terminal End frame. Koog's requireEndFrame() turns that into a
        // failure; the runtime must not commit the truncated partial.
        val runtime = runtime {
            streamFrameFlow {
                emitTextDelta("par")
            }
        }
        val session = newSession(runtime)

        session.prompt("hi")
        val state = session.state.value

        assertFalse(state.isStreaming)
        val error = assertNotNull(state.error)
        assertTrue(error.startsWith("The request to Anthropic failed"))
        assertNull(state.streamingMessage) // truncated partial discarded
        assertEquals(1, state.commitCount) // only the user message
        assertEquals(1, session.conversation.entries.size)
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

    // --- live model and thinking selection (picker above the composer) ---

    @Test
    fun selectModelSwapsModelAndAppliesThinkingToTheNextPrompt() = runTest {
        val seenProviders = mutableListOf<LLMProvider>()
        val seenPrompts = mutableListOf<Prompt>()
        val openai = ProviderDescriptors.byId("openai")!!
        val openaiModel = openai.models.first {
            it.model.supports(ai.koog.prompt.llm.LLMCapability.Thinking)
        }
        val runtime = runtime(
            keys = mapOf("anthropic" to "test-key", "openai" to "test-key"),
            seenProviders = seenProviders,
            seenPrompts = seenPrompts,
            frames = {
                streamFrameFlow {
                    emitTextDelta("ok")
                    emitEnd(finishReason = "stop")
                }
            },
        )
        val session = newSession(runtime)

        session.selectModel(openaiModel, ThinkingOption.Effort(ReasoningEffort.MEDIUM))
        session.prompt("hi")

        // Client dispatch follows the swapped model's Koog provider; the
        // thinking option rides the prompt params verbatim.
        assertEquals(LLMProvider.OpenAI, seenProviders.single())
        val params = assertNotNull(seenPrompts.single().params as? OpenAIChatParams)
        assertEquals(ReasoningEffort.MEDIUM, params.reasoningEffort)
        // The transcript is untouched by the swap: only this prompt's tree.
        assertEquals(2, session.conversation.entries.size)
    }

    @Test
    fun selectModelAndSetThinkingWhileStreamingApplyToTheNextPrompt() = runTest {
        // pi's model-as-state semantics: a swap during an in-flight response
        // is never rejected — the stream finishes on the model it started
        // with, and the next prompt executes against the new selection.
        val seenProviders = mutableListOf<LLMProvider>()
        val openai = ProviderDescriptors.byId("openai")!!
        val openaiModel = openai.models.first()
        val runtime = runtime(
            keys = mapOf("anthropic" to "test-key", "openai" to "test-key"),
            seenProviders = seenProviders,
            frames = { flow { awaitCancellation() } },
        )
        val session = newSession(runtime)
        session.prompt("hi")

        assertTrue(session.state.value.isStreaming)
        // Neither call throws while streaming.
        session.selectModel(openaiModel, ThinkingOption.Default)
        session.setThinking(ThinkingOption.Off)
        assertTrue(session.state.value.isStreaming) // in-flight response unaffected

        session.abort()
        session.prompt("again")

        // The first request went to the session's original provider (it was
        // captured when the prompt started); the second to the swapped one.
        assertEquals(listOf(LLMProvider.Anthropic, LLMProvider.OpenAI), seenProviders)
    }

    @Test
    fun setThinkingAppliesToTheNextPrompt() = runTest {
        val seenPrompts = mutableListOf<Prompt>()
        val runtime = runtime(seenPrompts = seenPrompts, frames = {
            streamFrameFlow {
                emitEnd(finishReason = "stop")
            }
        })
        val session = newSession(runtime)

        session.setThinking(ThinkingOption.Off)
        session.prompt("hi")

        val params = assertNotNull(seenPrompts.single().params as? AnthropicParams)
        assertNotNull(params.thinking as? AnthropicThinking.Disabled)
    }

    @Test
    fun createSessionResolvesPersistedThinkingPreference() = runTest {
        val seenPrompts = mutableListOf<Prompt>()
        val runtime = runtime(seenPrompts = seenPrompts, frames = {
            streamFrameFlow {
                emitEnd(finishReason = "stop")
            }
        })
        val session = runtime.createSession(
            ModelSettings(
                providerId = "anthropic",
                modelId = modelId,
                thinkingPrefs = mapOf("anthropic/$modelId" to "off"),
            ),
            sessionId = "s1",
            conversation = Conversation(emptyList(), null),
        )

        session.prompt("hi")

        val params = assertNotNull(seenPrompts.single().params as? AnthropicParams)
        assertNotNull(params.thinking as? AnthropicThinking.Disabled)
    }

    // --- ChatGPT Codex provider (OAuth dispatch) ---

    private val codex = ProviderDescriptors.byId("openai-codex")!!
    private var now = 1_000_000L

    private fun oauthCredential(
        accessToken: String = "token-1",
        expiresInMillis: Long = 3_600_000,
    ) = Credential.ChatGptOAuth(
        accessToken = accessToken,
        refreshToken = "refresh-1",
        expiresAtEpochMillis = now + expiresInMillis,
        accountId = "acct-1",
    )

    private fun codexRuntime(
        store: FakeCredentialStore,
        seenTokens: MutableList<String>,
        refreshCalls: MutableList<Credential.ChatGptOAuth>,
        refreshed: suspend (Credential.ChatGptOAuth) -> Credential.ChatGptOAuth,
        frames: () -> Flow<StreamFrame>,
    ) = KoogChatRuntime(
        credentials = store,
        scope = CoroutineScope(Dispatchers.Unconfined),
        clientFactory = { _, _ -> error("not reached") },
        clock = { now },
        oauthRefresher = { credential ->
            refreshCalls += credential
            refreshed(credential)
        },
        codexClientFactory = { accessToken, _ ->
            seenTokens += accessToken
            FakeStreamingClient(frames())
        },
    )

    private fun codexSession(runtime: ChatRuntime) = runtime.createSession(
        ModelSettings(providerId = codex.id, modelId = codex.models.first().id),
        sessionId = "s1",
        conversation = Conversation(emptyList(), null),
    )

    @Test
    fun codexUsesStoredTokenWithoutRefreshWhenFarFromExpiry() = runTest {
        val store = FakeCredentialStore(mapOf(codex.id to oauthCredential(expiresInMillis = 3_600_000)))
        val seenTokens = mutableListOf<String>()
        val refreshCalls = mutableListOf<Credential.ChatGptOAuth>()
        val runtime = codexRuntime(
            store, seenTokens, refreshCalls,
            refreshed = { error("not reached") },
            frames = {
                streamFrameFlow {
                    emitTextDelta("hi")
                    emitEnd(finishReason = "stop")
                }
            },
        )
        val session = codexSession(runtime)

        session.prompt("hello")
        val state = session.state.value

        assertEquals(emptyList(), refreshCalls)
        assertEquals(listOf("token-1"), seenTokens)
        assertEquals("hi", (state.committedMessages.last() as Message.Assistant).textContent())
        assertEquals(emptyList(), store.writes) // nothing persisted
    }

    @Test
    fun codexRefreshesPersistsAndBuildsClientWithRefreshedToken() = runTest {
        val store = FakeCredentialStore(mapOf(codex.id to oauthCredential(expiresInMillis = 30_000)))
        val seenTokens = mutableListOf<String>()
        val refreshCalls = mutableListOf<Credential.ChatGptOAuth>()
        val runtime = codexRuntime(
            store, seenTokens, refreshCalls,
            refreshed = { old ->
                Credential.ChatGptOAuth(
                    accessToken = "token-2",
                    refreshToken = old.refreshToken,
                    expiresAtEpochMillis = now + 3_600_000,
                    accountId = old.accountId,
                )
            },
            frames = {
                streamFrameFlow {
                    emitEnd(finishReason = "stop")
                }
            },
        )
        val session = codexSession(runtime)

        session.prompt("hello")

        assertEquals(1, refreshCalls.size)
        assertEquals(listOf("token-2"), seenTokens) // built with the refreshed token
        assertEquals(1, store.writes.size) // refreshed credential persisted
        assertEquals(codex.id, store.writes.single().first)
        assertNull(session.state.value.error)
    }

    @Test
    fun codexRefreshFailureSurfacesUserSafeErrorWithoutTokens() = runTest {
        val store = FakeCredentialStore(mapOf(codex.id to oauthCredential(expiresInMillis = 30_000)))
        val seenTokens = mutableListOf<String>()
        val runtime = codexRuntime(
            store, seenTokens, mutableListOf(),
            refreshed = { throw RuntimeException("secret refresh payload") },
            frames = { error("not reached") },
        )
        val session = codexSession(runtime)

        session.prompt("hello")
        val state = session.state.value

        assertFalse(state.isStreaming)
        val error = assertNotNull(state.error)
        assertTrue("sign-in expired" in error, error) // fixed text
        assertFalse("secret refresh payload" in error)
        assertFalse("token-1" in error)
        assertEquals(emptyList(), seenTokens) // no client built
        assertEquals(emptyList(), store.writes) // nothing persisted
        assertEquals(1, session.conversation.entries.size) // user message stays
    }

    @Test
    fun codexWithWrongCredentialKindFailsWithMissingSignInError() = runTest {
        val store = FakeCredentialStore(mapOf(codex.id to Credential.ApiKey("test-key")))
        val runtime = codexRuntime(
            store, mutableListOf(), mutableListOf(),
            refreshed = { error("not reached") },
            frames = { error("not reached") },
        )
        val session = codexSession(runtime)

        session.prompt("hello")
        val state = session.state.value

        assertFalse(state.isStreaming)
        val error = assertNotNull(state.error)
        assertEquals("Sign in with ChatGPT in Settings before sending a message.", error)
        assertEquals(1, session.conversation.entries.size)
    }
}
