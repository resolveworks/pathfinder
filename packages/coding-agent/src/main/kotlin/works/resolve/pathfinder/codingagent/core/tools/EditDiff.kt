package works.resolve.pathfinder.codingagent.core.tools

import java.text.Normalizer

/**
 * Shared diff computation utilities for the edit and similar tools.
 *
 * Divergence from pi: the line diff itself (upstream: the `diff` npm package)
 * is an equivalent minimal Myers line diff, not a port of jsdiff internals —
 * both produce minimal diffs, but tie-breaking between equally minimal diffs
 * can differ, which may place otherwise-identical hunks slightly differently.
 * The display diff and unified patch *formatting* is ported exactly (including
 * `diff`'s structuredPatch/context handling and "\ No newline at end of file"
 * markers). Pi's `computeEditsDiff` preview helpers are TUI-only and unported.
 */

enum class LineEnding { CRLF, LF }

/** Split a leading UTF-8 byte order mark off decoded text (pi's `splitBom`). */
fun splitBom(content: String): BomSplit {
    if (!content.startsWith("")) {
        return BomSplit(bom = "", text = content)
    }
    return BomSplit(bom = "﻿", text = content.substring(1))
}

data class BomSplit(val bom: String, val text: String)

fun detectLineEnding(content: String): LineEnding {
    val crlfIdx = content.indexOf("\r\n")
    val lfIdx = content.indexOf("\n")
    if (lfIdx == -1) return LineEnding.LF
    if (crlfIdx == -1) return LineEnding.LF
    return if (crlfIdx < lfIdx) LineEnding.CRLF else LineEnding.LF
}

