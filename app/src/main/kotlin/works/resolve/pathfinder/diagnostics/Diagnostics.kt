package works.resolve.pathfinder.diagnostics

import ai.koog.http.client.KoogHttpClientException

/**
 * Stable, non-sensitive diagnostic events emitted by Pathfinder.
 *
 * Event identifiers and metadata shapes are defined in code: callers cannot
 * attach arbitrary strings, URLs, provider payloads, credentials, message
 * content, or exception messages. This follows Android's log-information
 * disclosure guidance while retaining enough detail to locate handled
 * failures in debug builds.
 *
 * Instrumentation follows a lowest-layer-logs rule: the layer that first
 * observes a failure records the event; layers above it that merely map the
 * failure to user-safe messages do not record duplicates.
 */
internal enum class DiagnosticEvent(
    val id: String,
    internal val level: DiagnosticLevel,
) {
    CODEX_DEVICE_BEGIN_TRANSPORT_FAILED("codex.device.begin.transport_failed", DiagnosticLevel.ERROR),
    CODEX_DEVICE_BEGIN_HTTP_FAILED("codex.device.begin.http_failed", DiagnosticLevel.ERROR),
    CODEX_DEVICE_BEGIN_RESPONSE_INVALID("codex.device.begin.response_invalid", DiagnosticLevel.ERROR),
    CODEX_DEVICE_POLL_TRANSPORT_FAILED("codex.device.poll.transport_failed", DiagnosticLevel.ERROR),
    CODEX_DEVICE_POLL_RESPONSE_INVALID("codex.device.poll.response_invalid", DiagnosticLevel.ERROR),
    CODEX_DEVICE_POLL_HTTP_FAILED("codex.device.poll.http_failed", DiagnosticLevel.ERROR),
    CODEX_BROWSER_LISTENER_BOUND("codex.browser.listener_bound", DiagnosticLevel.INFO),
    CODEX_BROWSER_LISTENER_BIND_FAILED("codex.browser.listener_bind_failed", DiagnosticLevel.ERROR),
    CODEX_BROWSER_CONNECTION_RECEIVED("codex.browser.connection_received", DiagnosticLevel.INFO),
    CODEX_BROWSER_REQUEST_UNREADABLE("codex.browser.request_unreadable", DiagnosticLevel.WARN),
    CODEX_BROWSER_REQUEST_MALFORMED("codex.browser.request_malformed", DiagnosticLevel.WARN),
    CODEX_BROWSER_CALLBACK_PATH_INVALID("codex.browser.callback_path_invalid", DiagnosticLevel.WARN),
    CODEX_BROWSER_CALLBACK_STATE_INVALID("codex.browser.callback_state_invalid", DiagnosticLevel.WARN),
    CODEX_BROWSER_CALLBACK_CODE_MISSING("codex.browser.callback_code_missing", DiagnosticLevel.WARN),
    CODEX_BROWSER_CALLBACK_ACCEPTED("codex.browser.callback_accepted", DiagnosticLevel.INFO),
    CODEX_BROWSER_AUTHORIZATION_DENIED("codex.browser.authorization_denied", DiagnosticLevel.WARN),
    CODEX_BROWSER_REDIRECT_PAYLOAD_INVALID("codex.browser.redirect_payload_invalid", DiagnosticLevel.ERROR),
    CODEX_TOKEN_EXCHANGE_STARTED("codex.token.exchange_started", DiagnosticLevel.INFO),
    CODEX_TOKEN_EXCHANGE_TRANSPORT_FAILED("codex.token.exchange_transport_failed", DiagnosticLevel.ERROR),
    CODEX_TOKEN_EXCHANGE_HTTP_FAILED("codex.token.exchange_http_failed", DiagnosticLevel.ERROR),
    CODEX_TOKEN_EXCHANGE_RESPONSE_INVALID("codex.token.exchange_response_invalid", DiagnosticLevel.ERROR),
    CODEX_TOKEN_EXCHANGE_ACCOUNT_INVALID("codex.token.exchange_account_invalid", DiagnosticLevel.ERROR),
    CODEX_TOKEN_EXCHANGE_SUCCEEDED("codex.token.exchange_succeeded", DiagnosticLevel.INFO),
    CODEX_TOKEN_REFRESH_STARTED("codex.token.refresh_started", DiagnosticLevel.INFO),
    CODEX_TOKEN_REFRESH_TRANSPORT_FAILED("codex.token.refresh_transport_failed", DiagnosticLevel.ERROR),
    CODEX_TOKEN_REFRESH_HTTP_FAILED("codex.token.refresh_http_failed", DiagnosticLevel.ERROR),
    CODEX_TOKEN_REFRESH_RESPONSE_INVALID("codex.token.refresh_response_invalid", DiagnosticLevel.ERROR),
    CODEX_TOKEN_REFRESH_ACCOUNT_INVALID("codex.token.refresh_account_invalid", DiagnosticLevel.ERROR),
    CODEX_TOKEN_REFRESH_SUCCEEDED("codex.token.refresh_succeeded", DiagnosticLevel.INFO),
    CODEX_SIGN_IN_UNEXPECTED_FAILURE("codex.sign_in.unexpected_failure", DiagnosticLevel.ERROR),

    /*
     * Chat execution (runtime/KoogChatRuntime.kt). Failures of the prompt
     * request/stream itself; provider payloads and exception text stay out
     * of the entry (type chain + HTTP status only).
     */
    CHAT_REQUEST_FAILED("chat.request_failed", DiagnosticLevel.ERROR),
    CHAT_STREAM_INCOMPLETE("chat.stream_incomplete", DiagnosticLevel.ERROR),

    /*
     * Session persistence (data/sessions/SessionStore.kt). "Lowest layer
     * logs" rule: the store records failures where they originate; UI-layer
     * catches over the same operations do not duplicate them.
     */
    SESSION_LOAD_FAILED("session.load_failed", DiagnosticLevel.ERROR),
    SESSION_SAVE_FAILED("session.save_failed", DiagnosticLevel.ERROR),
    SESSION_SUMMARY_SKIPPED("session.summary_skipped", DiagnosticLevel.WARN),

    /* Credential storage (data/credentials/EncryptedCredentialStore.kt). */
    CREDENTIAL_READ_FAILED("credential.read_failed", DiagnosticLevel.ERROR),
    CREDENTIAL_WRITE_FAILED("credential.write_failed", DiagnosticLevel.ERROR),
    CREDENTIAL_DECODE_REJECTED("credential.decode_rejected", DiagnosticLevel.ERROR),

    /*
     * UI boundary (ui/chat/ChatViewModel.kt): swallowed failures with no
     * instrumented lower layer (settings DataStore writes, configuration
     * validation, init aggregate).
     */
    UI_INIT_FAILED("ui.init_failed", DiagnosticLevel.ERROR),
    UI_SETTINGS_WRITE_FAILED("ui.settings_write_failed", DiagnosticLevel.ERROR),
    UI_CONFIG_INVALID("ui.config_invalid", DiagnosticLevel.ERROR),
}

