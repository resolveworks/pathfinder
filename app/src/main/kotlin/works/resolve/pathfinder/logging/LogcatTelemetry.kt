package works.resolve.pathfinder.logging

import android.util.Log
import works.resolve.pathfinder.telemetry.AttributeValue
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_CONTEXT
import works.resolve.pathfinder.telemetry.SpanAttributes
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryContext
import works.resolve.pathfinder.telemetry.TelemetrySpan
import works.resolve.pathfinder.telemetry.automaticErrorStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's telemetry backend — an Android application adapter over the
 * ported pi telemetry contract: each span renders as structured Logcat lines
 * under one tag, and recording is passive — adapter failures never prevent
 * or replace the callback's result or `Throwable`.
 *
 * Security boundary, deliberate divergences from a literal OTel/Sentry
 * adapter (app policy):
 * - **No free-form status messages.** `TelemetryError.message` is never
 *   rendered, even when non-empty: automatic error statuses may carry
 *   provider exception messages (provider bodies, paths, prompts) that are
 *   not a guaranteed-safe free-form surface. Only the low-cardinality
 *   `error_name` is emitted ([PathfinderDiagnostics] records type-only
 *   statuses).
 * - **No `Throwable` is ever passed to `android.util.Log`.** Exception
 *   messages and causes stay out of logcat; the callback `Throwable` is
 *   rendered only as bounded, defensively-read stack-frame metadata
 *   (`class.method(File:line)`), capped in frames and length.
 *
 * [sink] and [nanoTime] are internal seams so JVM tests can drive lifecycle,
 * passivity, and duration without `android.util.Log`; production uses the
 * default Android sink and [System.nanoTime].
 */