fun normalizeToLF(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")

fun restoreLineEndings(text: String, ending: LineEnding): String =
    if (ending == LineEnding.CRLF) text.replace("\n", "\r\n") else text

private val SMART_SINGLE_QUOTES = "[‘‚‛’]".toRegex()
private val SMART_DOUBLE_QUOTES = "[“„‟”]".toRegex()
private val UNICODE_DASHES = "[‐‑‒–—―−]".toRegex()
private val SPECIAL_SPACES = "[         　]".toRegex()

/**
 * Normalize text for fuzzy matching. Applies progressive transformations:
 * - Strip trailing whitespace from each line
 * - Normalize smart quotes to ASCII equivalents
 * - Normalize Unicode dashes/hyphens to ASCII hyphen
 * - Normalize special Unicode spaces to regular space
 */
fun normalizeForFuzzyMatch(text: String): String = buildString(text.length) {
    append(Normalizer.normalize(text, Normalizer.Form.NFKC))
}.split("\n").joinToString("\n") { line ->
    line.trimEnd()
}.replace(SMART_SINGLE_QUOTES, "'").replace(SMART_DOUBLE_QUOTES, "\"").replace(UNICODE_DASHES, "-")
    .replace(SPECIAL_SPACES, " ")

private fun splitLinesWithEndings(content: String): List<String> =
    "[^\\n]*\\n|[^\\n]+".toRegex().findAll(content).map { it.value }.toList()

private class LineSpan(val start: Int, val end: Int)

interface Edit {
    val oldText: String
    val newText: String
}

private data class DefaultEdit(override val oldText: String, override val newText: String) : Edit

fun editOf(oldText: String, newText: String): Edit = DefaultEdit(oldText, newText)

open class TextReplacement(val matchIndex: Int, val matchLength: Int, val newText: String)

private class MatchedEdit(val editIndex: Int, matchIndex: Int, matchLength: Int, newText: String) :
    TextReplacement(matchIndex, matchLength, newText)

private fun getLineSpans(content: String): List<LineSpan> {
    var offset = 0
    return splitLinesWithEndings(content).map { line ->
        val span = LineSpan(offset, offset + line.length)
        offset = span.end
        span
    }
}

private fun getReplacementLineRange(lines: List<LineSpan>, replacement: TextReplacement): IntRange {
    val replacementStart = replacement.matchIndex
    val replacementEnd = replacement.matchIndex + replacement.matchLength

    var startLine = -1
    for (i in lines.indices) {
        val line = lines[i]
        if (replacementStart >= line.start && replacementStart < line.end) {
            startLine = i
            break
        }
    }
    if (startLine == -1) {
        throw IllegalStateException("Replacement range is outside the base content.")
    }

    var endLine = startLine
    while (endLine < lines.size && lines[endLine].end < replacementEnd) {
        endLine++
    }
    if (endLine >= lines.size) {
        throw IllegalStateException("Replacement range is outside the base content.")
    }

    return startLine until endLine + 1
}

private fun applyReplacements(
    content: String,
    replacements: List<TextReplacement>,
    offset: Int = 0
): String {
    var result = content
    for (i in replacements.indices.reversed()) {
        val replacement = replacements[i]
        val matchIndex = replacement.matchIndex - offset
        result =
            result.substring(0, matchIndex) + replacement.newText +
            result.substring(matchIndex + replacement.matchLength)
    }
    return result
}

private class ReplacementGroup(
    val startLine: Int,
    var endLine: Int,
    val replacements: MutableList<TextReplacement>
)

/**
 * Apply replacements matched against `baseContent` to `originalContent` while
 * preserving unchanged line blocks from the original.
 *
 * This is useful when `baseContent` is a normalized view of the original. Each
 * replacement is widened to the lines it actually touches, those touched lines
 * are rewritten from the normalized base, and all other lines are copied back
 * from `originalContent`. The actual replacement ranges drive preservation so
 * duplicate normalized lines cannot be aligned to the wrong occurrence.
 */
private fun applyReplacementsPreservingUnchangedLines(
    originalContent: String,
    baseContent: String,
    replacements: List<TextReplacement>
): String {
    val originalLines = splitLinesWithEndings(originalContent)
    val baseLines = getLineSpans(baseContent)
    if (originalLines.size != baseLines.size) {
        throw IllegalStateException(
            "Cannot preserve unchanged lines because the base content has a different line count."
        )
    }

    val groups = mutableListOf<ReplacementGroup>()
    val sortedReplacements = replacements.sortedBy { it.matchIndex }
    for (replacement in sortedReplacements) {
        val range = getReplacementLineRange(baseLines, replacement)
        val current = groups.lastOrNull()
        if (current != null && range.first < current.endLine) {
            current.endLine = maxOf(current.endLine, range.last + 1)
            current.replacements.add(replacement)
            continue
        }
        groups.add(ReplacementGroup(range.first, range.last + 1, mutableListOf(replacement)))
    }

    var originalLineIndex = 0
    val result = StringBuilder()
    for (group in groups) {
        result.append(originalLines.subList(originalLineIndex, group.startLine).joinToString(""))

        val groupStartOffset = baseLines[group.startLine].start
        val groupEndOffset = baseLines[group.endLine - 1].end
        result.append(
            applyReplacements(
                baseContent.substring(groupStartOffset, groupEndOffset),
                group.replacements,
                groupStartOffset
            )
        )
        originalLineIndex = group.endLine
    }
    result.append(originalLines.subList(originalLineIndex, originalLines.size).joinToString(""))

    return result.toString()
}

class FuzzyMatchResult(
    /** Whether a match was found. */
    val found: Boolean,
    /** The index where the match starts (in the content that should be used for replacement). */
    val index: Int,
    /** Length of the matched text. */
    val matchLength: Int,
    /** Whether fuzzy matching was used (false = exact match). */
    val usedFuzzyMatch: Boolean,
    /**
     * The content to use for replacement operations. When exact match: the
     * original content. When fuzzy match: the normalized content.
     */
    val contentForReplacement: String
)

data class AppliedEditsResult(val baseContent: String, val newContent: String)

/**
 * Find oldText in content, trying exact match first, then fuzzy match. When
 * fuzzy matching is used, the returned contentForReplacement is the
 * fuzzy-normalized version of the content (trailing whitespace stripped,
 * Unicode quotes/dashes normalized to ASCII).
 */
fun fuzzyFindText(content: String, oldText: String): FuzzyMatchResult {
    // Try exact match first
    val exactIndex = content.indexOf(oldText)
    if (exactIndex != -1) {
        return FuzzyMatchResult(
            true,
            exactIndex,
            oldText.length,
            usedFuzzyMatch = false,
            contentForReplacement = content
        )
    }

    // Try fuzzy match - work entirely in normalized space
    val fuzzyContent = normalizeForFuzzyMatch(content)
    val fuzzyOldText = normalizeForFuzzyMatch(oldText)
    val fuzzyIndex = fuzzyContent.indexOf(fuzzyOldText)

    if (fuzzyIndex == -1) {
        return FuzzyMatchResult(
            false,
            -1,
            0,
            usedFuzzyMatch = false,
            contentForReplacement = content
        )
    }

    // When fuzzy matching, return offsets in normalized space. Callers can use
    // the normalized content to compute replacements, then decide how much of
    // that normalized output should be written back.
    return FuzzyMatchResult(
        true,
        fuzzyIndex,
        fuzzyOldText.length,
        usedFuzzyMatch = true,
        contentForReplacement = fuzzyContent
    )
}

private fun countOccurrences(content: String, oldText: String): Int {
    val fuzzyContent = normalizeForFuzzyMatch(content)
    val fuzzyOldText = normalizeForFuzzyMatch(oldText)
    return fuzzyContent.split(fuzzyOldText).size - 1
}

private fun getNotFoundError(path: String, editIndex: Int, totalEdits: Int): IllegalStateException =
    if (totalEdits == 1) {
        IllegalStateException(
            "Could not find the exact text in $path. The old text must " +
                "match exactly including all whitespace and newlines."
        )
    } else {
        IllegalStateException(
            "Could not find edits[$editIndex] in $path. The oldText " +
                "must match exactly including all whitespace and newlines."
        )
    }

private fun getDuplicateError(
    path: String,
    editIndex: Int,
    totalEdits: Int,
    occurrences: Int
): IllegalStateException = if (totalEdits == 1) {
    IllegalStateException(
        "Found $occurrences occurrences of the text in $path. The text " +
            "must be unique. Please provide more context to make it unique."
    )
} else {
    IllegalStateException(
        "Found $occurrences occurrences of edits[$editIndex] in $path. " +
            "Each oldText must be unique. Please provide more context to make it unique."
    )
}

private fun getEmptyOldTextError(
    path: String,
    editIndex: Int,
    totalEdits: Int
): IllegalStateException = if (totalEdits == 1) {
    IllegalStateException("oldText must not be empty in $path.")
} else {
    IllegalStateException("edits[$editIndex].oldText must not be empty in $path.")
}

private fun getNoChangeError(path: String, totalEdits: Int): IllegalStateException =
    if (totalEdits == 1) {
        IllegalStateException(
            "No changes made to $path. The replacement produced identical content. " +
                "This might indicate an issue with special characters or the text not existing as expected."
        )
    } else {
        IllegalStateException(
            "No changes made to $path. The replacements produced identical content."
        )
    }

/**
 * Apply one or more exact-text replacements to LF-normalized content.
 *
 * All edits are matched against the same original content. Replacements are
 * then applied in reverse order so offsets remain stable. If any edit needs
 * fuzzy matching, the operation runs in fuzzy-normalized content space and
 * then overlays those line-level changes onto the original content so
 * unchanged line blocks keep their original bytes.
 */
fun applyEditsToNormalizedContent(
    normalizedContent: String,
    edits: List<Edit>,
    path: String
): AppliedEditsResult {
    val normalizedEdits = edits.map {
        DefaultEdit(normalizeToLF(it.oldText), normalizeToLF(it.newText))
    }

    for (i in normalizedEdits.indices) {
        if (normalizedEdits[i].oldText.isEmpty()) {
            throw getEmptyOldTextError(path, i, normalizedEdits.size)
        }
    }

    val initialMatches = normalizedEdits.map { fuzzyFindText(normalizedContent, it.oldText) }
    val usedFuzzyMatch = initialMatches.any { it.usedFuzzyMatch }
    val replacementBaseContent = if (usedFuzzyMatch) {
        normalizeForFuzzyMatch(
            normalizedContent
        )
    } else {
        normalizedContent
    }

    val matchedEdits = mutableListOf<MatchedEdit>()
    for (i in normalizedEdits.indices) {
        val edit = normalizedEdits[i]
        val matchResult = fuzzyFindText(replacementBaseContent, edit.oldText)
        if (!matchResult.found) {
            throw getNotFoundError(path, i, normalizedEdits.size)
        }

        val occurrences = countOccurrences(replacementBaseContent, edit.oldText)
        if (occurrences > 1) {
            throw getDuplicateError(path, i, normalizedEdits.size, occurrences)
        }

        matchedEdits.add(MatchedEdit(i, matchResult.index, matchResult.matchLength, edit.newText))
    }

    matchedEdits.sortBy { it.matchIndex }
    for (i in 1 until matchedEdits.size) {
        val previous = matchedEdits[i - 1]
        val current = matchedEdits[i]
        if (previous.matchIndex + previous.matchLength > current.matchIndex) {
            throw IllegalStateException(
                "edits[${previous.editIndex}] and edits[${current.editIndex}] overlap in $path. " +
                    "Merge them into one edit or target disjoint regions."
            )
        }
    }

    val baseContent = normalizedContent
    val newContent = if (usedFuzzyMatch) {
        applyReplacementsPreservingUnchangedLines(
            normalizedContent,
            replacementBaseContent,
            matchedEdits
        )
    } else {
        applyReplacements(replacementBaseContent, matchedEdits)
    }

    if (baseContent == newContent) {
        throw getNoChangeError(path, normalizedEdits.size)
    }

    return AppliedEditsResult(baseContent, newContent)
}

// ---------------------------------------------------------------------------
// Line diff (jsdiff `diffLines` equivalent)
// ---------------------------------------------------------------------------

internal class DiffPart(val added: Boolean, val removed: Boolean, val value: String)

private enum class DiffOp { EQUAL, DELETE, INSERT }

/**
 * Guard against the O(D²) Myers trace blowing up memory on pathological
 * inputs (e.g. a full rewrite of a very large file): past this edit distance
 * the middle is diffed as one wholesale replacement, which is still a valid
 * diff, just not the minimal one.
 */
private const val MAX_MYERS_D = 4096

private fun tokenizeLines(value: String): List<String> {
    val tokens = mutableListOf<String>()
    var i = 0
    while (i < value.length) {
        val newline = value.indexOf('\n', i)
        if (newline == -1) {
            tokens.add(value.substring(i))
            break
        }
        tokens.add(value.substring(i, newline + 1))
        i = newline + 1
    }
    return tokens
}

private fun myersOps(a: List<String>, b: List<String>): List<Pair<DiffOp, Int>>? {
    val n = a.size
    val m = b.size
    if (n == 0 && m == 0) return emptyList()
    val max = n + m
    val offset = max
    val trace = ArrayList<IntArray>()
    val v = IntArray(2 * max + 1)
    var foundD = -1

    outer@ for (d in 0..max) {
        var k = -d
        while (k <= d) {
            val x = if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                v[k + 1 + offset]
            } else {
                v[k - 1 + offset] + 1
            }
            var cx = x
            var y = cx - k
            while (cx < n && y < m && a[cx] == b[y]) {
                cx++
                y++
            }
            v[k + offset] = cx
            if (cx >= n && y >= m) {
                trace.add(v.copyOf())
                foundD = d
                break@outer
            }
            k += 2
        }
        trace.add(v.copyOf())
        if (d == MAX_MYERS_D) return null
    }
    if (foundD == -1) return null

    val ops = mutableListOf<Pair<DiffOp, Int>>()
    var x = n
    var y = m
    for (d in foundD downTo 1) {
        val vPrev = trace[d - 1]
        val k = x - y
        val prevK = if (k == -d ||
            (k != d && vPrev[k - 1 + offset] < vPrev[k + 1 + offset])
        ) {
            k + 1
        } else {
            k - 1
        }
        val prevX = vPrev[prevK + offset]
        val prevY = prevX - prevK
        while (x > prevX && y > prevY) {
            x--
            y--
            ops.add(DiffOp.EQUAL to x)
        }
        if (x == prevX) {
            y--
            ops.add(DiffOp.INSERT to y)
        } else {
            x--
            ops.add(DiffOp.DELETE to x)
        }
    }
    while (x > 0 && y > 0) {
        x--
        y--
        ops.add(DiffOp.EQUAL to x)
    }
    ops.reverse()
    return ops
}

