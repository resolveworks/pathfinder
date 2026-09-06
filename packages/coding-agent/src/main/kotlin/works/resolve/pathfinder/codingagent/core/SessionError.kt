package works.resolve.pathfinder.codingagent.core

enum class SessionErrorCode {
    NOT_FOUND,
    INVALID_ENTRY,
    INVALID_ID,
    STORAGE,
    AUTH
}

/** The session layer's single exception type, carrying the typed [code]. */
class SessionError(val code: SessionErrorCode, message: String, cause: Throwable? = null) :
    Exception(message, cause)
