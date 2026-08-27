package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.agent.compaction.CompactionDetails
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Usage

/**
 * A node in a session's conversation tree, mirroring pi's SessionEntry.
 * Every entry has an [id], an optional [parentId] (null for roots), and a
 * [timestamp] used to order siblings. Later variants (compaction, label
 * entries, ...) can be added alongside [MessageEntry].
 */
sealed class SessionEntry {
    abstract val id: String
    abstract val parentId: String?
    abstract val timestamp: Long
}

/** An entry carrying a chat [message]. */
data class MessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val message: Message,
) : SessionEntry()

/**
 * A compaction cut in the conversation tree, pi's harness CompactionEntry
 * (packages/agent/src/harness/session/types.ts): the summary replacing the
 * compacted history, the retained recent tail, and compaction metadata.
 * Divergence: upstream's `details?: unknown` is typed as
 * [CompactionDetails] (the only producer), and upstream's `seq` is not
 * ported (pathfinder entries carry no shared sequence number).
 */
data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    /** Summary text that replaces the compacted history in future context. */
    val summary: String,
    /** Retained recent messages stored directly on the entry. */
    val retainedTail: List<Message>,
    /** Estimated context tokens before compaction. */
    val tokensBefore: Int,
    /** File-operation details of the compacted history. */
    val details: CompactionDetails? = null,
    /** Usage from the LLM call(s) that generated the summary. */
    val usage: Usage? = null,
) : SessionEntry()
