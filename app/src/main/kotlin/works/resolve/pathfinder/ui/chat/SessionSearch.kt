package works.resolve.pathfinder.ui.chat

internal data class FuzzyMatch(val matches: Boolean, val score: Double)

/**
 * Greedy in-order subsequence match over lowercased strings. Lower score is better.
 */
internal fun fuzzyMatch(query: String, text: String): FuzzyMatch {
    val queryLower = query.lowercase()
    val textLower = text.lowercase()

    fun matchQuery(normalizedQuery: String): FuzzyMatch {
        if (normalizedQuery.isEmpty()) return FuzzyMatch(matches = true, score = 0.0)
        if (normalizedQuery.length >
            textLower.length
        ) {
            return FuzzyMatch(matches = false, score = 0.0)
        }

        var queryIndex = 0
        var score = 0.0
        var lastMatchIndex = -1
        var consecutiveMatches = 0

        var i = 0
        while (i < textLower.length && queryIndex < normalizedQuery.length) {
            if (textLower[i] == normalizedQuery[queryIndex]) {
                val prev = if (i == 0) null else textLower[i - 1]
                val isWordBoundary = i == 0 || prev!!.isWhitespace() || prev in "-_./:"

                if (lastMatchIndex == i - 1) {
                    consecutiveMatches++
                    score -= consecutiveMatches * 5.0
                } else {
                    consecutiveMatches = 0
                    if (lastMatchIndex >= 0) {
                        score += (i - lastMatchIndex - 1) * 2.0
                    }
                }

                if (isWordBoundary) score -= 10.0
                score += i * 0.1

                lastMatchIndex = i
                queryIndex++
            }
            i++
        }

        if (queryIndex < normalizedQuery.length) return FuzzyMatch(matches = false, score = 0.0)
        if (normalizedQuery == textLower) score -= 100.0
        return FuzzyMatch(matches = true, score)
    }

    val primaryMatch = matchQuery(queryLower)
    if (primaryMatch.matches) return primaryMatch

    val alphaNumeric = Regex("^([a-z]+)([0-9]+)$").find(queryLower)
    val numericAlpha = Regex("^([0-9]+)([a-z]+)$").find(queryLower)
    val swappedQuery = when {
        alphaNumeric != null -> alphaNumeric.groupValues[2] + alphaNumeric.groupValues[1]
        numericAlpha != null -> numericAlpha.groupValues[2] + numericAlpha.groupValues[1]
        else -> ""
    }

    if (swappedQuery.isEmpty()) return primaryMatch

    val swappedMatch = matchQuery(swappedQuery)
    if (!swappedMatch.matches) return primaryMatch

    return FuzzyMatch(matches = true, score = swappedMatch.score + 5.0)
}

enum class SessionSearchSort { RECENT, RELEVANCE }

internal data class SearchToken(val kind: Kind, val value: String) {
    enum class Kind { FUZZY, PHRASE }
}

internal data class ParsedSearchQuery(
    val mode: Mode,
    val tokens: List<SearchToken>,
    val regex: Regex?,
    val error: String? = null
) {
    enum class Mode { TOKENS, REGEX }
}

internal fun parseSearchQuery(query: String): ParsedSearchQuery {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return ParsedSearchQuery(
            ParsedSearchQuery.Mode.TOKENS,
            emptyList(),
            null
        )
    }

    if (trimmed.startsWith("re:")) {
        val pattern = trimmed.substring(3).trim()
        if (pattern.isEmpty()) {
            return ParsedSearchQuery(
                ParsedSearchQuery.Mode.REGEX,
                emptyList(),
                null,
                error = "Empty regex"
            )
        }
        return try {
            ParsedSearchQuery(
                ParsedSearchQuery.Mode.REGEX,
                emptyList(),
                Regex(pattern, RegexOption.IGNORE_CASE)
            )
        } catch (err: Exception) {
            ParsedSearchQuery(
                ParsedSearchQuery.Mode.REGEX,
                emptyList(),
                null,
                error = err.message ?: err.toString()
            )
        }
    }

    val tokens = mutableListOf<SearchToken>()
    var buf = ""
    var inQuote = false

    fun flush(kind: SearchToken.Kind) {
        val v = buf.trim()
        buf = ""
        if (v.isEmpty()) return
        tokens.add(SearchToken(kind, v))
    }

    for (ch in trimmed) {
        if (ch == '"') {
            if (inQuote) {
                flush(SearchToken.Kind.PHRASE)
                inQuote = false
            } else {
                flush(SearchToken.Kind.FUZZY)
                inQuote = true
            }
            continue
        }

        if (!inQuote && ch.isWhitespace()) {
            flush(SearchToken.Kind.FUZZY)
            continue
        }

        buf += ch
    }

    // Unbalanced quotes fall back to plain whitespace tokenization.
    if (inQuote) {
        val fallback =
            trimmed
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { SearchToken(SearchToken.Kind.FUZZY, it) }
        return ParsedSearchQuery(ParsedSearchQuery.Mode.TOKENS, fallback, null)
    }

    flush(SearchToken.Kind.FUZZY)
    return ParsedSearchQuery(ParsedSearchQuery.Mode.TOKENS, tokens, null)
}

internal data class MatchResult(val matches: Boolean, val score: Double)

internal data class SessionSearchEntry(val id: String, val updatedAt: Long, val searchText: String)

private fun normalizeWhitespaceLower(text: String): String =
    text.lowercase().replace(Regex("\\s+"), " ").trim()

internal fun matchSession(searchText: String, parsed: ParsedSearchQuery): MatchResult {
    if (parsed.mode == ParsedSearchQuery.Mode.REGEX) {
        val regex = parsed.regex ?: return MatchResult(matches = false, score = 0.0)
        val idx =
            regex.find(searchText)?.range?.first ?: return MatchResult(matches = false, score = 0.0)
        return MatchResult(matches = true, score = idx * 0.1)
    }

    if (parsed.tokens.isEmpty()) return MatchResult(matches = true, score = 0.0)

    var totalScore = 0.0
    var normalizedText: String? = null

    for (token in parsed.tokens) {
        if (token.kind == SearchToken.Kind.PHRASE) {
            if (normalizedText == null) normalizedText = normalizeWhitespaceLower(searchText)
            val phrase = normalizeWhitespaceLower(token.value)
            if (phrase.isEmpty()) continue
            val idx = normalizedText.indexOf(phrase)
            if (idx < 0) return MatchResult(matches = false, score = 0.0)
            totalScore += idx * 0.1
            continue
        }

        val m = fuzzyMatch(token.value, searchText)
        if (!m.matches) return MatchResult(matches = false, score = 0.0)
        totalScore += m.score
    }

    return MatchResult(matches = true, score = totalScore)
}

internal fun filterAndSortSessions(
    sessions: List<SessionSearchEntry>,
    query: String,
    sortMode: SessionSearchSort
): List<SessionSearchEntry> {
    if (query.trim().isEmpty()) return sessions

    val parsed = parseSearchQuery(query)
    if (parsed.error != null) return emptyList()

    if (sortMode == SessionSearchSort.RECENT) {
        return sessions.filter { matchSession(it.searchText, parsed).matches }
    }

    return sessions
        .mapNotNull { s ->
            val res = matchSession(s.searchText, parsed)
            if (res.matches) s to res.score else null
        }
        .sortedWith(
            compareBy<Pair<SessionSearchEntry, Double>> {
                it.second
            }.thenByDescending { it.first.updatedAt }
        )
        .map { it.first }
}
