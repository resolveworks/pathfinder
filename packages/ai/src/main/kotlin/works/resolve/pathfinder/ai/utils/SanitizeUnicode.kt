package works.resolve.pathfinder.ai.utils

/**
 * Removes unpaired Unicode surrogates, which cause JSON serialization errors
 * in many API providers. Valid surrogate pairs (emoji, astral text) are
 * preserved.
 */
internal fun sanitizeSurrogates(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate()) {
            if (i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                sb.append(c).append(text[i + 1])
                i += 2
            } else {
                i += 1
            }
        } else if (c.isLowSurrogate()) {
            // Unreachable: any low surrogate after a high one was already
            // consumed as a pair above; the guard just mirrors the pairing rule.
            if (i > 0 && text[i - 1].isHighSurrogate()) {
                sb.append(c)
            }
            i += 1
        } else {
            sb.append(c)
            i += 1
        }
    }
    return sb.toString()
}
