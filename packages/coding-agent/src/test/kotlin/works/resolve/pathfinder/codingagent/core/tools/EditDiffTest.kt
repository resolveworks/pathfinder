package works.resolve.pathfinder.codingagent.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditDiffTest {
    @Test
    fun `per-line fuzzy trim strips U+FEFF like JS trimEnd`() {
        // pi's edit-diff.ts strips trailing whitespace per line with JS
        // trimEnd, whose set includes U+FEFF; Kotlin's trimEnd does not.
        val content = "alpha\uFEFF\nbeta\n"
        val result = fuzzyFindText(content, "alpha\nbeta\n")

        assertTrue(result.usedFuzzyMatch)
        assertEquals("alpha\nbeta\n", result.contentForReplacement)
    }

    @Test
    fun `U+001C stays on the line because JS trimEnd does not strip it`() {
        // Kotlin trimEnd would strip U+001C-U+001F; the JS set keeps them,
        // so the fuzzy match must fail here like pi's.
        val content = "alpha\u001C\nbeta\n"
        val result = fuzzyFindText(content, "alpha\nbeta\n")

        assertFalse(result.found)
    }
}
