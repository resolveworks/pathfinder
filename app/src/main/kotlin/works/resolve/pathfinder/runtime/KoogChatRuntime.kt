package works.resolve.pathfinder.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientAPI
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.dashscope.DashscopeLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import works.resolve.pathfinder.runtime.CodexLLMClients
import works.resolve.pathfinder.runtime.CodexOAuthClient
import works.resolve.pathfinder.runtime.ProviderAuthKind
import works.resolve.pathfinder.runtime.ProviderDescriptor
import works.resolve.pathfinder.runtime.ProviderDescriptors
import works.resolve.pathfinder.data.credentials.Credential
import works.resolve.pathfinder.data.credentials.CredentialStore
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.diagnostics.DiagnosticEvent
import works.resolve.pathfinder.diagnostics.Diagnostics

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
 *   string is surfaced. Failure includes a stream that completes without
 *   Koog's terminal [StreamFrame.End] frame — a dropped connection that not
 *   every provider client turns into an error — which
 *   [requireEndFrame][ai.koog.prompt.streaming.requireEndFrame] converts to
 *   an exception. Provider payloads, exception messages, and
 *   credentials are never surfaced in the UI or logged verbatim; instead
 *   every non-cancellation failure records a sanitized [Diagnostics]
 *   entry (exception type chain plus HTTP status only) under
 *   [DiagnosticEvent.CHAT_REQUEST_FAILED], with dropped streams
 *   ([IncompleteStreamException]) distinguished as
 *   [DiagnosticEvent.CHAT_STREAM_INCOMPLETE].
 *
 * Retry/timeout configuration: clients are built with each Koog client's own
 * default [ConnectionTimeoutConfig](ai.koog.prompt.executor.clients.ConnectionTimeoutConfig)
 * (request/socket timeout 900 s, connect timeout 60 s — the values Koog
 * ships), which suits long streaming responses; there is no Pathfinder retry
 * layer.
 *
 * Clients are constructed per prompt (they embed the current credential as
 * auth headers) over one shared Ktor/OkHttp [HttpClient] engine. Closing a
 * derived client does not close the shared engine; the engine's lifecycle is
 * tied to this runtime (see [close]).
 *
 * Two auth kinds, dispatched per provider ([ProviderAuthKind]): API-key
 * providers use [clientFactory]; the ChatGPT Codex provider refreshes its
 * OAuth access token before use (see [REFRESH_MARGIN_MILLIS]), persists the
 * refreshed credential, and builds its client via [codexClientFactory] with
 * the ChatGPT-backend prompt params (`runtime/CodexLLMClients.kt`).
 *
 * @param clientFactory Production default maps the Koog
 *   [ai.koog.prompt.llm.LLMProvider] of the selected model to its Koog client
 *   (`prompt-executor-*-client/.../<Provider>LLMClient.kt`) over a shared
 *   OkHttp engine. Injected only by tests.
 * @param clock Wall clock for OAuth token expiry checks (and the default
 *   refresher). Injected only by tests.
 * @param oauthRefresher Production default refreshes via [CodexOAuthClient]
 *   over the shared engine. Injected only by tests.
 * @param codexClientFactory Production default builds the ChatGPT-backend
 *   client via [CodexLLMClients.create] over the shared engine. Injected
 *   only by tests.
 */
class KoogChatRuntime(
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    private val clientFactory: ((LLMProvider, String) -> LLMClientAPI)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val oauthRefresher: (suspend (Credential.ChatGptOAuth) -> Credential.ChatGptOAuth)? = null,
    private val codexClientFactory: ((accessToken: String, accountId: String) -> LLMClientAPI)? = null,
) : ChatRuntime, AutoCloseable {

    /**
     * Shared OkHttp engine, built lazily on first use of any production
     * default factory (tests that inject all of them never build one).
     */
    private val lazyEngine = lazy { HttpClient(OkHttp) }

    private val factory: (LLMProvider, String) -> LLMClientAPI =
        clientFactory ?: { provider, apiKey ->
            KoogClients.create(provider, apiKey, KtorKoogHttpClient.Factory(lazyEngine.value))
        }

    private val refresher: suspend (Credential.ChatGptOAuth) -> Credential.ChatGptOAuth =
        oauthRefresher ?: { credential ->
            val tokens = CodexOAuthClient(lazyEngine.value, clock).refresh(credential.refreshToken)
            Credential.ChatGptOAuth(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtEpochMillis = tokens.expiresAtEpochMillis,
                accountId = tokens.accountId,
            )
        }

    private val codexFactory: (String, String) -> LLMClientAPI =
        codexClientFactory ?: { accessToken, accountId ->
            CodexLLMClients.create(accessToken, accountId, KtorKoogHttpClient.Factory(lazyEngine.value))
        }

    override fun createSession(
        settings: ModelSettings,
        sessionId: String,
        conversation: Conversation,
    ): ChatRuntimeSession {
        val provider = requireNotNull(ProviderDescriptors.byId(settings.providerId)) {
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
            oauthRefresher = refresher,
            codexClientFactory = codexFactory,
            clock = clock,
        )
    }

    /** Closes the shared HTTP engine if it was ever built. No secrets involved. */
    override fun close() {
        if (lazyEngine.isInitialized()) lazyEngine.value.close()
    }
}

