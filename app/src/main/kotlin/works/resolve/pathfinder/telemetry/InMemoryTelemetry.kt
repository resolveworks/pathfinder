package works.resolve.pathfinder.telemetry

/**
 * Backend-neutral reference implementation recording spans in process memory,
 * ported symbol-for-symbol from pi `packages/telemetry/src/memory.ts`.
 *
 * Create a fresh instance to isolate tests or independent recording scopes;
 * [spans] returns detached snapshots in span-start order. Recording is
 * passive (pi's try/catch guarantees): exceptions from attribute maps or
 * status copies are swallowed, never propagated. Spans started from an
 * already-settled parent record nothing (pi routes them through the no-op
 * context). A callback exception settles the span with
 * [automaticErrorStatus] unless the callback already set an explicit status.
 *
 * Recorded statuses drop the [TelemetryError.throwable] transport field,
 * matching pi's serializable recording shape (`{ name, message }` only).
 */
class InMemoryTelemetryContext : TelemetryContext {

    /** Port of pi `RecordedTelemetryEvent`. */
    data class RecordedTelemetryEvent(
        val name: String,
        val attributes: SpanAttributes,
    )

    /** Port of pi `RecordedTelemetrySpan` (a detached snapshot). */
    data class RecordedTelemetrySpan(
        val id: Int,
        val parentId: Int?,
        val name: String,
        val attributes: SpanAttributes,
        val events: List<RecordedTelemetryEvent>,
        val status: SpanStatus,
        val settled: Boolean,
        /** Assigned in settle order when the span ends; null while in flight. */
        val endSequence: Int?,
    )

    /** pi's mutable recorded span (internal; snapshots only escape). */
    private class MutableSpan(
        val id: Int,
        val parentId: Int?,
        val name: String,
        attributes: SpanAttributes,
    ) {
        var attributes: SpanAttributes = attributes
            private set
        val events = mutableListOf<RecordedTelemetryEvent>()
        var status: SpanStatus = SpanStatus.Ok
            private set
        var explicitStatus: Boolean = false
            private set
        var settled: Boolean = false
            private set
        var endSequence: Int? = null
            private set

        fun addEvent(event: RecordedTelemetryEvent) {
            events += event
        }

        fun mergeAttributes(attributes: SpanAttributes) {
            val merged = LinkedHashMap(this.attributes)
            merged.putAll(attributes)
            this.attributes = merged
        }

        fun setStatus(status: SpanStatus) {
            this.status = copyStatus(status)
            explicitStatus = true
        }

        /** pi settleSpan's automatic error assignment: never marks the status explicit. */
        fun setAutomaticStatus(status: SpanStatus) {
            this.status = status
        }

        fun settle() {
            settled = true
        }

        fun assignEndSequence(sequence: Int) {
            endSequence = sequence
        }
    }

    private var spans = mutableListOf<MutableSpan>()
    private var nextSpanId = 1
    private var nextEndSequence = 1

    override suspend fun <T> startSpan(
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T,
    ): T = startSpan(parent = null, options, callback)

    private suspend fun <T> startSpan(
        parent: MutableSpan?,
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T,
    ): T {
        if (parent?.settled == true) return NOOP_TELEMETRY_CONTEXT.startSpan(options, callback)

        val recorded: MutableSpan
        try {
            recorded = MutableSpan(
                id = nextSpanId++,
                parentId = parent?.id,
                name = options.name,
                attributes = LinkedHashMap(options.attributes),
            )
            spans += recorded
        } catch (_: Exception) {
            return NOOP_TELEMETRY_CONTEXT.startSpan(options, callback)
        }

        val span: TelemetrySpan = object : TelemetrySpan {
            override suspend fun <R> startSpan(
                childOptions: SpanOptions,
                childCallback: suspend (TelemetrySpan) -> R,
            ): R = this@InMemoryTelemetryContext.startSpan(recorded, childOptions, childCallback)

            override fun addEvent(name: String, attributes: SpanAttributes) {
                if (recorded.settled) return
                try {
                    recorded.addEvent(RecordedTelemetryEvent(name, LinkedHashMap(attributes)))
                } catch (_: Exception) {
                    // Recording is passive. Ignore malformed or unreadable telemetry payloads.
                }
            }

            override fun setAttributes(attributes: SpanAttributes) {
                if (recorded.settled) return
                try {
                    recorded.mergeAttributes(attributes)
                } catch (_: Exception) {
                    // Recording is passive. Ignore malformed or unreadable telemetry payloads.
                }
            }

            override fun setStatus(status: SpanStatus) {
                if (recorded.settled) return
                try {
                    recorded.setStatus(status)
                } catch (_: Exception) {
                    // Recording is passive. Ignore malformed or unreadable telemetry payloads.
                }
            }
        }

        try {
            val result = callback(span)
            settleSpan(recorded, failed = false, error = null)
            return result
        } catch (error: Throwable) {
            settleSpan(recorded, failed = true, error = error)
            throw error
        }
    }

    private fun settleSpan(span: MutableSpan, failed: Boolean, error: Throwable?) {
        if (span.settled) return
        if (failed && !span.explicitStatus) span.setAutomaticStatus(error?.let(::automaticErrorStatus) ?: SpanStatus.Error())
        span.settle()
        span.assignEndSequence(nextEndSequence++)
    }

    /** Returns detached snapshots in span-start order (pi `getSpans`). */
    fun getSpans(): List<RecordedTelemetrySpan> = try {
        spans.map { span ->
            RecordedTelemetrySpan(
                id = span.id,
                parentId = span.parentId,
                name = span.name,
                attributes = LinkedHashMap(span.attributes),
                events = span.events.map { RecordedTelemetryEvent(it.name, LinkedHashMap(it.attributes)) },
                status = copyStatus(span.status),
                settled = span.settled,
                endSequence = span.endSequence,
            )
        }
    } catch (_: Exception) {
        // Recording is passive; a snapshot failure yields an empty view.
        emptyList()
    }
}

/** pi `copyStatus`: snapshots carry the serializable shape only. */
private fun copyStatus(status: SpanStatus): SpanStatus = when (status) {
    is SpanStatus.Ok -> SpanStatus.Ok
    is SpanStatus.Error -> status.error?.let {
        SpanStatus.Error(TelemetryError(it.name, it.message))
    } ?: SpanStatus.Error()
}
