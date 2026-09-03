package works.resolve.pathfinder.telemetry

/**
 * Telemetry describes what the app did while running: timed [spans][TelemetrySpan]
 * with attributes, point [events][TelemetrySpan.addEvent], and a final
 * [status][TelemetrySpan.setStatus]. It is diagnostic data, not business state:
 * recording must never change whether an operation runs, succeeds, fails, or
 * persists, and every implementation is passive — a recording failure must
 * never propagate.
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
 *
 * Scope vs pi (differences.md §3.5, verified at pin b8b873b98): this file and
 * [InMemoryTelemetryContext] are clean twins of the `pi-telemetry` package core
 * (`packages/telemetry/src/index.ts` + `memory.ts`) — the runtime contract
 * only. pi's agent-side `harness/telemetry.ts` is intentionally absent, not
 * folded in: at the pin it is pure exported vocabulary — `AI_TELEMETRY_SCHEMA`
 * (the single `pi.ai.request` span), `HARNESS_TELEMETRY_SCHEMA` (the harness
 * span taxonomy), and the `startAiSpan`/`startHarnessSpan` wrappers — with
 * zero producer call sites anywhere in packages/ai, packages/agent, or
 * packages/coding-agent. Porting span-name constants with no producer would
 * be dead code, and pathfinder matches the pin exactly: the `telemetryContext`
 * option is plumbed through request options (presence-redacted) but no
 * adapter starts a span. The app's actual span vocabulary is pathfinder-owned
 * (`pf.*`, PathfinderDiagnostics). No harness span or attribute is
 * port-relevant but missing; revisit if post-pin pi wires real producers.
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

data class SpanOptions(
    val name: String,
    val attributes: SpanAttributes = emptyMap(),
)

data class TelemetryError(
    val name: String,
    val message: String,
)

sealed interface SpanStatus {
    data object Ok : SpanStatus

    data class Error(val error: TelemetryError? = null) : SpanStatus
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
 */
interface TelemetryContext {
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

/** The span is its own context so child spans stay no-ops too. */
object NoopTelemetry : TelemetrySpan {
    override suspend fun <T> startSpan(
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T,
    ): T = callback(this)

    override fun addEvent(name: String, attributes: SpanAttributes) {}
    override fun setAttributes(attributes: SpanAttributes) {}
    override fun setStatus(status: SpanStatus) {}
}

val NOOP_TELEMETRY_CONTEXT: TelemetryContext = NoopTelemetry
