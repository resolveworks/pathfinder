package works.resolve.pathfinder.logging

import kotlinx.coroutines.CancellationException
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_CONTEXT
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryContext
import works.resolve.pathfinder.telemetry.TelemetryError
import works.resolve.pathfinder.telemetry.attr

/**
 * The app's diagnostic vocabulary and policy — a narrow facade over the
 * pi-ported [TelemetryContext] contract.
 *
 * Pathfinder-owned behavior, not a port of pi: pi's runtime starts no spans
 * upstream, so every span here is an app-boundary observation that would
 * otherwise be invisible on-device. Centralizing the vocabulary here means
 * no component defines its own span names, attributes, or sanitization
 * rules.
 *
 * Policy:
 * - **Type-only errors, everywhere.** Provider and platform exception
 *   messages are not a guaranteed-safe free-form surface, so every `pf.*`
 *   failure settles an error status carrying only the throwable's short
 *   class name and an empty message.
 * - **Cancellation is not an operational failure.** The ported contract
 *   settles a thrown exception with an automatic error status; this facade
 *   instead settles [CancellationException] `ok` before rethrowing.
 * - **Never recorded:** credentials, payloads, transcripts, provider bodies,
 *   paths, or unrestricted exception messages. Recording is passive and
 *   changes nothing about the recorded operation's semantics.
 */
class PathfinderDiagnostics(private val context: TelemetryContext) {

    enum class SessionWrite { SAVE, FORK }

    /**
     * Records `pf.auth.login` around one provider login; [authType] is the
     * wire auth type (`api_key`/`oauth`).
     */
    suspend fun <T> authLogin(providerId: String, authType: String, login: suspend () -> T): T =
        context.startSpan(
            SpanOptions(
                name = SPAN_AUTH_LOGIN,
                attributes = mapOf(
                    ATTR_AUTH_PROVIDER to attr(providerId),
                    ATTR_AUTH_TYPE to attr(authType),
                ),
            ),
        ) { span ->
            try {
                val result = login()
                span.setAttributes(mapOf(ATTR_AUTH_OUTCOME to attr(OUTCOME_PERSISTED)))
                result
            } catch (error: CancellationException) {
                span.setStatus(SpanStatus.Ok)
                throw error
            } catch (error: Throwable) {
                span.setStatus(typeOnlyError(error))
                throw error
            }
        }

    suspend fun <T> credentialRead(providerId: String, read: suspend () -> T?): T? =
        context.startSpan(credentialSpan(SPAN_CREDENTIAL_READ, providerId)) { span ->
            try {
                val value = read()
                span.setAttributes(
                    mapOf(ATTR_CREDENTIAL_OUTCOME to attr(if (value == null) OUTCOME_ABSENT else OUTCOME_DECRYPTED)),
                )
                value
            } catch (error: CancellationException) {
                span.setStatus(SpanStatus.Ok)
                throw error
            } catch (error: Throwable) {
                span.setStatus(typeOnlyError(error))
                throw error
            }
        }

    suspend fun credentialWrite(providerId: String, write: suspend () -> Unit) {
        context.startSpan(credentialSpan(SPAN_CREDENTIAL_WRITE, providerId)) { span ->
            try {
                write()
                span.setAttributes(mapOf(ATTR_CREDENTIAL_OUTCOME to attr(OUTCOME_PERSISTED)))
            } catch (error: CancellationException) {
                span.setStatus(SpanStatus.Ok)
                throw error
            } catch (error: Throwable) {
                span.setStatus(typeOnlyError(error))
                throw error
            }
        }
    }

    suspend fun <T> credentialDecode(providerId: String, decode: suspend () -> T): T =
        context.startSpan(credentialSpan(SPAN_CREDENTIAL_DECODE, providerId)) { span ->
            try {
                decode()
            } catch (error: CancellationException) {
                span.setStatus(SpanStatus.Ok)
                throw error
            } catch (error: Throwable) {
                span.setStatus(typeOnlyError(error))
                throw error
            }
        }

