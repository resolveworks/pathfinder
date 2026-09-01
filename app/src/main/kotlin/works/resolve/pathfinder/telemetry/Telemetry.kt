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
 *   async operations await naturally inside the span. Kotlin exceptions are
 *   always `Throwable`, so pi's non-`Error` rejection values have no
 *   counterpart; [automaticErrorStatus] keeps pi's passivity.
 * - The typed-schema layer (`TelemetrySchemaDefinition` and friends) is a
 *   deliberate selective omission: pi's schema values are ordinary
 *   runtime-serializable data (upstream `defineTelemetrySchema` "returns
 *   ordinary JSON-serializable data"), while the exact-type inference that
 *   consumes them is TypeScript-only. Span vocabularies are documented
 *   constants next to their producers instead.
 *
 * Security: telemetry is operational metadata. Credentials, message text,
 * and model responses must never be passed as attributes or error messages.
 */
sealed interface AttributeValue {
    /** pi `string`. */
    @JvmInline
    value class Str(val value: String) : AttributeValue

    /** pi `number`. Numbers box as [Number] so both integral and fractional values fit one case. */
    @JvmInline
    value class Num(val value: Number) : AttributeValue

    /** pi `boolean`. */
    @JvmInline
    value class Bool(val value: Boolean) : AttributeValue

    /** pi `readonly string[]`. */
    data class Strs(val values: List<String>) : AttributeValue

    /** pi `readonly number[]`. */
    data class Nums(val values: List<Number>) : AttributeValue

    /** pi `readonly boolean[]`. */
    data class Bools(val values: List<Boolean>) : AttributeValue
}

/** pi `SpanAttributes`: a name → value map; iteration order is producer order. */
typealias SpanAttributes = Map<String, AttributeValue>

/** pi `SpanOptions`. */
data class SpanOptions(
    val name: String,
    val attributes: SpanAttributes = emptyMap(),
)

/** pi `SpanStatus`'s error detail (`{ name, message }`). */
data class TelemetryError(
    val name: String,
    val message: String,
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

/** Array conversions defensively copy the caller's values, mirroring pi's `copyAttributeValue`. */
fun attr(vararg values: String): AttributeValue = AttributeValue.Strs(values.toList())
fun attr(vararg values: Number): AttributeValue = AttributeValue.Nums(values.toList())
fun attr(vararg values: Boolean): AttributeValue = AttributeValue.Bools(values.toList())

/**
 * Port of pi `automaticErrorStatus`: an error status whose detail is read
 * passively. pi reads JS `error.name`, which for `Error` and its subclasses
 * is the class's short name; the closest Kotlin mirror is the exception
 * class's [simpleName][Class.getSimpleName], falling back to the nearest
 * named superclass (an anonymous Kotlin exception is still an `Error`
 * instance as far as pi is concerned, never a non-`Error` rejection).
 */
internal fun automaticErrorStatus(error: Throwable): SpanStatus = try {
    val name = error::class.simpleName
        ?: generateSequence<Class<*>>(error::class.java.superclass) { it.superclass }
            .firstNotNullOfOrNull { it.simpleName }
        ?: "Throwable"
    SpanStatus.Error(TelemetryError(name, error.message.orEmpty()))
} catch (_: Throwable) {
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
