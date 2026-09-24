package works.resolve.pathfinder.ai.utils

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock
import works.resolve.pathfinder.ai.transport.NetworkException
import works.resolve.pathfinder.ai.transport.ProviderHttpException

/**
 * Mirrors the pinned OpenAI/Anthropic SDK retry policy: `x-should-retry`
 * overrides the status check, 408/409/429/5xx and transport failures are
 * retryable, and backoff is exponential with up to 25% jitter — review when
 * either SDK is upgraded. Honors `retry-after-ms` / `retry-after`; a
 * server-requested delay above maxRetryDelayMs fails immediately instead of
 * sleeping. Sleep is injectable so tests never wait.
 */
class ProviderRetry(
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val clock: Clock = Clock.System,
    private val random: () -> Double = Random.Default::nextDouble
) {
    suspend fun <T> retryProviderRequest(
        maxRetries: Int,
        maxRetryDelayMs: Long,
        request: suspend () -> T
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
        return error.status == 408 || error.status == 409 || error.status == 429 ||
            error.status >= 500
    }

    fun retryDelayMs(error: Exception, retryIndex: Int, maxRetryDelayMs: Long): Long {
        if (error is ProviderHttpException) {
            error.header("retry-after-ms")?.let { header ->
                jsParseFloatOrNull(header)?.let { value ->
                    return validateServerDelayMs(value.toLong(), maxRetryDelayMs, error)
                }
            }
            error.header("retry-after")?.let { header ->
                val delayMs = jsParseFloatOrNull(header)?.let { it * 1000 }
                    ?: (parseHttpDateMs(header) - clock.now().toEpochMilliseconds())
                return validateServerDelayMs(delayMs.toLong(), maxRetryDelayMs, error)
            }
        }
        val exponential = min(0.5 * 2.0.pow(retryIndex), 8.0) * 1000
        return (exponential * (1 - random() * 0.25)).toLong()
    }

    private fun validateServerDelayMs(
        delayMs: Long,
        maxRetryDelayMs: Long,
        error: Exception
    ): Long = validateRetryDelayMs(delayMs, maxRetryDelayMs, messageSuffix = error.message)

    private fun parseHttpDateMs(value: String): Long = parseHttpDateMsOrNull(value) ?: 0L
}

private inline fun parseDateMsOrNull(value: String, parse: (String) -> java.time.Instant): Long? =
    try {
        parse(value).toEpochMilli()
    } catch (_: Exception) {
        null
    }

/**
 * JS `Date.parse` stand-in for a non-numeric `Retry-After` value: RFC 1123
 * IMF-fixdate plus the ISO 8601 forms `Date.parse` accepts (offset
 * date-times, zoneless date-times as local time, date-only forms as UTC).
 * V8's remaining legacy formats (RFC 1036/850 two-digit years, asctime) stay
 * unparsed: like any unparseable value they yield null, taking pi's NaN path
 * (provider-retry sleeps zero; the Copilot flow returns the 429 unretried).
 * The trim is V8's too — `DateParser` skips the JS whitespace set around the
 * value.
 */
internal fun parseHttpDateMsOrNull(value: String): Long? {
    val text = trimJsWhitespace(value)
    return parseDateMsOrNull(text) {
        java.time.OffsetDateTime.parse(it).toInstant()
    } ?: parseDateMsOrNull(text) {
        java.time.LocalDateTime.parse(it).atZone(java.time.ZoneId.systemDefault()).toInstant()
    } ?: parseDateMsOrNull(text) {
        java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    } ?: parseDateMsOrNull(text) {
        java.time.ZonedDateTime.parse(
            it,
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        ).toInstant()
    }
}

/**
 * The server requested a retry delay above the cap, so the request fails
 * immediately and is never retried.
 *
 * Divergence: pi's codex adapter names this failure while provider-retry
 * throws a plain Error for the same condition; pathfinder folds the latter
 * into this type so both retry policies share one delay-exceeded sentinel.
 */
internal class RetryDelayExceededError(message: String) : Exception(message)

/**
 * Shared by both retry policies: provider-retry appends the provider error
 * text ([messageSuffix]); the codex variant passes none, exactly as upstream.
 */
internal fun validateRetryDelayMs(
    delayMs: Long,
    maxRetryDelayMs: Long,
    messageSuffix: String? = null
): Long {
    if (maxRetryDelayMs > 0 && delayMs > maxRetryDelayMs) {
        val seconds = (delayMs + 999) / 1000
        val maxSeconds = (maxRetryDelayMs + 999) / 1000
        val suffix = if (messageSuffix != null) ". $messageSuffix" else ""
        throw RetryDelayExceededError(
            "Server requested ${seconds}s retry delay (max: ${maxSeconds}s)$suffix"
        )
    }
    return delayMs
}
