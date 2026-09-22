package works.resolve.pathfinder.ui.chat

import com.mikepenz.markdown.model.State
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.codingagent.core.CompactionEntry
import works.resolve.pathfinder.codingagent.core.MessageEntry
import works.resolve.pathfinder.codingagent.core.SessionEntry

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
    pathEntries: List<SessionEntry>,
    parse: (String) -> State
): List<TranscriptRow> {
    val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Message, Boolean>())
    live.addAll(liveMessages)
    // Live results by call id: a Tool row's result half.
    val liveResults = mutableMapOf<String, ToolResultMessage>()
    for (message in liveMessages) {
        (message as? ToolResultMessage)?.let { liveResults[it.toolCallId] = it }
    }
    val projected = mutableListOf<TranscriptRow>()
    pathEntries.forEach { entry ->
        when {
            // pi shows the compaction summary in a collapsible; the marker
            // stays minimal — the summary lives in LLM context only.
            entry is CompactionEntry -> projected.add(TranscriptRow.Compacted(entry.id))

            entry is MessageEntry && live.contains(entry.message) ->
                projectMessageRows(entry.message, entry.id, liveResults, parse, projected)

            else -> Unit
        }
    }
    // The session re-emits message_end before appending to the tree (pi's
    // order), so the just-ended message has no entry yet at projection
    // time; it renders from live state after the branch rows and re-keys
    // onto its entry id once the append lands. The agent-state baseline
    // system prompt never enters the tree and stays unrendered, as before.
    val appended = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<Message, Boolean>()
    )
    for (entry in pathEntries) {
        (entry as? MessageEntry)?.let { appended.add(it.message) }
    }
    for (message in liveMessages) {
        if (message !is SystemMessage && !appended.contains(message)) {
            projectMessageRows(
                message,
                "live:${System.identityHashCode(message)}",
                liveResults,
                parse,
                projected
            )
        }
    }
    return projected
}

/** Chat plus per-call Tool rows for one committed or still-pending message. */
private fun projectMessageRows(
    message: Message,
    rowId: String,
    liveResults: Map<String, ToolResultMessage>,
    parse: (String) -> State,
    projected: MutableList<TranscriptRow>
) {
    // Tool results render through their call's row — a standalone row would
    // double every settled execution.
    if (message !is ToolResultMessage) {
        val blocks = if (message is AssistantMessage) {
            buildMarkdownBlocks(message.content, parse)
        } else {
            emptyList()
        }
        projected.add(TranscriptRow.Chat(rowId, message, blocks))
        if (message is AssistantMessage) {
            for (part in message.content) {
                if (part is ToolCall) {
                    projected.add(
                        TranscriptRow.Tool(
                            id = "$rowId:${part.id}",
                            call = part,
                            result = liveResults[part.id]
                        )
                    )
                }
            }
        }
    }
}
