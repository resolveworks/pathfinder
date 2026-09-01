package works.resolve.pathfinder.agent.compaction

import works.resolve.pathfinder.data.sessions.ActiveToolsEntry
import works.resolve.pathfinder.data.sessions.BranchSummaryEntry
import works.resolve.pathfinder.data.sessions.CompactionEntry
import works.resolve.pathfinder.data.sessions.CustomEntry
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.ModelChangeEntry
import works.resolve.pathfinder.data.sessions.SessionEntry
import works.resolve.pathfinder.data.sessions.ThinkingLevelEntry
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.StopReason

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

// Deferred assistant messages contribute no context messages (context.ts
// `sessionEntryToContextMessages`, see below); StopReason.DEFERRED is the
// ported union value they are recognized by.

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
    is MessageEntry -> {
        // Deferred assistant messages drop from context (context.ts:72): a
        // deferred response is not final and its content is not authoritative.
        val assistant = entry.message as? AssistantMessage
        if (assistant != null && assistant.stopReason == StopReason.DEFERRED) {
            emptyList()
        } else {
            listOf(entry.message)
        }
    }
    is CompactionEntry -> listOf(createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)) +
        entry.retainedTail
    // Upstream guards `entry.summary` truthiness (context.ts:81); summary is
    // non-null here, so the guard reduces to empty-string exclusion.
    is BranchSummaryEntry -> if (entry.summary.isNotEmpty()) {
        listOf(createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp))
    } else {
        emptyList()
    }
    // Configuration/bookkeeping kinds contribute no context messages
    // (context.ts falls through to []). Divergence: upstream routes `custom`
    // entries through entry projectors, but pathfinder has no producer, so
    // nothing projects.
    is ModelChangeEntry, is ThinkingLevelEntry, is ActiveToolsEntry, is CustomEntry -> emptyList()
}

/**
 * Build the LLM-facing message list for a root→leaf session path
 * (context.ts `buildSessionContext`, reduced to `messages`).
 */
fun buildSessionContext(pathEntries: List<SessionEntry>): List<Message> =
    defaultContextEntryTransform(pathEntries).flatMap(::sessionEntryToContextMessages)
