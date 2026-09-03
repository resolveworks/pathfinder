package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Uuidv7Test {

    /** First 12 hex chars are the 48-bit big-endian timestamp. */
    private fun parseTimestamp(id: String): Long = id.replace("-", "").substring(0, 12).toLong(16)

    @Test
    fun `produces canonical lowercase uuid format`() {
        val id = uuidv7()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id), "not canonical: $id")
    }

    @Test
    fun `sets version 7 and rfc variant bits`() {
        val id = uuidv7()
        assertEquals("7", id.substring(14, 15), "version nibble must be 7: $id")
        assertTrue(id.substring(19, 20).toInt(16) in 8..11, "variant bits must be 10xx: $id")
    }

    @Test
    fun `embeds the current unix millisecond timestamp`() {
        val before = System.currentTimeMillis()
        val id = uuidv7()
        val after = System.currentTimeMillis()
        val timestamp = parseTimestamp(id)
        assertTrue(timestamp in before..after, "timestamp $timestamp outside [$before, $after]")
    }

    @Test
    fun `strictly increases and stays unique across rapid calls`() {
        // Same-millisecond ties advance the sequence counter, so consecutive
        // calls sort strictly ascending even when the clock does not move.
        var previous = uuidv7()
        repeat(10_000) {
            val current = uuidv7()
            assertTrue(current > previous, "not increasing: $previous -> $current")
            previous = current
        }
    }

    /** Ports uuid.test.ts "generates ordered UUIDv7s while preserving follower timestamps". */
    @Test
    fun `explicit timestamps are preserved for follower ids`() {
        val followerTimestamp = System.currentTimeMillis() - 1_000
        val first = uuidv7(followerTimestamp)
        val second = uuidv7(followerTimestamp)
        assertEquals(followerTimestamp, parseTimestamp(first))
        assertEquals(followerTimestamp, parseTimestamp(second))
        // The sequence still advances, so same-timestamp followers stay distinct
        // and ordered.
        assertTrue(first < second)
        // Ordinary calls after a follower keep their own wall-clock timestamp.
        val ordinary = uuidv7()
        assertTrue(parseTimestamp(ordinary) >= followerTimestamp)
    }

    /** Ports uuid.test.ts "accepts timestamp boundary %s". */
    @Test
    fun `accepts timestamp boundaries`() {
        assertEquals(0L, parseTimestamp(uuidv7(0)))
        assertEquals((1L shl 48) - 1, parseTimestamp(uuidv7((1L shl 48) - 1)))
    }

    /**
     * Ports uuid.test.ts "rejects invalid timestamp %s". The JS float cases
     * (1.5, NaN, Infinity) are unrepresentable in the Long parameter.
     */
    @Test
    fun `rejects invalid timestamps`() {
        assertFailsWith<IllegalArgumentException> { uuidv7(-1) }
        assertFailsWith<IllegalArgumentException> { uuidv7(1L shl 48) }
    }
}
