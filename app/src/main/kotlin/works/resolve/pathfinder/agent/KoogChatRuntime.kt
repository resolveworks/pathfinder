package works.resolve.pathfinder.agent

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientAPI
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import works.resolve.pathfinder.ai.providers.ProviderDescriptor
import works.resolve.pathfinder.data.credentials.CredentialStore
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Production [ChatRuntime] on Koog executor clients.
 *
 * Each prompt appends the user message to the conversation tree, then reads
 * the provider's API key from [credentials] (per prompt, so key rotation
 * takes effect on the next message), builds a Koog
 * [Prompt](ai.koog.prompt.Prompt) from the active branch's messages — the
 * product has no system prompt — and folds the
 * [executeStreaming](ai.koog.prompt.executor.clients.LLMClientAPI.executeStreaming)
 * frames into [ChatRuntimeState.streamingMessage] as a Koog
 * [Message.Assistant]. Only frames the product renders are mapped:
 * [StreamFrame.TextDelta]/[TextComplete] → text part,
 * [StreamFrame.ReasoningDelta]/[ReasoningComplete] → reasoning part,
 * [StreamFrame.End] → finish reason + response meta. Tool-call frames are
 * ignored (the product has no tool plumbing).
 *
 * Stream lifecycle and commit policy:
 * - the user message is committed (appended + [ChatRuntimeState.commitCount]
 *   bumped) as soon as it is accepted;
 * - on normal completion the final assistant message is appended to the tree
 *   and committed;
 * - on [abort][ChatRuntimeSession.abort] the in-flight stream is cancelled —
 *   cancellation propagates into the Koog HTTP call — and the partial
 *   assistant message is committed when it has rendered content (the user
 *   chose to stop; the transcript keeps what they saw);
 * - on failure the partial assistant message is discarded, the tree stays
 *   valid (the user message remains committed), and a fixed, user-safe error
 *   string is surfaced. Provider payloads, exception messages, and
 *   credentials are never surfaced or logged.
 *
 * Retry/timeout configuration: clients are built with each Koog client's own
 * default [ConnectionTimeoutConfig](ai.koog.prompt.executor.clients.ConnectionTimeoutConfig)
 * (request/socket timeout 900 s, connect timeout 60 s — the values Koog
 * ships), which suits long streaming responses; there is no Pathfinder retry
 * layer.
 *
 * Clients are constructed per prompt (they embed the current API key as
 * auth headers) over one shared Ktor/OkHttp [HttpClient] engine. Closing a
 * derived client does not close the shared engine; the engine's lifecycle is
 * tied to this runtime (see [close]).
 *
 * @param clientFactory Production default maps each provider id to its Koog
 *   client (`prompt-executor-*-client/.../<Provider>LLMClient.kt`) over a
 *   shared OkHttp engine. Injected only by tests.
 */
class KoogChatRuntime(
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    private val clientFactory: ((ProviderDescriptor, String) -> LLMClientAPI)? = null,
) : ChatRuntime, AutoCloseable {

    /** Shared OkHttp engine for the default factory (tests inject their own factory; no engine is built). */
    private val engine: HttpClient? = if (clientFactory == null) HttpClient(OkHttp) else null

    private val factory: (ProviderDescriptor, String) -> LLMClientAPI =
        clientFactory ?: { provider, apiKey ->
            KoogClients.create(provider, apiKey, KtorKoogHttpClient.Factory(engine!!))
        }

    override fun createSession(
        settings: ModelSettings,
        sessionId: String,
        conversation: Conversation,
    ): ChatRuntimeSession {
        val provider = requireNotNull(ProviderDescriptorsById[settings.providerId]) {
            "Unknown provider: ${settings.providerId}"
        }
        val model = requireNotNull(provider.model(settings.modelId)) {
            "Unknown model ${settings.modelId} for provider ${settings.providerId}"
        }
        return KoogChatSession(
            conversation = Conversation(conversation.entries, conversation.leafId),
            provider = provider,
            model = model.model,
            promptId = sessionId,
            credentials = credentials,
            scope = scope,
            clientFactory = factory,
        )
    }

    /** Closes the shared HTTP engine if this runtime owns it. No secrets involved. */
    override fun close() {
        engine?.close()
    }

    private companion object {
        val ProviderDescriptorsById: Map<String, ProviderDescriptor> =
            works.resolve.pathfinder.ai.providers.ProviderDescriptors.all.associateBy { it.id }
    }
}