    suspend fun credentialDelete(providerId: String, delete: suspend () -> Boolean): Boolean =
        context.startSpan(credentialSpan(SPAN_CREDENTIAL_DELETE, providerId)) { span ->
            try {
                val deleted = delete()
                span.setAttributes(mapOf(ATTR_CREDENTIAL_OUTCOME to attr(if (deleted) OUTCOME_DELETED else OUTCOME_ABSENT)))
                deleted
            } catch (error: CancellationException) {
                span.setStatus(SpanStatus.Ok)
                throw error
            } catch (error: Throwable) {
                span.setStatus(typeOnlyError(error))
                throw error
            }
        }

    /** A fork writes a new log but is the same persistence boundary as a save. */
    suspend fun <T> sessionWrite(kind: SessionWrite, sessionId: String, operation: suspend () -> T): T =
        context.startSpan(sessionSpan(kind.spanName, sessionId)) { span ->
            try {
                val result = operation()
                span.setAttributes(mapOf(ATTR_SESSION_OUTCOME to attr(OUTCOME_PERSISTED)))
                result
            } catch (error: CancellationException) {
                span.setStatus(SpanStatus.Ok)
                throw error
            } catch (error: Throwable) {
                span.setStatus(typeOnlyError(error))
                throw error
            }
        }

    /**
     * Records `pf.session.load`; unlike [sessionSummary], a failed load
     * propagates.
     */
    suspend fun <T : Any> sessionLoad(sessionId: String, load: suspend () -> T): T =
        sessionRead(SPAN_SESSION_LOAD, sessionId, load, skipped = false)!!

    /**
     * Records `pf.session.summary`: an expected [Exception] is recorded and
     * the entry skipped so one unreadable entry cannot fail the whole
     * listing; other throwables are recorded but still propagate.
     */
    suspend fun <T> sessionSummary(sessionId: String, load: suspend () -> T): T? =
        sessionRead(SPAN_SESSION_SUMMARY, sessionId, load, skipped = true)

    private suspend fun <T> sessionRead(
        spanName: String,
        sessionId: String,
        load: suspend () -> T,
        skipped: Boolean,
    ): T? = context.startSpan(sessionSpan(spanName, sessionId)) { span ->
        try {
            val session = load()
            span.setAttributes(mapOf(ATTR_SESSION_OUTCOME to attr(OUTCOME_LOADED)))
            session
        } catch (error: CancellationException) {
            span.setStatus(SpanStatus.Ok)
            throw error
        } catch (error: Throwable) {
            span.setStatus(typeOnlyError(error))
            if (skipped && error is Exception) {
                span.setAttributes(mapOf(ATTR_SESSION_OUTCOME to attr(OUTCOME_SKIPPED)))
                null
            } else {
                throw error
            }
        }
    }

    /**
     * Records `pf.chat.error` at the UI's single error boundary: [uiMessage]
     * is the generic static UI string (no secrets); [cause] has already been
     * swallowed by the caller.
     */
    suspend fun chatError(uiMessage: String, cause: Throwable) =
        recordSwallowedFailure(SPAN_CHAT_ERROR, ATTR_UI_ERROR to uiMessage, cause)

    /**
     * Records `pf.chat.degraded`: a failure the ViewModel deliberately
     * absorbs into degraded UI state instead of an error; [operation] names
     * the failed operation.
     */
    suspend fun chatDegraded(operation: String, cause: Throwable) =
        recordSwallowedFailure(SPAN_CHAT_DEGRADED, ATTR_DEGRADED_OPERATION to operation, cause)

