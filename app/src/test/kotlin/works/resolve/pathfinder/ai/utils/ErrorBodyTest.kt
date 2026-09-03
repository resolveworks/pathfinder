package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import works.resolve.pathfinder.ai.api.formatCodexError
import works.resolve.pathfinder.ai.api.formatResponsesProviderError
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `formatProviderError surfaces status and body without a prefix through normalize`() {
        // Upstream composes from an SDK error whose message is opaque
        // ("403 status code (no body)"); the Kotlin analog of the opaque
        // message is the transport exception's "Provider returned HTTP 403".
        val norm = normalizeProviderError(httpError(403, """{"error":"blocked by gateway WAF"}"""))

        val formatted = formatProviderError(norm)

        assertTrue("403" in formatted)
        assertTrue("blocked by gateway WAF" in formatted)
        assertTrue(formatted != "Provider returned HTTP 403")
    }

    @Test
    fun `formatProviderError applies a provider prefix with status and body through normalize`() {
        val norm = normalizeProviderError(httpError(403, """{"error":"blocked by gateway WAF"}"""))

        assertEquals(
            """OpenAI API error (403): {"error":"blocked by gateway WAF"}""",
            formatProviderError(norm, "OpenAI API error"),
        )
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
    fun `normalizeProviderError surfaces a JSON error body verbatim`() {
        // Upstream "still surfaces a plain parsed JSON body object": the SDK's
        // parsed body is JSON-stringified; the transport body already is the
        // JSON text and must surface unchanged, messageCarriesBody false.
        val norm = normalizeProviderError(
            httpError(400, """{"message":"schema validation failed","field":"tools[0]"}"""),
        )

        assertEquals("""{"message":"schema validation failed","field":"tools[0]"}""", norm.body)
        assertEquals(false, norm.messageCarriesBody)
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

    // TODO(pi parity; documented divergence in ErrorBody.kt): pi's
    // normalizeProviderError computes messageCarriesBody =
    // `body === undefined || error.message.includes(body)`, so it is true when
    // the SDK already folded the body into the message (upstream "preserves
    // the message when @google/genai already folds the body into it" and "sets
    // messageCarriesBody when the message already contains the extracted
    // body"). Not portable: ProviderHttpException's message is fixed to
    // "Provider returned HTTP N", so an error whose message contains the body
    // cannot be constructed here, and the Kotlin port always returns false
    // (the only constructible near-hit would be a body that is a substring of
    // the fixed message, e.g. body "HTTP 500"). Upstream case, kept for the
    // day the message becomes customizable:
    //
    // @Test
    // fun `normalizeProviderError flags a message that already contains the body`() {
    //     // upstream error: message "500: upstream exploded", body "upstream exploded"
    //     val norm = normalizeProviderError(httpError(500, "upstream exploded"))
    //     assertEquals(true, norm.messageCarriesBody)
    // }

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
