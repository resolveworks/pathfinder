package com.aletheia.ui.chat

enum class ChatRole {
    User,
    Assistant,
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
)
