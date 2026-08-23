package works.resolve.aletheia.ai.utils


import works.resolve.aletheia.ai.transport.NetworkException
import works.resolve.aletheia.ai.transport.ProviderHttpException
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
                // pi clamps negative (stale HTTP-date) delays to zero at sleep time.
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
                header.toDoubleOrNull()?.let { value ->
                    return validateServerDelayMs(value.toLong(), maxRetryDelayMs, error)
                }
            }
            error.header("retry-after")?.let { header ->
                val delayMs = header.toDoubleOrNull()?.let { it * 1000 }
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

    private fun parseHttpDateMs(value: String): Long = try {
        java.time.ZonedDateTime
            .parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}
