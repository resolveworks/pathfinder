package works.resolve.pathfinder.telemetry

/**
 * Backend-neutral reference implementation recording spans in process memory,
 * ported symbol-for-symbol from pi `packages/telemetry/src/memory.ts`.
 *
 * Create a fresh instance to isolate tests or independent recording scopes;
 * [getSpans] returns detached snapshots in span-start order. Recording is
 * passive (pi's try/catch guarantees): exceptions from attribute copies or
 * status copies are swallowed, never propagated. Spans started from an
 * already-settled parent record nothing (pi routes them through the no-op
 * context). A callback exception settles the span with
 * [automaticErrorStatus] unless the callback already set an explicit status.
 *
 * Kotlin divergence from pi (single-threaded JS): coroutines may admit spans,
 * record, and settle concurrently from multiple threads, so all state
 * mutation is guarded by one context lock. The lock is never held across the
 * business callback — each recording call and settlement takes and releases
 * it, matching pi's observable semantics under concurrency.
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
    ) {
        var attributes: SpanAttributes = emptyMap()
        val events = mutableListOf<RecordedTelemetryEvent>()
        var status: SpanStatus = SpanStatus.Ok
        var explicitStatus: Boolean = false
        var settled: Boolean = false
        var endSequence: Int? = null
    }

    /** Guards [spans] and the id/sequence counters; never held across callbacks. */
    private val lock = Any()
    private val spans = mutableListOf<MutableSpan>()
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
        // One atomic admission, mirroring pi's synchronous settled-parent check
        // followed immediately by createSpan: the parent cannot settle between
        // the check and the append. pi's createSpan copies options.attributes
        // before consuming nextSpanId, so an unreadable payload wastes no ID.
        val admitted: MutableSpan? = synchronized(lock) {
            if (parent?.settled == true) return@synchronized null
            try {
                val attributes = copyAttributes(options.attributes)
                MutableSpan(
                    id = nextSpanId,
                    parentId = parent?.id,
                    name = options.name,
                ).also { span ->
                    span.attributes = attributes
                    spans += span
                    nextSpanId++
                }
            } catch (_: Throwable) {
                null
            }
        }
        val recorded = admitted ?: return NOOP_TELEMETRY_CONTEXT.startSpan(options, callback)

        val span: TelemetrySpan = object : TelemetrySpan {
            override suspend fun <R> startSpan(
                options: SpanOptions,
                callback: suspend (TelemetrySpan) -> R,
            ): R = this@InMemoryTelemetryContext.startSpan(recorded, options, callback)

            override fun addEvent(name: String, attributes: SpanAttributes) {
                synchronized(lock) {
                    if (recorded.settled) return
                    try {
                        recorded.events += RecordedTelemetryEvent(name, copyAttributes(attributes))
                    } catch (_: Throwable) {
                        // Recording is passive. Ignore malformed or unreadable telemetry payloads.
                    }
                }
            }

            override fun setAttributes(attributes: SpanAttributes) {
                synchronized(lock) {
                    if (recorded.settled) return
                    try {
                        recorded.attributes = mergeAttributes(recorded.attributes, attributes)
                    } catch (_: Throwable) {
                        // Recording is passive. Ignore malformed or unreadable telemetry payloads.
                    }
                }
            }

            override fun setStatus(status: SpanStatus) {
                synchronized(lock) {
                    if (recorded.settled) return
                    try {
                        recorded.status = copyStatus(status)
                        recorded.explicitStatus = true
                    } catch (_: Throwable) {
                        // Recording is passive. Ignore malformed or unreadable telemetry payloads.
                    }
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

    /** pi `settleSpan` (under [lock]); the automatic error never marks the status explicit. */
    private fun settleSpan(span: MutableSpan, failed: Boolean, error: Throwable?) {
        synchronized(lock) {
            if (span.settled) return
            if (failed && !span.explicitStatus) {
                span.status = error?.let(::automaticErrorStatus) ?: SpanStatus.Error()
            }
            span.settled = true
            span.endSequence = nextEndSequence++
        }
    }

    /** Returns detached snapshots in span-start order (pi `getSpans`). */
    fun getSpans(): List<RecordedTelemetrySpan> = synchronized(lock) {
        spans.map { span ->
            RecordedTelemetrySpan(
                id = span.id,
                parentId = span.parentId,
                name = span.name,
                attributes = copyAttributes(span.attributes),
                events = span.events.map { RecordedTelemetryEvent(it.name, copyAttributes(it.attributes)) },
                status = copyStatus(span.status),
                settled = span.settled,
                endSequence = span.endSequence,
            )
        }
    }
}

/** pi `copyAttributeValue`: scalars pass through, arrays copy defensively. */
private fun copyAttributeValue(value: AttributeValue): AttributeValue = when (value) {
    is AttributeValue.Str -> value
    is AttributeValue.Num -> value
    is AttributeValue.Bool -> value
    is AttributeValue.Strs -> AttributeValue.Strs(value.values.toList())
    is AttributeValue.Nums -> AttributeValue.Nums(value.values.toList())
    is AttributeValue.Bools -> AttributeValue.Bools(value.values.toList())
}

/** pi `copyAttributes`. */
private fun copyAttributes(attributes: SpanAttributes): SpanAttributes {
    val copy = LinkedHashMap<String, AttributeValue>(attributes.size)
    attributes.forEach { (name, value) -> copy[name] = copyAttributeValue(value) }
    return copy
}

/** pi `mergeAttributes`. */
private fun mergeAttributes(current: SpanAttributes, attributes: SpanAttributes): SpanAttributes {
    val merged = LinkedHashMap<String, AttributeValue>(current.size + attributes.size)
    current.forEach { (name, value) -> merged[name] = copyAttributeValue(value) }
    attributes.forEach { (name, value) -> merged[name] = copyAttributeValue(value) }
    return merged
}

/** pi `copyStatus`: snapshots carry the serializable shape only. */
private fun copyStatus(status: SpanStatus): SpanStatus = when (status) {
    is SpanStatus.Ok -> SpanStatus.Ok
    is SpanStatus.Error -> status.error?.let {
        SpanStatus.Error(TelemetryError(it.name, it.message))
    } ?: SpanStatus.Error()
}
