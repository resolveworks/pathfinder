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
