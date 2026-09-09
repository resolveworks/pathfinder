package works.resolve.pathfinder.ui.chat

import androidx.compose.runtime.Immutable
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent

/**
 * One renderable markdown block of a finalized (no longer growing) part
 * list, pre-parsed so composition never waits on parsing.
 */
@Immutable
sealed interface MarkdownBlock {
    /** The parsed markdown of the block. */
    val state: State

    /** Body text. */
    @Immutable
    data class Text(override val state: State) : MarkdownBlock

    /** One merged run of consecutive thinking parts. */
    @Immutable
    data class Thinking(override val state: State) : MarkdownBlock
}

/**
 * Index of the growing streaming tail — the final text/thinking part,
 * which streaming updates append to in place; -1 when the list is empty
 * or ends with another part kind.
 */
internal fun growingTailIndex(content: List<Content>): Int {
    if (content.isEmpty()) return -1
    return when (content.last()) {
        is TextContent, is ThinkingContent -> content.lastIndex
        else -> -1
    }
}

/**
 * Renderable markdown blocks of [content], in order: consecutive thinking
 * parts merge into one block and blank parts drop (pi's
 * AssistantMessageComponent does the same single pass). [parse] must not
 * suspend.
 */
internal fun buildMarkdownBlocks(
    content: List<Content>,
    parse: (String) -> State
): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < content.size) {
        when (val part = content[index]) {
            is TextContent -> {
                part.text.takeIf { it.isNotBlank() }?.let {
                    blocks.add(MarkdownBlock.Text(parse(it)))
                }
                index++
            }

            is ThinkingContent -> {
                val runStart = index
                while (index < content.size && content[index] is ThinkingContent) {
                    index++
                }
                val merged = content.subList(runStart, index)
                    .filterIsInstance<ThinkingContent>()
                    .joinToString("\n\n") { it.thinking }
                    .trim()
                if (merged.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Thinking(parse(merged)))
                }
            }

            else -> index++
        }
    }
    return blocks
}

/**
 * Content-keyed parse cache for transcript markdown.
 *
 * The renderer's `Markdown(content)` entry point parses asynchronously per
 * composed instance: rows blank until their parse lands, re-parse on every
 * LazyColumn recycle, and re-parse on every recomposition (its default
 * flavour/parser arguments are fresh, never-equal instances). Handing the
 * renderer pre-parsed states instead makes parsing a projection concern —
 * once per content, completed before publication.
 */
internal class TranscriptMarkdown {
    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)
    private val parsed = HashMap<String, State>()

    /**
     * Confined to the ViewModel's serialized flow: every caller runs on the
     * main dispatcher except the single Default-dispatched bulk prefetch,
     * and each call completes without suspending, so accesses never
     * interleave.
     */
    fun parse(content: String): State = parsed.getOrPut(content) {
        parseMarkdown(content, flavour = flavour, parser = parser)
    }

    /** Bounded by the active session's text: dropped on session switch. */
    fun clear() {
        parsed.clear()
    }
}
