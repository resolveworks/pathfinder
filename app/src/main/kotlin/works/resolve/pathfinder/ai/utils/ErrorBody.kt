package works.resolve.pathfinder.ai.utils

import works.resolve.pathfinder.ai.transport.ProviderHttpException

/**
 * Shared provider-error normalization and formatting, ported from pi's
 * `packages/ai/src/utils/error-body.ts`.
 *
 * Pi's `normalizeProviderError` has two halves: (1) probing provider-SDK error
 * field shapes (`statusCode`/`status`/`error`/`$metadata`/`$response`) to
 * extract status and body, and (2) composing the display string via
 * `formatProviderError`. Half (1) is obsolete in this port: there are no
 * provider SDKs — [ProviderHttpException] from the OkHttp transport already
 * carries the HTTP status and the raw (bounded) body, so the transport IS the
 * normalizer and [normalizeProviderError] only trims/caps the body. Pi's
 * `safeJsonStringify` fallback for non-`Error` throws is likewise moot here:
 * Kotlin throwables carry messages, and non-HTTP exceptions keep the port's
 * existing `error.message ?: simpleName` handling.
 */
const val MAX_PROVIDER_ERROR_BODY_CHARS = 4000

/** Port of pi's `NormalizedProviderError`. */
data class NormalizedProviderError(
    /** HTTP status code, when one could be extracted from the error. */
    val status: Int?,
    /** Raw HTTP body reason, already trimmed and truncated to the cap. */
    val body: String?,
    /** The exception's message. */
    val message: String,
    /** True when [message] already contains the body (no separate body to add). */
    val messageCarriesBody: Boolean,
)

/**
 * Normalize an HTTP transport error. Mirrors pi's `extractBody`: the body is
 * trimmed before truncating, and an empty/blank body yields no body at all so
 * it does not surface as an empty segment. `messageCarriesBody` is always
 * false here (pi's true case is the Anthropic/`@google/genai` SDK happy path
 * where the SDK folded the body into `error.message`; the raw transport body
 * is the port's stand-in).
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
        messageCarriesBody = false,
    )
}

/**
 * Compose a display string from a normalized error; pi's `formatProviderError`
 * exactly. When the message already carries the body or no body/status was
 * extracted, the message is returned (with `"<prefix> (<status>): "` when a
 * prefix and status exist). Otherwise the status and body are surfaced:
 *
 * - no prefix: `"<status>: <body>"`
 * - prefix:    `"<prefix> (<status>): <body>"`
 */
fun formatProviderError(norm: NormalizedProviderError, prefix: String? = null): String {
    if (norm.messageCarriesBody || norm.status == null || norm.body == null) {
        return if (prefix != null && norm.status != null) {
            "${prefix} (${norm.status}): ${norm.message}"
        } else {
            norm.message
        }
    }
    return if (prefix != null) "${prefix} (${norm.status}): ${norm.body}" else "${norm.status}: ${norm.body}"
}

/** Port of pi's `truncateErrorText`: cap + `"... [truncated N chars]"` suffix. */
fun truncateErrorText(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "... [truncated ${text.length - maxChars} chars]"
}
