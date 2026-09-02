package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ui.chat.markdown.MarkdownText
import works.resolve.pathfinder.ui.theme.PathfinderTheme

private const val STREAMING_PLACEHOLDER = "…"

/** Concatenated text of the text blocks; the displayable body for user (plain-text) messages. */
private fun ChatMessage.displayText(): String =
    blocks.filterIsInstance<ChatBlock.Text>().joinToString("\n\n") { it.text }

/**
 * Persists per-thinking-block expanded overrides across process death as two
 * parallel flat lists (key, value, key, value, …); both [String] and [Boolean]
 * are Bundle-saveable.
 */
private fun thinkingOverridesSaver() = listSaver<MutableMap<String, Boolean>, Any>(
    save = { map ->
        buildList {
            map.forEach { (key, expanded) ->
                add(key)
                add(expanded)
            }
        }
    },
    restore = { list ->
        mutableStateMapOf<String, Boolean>().apply {
            var i = 0
            while (i + 1 < list.size) {
                put(list[i] as String, list[i + 1] as Boolean)
                i += 2
            }
        }
    },
)

@Composable
internal fun ConversationContent(
    uiState: ChatUiState,
    modifier: Modifier = Modifier,
    initialThinkingOverrides: Map<String, Boolean> = emptyMap(),
    onToggleToolOutputExpansion: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val messageCount = uiState.messages.size
    val streamingId = uiState.streamingMessage?.id
    // Covers text AND thinking so a later chunk keeps auto-scrolling during
    // thinking-only streaming; thinking changes alone also advance it.
    val streamingLength = uiState.streamingMessage?.blocks?.sumOf { block ->
        when (block) {
            is ChatBlock.Text -> block.text.length
            is ChatBlock.Thinking -> block.text.length
            is ChatBlock.ToolCall -> 0
        }
    }

    // A reversed lazy list makes index 0 the bottom of the viewport. Reset to
    // that valid index whenever a session opens, a message is added, or the
    // streaming item grows. Including activeSessionId matters when switching
    // between transcripts that happen to contain the same number of messages.
    LaunchedEffect(uiState.activeSessionId, messageCount, uiState.pendingTools.size, streamingId, streamingLength) {
        if (messageCount > 0 || uiState.pendingTools.isNotEmpty() || streamingId != null) {
            listState.requestScrollToItem(0)
        }
    }

    // Per-block expanded overrides (ephemeral view state keyed by stable
    // "messageId:blockIndex"): a block the user never tapped keeps following
    // the persisted showThinking default; a tapped block locks in the user's
    // choice and outlives later changes to the setting.
    val thinkingOverrides = rememberSaveable(saver = thinkingOverridesSaver()) {
        mutableStateMapOf<String, Boolean>().apply { putAll(initialThinkingOverrides) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (messageCount == 0 && uiState.pendingTools.isEmpty() && streamingId == null) {
            Text(
                text = stringResource(R.string.chat_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
        ) {
            // reverseLayout places the first item at the bottom, so emit the
            // newest item first while preserving chronological visual order.
            uiState.streamingMessage?.let { streaming ->
                item(key = streaming.id) {
                    val hasVisibleText = streaming.blocks.any { it is ChatBlock.Text && it.text.isNotBlank() }
                    val hasThinking = streaming.blocks.any { it is ChatBlock.Thinking }
                    MessageItem(
                        message = if (hasVisibleText || hasThinking || streaming.error != null) {
                            streaming
                        } else {
                            // No visible content yet: same "…" placeholder as
                            // before. A thinking-only stream renders its real
                            // blocks (thinking header + loader) instead; a
                            // tool-call-only stream keeps it too — pi renders
                            // such assistant messages as zero lines (the
                            // execution shows as its own row), so the placeholder
                            // bridges until the call commits and the pending
                            // tool row appears.
                            streaming.copy(blocks = listOf(ChatBlock.Text(STREAMING_PLACEHOLDER)))
                        },
                        isStreaming = true,
                        showThinking = uiState.showThinking,
                        thinkingOverrides = thinkingOverrides,
                    )
                }
            }
            items(uiState.pendingTools, key = { "pending-${it.toolCallId}" }) { pending ->
                ToolResultRow(
                    toolName = pending.toolName,
                    output = null,
                    isError = false,
                    running = true,
                    expanded = uiState.toolOutputExpanded,
                    onToggleExpansion = onToggleToolOutputExpansion,
                )
                HorizontalDivider()
            }
            items(
                uiState.messages.asReversed().filter(ChatMessage::hasRenderableContent),
                key = ChatMessage::id,
            ) { message ->
                if (message.isCompactionMarker) {
                    CompactedDivider()
                } else if (message.role == ChatRole.Tool) {
                    message.toolResult?.let { result ->
                        ToolResultRow(
                            toolName = result.toolName,
                            output = result.output,
                            isError = result.isError,
                            running = false,
                            expanded = uiState.toolOutputExpanded,
                            onToggleExpansion = onToggleToolOutputExpansion,
                        )
                    }
                } else {
                    MessageItem(
                        message = message,
                        showThinking = uiState.showThinking,
                        thinkingOverrides = thinkingOverrides,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * Minimal divider marking a compaction cut in the active path (pi's
 * CompactionEntry): centered label between rules; the summary itself lives
 * in LLM context only.
 */
@Composable
private fun CompactedDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.chat_compacted),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * Whether the message renders a row at all. Pi's AssistantMessageComponent
 * renders zero lines for a tool-call-only assistant message — `toolCall`
 * blocks never render in the parent message; the executions render as their
 * own tool rows — so such messages are filtered out here. A message carrying
 * an error keeps its row (the error text shows in it).
 */
private fun ChatMessage.hasRenderableContent(): Boolean =
    isCompactionMarker ||
        role != ChatRole.Assistant ||
        error != null ||
        blocks.any { it is ChatBlock.Text || it is ChatBlock.Thinking }

/**
 * One chat row. User messages render plain concatenated text; assistant
 * messages render their blocks in content order — text blocks as markdown,
 * thinking blocks as collapsible [ThinkingBlock]s whose default expanded
 * state follows [showThinking] until the user taps one (then the per-block
 * [thinkingOverrides] entry wins, surviving changes to the setting). Tool
 * call blocks render nothing (pi's AssistantMessageComponent skips them:
 * executions render as their own tool rows).
 */
@Composable
private fun MessageItem(
    message: ChatMessage,
    showThinking: Boolean,
    thinkingOverrides: MutableMap<String, Boolean>,
    isStreaming: Boolean = false,
) {
    ListItem(
        // pi renders user markdown literally (markers preserved, not parsed);
        // the MVP equivalent here is plain text, so only the assistant path
        // goes through MarkdownText.
        headlineContent = {
            if (message.role == ChatRole.Assistant) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message.blocks.forEachIndexed { index, block ->
                        when (block) {
                            is ChatBlock.Text -> MarkdownText(markdown = block.text)
                            // Pi skips toolCall blocks in the assistant message;
                            // the execution renders as its own tool row.
                            is ChatBlock.ToolCall -> Unit
                            is ChatBlock.Thinking -> {
                                val key = "${message.id}:$index"
                                val expanded = thinkingOverrides[key] ?: showThinking
                                ThinkingBlock(
                                    text = block.text,
                                    expanded = expanded,
                                    // Loader only while thinking is actively being
                                    // produced: the streaming message's LAST block
                                    // and a Thinking block (earlier runs are done).
                                    showLoader = isStreaming && index == message.blocks.lastIndex,
                                    onToggle = { thinkingOverrides[key] = !expanded },
                                )
                            }
                        }
                    }
                }
            } else {
                Text(message.displayText())
            }
        },
        overlineContent = {
            Text(
                when {
                    message.role == ChatRole.User -> stringResource(R.string.role_user)
                    message.error != null -> stringResource(R.string.role_assistant_failed)
                    else -> stringResource(R.string.role_assistant)
                },
            )
        },
        supportingContent = message.error?.let { error ->
            {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/**
 * Collapsed preview cap (pi's FALLBACK_PREVIEW_LINES, tool-execution.ts):
 * the generic fallback renders the first 10 output lines plus a
 * "... (N more lines)" continuation hint; the expansion flag lifts the cap.
 */
private const val FALLBACK_PREVIEW_LINES = 10

/**
 * One tool row (pi's ToolExecutionComponent semantics, native adaptation):
 * tool name first, the result output below — parsed as markdown, matching
 * Scry's web_search renderer (renderMarkdownResult renders the result text
 * through Markdown), bounded to a [FALLBACK_PREVIEW_LINES] raw-line preview
 * with a remaining-lines hint until the global expand flag lifts the cap to
 * the full output; a loader while the execution is running and a done/failed
 * label after, error-colored when the result is an error. Tapping a row with
 * output flips the global flag (pi's Ctrl+O, `app.tools.expand`, exposed
 * here as a tap on the tool output itself); running rows carry no output and
 * stay inert.
 */
@Composable
private fun ToolResultRow(
    toolName: String,
    output: String?,
    isError: Boolean,
    running: Boolean,
    expanded: Boolean,
    onToggleExpansion: () -> Unit,
) {
    ListItem(
        // The whole row is the toggle's touch target (Android 48dp-target
        // convention); only rows that render output respond.
        modifier =
            if (output != null) {
                Modifier.clickable(onClickLabel = stringResource(R.string.tool_output_toggle)) { onToggleExpansion() }
            } else {
                Modifier
            },
        headlineContent = {
            Text(
                text = toolName,
                color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        supportingContent = output?.let { text ->
            {
                val lines = text.lines()
                val display = if (expanded) lines else lines.take(FALLBACK_PREVIEW_LINES)
                val remaining = lines.size - display.size
                Column {
                    // Scry's renderMarkdownResult caps the raw markdown
                    // lines first, then renders the kept lines as Markdown.
                    MarkdownText(
                        markdown = display.joinToString("\n"),
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (remaining > 0) {
                        // pi: `... (${remaining} more lines, <key> to
                        // expand)`; the row tap replaces the keybinding hint.
                        Text(
                            text = stringResource(R.string.tool_output_more_lines, remaining),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        trailingContent = {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(
                        if (isError) R.string.tool_status_failed else R.string.tool_status_done,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * Collapsible unit for one thinking run (pi's assistant-message thinking):
 * a tappable header row — lowercase "thinking" label, a small loader only
 * while the owning message is still streaming, and an expand chevron — plus,
 * when expanded, the reasoning rendered as dimmed italic markdown.
 */
@Composable
private fun ThinkingBlock(
    text: String,
    expanded: Boolean,
    showLoader: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.thinking_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showLoader) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            MarkdownText(
                markdown = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                italic = true,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationContentThinkingPreview() {
    PathfinderTheme {
        ConversationContent(
            uiState = ChatUiState(
                status = ChatStatus.Ready,
                // Default collapsed; the explicit per-block overrides below
                // expand the first run only, exercising both states at once.
                showThinking = false,
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = ChatRole.User,
                        blocks = listOf(ChatBlock.Text("What is 2 + 2?")),
                    ),
                    ChatMessage(
                        id = "m2",
                        role = ChatRole.Assistant,
                        blocks = listOf(
                            ChatBlock.Thinking("The user asks a simple arithmetic question. *2 + 2* equals **4** — no tools needed."),
                            ChatBlock.Text("2 + 2 = **4**."),
                            ChatBlock.Thinking("Answered directly; offering the derivation seems unnecessary."),
                        ),
                    ),
                ),
            ),
            initialThinkingOverrides = mapOf("m2:0" to true),
        )
    }
}
