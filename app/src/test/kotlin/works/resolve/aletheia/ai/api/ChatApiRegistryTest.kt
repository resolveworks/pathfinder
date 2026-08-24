package works.resolve.aletheia.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.resolve.aletheia.ai.testing.FakeTransport
import works.resolve.aletheia.ai.utils.ProviderRetry

class ChatApiRegistryTest {

    @Test
    fun `supported ids include every implemented api and exclude unported ones`() {
        assertEquals(
            setOf(
                ChatApiRegistry.OPENAI_COMPLETIONS,
                ChatApiRegistry.GOOGLE_GENERATIVE_AI,
                ChatApiRegistry.MISTRAL_CONVERSATIONS,
            ),
            ChatApiRegistry.SUPPORTED_API_IDS,
        )
        assertTrue(ChatApiRegistry.isSupported("google-generative-ai"))
        assertTrue(ChatApiRegistry.isSupported("mistral-conversations"))
        assertFalse(ChatApiRegistry.isSupported("google-vertex"))
        assertFalse(ChatApiRegistry.isSupported("anthropic-messages"))
    }

    @Test
    fun `create builds the matching api or null`() {
        val transport = FakeTransport()
        val retry = ProviderRetry()
        assertIs<GoogleGenerativeAiApi>(ChatApiRegistry.create("google-generative-ai", transport, retry))
        assertIs<MistralConversationsApi>(ChatApiRegistry.create("mistral-conversations", transport, retry))
        assertIs<OpenAiCompletionsApi>(ChatApiRegistry.create("openai-completions", transport, retry))
        assertNull(ChatApiRegistry.create("google-vertex", transport, retry))
    }
}