internal fun diffLineParts(oldContent: String, newContent: String): List<DiffPart> {
    // Trim the common prefix/suffix first so Myers only sees the changed middle.
    val a0 = tokenizeLines(oldContent)
    val b0 = tokenizeLines(newContent)
    var prefix = 0
    while (prefix < a0.size && prefix < b0.size && a0[prefix] == b0[prefix]) {
        prefix++
    }
    var suffix = 0
    while (
        suffix < a0.size - prefix && suffix < b0.size - prefix &&
        a0[a0.size - 1 - suffix] == b0[b0.size - 1 - suffix]
    ) {
        suffix++
    }
    val a = a0.subList(prefix, a0.size - suffix)
    val b = b0.subList(prefix, b0.size - suffix)

    val ops = myersOps(a, b) ?: run {
        // Fallback: wholesale replacement of the changed middle.
        buildList {
            for (i in a.indices) add(DiffOp.DELETE to i)
            for (i in b.indices) add(DiffOp.INSERT to i)
        }
    }

    val parts = mutableListOf<DiffPart>()
    val sb = StringBuilder()
    var current: DiffOp? = null
    fun flush() {
        if (current != null && sb.isNotEmpty()) {
            parts.add(
                DiffPart(
                    added = current == DiffOp.INSERT,
                    removed = current == DiffOp.DELETE,
                    value = sb.toString()
                )
            )
        }
        sb.clear()
    }
    if (prefix > 0) {
        parts.add(
            DiffPart(added = false, removed = false, value = a0.subList(0, prefix).joinToString(""))
        )
    }
    for ((op, index) in ops) {
        val token = when (op) {
            DiffOp.EQUAL -> a[index]
            DiffOp.DELETE -> a[index]
            DiffOp.INSERT -> b[index]
        }
        if (op != current) {
            flush()
            current = op
        }
        sb.append(token)
    }
    flush()
    if (suffix > 0) {
        parts.add(
            DiffPart(
                added = false,
                removed = false,
                value = a0.subList(a0.size - suffix, a0.size).joinToString("")
            )
        )
    }
    // Merge adjacent parts with identical flags (jsdiff joins consecutive
    // same-kind components; the prefix/suffix parts may abut equal middle runs).
    val merged = mutableListOf<DiffPart>()
    for (part in parts) {
        val last = merged.lastOrNull()
        if (last != null && last.added == part.added && last.removed == part.removed) {
            merged[merged.size - 1] = DiffPart(last.added, last.removed, last.value + part.value)
        } else {
            merged.add(part)
        }
    }
    return merged
}