class LogcatTelemetryContext internal constructor(
    private val tag: String = DEFAULT_TAG,
    private val sink: LogSink = ANDROID_LOG_SINK,
    private val nanoTime: () -> Long = System::nanoTime,
) : TelemetryContext {

    /**
     * The single logcat touchpoint. Receives a pre-rendered line; `isError`
     * selects `Log.e` vs `Log.i`. Never receives a `Throwable`.
     */
    internal fun interface LogSink {
        fun log(isError: Boolean, tag: String, message: String)
    }

    private val nextId = AtomicInteger(0)

    override suspend fun <T> startSpan(options: SpanOptions, callback: suspend (TelemetrySpan) -> T): T =
        startSpan(parentId = null, options, callback)

    private suspend fun <T> startSpan(
        parentId: Int?,
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T,
    ): T {
        // One atomic admission: copy the payload before consuming an id, and
        // route the whole span through the no-op context if anything about it
        // is unreadable (the callback still runs, exactly once, passively).
        val id: Int
        val startAttributes: SpanAttributes
        try {
            startAttributes = copyAttributes(options.attributes)
            id = nextId.incrementAndGet()
        } catch (_: Throwable) {
            return NOOP_TELEMETRY_CONTEXT.startSpan(options, callback)
        }

        val span = SpanState(id, parentId, options.name, startAttributes)
        emitPassively(isError = false) { renderStart(id, parentId, options.name, startAttributes) }

        val startNanos = nanoTime()
        return try {
            val result = callback(span)
            span.settle(failed = false, error = null, startNanos, nanoTime())
            result
        } catch (error: Throwable) {
            span.settle(failed = true, error = error, startNanos, nanoTime())
            throw error
        }
    }

    /**
     * One span's mutable recording state. All fields are atomics/volatile —
     * no lock is ever held across the business callback or a sink call.
     */
    private inner class SpanState(
        val id: Int,
        val parentId: Int?,
        val name: String,
        startAttributes: SpanAttributes,
    ) : TelemetrySpan {
        private val attributes = AtomicReference(startAttributes)
        private val explicitStatus = AtomicReference<SpanStatus?>(null)
        private val settled = AtomicBoolean(false)

        override suspend fun <R> startSpan(
            childOptions: SpanOptions,
            childCallback: suspend (TelemetrySpan) -> R,
        ): R = if (settled.get()) {
            // Post-settlement admission is inert: the child callback still
            // runs via the no-op context, but nothing is recorded.
            NOOP_TELEMETRY_CONTEXT.startSpan(childOptions, childCallback)
        } else {
            this@LogcatTelemetryContext.startSpan(id, childOptions, childCallback)
        }

        override fun addEvent(eventName: String, eventAttributes: SpanAttributes) {
            if (settled.get()) return
            // Event attributes stay event-only: they never merge into the
            // span's attribute bag.
            try {
                val copied = copyAttributes(eventAttributes)
                emitPassively(isError = false) { renderEvent(id, name, eventName, copied) }
            } catch (_: Throwable) {
                // Passive: ignore malformed or unreadable payloads.
            }
        }

        override fun setAttributes(newAttributes: SpanAttributes) {
            if (settled.get()) return
            // Copy-first, then CAS-publish: the call either lands whole or
            // not at all, and a concurrent writer is never lost.
            while (true) {
                val current = attributes.get()
                try {
                    val merged = LinkedHashMap<String, AttributeValue>(current.size + newAttributes.size)
                    current.forEach { (key, value) -> merged[key] = value }
                    newAttributes.forEach { (key, value) -> merged[key] = copyAttributeValue(value) }
                    if (attributes.compareAndSet(current, merged)) return
                } catch (_: Throwable) {
                    // Passive; the partial copy is discarded.
                    return
                }
            }
        }

        override fun setStatus(status: SpanStatus) {
            if (settled.get()) return
            try {
                explicitStatus.set(status)
            } catch (_: Throwable) {
                // Recording is passive.
            }
        }

        /** First settle wins; emits the end line at `error` level for every error status. */
        fun settle(failed: Boolean, error: Throwable?, startNanos: Long, endNanos: Long) {
            if (!settled.compareAndSet(false, true)) return
            try {
                val status = explicitStatus.get() ?: if (failed) error?.let(::automaticErrorStatus) ?: SpanStatus.Error() else SpanStatus.Ok
                val isError = status is SpanStatus.Error
                val stack = if (isError && error != null) renderStack(error) else null
                val durationMs = (endNanos - startNanos) / 1_000_000
                emitPassively(isError) { renderEnd(id, name, status, attributes.get(), durationMs, stack) }
            } catch (_: Throwable) {
                // Passive; the business result/exception is already on its way.
            }
        }
    }

    /** Emits one line, swallowing any rendering or sink failure. */
    private inline fun emitPassively(isError: Boolean, line: () -> String) {
        try {
            sink.log(isError, tag, line())
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val DEFAULT_TAG = "Pathfinder"
        val ANDROID_LOG_SINK = LogSink { isError, tag, message ->
            if (isError) Log.e(tag, message) else Log.i(tag, message)
        }
    }
}

/** Renders a span start line: `> <name> id=<n> parent=<id|-> <attributes>`. */
internal fun renderStart(
    id: Int,
    parentId: Int?,
    name: String,
    attributes: SpanAttributes,
): String = buildString {
    append("> ").append(name.asLogValue()).append(" id=").append(id)
    append(" parent=").append(parentId?.toString() ?: "-")
    appendAttributes(attributes)
}

/** Renders a span event line: `+ <name> id=<n> event=<event> <attributes>` (event attributes only). */
internal fun renderEvent(
    id: Int,
    name: String,
    eventName: String,
    attributes: SpanAttributes,
): String = buildString {
    append("+ ").append(name.asLogValue()).append(" id=").append(id)
    append(" event=").append(eventName.asLogValue())
    appendAttributes(attributes)
}

/**
 * Renders a span end line with outcome, accumulated attributes, and duration;
 * [stack] carries the callback `Throwable`'s bounded stack-frame metadata on
 * error end lines.
 */
internal fun renderEnd(
    id: Int,
    name: String,
    status: SpanStatus,
    attributes: SpanAttributes,
    durationMs: Long,
    stack: String? = null,
): String = buildString {
    append("< ").append(name.asLogValue()).append(" id=").append(id)
    when (status) {
        is SpanStatus.Ok -> append(" status=ok")
        is SpanStatus.Error -> {
            append(" status=error")
            status.error?.let { error -> append(" error_name=").append(error.name.asLogValue()) }
        }
    }
    append(" duration_ms=").append(durationMs)
    appendAttributes(attributes)
    stack?.let { append(" stack=").append(it.asLogValue()) }
}

/**
 * Bounded, type-only stack metadata for [error]: at most [MAX_STACK_FRAMES]
 * `class.method(File:line)` frames joined with `;`, capped to
 * [MAX_STACK_LENGTH]. No exception message, cause, or cause chain is ever
 * included.
 */
internal fun renderStack(error: Throwable): String? = try {
    error.stackTrace
        .asSequence()
        .take(MAX_STACK_FRAMES)
        .joinToString(";") { frame ->
            buildString {
                append(frame.className.substringAfterLast('.').asLogValue())
                append('.')
                append(frame.methodName.asLogValue())
                append('(')
                append(frame.fileName?.asLogValue() ?: "-")
                append(':')
                append(frame.lineNumber)
                append(')')
            }
        }
        .take(MAX_STACK_LENGTH)
        .takeIf { it.isNotEmpty() }
} catch (_: Throwable) {
    null
}

private fun StringBuilder.appendAttributes(attributes: SpanAttributes) {
    attributes.forEach { (key, value) ->
        append(' ').append(key.asLogValue()).append('=').append(value.render())
    }
}

private fun AttributeValue.render(): String = when (this) {
    is AttributeValue.Str -> value.asLogValue()
    is AttributeValue.Num -> value.toString()
    is AttributeValue.Bool -> value.toString()
    is AttributeValue.Strs -> values.joinToString(",", "[", "]") { it.asLogValue() }
    is AttributeValue.Nums -> values.joinToString(",", "[", "]")
    is AttributeValue.Bools -> values.joinToString(",", "[", "]")
}

/**
 * Log-safe token: control characters escaped, length capped, and quoted when
 * it contains whitespace or is empty (so a poisoned value can never forge or
 * corrupt the `key=value` structure).
 */
internal fun String.asLogValue(): String {
    val sanitized = replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .take(MAX_VALUE_LENGTH)
    return if (sanitized.any(Char::isWhitespace) || sanitized.isEmpty()) {
        "\"${sanitized.replace("\"", "\\\"")}\""
    } else {
        sanitized
    }
}

private fun copyAttributeValue(value: AttributeValue): AttributeValue = when (value) {
    is AttributeValue.Str -> value
    is AttributeValue.Num -> value
    is AttributeValue.Bool -> value
    is AttributeValue.Strs -> AttributeValue.Strs(value.values.toList())
    is AttributeValue.Nums -> AttributeValue.Nums(value.values.toList())
    is AttributeValue.Bools -> AttributeValue.Bools(value.values.toList())
}

private fun copyAttributes(attributes: SpanAttributes): SpanAttributes {
    val copy = LinkedHashMap<String, AttributeValue>(attributes.size)
    attributes.forEach { (name, value) -> copy[name] = copyAttributeValue(value) }
    return copy
}

private const val MAX_VALUE_LENGTH = 500
private const val MAX_STACK_FRAMES = 8
private const val MAX_STACK_LENGTH = 1000
