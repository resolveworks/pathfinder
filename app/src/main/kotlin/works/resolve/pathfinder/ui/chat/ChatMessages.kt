package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import works.resolve.pathfinder.R
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.ui.chat.markdown.MarkdownText
import works.resolve.pathfinder.ui.theme.PathfinderTheme

private const val STREAMING_PLACEHOLDER = "…"

private fun ChatMessage.displayText(): String =
    blocks.filterIsInstance<ChatBlock.Text>().joinToString("\n\n") { it.text }

/**
 * [listSaver] needs flat Bundle-saveable values, so the map is saved as an
 * alternating key, value, … list.
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
    }
)

@Composable
internal fun ConversationContent(
    uiState: ChatUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    initialThinkingOverrides: Map<String, Boolean> = emptyMap()
) {
    val messageCount = uiState.messages.size
    val streamingId = uiState.streamingMessage?.id

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
                modifier = Modifier.align(Alignment.Center)
            )
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Emitted newest-first; reverseLayout puts it at the bottom,
            // preserving chronological visual order.
            uiState.streamingMessage?.let { streaming ->
                item(key = streaming.id) {
                    val hasVisibleText = streaming.blocks.any {
                        it is ChatBlock.Text &&
                            it.text.isNotBlank()
                    }
                    val hasThinking = streaming.blocks.any { it is ChatBlock.Thinking }
                    AssistantMessageItem(
                        message = if (hasVisibleText || hasThinking || streaming.error != null) {
                            streaming
                        } else {
                            // pi renders tool-call-only assistant messages as
                            // zero lines (the execution shows as its own row);
                            // the placeholder bridges until the call commits
                            // and the pending tool row appears.
                            streaming.copy(blocks = listOf(ChatBlock.Text(STREAMING_PLACEHOLDER)))
                        },
                        isStreaming = true,
                        showThinking = uiState.showThinking,
                        thinkingOverrides = thinkingOverrides
                    )
                }
            }
            items(uiState.pendingTools, key = { "pending-${it.toolCallId}" }) { pending ->
                ToolCallItem(
                    toolCallId = pending.toolCallId,
                    toolName = pending.toolName,
                    input = pending.input,
                    output = null,
                    isError = false,
                    running = true
                )
            }
            items(
                uiState.messages.asReversed().filter(ChatMessage::hasRenderableContent),
                key = ChatMessage::id
            ) { message ->
                when {
                    message.isCompactionMarker -> CompactedDivider()

                    message.role == ChatRole.Tool -> message.toolResult?.let { result ->
                        ToolCallItem(
                            toolCallId = result.toolCallId,
                            toolName = result.toolName,
                            input = result.input,
                            output = result.output,
                            isError = result.isError,
                            running = false
                        )
                    }

                    message.role == ChatRole.User -> UserMessageItem(message)

                    else -> AssistantMessageItem(
                        message = message,
                        showThinking = uiState.showThinking,
                        thinkingOverrides = thinkingOverrides
                    )
                }
            }
        }
    }
}

/** Marks a compaction cut; the summary itself lives in LLM context only. */
@Composable
private fun CompactedDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.chat_compacted),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * Whether the message renders a row at all: pi renders tool-call-only
 * assistant messages as zero lines (the executions show as their own tool
 * rows), so they are filtered out here; an error keeps its row.
 */
private fun ChatMessage.hasRenderableContent(): Boolean = isCompactionMarker ||
    role != ChatRole.Assistant ||
    error != null ||
    blocks.any { it is ChatBlock.Text || it is ChatBlock.Thinking }

/**
 * User message: a right-aligned bubble, as in modern chat apps. The start
 * padding caps the bubble width so short messages stay compact and long
 * ones never span the full row.
 */
