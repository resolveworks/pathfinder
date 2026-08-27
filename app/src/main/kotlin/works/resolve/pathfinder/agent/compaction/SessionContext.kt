package works.resolve.pathfinder.agent.compaction

import works.resolve.pathfinder.data.sessions.CompactionEntry
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.SessionEntry
import works.resolve.pathfinder.ai.core.Message

/**
 * Reduced port of pi's session-context builder
 * (`packages/agent/src/harness/session/context.ts`), limited to what the
 * compaction port needs: the message list [buildSessionContext] produces.
 *
 * Divergences (documented per symbol):
 * - Upstream `SessionContext` also derives `thinkingLevel`, `model`, and
 *   `activeToolNames` from thinking_level/model_change/active_tools_change
 *   entries; those entry kinds do not exist in pathfinder's [SessionEntry],
 *   so only the message projection is ported.
 * - Upstream `SessionContextBuildOptions` (entryTransforms/projectors for
 *   `custom` entries) has no counterpart; `custom` entries do not exist.
 */

/**
 * Keep only the latest compaction entry and everything after it
 * (context.ts `defaultContextEntryTransform`): everything before the latest
 * compaction is already summarized into it.
 */
fun defaultContextEntryTransform(pathEntries: List<SessionEntry>): List<SessionEntry> {
    var compactionIndex = -1
    for (index in pathEntries.indices.reversed()) {
        if (pathEntries[index] is CompactionEntry) {
            compactionIndex = index
            break
        }
    }
    return if (compactionIndex == -1) pathEntries.toList() else pathEntries.drop(compactionIndex)
}

/**
 * The latest compaction entry on a session path, or null (pi's
 * `getLatestCompactionEntry`, coding-agent session-manager.ts:316); the
 * boundary guard for stale pre-compaction usage/errors.
 */
fun getLatestCompactionEntry(entries: List<SessionEntry>): CompactionEntry? {
    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        if (entry is CompactionEntry) return entry
    }
    return null
}

/**
 * Project one context entry to the messages it contributes
 * (context.ts `sessionEntryToContextMessages`, reduced).
 */
private fun sessionEntryToContextMessages(entry: SessionEntry): List<Message> = when (entry) {
    is MessageEntry -> listOf(entry.message)
    is CompactionEntry -> listOf(createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)) +
        entry.retainedTail
}

/**
 * Build the LLM-facing message list for a root→leaf session path
 * (context.ts `buildSessionContext`, reduced to `messages`).
 */
fun buildSessionContext(pathEntries: List<SessionEntry>): List<Message> =
    defaultContextEntryTransform(pathEntries).flatMap(::sessionEntryToContextMessages)
