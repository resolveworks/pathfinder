package com.aletheia.agent

sealed interface AgentEvent {
    val type: String

    data class Initialized(
        val providerId: String,
        val modelId: String,
    ) : AgentEvent {
        override val type = "initialized"
    }

    data class TextDelta(val delta: String) : AgentEvent {
        override val type = "text_delta"
    }

    data object MessageEnd : AgentEvent {
        override val type = "message_end"
    }

    data object AgentEnd : AgentEvent {
        override val type = "agent_end"
    }

    data class Error(val message: String) : AgentEvent {
        override val type = "error"
    }
}
