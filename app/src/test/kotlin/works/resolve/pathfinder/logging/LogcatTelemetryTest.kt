package works.resolve.pathfinder.logging

import org.junit.Assert.assertEquals
import org.junit.Test
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryError
import works.resolve.pathfinder.telemetry.attr

/**
 * Tests for the Logcat telemetry backend's pure rendering: structured
 * `key=value` lines, value sanitization (control characters, quoting,
 * length caps), and error-status detail. `android.util.Log` itself is the
 * only untested touchpoint.
 */
class LogcatTelemetryTest {

    @Test
    fun `start line carries direction, ids, parent, and attributes`() {
        assertEquals(
            "> pf.chat.error id=2 parent=1 pf.error.ui_message=\"Something went wrong\"",
            renderStart(2, 1, "pf.chat.error", mapOf("pf.error.ui_message" to attr("Something went wrong"))),
        )
        assertEquals(
            "> pf.root id=1 parent=-",
            renderStart(1, null, "pf.root", emptyMap()),
        )
    }

    @Test
    fun `event line carries the span, event name, and attributes`() {
        assertEquals(
            "+ pf.chat.error id=3 event=stream_failed attempt=1",
            renderEvent(3, "pf.chat.error", "stream_failed", mapOf("attempt" to attr(1))),
        )
    }

    @Test
    fun `end line carries status, duration, and accumulated attributes`() {
        assertEquals(
            "< pf.chat.error id=2 status=ok duration_ms=1837 pf.degraded.operation=none",
            renderEnd(2, "pf.chat.error", SpanStatus.Ok, mapOf("pf.degraded.operation" to attr("none")), 1837),
        )
    }

    @Test
    fun `error end line carries error name and quoted message`() {
        val status = SpanStatus.Error(
            TelemetryError(
                "java.io.IOException",
                "Connection closed mid-stream",
            ),
        )
        assertEquals(
            "< pf.chat.error id=2 status=error error_name=java.io.IOException" +
                " error_message=\"Connection closed mid-stream\" duration_ms=42",
            renderEnd(2, "pf.chat.error", status, emptyMap(), 42),
        )
    }

    @Test
    fun `values with whitespace or quotes are quoted and escaped`() {
        assertEquals(
            "< pf.chat.error id=1 status=error error_name=E error_message=\"line one\\nline \\\"two\\\"\" duration_ms=0",
            renderEnd(
                1,
                "pf.chat.error",
                SpanStatus.Error(TelemetryError("E", "line one\nline \"two\"")),
                emptyMap(),
                0,
            ),
        )
    }

    @Test
    fun `long values are capped`() {
        assertEquals(500, "x".repeat(2000).asLogValue().length)
    }

    @Test
    fun `numeric and boolean attribute values render unquoted`() {
        assertEquals(
            "> pf.span id=1 parent=- count=400 ok=true cost=0.5",
            renderStart(1, null, "pf.span", mapOf("count" to attr(400), "ok" to attr(true), "cost" to attr(0.5))),
        )
    }

    @Test
    fun `attribute values cannot forge key value structure`() {
        // A value containing spaces is quoted wholesale, so `k=v` stays parseable.
        assertEquals(
            "> pf.span id=1 parent=- note=\"a b c=forged\"",
            renderStart(1, null, "pf.span", mapOf("note" to attr("a b c=forged"))),
        )
    }
}
