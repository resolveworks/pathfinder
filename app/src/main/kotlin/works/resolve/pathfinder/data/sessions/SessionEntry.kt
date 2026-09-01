package works.resolve.pathfinder.data.sessions

import ai.koog.prompt.message.Message

/**
 * A node in a session's conversation tree, mirroring pi's SessionEntry.
 * Every entry has an [id], an optional [parentId] (null for roots), and a
 * [timestamp] used to order siblings.
 */
sealed class SessionEntry {
    abstract val id: String
    abstract val parentId: String?
    abstract val timestamp: Long
}

/**
 * An entry carrying a Koog chat [Message]. The session tree stores Koog
 * history directly; no parallel message model exists.
 */
data class MessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val message: Message,
) : SessionEntry()

/**
 * A model switch recorded as conversation state (pi's ModelChangeEntry):
 * the branch's active model is the last such entry on the root→leaf path,
 * so the transcript is self-describing and restore is a fold, not a guess.
 */
data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val providerId: String,
    val modelId: String,
) : SessionEntry()
