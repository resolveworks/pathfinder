package works.resolve.distill.data.settings

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
)
