package works.resolve.pathfinder.codingagent.core.session

import works.resolve.pathfinder.ai.Message

/**
 * Transcript as a tree of [SessionEntry]s with a designated [leafId] —
 * pi's branching session semantics. [SessionStore] hands out defensive
 * copies of all collections.
 */
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val entries: List<SessionEntry>,
    val leafId: String?
) {
    init {
        requireId(id)
    }

    /** Messages along the active root→leaf path, in order. */
    val messages: List<Message>
        get() = Conversation(entries, leafId).activeMessages()

    /** Copy whose entry tree is a fresh linear chain built from [messages] (leaf = last); branch structure is not preserved. */
    fun withMessages(messages: List<Message>): Session {
        val conversation = Conversation.fromMessages(messages)
        return copy(entries = conversation.entries, leafId = conversation.leafId)
    }
}

data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

enum class SessionErrorCode {
    NOT_FOUND,
    ALREADY_EXISTS,
    INVALID_ENTRY,
    INVALID_PAYLOAD,
    INVALID_LANE,
    INVALID_QUERY,
    INVALID_FORK_TARGET,
    STORAGE
}

/**
 * The session layer's single exception type, carrying the typed [code] so
 * callers can react (notably [SessionErrorCode.INVALID_FORK_TARGET] and
 * [SessionErrorCode.INVALID_LANE]).
 */
class SessionError(val code: SessionErrorCode, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Session ids are restricted to a flat, filesystem-safe alphabet. */
internal val SESSION_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")

internal fun requireId(id: String): String {
    // Divergence from pi's validateSessionId: pi allows dots and anchors
    // start/end on alphanumerics with no length cap; Pathfinder keeps a flat
    // filesystem-safe alphabet capped at 64.
    if (!SESSION_ID_REGEX.matches(id)) {
        throw SessionError(SessionErrorCode.INVALID_PAYLOAD, "Invalid session id")
    }
    return id
}
