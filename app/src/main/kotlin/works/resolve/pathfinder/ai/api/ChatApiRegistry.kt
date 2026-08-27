package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry

/**
 * Creates [ChatApi] implementations for the API ids pi's generated model
 * catalog uses (pi: providers register `Record<ApiId, Api>` maps). Only the
 * APIs with a Kotlin implementation resolve here; the rest return null so a
 * catalog provider with not-yet-ported APIs can still be parsed and listed —
 * selecting one of those models fails clearly with an unsupported-API error.
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

    /** Builds the API implementation for [apiId], or null when it has no Kotlin port yet. */
    fun create(apiId: String, transport: HttpStreamingTransport, retry: ProviderRetry): ChatApi? =
        when (apiId) {
            OPENAI_COMPLETIONS -> OpenAiCompletionsApi(transport, retry)
            ANTHROPIC_MESSAGES -> AnthropicMessagesApi(transport, retry)
            GOOGLE_GENERATIVE_AI -> GoogleGenerativeAiApi(transport, retry)
            MISTRAL_CONVERSATIONS -> MistralConversationsApi(transport, retry)
            OPENAI_RESPONSES -> OpenAiResponsesApi(transport, retry)
            OPENAI_CODEX_RESPONSES -> OpenAICodexResponsesApi(transport)
            AZURE_OPENAI_RESPONSES -> AzureOpenAiResponsesApi(transport, retry)
            else -> null
        }
}
