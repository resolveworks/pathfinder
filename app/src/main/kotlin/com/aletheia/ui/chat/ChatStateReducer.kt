package com.aletheia.ui.chat

import com.aletheia.agent.AgentEvent

/** Pure chat state transitions, kept separate from Android and QuickJS. */
object ChatStateReducer {

    fun beginPrompt(state: ChatUiState, firstMessageId: Long): ChatUiState {
        val text = state.draft.trim()
        if (text.isEmpty() || state.isInitializing || state.isStreaming) return state

        return state.copy(
            messages = state.messages + listOf(
                ChatMessage(id = firstMessageId, role = ChatRole.User, text = text),
                ChatMessage(
                    id = firstMessageId + 1,
                    role = ChatRole.Assistant,
                    text = "",
                    isStreaming = true,
                ),
            ),
            draft = "",
            isStreaming = true,
            error = null,
        )
    }

    fun reduce(state: ChatUiState, event: AgentEvent): ChatUiState = when (event) {
        is AgentEvent.Initialized -> state.copy(isInitializing = false, error = null)
        is AgentEvent.TextDelta -> state.copy(
            messages = state.messages.updateStreamingAssistant { message ->
                message.copy(text = message.text + event.delta)
            },
        )
        AgentEvent.MessageEnd -> state.copy(
            messages = state.messages.finishStreamingAssistant(),
        )
        AgentEvent.AgentEnd -> state.copy(
            messages = state.messages.finishStreamingAssistant(),
            isStreaming = false,
        )
        is AgentEvent.Error -> state.copy(
            messages = state.messages.finishStreamingAssistant(),
            isInitializing = false,
            isStreaming = false,
            error = event.message,
        )
    }

    private fun List<ChatMessage>.finishStreamingAssistant(): List<ChatMessage> =
        updateStreamingAssistant { it.copy(isStreaming = false) }

    private fun List<ChatMessage>.updateStreamingAssistant(
        transform: (ChatMessage) -> ChatMessage,
    ): List<ChatMessage> {
        val index = indexOfLast { it.role == ChatRole.Assistant && it.isStreaming }
        if (index < 0) return this
        return toMutableList().also { it[index] = transform(it[index]) }
    }
}
