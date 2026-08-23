package works.resolve.aletheia.ai

import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StreamOptions
import works.resolve.aletheia.ai.transport.TransportRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secrets must never surface in toString(): a distinctive key value must not
 * occur in string output, while data-class copy/equality stays intact.
 */
class SecretRedactionTest {

    private val secret = "sk-SECRET-9f8e7d6c5b4a"

    @Test
    fun `StreamOptions toString omits api key`() {
        val options = StreamOptions(apiKey = secret, sessionId = "s1", temperature = 0.5)
        val rendered = options.toString()
        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.contains("sessionId=s1"))
        assertTrue(rendered.contains("temperature=0.5"))

        val none = StreamOptions(apiKey = null).toString()
        assertFalse(none.contains("redacted"))
        assertTrue(none.contains("apiKey=null"))
    }

    @Test
    fun `StreamOptions keeps copy and equality`() {
        val options = StreamOptions(apiKey = secret, maxTokens = 42)
        assertEquals(options, options.copy())
        assertEquals(StreamOptions(apiKey = "other", maxTokens = 42), options.copy(apiKey = "other"))
        assertEquals(42, options.copy(temperature = 1.0).maxTokens)
    }

    @Test
    fun `SimpleStreamOptions toString omits api key`() {
        val options = SimpleStreamOptions(apiKey = secret, temperature = 0.7)
        assertFalse(options.toString().contains(secret))
        assertTrue(options.toString().contains("<redacted>"))
        assertFalse(SimpleStreamOptions(apiKey = null).toString().contains("redacted"))
        assertEquals(options, options.copy())
    }

    @Test
    fun `OpenAiCompletionsOptions toString omits api key`() {
        val options = OpenAiCompletionsOptions(apiKey = secret, maxTokens = 100)
        assertFalse(options.toString().contains(secret))
        assertTrue(options.toString().contains("<redacted>"))
        assertFalse(OpenAiCompletionsOptions(apiKey = null).toString().contains("redacted"))
        assertEquals(options, options.copy())
    }

    @Test
    fun `TransportRequest toString omits bearer token`() {
        val request = TransportRequest(
            url = "https://api.example.com/v1/chat/completions",
            bearerToken = secret,
            headers = mapOf("Accept" to "text/event-stream"),
            body = "{}".toByteArray(),
        )
        val rendered = request.toString()
        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.contains("url=https://api.example.com/v1/chat/completions"))

        val noAuth = request.copy(bearerToken = null).toString()
        assertFalse(noAuth.contains("redacted"))
        assertTrue(noAuth.contains("bearerToken=null"))
    }

    @Test
    fun `TransportRequest keeps copy and equality`() {
        val request = TransportRequest(
            url = "https://api.example.com",
            bearerToken = secret,
            headers = emptyMap(),
            body = "ping".toByteArray(),
        )
        assertEquals(request, request.copy())
        assertEquals(
            TransportRequest("https://api.example.com", "other", body = "ping".toByteArray()),
            request.copy(bearerToken = "other"),
        )
    }
}
