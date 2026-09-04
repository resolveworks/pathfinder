package works.resolve.pathfinder.codingagent.core

/**
 * Auto-retry settings for agent runs.
 *
 * Divergence: pi's `provider` sub-object is omitted — provider-level request
 * retry is handled separately by [works.resolve.pathfinder.ai.utils.ProviderRetry].
 */
data class RetrySettings(
    val enabled: Boolean = true,
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 2000
)
