package works.resolve.pathfinder.ai.utils


import works.resolve.pathfinder.ai.transport.NetworkException
import works.resolve.pathfinder.ai.transport.ProviderHttpException
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Retry behavior ported from pi's provider-retry (which mirrors the pinned
 * OpenAI/Anthropic SDK policy):
 *
 * - Retries 408/409/429/5xx, transport-level failures, and anything the
 *   provider marks with `x-should-retry: true`; never retries when the header
 *   says `false`.
 * - Honors `retry-after-ms` and `retry-after` (seconds or HTTP date).
 *   Server-requested delays above [maxRetryDelayMs] fail immediately instead
 *   of sleeping.
 * - Otherwise backs off exponentially (0.5s * 2^n capped at 8s) with up to 25%
 *   jitter.
 *
 * Timing is injectable so tests never sleep.
 */
class ProviderRetry(
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Random.Default::nextDouble,
) {
    suspend fun <T> retryProviderRequest(
        maxRetries: Int,
        maxRetryDelayMs: Long,
        request: suspend () -> T,
    ): T {
        var retriesRemaining = maxRetries
        while (true) {
            try {
                return request()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (retriesRemaining <= 0 || !isRetryable(error)) throw error

                val retryIndex = maxRetries - retriesRemaining
                retriesRemaining--
                // Negative (stale HTTP-date) delays are clamped to zero at sleep time.
                sleep(maxOf(0L, retryDelayMs(error, retryIndex, maxRetryDelayMs)))
            }
        }
    }

    fun isRetryable(error: Exception): Boolean = when (error) {
        is NetworkException -> true
        is ProviderHttpException -> isRetryableHttpError(error)
        else -> false
    }

    private fun isRetryableHttpError(error: ProviderHttpException): Boolean {
        when (error.header("x-should-retry")) {
            "true" -> return true
            "false" -> return false
        }
        return error.status == 408 || error.status == 409 || error.status == 429 || error.status >= 500
    }

    fun retryDelayMs(error: Exception, retryIndex: Int, maxRetryDelayMs: Long): Long {
        if (error is ProviderHttpException) {
            error.header("retry-after-ms")?.let { header ->
                parseFloatPrefix(header)?.let { value ->
                    return validateServerDelayMs(value.toLong(), maxRetryDelayMs, error)
                }
            }
            error.header("retry-after")?.let { header ->
                val delayMs = parseFloatPrefix(header)?.let { it * 1000 }
                    ?: (parseHttpDateMs(header) - nowMs())
                return validateServerDelayMs(delayMs.toLong(), maxRetryDelayMs, error)
            }
        }
        val exponential = min(0.5 * 2.0.pow(retryIndex), 8.0) * 1000
        return (exponential * (1 - random() * 0.25)).toLong()
    }

    private fun validateServerDelayMs(delayMs: Long, maxRetryDelayMs: Long, error: Exception): Long {
        if (maxRetryDelayMs > 0 && delayMs > maxRetryDelayMs) {
            throw IllegalStateException(
                "Server requested ${ceil(delayMs / 1000.0).toInt()}s retry delay " +
                    "(max: ${ceil(maxRetryDelayMs / 1000.0).toInt()}s). ${error.message}",
            )
        }
        return delayMs
    }

    /** Longest numeric prefix (so "1200ms" parses as 1200), or null when none.
     * NaN parses in Kotlin but is not a valid delay, so it falls through. */
    private fun parseFloatPrefix(value: String): Double? {
        val trimmed = value.trim()
        // Longest-first so "1.5" wins over "1"; header values are tiny.
        for (end in trimmed.length downTo 1) {
            trimmed.substring(0, end).toDoubleOrNull()?.takeIf { !it.isNaN() }?.let { return it }
        }
        return null
    }

    private fun parseHttpDateMs(value: String): Long = parseHttpDateMsOrNull(value) ?: 0L
}

/** Parses an RFC 1123 HTTP date ("Wed, 21 Oct 2015 07:28:00 GMT"), or null.
 * Shared by [ProviderRetry] and the Codex retry-after handling (pi's Date.parse
 * accepts HTTP dates). */
internal fun parseHttpDateMsOrNull(value: String): Long? = try {
    java.time.ZonedDateTime
        .parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        .toInstant().toEpochMilli()
} catch (_: Exception) {
    null
}
