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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
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

    // Tool result opened in the viewer sheet, by tool call id. Ephemeral view
    // state resolved against the live message list, so a stale id (session
    // switch, branch navigation) just closes the sheet.
    var openToolResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val openToolResult = openToolResultId?.let { id ->
        uiState.messages.firstNotNullOfOrNull { message ->
            message.toolResult?.takeIf { it.toolCallId == id }
        }
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
                    toolName = pending.toolName,
                    input = pending.input,
                    output = null,
                    isError = false,
                    running = true,
                    onOpenOutput = {}
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
                            toolName = result.toolName,
                            input = result.input,
                            output = result.output,
                            isError = result.isError,
                            running = false,
                            onOpenOutput = { openToolResultId = result.toolCallId }
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

        openToolResult?.let { result ->
            ToolOutputSheet(
                result = result,
                onDismiss = { openToolResultId = null }
            )
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
    /** Scry renders web_search results as Markdown; web_fetch output is Markdown (defuddle). */
    private val formats: Map<String, ToolResultFormat> = mapOf(
        BraveWebSearchTool.NAME to ToolResultFormat.MARKDOWN,
        WebFetchTool.NAME to ToolResultFormat.MARKDOWN
    )

    fun formatFor(toolName: String): ToolResultFormat = formats[toolName] ?: ToolResultFormat.RAW
}

/** pi's preview budget: Scry's markdown renderer and the generic fallback both clip at ten source lines. */
private const val TOOL_PREVIEW_LINES = 10

/** An opened tool row's display text plus what the show-all button still hides. */
internal data class ToolPreview(val text: String, val hiddenLines: Int)

/**
 * pi clips an un-expanded tool result at ten source lines and hints at the
 * rest; the port keeps the clip in the viewer sheet and turns the hint into
 * the show-all button ([showAll] reveals everything). Scry leaves error
 * output whole; the generic fallback clips errors too.
 */
internal fun toolOutputPreview(
    output: String,
    format: ToolResultFormat,
    isError: Boolean,
    showAll: Boolean
): ToolPreview {
    if (showAll || (format == ToolResultFormat.MARKDOWN && isError)) {
        return ToolPreview(output, 0)
    }
    val lines = output.lines()
    return if (lines.size <= TOOL_PREVIEW_LINES) {
        ToolPreview(output, 0)
    } else {
        ToolPreview(
            lines.take(TOOL_PREVIEW_LINES).joinToString("\n"),
            lines.size - TOOL_PREVIEW_LINES
        )
    }
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
 * One tool execution as a single fixed-height line in a tonal container,
 * distinct from conversation text: the row title (a tool-specific phrase
 * like "Searched for …", else the tool name) and a spinner while running.
 * Rows with output open [ToolOutputSheet] instead of expanding in place:
 * the transcript list is reversed (so streaming pins to its bottom), which
 * pins each row's bottom edge and would grow an in-place expansion upward
 * past the tapped title — the sheet owns its scroll, starts at the top of
 * the content, and leaves the transcript untouched behind it. Per-row, never
 * global (pi's Ctrl+O, exposed as a tap). Error coloring is a native
 * adaptation (pi signals errors through the shell, not text color).
 */
@Composable
private fun ToolCallItem(
    toolName: String,
    input: String?,
    output: String?,
    isError: Boolean,
    running: Boolean,
    onOpenOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // The whole row is the open action's touch target.
            modifier = if (output != null) {
                Modifier.clickable(onClickLabel = stringResource(R.string.tool_output_view)) {
                    onOpenOutput()
                }
            } else {
                Modifier
            }.padding(horizontal = 12.dp, vertical = 10.dp)
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
        }
    }
}

/**
 * Viewer for one tool result: pi's clipped preview with the show-all button
 * in a scrollable modal sheet. A fresh scroll state per open anchors the
 * viewport at the top of the content; dismissing restores the transcript's
 * scroll position because it never moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolOutputSheet(
    result: ChatToolResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        var showAll by remember(result.toolCallId) { mutableStateOf(false) }
        val format = ToolResultRenderers.formatFor(result.toolName)
        val searchResults = result.searchResults?.takeIf { !result.isError }
        val preview = toolOutputPreview(
            output = result.output.orEmpty(),
            format = format,
            isError = result.isError,
            showAll = showAll
        )
        val contentColor = if (result.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = toolCallTitle(result.toolName, result.input),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (result.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f)
                )
                if (result.isError) {
                    Text(
                        text = stringResource(R.string.tool_status_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    searchResults != null -> WebSearchResults(results = searchResults)

                    format == ToolResultFormat.MARKDOWN -> MarkdownText(
                        markdown = preview.text,
                        color = contentColor
                    )

                    else -> Text(
                        text = preview.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                }
                if (searchResults == null && preview.hiddenLines > 0) {
                    TextButton(onClick = { showAll = true }) {
                        Text(
                            pluralStringResource(
                                R.plurals.tool_output_show_all,
                                preview.hiddenLines,
                                preview.hiddenLines
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * web_search results rendered from the structured details: per result, the
 * title link and description stay visible while the extra excerpts wait
 * behind a read-more toggle, so one long result cannot dominate the sheet.
 * Results without structured details render through the markdown/text
 * fallback above instead.
 */
@Composable
private fun WebSearchResults(results: List<ChatSearchResult>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        results.forEachIndexed { index, result ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            WebSearchResultItem(result)
        }
    }
}

@Composable
private fun WebSearchResultItem(result: ChatSearchResult, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val label = result.title.ifEmpty { result.url }.takeIf { it.isNotBlank() }
        when {
            label != null && result.url.isNotEmpty() -> Text(
                text = buildAnnotatedString {
                    withLink(
                        LinkAnnotation.Url(
                            result.url,
                            TextLinkStyles(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        )
                    ) { append(label) }
                },
                style = MaterialTheme.typography.bodyLarge
            )

            label != null -> Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }
        result.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            result.snippets.forEach { snippet ->
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (result.snippets.isNotEmpty()) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    stringResource(
                        if (expanded) {
                            R.string.search_result_show_less
                        } else {
                            R.string.search_result_read_more
                        }
                    )
                )
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