/**
 * Maps a [ProviderDescriptor] id to its Koog executor client. Kept internal
 * so tests can assert the full mapping without going through the network.
 */
internal object KoogClients {

    /** Creates the Koog [LLMClientAPI] for [provider], authenticated with [apiKey]. */
    fun create(
        provider: ProviderDescriptor,
        apiKey: String,
        httpClientFactory: KoogHttpClient.Factory,
    ): LLMClientAPI = when (provider.id) {
        "anthropic" -> AnthropicLLMClient(apiKey, httpClientFactory = httpClientFactory)
        "openai" -> OpenAILLMClient(apiKey, httpClientFactory = httpClientFactory)
        "google" -> GoogleLLMClient(apiKey, httpClientFactory = httpClientFactory)
        "openrouter" -> OpenRouterLLMClient(apiKey, httpClientFactory = httpClientFactory)
        "mistral" -> MistralAILLMClient(apiKey, httpClientFactory = httpClientFactory)
        else -> throw IllegalArgumentException("Unknown provider: ${provider.id}")
    }
}

/**
 * Folds [StreamFrame]s into the frames the product renders. Text and
 * reasoning are accumulated separately — the UI renders them in distinct
 * surfaces, so part interleaving is not preserved. `*Complete` frames
 * replace the accumulated delta text (some providers send both).
 */
private class StreamingAssistantAccumulator {

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val reasoningSummary = StringBuilder()
    private var reasoningEncrypted: String? = null
    private var reasoningId: String? = null
    private var finishReason: String? = null
    private var metaInfo = ResponseMetaInfo.Empty

    fun onFrame(frame: StreamFrame) {
        when (frame) {
            is StreamFrame.TextDelta -> text.append(frame.text)
            is StreamFrame.TextComplete -> text.replaceEntire(frame.text)
            is StreamFrame.ReasoningDelta -> {
                frame.text?.let { reasoning.append(it) }
                frame.summary?.let { reasoningSummary.append(it) }
            }
            is StreamFrame.ReasoningComplete -> {
                reasoning.replaceEntire(frame.content.joinToString(""))
                reasoningSummary.replaceEntire(frame.summary?.joinToString("") ?: "")
                reasoningEncrypted = frame.encrypted
                reasoningId = frame.id
            }
            is StreamFrame.End -> {
                finishReason = frame.finishReason
                metaInfo = frame.metaInfo
            }
            // Tool-call frames are not rendered by the product; ignored.
            else -> {}
        }
    }

    /** True when any rendered content accumulated. */
    fun hasContent(): Boolean = text.isNotBlank() || reasoning.isNotBlank()

    /** Partial snapshot for [ChatRuntimeState.streamingMessage]. */
    fun snapshot(): Message.Assistant = assistant(ResponseMetaInfo.Empty, null)

    /** Final message with the terminal [StreamFrame.End] metadata applied. */
    fun finalMessage(): Message.Assistant = assistant(metaInfo, finishReason)

    private fun assistant(meta: ResponseMetaInfo, finish: String?): Message.Assistant {
        val parts = buildList {
            if (reasoning.isNotBlank()) {
                add(
                    MessagePart.Reasoning(
                        content = listOf(reasoning.toString()),
                        summary = reasoningSummary.toString().takeIf { it.isNotBlank() }?.let(::listOf),
                        encrypted = reasoningEncrypted,
                        id = reasoningId,
                    ),
                )
            }
            if (text.isNotBlank()) add(MessagePart.Text(text.toString()))
        }
        return Message.Assistant(parts = parts, metaInfo = meta, finishReason = finish)
    }

