package works.resolve.pathfinder.ui.chat

import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.agent.AgentState
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool

/**
 * UI projection of the committed transcript: the active conversation path is
 * the structural source (pi's session branch), but only entries still live
 * in the agent transcript render — auto-retry and overflow recovery remove
 * failed assistant messages from agent state while the append-only tree
 * keeps them in history, exactly like pi's UI. Keys are stable per path
 * index+role+timestamp so same-millisecond messages can never collide.
 */
internal fun projectCommitted(
    liveMessages: List<Message>,
    conversation: Conversation
): List<ChatMessage> {
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
    val projected = mutableListOf<ChatMessage>()
    conversation.activeEntries().forEachIndexed { index, entry ->
        when {
            // pi shows the compaction summary in a collapsible; the marker
            // stays minimal — the summary lives in LLM context only.
            entry is CompactionEntry -> projected.add(
                ChatMessage(
                    id = "compacted-${entry.id}",
                    role = ChatRole.Assistant,
                    blocks = emptyList(),
                    isCompactionMarker = true
                )
            )

            entry !is MessageEntry || !live.contains(entry.message) -> Unit

            else -> {
                val message = entry.message
                val chat = when (message) {
                    is UserMessage -> ChatMessage(
                        id = "msg-$index-${message.timestamp}",
                        role = ChatRole.User,
                        blocks = message.content.toChatBlocks()
                    )

                    is AssistantMessage -> ChatMessage(
                        id = "msg-$index-${message.timestamp}",
                        role = ChatRole.Assistant,
                        blocks = message.content.toChatBlocks(),
                        error = message.errorMessage,
                        reasoningTokens = message.usage.reasoning
                    )

                    // Distinct id namespace so a tool row can never collide
                    // with a message row.
                    is ToolResultMessage -> ChatMessage(
                        id = "tool-$index-${message.timestamp}-${message.toolCallId}",
                        role = ChatRole.Tool,
                        blocks = emptyList(),
                        toolResult = ChatToolResult(
                            toolCallId = message.toolCallId,
                            toolName = message.toolName,
                            isError = message.isError,
                            output = toolResultOutput(message),
                            input = liveCalls[message.toolCallId]
                                ?.let { toolCallInput(it.name, it.arguments) },
                            searchResults = toolResultSearchResults(message)
                        )
                    )
                }
                projected.add(chat)
            }
        }
    }
    return projected
}

/** UI projection of the in-flight partial; distinct key namespace from committed rows.
 * Pi's streaming message is role-generic (user/tool-result starts transiently
 * occupy it); this contract is assistant-only, so non-assistant partials
 * project to nothing at the call site. */
internal fun projectStreaming(message: AssistantMessage): ChatMessage = ChatMessage(
    id = "streaming-${message.timestamp}",
    role = ChatRole.Assistant,
    blocks = message.content.toChatBlocks(),
    error = message.errorMessage,
    reasoningTokens = message.usage.reasoning
)

/** Ordered blocks: consecutive thinking parts merge into one, blank parts drop. */
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
            is ThinkingContent -> (
                thinkingRun ?: mutableListOf<String>().also {
                    thinkingRun = it
                }
                ).add(part.thinking)

            is TextContent -> {
                flushThinking()
                part.text.takeIf { it.isNotBlank() }?.let { blocks.add(ChatBlock.Text(it)) }
            }

            is ToolCall -> {
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
 * Full text output: text parts joined with newlines. No truncation at the
 * projection boundary — the viewer scrolls the whole text.
 */
internal fun toolResultOutput(message: ToolResultMessage): String? {
    val parts = message.content.filterIsInstance<TextContent>()
    if (parts.isEmpty()) return null
    return parts.joinToString("\n") { it.text }.takeIf { it.isNotEmpty() }
}

/**
 * web_search result entries parsed from the tool result's `details`
 * (mirrors the fields BraveWebSearchTool emits; reads are lenient so a
 * future shape change degrades to the text renderer, never a crash).
 * Null for other tools, error results, and malformed or empty shapes.
 */
internal fun toolResultSearchResults(message: ToolResultMessage): List<ChatSearchResult>? {
    if (message.isError || message.toolName != BraveWebSearchTool.NAME) return null
    val results = (message.details as? JsonObject)?.arr("results") ?: return null
    val entries = results.mapNotNull { it as? JsonObject }.map { r ->
        ChatSearchResult(
            title = r.str("title").orEmpty(),
            url = r.str("url").orEmpty(),
            description = r.str("description")?.takeIf { it.isNotEmpty() }
        )
    }
    return entries.takeIf { it.isNotEmpty() }
}

/**
 * The one call argument a tool's row title is built from, parsed from the
 * raw JSON arguments string; the argument key comes from the shared
 * [ToolCallTitles] spec table. Null for tools without a spec and for
 * malformed arguments or a missing/empty value. Raw JSON never enters UI
 * state beyond this parsed field.
 */
internal fun toolCallInput(toolName: String, arguments: String): String? {
    val argument = ToolCallTitles.specFor(toolName)?.argument ?: return null
    val parsed =
        runCatching { lenientJson.parseToJsonElement(arguments) }.getOrNull() as? JsonObject
            ?: return null
    return parsed.string(argument)?.takeIf { it.isNotEmpty() }
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
                rows.add(
                    PendingToolExecution(
                        part.id,
                        part.name,
                        toolCallInput(part.name, part.arguments)
                    )
                )
            }
        }
    }
    // A malformed or out-of-order event must still show an in-flight
    // indicator rather than disappearing from the UI.
    for (id in state.pendingToolCalls) {
        if (resolved.add(id)) rows.add(PendingToolExecution(id, UNKNOWN_TOOL_NAME))
    }
    return rows
}

private const val UNKNOWN_TOOL_NAME = "tool"
