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

sealed interface AgentEvent {
    data object Initialized : AgentEvent
    data class TextDelta(val delta: String) : AgentEvent
    data object MessageEnd : AgentEvent
    data object AgentEnd : AgentEvent
    data class Error(val message: String) : AgentEvent
}
