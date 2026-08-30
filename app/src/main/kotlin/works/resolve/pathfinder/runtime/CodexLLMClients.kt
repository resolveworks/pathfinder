package works.resolve.pathfinder.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.LLMClientAPI
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass

/**
 * Builds the Koog LLM client and per-prompt params for the ChatGPT Codex backend.
 *
 * ChatGPT OAuth users talk to the ChatGPT backend, which speaks the OpenAI
 * Responses API with extra headers and a slightly stricter request contract.
 * No Koog fork is needed: everything below is assembled from Koog's public API —
 * [OpenAILLMClient] and [OpenAIClientSettings] from
 * `prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/.../openai/OpenAILLMClient.kt`,
 * the public [AbstractOpenAILLMClient.createConfiguredHttpClient] factory
 * (base URL, `Authorization: Bearer <accessToken>`, Koog's Json, timeouts),
 * and the [OpenAIResponsesParams] surface (including `additionalProperties`,
 * flattened into the top-level request JSON by
 * `OpenAIResponsesAPIRequestSerializer`, which is how `instructions` gets in).
 *
 * The behavioral reference is pi's working client
 * `packages/ai/src/api/openai-codex-responses.ts`
 * (`DEFAULT_CODEX_BASE_URL`, `buildBaseCodexHeaders`, `buildSSEHeaders`,
 * and the request body: `store: false`, `instructions`,
 * `include: ["reasoning.encrypted_content"]`, `prompt_cache_key`).
 */
public object CodexLLMClients {

    private const val CLIENT_NAME = "OpenAICodexLLMClient"

    /**
     * Fallback instructions for the ChatGPT backend.
     *
     * pi sends `context.systemPrompt || "You are a helpful assistant."`
     * (`openai-codex-responses.ts`, request body builder); Pathfinder has no
     * system prompt (see `runtime/KoogChatRuntime.kt`), so the fallback is always used.
     */
    private const val INSTRUCTIONS = "You are a helpful assistant."

    /**
     * Creates an [LLMClientAPI] for the ChatGPT Codex backend.
     *
     * The base HTTP client is built by [AbstractOpenAILLMClient.createConfiguredHttpClient]
     * (Bearer token, base URL, timeouts, Koog Json) and wrapped in a decorator that adds
     * the ChatGPT-backend headers (`chatgpt-account-id`, `originator`, `OpenAI-Beta`)
     * to every request — mirroring pi's `buildBaseCodexHeaders`/`buildSSEHeaders`.
     *
     * Codex currently omits `Content-Type` from successful streaming responses.
     * Ktor's SSE plugin rejects those responses before reading their valid SSE body,
     * so [ChatGPTBackendHeaderDecorator] streams through [KoogHttpClient.lines]
     * and decodes the `data:` records just as pi's fetch-based parser does.
     */
    public fun create(
        accessToken: String,
        accountId: String,
        httpClientFactory: KoogHttpClient.Factory,
    ): LLMClientAPI {
        val settings = OpenAIClientSettings(
            baseUrl = "https://chatgpt.com/backend-api",
            responsesAPIPath = "codex/responses",
        )
        val base = AbstractOpenAILLMClient.createConfiguredHttpClient(
            apiKey = accessToken,
            settings = settings,
            httpClientFactory = httpClientFactory,
            clientName = CLIENT_NAME,
        )
        return OpenAILLMClient(
            settings = settings,
            httpClient = ChatGPTBackendHeaderDecorator(
                delegate = base,
                extraHeaders = mapOf(
                    "chatgpt-account-id" to accountId,
                    "originator" to "pathfinder",
                    "OpenAI-Beta" to "responses=experimental",
                ),
            ),
        )
    }

    /**
     * Per-prompt params for the ChatGPT backend.
     *
     * `store = false` is REQUIRED — the ChatGPT backend rejects requests with
     * store true/default ("Store must be set to false"; see pi's
     * `openai-codex-responses.ts`). `instructions` travels through
     * `additionalProperties` because Koog never populates the request's
     * `instructions` field from params; the responses request serializer
     * flattens additional properties into the top-level JSON.
     */
    public fun promptParams(sessionId: String): LLMParams = OpenAIResponsesParams(
        store = false,
        include = listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT),
        promptCacheKey = sessionId,
        additionalProperties = mapOf<String, JsonElement>(
            "instructions" to JsonPrimitive(INSTRUCTIONS),
        ),
    )

    /**
     * [KoogHttpClient] decorator adding fixed headers to every request.
     * Per-call headers win over the decorator's, matching the merge semantics
     * of [KoogHttpClient] ("headers with the same name replace configured ones").
     * Internal so tests can drive the merge directly.
     */
    internal class ChatGPTBackendHeaderDecorator(
        private val delegate: KoogHttpClient,
        private val extraHeaders: Map<String, String>,
    ) : KoogHttpClient {
        override val clientName: String get() = delegate.clientName

        private fun Map<String, String>.withExtras(): Map<String, String> =
            extraHeaders + this

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = delegate.get(path, responseType, parameters, headers.withExtras())

        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers.withExtras())

        override fun <T : Any, R : Any, O : Any> sse(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<O> = delegate.lines(
            path = path,
            requestBody = requestBody,
            requestBodyType = requestBodyType,
            parameters = parameters,
            headers = mapOf(
                "Accept" to "text/event-stream",
                "Content-Type" to "application/json",
            ) + headers.withExtras(),
        ).mapNotNull { line ->
            // The Codex backend emits one JSON value per data line. Ignore SSE
            // metadata/comments and the optional OpenAI stream terminator.
            val data = line
                .takeIf { it.startsWith("data:") }
                ?.removePrefix("data:")
                ?.trimStart()
                ?: return@mapNotNull null
            if (data == "[DONE]" || !dataFilter(data)) return@mapNotNull null
            processStreamingChunk(decodeStreamingResponse(data))
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = delegate.lines(path, requestBody, requestBodyType, parameters, headers.withExtras())

        override fun close() = delegate.close()
    }
}
