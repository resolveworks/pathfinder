package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-value provenance test for shortHash (port of pi's
 * packages/ai/src/utils/hash.ts).
 *
 * Expected values were derived by executing pi's implementation directly:
 * `node --experimental-strip-types` importing
 * /home/johan/Projects/pi/packages/ai/src/utils/hash.ts and printing
 * shortHash() for each fixed input below, so the Kotlin imul/ushr/base36
 * chain is pinned to pi's exact output.
 */
class HashTest {

    @Test
    fun `matches pi reference values`() {
        assertEquals("k4n83c7h0j2b", shortHash(""))
        assertEquals("y0biex7f9bbh", shortHash("abc"))
        assertEquals("n7rb4n1m39uz8", shortHash("hello world"))
        assertEquals("1ir6h7h1xlwf6l", shortHash("pathfinder"))
        // Astral pair (UTF-16 code-unit iteration, like charCodeAt).
        assertEquals("17d5p5gmlqhu", shortHash("🙈 emoji pair"))
    }

    @Test
    fun `is deterministic`() {
        assertEquals(shortHash("call_x|fc_abc"), shortHash("call_x|fc_abc"))
        assertTrue(shortHash("call_x|fc_abc").length <= 26)
    }
}
