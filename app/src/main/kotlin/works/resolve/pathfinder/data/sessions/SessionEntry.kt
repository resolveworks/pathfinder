package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.ai.core.Message

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
