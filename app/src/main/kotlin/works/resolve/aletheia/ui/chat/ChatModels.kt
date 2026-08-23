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

/** Catalog entry for the model picker. */
data class ChatModelOption(
    val id: String,
    val name: String,
)

/**
 * Immutable projection of the chat screen state. Contains only UI-safe data:
 * no API keys (only [hasApiKey]), no provider-request options, and no AI-core
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
    val modelOptions: List<ChatModelOption> = emptyList(),
    val selectedModelId: String? = null,
    val baseUrl: String? = null,
    /** Whether a stored API key exists; the key itself never enters this state. */
    val hasApiKey: Boolean = false,
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
