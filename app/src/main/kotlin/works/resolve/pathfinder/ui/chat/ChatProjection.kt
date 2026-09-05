package works.resolve.pathfinder.ui.chat

import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.MessageEntry

/**
 * UI projection of the committed transcript: the active conversation path is
 * the structural source (pi's session branch), but only entries still live
 * in the agent transcript render — auto-retry and overflow recovery remove
 * failed assistant messages from agent state while the append-only tree
 * keeps them in history, exactly like pi's UI. Rows key by entry id and
 * carry the runtime messages themselves; bodies render directly from them.
 */
internal fun projectCommitted(
    liveMessages: List<Message>,
    conversation: Conversation
): List<TranscriptRow> {
    val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Message, Boolean>())
    live.addAll(liveMessages)
    // Committed calls by id: a tool-result row titles itself from its
    // originating call's arguments (the result message carries none).
    val liveCalls = mutableMapOf<String, ToolCall>()
    for (message in liveMessages) {
        val content = (message as? AssistantMessage)?.content ?: continue
        for (part in content) {
            if (part is ToolCall) liveCalls[part.id] = part
        }
    }
    val projected = mutableListOf<TranscriptRow>()
    conversation.activeEntries().forEach { entry ->
        when {
            // pi shows the compaction summary in a collapsible; the marker
            // stays minimal — the summary lives in LLM context only.
            entry is CompactionEntry -> projected.add(TranscriptRow.Compacted(entry.id))

            entry is MessageEntry && live.contains(entry.message) -> projected.add(
                TranscriptRow.Chat(
                    id = entry.id,
                    message = entry.message,
                    call = (entry.message as? ToolResultMessage)?.let { liveCalls[it.toolCallId] }
                )
            )

            else -> Unit
        }
    }
    return projected
}

/**
 * Resolves the agent's pending tool-execution ids into UI rows via their
 * committed assistant calls in transcript order (calls commit before
 * execution starts).
 */
internal fun pendingToolExecutions(state: AgentState): List<PendingToolExecution> {
    if (state.pendingToolCalls.isEmpty()) return emptyList()
    val rows = mutableListOf<PendingToolExecution>()
    val resolved = mutableSetOf<String>()
    for (message in state.messages) {
        val content = (message as? AssistantMessage)?.content ?: continue
        for (part in content) {
            if (part is ToolCall && part.id in state.pendingToolCalls && resolved.add(part.id)) {
                rows.add(PendingToolExecution(part.id, part))
            }
        }
    }
    // A malformed or out-of-order event must still show an in-flight
    // indicator rather than disappearing from the UI.
    for (id in state.pendingToolCalls) {
        if (resolved.add(id)) rows.add(PendingToolExecution(id))
    }
    return rows
}
