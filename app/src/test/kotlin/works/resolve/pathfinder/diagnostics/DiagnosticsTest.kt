package works.resolve.pathfinder.diagnostics

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.streaming.IncompleteStreamException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class DiagnosticsTest {

    @Test
    fun `failure metadata excludes throwable messages`() {
        val secret = "access-token-secret"
        val cause = IllegalStateException("provider payload contains $secret")
        val error = RuntimeException("request URL contains $secret", cause).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "works.resolve.pathfinder.runtime.CodexOAuthClient",
                    "requestTokens",
                    "CodexOAuthClient.kt",
                    339,
                ),
            )
        }

        val message = DiagnosticEntry(
            event = DiagnosticEvent.CODEX_TOKEN_EXCHANGE_TRANSPORT_FAILED,
            failure = DiagnosticFailure.from(error),
        ).message()

        assertEquals(
            "event=codex.token.exchange_transport_failed " +
                "error_types=java.lang.RuntimeException>java.lang.IllegalStateException " +
                "origin=works.resolve.pathfinder.runtime.CodexOAuthClient.requestTokens(CodexOAuthClient.kt:339)",
            message,
        )
        assertFalse(message.contains(secret))
        assertFalse(message.contains("provider payload"))
        assertFalse(message.contains("request URL"))
    }

    @Test
    fun `failure cause chain is bounded`() {
        var error: Throwable = IllegalStateException("root")
        repeat(20) { error = RuntimeException("cause-$it", error) }

        assertEquals(8, DiagnosticFailure.from(error).typeChain.size)
    }

    @Test
    fun `http status is constrained and rendered structurally`() {
        val entry = DiagnosticEntry(
            event = DiagnosticEvent.CODEX_TOKEN_EXCHANGE_HTTP_FAILED,
            httpStatus = 400,
        )
        assertEquals("event=codex.token.exchange_http_failed http_status=400", entry.message())
        assertFailsWith<IllegalArgumentException> {
            DiagnosticEntry(DiagnosticEvent.CODEX_TOKEN_EXCHANGE_HTTP_FAILED, httpStatus = 42)
        }
    }

    @Test
    fun `failure harvests http status from koog client exception in cause chain`() {
        val secret = "session-token-secret"
        val transport = KoogHttpClientException(
            clientName = "OpenAICodexLLMClient",
            statusCode = 401,
            errorBody = "provider payload with $secret",
        )
        val error = LLMClientException(
            clientName = "OpenAICodexLLMClient",
            message = "request failed: $secret",
            cause = transport,
        )

        val entries = RecordedSink.entries { Diagnostics.failure(DiagnosticEvent.CHAT_REQUEST_FAILED, error) }

        assertEquals(1, entries.size)
        assertEquals(401, entries.single().httpStatus)
        assertEquals(
            "event=chat.request_failed http_status=401 " +
                "error_types=ai.koog.prompt.executor.clients.LLMClientException>" +
                "ai.koog.http.client.KoogHttpClientException",
            entries.single().message(),
        )
    }

    @Test
    fun `failure without koog client exception carries no http status`() {
        val entries = RecordedSink.entries {
            Diagnostics.failure(DiagnosticEvent.CHAT_STREAM_INCOMPLETE, IncompleteStreamException())
        }

        assertEquals(1, entries.size)
        assertEquals(null, entries.single().httpStatus)
        assertEquals("event=chat.stream_incomplete error_types=ai.koog.prompt.streaming.IncompleteStreamException", entries.single().message())
    }
}

/**
 * Installs a recording sink around [block] and restores the (null) backend
 * afterwards: [Diagnostics] is process-wide, so tests must not leak sinks.
 */
private object RecordedSink {
    fun entries(block: () -> Unit): List<DiagnosticEntry> {
        val recorded = mutableListOf<DiagnosticEntry>()
        Diagnostics.install { entry -> recorded += entry }
        try {
            block()
        } finally {
            Diagnostics.install(null)
        }
        return recorded
    }
}
