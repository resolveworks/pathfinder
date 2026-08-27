package works.resolve.distill.logging

import android.util.Log

enum class LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

/**
 * Small structured-logging boundary for the app.
 *
 * Fields must contain operational metadata only. Message text, model responses, and
 * credentials must never be passed to this API.
 */
interface AppLogger {
    fun log(
        level: LogLevel,
        component: String,
        event: String,
        fields: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    )
}

class LogcatLogger(
    private val tag: String = DEFAULT_TAG,
) : AppLogger {

    override fun log(
        level: LogLevel,
        component: String,
        event: String,
        fields: Map<String, String>,
        error: Throwable?,
    ) {
        val message = buildString {
            append("component=")
            append(component.asLogValue())
            append(" event=")
            append(event.asLogValue())
            fields.toSortedMap().forEach { (key, value) ->
                append(' ')
                append(key)
                append('=')
                append(value.asLogValue())
            }
        }
        when (level) {
            LogLevel.Debug -> Log.d(tag, message, error)
            LogLevel.Info -> Log.i(tag, message, error)
            LogLevel.Warn -> Log.w(tag, message, error)
            LogLevel.Error -> Log.e(tag, message, error)
        }
    }

    private fun String.asLogValue(): String {
        val sanitized = replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .take(MAX_VALUE_LENGTH)
        return if (sanitized.any(Char::isWhitespace) || sanitized.isEmpty()) {
            "\"${sanitized.replace("\"", "\\\"")}\""
        } else {
            sanitized
        }
    }

    private companion object {
        const val DEFAULT_TAG = "Distill"
        const val MAX_VALUE_LENGTH = 500
    }
}
