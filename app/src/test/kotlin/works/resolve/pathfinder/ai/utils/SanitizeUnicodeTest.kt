package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Provenance test for sanitizeSurrogates, porting the doc examples of pi's
 * packages/ai/src/utils/sanitize-unicode.ts.
 */
class SanitizeUnicodeTest {

    @Test
    fun `paired surrogates are preserved`() {
        // sanitizeSurrogates("Hello 🙈 World") // => "Hello 🙈 World"
        assertEquals("Hello 🙈 World", sanitizeSurrogates("Hello 🙈 World"))
    }

    @Test
    fun `unpaired high surrogate is removed`() {
        // String.fromCharCode(0xD83D) without a low surrogate.
        val unpaired = String(charArrayOf(0xD83D.toChar()))
        assertEquals("Text  here", sanitizeSurrogates("Text ${unpaired} here"))
    }

    @Test
    fun `unpaired low surrogate is removed`() {
        val unpaired = String(charArrayOf(0xDE48.toChar()))
        assertEquals("Text  here", sanitizeSurrogates("Text ${unpaired} here"))
    }
}