/** jsdiff's `splitLines`: lines including the trailing newline, where present. */
private fun jsSplitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val hasTrailingNl = text.endsWith("\n")
    val result = text.split("\n").map { "$it\n" }.toMutableList()
    if (hasTrailingNl) {
        result.removeAt(result.size - 1)
    } else {
        result[result.size - 1] = result[result.size - 1].removeSuffix("\n")
    }
    return result
}

private class Hunk(
    var oldStart: Int,
    var oldLines: Int,
    var newStart: Int,
    var newLines: Int,
    val lines: MutableList<String>
)

/** jsdiff `structuredPatch` port: build hunks with the given context size. */
private fun buildHunks(parts: List<DiffPart>, context: Int): List<Hunk> {
    val diff = parts.toMutableList()
    // Append an empty value to make cleanup easier (jsdiff does the same).
    diff.add(DiffPart(added = false, removed = false, value = ""))

    val hunks = mutableListOf<Hunk>()
    var oldRangeStart = 0
    var newRangeStart = 0
    val curRange = mutableListOf<String>()
    var oldLine = 1
    var newLine = 1

    var i = 0
    while (i < diff.size) {
        val current = diff[i]
        val lines = jsSplitLines(current.value)
        if (current.added || current.removed) {
            // If we have previous context, start with that
            if (oldRangeStart == 0) {
                val prev = diff.getOrNull(i - 1)
                oldRangeStart = oldLine
                newRangeStart = newLine
                if (prev != null && context > 0) {
                    val prevLines = jsSplitLines(prev.value).takeLast(context)
                    curRange.addAll(prevLines.map { " $it" })
                    oldRangeStart -= curRange.size
                    newRangeStart -= curRange.size
                }
            }
            // Output our changes
            for (line in lines) {
                curRange.add((if (current.added) "+" else "-") + line)
            }
            // Track the updated file position
            if (current.added) {
                newLine += lines.size
            } else {
                oldLine += lines.size
            }
        } else {
            // Identical context lines. Track line changes
            if (oldRangeStart != 0) {
                // Close out any changes that have been output (or join overlapping)
                if (lines.size <= context * 2 && i < diff.size - 2) {
                    // Overlapping
                    curRange.addAll(lines.map { " $it" })
                } else {
                    // end the range and output
                    val contextSize = minOf(lines.size, context)
                    curRange.addAll(lines.subList(0, contextSize).map { " $it" })
                    hunks.add(
                        Hunk(
                            oldStart = oldRangeStart,
                            oldLines = oldLine - oldRangeStart + contextSize,
                            newStart = newRangeStart,
                            newLines = newLine - newRangeStart + contextSize,
                            lines = curRange.toMutableList()
                        )
                    )
                    oldRangeStart = 0
                    newRangeStart = 0
                    curRange.clear()
                }
            }
            oldLine += lines.size
            newLine += lines.size
        }
        i++
    }

    // Eliminate the trailing "\n" from each line of each hunk, and, where
    // needed, add "\ No newline at end of file".
    for (hunk in hunks) {
        var j = 0
        while (j < hunk.lines.size) {
            val line = hunk.lines[j]
            if (line.endsWith("\n")) {
                hunk.lines[j] = line.removeSuffix("\n")
            } else {
                hunk.lines.add(j + 1, "\\ No newline at end of file")
                j++ // Skip the line we just added
            }
            j++
        }
    }
    return hunks
}

