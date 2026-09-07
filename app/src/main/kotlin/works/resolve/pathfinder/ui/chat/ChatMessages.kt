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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.ui.chat.markdown.MarkdownText
import works.resolve.pathfinder.ui.theme.PathfinderTheme

private const val STREAMING_PLACEHOLDER = "…"

/** Sampling cadence for rendering the growing streaming partial. */
private const val STREAM_SAMPLE_INTERVAL_MS = 100L

/**
 * Streaming cadence limiter: rendering the partial re-parses its markdown,
 * so per-token recomposition is O(n²) over a response. The partial is
 * sampled at [STREAM_SAMPLE_INTERVAL_MS] instead; once [isStreaming] ends
 * the caller receives the message as-is, so the final text always renders
 * fully.
 */
@Composable
private fun sampledStreamingMessage(
    message: AssistantMessage,
    isStreaming: Boolean
): AssistantMessage {
    if (!isStreaming) return message
    val latest = remember { mutableStateOf(message) }
    SideEffect { latest.value = message }
    return produceState(initialValue = message) {
        while (true) {
            delay(STREAM_SAMPLE_INTERVAL_MS)
            value = latest.value
        }
    }.value
}

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
                        onOpenOutput = { openToolResultId = row.call.id }
                    )

                    is TranscriptRow.Chat -> when (val message = row.message) {
                        is UserMessage -> UserMessageItem(message)

                        is AssistantMessage -> AssistantMessageItem(
                            message = message,
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
                    val sampled = sampledStreamingMessage(streaming, isStreaming)
                    val hasVisibleText = sampled.content.any {
                        it is TextContent && it.text.isNotBlank()
                    }
                    val hasThinking = sampled.content.any { it is ThinkingContent }
                    AssistantMessageItem(
                        message = if (hasVisibleText || hasThinking ||
                            sampled.errorMessage != null
                        ) {
                            sampled
                        } else {
                            // pi renders tool-call-only assistant messages as
                            // zero lines (the executions show as their own
                            // rows); the placeholder bridges until the call
                            // commits and its tool row appears.
                            sampled.copy(
                                content = listOf(TextContent(STREAMING_PLACEHOLDER))
                            )
                        },
                        isStreaming = true,
                        showThinking = showThinking
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
        is AssistantMessage ->
            message.errorMessage != null ||
                message.content.any {
                    (it is TextContent && it.text.isNotBlank()) || it is ThinkingContent
                }

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
            Text(
                text = message.content.textContent(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * Assistant message: plain full-width markdown with no container, so it
 * reads like a reply rather than a bubble. With showThinking on, thinking
 * blocks render inline and stream as they arrive (pi's shown state); with
 * it off they collapse to [ThinkingLabel] (pi's hidden state, plus a
 * duration once the run completes). An error renders below the body in
 * error color. Content renders in order straight from the runtime message
 * (pi's AssistantMessageComponent does the same single pass): consecutive
 * thinking parts merge into one block, blank parts drop.
 */
@Composable
private fun AssistantMessageItem(
    message: AssistantMessage,
    showThinking: Boolean,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var index = 0
        var seenThinking = false
        while (index < message.content.size) {
            val part = message.content[index]
            when (part) {
                is TextContent -> {
                    part.text.takeIf { it.isNotBlank() }?.let { MarkdownText(markdown = it) }
                    index++
                }

                is ThinkingContent -> {
                    val runStart = index
                    while (index < message.content.size &&
                        message.content[index] is ThinkingContent
                    ) {
                        index++
                    }
                    val merged = message.content.subList(runStart, index)
                        .filterIsInstance<ThinkingContent>()
                        .joinToString("\n\n") { it.thinking }
                        .trim()
                    if (merged.isNotEmpty()) {
                        if (showThinking) {
                            ThinkingText(markdown = merged)
                        } else {
                            ThinkingLabel(
                                // Active only while this run is the message's
                                // growing tail: nothing renderable follows it.
                                active = isStreaming && message.content.drop(index).none {
                                    it is ToolCall ||
                                        (it is TextContent && it.text.isNotBlank())
                                },
                                // usage.reasoning is message-level: only the
                                // first collapsed run shows it, later runs
                                // stay uncounted.
                                tokens = if (seenThinking) 0 else message.usage.reasoning
                            )
                        }
                    }
                    seenThinking = true
                }

                else -> index++
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
    private val formats: Map<String, ToolResultFormat> = mapOf(
        BraveWebSearchTool.NAME to ToolResultFormat.MARKDOWN,
        WebFetchTool.NAME to ToolResultFormat.MARKDOWN
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

/**
 * The one call argument a tool's row title is built from, parsed from the
 * raw JSON arguments string; the argument key comes from the shared
 * [ToolCallTitles] spec table. Null for tools without a spec and for
 * malformed arguments or a missing/empty value.
 */
internal fun toolCallInput(toolName: String, arguments: String): String? {
    val argument = ToolCallTitles.specFor(toolName)?.argument ?: return null
    val parsed =
        runCatching { lenientJson.parseToJsonElement(arguments) }.getOrNull() as? JsonObject
            ?: return null
    return parsed.string(argument)?.takeIf { it.isNotEmpty() }
}

/** Row title: the spec's format filled with the parsed input, else the tool name. */
@Composable
internal fun toolCallTitle(call: ToolCall): String {
    val input = remember(call.id, call.arguments) { toolCallInput(call.name, call.arguments) }
    val spec = ToolCallTitles.specFor(call.name)
    return if (spec != null && input != null) stringResource(spec.format, input) else call.name
}

/**
 * The tree's history-shaped variant, like pi's tree fallback: a result whose
 * call is not in history titles by its result's bare name.
 */
@Composable
internal fun toolCallTitle(call: ToolCall?, fallbackName: String?): String =
    if (call == null) fallbackName ?: "tool" else toolCallTitle(call)

/**
 * One tool execution as a single fixed-height line in a tonal container,
 * distinct from conversation text: the row title (a tool-specific phrase
 * like "Searched for …", else the tool name) and a spinner while running
 * (the call committed but its result has not, and the run is still live —
 * pi's component spins between its start and end events). Rows with output
 * open [ToolOutputSheet] instead of expanding in place: the sheet owns its
 * scroll, starts at the top of the content, and leaves the transcript's
 * layout and scroll position untouched behind it. Per-row, never global
 * (pi's Ctrl+O, exposed as a tap). Error coloring is a native adaptation
 * (pi signals errors through the shell, not text color).
 */
@Composable
private fun ToolCallItem(
    call: ToolCall,
    result: ToolResultMessage?,
    running: Boolean,
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
            Text(
                text = toolCallTitle(call),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = toolCallTitle(call),
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
                when (format) {
                    ToolResultFormat.MARKDOWN -> MarkdownText(
                        markdown = output,
                        color = contentColor
                    )

                    ToolResultFormat.RAW -> Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                }
            }
        }
    }
}

/**
 * pi's hidden thinking state as a non-interactive label line: spinner on
 * the left while the run streams, the message's reported thinking-token
 * count once committed (plain "thought" when the provider reports none).
 * Same tone as the thinking text itself; showing thinking is the setting's
 * job, never a per-block interaction.
 */
@Composable
private fun ThinkingLabel(active: Boolean, tokens: Int) {
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
            text = when {
                active -> stringResource(R.string.thinking_label)
                tokens > 0 -> pluralStringResource(R.plurals.thought_tokens, tokens, tokens)
                else -> stringResource(R.string.thought_label)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * pi's shown thinking state's look: markdown dimmer than the onSurface
 * answer text in both theme variants, italic.
 */
@Composable
private fun ThinkingText(markdown: String, modifier: Modifier = Modifier) {
    MarkdownText(
        markdown = markdown,
        modifier = modifier,
        color = MaterialTheme.colorScheme.outline,
        italic = true
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
                TranscriptRow.Chat(
                    "m2",
                    AssistantMessage(
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
                ),
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
