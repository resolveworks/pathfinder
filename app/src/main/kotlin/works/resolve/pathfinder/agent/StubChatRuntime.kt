package works.resolve.pathfinder.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Temporary [ChatRuntime] stand-in: sessions exist and own their
 * conversation, but a prompt immediately surfaces a user-safe error instead
 * of contacting any provider. Replaced by the Koog-backed runtime in the
 * next change; delete this file then.
 */
class StubChatRuntime : ChatRuntime {

    override fun createSession(
        settings: ModelSettings,
        sessionId: String,
        conversation: Conversation,
    ): ChatRuntimeSession = StubSession(Conversation(conversation.entries, conversation.leafId))

    private class StubSession(
        override var conversation: Conversation,
    ) : ChatRuntimeSession {

        private val _state = MutableStateFlow(ChatRuntimeState())
        override val state: StateFlow<ChatRuntimeState> = _state.asStateFlow()

        override fun prompt(text: String) {
            check(!state.value.isStreaming) { "A response is already streaming" }
            _state.value = _state.value.copy(error = RUNTIME_NOT_CONNECTED)
        }

        override fun abort() {}

        override fun replaceConversation(conversation: Conversation) {
            check(!state.value.isStreaming) { "Cannot replace the conversation while streaming" }
            this.conversation = conversation
        }

        private companion object {
            const val RUNTIME_NOT_CONNECTED = "Chat runtime not connected"
        }
    }
}
