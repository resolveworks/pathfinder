package works.resolve.pathfinder.data.settings

import works.resolve.pathfinder.agent.compaction.CompactionSettings
import works.resolve.pathfinder.agent.compaction.DEFAULT_COMPACTION_SETTINGS
import works.resolve.pathfinder.ai.core.ModelThinkingLevel

/** Model configuration; the API key is intentionally excluded — it lives in the credential store. */
data class ModelSettings(
    val providerId: String = "",
    val modelId: String = "",
    val activeSessionId: String? = null,
    val showThinking: Boolean = false,
    /**
     * Applied to sessions without a recorded branch level and re-applied on
     * model switches; null = unset, falling back to "medium" at use sites,
     * as in pi.
     */
    val defaultThinkingLevel: ModelThinkingLevel? = null,
    val retry: RetrySettings = RetrySettings(),
    /**
     * Ordered canonical "provider/modelId" references defining the usable
     * model scope. Null = no configured scope (every available model usable);
     * an empty list is preserved as written but behaves as no scope
     * downstream, as in pi. Order is significant (model cycling).
     */
    val enabledModels: List<String>? = null,
    val compaction: CompactionSettings = DEFAULT_COMPACTION_SETTINGS,
)
