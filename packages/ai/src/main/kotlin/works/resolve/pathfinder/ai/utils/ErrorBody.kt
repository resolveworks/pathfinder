package works.resolve.pathfinder.ai.utils

import works.resolve.pathfinder.ai.transport.ProviderHttpException

const val MAX_PROVIDER_ERROR_BODY_CHARS = 4000

data class NormalizedProviderError(
    val status: Int?,
    /** Raw HTTP body reason, already trimmed and truncated to the cap. */
    val body: String?,
    val message: String,
    /** True when [message] already contains the body (no separate body to add). */
    val messageCarriesBody: Boolean
)

/**
 * Pi's `normalizeProviderError` probes provider-SDK error objects for status
 * and body; here the transport's [ProviderHttpException] already carries both,
 * so only the body is normalized. Mirrors pi's `extractBody`: trim before
 * truncating, and a blank body yields none at all so it never surfaces as an
 * empty segment. `messageCarriesBody` is always false — pi's true case is the
 * Anthropic/`@google/genai` SDKs folding the body into `error.message`, which
 * a raw transport body cannot.
 */
fun normalizeProviderError(error: ProviderHttpException): NormalizedProviderError {
    val body = error.body
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.let { truncateErrorText(it, MAX_PROVIDER_ERROR_BODY_CHARS) }
    return NormalizedProviderError(
        status = error.status,
        body = body,
        message = error.message ?: error::class.simpleName ?: "Unknown error",
        messageCarriesBody = false
    )
}

/**
 * Compose a display string from a normalized error. When the message already
 * carries the body or no body/status was extracted, the message is returned
 * (with `"<prefix> (<status>): "` when a prefix and status exist). Otherwise
 * the status and body are surfaced:
 *
 * - no prefix: `"<status>: <body>"`
 * - prefix:    `"<prefix> (<status>): <body>"`
 */
fun formatProviderError(norm: NormalizedProviderError, prefix: String? = null): String {
    if (norm.messageCarriesBody || norm.status == null || norm.body == null) {
        return if (prefix != null && norm.status != null) {
            "$prefix (${norm.status}): ${norm.message}"
        } else {
            norm.message
        }
    }
    return if (prefix !=
        null
    ) {
        "$prefix (${norm.status}): ${norm.body}"
    } else {
        "${norm.status}: ${norm.body}"
    }
}

fun truncateErrorText(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "... [truncated ${text.length - maxChars} chars]"
}