    private fun StringBuilder.replaceEntire(value: String) {
        clear()
        append(value)
    }
}

/** One live Koog-backed session; see [KoogChatRuntime] for the lifecycle contract. */
private class KoogChatSession(
    override var conversation: Conversation,
    private val provider: ProviderDescriptor,
    private val model: LLModel,
    private val promptId: String,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    private val clientFactory: (ProviderDescriptor, String) -> LLMClientAPI,
) : ChatRuntimeSession {

    private val _state = MutableStateFlow(
        ChatRuntimeState(committedMessages = conversation.activeMessages()),
    )
    override val state: StateFlow<ChatRuntimeState> = _state.asStateFlow()

    private var streamJob: Job? = null

    override fun prompt(text: String) {
        check(!state.value.isStreaming) { "A response is already streaming" }
        conversation = conversation.append(Message.User(text, RequestMetaInfo.Empty))
        _state.value = _state.value.copy(
            committedMessages = conversation.activeMessages(),
            streamingMessage = null,
            isStreaming = true,
            error = null,
            commitCount = _state.value.commitCount + 1,
        )
        streamJob = scope.launch { runPrompt() }
    }

    override fun abort() {
        streamJob?.cancel()
    }

    override fun replaceConversation(conversation: Conversation) {
        check(!state.value.isStreaming) { "Cannot replace the conversation while streaming" }
        this.conversation = conversation
        _state.value = _state.value.copy(committedMessages = conversation.activeMessages())
    }

    private suspend fun runPrompt() {
        val credential = try {
            credentials.read(provider.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fail(missingCredentialError())
            return
        }
        if (credential == null) {
            fail(missingCredentialError())
            return
        }

        val accumulator = StreamingAssistantAccumulator()
        val client = clientFactory(provider, credential.key)
        try {
            val prompt = Prompt(
                messages = conversation.activeMessages(),
                id = promptId,
            )
            client.executeStreaming(prompt, model).collect { frame ->
                accumulator.onFrame(frame)
                _state.value = _state.value.copy(streamingMessage = accumulator.snapshot())
            }
            commit(accumulator.finalMessage())
        } catch (error: CancellationException) {
            // Abort: keep what the user saw. No error surfaced.
            if (accumulator.hasContent()) commit(accumulator.finalMessage())
            else clearStreaming()
            throw error
        } catch (_: Exception) {
            // Provider payload and exception text stay out of the UI.
            clearStreaming()
            fail(requestFailedError())
        } finally {
            // Derived clients close without closing the shared engine.
            try {
                client.close()
            } catch (_: Exception) {
                // Nothing user-visible depends on close failing.
            }
        }
    }

    private fun commit(message: Message) {
        conversation = conversation.append(message)
        _state.value = _state.value.copy(
            committedMessages = conversation.activeMessages(),
            streamingMessage = null,
            isStreaming = false,
            commitCount = _state.value.commitCount + 1,
        )
    }

    private fun clearStreaming() {
        _state.value = _state.value.copy(streamingMessage = null, isStreaming = false)
    }

    private fun fail(errorText: String) {
        _state.value = _state.value.copy(
            streamingMessage = null,
            isStreaming = false,
            error = errorText,
        )
    }

    /** Fixed, user-safe error text; no provider payload or credential can leak through it. */
    private fun missingCredentialError(): String =
        "Add your ${provider.displayName} API key in Settings before sending a message."

    /** Fixed, user-safe error text; no provider payload or credential can leak through it. */
    private fun requestFailedError(): String =
        "The request to ${provider.displayName} failed. Check your connection and API key, then try again."
}
