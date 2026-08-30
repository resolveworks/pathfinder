package works.resolve.pathfinder.diagnostics

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
}
