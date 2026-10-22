package works.resolve.pathfinder.telemetry

/**
 * Telemetry describes what the app did while running: timed [spans][TelemetrySpan]
 * with attributes, point [events][TelemetrySpan.addEvent], a final
 * [status][TelemetrySpan.setStatus], and optional low-cardinality metrics. It is
 * diagnostic data, not business state: recording must never change whether an
 * operation runs, succeeds, fails, or persists, and every implementation is
 * passive — a recording failure must never propagate.
 *
 * Divergences from pi:
 * - **Promises → coroutines.** pi's `startSpan` callback returns `T |
 *   Promise<T>`; this port's callback is `suspend (TelemetrySpan) -> T`, so
 *   async operations await naturally inside the span. Kotlin exceptions are
 *   always `Throwable`, so pi's non-`Error` rejection values have no
 *   counterpart; [automaticErrorStatus] keeps pi's passivity.
 * - pi's typed-schema layer (`TelemetrySchemaDefinition` and friends) is
 *   omitted: its exact-type inference is TypeScript-only. Span vocabularies
 *   are documented constants next to their producers instead.
 * - Propagation carriers, span links, stored traces, and trace rendering are
 *   omitted. No ported runtime code consumes them; only the metrics capability
 *   is carried because `pi.ai.request` instrumentation records histograms and
 *   counters through its parent span.
 *
 * Security: telemetry is operational metadata. Credentials, message text,
 * and model responses must never be passed as attributes or error messages.
 */
sealed interface AttributeValue {
    @JvmInline
    value class Str(val value: String) : AttributeValue

    /** Numbers box as [Number] so both integral and fractional values fit one case. */
    @JvmInline
    value class Num(val value: Number) : AttributeValue

    @JvmInline
    value class Bool(val value: Boolean) : AttributeValue

    data class Strs(val values: List<String>) : AttributeValue

    data class Nums(val values: List<Number>) : AttributeValue

    data class Bools(val values: List<Boolean>) : AttributeValue
}

/** Iteration order is producer order. */
typealias SpanAttributes = Map<String, AttributeValue>

data class SpanOptions(val name: String, val attributes: SpanAttributes = emptyMap())

data class TelemetryError(val name: String, val message: String)

sealed interface SpanStatus {
    data object Ok : SpanStatus

    data class Error(val error: TelemetryError? = null) : SpanStatus
}

data class InstrumentOptions(val description: String? = null, val unit: String? = null)

data class HistogramOptions(
    val description: String? = null,
    val unit: String? = null,
    /** Finite, strictly increasing bucket hints; adapters own aggregation. */
    val boundaries: List<Double>? = null
)

interface TelemetryCounter {
    fun add(value: Double, attributes: SpanAttributes = emptyMap())
}

interface TelemetryUpDownCounter {
    fun add(value: Double, attributes: SpanAttributes = emptyMap())
}

interface TelemetryHistogram {
    fun record(
        value: Double,
        attributes: SpanAttributes = emptyMap(),
        context: TelemetryContext? = null
    )
}

/** Optional adapter capability with synchronous factories and recording methods. */
interface TelemetryMetrics {
    fun counter(name: String, options: InstrumentOptions? = null): TelemetryCounter
    fun upDownCounter(name: String, options: InstrumentOptions? = null): TelemetryUpDownCounter
    fun histogram(name: String, options: HistogramOptions? = null): TelemetryHistogram
}

/** Also a [TelemetryContext], so child spans nest under this span. */
interface TelemetrySpan : TelemetryContext {
    fun addEvent(name: String, attributes: SpanAttributes = emptyMap())

    /** Merges attributes into the span (later values win). */
    fun setAttributes(attributes: SpanAttributes)

    /** Sets the final outcome; overrides the automatic error status of a throw. */
    fun setStatus(status: SpanStatus)
}

/**
 * Starts a span around `callback`. The span settles
 * when the callback returns (status ok unless overridden) or throws (status
 * error built from the exception unless [TelemetrySpan.setStatus] already
 * set one); the callback's result or exception propagates unchanged.
 *
 * [metrics] is the adapter capability carried by roots, spans, and extracted
 * parents. Null means a tracing-only adapter.
 */
interface TelemetryContext {
    val metrics: TelemetryMetrics? get() = null

    suspend fun <T> startSpan(options: SpanOptions, callback: suspend (TelemetrySpan) -> T): T
}

fun attr(value: String): AttributeValue = AttributeValue.Str(value)
fun attr(value: Number): AttributeValue = AttributeValue.Num(value)
fun attr(value: Boolean): AttributeValue = AttributeValue.Bool(value)

/** Array conversions defensively copy the caller's values. */
fun attr(vararg values: String): AttributeValue = AttributeValue.Strs(values.toList())
fun attr(vararg values: Number): AttributeValue = AttributeValue.Nums(values.toList())
fun attr(vararg values: Boolean): AttributeValue = AttributeValue.Bools(values.toList())

/**
 * An error status whose detail is read passively. pi reads JS `error.name`,
 * which for `Error` and its subclasses is the class's short name; the closest
 * Kotlin mirror is the exception class's [simpleName][Class.getSimpleName],
 * falling back to the nearest named superclass (anonymous classes have no
 * simple name).
 */
fun automaticErrorStatus(error: Throwable): SpanStatus = try {
    val name = error::class.simpleName
        ?: generateSequence<Class<*>>(error::class.java.superclass) { it.superclass }
            .firstNotNullOfOrNull { it.simpleName }
        ?: "Throwable"
    SpanStatus.Error(TelemetryError(name, error.message.orEmpty()))
} catch (_: Throwable) {
    // Error inspection is passive. Fall through to an error status without details.
    SpanStatus.Error()
}

private object NoopCounter : TelemetryCounter {
    override fun add(value: Double, attributes: SpanAttributes) {}
}

private object NoopUpDownCounter : TelemetryUpDownCounter {
    override fun add(value: Double, attributes: SpanAttributes) {}
}

private object NoopHistogram : TelemetryHistogram {
    override fun record(value: Double, attributes: SpanAttributes, context: TelemetryContext?) {}
}

/** Shared instruments that never inspect or retain metadata or measurements. */
object NoopTelemetryMetrics : TelemetryMetrics {
    override fun counter(name: String, options: InstrumentOptions?): TelemetryCounter = NoopCounter
    override fun upDownCounter(name: String, options: InstrumentOptions?): TelemetryUpDownCounter =
        NoopUpDownCounter

    override fun histogram(name: String, options: HistogramOptions?): TelemetryHistogram =
        NoopHistogram
}

/** The span is its own context so child spans stay no-ops too. */
object NoopTelemetry : TelemetrySpan {
    override val metrics: TelemetryMetrics get() = NoopTelemetryMetrics

    override suspend fun <T> startSpan(
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T
    ): T = callback(this)

    override fun addEvent(name: String, attributes: SpanAttributes) {}
    override fun setAttributes(attributes: SpanAttributes) {}
    override fun setStatus(status: SpanStatus) {}
}

val NOOP_TELEMETRY_METRICS: TelemetryMetrics = NoopTelemetryMetrics

val NOOP_TELEMETRY_CONTEXT: TelemetrySpan = NoopTelemetry
