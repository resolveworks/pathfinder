package com.aletheia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aletheia.agent.AgentClient
import com.aletheia.agent.AgentConfig
import com.aletheia.agent.AgentEvent
import com.aletheia.logging.AppLogger
import com.aletheia.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ChatViewModel(
    private val agent: AgentClient,
    private val config: AgentConfig,
    private val logger: AppLogger,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var nextMessageId = 1L
    private var promptJob: Job? = null

    init {
        logger.log(LogLevel.Info, COMPONENT, "created")
        viewModelScope.launch {
            agent.events.collect { event ->
                logger.log(LogLevel.Debug, COMPONENT, "agent_event", mapOf("type" to event.type))
                mutableState.update { ChatStateReducer.reduce(it, event) }
            }
        }
        viewModelScope.launch {
            try {
                agent.start(config)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleFailure("start", "Could not start the agent runtime", error)
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
        val firstMessageId = nextMessageId
        nextMessageId += 2
        mutableState.value = ChatStateReducer.beginPrompt(current, firstMessageId)
        logger.log(
            LogLevel.Info,
            COMPONENT,
            "prompt_submitted",
            mapOf(
                "messageId" to firstMessageId.toString(),
                "textLength" to prompt.length.toString(),
            ),
        )

        promptJob = viewModelScope.launch {
            try {
                withTimeout(PROMPT_TIMEOUT_MS) {
                    agent.prompt(prompt)
                }
            } catch (error: TimeoutCancellationException) {
                handleFailure(
                    operation = "prompt_timeout",
                    fallbackMessage = "The agent did not respond in time",
                    error = error,
                    exposeErrorMessage = false,
                )
                abortAfterInterruptedPrompt()
            } catch (error: CancellationException) {
                logger.log(LogLevel.Info, COMPONENT, "prompt_cancelled")
                throw error
            } catch (error: Exception) {
                handleFailure("prompt", "The prompt failed", error)
            } finally {
                promptJob = null
            }
        }
    }

    fun stop() {
        if (!mutableState.value.isStreaming) return
        logger.log(LogLevel.Info, COMPONENT, "stop_requested")

        // quickjs-kt serializes evaluations, so a second abort evaluation cannot overtake a
        // running prompt. Cancelling first invokes its native interrupt; abort then cleans up
        // pi's active run once the evaluation mutex is available.
        promptJob?.cancel()
        promptJob = null
        mutableState.update { ChatStateReducer.reduce(it, AgentEvent.AgentEnd) }
        abortAfterInterruptedPrompt()
    }

    private fun abortAfterInterruptedPrompt() {
        viewModelScope.launch {
            try {
                agent.abort()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.log(LogLevel.Warn, COMPONENT, "abort_failed", error = error)
            }
        }
    }

    private fun handleFailure(
        operation: String,
        fallbackMessage: String,
        error: Exception,
        exposeErrorMessage: Boolean = true,
    ) {
        logger.log(LogLevel.Error, COMPONENT, "operation_failed", mapOf("operation" to operation), error)
        val message = if (exposeErrorMessage) {
            error.message?.takeIf(String::isNotBlank) ?: fallbackMessage
        } else {
            fallbackMessage
        }
        mutableState.update { ChatStateReducer.reduce(it, AgentEvent.Error(message)) }
    }

    override fun onCleared() {
        agent.close()
        logger.log(LogLevel.Info, COMPONENT, "cleared")
    }

    companion object {
        private const val COMPONENT = "ChatViewModel"
        private const val PROMPT_TIMEOUT_MS = 120_000L

        fun factory(
            agent: AgentClient,
            config: AgentConfig,
            logger: AppLogger,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ChatViewModel::class.java))
                return ChatViewModel(agent, config, logger) as T
            }
        }
    }
}
