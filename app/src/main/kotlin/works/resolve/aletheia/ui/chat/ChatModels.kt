package works.resolve.aletheia.ui.chat

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

/** Lifecycle of the chat surface. */
enum class ChatStatus {
    /** Initial load of settings, credentials, and sessions in progress. */
    Loading,
    /** No valid provider/model/key configuration; the setup UI should show. */
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
 */
data class ChatUiState(
    val status: ChatStatus = ChatStatus.Loading,
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
    val error: String? = null,
)
