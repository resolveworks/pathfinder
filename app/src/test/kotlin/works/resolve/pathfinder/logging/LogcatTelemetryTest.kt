package works.resolve.pathfinder.logging

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import works.resolve.pathfinder.logging.LogcatTelemetryContext.LogSink
import works.resolve.pathfinder.telemetry.AttributeValue
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryError
import works.resolve.pathfinder.telemetry.TelemetrySpan
import works.resolve.pathfinder.telemetry.attr

/**
 * Adapter conformance tests for [LogcatTelemetryContext], ported from pi
 * `packages/telemetry/src/testing/conformance.ts` (callback lifecycle,
 * status, recording, parentage, passivity) plus the app adapter's
 * security-specific behavior: no `Throwable` reaches the sink, no free-form
 * error messages are rendered, stack output is bounded and type-only, and a
 * failing sink never changes the business result.
 *
 * Lines are captured through the injectable [LogSink]; the fake time source
 * makes durations deterministic. `android.util.Log` is never touched.
 */
class LogcatTelemetryTest {

    /** One captured line: error level flag, tag, and rendered message. */
    private data class Line(val isError: Boolean, val tag: String, val message: String)

    /** A sink that records lines; optionally throws on every call. */
    private class RecordingSink(val fail: Boolean = false) : LogSink {
        val lines = mutableListOf<Line>()
        val messages: List<String> get() = lines.map { it.message }
        override fun log(isError: Boolean, tag: String, message: String) {
            if (fail) throw IllegalStateException("sink down")
            lines += Line(isError, tag, message)
        }
    }

    /** Deterministic nanosecond source: advances by [step] per read. */
    private class FakeTime(var now: Long = 0L, private val step: Long = 1_000_000L) {
        val read: () -> Long = {
            val value = now
            now += step
            value
        }
    }

    private fun context(sink: RecordingSink, time: FakeTime = FakeTime()) =
        Pair(LogcatTelemetryContext(tag = "PF", sink = sink, nanoTime = time.read), sink)


    // ---- callback lifecycle (conformance: "callback lifecycle") ----

