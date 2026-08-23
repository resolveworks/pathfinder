package com.aletheia.data.sessions

import com.aletheia.ai.core.Message

/**
 * A persisted chat session transcript. Instances are immutable value objects;
 * [SessionStore] hands out defensive copies of all collections.
 */
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<Message>,
) {
    init {
        requireId(id)
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

/** Generic exception for invalid ids and malformed or unreadable session data. */
class SessionDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Session ids are restricted to a flat, filesystem-safe alphabet. */
internal val SESSION_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")

internal fun requireId(id: String): String {
    // Ids shorter than 3 chars are fine as session ids; persistence pads the
    // temp-file prefix so every accepted id can be saved.
    if (!SESSION_ID_REGEX.matches(id)) {
        throw SessionDataException("Invalid session id")
    }
    return id
}
