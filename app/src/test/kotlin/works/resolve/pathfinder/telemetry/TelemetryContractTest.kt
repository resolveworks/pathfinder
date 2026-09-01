package works.resolve.pathfinder.telemetry

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the ported telemetry contract
 * (`packages/telemetry/src/index.ts` + `memory.ts` and the adapter
 * conformance semantics of `src/testing/conformance.ts`): no-op passthrough,
 * in-memory recording (ids, parents, settle order, snapshots), automatic
 * error statuses, explicit-status precedence and last-write-wins, attribute
 * merging with defensive array copies, event ordering, nested and concurrent
 * child parentage, post-settle passivity, and callback exception identity.
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
        val spans = context.getSpans()
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
        val span = context.getSpans().single()
        val status = span.status as SpanStatus.Error
        // JS Error.name is the error class's short name; Kotlin mirrors it with simpleName.
        assertEquals("IllegalStateException", status.error?.name)
        assertEquals("redacted-by-construction detail", status.error?.message)
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
        val status = context.getSpans().single().status as SpanStatus.Error
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
        val recorded = context.getSpans().single()
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
        assertEquals(listOf("pf.outer"), context.getSpans().map { it.name })
    }

    @Test
    fun `setAttributes merge over start attributes with later values winning`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.merge", mapOf("a" to attr(1), "b" to attr("old")))) { span ->
            span.setAttributes(mapOf("b" to attr("new"), "c" to attr(false)))
        }
        assertEquals(
            mapOf("a" to attr(1), "b" to attr("new"), "c" to attr(false)),
            context.getSpans().single().attributes,
        )
    }

    @Test
    fun `anonymous exception class falls back to the nearest named superclass`() = runTest {
        val context = InMemoryTelemetryContext()
        val thrown = object : RuntimeException("named by superclass") {}
        try {
            context.startSpan(SpanOptions("pf.anonymous")) { throw thrown }
            throw AssertionError("expected rethrow")
        } catch (error: RuntimeException) {
            assertSame(thrown, error)
        }
        val status = context.getSpans().single().status as SpanStatus.Error
        // An anonymous Kotlin exception is still an Error instance to pi, never a non-Error rejection.
        assertEquals("RuntimeException", status.error?.name)
        assertEquals("named by superclass", status.error?.message)
    }

    @Test
    fun `repeated setStatus calls are last-write-wins and suppress the automatic status`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.last-status")) { span ->
            span.setStatus(SpanStatus.Error(TelemetryError("Expected", "first")))
            span.setStatus(SpanStatus.Ok)
        }
        assertEquals(SpanStatus.Ok, context.getSpans().single().status)

        try {
            context.startSpan(SpanOptions("pf.explicit-before-throw")) { span ->
                span.setStatus(SpanStatus.Ok)
                throw IllegalStateException("suppressed")
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals(SpanStatus.Ok, context.getSpans().last().status)
    }

    @Test
    fun `array attributes are recorded as defensive copies`() = runTest {
        val context = InMemoryTelemetryContext()
        val startModels = mutableListOf("gpt-4o")
        val eventIds = mutableListOf<Number>(1)
        context.startSpan(SpanOptions("pf.arrays", mapOf("models" to attr(*startModels.toTypedArray())))) { span ->
            startModels.add("o3") // later caller mutation must not leak in
            span.setAttributes(mapOf("codes" to attr(*arrayOf<Number>(4, 2))))
            span.addEvent("listed", mapOf("ids" to attr(*eventIds.toTypedArray())))
            eventIds.add(2)
            span.setAttributes(mapOf("flags" to attr(*booleanArrayOf(true, false))))
        }
        val recorded = context.getSpans().single()
        assertEquals(
            mapOf(
                "models" to attr(*arrayOf<String>("gpt-4o")),
                "codes" to attr(*arrayOf<Number>(4, 2)),
                "flags" to attr(*booleanArrayOf(true, false)),
            ),
            recorded.attributes,
        )
        assertEquals(
            listOf(InMemoryTelemetryContext.RecordedTelemetryEvent("listed", mapOf("ids" to attr(*arrayOf<Number>(1))))),
            recorded.events,
        )
        // Snapshots are detached: mutating a recorded array does not affect later snapshots.
        val array = recorded.attributes["codes"] as AttributeValue.Nums
        (array.values as MutableList<Number>).add(99)
        assertEquals(attr(*arrayOf<Number>(4, 2)), context.getSpans().single().attributes["codes"])
    }

    @Test
    fun `later setAttributes replace earlier array values for the same key`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.replace")) { span ->
            span.setAttributes(mapOf("attempted" to attr(*arrayOf<String>("a"))))
            span.setAttributes(mapOf("attempted" to attr(*arrayOf<String>("a", "b"))))
        }
        assertEquals(
            mapOf("attempted" to attr(*arrayOf<String>("a", "b"))),
            context.getSpans().single().attributes,
        )
    }

    @Test
    fun `concurrent children record parentage and settle order`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.parent")) { parent ->
            val first = launch {
                parent.startSpan(SpanOptions("pf.first-child")) { child ->
                    delay(10)
                    child.setAttributes(mapOf("order" to attr("first")))
                }
            }
            val second = launch {
                parent.startSpan(SpanOptions("pf.second-child")) { }
            }
            second.join()
            first.join()
        }
        val spans = context.getSpans()
        assertEquals(listOf("pf.parent", "pf.first-child", "pf.second-child"), spans.map { it.name })
        val parent = spans.first { it.name == "pf.parent" }
        val first = spans.first { it.name == "pf.first-child" }
        val second = spans.first { it.name == "pf.second-child" }
        assertNull(parent.parentId)
        assertEquals(parent.id, first.parentId)
        assertEquals(parent.id, second.parentId)
        // Second settles before first; the parent settles last.
        val secondEnd = requireNotNull(second.endSequence)
        val firstEnd = requireNotNull(first.endSequence)
        val parentEnd = requireNotNull(parent.endSequence)
        assertTrue(secondEnd < firstEnd && firstEnd < parentEnd)
        assertEquals(mapOf("order" to attr("first")), first.attributes)
    }

    @Test
    fun `snapshots are detached from later recording`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.snapshot", mapOf("a" to attr("1")))) { }
        val snapshot = context.getSpans().single()
        (snapshot.attributes as MutableMap<String, AttributeValue>)["a"] = attr("mutated")
        assertFalse(context.getSpans().single().attributes.isEmpty())
        assertEquals(attr("1"), context.getSpans().single().attributes["a"])
    }

    @Test
    fun `unsettled spans carry no end sequence`() = runTest {
        val context = InMemoryTelemetryContext()
        context.startSpan(SpanOptions("pf.unsettled")) { span ->
            val recorded = context.getSpans().single()
            assertFalse(recorded.settled)
            assertNull(recorded.endSequence)
            span.setStatus(SpanStatus.Ok)
        }
    }
}
