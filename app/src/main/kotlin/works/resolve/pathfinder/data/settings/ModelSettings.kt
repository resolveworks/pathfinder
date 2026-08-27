package works.resolve.pathfinder.data.settings

import works.resolve.pathfinder.agent.compaction.CompactionSettings
import works.resolve.pathfinder.agent.compaction.DEFAULT_COMPACTION_SETTINGS

/**
 * Model configuration shown in the UI. The API key is intentionally not part
 * of this class; it lives in the credential store.
 */
data class ModelSettings(
    val providerId: String = "",
    val modelId: String = "",
    val activeSessionId: String? = null,
    /**
     * Display-only preference: show the model's reasoning while it thinks.
     * The agent layer ignores this for now; nothing renders thinking yet.
     */
    val showThinking: Boolean = false,
    /** Auto-retry of failed agent runs (pi's settings.retry; see [RetrySettings]). */
    val retry: RetrySettings = RetrySettings(),
    /** Automatic compaction thresholds (pi's settings compaction object). */
    val compaction: CompactionSettings = DEFAULT_COMPACTION_SETTINGS,
)
