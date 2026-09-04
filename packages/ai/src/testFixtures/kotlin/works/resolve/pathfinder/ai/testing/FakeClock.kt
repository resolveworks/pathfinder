package works.resolve.pathfinder.ai.testing

import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class FakeClock(startEpochMs: Long = 0L) : Clock {
    private var current: Instant = Instant.fromEpochMilliseconds(startEpochMs)

    override fun now(): Instant = current

    fun advanceMillis(ms: Long) {
        current += ms.milliseconds
    }
}
