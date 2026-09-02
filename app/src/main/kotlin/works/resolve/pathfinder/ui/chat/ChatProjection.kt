package works.resolve.pathfinder.ui.chat

import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.data.sessions.CompactionEntry
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.MessageEntry

// ---- transcript projections (pure; pi's session branch → chat rows) ----

/**
 * UI projection of the committed transcript as ordered blocks: the active
 * conversation path is the structural source (pi's session branch) but only
 * entries still live in the agent transcript render — auto-retry and
 * overflow recovery remove failed assistant messages from agent state while
 * the append-only tree keeps them in history, exactly like pi's UI.
 * Text parts stay separate, runs of consecutive thinking parts merge into
 * one block (pi's assistant-message semantics), and blank parts drop. Keys
 * are stable per path index+role+timestamp so that same-millisecond
 * user/assistant messages can never collide.
 */
internal fun projectCommitted(liveMessages: List<Message>, conversation: Conversation): List<ChatMessage> {
    val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Message, Boolean>())
    live.addAll(liveMessages)
    val projected = mutableListOf<ChatMessage>()
    conversation.activeEntries().forEachIndexed { index, entry ->
        when {
            // A compaction cut renders as a minimal divider marker (pi's UI
            // shows the summary in a collapsible; pathfinder keeps the marker
            // minimal — the summary itself lives in LLM context only).
            entry is CompactionEntry -> projected.add(
                ChatMessage(id = "compacted-${entry.id}", role = ChatRole.Assistant, blocks = emptyList(), isCompactionMarker = true),
            )
            entry !is MessageEntry || !live.contains(entry.message) -> Unit
            else -> {
                val message = entry.message
                val chat = when (message) {
                    is UserMessage -> ChatMessage(
                        id = "msg-$index-${message.timestamp}",
                        role = ChatRole.User,
                        blocks = message.content.toChatBlocks(),
                    )
                    is AssistantMessage -> ChatMessage(
                        id = "msg-$index-${message.timestamp}",
                        role = ChatRole.Assistant,
                        blocks = message.content.toChatBlocks(),
                        error = message.errorMessage,
                    )
                    // Pi's tool-result messages render as tool rows (pi's
                    // ToolExecutionComponent semantics: tool name first,
                    // result text below, bounded to a preview until expanded).
                    // Only UI-safe fields cross the boundary: the structured
                    // `details` JSON is never projected (pi's TUI renders rich
                    // per-tool details; pathfinder stays minimal until real
                    // tools define needs). Distinct id namespace so a tool row
                    // can never collide with a message row.
                    is ToolResultMessage -> ChatMessage(
                        id = "tool-$index-${message.timestamp}-${message.toolCallId}",
                        role = ChatRole.Tool,
                        blocks = emptyList(),
                        toolResult = ChatToolResult(
                            toolCallId = message.toolCallId,
                            toolName = message.toolName,
                            isError = message.isError,
                            output = toolResultOutput(message),
                        ),
                    )
                }
                projected.add(chat)
            }
        }
    }
    return projected
}

/** UI projection of the in-flight partial; distinct key namespace from committed messages.
 * Pi's `streamingMessage` is role-generic (user/tool-result message_starts
 * transiently occupy it); the chat's streaming contract stays assistant-only,
 * so non-assistant partials project to nothing at the call site. */
internal fun projectStreaming(message: AssistantMessage): ChatMessage =
    ChatMessage(
        id = "streaming-${message.timestamp}",
        role = ChatRole.Assistant,
        blocks = message.content.toChatBlocks(),
        error = message.errorMessage,
    )

/**
 * Projects content into ordered blocks: each non-blank [TextContent] becomes
 * its own [ChatBlock.Text]; runs of consecutive [ThinkingContent] merge into
 * one [ChatBlock.Thinking] joined with "\n\n" and trimmed (dropped when the
 * merged result is blank).
 */
internal fun List<Content>.toChatBlocks(): List<ChatBlock> {
    val blocks = mutableListOf<ChatBlock>()
    var thinkingRun: MutableList<String>? = null
    fun flushThinking() {
        thinkingRun?.let { run ->
            run.joinToString("\n\n").trim().takeIf { it.isNotEmpty() }?.let { merged ->
                blocks.add(ChatBlock.Thinking(merged))
            }
        }
        thinkingRun = null
    }
    for (part in this) {
        when (part) {
            is ThinkingContent -> (thinkingRun ?: mutableListOf<String>().also { thinkingRun = it }).add(part.thinking)
            is TextContent -> {
                flushThinking()
                part.text.takeIf { it.isNotBlank() }?.let { blocks.add(ChatBlock.Text(it)) }
            }
            is ToolCall -> {
                // Name-only label in content order; raw JSON arguments are
                // deliberately never projected into UI state.
                flushThinking()
                blocks.add(ChatBlock.ToolCall(part.id, part.name))
            }
            else -> flushThinking()
        }
    }
    flushThinking()
    return blocks
}

/**
 * Full text output of a tool result (pi's getTextOutput, render-utils.ts —
 * text parts joined with newlines, no truncation at the projection
 * boundary; renderers bound the collapsed preview the way pi's renderers
 * cap preview lines). Null when the result carries no text at all.
 */
internal fun toolResultOutput(message: ToolResultMessage): String? {
    val parts = message.content.filterIsInstance<TextContent>()
    if (parts.isEmpty()) return null
    return parts.joinToString("\n") { it.text }.takeIf { it.isNotEmpty() }
}

/**
 * Resolves the agent's pending tool-execution ids into UI rows: names come
 * from committed assistant [ToolCall] blocks in transcript order (calls
 * commit before execution starts); an unknown id falls back to a generic
 * label so it can never block the indicator.
 */
internal fun pendingToolExecutions(state: AgentState): List<PendingToolExecution> {
    if (state.pendingToolCalls.isEmpty()) return emptyList()
    val rows = mutableListOf<PendingToolExecution>()
    val resolved = mutableSetOf<String>()
    for (message in state.messages) {
        val content = (message as? AssistantMessage)?.content ?: continue
        for (part in content) {
            if (part is ToolCall && part.id in state.pendingToolCalls && resolved.add(part.id)) {
                rows.add(PendingToolExecution(part.id, part.name))
            }
        }
    }
    // Defensive fallback: a malformed or out-of-order event must still show
    // an in-flight indicator rather than disappearing from the UI.
    for (id in state.pendingToolCalls) {
        if (resolved.add(id)) rows.add(PendingToolExecution(id, UNKNOWN_TOOL_NAME))
    }
    return rows
}

private const val UNKNOWN_TOOL_NAME = "tool"
