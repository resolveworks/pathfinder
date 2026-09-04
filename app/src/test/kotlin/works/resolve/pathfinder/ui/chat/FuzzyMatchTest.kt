package works.resolve.pathfinder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatchTest {
    @Test
    fun emptyQueryMatchesEverythingWithScoreZero() {
        val result = fuzzyMatch("", "anything")
        assertTrue(result.matches)
        assertEquals(0.0, result.score, 0.0)
    }

    @Test
    fun queryLongerThanTextDoesNotMatch() {
        val result = fuzzyMatch("longquery", "short")
        assertFalse(result.matches)
    }

    @Test
    fun exactMatchHasGoodScore() {
        val result = fuzzyMatch("test", "test")
        assertTrue(result.matches)
        assertTrue(result.score < 0)
    }

    @Test
    fun charactersMustAppearInOrder() {
        val matchInOrder = fuzzyMatch("abc", "aXbXc")
        assertTrue(matchInOrder.matches)

        val matchOutOfOrder = fuzzyMatch("abc", "cba")
        assertFalse(matchOutOfOrder.matches)
    }

    @Test
    fun caseInsensitiveMatching() {
        val result = fuzzyMatch("ABC", "abc")
        assertTrue(result.matches)

        val result2 = fuzzyMatch("abc", "ABC")
        assertTrue(result2.matches)
    }

    @Test
    fun consecutiveMatchesScoreBetterThanScatteredMatches() {
        val consecutive = fuzzyMatch("foo", "foobar")
        val scattered = fuzzyMatch("foo", "f_o_o_bar")

        assertTrue(consecutive.matches)
        assertTrue(scattered.matches)
        assertTrue(consecutive.score < scattered.score)
    }

    @Test
    fun wordBoundaryMatchesScoreBetter() {
        val atBoundary = fuzzyMatch("fb", "foo-bar")
        val notAtBoundary = fuzzyMatch("fb", "afbx")

        assertTrue(atBoundary.matches)
        assertTrue(notAtBoundary.matches)
        assertTrue(atBoundary.score < notAtBoundary.score)
    }

    @Test
    fun matchesSwappedAlphaNumericTokens() {
        val result = fuzzyMatch("codex52", "gpt-5.2-codex")
        assertTrue(result.matches)
    }
}
