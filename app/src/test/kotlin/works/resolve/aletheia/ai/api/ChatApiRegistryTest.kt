package works.resolve.aletheia.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.resolve.aletheia.ai.testing.FakeTransport
import works.resolve.aletheia.ai.utils.ProviderRetry

/** The registry resolves exactly the APIs with a Kotlin port. */
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
            ),
            ChatApiRegistry.SUPPORTED_API_IDS,
        )
        assertTrue(ChatApiRegistry.isSupported("anthropic-messages"))
        assertTrue(ChatApiRegistry.isSupported("google-generative-ai"))
        assertTrue(ChatApiRegistry.isSupported("mistral-conversations"))
        assertFalse(ChatApiRegistry.isSupported("google-vertex"))
        assertFalse(ChatApiRegistry.isSupported("openai-responses"))
    }

    @Test
    fun `create builds the matching api or null`() {
        assertIs<OpenAiCompletionsApi>(ChatApiRegistry.create("openai-completions", transport, retry))
        assertIs<AnthropicMessagesApi>(ChatApiRegistry.create("anthropic-messages", transport, retry))
        assertIs<GoogleGenerativeAiApi>(ChatApiRegistry.create("google-generative-ai", transport, retry))
        assertIs<MistralConversationsApi>(ChatApiRegistry.create("mistral-conversations", transport, retry))
        assertNull(ChatApiRegistry.create("google-vertex", transport, retry))
        assertNull(ChatApiRegistry.create("openai-responses", transport, retry))
    }
}
