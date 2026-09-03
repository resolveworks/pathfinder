package works.resolve.pathfinder.ai.utils

import works.resolve.pathfinder.ai.core.AssistantMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Structured shape of a thrown value, carried by [AssistantMessageDiagnostic.error]. */
data class DiagnosticErrorInfo(
    val name: String? = null,
    val message: String,
    val stack: String? = null,
    /**
     * pi keeps Node's errno-style `string | number` error code here. Kotlin
     * throwables carry no such field, so this stays null until a pathfinder
     * exception type exposes one; the opaque-element type keeps the union
     * representable for future producers.
     */
    val code: JsonElement? = null,
)

/** Redacted provider/runtime diagnostic attached to an [AssistantMessage]. */
data class AssistantMessageDiagnostic(
    val type: String,
    val timestamp: Long,
    val error: DiagnosticErrorInfo? = null,
    val details: JsonObject? = null,
)

fun formatThrownValue(value: Any?): String = when (value) {
    is Throwable -> value.message ?: value::class.simpleName ?: "Throwable"
    is String -> value
    else -> value.toString()
}

fun extractDiagnosticError(error: Any?): DiagnosticErrorInfo {
    if (error !is Throwable) {
        return DiagnosticErrorInfo(name = "ThrownValue", message = formatThrownValue(error))
    }
    return DiagnosticErrorInfo(
        name = error::class.simpleName,
        message = error.message ?: error::class.simpleName ?: "Throwable",
        stack = error.stackTraceToString(),
    )
}

fun createAssistantMessageDiagnostic(
    type: String,
    error: Any?,
    details: JsonObject? = null,
): AssistantMessageDiagnostic = AssistantMessageDiagnostic(
    type = type,
    // pi stamps Date.now() here; reading wall time is this helper's job.
    timestamp = System.currentTimeMillis(),
    error = extractDiagnosticError(error),
    details = details,
)

/**
 * pi appends to the mutable `diagnostics` array in place; Kotlin's immutable
 * data class returns the updated message instead.
 */
fun appendAssistantMessageDiagnostic(
    message: AssistantMessage,
    diagnostic: AssistantMessageDiagnostic,
): AssistantMessage = message.copy(diagnostics = message.diagnostics + diagnostic)
