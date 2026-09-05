package works.resolve.pathfinder.codingagent.core.session

/**
 * Read-only session summary for listing, ported from pi's buildSessionInfo.
 * `modified` derives from message timestamps, never file mtime, so merely
 * opening a session cannot reorder the list.
 */
data class SessionInfo(
    val id: String,
    /** Header timestamp. */
    val createdAt: Long,
    /** Max user/assistant message timestamp, else the header timestamp. */
    val modified: Long,
    /** Count of message entries (all roles). */
    val messageCount: Int,
    /** Text of the first user message, or the sentinel "(no messages)". */
    val firstMessage: String,
    /** All user+assistant message texts joined with " ". */
    val allMessagesText: String
)