@Composable
private fun UserMessageItem(message: ChatMessage, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 48.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            // pi renders user markdown literally (markers preserved, not
            // parsed), so the bubble stays plain text.
            Text(
                text = message.displayText(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * Assistant message: plain full-width markdown with no container, so it
 * reads like a reply rather than a bubble. Thinking blocks stay
 * collapsible; an error renders below the body in error color.
 */
@Composable
private fun AssistantMessageItem(
    message: ChatMessage,
    showThinking: Boolean,
    thinkingOverrides: MutableMap<String, Boolean>,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // pi renders assistant text as markdown; only this path goes
        // through MarkdownText.
        message.blocks.forEachIndexed { index, block ->
            when (block) {
                is ChatBlock.Text -> MarkdownText(markdown = block.text)

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
                        onToggle = { thinkingOverrides[key] = !expanded }
                    )
                }
            }
        }
        message.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal enum class ToolResultFormat {
    RAW,

    MARKDOWN
}

/**
 * pi resolves per-tool result renderers by tool name, falling back to
 * generic raw text; the port's equivalent is this name-keyed table —
 * listed tools render their output as Markdown, everything else keeps
 * pi's raw fallback.
 */
internal object ToolResultRenderers {
    /** Scry renders web_search results as Markdown. */
    private val formats: Map<String, ToolResultFormat> = mapOf(
        BraveWebSearchTool.NAME to ToolResultFormat.MARKDOWN
    )

    fun formatFor(toolName: String): ToolResultFormat = formats[toolName] ?: ToolResultFormat.RAW
}

/**
 * Shared row-title spec, keyed by tool name like [ToolResultRenderers]:
 * which call argument titles a tool's row ("Searched for …", "Fetched …")
 * and the string format rendering it. Tools without a spec keep the bare
 * tool name as their title; adding a tool is one table entry.
 */
internal object ToolCallTitles {
    data class Spec(
        /** JSON-argument key holding the row-title input. */
        val argument: String,
        /** Title format filled with the parsed argument (strings.xml). */
        val format: Int
    )

    private val specs: Map<String, Spec> = mapOf(
        BraveWebSearchTool.NAME to Spec("query", R.string.tool_title_searched_for),
        WebFetchTool.NAME to Spec("url", R.string.tool_title_fetched)
    )

    fun specFor(toolName: String): Spec? = specs[toolName]
}

/** Row title: the spec's format filled with the parsed input, else the tool name. */
@Composable
internal fun toolCallTitle(toolName: String, input: String?): String {
    val spec = ToolCallTitles.specFor(toolName)
    return if (spec != null && input != null) stringResource(spec.format, input) else toolName
}

/**
 * One tool execution as a single line in a tonal container, distinct from
 * conversation text: the row title (a tool-specific phrase like
 * "Searched for …", else the tool name) and a spinner while running.
 * Rows with output expand in place — per row, never globally (pi's Ctrl+O,
 * exposed as a tap). Error coloring is a native adaptation (pi signals
 * errors through the shell, not text color).
 */
@Composable
private fun ToolCallItem(
    toolCallId: String,
    toolName: String,
    input: String?,
    output: String?,
    isError: Boolean,
    running: Boolean,
    modifier: Modifier = Modifier
) {
    // Expansion is local to this row; the lazy list's stable keys keep it
    // across recycling and process death.
    var expanded by rememberSaveable(toolCallId) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            // The whole row is the toggle's touch target.
            modifier = if (output != null) {
                Modifier.clickable(onClickLabel = stringResource(R.string.tool_output_toggle)) {
                    expanded =
                        !expanded
                }
            } else {
                Modifier
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = toolCallTitle(toolName, input),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
                    modifier = Modifier.weight(1f)
                )
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else if (isError) {
                    Text(
                        text = stringResource(R.string.tool_status_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (output != null) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (output != null && expanded) {
                val format = ToolResultRenderers.formatFor(toolName)
                val color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    when (format) {
                        ToolResultFormat.MARKDOWN -> MarkdownText(markdown = output, color = color)

                        ToolResultFormat.RAW -> Text(
                            text = output,
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(
    text: String,
    expanded: Boolean,
    showLoader: Boolean,
    onToggle: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.thinking_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showLoader) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            // outline (not onSurfaceVariant): keeps expanded thinking clearly
            // dimmer than the onSurface answer text in both theme variants.
            MarkdownText(
                markdown = text,
                color = MaterialTheme.colorScheme.outline,
                italic = true
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
                        blocks = listOf(ChatBlock.Text("What is 2 + 2?"))
                    ),
                    ChatMessage(
                        id = "m2",
                        role = ChatRole.Assistant,
                        blocks = listOf(
                            ChatBlock.Thinking(
                                "The user asks a simple arithmetic question. *2 + 2* equals " +
                                    "**4** — no tools needed."
                            ),
                            ChatBlock.Text("2 + 2 = **4**."),
                            ChatBlock.Thinking(
                                "Answered directly; offering the derivation seems unnecessary."
                            )
                        )
                    ),
                    ChatMessage(
                        id = "m3",
                        role = ChatRole.Tool,
                        blocks = emptyList(),
                        toolResult = ChatToolResult(
                            toolCallId = "t1",
                            toolName = "web_search",
                            isError = false,
                            output = "1. Arithmetic — Wikipedia\n2. Addition — Wikipedia",
                            input = "arithmetic"
                        )
                    )
                )
            ),
            listState = rememberLazyListState(),
            initialThinkingOverrides = mapOf("m2:0" to true)
        )
    }
}
