package works.resolve.aletheia.ai.auth.oauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Shared RFC 8628 device-code poller, ported from pi
 * `packages/ai/src/auth/oauth/device-code.ts`.
 *
 * Divergence from pi (documented per AGENTS.md): pi uses an `AbortSignal` for
 * caller cancellation; the narrow Kotlin equivalent is coroutine cancellation,
 * which surfaces as a [CancellationException] carrying the upstream cancel
 * message. Time is read through an injectable `now` supplier (defaulting to the
 * system clock) so tests can run deterministically against a virtual scheduler;
 * sleeping uses [delay], which virtual time frameworks skip automatically.
 */

private const val CANCEL_MESSAGE = "Login cancelled"
private const val TIMEOUT_MESSAGE = "Device flow timed out"
private const val SLOW_DOWN_TIMEOUT_MESSAGE =
    "Device flow timed out after one or more slow_down responses. This is often caused by clock drift in WSL or VM environments. Please sync or restart the VM clock and try again."

/** Port of pi `MINIMUM_INTERVAL_MS`. */
private const val MINIMUM_INTERVAL_MS = 1000L

/** RFC 8628 section 3.2: if the authorization server omits `interval`, the client must use 5 seconds. */
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5.0

/** RFC 8628 section 3.5: `slow_down` means the polling interval must increase by 5 seconds. */
private const val SLOW_DOWN_INTERVAL_INCREMENT_MS = 5000L

/**
 * Port of pi `OAuthDeviceCodePollResult<T>`.
 */
sealed interface OAuthDeviceCodePollResult<out T> {
    /** pi `{ status: "pending" }`. */
    data object Pending : OAuthDeviceCodePollResult<Nothing>

    /**
     * pi `{ status: "slow_down"; intervalSeconds?: number }`. The server may
     * report the new required minimum interval in `interval`.
     */
    data class SlowDown(val intervalSeconds: Double? = null) : OAuthDeviceCodePollResult<Nothing>

    /** pi `{ status: "failed"; message: string }`. */
    data class Failed(val message: String) : OAuthDeviceCodePollResult<Nothing>

    /** pi `{ status: "complete"; value: T }`. */
    data class Complete<out T>(val value: T) : OAuthDeviceCodePollResult<T>
}

/**
 * Port of pi `OAuthDeviceCodePollOptions<T>`. Cancellation travels through the
 * calling coroutine instead of an `AbortSignal`.
 */
class OAuthDeviceCodePollOptions<T>(
    val intervalSeconds: Double? = null,
    val expiresInSeconds: Long? = null,
    val waitBeforeFirstPoll: Boolean = false,
    val poll: suspend () -> OAuthDeviceCodePollResult<T>,
)

/**
 * Port of pi `abortableSleep`. Aborting maps to coroutine cancellation: a
 * cancelled sleep throws [CancellationException] with [cancelMessage] instead
 * of pi's rejected promise.
 */
private suspend fun abortableSleep(ms: Long, cancelMessage: String) {
    try {
        delay(ms)
    } catch (e: CancellationException) {
        throw CancellationException(cancelMessage, e)
    }
}

/**
 * Port of pi `pollOAuthDeviceCodeFlow<T>`.
 *
 * [now] is internal for deterministic tests only; production callers use the
 * system-clock default.
 */
internal suspend fun <T> pollOAuthDeviceCodeFlow(
    options: OAuthDeviceCodePollOptions<T>,
    now: () -> Long = { System.currentTimeMillis() },
): T {
    val deadline =
        options.expiresInSeconds?.let { now() + it * 1000 } ?: Long.MAX_VALUE
    var intervalMs = max(
        MINIMUM_INTERVAL_MS,
        Math.floor((options.intervalSeconds ?: DEFAULT_POLL_INTERVAL_SECONDS) * 1000).toLong(),
    )

    var slowDownResponses = 0
    if (options.waitBeforeFirstPoll) {
        val remainingMs = deadline - now()
        if (remainingMs > 0) {
            abortableSleep(minOf(intervalMs, remainingMs), CANCEL_MESSAGE)
        }
    }

    while (now() < deadline) {
        val job = coroutineContext[Job]
        if (job?.isActive == false) {
            throw CancellationException(CANCEL_MESSAGE)
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
        if (slowDownResponses > 0) SLOW_DOWN_TIMEOUT_MESSAGE else TIMEOUT_MESSAGE,
    )
}
