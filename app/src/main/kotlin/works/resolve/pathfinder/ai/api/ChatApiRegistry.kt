package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry

/**
 * Creates [ChatApi] implementations for the API ids in Pathfinder's generated
 * model catalog (pi: providers register `Record<ApiId, Api>` maps). Catalog
 * generation must select only ids registered here. Unknown ids return null so
 * a catalog/runtime mismatch produces the models layer's unsupported-API error.
 */
object ChatApiRegistry {

    const val OPENAI_COMPLETIONS = "openai-completions"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
    const val GOOGLE_GENERATIVE_AI = "google-generative-ai"
    const val MISTRAL_CONVERSATIONS = "mistral-conversations"
    const val OPENAI_RESPONSES = "openai-responses"
    const val OPENAI_CODEX_RESPONSES = "openai-codex-responses"
    const val AZURE_OPENAI_RESPONSES = "azure-openai-responses"

    /** API ids with a runtime implementation. */
    val SUPPORTED_API_IDS: Set<String> = setOf(
        OPENAI_COMPLETIONS,
        ANTHROPIC_MESSAGES,
        GOOGLE_GENERATIVE_AI,
        MISTRAL_CONVERSATIONS,
        OPENAI_RESPONSES,
        OPENAI_CODEX_RESPONSES,
        AZURE_OPENAI_RESPONSES,
    )

    fun isSupported(apiId: String): Boolean = apiId in SUPPORTED_API_IDS

    /** Builds the registered API implementation for [apiId], or null for an unknown id. */
    fun create(
        apiId: String,
        transport: HttpStreamingTransport,
        retry: ProviderRetry,
        webSocketTransport: WebSocketStreamingTransport? = null,
    ): ChatApi? =
        when (apiId) {
            OPENAI_COMPLETIONS -> OpenAiCompletionsApi(transport, retry)
            ANTHROPIC_MESSAGES -> AnthropicMessagesApi(transport, retry)
            GOOGLE_GENERATIVE_AI -> GoogleGenerativeAiApi(transport, retry)
            MISTRAL_CONVERSATIONS -> MistralConversationsApi(transport) // pi: no retry wrapper for Mistral
            OPENAI_RESPONSES -> OpenAiResponsesApi(transport, retry)
            // Android always wires a WebSocket transport (pi's no-WebSocket
            // browser runtimes do not exist here); fail fast on missing wiring.
            OPENAI_CODEX_RESPONSES -> OpenAICodexResponsesApi(
                transport,
                webSocketTransport = webSocketTransport
                    ?: error("openai-codex-responses requires a WebSocket streaming transport"),
            )
            AZURE_OPENAI_RESPONSES -> AzureOpenAiResponsesApi(transport, retry)
            else -> null
        }
}
