package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.NoWebSocketTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry

class ChatApiRegistryTest {

    private val transport = FakeTransport()
    private val retry = ProviderRetry()

    @Test
    fun `supported ids include every implemented api and exclude unported ones`() {
        assertEquals(
            setOf(
                ChatApiRegistry.OPENAI_COMPLETIONS,
                ChatApiRegistry.ANTHROPIC_MESSAGES,
                ChatApiRegistry.GOOGLE_GENERATIVE_AI,
                ChatApiRegistry.MISTRAL_CONVERSATIONS,
                ChatApiRegistry.OPENAI_RESPONSES,
                ChatApiRegistry.OPENAI_CODEX_RESPONSES,
                ChatApiRegistry.AZURE_OPENAI_RESPONSES
            ),
            ChatApiRegistry.SUPPORTED_API_IDS
        )
        assertTrue(ChatApiRegistry.isSupported("anthropic-messages"))
        assertTrue(ChatApiRegistry.isSupported("google-generative-ai"))
        assertTrue(ChatApiRegistry.isSupported("mistral-conversations"))
        assertTrue(ChatApiRegistry.isSupported("openai-responses"))
        assertTrue(ChatApiRegistry.isSupported("openai-codex-responses"))
        assertTrue(ChatApiRegistry.isSupported("azure-openai-responses"))
        assertFalse(ChatApiRegistry.isSupported("google-vertex"))
        assertFalse(ChatApiRegistry.isSupported("bedrock"))
    }

    @Test
    fun `create builds the matching api or null`() {
        assertIs<OpenAiCompletionsApi>(
            ChatApiRegistry.create("openai-completions", transport, retry)
        )
        assertIs<AnthropicMessagesApi>(
            ChatApiRegistry.create("anthropic-messages", transport, retry)
        )
        assertIs<GoogleGenerativeAiApi>(
            ChatApiRegistry.create("google-generative-ai", transport, retry)
        )
        assertIs<MistralConversationsApi>(
            ChatApiRegistry.create("mistral-conversations", transport, retry)
        )
        assertIs<OpenAiResponsesApi>(ChatApiRegistry.create("openai-responses", transport, retry))
        assertIs<OpenAICodexResponsesApi>(
            ChatApiRegistry.create(
                "openai-codex-responses",
                transport,
                retry,
                webSocketTransport = NoWebSocketTransport
            )
        )
        assertIs<AzureOpenAiResponsesApi>(
            ChatApiRegistry.create("azure-openai-responses", transport, retry)
        )
        assertNull(ChatApiRegistry.create("google-vertex", transport, retry))
    }
}