/**
 * Refresh the access token when less than this remains until expiry — margin
 * for request latency and clock skew between device and OpenAI's auth servers.
 */
private const val REFRESH_MARGIN_MILLIS = 60_000L

/**
 * Maps a Koog [LLMProvider] to its executor client — the providers the
 * product ships, and only those. Kept internal so tests can assert the full
 * mapping without going through the network.
 *
 * Three kinds of entry: first-party Koog clients (Anthropic, OpenAI, Google,
 * OpenRouter, MistralAI, DeepSeek, DashScope); and coding-plan endpoints without a Koog
 * client module, executed by Koog's stock OpenAI/Anthropic clients against
 * their coding base URLs (Z.AI is OpenAI chat-completions protocol; Kimi
 * speaks the Anthropic Messages protocol, requiring a model version map —
 * see [KimiModels]).
 */
internal object KoogClients {

    /** Base URL of the Z.AI coding plan (pi: `providers/zai.ts`). */
    internal const val ZAI_BASE_URL = "https://api.z.ai/api/coding/paas/v4"

    /** Base URL of Kimi For Coding (pi: `providers/kimi-coding.ts`). */
    internal const val KIMI_BASE_URL = "https://api.kimi.com/coding"

    /** Creates the Koog [LLMClientAPI] for [provider], authenticated with [apiKey]. */
    fun create(
        provider: LLMProvider,
        apiKey: String,
        httpClientFactory: KoogHttpClient.Factory,
    ): LLMClientAPI = when (provider) {
        LLMProvider.Anthropic -> AnthropicLLMClient(apiKey, httpClientFactory = httpClientFactory)
        LLMProvider.OpenAI -> OpenAILLMClient(apiKey, httpClientFactory = httpClientFactory)
        LLMProvider.Google -> GoogleLLMClient(apiKey, httpClientFactory = httpClientFactory)
        LLMProvider.OpenRouter -> OpenRouterLLMClient(apiKey, httpClientFactory = httpClientFactory)
        LLMProvider.MistralAI -> MistralAILLMClient(apiKey, httpClientFactory = httpClientFactory)
        LLMProvider.DeepSeek -> DeepSeekLLMClient(apiKey, httpClientFactory = httpClientFactory)
        LLMProvider.Alibaba -> DashscopeLLMClient(apiKey, httpClientFactory = httpClientFactory)
        LLMProvider.ZhipuAI -> OpenAILLMClient(
            apiKey,
            settings = OpenAIClientSettings(
                baseUrl = ZAI_BASE_URL,
                // The coding endpoint serves completions at its API root, not
                // under "v1/" like api.openai.com.
                chatCompletionsPath = "chat/completions",
            ),
            httpClientFactory = httpClientFactory,
        )
        KimiProvider -> AnthropicLLMClient(
            apiKey,
            settings = AnthropicClientSettings(
                baseUrl = KIMI_BASE_URL,
                modelVersionsMap = KimiModels.versionMap,
            ),
            httpClientFactory = httpClientFactory,
        )
        else -> throw IllegalArgumentException("Unsupported provider: ${provider.id}")
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
            is StreamFrame.ToolCallDelta -> {}
            is StreamFrame.ToolCallComplete -> {}
        }
    }

    /** True when any rendered content accumulated. */
    fun hasContent(): Boolean = text.isNotBlank() || reasoning.isNotBlank() || reasoningSummary.isNotBlank()

    /** Partial snapshot for [ChatRuntimeState.streamingMessage]. */
    fun snapshot(): Message.Assistant = assistant(ResponseMetaInfo.Empty, null)

    /** Final message with the terminal [StreamFrame.End] metadata applied. */
    fun finalMessage(): Message.Assistant = assistant(metaInfo, finishReason)

    private fun assistant(meta: ResponseMetaInfo, finish: String?): Message.Assistant {
        val parts = buildList {
            // Hosted reasoning models (the ChatGPT Codex backend among them)
            // stream summaries only; raw content is the richer form some
            // other providers supply. The message records both faithfully;
            // display policy (summary fallback) lives in the UI projection.
            if (reasoning.isNotBlank() || reasoningSummary.isNotBlank()) {
                add(
                    MessagePart.Reasoning(
                        content = if (reasoning.isBlank()) emptyList() else listOf(reasoning.toString()),
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

/**
 * True when [IncompleteStreamException] appears anywhere in the cause chain.
 * `requireEndFrame()` is applied in [KoogChatRuntime] itself, so the exception
 * normally arrives unwrapped, but provider clients can wrap stream failures
 * of their own — hence the defensive walk.
 */
private fun Throwable.hasIncompleteStreamCause(): Boolean =
    generateSequence(this) { current -> current.cause }
        .take(8)
        .any { it is IncompleteStreamException }

/** One live Koog-backed session; see [KoogChatRuntime] for the lifecycle contract. */
private class KoogChatSession(
    override var conversation: Conversation,
    private val provider: ProviderDescriptor,
    private val model: LLModel,
    private val promptId: String,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    private val clientFactory: (LLMProvider, String) -> LLMClientAPI,
    private val oauthRefresher: suspend (Credential.ChatGptOAuth) -> Credential.ChatGptOAuth,
    private val codexClientFactory: (String, String) -> LLMClientAPI,
    private val clock: () -> Long,
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

        val client: LLMClientAPI
        val prompt = when (provider.authKind) {
            is ProviderAuthKind.ApiKey -> {
                val apiKey = (credential as? Credential.ApiKey)?.key ?: run {
                    fail(missingCredentialError())
                    return
                }
                client = clientFactory(model.provider, apiKey)
                Prompt(messages = conversation.activeMessages(), id = promptId)
            }

            ProviderAuthKind.ChatGptSignIn -> {
                val oauth = credential as? Credential.ChatGptOAuth ?: run {
                    fail(missingCredentialError())
                    return
                }
                val tokens = if (oauth.expiresAtEpochMillis - clock() < REFRESH_MARGIN_MILLIS) {
                    try {
                        val refreshed = oauthRefresher(oauth)
                        credentials.set(provider.id, refreshed)
                        refreshed
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Token-refresh failures are never surfaced verbatim.
                        fail(signInExpiredError())
                        return
                    }
                } else {
                    oauth
                }
                client = codexClientFactory(tokens.accessToken, tokens.accountId)
                Prompt(
                    messages = conversation.activeMessages(),
                    id = promptId,
                    params = CodexLLMClients.promptParams(promptId),
                )
            }
        }

        val accumulator = StreamingAssistantAccumulator()
        try {
            client.executeStreaming(prompt, model)
                // Not every provider client applies this itself; a stream that
                // ends without [StreamFrame.End] is a dropped connection.
                .requireEndFrame()
                .collect { frame ->
                    accumulator.onFrame(frame)
                    _state.value = _state.value.copy(streamingMessage = accumulator.snapshot())
                }
            commit(accumulator.finalMessage())
        } catch (error: CancellationException) {
            // Abort: keep what the user saw. No error surfaced, no diagnostics
            // (cancellation is a user action, not a failure).
            if (accumulator.hasContent()) commit(accumulator.finalMessage())
            else clearStreaming()
            throw error
        } catch (error: Exception) {
            // Provider payload and exception text stay out of the UI; the
            // diagnostics entry carries only the sanitized type chain and
            // HTTP status. A dropped connection — the stream completing
            // without the terminal End frame — is classified separately so it
            // can be told apart from other request failures; requireEndFrame
            // is applied here rather than inside the Koog client, but the
            // cause chain is walked defensively anyway.
            Diagnostics.failure(
                if (error.hasIncompleteStreamCause()) {
                    DiagnosticEvent.CHAT_STREAM_INCOMPLETE
                } else {
                    DiagnosticEvent.CHAT_REQUEST_FAILED
                },
                error,
            )
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
    private fun missingCredentialError(): String = when (provider.authKind) {
        is ProviderAuthKind.ApiKey ->
            "Add your ${provider.displayName} API key in Settings before sending a message."

        ProviderAuthKind.ChatGptSignIn ->
            "Sign in with ChatGPT in Settings before sending a message."
    }

    /** Fixed, user-safe error text; no provider payload or credential can leak through it. */
    private fun signInExpiredError(): String =
        "Your ChatGPT sign-in expired. Sign in again in Settings."

    /** Fixed, user-safe error text; no provider payload or credential can leak through it. */
    private fun requestFailedError(): String = when (provider.authKind) {
        is ProviderAuthKind.ApiKey ->
            "The request to ${provider.displayName} failed. Check your connection and API key, then try again."

        ProviderAuthKind.ChatGptSignIn ->
            "The request to ${provider.displayName} failed. Check your connection and ChatGPT sign-in, then try again."
    }
}
