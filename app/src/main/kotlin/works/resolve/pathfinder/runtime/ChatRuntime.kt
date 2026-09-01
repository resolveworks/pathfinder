package works.resolve.pathfinder.runtime

import ai.koog.prompt.message.Message
import kotlinx.coroutines.flow.StateFlow
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * The permanent ViewModel⇄runtime seam: creates chat sessions that own the
 * conversation tree and drive a provider model. The production implementation
 * (Koog runtime) maps [ModelSettings] onto Koog executor clients; the UI and
 * persistence layers depend only on this contract.
 */
interface ChatRuntime {
    /**
     * Creates a chat session bound to [settings] and [sessionId], continuing
     * from [conversation]'s entries and leaf. The returned session owns that
     * tree from here on; callers project and persist it, and mutate its leaf
     * position through [ChatRuntimeSession.replaceConversation].
     */
    fun createSession(
        settings: ModelSettings,
        sessionId: String,
        conversation: Conversation,
    ): ChatRuntimeSession
}

/** One live chat session owned by the [ChatRuntime]. */
interface ChatRuntimeSession {
    /** The conversation tree this session owns (transcript source of truth). */
    val conversation: Conversation

    /** Live session state; see [ChatRuntimeState]. */
    val state: StateFlow<ChatRuntimeState>

    /**
     * Sends [text] as the next user message on the active path and streams a
     * response. Illegal while [ChatRuntimeState.isStreaming] is true.
     */
    fun prompt(text: String)

    /** Aborts the in-flight response, if any. */
    fun abort()

    /**
     * Swaps in a new [model] (with its [thinking] option) for subsequent
     * prompts. The conversation tree is untouched: the next prompt simply
     * executes against the new selection. Illegal while
     * [ChatRuntimeState.isStreaming] is true.
     */
    fun selectModel(model: ModelDescriptor, thinking: ThinkingOption)

    /**
     * Applies [option] to the current model for subsequent prompts. Illegal
     * while streaming (same rule as [selectModel]).
     */
    fun setThinking(option: ThinkingOption)

    /**
     * Swaps in a new tree (tree navigation/re-edit): subsequent prompts
     * continue from [conversation]'s leaf. Illegal while streaming.
     */
    fun replaceConversation(conversation: Conversation)
}

/**
 * Everything the UI needs from a running session, folded into one immutable
 * state — no separate event flow.
 *
 * @property committedMessages Transcript messages the runtime currently holds
 *   live (committed entries of the active path); tree entries no longer live
 *   stay in [ChatRuntimeSession.conversation] but render as removed.
 * @property streamingMessage The in-flight partial assistant message.
 * @property isStreaming True between a prompt start and its terminal state.
 * @property error User-safe error text, or null. Surfaced once; the runtime
 *   may clear it on the next prompt.
 * @property commitCount Monotonic count of committed assistant/user messages
 *   appended to the tree by this session; each increment is a persistence
 *   point (the state transition that tells the UI the tree grew).
 */
data class ChatRuntimeState(
    val committedMessages: List<Message> = emptyList(),
    val streamingMessage: Message.Assistant? = null,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val commitCount: Int = 0,
)
