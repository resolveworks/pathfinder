package works.resolve.pathfinder.telemetry

/**
 * Vendor-neutral telemetry contracts, ported from pi
 * `packages/telemetry/src/index.ts` (runtime contract only).
 *
 * Telemetry describes what the app did while running: timed [spans][TelemetrySpan]
 * with attributes, point [events][TelemetrySpan.addEvent], and a final
 * [status][TelemetrySpan.setStatus]. It is diagnostic data, not business
 * state: recording must never change whether an operation runs, succeeds,
 * fails, or persists, and every implementation is passive — a recording
 * failure must never propagate.
 *
 * Backends adapt these concepts to their own world (pi names OpenTelemetry,
 * Sentry, logs). This app's backend is the Logcat adapter in
 * `works.resolve.pathfinder.logging`; tests use [InMemoryTelemetryContext];
 * [NOOP_TELEMETRY_CONTEXT] is the default seam when no backend is provided.
 *
 * Divergences from pi (documented per AGENTS.md, each as narrow as possible):
 * - **Promises → coroutines.** pi's `startSpan` callback returns `T |
 *   Promise<T>`; this port's callback is `suspend (TelemetrySpan) -> T`, so
 *   async operations await naturally inside the span.
 * - **Attribute values.** pi's `AttributeValue` union is
 *   `string | number | boolean | readonly arrays`; this port keeps the three
 *   scalar shapes ([Str], [Num], [Bool]) and drops the array shapes until a
 *   span vocabulary needs them. Numbers box as [Number] so [Num] renders
 *   integral and fractional values without a second case.
 * - **Throwable on error statuses.** pi's serializable status shape carries
 *   `{ name, message }` only; [TelemetryError] additionally keeps the
 *   throwable so log-style backends can print stack traces. It is transport
 *   for the backend, not recorded data: [InMemoryTelemetryContext] snapshots
 *   drop it, matching pi's recording shape.
 * - The typed-schema layer (`TelemetrySchemaDefinition` and friends) is a
 *   TypeScript compile-time-only facility ("no runtime schema validation is
 *   performed" upstream); it has no Kotlin port. Span vocabularies are
 *   documented constants next to their producers instead.
 *
 * Security: telemetry is operational metadata. Credentials, message text,
 * and model responses must never be passed as attributes or error messages.
 */
sealed interface AttributeValue {
    /** pi `string`. */
    @JvmInline
    value class Str(val value: String) : AttributeValue

    /** pi `number`. */
    @JvmInline
    value class Num(val value: Number) : AttributeValue

    /** pi `boolean`. */
    @JvmInline
    value class Bool(val value: Boolean) : AttributeValue
}

/** pi `SpanAttributes`: a name → value map; iteration order is producer order. */
typealias SpanAttributes = Map<String, AttributeValue>

/** pi `SpanOptions`. */
data class SpanOptions(
    val name: String,
    val attributes: SpanAttributes = emptyMap(),
)

/**
 * pi `SpanStatus`'s error detail (`{ name, message }`), plus the originating
 * throwable for backend adapters (see the divergences note on [AttributeValue]).
 */
data class TelemetryError(
    val name: String,
    val message: String,
    val throwable: Throwable? = null,
)

/** pi `SpanStatus`: `{ status: "ok" } | { status: "error"; error?: {...} }`. */
sealed interface SpanStatus {
    data object Ok : SpanStatus

    data class Error(val error: TelemetryError? = null) : SpanStatus
}

/**
 * pi `TelemetrySpan`: an in-flight operation. Also a [TelemetryContext] so
 * child spans start from this span's parent context.
 */
interface TelemetrySpan : TelemetryContext {
    /** Records a point-in-time occurrence with optional attributes. */
    fun addEvent(name: String, attributes: SpanAttributes = emptyMap())

    /** Merges attributes into the span (later values win). */
    fun setAttributes(attributes: SpanAttributes)

    /** Sets the final outcome; overrides the automatic error status of a throw. */
    fun setStatus(status: SpanStatus)
}

/**
 * pi `TelemetryContext`: starts a span around `callback`. The span settles
 * when the callback returns (status ok unless overridden) or throws (status
 * error built from the exception unless [TelemetrySpan.setStatus] already
 * set one); the callback's result or exception propagates unchanged.
 */
interface TelemetryContext {
    suspend fun <T> startSpan(options: SpanOptions, callback: suspend (TelemetrySpan) -> T): T
}

/** Convenience conversions so call sites read `mapOf("provider" to attr(id))`. */
fun attr(value: String): AttributeValue = AttributeValue.Str(value)
fun attr(value: Number): AttributeValue = AttributeValue.Num(value)
fun attr(value: Boolean): AttributeValue = AttributeValue.Bool(value)

/** pi `automaticErrorStatus`: an error status whose detail is read passively. */
internal fun automaticErrorStatus(error: Throwable): SpanStatus = try {
    val name = error::class.qualifiedName ?: error::class.simpleName ?: "unknown"
    SpanStatus.Error(TelemetryError(name, error.message.orEmpty(), error))
} catch (_: Exception) {
    // Error inspection is passive. Fall through to an error status without details.
    SpanStatus.Error()
}

/**
 * Port of pi `NOOP_TELEMETRY_CONTEXT`: the shared context used when an
 * application does not provide one. The span methods are no-ops; the span is
 * also the context so child spans stay no-ops too.
 */
object NoopTelemetry : TelemetrySpan {
    override suspend fun <T> startSpan(
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T,
    ): T = callback(this)

    override fun addEvent(name: String, attributes: SpanAttributes) {}
    override fun setAttributes(attributes: SpanAttributes) {}
    override fun setStatus(status: SpanStatus) {}
}

/** pi's exported binding name for the shared no-op context. */
val NOOP_TELEMETRY_CONTEXT: TelemetryContext = NoopTelemetry
