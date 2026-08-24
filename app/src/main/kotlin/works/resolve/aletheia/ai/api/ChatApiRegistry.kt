package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.utils.ProviderRetry

/**
 * Creates [ChatApi] implementations for the API ids pi's generated model
 * catalog uses (pi: providers register `Record<ApiId, Api>` maps). Only the
 * APIs with a Kotlin implementation resolve here; the rest return null so a
 * catalog provider with not-yet-ported APIs can still be parsed and listed —
 * selecting one of those models fails clearly with an unsupported-API error.
 */
object ChatApiRegistry {

    const val OPENAI_COMPLETIONS = "openai-completions"
    const val GOOGLE_GENERATIVE_AI = "google-generative-ai"
    const val MISTRAL_CONVERSATIONS = "mistral-conversations"

    /** API ids with a runtime implementation. */
    val SUPPORTED_API_IDS: Set<String> = setOf(
        OPENAI_COMPLETIONS,
        GOOGLE_GENERATIVE_AI,
        MISTRAL_CONVERSATIONS,
    )

    fun isSupported(apiId: String): Boolean = apiId in SUPPORTED_API_IDS

    /**
     * Builds the API implementation for [apiId], or null when the protocol
     * has no Kotlin port yet (anthropic-messages, openai-responses, ...).
     */
    fun create(apiId: String, transport: HttpStreamingTransport, retry: ProviderRetry): ChatApi? =
        when (apiId) {
            OPENAI_COMPLETIONS -> OpenAiCompletionsApi(transport, retry)
            GOOGLE_GENERATIVE_AI -> GoogleGenerativeAiApi(transport, retry)
            MISTRAL_CONVERSATIONS -> MistralConversationsApi(transport, retry)
            else -> null
        }
}
