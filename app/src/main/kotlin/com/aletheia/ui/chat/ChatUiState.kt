package com.aletheia.ui.chat

enum class ChatRole {
    User,
    Assistant,
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val isStreaming: Boolean = false,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val isInitializing: Boolean = true,
    val isStreaming: Boolean = false,
    val error: String? = null,
) {
    val canSend: Boolean
        get() = draft.isNotBlank() && !isInitializing && !isStreaming
}