internal enum class DiagnosticLevel {
    INFO,
    WARN,
    ERROR,
}

/** A fully sanitized entry suitable for a Logcat backend. */
internal data class DiagnosticEntry(
    val event: DiagnosticEvent,
    val failure: DiagnosticFailure? = null,
    val httpStatus: Int? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599)
    }

    fun message(): String = buildString {
        append("event=").append(event.id)
        httpStatus?.let { append(" http_status=").append(it) }
        failure?.let {
            append(" error_types=").append(it.typeChain.joinToString(">"))
            it.origin?.let { origin -> append(" origin=").append(origin) }
        }
    }
}

/**
 * Predictable exception metadata. Throwable messages and raw stack traces are
 * deliberately excluded because networking/provider exceptions can embed
 * request URLs, response payloads, tokens, or user content in them.
 */
internal data class DiagnosticFailure(
    val typeChain: List<String>,
    val origin: String?,
) {
    companion object {
        fun from(error: Throwable): DiagnosticFailure {
            val chain = generateSequence(error) { current -> current.cause }
                .take(MAX_CAUSE_DEPTH)
                .map { current -> current.javaClass.name }
                .toList()
            val origin = generateSequence(error) { current -> current.cause }
                .take(MAX_CAUSE_DEPTH)
                .flatMap { current -> current.stackTrace.asSequence() }
                .firstOrNull { frame ->
                    frame.className.startsWith(APP_PACKAGE_PREFIX) &&
                        !frame.className.startsWith(DIAGNOSTICS_PACKAGE_PREFIX)
                }
                ?.let { frame ->
                    "${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown"}:${frame.lineNumber})"
                }
            return DiagnosticFailure(chain, origin)
        }

        private const val MAX_CAUSE_DEPTH = 8
        private const val APP_PACKAGE_PREFIX = "works.resolve.pathfinder."
        private const val DIAGNOSTICS_PACKAGE_PREFIX = "works.resolve.pathfinder.diagnostics."
    }
}

internal fun interface DiagnosticSink {
    fun record(entry: DiagnosticEntry)
}

/**
 * Process-wide diagnostic entry point. It has no backend by default, which
 * keeps local JVM tests and non-debug builds silent. The Android application
 * installs the Logcat backend only when its APK is debuggable.
 */
internal object Diagnostics {
    @Volatile
    private var sink: DiagnosticSink? = null

    internal fun install(sink: DiagnosticSink?) {
        this.sink = sink
    }

    fun failure(event: DiagnosticEvent, error: Throwable) {
        record {
            DiagnosticEntry(
                event = event,
                failure = DiagnosticFailure.from(error),
                httpStatus = httpStatusOf(error),
            )
        }
    }

    fun httpFailure(event: DiagnosticEvent, status: Int) {
        record { DiagnosticEntry(event, httpStatus = status) }
    }

    /**
     * HTTP status of the first [KoogHttpClientException] in the cause chain,
     * if any. Koog's clients wrap transport failures (including non-2xx
     * responses, whose bodies the exception message embeds — never logged)
     * so the status is only available by walking the chain.
     */
    private fun httpStatusOf(error: Throwable): Int? =
        generateSequence<Throwable>(error) { current -> current.cause }
            .take(STATUS_CAUSE_DEPTH)
            .filterIsInstance<KoogHttpClientException>()
            .firstNotNullOfOrNull { exception -> exception.statusCode }

    private const val STATUS_CAUSE_DEPTH = 8

    fun event(event: DiagnosticEvent) {
        record { DiagnosticEntry(event) }
    }

    private inline fun record(entry: () -> DiagnosticEntry) {
        val activeSink = sink ?: return
        activeSink.record(entry())
    }
}
