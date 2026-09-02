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
 * Keep only the latest compaction entry and everything after it: everything
 * before it is already summarized into that entry.
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

fun getLatestCompactionEntry(entries: List<SessionEntry>): CompactionEntry? {
    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        if (entry is CompactionEntry) return entry
    }
    return null
}

private fun sessionEntryToContextMessages(entry: SessionEntry): List<Message> = when (entry) {
    is MessageEntry -> {
        // Deferred assistant messages drop from context: a deferred response
        // is not final and its content is not authoritative.
        val assistant = entry.message as? AssistantMessage
        if (assistant != null && assistant.stopReason == StopReason.DEFERRED) {
            emptyList()
        } else {
            listOf(entry.message)
        }
    }
    is CompactionEntry -> listOf(createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)) +
        entry.retainedTail
    // Upstream guards summary truthiness; with a non-null summary here, that
    // reduces to excluding the empty string.
    is BranchSummaryEntry -> if (entry.summary.isNotEmpty()) {
        listOf(createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp))
    } else {
        emptyList()
    }
    // Upstream routes `custom` entries through per-type projectors; pathfinder
    // has no custom-entry producer, so nothing projects.
    is ModelChangeEntry, is ThinkingLevelEntry, is ActiveToolsEntry, is CustomEntry -> emptyList()
}

fun buildSessionContext(pathEntries: List<SessionEntry>): List<Message> =
    defaultContextEntryTransform(pathEntries).flatMap(::sessionEntryToContextMessages)
