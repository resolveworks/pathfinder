package com.aletheia.ui.chat

import com.aletheia.agent.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateReducerTest {

    @Test
    fun prompt_addsUserAndStreamingAssistantMessages() {
        val state = ChatUiState(draft = "  Hello pi  ", isInitializing = false)

        val result = ChatStateReducer.beginPrompt(state, firstMessageId = 7)

        assertEquals(
            listOf(
                ChatMessage(7, ChatRole.User, "Hello pi"),
                ChatMessage(8, ChatRole.Assistant, "", isStreaming = true),
            ),
            result.messages,
        )
        assertEquals("", result.draft)
        assertTrue(result.isStreaming)
        assertFalse(result.canSend)
    }

    @Test
    fun prompt_isIgnoredUntilInitialized() {
        val state = ChatUiState(draft = "Hello")

        assertEquals(state, ChatStateReducer.beginPrompt(state, firstMessageId = 1))
    }

    @Test
    fun textDeltas_appendToStreamingAssistant() {
        val started = ChatStateReducer.beginPrompt(
            ChatUiState(draft = "Hello", isInitializing = false),
            firstMessageId = 1,
        )

        val first = ChatStateReducer.reduce(started, AgentEvent.TextDelta("QuickJS "))
        val second = ChatStateReducer.reduce(first, AgentEvent.TextDelta("works."))

        assertEquals("QuickJS works.", second.messages.last().text)
        assertTrue(second.messages.last().isStreaming)
        assertTrue(second.isStreaming)
    }

    @Test
    fun messageAndAgentEnd_finishResponse() {
        val started = ChatStateReducer.beginPrompt(
            ChatUiState(draft = "Hello", isInitializing = false),
            firstMessageId = 1,
        )

        val messageEnded = ChatStateReducer.reduce(started, AgentEvent.MessageEnd)
        val agentEnded = ChatStateReducer.reduce(messageEnded, AgentEvent.AgentEnd)

        assertFalse(agentEnded.messages.last().isStreaming)
        assertFalse(agentEnded.isStreaming)
    }

    @Test
    fun error_finishesResponseAndIsVisible() {
        val started = ChatStateReducer.beginPrompt(
            ChatUiState(draft = "Hello", isInitializing = false),
            firstMessageId = 1,
        )

        val result = ChatStateReducer.reduce(started, AgentEvent.Error("Provider failed"))

        assertEquals("Provider failed", result.error)
        assertFalse(result.isInitializing)
        assertFalse(result.isStreaming)
        assertFalse(result.messages.last().isStreaming)
    }

    @Test
    fun initialized_enablesSendingAndClearsStartupError() {
        val state = ChatUiState(draft = "Hello", error = "old error")

        val result = ChatStateReducer.reduce(
            state,
            AgentEvent.Initialized(providerId = "faux", modelId = "faux-1"),
        )

        assertFalse(result.isInitializing)
        assertNull(result.error)
        assertTrue(result.canSend)
    }
}
