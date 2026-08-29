package works.resolve.pathfinder.telemetry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the ported telemetry contract
 * (`packages/telemetry/src/index.ts` + `memory.ts`): no-op passthrough,
 * in-memory recording (ids, parents, settle order, snapshots), automatic
 * error statuses, explicit-status precedence, and post-settle passivity.
 */
class TelemetryContractTest {

    @Test
    fun `noop context passes values through and propagates exceptions`() = runTest {
        val result = NOOP_TELEMETRY_CONTEXT.startSpan(SpanOptions("pf.test")) { span ->
            span.addEvent("event")
            span.setAttributes(mapOf("k" to attr("v")))
            span.setStatus(SpanStatus.Error())
            "value"
        }
        assertEquals("value", result)

        val thrown = IllegalStateException("boom")
        try {
            NOOP_TELEMETRY_CONTEXT.startSpan(SpanOptions("pf.test")) { throw thrown }
            throw AssertionError("expected rethrow")
        } catch (error: IllegalStateException) {
            assertSame(thrown, error)
        }
    }

    @Test
    fun `in-memory records spans with start attributes and settle order`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.outer", mapOf("a" to attr("1")))) { outer ->
            outer.startSpan(SpanOptions("pf.inner")) { inner ->
                inner.addEvent("happened", mapOf("detail" to attr(true)))
            }
        }
        val spans = context.spans()
        assertEquals(listOf("pf.outer", "pf.inner"), spans.map { it.name })
        assertEquals(1, spans[1].parentId)
        assertEquals(mapOf("a" to attr("1")), spans[0].attributes)
        assertEquals(listOf(InMemoryTelemetryContext.RecordedTelemetryEvent("happened", mapOf("detail" to attr(true)))), spans[1].events)
        // Both settled, in call order (outer settles after inner).
        assertTrue(spans.all { it.settled })
        assertTrue(spans[0].endSequence!! > spans[1].endSequence!!)
        assertEquals(SpanStatus.Ok, spans[0].status)
    }

    @Test
    fun `callback throw settles span with automatic error status and rethrows`() = runTest {
        val context = InMemoryTelemetryContext()
        val thrown = IllegalStateException("redacted-by-construction detail")
        try {
            context.startSpan(SpanOptions("pf.failing")) { throw thrown }
            throw AssertionError("expected rethrow")
        } catch (error: IllegalStateException) {
            assertSame(thrown, error)
        }
        val span = context.spans().single()
        val status = span.status as SpanStatus.Error
        assertEquals("java.lang.IllegalStateException", status.error?.name)
        assertEquals("redacted-by-construction detail", status.error?.message)
        // The recorded snapshot drops the throwable transport field (pi's serializable shape).
        assertNull(status.error?.throwable)
    }

    @Test
    fun `explicit status wins over the automatic error status`() = runTest {
        val context = InMemoryTelemetryContext()
        try {
            context.startSpan(SpanOptions("pf.explicit")) { span ->
                span.setStatus(SpanStatus.Error(TelemetryError("Custom", "expected failure")))
                throw RuntimeException("should not be recorded")
            }
        } catch (_: RuntimeException) {
        }
        val status = context.spans().single().status as SpanStatus.Error
        assertEquals("Custom", status.error?.name)
        assertEquals("expected failure", status.error?.message)
    }

    @Test
    fun `mutations after settle are ignored`() = runTest {
        val context = InMemoryTelemetryContext()
        lateinit var span: TelemetrySpan
        context.startSpan(SpanOptions("pf.outer", mapOf("start" to attr("kept")))) { captured ->
            span = captured
        }
        span.setAttributes(mapOf("late" to attr("dropped")))
        span.addEvent("late")
        span.setStatus(SpanStatus.Error())
        val recorded = context.spans().single()
        assertEquals(mapOf("start" to attr("kept")), recorded.attributes)
        assertTrue(recorded.events.isEmpty())
        assertEquals(SpanStatus.Ok, recorded.status)
    }

    @Test
    fun `child of a settled parent records nothing`() = runTest {
        val context = InMemoryTelemetryContext()
        lateinit var span: TelemetrySpan
        context.startSpan(SpanOptions("pf.outer")) { captured ->
            span = captured
        }
        span.startSpan(SpanOptions("pf.late-child")) { }
        assertEquals(listOf("pf.outer"), context.spans().map { it.name })
    }

    @Test
    fun `setAttributes merge over start attributes with later values winning`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.merge", mapOf("a" to attr(1), "b" to attr("old")))) { span ->
            span.setAttributes(mapOf("b" to attr("new"), "c" to attr(false)))
        }
        assertEquals(
            mapOf("a" to attr(1), "b" to attr("new"), "c" to attr(false)),
            context.spans().single().attributes,
        )
    }

    @Test
    fun `snapshots are detached from later recording`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.snapshot", mapOf("a" to attr("1")))) { }
        val snapshot = context.spans().single()
        (snapshot.attributes as MutableMap<String, AttributeValue>)["a"] = attr("mutated")
        assertFalse(context.spans().single().attributes.isEmpty())
        assertEquals(attr("1"), context.spans().single().attributes["a"])
    }

    @Test
    fun `unsettled spans carry no end sequence`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.unsettled")) { span ->
            val recorded = context.spans().single()
            assertFalse(recorded.settled)
            assertNull(recorded.endSequence)
            span.setStatus(SpanStatus.Ok)
        }
    }
}
