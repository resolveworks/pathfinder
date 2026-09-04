package works.resolve.pathfinder.ai.auth.oauth

import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
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
    val expiresInSeconds: Long? = null,
    val waitBeforeFirstPoll: Boolean = false,
    val poll: suspend () -> OAuthDeviceCodePollResult<T>
)

/**
 * pi rejects an aborted sleep with a plain `Error`; Kotlin must keep
 * cancellation cooperative, so this rethrows a [CancellationException]
 * carrying [cancelMessage].
 */
private suspend fun abortableSleep(ms: Long, cancelMessage: String) {
    try {
        delay(ms)
    } catch (e: CancellationException) {
        throw CancellationException(cancelMessage, e)
    }
}

/**
 * Divergence from pi: pi cancels through an `AbortSignal`; here coroutine
 * cancellation surfaces as a [CancellationException] carrying the upstream
 * cancel message. [clock] is injectable for deterministic tests only, and
 * sleeping uses [delay], which virtual-time test schedulers skip
 * automatically.
 */
internal suspend fun <T> pollOAuthDeviceCodeFlow(
    options: OAuthDeviceCodePollOptions<T>,
    clock: Clock = Clock.System
): T {
    fun now() = clock.now().toEpochMilliseconds()
    val deadline =
        options.expiresInSeconds?.let { now() + it * 1000 } ?: Long.MAX_VALUE
    var intervalMs = max(
        MINIMUM_INTERVAL_MS,
        Math.floor((options.intervalSeconds ?: DEFAULT_POLL_INTERVAL_SECONDS) * 1000).toLong()
    )

    var slowDownResponses = 0
    if (options.waitBeforeFirstPoll) {
        val remainingMs = deadline - now()
        if (remainingMs > 0) {
            abortableSleep(minOf(intervalMs, remainingMs), CANCEL_MESSAGE)
        }
    }

    while (now() < deadline) {
        // pi checks `signal.aborted` between polls; cancellation observed here
        // is retagged with pi's cancel message.
        try {
            coroutineContext.ensureActive()
        } catch (error: CancellationException) {
            throw CancellationException(CANCEL_MESSAGE, error)
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

        abortableSleep(minOf(intervalMs, remainingMs), CANCEL_MESSAGE)
    }

    throw IllegalStateException(
        if (slowDownResponses > 0) SLOW_DOWN_TIMEOUT_MESSAGE else TIMEOUT_MESSAGE
    )
}
