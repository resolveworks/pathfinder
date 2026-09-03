package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Uuidv7Test {

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
        // First 12 hex chars are the 48-bit big-endian timestamp.
        val timestamp = id.replace("-", "").substring(0, 12).toLong(16)
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
}
