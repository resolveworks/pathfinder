package works.resolve.pathfinder.data.settings

/**
 * Auto-retry settings for agent runs, ported from pi's `RetrySettings`
 * (coding-agent settings-manager.ts: `enabled` default true, `maxRetries`
 * default 3, `baseDelayMs` default 2000 → backoff 2s/4s/8s).
 *
 * Pi's `provider` sub-object is deliberately not ported: provider-level
 * request retry already exists separately in this port
 * (see `works.resolve.pathfinder.ai.utils.ProviderRetry`).
 */
data class RetrySettings(
    val enabled: Boolean = true,
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 2000,
)
