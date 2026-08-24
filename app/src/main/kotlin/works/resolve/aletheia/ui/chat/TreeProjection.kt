package works.resolve.aletheia.ui.chat

import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ToolResultMessage
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.data.sessions.Conversation
import works.resolve.aletheia.data.sessions.MessageEntry
import works.resolve.aletheia.data.sessions.SessionEntry

/**
 * Pure projection of a [Conversation] into flat, renderable [TreeRow]s,
 * mirroring pi's tree-selector semantics (reduced to aletheia's two filters):
 *
 * - Ordering: depth-first; among siblings the subtree containing the active
 *   leaf comes first, then oldest-first (so the active path always reads as
 *   an unbroken top-down run).
 * - Depth: indent +1 only at branch points (a node with more than one
 *   *visible* child); single-child chains stay at the parent's depth.
 * - [TreeFilter.USER_ONLY] hides non-user rows and recomputes depths and
 *   branch points over the visible set: a hidden node's visible descendants
 *   re-parent to their nearest visible ancestor (pi's
 *   recalculateVisualStructure idea).
 *
 * The function is pure: it reads only its arguments and never touches UI or
 * persistence state.
 */
internal fun buildTreeRows(conversation: Conversation, filter: TreeFilter): List<TreeRow> {
    if (conversation.entries.isEmpty()) return emptyList()

    val byId = conversation.entries.associateBy { it.id }
    val activePathIds = conversation.activeEntries().mapTo(mutableSetOf()) { it.id }
    val leafId = conversation.leafId

    fun isVisible(entry: SessionEntry): Boolean = when (filter) {
        TreeFilter.DEFAULT -> entry is MessageEntry
        TreeFilter.USER_ONLY -> entry is MessageEntry && entry.message is UserMessage
    }

    // Visible tree: each visible entry attaches to its nearest visible
    // ancestor (skipping hidden ancestors), roots keep tree() order.
    val visibleChildren = HashMap<String?, MutableList<SessionEntry>>()
    val roots = ArrayList<SessionEntry>()
    for (entry in conversation.entries) {
        if (!isVisible(entry)) continue
        var ancestorId = entry.parentId
        var attachedTo: String? = null
        while (ancestorId != null) {
            val ancestor = byId[ancestorId]
            if (ancestor == null) break // orphan: promoted to a root
            if (isVisible(ancestor)) {
                attachedTo = ancestor.id
                break
            }
            ancestorId = ancestor.parentId
        }
        if (attachedTo == null) {
            roots += entry
        } else {
            visibleChildren.getOrPut(attachedTo) { mutableListOf() } += entry
        }
    }
    roots.sortBy { it.timestamp }
    visibleChildren.values.forEach { it.sortBy { e -> e.timestamp } }

    // Whether each visible entry's subtree contains the active leaf. Because
    // entries are append-only (a parent always precedes its children), a
    // reverse scan visits every child before its parent — an iterative
    // post-order, no recursion regardless of chain depth.
    val containsActive = HashMap<String, Boolean>()
    for (entry in conversation.entries.asReversed()) {
        if (!isVisible(entry)) continue
        var has = leafId != null && entry.id == leafId
        for (child in visibleChildren[entry.id] ?: emptyList()) {
            if (containsActive[child.id] == true) has = true
        }
        containsActive[entry.id] = has
    }
    fun activeFirst(entries: List<SessionEntry>): List<SessionEntry> =
        entries.sortedBy { containsActive[it.id] != true }

    // Iterative pre-order DFS; children ordered active-branch-first, then
    // oldest-first (stable sort over the timestamp-ordered lists).
    data class Frame(val entry: SessionEntry, val depth: Int, val path: List<String>)

    val rows = ArrayList<TreeRow>(conversation.entries.size)
    val stack = ArrayDeque<Frame>()
    for (root in activeFirst(roots).asReversed()) {
        stack.addLast(Frame(root, 0, listOf(root.id)))
    }
    while (stack.isNotEmpty()) {
        val (entry, depth, path) = stack.removeLast()
        val children = activeFirst(visibleChildren[entry.id] ?: emptyList())
        rows += TreeRow(
            id = entry.id,
            path = path,
            depth = depth,
            isOnActivePath = entry.id in activePathIds,
            isCurrentLeaf = entry.id == leafId,
            isUser = entry is MessageEntry && entry.message is UserMessage,
            isBranchPoint = children.size > 1,
            preview = entry.previewOf(),
        )
        // Branch points indent their children; single-child chains stay flat.
        val childDepth = if (children.size > 1) depth + 1 else depth
        for (child in children.asReversed()) {
            stack.addLast(Frame(child, childDepth, path + child.id))
        }
    }
    return rows
}

/** Whitespace-normalized first line of the entry's text content, bounded. */
private fun SessionEntry.previewOf(): String {
    if (this !is MessageEntry) return "(no content)"
    val prefix = when (message) {
        is UserMessage -> "You"
        is AssistantMessage -> "Assistant"
        is ToolResultMessage -> "Tool"
    }
    val body = when {
        message is AssistantMessage && message.errorMessage != null -> message.errorMessage!!
        else -> when (val m = message) {
            is UserMessage -> m.content
            is AssistantMessage -> m.content
            is ToolResultMessage -> emptyList()
        }.asSequence()
            .filterIsInstance<TextContent>()
            .joinToString("") { it.text }
    }
    val normalized = body
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(PREVIEW_MAX_LENGTH)
    return "$prefix: ${normalized.ifEmpty { "(no content)" }}"
}

private const val PREVIEW_MAX_LENGTH = 120
