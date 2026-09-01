package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.ai.core.Message

/**
 * A persisted chat session transcript. Instances are immutable value objects;
 * [SessionStore] hands out defensive copies of all collections. The transcript
 * is a tree of [SessionEntry]s with a designated [leafId] (pi's branching
 * session semantics); [messages] exposes the active root→leaf path for
 * callers that only need the linear transcript.
 */
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val entries: List<SessionEntry>,
    val leafId: String?,
) {
    init {
        requireId(id)
    }

    /** Messages along the active root→leaf path, in order. */
    val messages: List<Message>
        get() = Conversation(entries, leafId).activeMessages()

    /**
     * Returns a copy whose entry tree is a fresh linear chain built from
     * [messages] (leaf = last). Mechanical adaptation for callers that still
     * hold flat transcripts; branch structure is not preserved.
     */
    fun withMessages(messages: List<Message>): Session {
        val conversation = Conversation.fromMessages(messages)
        return copy(entries = conversation.entries, leafId = conversation.leafId)
    }
}

/** Session listing entry; summaries are ordered newest-updated first. */
data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

/** pi's SessionErrorCode (session/types.ts ~382). */
enum class SessionErrorCode {
    NOT_FOUND,
    ALREADY_EXISTS,
    INVALID_ENTRY,
    INVALID_PAYLOAD,
    INVALID_LANE,
    INVALID_QUERY,
    INVALID_FORK_TARGET,
    STORAGE,
}

/**
 * pi's SessionError (session/types.ts): the session layer's single
 * exception type, carrying the typed [code] so callers can react (notably
 * [SessionErrorCode.INVALID_FORK_TARGET] and [SessionErrorCode.INVALID_LANE]).
 */
class SessionError(val code: SessionErrorCode, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Session ids are restricted to a flat, filesystem-safe alphabet. */
internal val SESSION_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")

internal fun requireId(id: String): String {
    // Ids shorter than 3 chars are fine as session ids; persistence pads the
    // temp-file prefix so every accepted id can be saved.
    // Divergence from pi's validateSessionId (jsonl/repo.ts, invalid_payload):
    // Pathfinder keeps its narrower filesystem-safe alphabet and message.
    if (!SESSION_ID_REGEX.matches(id)) {
        throw SessionError(SessionErrorCode.INVALID_PAYLOAD, "Invalid session id")
    }
    return id
}