/** Generate a standard unified patch (pi's `generateUnifiedPatch`, jsdiff
 * FILE_HEADERS_ONLY formatting). */
fun generateUnifiedPatch(
    path: String,
    oldContent: String,
    newContent: String,
    contextLines: Int = 4
): String {
    val hunks = buildHunks(diffLineParts(oldContent, newContent), contextLines)
    val ret = mutableListOf("--- $path", "+++ $path")
    for (hunk in hunks) {
        // Unified Diff Format quirk: If the chunk size is 0,
        // the first number is one lower than one would expect.
        if (hunk.oldLines == 0) {
            hunk.oldStart -= 1
        }
        if (hunk.newLines == 0) {
            hunk.newStart -= 1
        }
        ret.add("@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@")
        ret.addAll(hunk.lines)
    }
    return ret.joinToString("\n") + "\n"
}

class EditDiffResult(val diff: String, val firstChangedLine: Int?)

/**
 * Generate a display-oriented diff string with line numbers and context.
 * Returns both the diff string and the first changed line number (in the new
 * file).
 */
fun generateDiffString(
    oldContent: String,
    newContent: String,
    contextLines: Int = 4
): EditDiffResult {
    val parts = diffLineParts(oldContent, newContent)
    val output = mutableListOf<String>()

    val oldLines = oldContent.split("\n")
    val newLines = newContent.split("\n")
    val maxLineNum = maxOf(oldLines.size, newLines.size)
    val lineNumWidth = maxLineNum.toString().length

    var oldLineNum = 1
    var newLineNum = 1
    var lastWasChange = false
    var firstChangedLine: Int? = null

    for (i in parts.indices) {
        val part = parts[i]
        val raw = part.value.split("\n").toMutableList()
        if (raw.isNotEmpty() && raw.last() == "") {
            raw.removeAt(raw.size - 1)
        }

        if (part.added || part.removed) {
            // Capture the first changed line (in the new file)
            if (firstChangedLine == null) {
                firstChangedLine = newLineNum
            }

            // Show the change
            for (line in raw) {
                if (part.added) {
                    val lineNum = newLineNum.toString().padStart(lineNumWidth, ' ')
                    output.add("+$lineNum $line")
                    newLineNum++
                } else {
                    // removed
                    val lineNum = oldLineNum.toString().padStart(lineNumWidth, ' ')
                    output.add("-$lineNum $line")
                    oldLineNum++
                }
            }
            lastWasChange = true
        } else {
            // Context lines - only show a few before/after changes
            val nextPartIsChange =
                i < parts.size - 1 && (parts[i + 1].added || parts[i + 1].removed)
            val hasLeadingChange = lastWasChange
            val hasTrailingChange = nextPartIsChange

            if (hasLeadingChange && hasTrailingChange) {
                if (raw.size <= contextLines * 2) {
                    for (line in raw) {
                        val lineNum = oldLineNum.toString().padStart(lineNumWidth, ' ')
                        output.add(" $lineNum $line")
                        oldLineNum++
                        newLineNum++
                    }
                } else {
                    val leadingLines = raw.subList(0, contextLines)
                    val trailingLines = raw.subList(raw.size - contextLines, raw.size)
                    val skippedLines = raw.size - leadingLines.size - trailingLines.size

                    for (line in leadingLines) {
                        val lineNum = oldLineNum.toString().padStart(lineNumWidth, ' ')
                        output.add(" $lineNum $line")
                        oldLineNum++
                        newLineNum++
                    }

                    output.add(" ${"".padStart(lineNumWidth, ' ')} ...")
                    oldLineNum += skippedLines
                    newLineNum += skippedLines

                    for (line in trailingLines) {
                        val lineNum = oldLineNum.toString().padStart(lineNumWidth, ' ')
                        output.add(" $lineNum $line")
                        oldLineNum++
                        newLineNum++
                    }
                }
            } else if (hasLeadingChange) {
                val shownLines = raw.subList(0, minOf(contextLines, raw.size))
                val skippedLines = raw.size - shownLines.size

                for (line in shownLines) {
                    val lineNum = oldLineNum.toString().padStart(lineNumWidth, ' ')
                    output.add(" $lineNum $line")
                    oldLineNum++
                    newLineNum++
                }

                if (skippedLines > 0) {
                    output.add(" ${"".padStart(lineNumWidth, ' ')} ...")
                    oldLineNum += skippedLines
                    newLineNum += skippedLines
                }
            } else if (hasTrailingChange) {
                val skippedLines = maxOf(0, raw.size - contextLines)
                if (skippedLines > 0) {
                    output.add(" ${"".padStart(lineNumWidth, ' ')} ...")
                    oldLineNum += skippedLines
                    newLineNum += skippedLines
                }

                for (line in raw.subList(skippedLines, raw.size)) {
                    val lineNum = oldLineNum.toString().padStart(lineNumWidth, ' ')
                    output.add(" $lineNum $line")
                    oldLineNum++
                    newLineNum++
                }
            } else {
                // Skip these context lines entirely
                oldLineNum += raw.size
                newLineNum += raw.size
            }

            lastWasChange = false
        }
    }

    return EditDiffResult(output.joinToString("\n"), firstChangedLine)
}