    /**
     * Records an already-swallowed failure as a settled span: a type-only
     * error status, then the same [cause] is thrown *through* the span so a
     * backend adapter observes the callback throwable locally (stack frames)
     * without its message ever entering the recorded status — and that
     * diagnostic throw is caught and swallowed here. The cause has already
     * been handled by the caller; recording must never re-observe it as a
     * behavior change.
     */
    private suspend fun recordSwallowedFailure(spanName: String, attribute: Pair<String, String>, cause: Throwable) {
        try {
            context.startSpan(
                SpanOptions(name = spanName, attributes = mapOf(attribute.first to attr(attribute.second))),
            ) { span ->
                span.setStatus(typeOnlyError(cause))
                throw cause
            }
        } catch (recorded: Throwable) {
            // Passive by contract: the diagnostic throw (this [cause], or a
            // passive backend failure) is swallowed, never propagated.
        }
    }

    private fun credentialSpan(name: String, providerId: String) = SpanOptions(
        name = name,
        attributes = mapOf(ATTR_CREDENTIAL_PROVIDER to attr(providerId)),
    )

    private fun sessionSpan(name: String, sessionId: String) = SpanOptions(
        name = name,
        attributes = mapOf(ATTR_SESSION_ID to attr(sessionId)),
    )

    private val SessionWrite.spanName: String
        get() = when (this) {
            SessionWrite.SAVE -> SPAN_SESSION_SAVE
            SessionWrite.FORK -> SPAN_SESSION_FORK
        }

    companion object {
        val NOOP: PathfinderDiagnostics = PathfinderDiagnostics(NOOP_TELEMETRY_CONTEXT)

        private const val SPAN_AUTH_LOGIN = "pf.auth.login"
        private const val ATTR_AUTH_PROVIDER = "pf.auth.provider"
        private const val ATTR_AUTH_TYPE = "pf.auth.type"
        private const val ATTR_AUTH_OUTCOME = "pf.auth.outcome"

        private const val SPAN_CREDENTIAL_READ = "pf.credentials.read"
        private const val SPAN_CREDENTIAL_WRITE = "pf.credentials.write"
        private const val SPAN_CREDENTIAL_DECODE = "pf.credentials.decode"
        private const val SPAN_CREDENTIAL_DELETE = "pf.credentials.delete"
        private const val ATTR_CREDENTIAL_PROVIDER = "pf.credentials.provider"
        private const val ATTR_CREDENTIAL_OUTCOME = "pf.credentials.outcome"

        private const val SPAN_SESSION_SAVE = "pf.session.save"
        private const val SPAN_SESSION_LOAD = "pf.session.load"
        private const val SPAN_SESSION_SUMMARY = "pf.session.summary"
        private const val SPAN_SESSION_FORK = "pf.session.fork"
        private const val ATTR_SESSION_ID = "pf.session.id"
        private const val ATTR_SESSION_OUTCOME = "pf.session.outcome"

        private const val SPAN_CHAT_ERROR = "pf.chat.error"
        private const val SPAN_CHAT_DEGRADED = "pf.chat.degraded"
        private const val ATTR_UI_ERROR = "pf.error.ui_message"
        private const val ATTR_DEGRADED_OPERATION = "pf.degraded.operation"

        private const val OUTCOME_PERSISTED = "persisted"
        private const val OUTCOME_DECRYPTED = "decrypted"
        private const val OUTCOME_ABSENT = "absent"
        private const val OUTCOME_DELETED = "deleted"
        private const val OUTCOME_LOADED = "loaded"
        private const val OUTCOME_SKIPPED = "skipped"

        /**
         * Names stay low-cardinality: anonymous classes fall back to the
         * stable `Throwable`. Inspection is passive — a failure here must
         * not replace the business exception.
         */
        private fun typeOnlyError(error: Throwable): SpanStatus = try {
            SpanStatus.Error(TelemetryError(name = error::class.simpleName ?: "Throwable", message = ""))
        } catch (_: Throwable) {
            SpanStatus.Error(TelemetryError(name = "Throwable", message = ""))
        }
    }
}
