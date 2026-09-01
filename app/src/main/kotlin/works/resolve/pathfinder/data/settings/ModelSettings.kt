package works.resolve.pathfinder.data.settings

/**
 * Model configuration shown in the UI. The API key is intentionally not part
 * of this class; it lives in the credential store.
 */
data class ModelSettings(
    val providerId: String = "",
    val modelId: String = "",
    val activeSessionId: String? = null,
    /** Display-only preference: show the model's reasoning while it thinks. */
    val showThinking: Boolean = false,
    /**
     * The scoped model set for the chat model picker, as `provider/model`
     * refs. Null (never curated) means every model of every configured
     * provider is offered; an explicit set is offered as-is, minus refs
     * whose provider no longer has a credential. A set left effectively
     * empty by credential loss degrades to the uncurated default, and
     * editing can never empty it (the last usable model stays in), so the
     * picker is never empty while a configured model exists.
     */
    val enabledModels: Set<String>? = null,
    /**
     * Last thinking option chosen per model, keyed `provider/model` with a
     * Koog-native [works.resolve.pathfinder.runtime.ThinkingOption.label]
     * value; absent entries use the provider default.
     */
    val thinkingPrefs: Map<String, String> = emptyMap(),
)
