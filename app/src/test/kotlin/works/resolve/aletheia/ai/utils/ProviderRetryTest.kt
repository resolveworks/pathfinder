package works.resolve.aletheia.ai.utils

import works.resolve.aletheia.ai.core.StreamOptions
import works.resolve.aletheia.ai.transport.NetworkException
import works.resolve.aletheia.ai.transport.ProviderHttpException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ProviderRetryTest {

    private class Harness(
        val sleeps: MutableList<Long> = mutableListOf(),
        var randomValue: Double = 0.0,
    ) {
        val retry = ProviderRetry(
            sleep = { sleeps.add(it) },
            nowMs = { 1_000_000L },
            random = { randomValue },
        )
    }

    private fun httpError(
        status: Int,
        headers: Map<String, List<String>> = emptyMap(),
    ): ProviderHttpException = ProviderHttpException(status, headers.mapKeys { it.key.lowercase() }, "")

    @Test
    fun `retries 408 409 429 and 5xx`() = runTest {
        for (status in listOf(408, 409, 429, 500, 503)) {
            val h = Harness()
            var calls = 0
            h.retry.retryProviderRequest(maxRetries = 2, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
                calls++
                if (calls < 3) throw httpError(status)
                "ok"
            }
            assertEquals(3, calls)
        }
    }

    @Test
    fun `does not retry other 4xx`() = runTest {
        val h = Harness()
        var calls = 0
        val error = assertFailsWith<ProviderHttpException> {
            h.retry.retryProviderRequest(maxRetries = 3, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
                calls++
                throw httpError(400)
            }
        }
        assertEquals(400, error.status)
        assertEquals(1, calls)
    }

    @Test
    fun `honors x-should-retry header`() = runTest {
        // 400 is normally final, but the header overrides it.
        run {
            val h = Harness()
            var calls = 0
            h.retry.retryProviderRequest(maxRetries = 1, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
                calls++
                if (calls == 1) throw httpError(400, mapOf("X-Should-Retry" to listOf("true")))
                "ok"
            }
            assertEquals(2, calls)
        }
        // 500 is normally retryable, but the header vetoes it.
        run {
            val h = Harness()
            var calls = 0
            assertFailsWith<ProviderHttpException> {
                h.retry.retryProviderRequest(maxRetries = 1, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
                    calls++
                    throw httpError(500, mapOf("x-should-retry" to listOf("false")))
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `retries network exceptions`() = runTest {
        val h = Harness()
        var calls = 0
        h.retry.retryProviderRequest(maxRetries = 1, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
            calls++
            if (calls == 1) throw NetworkException(java.io.IOException("boom"))
            "ok"
        }
        assertEquals(2, calls)
    }

    @Test
    fun `never retries arbitrary exceptions`() = runTest {
        val h = Harness()
        var calls = 0
        assertFailsWith<IllegalStateException> {
            h.retry.retryProviderRequest(maxRetries = 5, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
                calls++
                throw IllegalStateException("nope")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `uses retry-after-ms and validates against cap`() = runTest {
        val h = Harness()
        var calls = 0
        h.retry.retryProviderRequest(maxRetries = 1, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
            calls++
            if (calls == 1) throw httpError(429, mapOf("Retry-After-Ms" to listOf("2500")))
            "ok"
        }
        assertEquals(listOf(2500L), h.sleeps)
    }

    @Test
    fun `server-requested delay above cap fails immediately`() = runTest {
        val h = Harness()
        var calls = 0
        val error = assertFailsWith<IllegalStateException> {
            h.retry.retryProviderRequest(maxRetries = 3, maxRetryDelayMs = 5000) {
                calls++
                throw httpError(429, mapOf("retry-after-ms" to listOf("60000")))
            }
        }
        assertEquals(1, calls)
        assertTrue(h.sleeps.isEmpty())
        assertTrue("60s retry delay" in (error.message ?: ""))
    }

    @Test
    fun `cap of zero disables the limit`() = runTest {
        val h = Harness()
        var calls = 0
        h.retry.retryProviderRequest(maxRetries = 1, maxRetryDelayMs = 0) {
            calls++
            if (calls == 1) throw httpError(429, mapOf("Retry-After-Ms" to listOf("120000")))
            "ok"
        }
        assertEquals(listOf(120_000L), h.sleeps)
    }

    @Test
    fun `retry-after-ms takes precedence over retry-after`() = runTest {
        val h = Harness()
        val error = httpError(429, mapOf("Retry-After-Ms" to listOf("100"), "Retry-After" to listOf("30")))
        assertEquals(100L, h.retry.retryDelayMs(error, 0, 60_000))
    }

    @Test
    fun `parses numeric prefixes like parseFloat`() = runTest {
        val h = Harness()
        // pi uses Number.parseFloat, which accepts trailing junk (e.g. "1200ms").
        assertEquals(1200L, h.retry.retryDelayMs(httpError(429, mapOf("retry-after-ms" to listOf("1200ms"))), 0, 60_000))
        // Unparseable retry-after-ms falls through to retry-after.
        val error = httpError(429, mapOf("Retry-After-Ms" to listOf("soon"), "Retry-After" to listOf("2")))
        assertEquals(2000L, h.retry.retryDelayMs(error, 0, 60_000))
    }

    @Test
    fun `cancellation during backoff is terminal`() = runTest {
        val h = Harness()
        var calls = 0
        val retry = ProviderRetry(
            sleep = { throw kotlinx.coroutines.CancellationException("aborted") },
            nowMs = { 0L },
        )
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            retry.retryProviderRequest(maxRetries = 2, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
                calls++
                throw httpError(429)
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `parses retry-after seconds`() = runTest {
        val h = Harness()
        h.retry.retryDelayMs(httpError(503, mapOf("Retry-After" to listOf("3"))), 0, 60_000).let {
            assertEquals(3000L, it)
        }
    }

    @Test
    fun `parses HTTP-date retry-after relative to injectable clock`() = runTest {
        val h = Harness()
        // nowMs is 1_000_000; the date is 1s in the future.
        val future = java.time.Instant.ofEpochMilli(1_000_000L + 1000)
            .atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        val error = httpError(503, mapOf("Retry-After" to listOf(future)))
        assertEquals(1000L, h.retry.retryDelayMs(error, 0, 60_000))
    }

    @Test
    fun `stale HTTP-date sleeps zero instead of negative`() = runTest {
        val h = Harness()
        val stale = java.time.Instant.ofEpochMilli(0)
            .atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        var calls = 0
        h.retry.retryProviderRequest(maxRetries = 1, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
            calls++
            if (calls == 1) throw httpError(503, mapOf("Retry-After" to listOf(stale)))
            "ok"
        }
        // Raw delay is negative (pi returns it unclamped); the sleep clamps to zero.
        assertTrue(h.retry.retryDelayMs(httpError(503, mapOf("Retry-After" to listOf(stale))), 0, 60_000) < 0)
        assertEquals(listOf(0L), h.sleeps)
    }

    @Test
    fun `exponential backoff capped at 8s with jitter`() = runTest {
        val h = Harness()
        h.randomValue = 0.5
        // 0.5 * 2^5 = 16 -> capped at 8s; jitter (1 - 0.5*0.25) = 0.875
        assertEquals(7000L, h.retry.retryDelayMs(httpError(500), 5, 60_000))
        h.randomValue = 0.0
        assertEquals(500L, h.retry.retryDelayMs(httpError(500), 0, 60_000))
    }

    @Test
    fun `exponential backoff is not capped by maxRetryDelayMs`() = runTest {
        val h = Harness()
        h.randomValue = 0.0
        assertEquals(8000L, h.retry.retryDelayMs(httpError(500), 10, maxRetryDelayMs = 1000))
    }

    @Test
    fun `maxRetries exhaust then throws`() = runTest {
        val h = Harness()
        var calls = 0
        assertFailsWith<ProviderHttpException> {
            h.retry.retryProviderRequest(maxRetries = 2, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
                calls++
                throw httpError(500)
            }
        }
        assertEquals(3, calls)
        assertEquals(2, h.sleeps.size)
    }

    @Test
    fun `zero retries by default`() = runTest {
        val h = Harness()
        var calls = 0
        assertFailsWith<ProviderHttpException> {
            h.retry.retryProviderRequest(maxRetries = 0, maxRetryDelayMs = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS) {
                calls++
                throw httpError(500)
            }
        }
        assertEquals(1, calls)
        assertTrue(h.sleeps.isEmpty())
    }
}
