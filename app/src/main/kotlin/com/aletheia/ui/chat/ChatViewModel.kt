package com.aletheia.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aletheia.agent.AgentRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class ChatViewModel(
    private val runtime: AgentRuntime,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var nextMessageId = 1L

    init {
        // Register the event collector before initialization can emit its first event.
        viewModelScope.launch {
            runtime.events.collect { eventJson ->
                try {
                    AgentEventParser.parse(eventJson)?.let { event ->
                        mutableState.update { ChatStateReducer.reduce(it, event) }
                    }
                } catch (error: Exception) {
                    handleFailure("Invalid agent event: ${error.message ?: "unknown error"}")
                }
            }
        }
        viewModelScope.launch {
            try {
                runtime.start()
                runtime.command(
                    JSONObject()
                        .put("type", "initialize")
                        .put("providerId", "faux")
                        .put("modelId", "faux-1")
                        .toString(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleFailure(error.message ?: "Could not start the agent runtime")
            }
        }
    }

    fun onDraftChange(draft: String) {
        mutableState.update { it.copy(draft = draft) }
    }

    fun send() {
        val current = mutableState.value
        if (!current.canSend) return
        val prompt = current.draft.trim()
        val updated = ChatStateReducer.beginPrompt(current, nextMessageId)
        nextMessageId += 2
        mutableState.value = updated

        viewModelScope.launch {
            try {
                runtime.command(
                    JSONObject()
                        .put("type", "prompt")
                        .put("text", prompt)
                        .toString(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleFailure(error.message ?: "The prompt failed")
            }
        }
    }

    fun stop() {
        if (!mutableState.value.isStreaming) return
        viewModelScope.launch {
            try {
                runtime.command(JSONObject().put("type", "abort").toString())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleFailure(error.message ?: "Could not stop the response")
            }
        }
    }

    private fun handleFailure(message: String) {
        mutableState.update { ChatStateReducer.reduce(it, AgentEvent.Error(message)) }
    }

    override fun onCleared() {
        runtime.close()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ChatViewModel::class.java))
                    return ChatViewModel(AgentRuntime(context.applicationContext)) as T
                }
            }
    }
}
