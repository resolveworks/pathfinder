package works.resolve.pathfinder.codingagent.core.session

enum class SessionErrorCode {
    NOT_FOUND,
    INVALID_ENTRY,
    INVALID_ID,
    STORAGE
}

/** The session layer's single exception type, carrying the typed [code]. */
class SessionError(val code: SessionErrorCode, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** pi's assertValidSessionId: alphanumeric start/end, '-._' allowed inside. */
private val SESSION_ID_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")

private const val INVALID_SESSION_ID_MESSAGE =
    "Session id must be non-empty, contain only alphanumeric characters, '-', '_', and '.', " +
        "and start and end with an alphanumeric character"

fun assertValidSessionId(id: String) {
    if (!SESSION_ID_REGEX.matches(id)) {
        throw SessionError(SessionErrorCode.INVALID_ID, INVALID_SESSION_ID_MESSAGE)
    }
}
