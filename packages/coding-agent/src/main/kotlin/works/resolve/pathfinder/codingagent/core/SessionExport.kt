package works.resolve.pathfinder.codingagent.core

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** pi's TrailingEntries: raw entries appended after the branch, verbatim. */
fun interface TrailingEntries {
    operator fun invoke(parentId: String?, timestampMillis: Long): List<JsonObject>
}

/**
 * pi's session-export.ts: the current branch serialized as a fresh,
 * single-branch session (what pi's /export writes), or written to a file
 * with pi's default export name. The serialized form is re-loadable by pi
 * itself (and this runtime).
 *
 * Divergences from pi:
 * - The destination is an output directory, not a cwd-resolved output path
 *   (Android has no working directory); an explicit name keeps pi's
 *   outputPath override.
 * - The header has no cwd field (no producer here); the shared
 *   [JsonlCodec.encodeHeaderLine] already omits it.
 * - Trailing entries receive the header timestamp as epoch millis (the
 *   port's [JsonlCodec.SessionHeader] form), not pi's ISO string.
 *
 * The `System.currentTimeMillis()` calls below are the wall-time reads
 * sanctioned by the AGENTS.md "Time and deadlines" rule: pi stamps
 * `new Date()` inline in both spots, and stamping the export's header and
 * default filename is this module's job.
 */
fun serializeSessionBranch(
    session: ReadonlySessionManager,
    createTrailingEntries: TrailingEntries? = null
): String {
    val timestampMillis = System.currentTimeMillis()
    val header = JsonlCodec.SessionHeader(
        id = session.getSessionId(),
        timestamp = timestampMillis
    )
    val lines = StringBuilder(JsonlCodec.encodeHeaderLine(header))
    var parentId: String? = null
    for (entry in session.getBranch()) {
        lines.append(encodeEntryLine(entry, parentId))
        parentId = entry.id
    }
    for (trailing in createTrailingEntries?.invoke(parentId, timestampMillis) ?: emptyList()) {
        lines.append(trailing.toString() + "\n")
    }
    return lines.toString()
}

/** pi's exportSessionToJsonl: write the export, return the file written. */
fun exportSessionToJsonl(
    session: ReadonlySessionManager,
    outputDir: File,
    fileName: String? = null,
    createTrailingEntries: TrailingEntries? = null
): File {
    val file = File(outputDir, fileName ?: defaultExportFileName())
    outputDir.mkdirs()
    file.writeText(serializeSessionBranch(session, createTrailingEntries))
    return file
}

/** pi's exportSessionToJsonl default output name (sans its fs write). */
private fun defaultExportFileName(): String = "session-" + JsonlCodec.formatIso(
    System.currentTimeMillis()
)
    .replace(":", "-")
    .replace(".", "-") + ".jsonl"

/**
 * pi's `{ ...entry, parentId }` spread: the entry re-serialized with the
 * re-chained parent. For a parent-link path the chain equals each entry's
 * own parentId except at the root, where a dangling parent (a corrupt file)
 * is normalized to null like pi's override does.
 */
private fun encodeEntryLine(entry: SessionEntry, parentId: String?): String = when (entry) {
    is RawEntry -> buildJsonObject {
        entry.json.forEach { (key, value) -> put(key, value) }
        put("parentId", parentId)
    }.toString() + "\n"

    is MessageEntry -> JsonlCodec.encodeEntryLine(entry.copy(parentId = parentId))

    is CompactionEntry -> JsonlCodec.encodeEntryLine(entry.copy(parentId = parentId))

    is ModelChangeEntry -> JsonlCodec.encodeEntryLine(entry.copy(parentId = parentId))

    is ThinkingLevelEntry -> JsonlCodec.encodeEntryLine(entry.copy(parentId = parentId))

    is BranchSummaryEntry -> JsonlCodec.encodeEntryLine(entry.copy(parentId = parentId))

    is ContextEditEntry -> JsonlCodec.encodeEntryLine(entry.copy(parentId = parentId))
}
