package works.resolve.pathfinder.ai.auth.oauth

import kotlin.math.max
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private const val CANCEL_MESSAGE = "Login cancelled"
private const val TIMEOUT_MESSAGE = "Device flow timed out"
private const val SLOW_DOWN_TIMEOUT_MESSAGE =
    "Device flow timed out after one or more slow_down responses. This is often caused by clock drift in WSL or VM environments. Please sync or restart the VM clock and try again."

private const val MINIMUM_INTERVAL_MS = 1000L

/** RFC 8628 section 3.2: if the authorization server omits `interval`, the client must use 5 seconds. */
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5.0

/** RFC 8628 section 3.5: `slow_down` means the polling interval must increase by 5 seconds. */
private const val SLOW_DOWN_INTERVAL_INCREMENT_MS = 5000L

sealed interface OAuthDeviceCodePollResult<out T> {
    data object Pending : OAuthDeviceCodePollResult<Nothing>

    data class SlowDown(val intervalSeconds: Double? = null) : OAuthDeviceCodePollResult<Nothing>

    data class Failed(val message: String) : OAuthDeviceCodePollResult<Nothing>

    data class Complete<out T>(val value: T) : OAuthDeviceCodePollResult<T>
}

class OAuthDeviceCodePollOptions<T>(
    val intervalSeconds: Double? = null,
    val expiresInSeconds: Double? = null,
    val waitBeforeFirstPoll: Boolean = false,
    val poll: suspend () -> OAuthDeviceCodePollResult<T>
)

/**
 * pi rejects an aborted sleep with a plain `Error` carrying the cancel
 * message; the port retags the same condition onto
 * [IllegalStateException] — a bare [CancellationException] would be
 * swallowed as a silent user-cancel by the app instead of surfacing as a
 * login failure like pi's Error.
 */
private suspend fun abortableSleep(ms: Long, cancelMessage: String) {
    try {
        delay(ms)
    } catch (e: CancellationException) {
        throw IllegalStateException(cancelMessage, e)
    }
}

/**
 * pi sleeps through `setTimeout(ms)`, which truncates fractional
 * milliseconds and clamps sub-1ms delays to 1 (Node `Timeout` clamp,
 * `insert`'s `MathTrunc`).
 */
private fun setTimeoutMs(ms: Double): Long = if (ms >= 1) ms.toLong() else 1L

/**
 * Divergence from pi: pi cancels through an `AbortSignal` and observes it
 * via `signal.aborted`; here coroutine cancellation surfaces as a
 * [CancellationException], retagged onto [IllegalStateException] with the
 * upstream cancel message so the abort surfaces as a login failure. pi's
 * plain-Error checkpoints would otherwise collapse into Kotlin's silent
 * user-cancel channel. [clock] is injectable for deterministic tests only,
 * and sleeping uses [delay], which virtual-time test schedulers skip
 * automatically.
 */
internal suspend fun <T> pollOAuthDeviceCodeFlow(
    options: OAuthDeviceCodePollOptions<T>,
    clock: Clock = Clock.System
): T {
    fun now() = clock.now().toEpochMilliseconds()

    // pi's deadline math stays in JS numbers: `Date.now() + expiresInSeconds * 1000`
    // can be fractional, and an infinite `expires_in` keeps the flow polling
    // forever instead of wrapping a Long multiplication.
    val deadline =
        options.expiresInSeconds?.let { now() + it * 1000 }
            ?: Double.POSITIVE_INFINITY
    var intervalMs = max(
        MINIMUM_INTERVAL_MS,
        Math.floor((options.intervalSeconds ?: DEFAULT_POLL_INTERVAL_SECONDS) * 1000).toLong()
    )

    var slowDownResponses = 0
    if (options.waitBeforeFirstPoll) {
        val remainingMs = deadline - now()
        if (remainingMs > 0) {
            abortableSleep(setTimeoutMs(minOf(intervalMs.toDouble(), remainingMs)), CANCEL_MESSAGE)
        }
    }

    while (now() < deadline) {
        // pi checks `signal.aborted` between polls and throws a plain Error;
        // the retag keeps the abort a surfaced login failure, not a silent
        // user-cancel.
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            throw IllegalStateException(CANCEL_MESSAGE, error)
        }

        when (val result = options.poll()) {
            is OAuthDeviceCodePollResult.Complete -> return result.value

            is OAuthDeviceCodePollResult.Failed -> throw IllegalStateException(result.message)

            is OAuthDeviceCodePollResult.SlowDown -> {
                slowDownResponses += 1
                // Use the server-provided interval when given (GitHub reports the new required
                // minimum in `interval`); trusting only a client-tracked value risks polling
                // early forever under WSL/VM clock drift. Otherwise apply RFC 8628 section 3.5:
                // increase by 5 seconds.
                intervalMs =
                    if (result.intervalSeconds != null &&
                        result.intervalSeconds.isFinite() &&
                        result.intervalSeconds > 0
                    ) {
                        max(MINIMUM_INTERVAL_MS, Math.floor(result.intervalSeconds * 1000).toLong())
                    } else {
                        max(MINIMUM_INTERVAL_MS, intervalMs + SLOW_DOWN_INTERVAL_INCREMENT_MS)
                    }
            }

            OAuthDeviceCodePollResult.Pending -> {}
        }

        val remainingMs = deadline - now()
        if (remainingMs <= 0) {
            break
        }

        abortableSleep(setTimeoutMs(minOf(intervalMs.toDouble(), remainingMs)), CANCEL_MESSAGE)
    }

    throw IllegalStateException(
        if (slowDownResponses > 0) SLOW_DOWN_TIMEOUT_MESSAGE else TIMEOUT_MESSAGE
    )
}
