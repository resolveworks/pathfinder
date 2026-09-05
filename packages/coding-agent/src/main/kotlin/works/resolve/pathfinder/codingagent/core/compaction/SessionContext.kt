package works.resolve.pathfinder.codingagent.core.compaction

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.session.SessionEntry
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry

/**
 * Pathfinder-specific helper with no upstream twin: AgentSession's
 * compaction guard uses it to skip stale pre-compaction usage.
 */
fun getLatestCompactionEntry(entries: List<SessionEntry>): CompactionEntry? {
    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        if (entry is CompactionEntry) return entry
    }
    return null
}

/**
 * pi's sessionEntryToContextMessages: project one entry into LLM messages.
 * Message entries pass through (deferred assistant messages drop: a
 * deferred response is not final and its content is not authoritative);
 * compaction and branch-summary entries project to their wrapped summary
 * messages; configuration entries project nothing.
 */
fun sessionEntryToContextMessages(entry: SessionEntry): List<Message> = when (entry) {
    is MessageEntry -> {
        val assistant = entry.message as? AssistantMessage
        if (assistant != null && assistant.stopReason == StopReason.DEFERRED) {
            emptyList()
        } else {
            listOf(entry.message)
        }
    }

    is CompactionEntry -> listOf(
        createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)
    )

    // Upstream guards summary truthiness; with a non-null summary here, that
    // reduces to excluding the empty string.
    is BranchSummaryEntry -> if (entry.summary.isNotEmpty()) {
        listOf(createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp))
    } else {
        emptyList()
    }

    is ModelChangeEntry, is ThinkingLevelEntry -> emptyList()
}

/**
 * pi's buildContextEntries over the leaf path: the latest compaction is
 * represented by the compaction entry itself, followed by the path entries
 * from `firstKeptEntryId` through just before the compaction, then
 * everything after the compaction. Older summarized entries are omitted.
 */
fun buildContextEntries(pathEntries: List<SessionEntry>): List<SessionEntry> {
    var compaction: CompactionEntry? = null
    for (entry in pathEntries) {
        if (entry is CompactionEntry) compaction = entry
    }
    if (compaction == null) return pathEntries

    val compactionIndex = pathEntries.indexOfFirst { it.id == compaction.id }
    if (compactionIndex < 0) return pathEntries

    val contextEntries = ArrayList<SessionEntry>()
    contextEntries.add(compaction!!)
    var foundFirstKept = false
    for (i in 0 until compactionIndex) {
        val entry = pathEntries[i]
        if (entry.id == compaction!!.firstKeptEntryId) foundFirstKept = true
        if (foundFirstKept) contextEntries.add(entry)
    }
    contextEntries.addAll(pathEntries.subList(compactionIndex + 1, pathEntries.size))
    return contextEntries
}

/**
 * The resolved LLM message list over the leaf path. The branch state fold
 * (model, thinking level) stays in
 * [works.resolve.pathfinder.codingagent.core.session.Conversation.effectiveConfiguration],
 * which iterates the same full pre-compaction path.
 */
fun buildSessionContext(pathEntries: List<SessionEntry>): List<Message> =
    buildContextEntries(pathEntries).flatMap(::sessionEntryToContextMessages)
