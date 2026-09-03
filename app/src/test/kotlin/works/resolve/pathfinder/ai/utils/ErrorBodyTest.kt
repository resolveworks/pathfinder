package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import works.resolve.pathfinder.ai.api.formatCodexError
import works.resolve.pathfinder.ai.api.formatResponsesProviderError
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import kotlin.test.assertEquals

class ErrorBodyTest {
    private fun httpError(status: Int, body: String) =
        ProviderHttpException(status = status, headers = emptyMap(), body = body)

    @Test
    fun `truncateErrorText boundary`() {
        assertEquals("abcde", truncateErrorText("abcde", 5))
        assertEquals("abcde... [truncated 1 chars]", truncateErrorText("abcdef", 5))
        assertEquals("", truncateErrorText("", 5))
    }

    @Test
    fun `formatProviderError composes body with and without prefix`() {
        val norm = NormalizedProviderError(status = 429, body = "rate limited", message = "msg", messageCarriesBody = false)
        assertEquals("429: rate limited", formatProviderError(norm))
        assertEquals("OpenAI API error (429): rate limited", formatProviderError(norm, "OpenAI API error"))
    }

    @Test
    fun `formatProviderError falls back to message when body is missing`() {
        val norm = NormalizedProviderError(status = 500, body = null, message = "msg", messageCarriesBody = false)
        assertEquals("msg", formatProviderError(norm))
        assertEquals("OpenAI API error (500): msg", formatProviderError(norm, "OpenAI API error"))
    }

    @Test
    fun `formatProviderError returns message unchanged when it carries the body`() {
        val norm = NormalizedProviderError(status = 400, body = null, message = "bad request: details", messageCarriesBody = true)
        assertEquals("bad request: details", formatProviderError(norm))
        assertEquals("OpenAI API error (400): bad request: details", formatProviderError(norm, "OpenAI API error"))
    }

    @Test
    fun `formatProviderError returns message unchanged when status is missing`() {
        val norm = NormalizedProviderError(status = null, body = "body", message = "msg", messageCarriesBody = false)
        assertEquals("msg", formatProviderError(norm))
        assertEquals("msg", formatProviderError(norm, "OpenAI API error"))
    }

    @Test
    fun `normalizeProviderError trims and drops blank bodies`() {
        val norm = normalizeProviderError(httpError(403, "  blocked by gateway  "))
        assertEquals(403, norm.status)
        assertEquals("blocked by gateway", norm.body)
        assertEquals(false, norm.messageCarriesBody)

        val blank = normalizeProviderError(httpError(403, "   "))
        assertEquals(null, blank.body)
    }

    @Test
    fun `normalizeProviderError caps the body at the shared limit`() {
        val body = "y".repeat(MAX_PROVIDER_ERROR_BODY_CHARS + 10)
        val norm = normalizeProviderError(httpError(500, body))
        assertEquals(
            "y".repeat(MAX_PROVIDER_ERROR_BODY_CHARS) + "... [truncated 10 chars]",
            norm.body,
        )
    }

    @Test
    fun `openai responses golden format`() {
        assertEquals(
            """OpenAI API error (500): {"error":{"code":"server_error","message":"boom"}}""",
            formatResponsesProviderError(
                httpError(500, """{"error":{"code":"server_error","message":"boom"}}"""),
                "OpenAI API error",
            ),
        )
        // Documented divergence: a blank body emits only "prefix (status)".
        assertEquals(
            "OpenAI API error (429)",
            formatResponsesProviderError(httpError(429, "  "), "OpenAI API error"),
        )
    }

    @Test
    fun `azure openai responses golden format`() {
        assertEquals(
            """Azure OpenAI API error (401): {"error":{"message":"bad key"}}""",
            formatResponsesProviderError(
                httpError(401, """{"error":{"message":"bad key"}}"""),
                "Azure OpenAI API error",
            ),
        )
    }

    @Test
    fun `codex golden format has no prefix`() {
        assertEquals(
            """503: {"error":{"message":"quota exceeded"}}""",
            formatCodexError(httpError(503, """{"error":{"message":"quota exceeded"}}""")),
        )
        assertEquals(
            "Provider returned HTTP 503",
            formatCodexError(httpError(503, "")),
        )
    }

    @Test
    fun `mistral golden format keeps its provider-specific composition`() {
        val api = works.resolve.pathfinder.ai.api.MistralConversationsApi(FakeTransport(), clock = FakeClock(0L))
        assertEquals(
            """Mistral API error (403): {"message":"blocked by gateway"}""",
            api.formatMistralError(httpError(403, """{"message":"blocked by gateway"}""")),
        )
        assertEquals(
            "Mistral API error (403): Provider returned HTTP 403",
            api.formatMistralError(httpError(403, "")),
        )
    }
}
