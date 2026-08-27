package works.resolve.distill.ai.auth.oauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the RFC 8628 device-code poller, ported from pi
 * `packages/ai/src/auth/oauth/device-code.ts`.
 *
 * Time is deterministic: `runTest` virtualizes [kotlinx.coroutines.delay], and
 * the poller's `now` supplier is bound to the test scheduler's virtual clock,
 * mirroring how `Date.now()` advances as the upstream sleeps resolve.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceCodePollerTest {

    // --- complete / pending / interval behavior ---

    @Test
    fun `complete on first poll returns value`() = runTest {
        val value = pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(poll = { OAuthDeviceCodePollResult.Complete("token") }),
        ) { currentTime }
        assertEquals("token", value)
    }

    @Test
    fun `default interval is 5 seconds (RFC 8628 section 3_2)`() = runTest {
        val times = mutableListOf<Long>()
        var calls = 0
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                expiresInSeconds = 16,
                poll = {
                    times += currentTime
                    calls += 1
                    if (calls == 3) OAuthDeviceCodePollResult.Complete("ok") else OAuthDeviceCodePollResult.Pending
                },
            ),
        ) { currentTime }
        assertEquals(listOf(0L, 5000L, 10000L), times)
    }

    @Test
    fun `minimum interval is 1 second even when server sends less`() = runTest {
        val times = mutableListOf<Long>()
        var calls = 0
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = 0.1,
                expiresInSeconds = 5,
                poll = {
                    times += currentTime
                    calls += 1
                    if (calls == 2) OAuthDeviceCodePollResult.Complete("ok") else OAuthDeviceCodePollResult.Pending
                },
            ),
        ) { currentTime }
        assertEquals(listOf(0L, 1000L), times)
    }

    @Test
    fun `waitBeforeFirstPoll delays the first poll by one interval`() = runTest {
        val times = mutableListOf<Long>()
        var calls = 0
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = 2.0,
                waitBeforeFirstPoll = true,
                expiresInSeconds = 10,
                poll = {
                    times += currentTime
                    calls += 1
                    if (calls == 2) OAuthDeviceCodePollResult.Complete("ok") else OAuthDeviceCodePollResult.Pending
                },
            ),
        ) { currentTime }
        assertEquals(listOf(2000L, 4000L), times)
    }

    // --- failure and timeout ---

    @Test
    fun `failed poll result throws the server message`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            pollOAuthDeviceCodeFlow(
                OAuthDeviceCodePollOptions(
                    poll = { OAuthDeviceCodePollResult.Failed("authorization_pending is bad") },
                ),
            ) { currentTime }
        }
        assertEquals("authorization_pending is bad", error.message)
    }

    @Test
    fun `timeout throws the plain timeout message`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            pollOAuthDeviceCodeFlow(
                OAuthDeviceCodePollOptions(
                    expiresInSeconds = 12,
                    poll = { OAuthDeviceCodePollResult.Pending },
                ),
            ) { currentTime }
        }
        assertEquals("Device flow timed out", error.message)
    }

    @Test
    fun `timeout after slow_down uses the distinct clock-drift message`() = runTest {
        var calls = 0
        val error = assertFailsWith<IllegalStateException> {
            pollOAuthDeviceCodeFlow(
                OAuthDeviceCodePollOptions(
                    expiresInSeconds = 12,
                    poll = {
                        calls += 1
                        if (calls == 1) OAuthDeviceCodePollResult.SlowDown() else OAuthDeviceCodePollResult.Pending
                    },
                ),
            ) { currentTime }
        }
        assertEquals(
            "Device flow timed out after one or more slow_down responses. " +
                "This is often caused by clock drift in WSL or VM environments. " +
                "Please sync or restart the VM clock and try again.",
            error.message,
        )
    }

    // --- slow_down interval handling ---

    @Test
    fun `slow_down without server interval adds 5 seconds (RFC 8628 section 3_5)`() = runTest {
        val times = mutableListOf<Long>()
        var calls = 0
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = 1.0,
                expiresInSeconds = 30,
                poll = {
                    times += currentTime
                    calls += 1
                    when {
                        calls == 1 -> OAuthDeviceCodePollResult.SlowDown()
                        calls == 3 -> OAuthDeviceCodePollResult.Complete("ok")
                        else -> OAuthDeviceCodePollResult.Pending
                    }
                },
            ),
        ) { currentTime }
        // t=0 slow_down; sleep 1s+5s; t=6000 pending; sleep 6s; t=12000 complete
        assertEquals(listOf(0L, 6000L, 12000L), times)
    }

    @Test
    fun `slow_down with server interval overrides the tracked interval`() = runTest {
        val times = mutableListOf<Long>()
        var calls = 0
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = 1.0,
                expiresInSeconds = 30,
                poll = {
                    times += currentTime
                    calls += 1
                    when {
                        calls == 1 -> OAuthDeviceCodePollResult.SlowDown(intervalSeconds = 10.0)
                        calls == 3 -> OAuthDeviceCodePollResult.Complete("ok")
                        else -> OAuthDeviceCodePollResult.Pending
                    }
                },
            ),
        ) { currentTime }
        // t=0 slow_down(interval=10); t=10000 pending; t=20000 complete
        assertEquals(listOf(0L, 10000L, 20000L), times)
    }

    @Test
    fun `slow_down with non-finite or non-positive interval falls back to +5s`() = runTest {
        val times = mutableListOf<Long>()
        var calls = 0
        pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                intervalSeconds = 2.0,
                expiresInSeconds = 30,
                poll = {
                    times += currentTime
                    calls += 1
                    when {
                        calls == 1 -> OAuthDeviceCodePollResult.SlowDown(intervalSeconds = Double.NaN)
                        calls == 2 -> OAuthDeviceCodePollResult.SlowDown(intervalSeconds = -1.0)
                        calls == 3 -> OAuthDeviceCodePollResult.Complete("ok")
                        else -> OAuthDeviceCodePollResult.Pending
                    }
                },
            ),
        ) { currentTime }
        // t=0 slow_down(NaN) -> 2s+5s; t=7000 slow_down(-1) -> 7s+5s; t=19000 complete
        assertEquals(listOf(0L, 7000L, 19000L), times)
    }

    // --- deadline edge cases ---

    @Test
    fun `sleep is clamped to the remaining deadline`() = runTest {
        var calls = 0
        val times = mutableListOf<Long>()
        assertFailsWith<IllegalStateException> {
            pollOAuthDeviceCodeFlow(
                OAuthDeviceCodePollOptions(
                    intervalSeconds = 10.0,
                    expiresInSeconds = 3,
                    poll = {
                        times += currentTime
                        calls += 1
                        OAuthDeviceCodePollResult.Pending
                    },
                ),
            ) { currentTime }
        }
        // Only t=0 poll fits; after it the remaining 3s elapse and the loop exits.
        assertEquals(listOf(0L), times)
    }

    @Test
    fun `flow without expiresInSeconds never times out`() = runTest {
        // Upstream: no expiresInSeconds -> deadline is +Infinity. Verify a
        // pending flow keeps polling rather than timing out.
        var calls = 0
        val value = pollOAuthDeviceCodeFlow(
            OAuthDeviceCodePollOptions(
                poll = {
                    calls += 1
                    if (calls >= 3) OAuthDeviceCodePollResult.Complete("ok") else OAuthDeviceCodePollResult.Pending
                },
            ),
        ) { currentTime }
        assertEquals("ok", value)
    }

    // --- cancellation (AbortSignal equivalent) ---

    @Test
    fun `cancelling during sleep throws CancellationException with the cancel message`() = runTest {
        var started = false
        val job = async {
            try {
                pollOAuthDeviceCodeFlow(
                    OAuthDeviceCodePollOptions(
                        intervalSeconds = 5.0,
                        poll = {
                            started = true
                            OAuthDeviceCodePollResult.Pending
                        },
                    ),
                ) { currentTime }
            } catch (e: CancellationException) {
                assertEquals("Login cancelled", e.message)
                throw e
            }
        }
        while (!started) kotlinx.coroutines.yield()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
    }
}
