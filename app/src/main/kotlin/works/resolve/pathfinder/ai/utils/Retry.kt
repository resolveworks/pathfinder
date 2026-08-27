package works.resolve.pathfinder.ai.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.StopReason

/**
 * Ported from pi `packages/ai/src/utils/retry.ts`
 * (`buildProviderErrorPattern`, `NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN`,
 * `RETRYABLE_PROVIDER_ERROR_PATTERN`, `RetryPolicy`, `RetryCallbacks`,
 * `retryAssistantCall`, `isRetryableAssistantError`).
 */

/** Upstream `buildProviderErrorPattern`: one case-insensitive regex joining fragments with `|`. */
private fun buildProviderErrorPattern(patterns: List<String>): Regex =
    Regex(patterns.joinToString("|"), setOf(RegexOption.IGNORE_CASE))

private val NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN = buildProviderErrorPattern(
    listOf(
        // OpenCode Go/free-tier limits returned as 429 JSON error types by OpenCode's
        // Zen API. These are subscription/account limits, not transient throttles.
        "GoUsageLimitError",
        "FreeUsageLimitError",

        // OpenCode Go subscription-limit text asks users to enable available-balance
        // usage after rolling/weekly/monthly limits are reached.
        "Monthly usage limit reached",
        "available balance",

        // Generic quota/budget/billing exhaustion. `insufficient_quota` is OpenAI's
        // quota/billing error code; the other strings cover common gateway wording.
        "insufficient_quota",
        "out of budget",
        "quota exceeded",
        "billing",
    ),
)

private val RETRYABLE_PROVIDER_ERROR_PATTERN = buildProviderErrorPattern(
    listOf(
        // Generic provider load, HTTP status, and server-side transient failures.
        "overloaded",
        "rate.?limit",
        "too many requests",
        "429",
        "500",
        "502",
        "503",
        "504",
        "524",
        "service.?unavailable",
        "server.?error",
        "internal.?error",

        // Wrapper/provider text for transient upstream failures, including OpenRouter
        // "Provider returned error" responses (#2264).
        "provider.?returned.?error",
        "exceeded request buffer limit while retrying upstream",

        // Network, proxy, and fetch transport failures. This includes OpenAI Codex
        // raw-fetch failures such as "upstream connect", "connection refused", and
        // "reset before headers" (#733), plus OpenRouter connection drops (#3317).
        "network.?error",
        "connection.?error",
        "connection.?refused",
        "connection.?lost",
        "other side closed",
        "fetch failed",
        "getaddrinfo",
        "ENOTFOUND",
        "EAI_AGAIN",
        "upstream.?connect",
        "reset before headers",
        "socket hang up",
        "socket connection was closed",
        "timed? out",
        "timeout",
        "terminated",

        // WebSocket transports can report close/error text instead of HTTP/fetch text.
        "websocket.?closed",
        "websocket.?error",

        // Premature stream endings from SDKs and transports. Anthropic can throw
        // "stream ended without ..." and "Anthropic stream ended before message_stop"
        // (#4433); Bedrock/Smithy can throw an HTTP/2 no-response error (#3594).
        "ended without",
        "stream ended before message_stop",
        "stream ended before a terminal response event",
        "http2 request did not get a response",

        // Provider-requested retry delay cap failures should flow through the outer
        // retry policy so callers can surface/abort the backoff (#1123).
        "retry delay",

        // Explicit retry guidance emitted mid-stream by OpenAI Responses and Bedrock
        // stream exceptions (#6019).
        "you can retry your request",
        "try your request again",
        "please retry your request",

        // gRPC based providers (e.g. NVIDIA NIM)
        "ResourceExhausted",
    ),
)

/**
 * Retry policy: bounded attempts with exponential backoff (`baseDelayMs * 2^(attempt-1)`).
 * Matches `settings.retry` (`enabled`, `maxRetries`, `baseDelayMs`) in coding-agent; kept
 * here so the classifier and the policy-driven retry loop live together and stay reusable
 * by other callers. Upstream `RetryPolicy` (retry.ts).
 */
data class RetryPolicy(
    val enabled: Boolean,
    /** Max retry attempts (0 = no retries). The initial call never counts as a retry. */
    val maxRetries: Int,
    /** Base delay in ms. Per-attempt delay is `baseDelayMs * 2^(attempt-1)` before jitter. */
    val baseDelayMs: Long,
)

/**
 * Optional callbacks emitted by [Retry.retryAssistantCall] around each retry.
 * Upstream `RetryCallbacks` (retry.ts) is an interface with optional methods; the
 * repo rule maps that to a data class of nullable function types.
 */
