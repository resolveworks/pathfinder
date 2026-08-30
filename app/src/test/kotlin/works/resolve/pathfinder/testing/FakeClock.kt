package works.resolve.pathfinder.testing

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deterministic [Clock] for tests: starts at [startEpochMs] and advances only
 * when the test says so. Use with constructor-injected clocks (TS→Kotlin
 * conventions: Injectable timing uses kotlin.time.Clock).
 */
class FakeClock(startEpochMs: Long = 0L) : Clock {
    private var current: Instant = Instant.fromEpochMilliseconds(startEpochMs)

    override fun now(): Instant = current

    fun advanceMillis(ms: Long) {
        current += ms.milliseconds
    }
}
