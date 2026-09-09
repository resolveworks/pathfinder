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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.codingagent.core.tools.BashTool
import works.resolve.pathfinder.codingagent.core.tools.EditTool
import works.resolve.pathfinder.codingagent.core.tools.ReadTool
import works.resolve.pathfinder.codingagent.core.tools.WriteTool
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.ui.theme.PathfinderTheme

private const val STREAMING_PLACEHOLDER = "…"

/** Cap on the in-row bash partial preview; the full output opens in the sheet. */
private const val PARTIAL_OUTPUT_MAX_LINES = 4

@Composable
internal fun ConversationContent(
    uiState: ChatUiState,
    scrollState: TranscriptScrollState,
    modifier: Modifier = Modifier
) {
    val listState = scrollState.listState
    FollowTranscriptBottom(scrollState)
    val messageCount = uiState.messages.size
    val renderableMessages = remember(uiState.messages) {
        uiState.messages.filter(TranscriptRow::hasRenderableContent)
    }

    // Opened-viewer view state, by tool call id. Ephemeral state resolved
    // against the live rows, so a stale key (session switch, branch
    // navigation) just closes the sheet.
    var openToolResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val openToolRow = remember(uiState.messages, openToolResultId) {
        val id = openToolResultId
        if (id == null) {
            null
        } else {
            uiState.messages.filterIsInstance<TranscriptRow.Tool>().firstOrNull { it.call.id == id }
        }
    }

    // Narrowed reads so the item lambdas capture only stable primitives;
    // capturing uiState would re-invalidate every visible row per token.
    val isStreaming = uiState.isStreaming
    val showThinking = uiState.showThinking
    val toolPartials = uiState.toolPartials
    val streamingBlocks = uiState.streamingBlocks

    Box(modifier = modifier.fillMaxSize()) {
        if (messageCount == 0 && uiState.streamingMessage == null) {
            EmptyStateText(text = stringResource(R.string.chat_empty))
        }
        // Forward layout anchors the TOP of a visible message, so appending
        // text below the reader does not move their position within that row.
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            modifier = Modifier.fillMaxSize().nestedScroll(scrollState.nestedScrollConnection)
        ) {
            items(
                renderableMessages,
                key = TranscriptRow::id,
                contentType = { it::class }
            ) { row ->
                when (row) {
                    is TranscriptRow.Compacted -> CompactedDivider()

                    is TranscriptRow.Tool -> ToolCallItem(
                        call = row.call,
                        result = row.result,
                        running = row.result == null && isStreaming,
                        partialOutput = if (row.result == null) toolPartials[row.call.id] else null,
                        onOpenOutput = { openToolResultId = row.call.id }
                    )

                    is TranscriptRow.Chat -> when (val message = row.message) {
                        is UserMessage -> UserMessageItem(message)

                        is AssistantMessage -> AssistantMessageItem(
                            message = message,
                            blocks = row.blocks,
                            showThinking = showThinking
                        )

                        // The projection never emits result messages as rows:
                        // they render through their call's Tool row.
                        is ToolResultMessage -> Unit
                    }
                }
            }
            uiState.streamingMessage?.let { streaming ->
                item(key = "streaming") {
                    val hasVisibleText = streaming.content.any {
                        it is TextContent && it.text.isNotBlank()
                    }
                    val hasThinking = streaming.content.any { it is ThinkingContent }
                    AssistantMessageItem(
                        message = if (hasVisibleText || hasThinking ||
                            streaming.errorMessage != null
                        ) {
                            streaming
                        } else {
                            // pi renders tool-call-only assistant messages as
                            // zero lines (the executions show as their own
                            // rows); the placeholder bridges until the call
                            // commits and its tool row appears.
                            streaming.copy(
                                content = listOf(TextContent(STREAMING_PLACEHOLDER))
                            )
                        },
                        isStreaming = true,
                        showThinking = showThinking,
                        blocks = streamingBlocks
                    )
                }
            }
            item(key = "transcript-bottom-anchor") {
                Spacer(Modifier.height(with(LocalDensity.current) { 1f.toDp() }))
            }
        }

        openToolRow?.result?.let { result ->
            ToolOutputSheet(
                call = openToolRow.call,
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
 * Whether the row renders at all: pi renders tool-call-only assistant
 * messages as zero lines (the executions show as their own tool rows), so
 * they are filtered out here; an error keeps its row.
 */
internal fun TranscriptRow.hasRenderableContent(): Boolean = when (this) {
    is TranscriptRow.Compacted -> true

    is TranscriptRow.Tool -> true

    is TranscriptRow.Chat -> when (val message = this.message) {
        is AssistantMessage -> message.errorMessage != null || blocks.isNotEmpty()
        else -> true
    }
}

/**
 * User message: a right-aligned bubble, as in modern chat apps. The start
 * padding caps the bubble width so short messages stay compact and long
 * ones never span the full row.
 */
@Composable
private fun UserMessageItem(message: UserMessage, modifier: Modifier = Modifier) {
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
            SelectionContainer {
                Text(
                    text = message.content.textContent(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/**
 * Assistant message: plain full-width markdown with no container, so it
 * reads like a reply rather than a bubble. Blocks arrive pre-parsed from
 * the projection (see [TranscriptMarkdown]) and render in order (pi's
 * AssistantMessageComponent does the same single pass). With showThinking
 * on, thinking blocks render inline (pi's shown state); with it off they
 * collapse to [ThinkingLabel] (pi's hidden state). An error renders below
 * the body in error color. While streaming, the final text/thinking part
 * is the only growing one; it renders through the renderer's append-only
 * streaming state (re-parsing just the unstable tail), everything before
 * it is final.
 */
@Composable
private fun AssistantMessageItem(
    message: AssistantMessage,
    blocks: List<MarkdownBlock>,
    showThinking: Boolean,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    // One container per message: LazyColumn rows recycle, so a container
    // around the list itself could not span items.
    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Text -> Markdown(
                        state = block.state,
                        modifier = Modifier.fillMaxWidth()
                    )

                    is MarkdownBlock.Thinking -> if (showThinking) {
                        ThinkingText(block.state)
                    } else {
                        ThinkingLabel(active = false)
                    }
                }
            }
            if (isStreaming) {
                // The tail part's index is a stable identity for the
                // per-part streaming state.
                val tailIndex = growingTailIndex(message.content)
                if (tailIndex >= 0) {
                    key(tailIndex) {
                        when (val tail = message.content[tailIndex]) {
                            is TextContent -> StreamingMarkdownBlock(
                                text = tail.text,
                                thinking = false
                            )

                            is ThinkingContent -> if (showThinking) {
                                StreamingMarkdownBlock(text = tail.thinking, thinking = true)
                            } else {
                                ThinkingLabel(active = true)
                            }

                            // Unreachable: tailIndex is only set for text/thinking tails.
                            else -> Unit
                        }
                    }
                }
            }
            message.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * The growing tail of a streaming message, fed into the renderer's
 * append-only streaming state: each update re-parses only the unstable
 * tail of the document, not the whole text.
 */
@Composable
private fun StreamingMarkdownBlock(text: String, thinking: Boolean) {
    val streamingState = rememberStreamingMarkdownState()
    // Message updates grow the part's text in place, so only the new
    // suffix is appended; a shorter or equal text means nothing new.
    LaunchedEffect(text) {
        val appended = streamingState.content.length
        if (text.length > appended) {
            streamingState.append(text.substring(appended))
        }
    }
    Markdown(
        streamingMarkdownState = streamingState,
        colors = if (thinking) {
            markdownColor(text = MaterialTheme.colorScheme.outline)
        } else {
            markdownColor()
        },
        modifier = Modifier.fillMaxWidth()
    )
}

internal enum class ToolResultFormat {
    MONO,

    MARKDOWN
}

/** The two Pathfinder-owned web tools render their result as Markdown; pi
 * has no web tools. Everything else is [ToolResultFormat.MONO] — the port
 * of pi's generic raw terminal-output fallback. */
internal object ToolResultRenderers {
    private val MARKDOWN_TOOLS = setOf(BraveWebSearchTool.NAME, WebFetchTool.NAME)

    fun formatFor(toolName: String): ToolResultFormat =
        if (toolName in MARKDOWN_TOOLS) ToolResultFormat.MARKDOWN else ToolResultFormat.MONO
}

/**
 * The display diff of an edit result, from its details (pi's edit renderer
 * shows the diff); null for non-edit calls or missing details.
 */
internal fun editDiff(result: ToolResultMessage): String? {
    if (result.toolName != EditTool.NAME) return null
    return (result.details as? JsonObject)?.string("diff")?.takeIf { it.isNotEmpty() }
}

/**
 * Row title, in pi's renderCall grammar: tool name (or the `$` prompt for
 * bash) followed by the key argument, plain concatenation. pi's read range
 * and bash timeout suffixes are ported as-is. A missing spec, malformed
 * arguments, or a missing/empty key argument leave the bare tool name.
 */
@Composable
internal fun toolCallTitle(call: ToolCall): String {
    val parsed = remember(call.id, call.arguments) {
        runCatching { lenientJson.parseToJsonElement(call.arguments) }.getOrNull() as? JsonObject
    }
    return when (call.name) {
        BashTool.NAME -> {
            val command = parsed?.string("command")?.takeIf { it.isNotEmpty() } ?: return call.name
            val timeout = parsed?.int("timeout")
            "$ " + command + (if (timeout != null) " (timeout ${timeout}s)" else "")
        }

        ReadTool.NAME -> {
            val path = parsed?.string("path")?.takeIf { it.isNotEmpty() } ?: return call.name
            val offset = parsed?.int("offset")
            val limit = parsed?.int("limit")
            val range = if (offset == null && limit == null) {
                ""
            } else {
                val start = offset ?: 1
                if (limit != null) ":$start-${start + limit - 1}" else ":$start"
            }
            "read " + path + range
        }

        EditTool.NAME, WriteTool.NAME -> {
            val path = parsed?.string("path")?.takeIf { it.isNotEmpty() } ?: return call.name
            call.name + " " + path
        }

        BraveWebSearchTool.NAME -> {
            val query = parsed?.string("query")?.takeIf { it.isNotEmpty() } ?: return call.name
            stringResource(R.string.tool_title_searched_for, query)
        }

        WebFetchTool.NAME -> {
            val url = parsed?.string("url")?.takeIf { it.isNotEmpty() } ?: return call.name
            stringResource(R.string.tool_title_fetched, url)
        }

        else -> call.name
    }
}

/**
 * The tree's history-shaped variant, like pi's tree fallback: a result whose
 * call is not in history titles by its result's bare name.
 */
@Composable
internal fun toolCallTitle(call: ToolCall?, fallbackName: String?): String =
    if (call == null) fallbackName ?: "tool" else toolCallTitle(call)

/**
 * Shared tool-call header: the row title (monospace for bash calls) with the
 * trailing "Failed" label on errors. The title stays on one line; the sheet
 * is about the output.
 */
@Composable
private fun ToolHeader(call: ToolCall, isError: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = toolCallTitle(call),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = if (call.name == BashTool.NAME) FontFamily.Monospace else null,
            color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isError) {
            Text(
                text = stringResource(R.string.tool_status_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * One tool execution as a single fixed-height line in a tonal container,
 * distinct from conversation text: the row title (the call's key argument,
 * else the tool name) and a spinner while running
 * (the call committed but its result has not, and the run is still live —
 * pi's component spins between its start and end events). A running bash
 * call previews its throttled partial output under the title (pi's bash
 * renderer streams it). Rows with output open [ToolOutputSheet] instead
 * of expanding in place: the sheet owns its scroll, starts at the top of
 * the content, and leaves the transcript's layout and scroll position
 * untouched behind it. Per-row, never global (pi's Ctrl+O, exposed as a
 * tap). Error coloring is a native adaptation (pi signals errors through
 * the shell, not text color).
 */
@Composable
private fun ToolCallItem(
    call: ToolCall,
    result: ToolResultMessage?,
    running: Boolean,
    partialOutput: String?,
    onOpenOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    val output = result?.content?.textContent()?.takeIf { it.isNotEmpty() }
    val isError = result?.isError == true
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
            Column(modifier = Modifier.weight(1f)) {
                ToolHeader(call = call, isError = isError)
                partialOutput?.takeIf { it.isNotBlank() }?.let { partial ->
                    Text(
                        text = partial,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = PARTIAL_OUTPUT_MAX_LINES,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/**
 * Tool-result viewer: the whole output in a scrollable modal sheet. A fresh
 * scroll state per open anchors the viewport at the top of the content;
 * dismissing restores the transcript's scroll position because it never moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolOutputSheet(call: ToolCall, result: ToolResultMessage, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val format = ToolResultRenderers.formatFor(call.name)
        val output = remember(result) { result.content.textContent() }
        val contentColor = if (result.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            ToolHeader(call = call, isError = result.isError)
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                val diff = remember(result) { editDiff(result) }
                when {
                    // pi's edit renderer shows the display diff from details,
                    // not the bare result line.
                    diff != null -> Text(
                        text = diff,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = contentColor
                    )

                    format == ToolResultFormat.MARKDOWN -> Markdown(
                        content = output,
                        colors = markdownColor(text = contentColor),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Raw fallback: pi's generic result renderer.
                    else -> Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = contentColor
                    )
                }
            }
        }
    }
}

/**
 * pi's hidden thinking state as a non-interactive label line: spinner on
 * the left while the run streams, a static label once committed. Same
 * tone as the thinking text itself; showing thinking is the setting's
 * job, never a per-block interaction.
 */
@Composable
private fun ThinkingLabel(active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        if (active) {
            CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text(
            text = if (active) {
                stringResource(R.string.thinking_label)
            } else {
                stringResource(R.string.thought_label)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * pi's shown thinking state's look: markdown dimmer than the onSurface
 * answer text in both theme variants.
 */
@Composable
private fun ThinkingText(state: State) {
    Markdown(
        state = state,
        colors = markdownColor(text = MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Full text output of a message: text parts joined with newlines. No
 * truncation at the projection boundary — the viewer scrolls the whole
 * text. (Tree previews normalize whitespace, so their join is equivalent.)
 */
internal fun List<Content>.textContent(): String =
    filterIsInstance<TextContent>().joinToString("\n") { it.text }

@Preview(showBackground = true)
@Composable
private fun ConversationContentThinkingPreview() {
    PathfinderTheme {
        val uiState = ChatUiState(
            status = ChatStatus.Ready,
            // The setting's on-mode renders reasoning inline (pi's shown
            // state), streaming as it arrives.
            showThinking = true,
            messages = listOf(
                TranscriptRow.Chat("m1", UserMessage.ofText("What is 2 + 2?")),
                run {
                    val message = AssistantMessage(
                        content = listOf(
                            ThinkingContent(
                                "The user asks a simple arithmetic question. *2 + 2* equals " +
                                    "**4** — no tools needed."
                            ),
                            TextContent("2 + 2 = **4**."),
                            ThinkingContent(
                                "Answered directly; offering the derivation seems unnecessary."
                            )
                        ),
                        api = "preview",
                        provider = "preview",
                        model = "preview",
                        usage = works.resolve.pathfinder.ai.Usage()
                    )
                    TranscriptRow.Chat(
                        "m2",
                        message,
                        // Previews parse inline: inspection-mode rendering,
                        // like the renderer's own preview defaults.
                        buildMarkdownBlocks(message.content, ::parseMarkdown)
                    )
                },
                TranscriptRow.Tool(
                    "m3:t1",
                    ToolCall(
                        id = "t1",
                        name = "web_search",
                        arguments = """{"query":"arithmetic"}"""
                    ),
                    ToolResultMessage(
                        toolCallId = "t1",
                        toolName = "web_search",
                        content = listOf(
                            TextContent("1. Arithmetic — Wikipedia\n2. Addition — Wikipedia")
                        )
                    )
                )
            )
        )
        ConversationContent(
            uiState = uiState,
            scrollState = rememberTranscriptScrollState(uiState)
        )
    }
}
