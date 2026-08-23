package works.resolve.aletheia.data.settings

/**
 * Model configuration shown in the UI. The API key is intentionally not part
 * of this class; it lives in the credential store.
 */
data class ModelSettings(
    val providerId: String = "",
    val modelId: String = "",
    /** Optional base URL override for OpenAI-compatible endpoints. */
    val baseUrl: String? = null,
    val activeSessionId: String? = null,
)
