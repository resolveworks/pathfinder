package works.resolve.pathfinder.logging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.telemetry.InMemoryTelemetryContext
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.attr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Facade-level policy tests for the app-owned diagnostics boundary: type-only
 * error statuses (short class names, empty messages), the central
 * cancellation-settles-ok policy, fatal-error propagation on the summary
 * path, and the passivity/identity contract of the swallowed-failure
 * recorder.
 */
class PathfinderDiagnosticsTest {

    private fun newFacade() = InMemoryTelemetryContext().let { it to PathfinderDiagnostics(it) }

    @Test
    fun `authLogin failure records type-only status and rethrows the original`() = runTest {
        val (telemetry, diagnostics) = newFacade()
        val cause = IllegalStateException("token exchange failed (400)")

        val thrown = assertFailsWith<IllegalStateException> {
            diagnostics.authLogin("zai", "oauth") { throw cause }
        }
        assertSame(cause, thrown) // the caller's exception, unchanged

        val span = telemetry.spans().single()
        assertEquals("pf.auth.login", span.name)
        val status = assertIs<SpanStatus.Error>(span.status)
        assertEquals("IllegalStateException", status.error?.name) // short type name
        assertEquals("", status.error?.message) // never the free-form message
        assertNull(span.attributes["pf.auth.outcome"]) // no success outcome on failure
    }

    @Test
    fun `authLogin cancellation settles ok and propagates`() = runTest {
        val (telemetry, diagnostics) = newFacade()
        val cancelled = CancellationException("login cancelled")

        assertFailsWith<CancellationException> {
            diagnostics.authLogin("zai", "api_key") { throw cancelled }
        }
        val span = telemetry.spans().single()
        assertEquals(SpanStatus.Ok, span.status) // cancellation is not an operational failure
    }

    @Test
    fun `credentialDelete records failure type-only and settles cancellation ok`() = runTest {
        val (telemetry, diagnostics) = newFacade()
        assertFailsWith<IllegalStateException> {
            diagnostics.credentialDelete("zai") { throw IllegalStateException("keystore exploded") }
        }
        val failed = telemetry.spans().single()
        val status = assertIs<SpanStatus.Error>(failed.status)
        assertEquals("IllegalStateException", status.error?.name)
        assertEquals("", status.error?.message)

        val (cancelledTelemetry, cancelledDiagnostics) = newFacade()
        assertFailsWith<CancellationException> {
            cancelledDiagnostics.credentialDelete("zai") { throw CancellationException("cancelled") }
        }
        assertEquals(SpanStatus.Ok, cancelledTelemetry.spans().single().status)
    }

    @Test
    fun `sessionSummary skips expected exceptions but propagates fatal errors`() = runTest {
        val (telemetry, diagnostics) = newFacade()
        assertNull(
            diagnostics.sessionSummary("s") { throw IllegalStateException("corrupt log") },
        )
        val skipped = telemetry.spans().single()
        val status = assertIs<SpanStatus.Error>(skipped.status)
        assertEquals("IllegalStateException", status.error?.name)
        assertEquals(attr("skipped"), skipped.attributes["pf.session.outcome"])

        // Fatal Throwables (Errors) are recorded but must propagate: the
        // listing swallows Exceptions only, exactly like the store did.
        val (fatalTelemetry, fatalDiagnostics) = newFacade()
        val fatal = AssertionError("fatal")
        val thrown = assertFailsWith<AssertionError> {
            fatalDiagnostics.sessionSummary("s") { throw fatal }
        }
        assertSame(fatal, thrown)
        val recorded = assertIs<SpanStatus.Error>(fatalTelemetry.spans().single().status)
        assertEquals("AssertionError", recorded.error?.name)
    }

    @Test
    fun `chatError is passive and records the swallowed cause type-only`() = runTest {
        val (telemetry, diagnostics) = newFacade()
        val cause = RuntimeException("provider body: <secret>")

        // Passive: recording never throws, even though the same cause is
        // thrown through the span for backend stack frames.
        diagnostics.chatError("Could not complete sign-in", cause)

        val span = telemetry.spans().single()
        assertEquals("pf.chat.error", span.name)
        assertEquals(attr("Could not complete sign-in"), span.attributes["pf.error.ui_message"])
        val status = assertIs<SpanStatus.Error>(span.status)
        assertEquals("RuntimeException", status.error?.name)
        assertEquals("", status.error?.message) // the provider body never appears
    }

    @Test
    fun `chatDegraded is passive and records the operation`() = runTest {
        val (telemetry, diagnostics) = newFacade()
        diagnostics.chatDegraded("available_models", IllegalStateException("read failed"))

        val span = telemetry.spans().single()
        assertEquals("pf.chat.degraded", span.name)
        assertEquals(attr("available_models"), span.attributes["pf.degraded.operation"])
        val status = assertIs<SpanStatus.Error>(span.status)
        assertEquals("IllegalStateException", status.error?.name)
        assertEquals("", status.error?.message)
        assertTrue(span.settled)
    }
}
