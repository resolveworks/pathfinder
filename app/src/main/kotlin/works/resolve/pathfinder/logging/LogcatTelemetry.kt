package works.resolve.pathfinder.logging

import android.util.Log
import works.resolve.pathfinder.telemetry.SpanAttributes
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryContext
import works.resolve.pathfinder.telemetry.TelemetrySpan
import works.resolve.pathfinder.telemetry.AttributeValue
import works.resolve.pathfinder.telemetry.automaticErrorStatus
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's telemetry backend: renders spans, events, and statuses as
 * structured Logcat lines under one tag — pi's "logs" adapter case
 * (the telemetry README names OpenTelemetry/Sentry/logs as application
 * adapters; this is Pathfinder's).
 *
 * One line per span lifecycle step, so a span reads in logcat as:
 *
 * ```text
 * I Pathfinder: > pf.auth.login id=2 parent=- provider=openai-codex type=oauth
 * I Pathfinder: + pf.auth.login id=2 event=callback_received
 * E Pathfinder: < pf.auth.login id=2 status=error duration_ms=1837 error_name=java.lang.IllegalStateException error_message="OpenAI Codex token exchange failed (400): error=..." outcome=...
 * ```
 *
 * Error-status spans pass their throwable to `Log.e` so the stack trace (and
 * any `Caused by` chain) lands right under the end line; the
 * [TelemetryError.throwable] transport field exists for exactly this
 * (see [works.resolve.pathfinder.telemetry.AttributeValue] divergences).
 *
 * Semantics mirror the contract and [works.resolve.pathfinder.telemetry.InMemoryTelemetryContext]:
 * a callback throw settles the span with an automatic error status unless the
 * callback set one explicitly, the exception propagates unchanged, and
 * mutations after settle are ignored. Unlike the in-memory recorder, a child
 * span started from a settled parent still logs — logcat is an append-only
 * sink, so late lines are strictly more information, and span end lines carry
 * their own ids for correlation.
 *
 * Security: values are sanitized (control characters escaped, length capped)
 * before they reach logcat, and the whole strategy is metadata-only —
 * credentials, message text, and model responses must never be recorded
 * (the ported OAuth flows construct redacted error messages by design; that
 * redaction is what makes exception detail safe to log here).
 */
class LogcatTelemetryContext(
    private val tag: String = DEFAULT_TAG,
) : TelemetryContext {

    private val nextId = AtomicInteger(0)

    override suspend fun <T> startSpan(options: SpanOptions, callback: suspend (TelemetrySpan) -> T): T =
        startSpan(parentId = null, options, callback)

    private suspend fun <T> startSpan(
        parentId: Int?,
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T,
    ): T {
        val id = nextId.incrementAndGet()
        val startNanos = System.nanoTime()
        emit(
            level = INFO,
            line = renderStart(id, parentId, options.name, options.attributes),
            throwable = null,
        )

        val attributes = LinkedHashMap(options.attributes)
        var explicitStatus: SpanStatus? = null
        var settled = false

        val span = object : TelemetrySpan {
            override suspend fun <R> startSpan(
                childOptions: SpanOptions,
                childCallback: suspend (TelemetrySpan) -> R,
            ): R = this@LogcatTelemetryContext.startSpan(id, childOptions, childCallback)

            override fun addEvent(name: String, eventAttributes: SpanAttributes) {
                if (settled) return
                attributes += eventAttributes
                emit(
                    level = INFO,
                    line = renderEvent(id, options.name, name, eventAttributes),
                    throwable = null,
                )
            }

            override fun setAttributes(newAttributes: SpanAttributes) {
                if (settled) return
                attributes += newAttributes
            }

            override fun setStatus(status: SpanStatus) {
                if (settled) return
                explicitStatus = status
            }
        }

        return try {
            val result = callback(span)
            settled = true
            val status = explicitStatus ?: SpanStatus.Ok
            emitSpanEnd(id, options.name, status, attributes, startNanos)
            result
        } catch (error: Throwable) {
            settled = true
            val status = explicitStatus ?: automaticErrorStatus(error)
            emitSpanEnd(id, options.name, status, attributes, startNanos)
            throw error
        }
    }

    private fun emitSpanEnd(
        id: Int,
        name: String,
        status: SpanStatus,
        attributes: SpanAttributes,
        startNanos: Long,
    ) {
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000
        val error = (status as? SpanStatus.Error)?.error
        emit(
            level = if (error == null) INFO else ERROR,
            line = renderEnd(id, name, status, attributes, durationMs),
            throwable = error?.throwable,
        )
    }

    /** The single `android.util.Log` touchpoint; rendering stays pure for JVM tests. */
    private fun emit(level: Int, line: String, throwable: Throwable?) {
        when (level) {
            ERROR -> Log.e(tag, line, throwable)
            else -> Log.i(tag, line)
        }
    }

    private companion object {
        const val INFO = 4
        const val ERROR = 6
        const val DEFAULT_TAG = "Pathfinder"
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

/** Renders a span event line: `+ <name> id=<n> event=<event> <attributes>`. */
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

/** Renders a span end line with outcome, accumulated attributes, and duration. */
internal fun renderEnd(
    id: Int,
    name: String,
    status: SpanStatus,
    attributes: SpanAttributes,
    durationMs: Long,
): String = buildString {
    append("< ").append(name.asLogValue()).append(" id=").append(id)
    when (status) {
        is SpanStatus.Ok -> append(" status=ok")
        is SpanStatus.Error -> {
            append(" status=error")
            status.error?.let { error ->
                append(" error_name=").append(error.name.asLogValue())
                append(" error_message=").append(error.message.asLogValue())
            }
        }
    }
    append(" duration_ms=").append(durationMs)
    appendAttributes(attributes)
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

private const val MAX_VALUE_LENGTH = 500