data class RetryCallbacks(
    /** Emitted before the backoff sleep of each retry attempt (1-indexed). */
    val onRetryScheduled: (suspend (attempt: Int, maxAttempts: Int, delayMs: Long, errorMessage: String) -> Unit)? = null,
    /** Emitted after the backoff sleep, immediately before the retried call starts. */
    val onRetryAttemptStart: (suspend () -> Unit)? = null,
    /** Emitted once when the loop ends: success if a later call completed normally. */
    val onRetryFinished: (suspend (success: Boolean, attempt: Int, finalError: String?) -> Unit)? = null,
)

/**
 * Upstream `retryAssistantCall` (retry.ts). Sleep is injectable like `ProviderRetry`
 * so tests never wait.
 *
 * Divergence: upstream takes an `AbortSignal` and its `sleep` rejects on abort,
 * returning `{...response, stopReason: "aborted", errorMessage: undefined}`. This
 * codebase expresses abort as plain coroutine cancellation (see `agent/AgentLoop.kt`,
 * which surfaces aborts as ABORTED AssistantMessages, not exceptions), so the abort
 * path maps to catching [CancellationException] around the backoff sleep; the final
 * callback still fires via [NonCancellable] and the aborted `AssistantMessage` is
 * returned normally.
 */
class Retry(
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    /**
     * Run a single assistant-producing call with bounded retry on transient errors.
     *
     * Behavior:
     * - A successful response is returned immediately. Aborts are terminal and never
     *   retried, but reported as unsuccessful if they happen after a retry was scheduled.
     *   Aborts during the backoff sleep are normalized to an aborted `AssistantMessage`
     *   too, so callers do not need to care when cancellation happened.
     * - A non-retryable error (per [isRetryableAssistantError], including quota/billing
     *   exhaustion) is returned immediately so deterministic errors fail fast.
     * - Otherwise retries up to `maxRetries` times with exponential backoff, emitting
     *   `onRetryScheduled` before each sleep, `onRetryAttemptStart` after each sleep
     *   before the retried call starts, and `onRetryFinished` once at the end (whether
     *   the loop ends in success, exhausted retries, or an aborted backoff).
     *
     * When `policy` is null or disabled, the first response is returned unchanged
     * (equivalent to calling `produce()` directly).
     */
    suspend fun retryAssistantCall(
        produce: suspend () -> AssistantMessage,
        policy: RetryPolicy?,
        callbacks: RetryCallbacks? = null,
    ): AssistantMessage {
        val maxAttempts = if (policy?.enabled == true) policy.maxRetries else 0

        var attempt = 0
        var lastRetry: Pair<Int, String>? = null
        while (true) {
            val response = produce()

            // Abort: terminal but not successful. Never retry an aborted message.
            if (response.stopReason == StopReason.ABORTED) {
                if (lastRetry != null) callbacks?.onRetryFinished?.invoke(false, lastRetry.first, null)
                return response
            }

            // Success: non-error, non-abort responses return as-is.
            if (response.stopReason != StopReason.ERROR) {
                if (lastRetry != null) callbacks?.onRetryFinished?.invoke(true, lastRetry.first, null)
                return response
            }

            // Non-retryable, or budget exhausted: return the final error message.
            if (attempt >= maxAttempts || !isRetryableAssistantError(response)) {
                if (lastRetry != null) callbacks?.onRetryFinished?.invoke(false, lastRetry.first, response.errorMessage)
                return response
            }

            attempt++
            val errorMessage = response.errorMessage ?: "Unknown error"
            lastRetry = attempt to errorMessage
            val delayMs = policy!!.baseDelayMs shl (attempt - 1)
            callbacks?.onRetryScheduled?.invoke(attempt, maxAttempts, delayMs, errorMessage)

            // Normalize aborts during retry backoff to the same AssistantMessage shape as
            // provider stream aborts, so callers do not need to care when cancellation
            // happened. Upstream's RetrySleepAbortError maps to coroutine cancellation.
            try {
                sleep(delayMs)
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    callbacks?.onRetryFinished?.invoke(false, attempt, errorMessage)
                }
                return response.copy(stopReason = StopReason.ABORTED, errorMessage = null)
            }
            callbacks?.onRetryAttemptStart?.invoke()
        }
    }

    /**
     * Classifies whether a failed assistant message looks like a transient provider
     * or transport error, so callers can decide if the last assistant turn should be
     * restarted.
     *
     * This does not implement retry policy. Callers should first handle context
     * overflow separately, then apply their own retry budget, backoff, and reporting
     * before restarting the assistant turn.
     */
    fun isRetryableAssistantError(message: AssistantMessage): Boolean {
        if (message.stopReason != StopReason.ERROR) return false
        val errorMessage = message.errorMessage ?: return false
        if (NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN.containsMatchIn(errorMessage)) return false
        return RETRYABLE_PROVIDER_ERROR_PATTERN.containsMatchIn(errorMessage)
    }
}
