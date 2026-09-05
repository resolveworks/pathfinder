package works.resolve.pathfinder.ui.chat

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
 *
 * Tool executions render like pi's execution components: one row per call,
 * emitted after its assistant message, holding the call itself; the tool
 * result joins by call id when it commits, so the row is updated in place
 * instead of being removed and re-added across the persistence write.
 */
internal fun projectCommitted(
    liveMessages: List<Message>,
    conversation: Conversation
): List<TranscriptRow> {
    val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Message, Boolean>())
    live.addAll(liveMessages)
    // Live results by call id: a Tool row's result half.
    val liveResults = mutableMapOf<String, ToolResultMessage>()
    for (message in liveMessages) {
        (message as? ToolResultMessage)?.let { liveResults[it.toolCallId] = it }
    }
    val projected = mutableListOf<TranscriptRow>()
    conversation.activeEntries().forEach { entry ->
        when {
            // pi shows the compaction summary in a collapsible; the marker
            // stays minimal — the summary lives in LLM context only.
            entry is CompactionEntry -> projected.add(TranscriptRow.Compacted(entry.id))

            entry is MessageEntry && live.contains(entry.message) -> {
                val message = entry.message
                // Tool results render through their call's row below — a
                // standalone row would double every settled execution.
                if (message !is ToolResultMessage) {
                    projected.add(TranscriptRow.Chat(entry.id, message))
                    if (message is AssistantMessage) {
                        for (part in message.content) {
                            if (part is ToolCall) {
                                projected.add(
                                    TranscriptRow.Tool(
                                        id = "${entry.id}:${part.id}",
                                        call = part,
                                        result = liveResults[part.id]
                                    )
                                )
                            }
                        }
                    }
                }
            }

            else -> Unit
        }
    }
    return projected
}
