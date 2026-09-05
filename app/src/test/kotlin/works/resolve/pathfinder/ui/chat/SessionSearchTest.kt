package works.resolve.pathfinder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSearchTest {
    private fun makeSession(
        id: String,
        modified: Long,
        allMessagesText: String,
        name: String? = null
    ): SessionSearchEntry {
        val searchText = "$id ${name ?: ""} $allMessagesText"
        return SessionSearchEntry(id = id, modified = modified, searchText = searchText)
    }

    @Test
    fun filtersByQuotedPhraseWithWhitespaceNormalization() {
        val sessions =
            listOf(
                makeSession("a", 1, "node\n\n   cve was discussed"),
                makeSession("b", 2, "node something else")
            )

        val result = filterAndSortSessions(sessions, "\"node cve\"", SessionSearchSort.RECENT)
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun filtersByRegexAndIsCaseInsensitive() {
        val sessions =
            listOf(
                makeSession("a", 2, "Brave is great"),
                makeSession("b", 3, "bravery is not the same")
            )

        val result = filterAndSortSessions(sessions, "re:\\bbrave\\b", SessionSearchSort.RECENT)
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun recentSortPreservesInputOrder() {
        val sessions =
            listOf(
                makeSession("newer", 3, "brave"),
                makeSession("older", 1, "brave"),
                makeSession("nomatch", 4, "something else")
            )

        val result = filterAndSortSessions(sessions, "\"brave\"", SessionSearchSort.RECENT)
        assertEquals(listOf("newer", "older"), result.map { it.id })
    }

    @Test
    fun relevanceSortOrdersByScoreAndTieBreaksByModifiedDesc() {
        val sessions =
            listOf(
                makeSession("late", 3, "xxxx brave"),
                makeSession("early", 1, "brave xxxx")
            )

        val result1 = filterAndSortSessions(sessions, "\"brave\"", SessionSearchSort.RELEVANCE)
        assertEquals(listOf("early", "late"), result1.map { it.id })

        val tieSessions =
            listOf(
                makeSession("newer", 3, "brave"),
                makeSession("older", 1, "brave")
            )

        val result2 = filterAndSortSessions(tieSessions, "\"brave\"", SessionSearchSort.RELEVANCE)
        assertEquals(listOf("newer", "older"), result2.map { it.id })
    }

    @Test
    fun returnsEmptyListForInvalidRegex() {
        val sessions = listOf(makeSession("a", 1, "brave"))

        val result = filterAndSortSessions(sessions, "re:(", SessionSearchSort.RECENT)
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    @Test
    fun blankQueryReturnsInputUnchanged() {
        val sessions =
            listOf(
                makeSession("named", 3, "blueberry", name = "My Project"),
                makeSession("other", 1, "blueberry")
            )

        assertEquals(sessions, filterAndSortSessions(sessions, "", SessionSearchSort.RECENT))
        assertEquals(sessions, filterAndSortSessions(sessions, "   ", SessionSearchSort.RELEVANCE))
    }
}
