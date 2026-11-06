package works.resolve.pathfinder.codingagent.core.utils

/**
 * Split a leading UTF-8 byte order mark from decoded text (pi's `splitBom`).
 *
 * The BOM is written as the `\uFEFF` escape, never a raw character: a raw
 * U+FEFF literal is invisible, and one was silently eaten from this source
 * once, making every edit drop the file's first character.
 */
fun splitBom(content: String): BomSplit {
    if (!content.startsWith("\uFEFF")) {
        return BomSplit(bom = "", text = content)
    }
    return BomSplit(bom = "\uFEFF", text = content.substring(1))
}

data class BomSplit(val bom: String, val text: String)

/** Remove a leading UTF-8 byte order mark from decoded text. */
fun stripBom(content: String): String = splitBom(content).text

/** At most the first [maxUnits] UTF-16 code units of [text], without splitting a surrogate pair. */
fun sliceHead(text: String, maxUnits: Int): String {
    val end = maxUnits.coerceIn(0, text.length)
    val splitsPair = end in 1 until text.length &&
        text[end - 1].isHighSurrogate() &&
        text[end].isLowSurrogate()
    return text.substring(0, if (splitsPair) end - 1 else end)
}

/** At most the last [maxUnits] UTF-16 code units of [text], without splitting a surrogate pair. */
fun sliceTail(text: String, maxUnits: Int): String {
    if (maxUnits <= 0) return ""
    val start = (text.length - maxUnits).coerceAtLeast(0)
    val splitsPair = start in 1 until text.length &&
        text[start].isLowSurrogate() &&
        text[start - 1].isHighSurrogate()
    return text.substring(if (splitsPair) start + 1 else start)
}