    @Test
    fun `admits once and preserves the result identity`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())
        val expected = Any()
        var calls = 0

        val result = telemetry.startSpan(SpanOptions(name = "success")) { calls++; expected }

        assertSame(expected, result)
        assertEquals(1, calls)
        assertEquals(
            listOf(
                "> success id=1 parent=-",
                "< success id=1 status=ok duration_ms=1",
            ),
            sink.messages,
        )
        assertFalse(sink.lines.any { it.isError })
    }

    @Test
    fun `preserves the exact rejection value and settles error`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())
        val syncError = IllegalStateException("secret message")

        try {
            telemetry.startSpan(SpanOptions(name = "sync-error")) { throw syncError }
            fail("expected rethrow")
        } catch (thrown: Throwable) {
            assertSame(syncError, thrown)
        }

        val end = sink.lines.last()
        assertTrue(end.isError)
        assertTrue(end.message.startsWith("< sync-error id=1 status=error error_name=IllegalStateException duration_ms="))
        // The exception message must never reach the sink.
        assertFalse(end.message.contains("secret message"))
    }

    @Test
    fun `automatic error status is used when none was set`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())
        runCatching {
            telemetry.startSpan(SpanOptions(name = "auto")) { delay(1); throw RuntimeException("boom") }
        }
        val end = sink.lines.last()
        assertTrue(end.isError)
        assertTrue("status=error error_name=RuntimeException" in end.message)
    }

    // ---- status (conformance: "status") ----

    @Test
    fun `uses last explicit status without automatic overwrite`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())

        telemetry.startSpan(SpanOptions(name = "last-status")) { span ->
            span.setStatus(SpanStatus.Error(TelemetryError("Expected", "first")))
            span.setStatus(SpanStatus.Ok)
        }

        val thrown = IllegalStateException("after explicit status")
        try {
            telemetry.startSpan(SpanOptions(name = "explicit-before-throw")) { span ->
                span.setStatus(SpanStatus.Ok)
                throw thrown
            }
            fail("expected rethrow")
        } catch (error: Throwable) {
            assertSame(thrown, error)
        }

        telemetry.startSpan(SpanOptions(name = "expected-failure")) { span ->
            span.setStatus(SpanStatus.Error(TelemetryError("Expected", "returned failure")))
            Any()
        }

        val messages = sink.messages
        assertTrue("< last-status id=1 status=ok duration_ms=" in messages[1])
        assertTrue("< explicit-before-throw id=2 status=ok duration_ms=" in messages[3])
        val expectedFailure = messages.last()
        assertTrue("status=error error_name=Expected" in expectedFailure)
        // Free-form status messages are never emitted (type-only policy).
        assertFalse(expectedFailure.contains("returned failure"))
    }

    @Test
    fun `every error status logs at error level even without detail`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())

        telemetry.startSpan(SpanOptions(name = "detail-less")) { span ->
            span.setStatus(SpanStatus.Error())
            Unit
        }

        val end = sink.lines.last()
        assertTrue(end.isError)
        assertEquals("< detail-less id=1 status=error duration_ms=1", end.message)
    }

    // ---- recording (conformance: "recording") ----

    @Test
    fun `merges attributes with later values winning and records ordered events`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())

        telemetry.startSpan(
            SpanOptions(name = "recording", attributes = mapOf("start" to attr("value"), "overwrite" to attr("start"))),
        ) { span ->
            span.setAttributes(mapOf("count" to attr(1), "overwrite" to attr("middle")))
            span.setAttributes(mapOf("overwrite" to attr("end")))
            span.addEvent("first", mapOf("index" to attr(1)))
            span.addEvent("second", mapOf("index" to attr(2)))
        }

        val messages = sink.messages
        assertEquals("> recording id=1 parent=- start=value overwrite=start", messages[0])
        assertEquals("+ recording id=1 event=first index=1", messages[1])
        assertEquals("+ recording id=1 event=second index=2", messages[2])
        assertEquals("< recording id=1 status=ok duration_ms=1 start=value overwrite=end count=1", messages[3])
    }

    @Test
    fun `event attributes stay event-only and never merge into span attributes`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())

        telemetry.startSpan(SpanOptions(name = "events")) { span ->
            span.addEvent("cache.lookup", mapOf("cache.hit" to attr(false)))
        }

        assertEquals("< events id=1 status=ok duration_ms=1", sink.messages[2])
    }

    @Test
    fun `ignores failed attribute calls atomically`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())

        // A list whose iteration throws mirrors pi's "unreadable" payload:
        // the whole call is ignored, previously retained data survives.
        val throwingList = object : AbstractList<String>() {
            override val size: Int get() = throw IllegalStateException("read")
            override fun get(index: Int): String = throw IllegalStateException("read")
        }

        telemetry.startSpan(SpanOptions(name = "atomic", attributes = mapOf("retained" to attr("value")))) { span ->
            try {
                span.setAttributes(mapOf("partial" to attr("must not survive"), "unreadable" to AttributeValue.Strs(throwingList)))
            } catch (_: Throwable) {
                fail("setAttributes must be passive")
            }
        }

        assertEquals("< atomic id=1 status=ok duration_ms=1 retained=value", sink.messages[1])
    }

    @Test
    fun `arrays are defensively copied so caller mutation cannot leak`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())
        val callerList = mutableListOf("a", "b")

        telemetry.startSpan(SpanOptions(name = "copy")) { span ->
            span.setAttributes(mapOf("items" to AttributeValue.Strs(callerList)))
            callerList += "mutated-after-recording"
        }

        val end = sink.messages[1]
        assertTrue("items=[a,b]" in end)
        assertFalse("mutated-after-recording" in end)
    }

    // ---- settlement (conformance: "makes calls after settlement inert") ----

    @Test
    fun `makes calls after settlement inert and routes late children through noop`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())
        lateinit var settledSpan: TelemetrySpan

        telemetry.startSpan(SpanOptions(name = "settled", attributes = mapOf("value" to attr("initial")))) { span ->
            settledSpan = span
        }

        val before = sink.lines.toList()
        settledSpan.setAttributes(mapOf("value" to attr("late")))
        settledSpan.addEvent("late", mapOf("value" to attr(true)))
        settledSpan.setStatus(SpanStatus.Error())

        var childAdmitted = false
        val childResult = settledSpan.startSpan(SpanOptions(name = "late-child")) {
            childAdmitted = true
            7
        }

        assertTrue(childAdmitted)
        assertEquals(7, childResult)
        // No additional lines: two for the settled span, nothing else.
        assertEquals(before, sink.lines)
        assertEquals(2, sink.lines.size)
    }

    // ---- parentage (conformance: "records nested and concurrent child relationships") ----

    @Test
    fun `records nested child relationships with end ordering`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())

        telemetry.startSpan(SpanOptions(name = "parent")) { parent ->
            parent.startSpan(SpanOptions(name = "child")) { child ->
                child.addEvent("step", emptyMap())
                "done"
            }
        }

        val messages = sink.messages
        assertEquals("> parent id=1 parent=-", messages[0])
        assertEquals("> child id=2 parent=1", messages[1])
        assertEquals("+ child id=2 event=step", messages[2])
        // The child ends before its parent.
        assertTrue(messages[3].startsWith("< child id=2 status=ok"))
        assertTrue(messages[4].startsWith("< parent id=1 status=ok"))
    }

    @Test
    fun `records concurrent child relationships without corruption`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())

        telemetry.startSpan(SpanOptions(name = "parent")) { parent ->
            listOf(
                async { parent.startSpan(SpanOptions(name = "first-child")) { delay(50); 1 } },
                async { parent.startSpan(SpanOptions(name = "second-child")) { 2 } },
            ).awaitAll()
        }

        val messages = sink.messages
        assertEquals("> parent id=1 parent=-", messages.first())
        assertEquals("> first-child id=2 parent=1", messages[1])
        assertEquals("> second-child id=3 parent=1", messages[2])
        // Parent ends after both children.
        assertTrue(messages.last().startsWith("< parent id=1 status=ok"))
        assertTrue(messages.indexOfFirst { it.startsWith("< first-child") } < messages.indexOfFirst { it.startsWith("< parent") })
        assertTrue(messages.indexOfFirst { it.startsWith("< second-child") } < messages.indexOfFirst { it.startsWith("< parent") })
    }

    // ---- passivity (conformance: "passivity" + app security policy) ----

    @Test
    fun `a failing sink never prevents or replaces the business result`() = runBlocking {
        val (telemetry, _) = context(RecordingSink(fail = true))
        val expected = Any()
        var calls = 0

        val result = telemetry.startSpan(SpanOptions(name = "sink-down")) { span ->
            span.addEvent("event", emptyMap())
            calls++
            expected
        }

        assertSame(expected, result)
        assertEquals(1, calls)
    }

    @Test
    fun `a failing sink never replaces the business exception`() = runBlocking {
        val (telemetry, _) = context(RecordingSink(fail = true))
        val businessError = IllegalStateException("business")

        try {
            telemetry.startSpan(SpanOptions(name = "sink-down-error")) { throw businessError }
            fail("expected rethrow")
        } catch (thrown: Throwable) {
            assertSame(businessError, thrown)
        }
    }

    @Test
    fun `unreadable span options route the whole span through noop`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())
        val throwingList = object : AbstractList<String>() {
            override val size: Int get() = throw IllegalStateException("read")
            override fun get(index: Int): String = throw IllegalStateException("read")
        }
        var calls = 0

        val result = telemetry.startSpan(
            SpanOptions(name = "unreadable-options", attributes = mapOf("secret" to AttributeValue.Strs(throwingList))),
        ) {
            calls++
            9
        }

        assertEquals(9, result)
        assertEquals(1, calls)
        // Nothing was admitted, so nothing was logged.
        assertTrue(sink.lines.isEmpty())
    }

    // ---- safe stack output (app security policy) ----

    @Test
    fun `error end lines carry bounded type-only stack metadata, never messages`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())
        val error = IllegalStateException("token=super-secret")

        try {
            telemetry.startSpan(SpanOptions(name = "pf.auth.login")) { throw error }
        } catch (_: Throwable) {
        }

        val end = sink.lines.last()
        assertTrue(end.isError)
        assertTrue("stack=" in end.message)
        // Frames render as bounded, type-only class.method(File:line) metadata.
        val stack = end.message.substringAfter("stack=").removeSurrounding("\"")
        val frames = stack.split(";")
        assertTrue(frames.size in 1..8)
        // Every complete frame is class.method(File:line); the last fragment
        // may be truncated mid-frame by the value length cap.
        assertTrue(frames.dropLast(1).all { it.endsWith(")") && ':' in it })
        assertTrue(frames.last().contains("("))
        assertTrue(frames.any { "(LogcatTelemetryTest.kt:" in it || "(Builders.kt:" in it })
        // No message from the exception.
        assertFalse(end.message.contains("super-secret"))
    }

    @Test
    fun `explicit error status on a returned result logs at error level without stack`() = runBlocking {
        val (telemetry, sink) = context(RecordingSink())

        telemetry.startSpan(SpanOptions(name = "type-only")) { span ->
            span.setStatus(SpanStatus.Error(TelemetryError("ProviderAuthException", "")))
            Unit
        }

        val end = sink.lines.last()
        assertTrue(end.isError)
        assertEquals("< type-only id=1 status=error error_name=ProviderAuthException duration_ms=1", end.message)
    }

    // ---- rendering unit tests ----

    @Test
    fun `values with whitespace or quotes are quoted and escaped`() {
        assertEquals(
            "< pf.chat.error id=1 status=error error_name=E duration_ms=0 note=\"line one\\nline \\\"two\\\"\"",
            renderEnd(
                1,
                "pf.chat.error",
                SpanStatus.Error(TelemetryError("E", "line one\nline \"two\"")),
                mapOf("note" to attr("line one\nline \"two\"")),
                0,
            ),
        )
    }

    @Test
    fun `long values are capped`() {
        assertEquals(500, "x".repeat(2000).asLogValue().length)
        assertNull(renderStack(NoStackTraceError()))
    }

    private class NoStackTraceError : Error() {
        init {
            stackTrace = emptyArray()
        }
    }

    @Test
    fun `numeric boolean and array attribute values render safely`() {
        assertEquals(
            "> pf.span id=1 parent=- count=400 ok=true cost=0.5 items=[a,b]",
            renderStart(
                1,
                null,
                "pf.span",
                mapOf("count" to attr(400), "ok" to attr(true), "cost" to attr(0.5), "items" to AttributeValue.Strs(listOf("a", "b"))),
            ),
        )
    }

    @Test
    fun `attribute values cannot forge key value structure`() {
        assertEquals(
            "> pf.span id=1 parent=- note=\"a b c=forged\"",
            renderStart(1, null, "pf.span", mapOf("note" to attr("a b c=forged"))),
        )
    }
}
