package works.resolve.aletheia.ui.chat

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import works.resolve.aletheia.data.sessions.SessionSummary

enum class ChatRole {
    User,
    Assistant,
}

data class ChatMessage(
    /** Stable UI key; unique even when timestamps collide. */
    val id: String,
    val role: ChatRole,
    val text: String,
    /** User-facing failure text for error/aborted assistant messages. */
    val error: String? = null,
)

/** Navigation 3 destination key: the conversation surface. */
@Serializable
data object ChatNavKey : NavKey

/** Navigation 3 destination key: the configuration form. */
@Serializable
data object SettingsNavKey : NavKey

/** Navigation 3 destination key: the model configuration form. */
@Serializable
data object ModelSettingsNavKey : NavKey

/** Navigation 3 destination key: the provider credential list (pi's /login). */
@Serializable
data object ProvidersNavKey : NavKey

/** Navigation 3 destination key: the credential form of one provider. */
@Serializable
data class ProviderAuthNavKey(val providerId: String) : NavKey

/** Outcome of initial load of settings, credentials, and sessions. */
enum class ChatStatus {
    /** Initial load in progress. */
    Loading,
    /** No valid provider/model/key configuration; the settings form is forced. */
    NeedsConfiguration,
    /** Configured and ready to chat. */
    Ready,
    /** Initialization failed with a surfaced error. */
    Failed,
}

/** Row of the providers screen: one per catalog provider, with live auth status. */
data class ProviderOption(
    val id: String,
    val name: String,
    /** True iff a credential with a non-blank key is stored for this provider. */
    val configured: Boolean,
)

/** Row of the model picker: one per model of a configured provider. */
data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val name: String,
    /** The provider's catalog base URL; placeholder for the override field. */
    val defaultBaseUrl: String = "",
)

/** UI-safe projection of a catalog auth prompt: env slot, message, secret flag. */
data class ProviderAuthPrompt(
    val envKey: String,
    val message: String,
    val secret: Boolean,
)

/** The committed provider+model selection (from settings), projected for display. */
data class SelectedModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    /** Normalized base-URL override from settings, or null when none. */
    val baseUrlOverride: String?,
    /** The provider's catalog base URL (placeholder for the override field). */
    val defaultBaseUrl: String,
)

/**
 * Immutable projection of the chat screen state. Contains only UI-safe data:
 * no API keys (only per-provider [ProviderOption.configured] flags), no provider-request options, and no AI-core
 * message objects.
 *
 * Navigation is signaled from this state rather than commanded: an
 * unconfigured app sets [startKey] to [SettingsNavKey], which the UI layer
 * honors by rebuilding its Nav3 back stack to exactly that root (a dead end:
 * back cannot leave it). Every success that should return the user to the
 * chat (adopting a session, saving configuration) bumps the monotonic
 * [navigationEpoch], which the UI layer reads as a reset-to-[startKey] signal,
 * atomically with the rest of the state. The invariant `NeedsConfiguration
 * implies startKey == SettingsNavKey` holds by construction.
 */
data class ChatUiState(
    val status: ChatStatus = ChatStatus.Loading,
    /** Root of the Nav3 back stack; the stack must contain exactly this after a reset. */
    val startKey: NavKey = ChatNavKey,
    /** Monotonic reset signal: any change tells the UI to rebuild the stack to [startKey]. */
    val navigationEpoch: Long = 0,
    /** Every catalog provider with live auth status; name-sorted (providers screen). */
    val providerOptions: List<ProviderOption> = emptyList(),
    /** Models of configured providers only; provider-name-then-model-name sorted. */
    val modelOptions: List<ModelOption> = emptyList(),
    /** The committed selection projected from settings, or null when unset/invalid. */
    val selectedModel: SelectedModel? = null,
    /** True iff the selection is catalog-valid AND a key exists for its provider. */
    val configured: Boolean = false,
    val activeSessionId: String? = null,
    val sessionSummaries: List<SessionSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val streamingMessage: ChatMessage? = null,
    val draft: String = "",
    val isStreaming: Boolean = false,
    val canSend: Boolean = false,
    /** Display-only flag: whether to show the model's reasoning (never affects the agent). */
    val showThinking: Boolean = false,
    val error: String? = null,
)
