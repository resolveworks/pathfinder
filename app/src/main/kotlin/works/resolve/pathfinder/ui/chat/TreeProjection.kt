package works.resolve.pathfinder.ui.chat

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.SessionEntry

/**
 * Pure projection of a [Conversation] into flat, renderable [TreeRow]s,
 * mirroring pi's tree selector (reduced to pathfinder's two filters):
 *
 * - Ordering: depth-first; among siblings the subtree containing the active
 *   leaf comes first, then oldest-first (so the active path always reads as
 *   an unbroken top-down run).
 * - Indent: branch points indent their children by one level, and so does
 *   the first generation below a branch (pi's justBranched rule, for visual
 *   grouping of the subtree); other single-child chains stay at their
 *   parent's level.
 * - Connectors: every child of a branch point carries ├─ (later siblings
 *   follow) or └─ (last visible sibling).
 * - Gutters: descendants of a ├─ connector keep a │ guide at its level while
 *   the later siblings follow below.
 * - Multiple roots behave as children of a virtual branching root: roots
 *   render unshifted and without connectors, and their descendants indent
 *   one level.
 * - [TreeFilter.USER_ONLY] hides non-user rows; in either filter a hidden
 *   entry's visible descendants re-parent to their nearest visible ancestor.
 */
internal fun buildTreeRows(conversation: Conversation, filter: TreeFilter): List<TreeRow> {
    if (conversation.entries.isEmpty()) return emptyList()

    val byId = conversation.entries.associateBy { it.id }
    val activePathIds = conversation.activeEntries().mapTo(mutableSetOf()) { it.id }
    val leafId = conversation.leafId

    fun isVisible(entry: SessionEntry): Boolean = when (filter) {
        // Bookkeeping entries (compaction cuts, model_change, ...) are
        // elided in both filters, as in pi's default view.
        TreeFilter.DEFAULT -> entry is MessageEntry
        TreeFilter.USER_ONLY -> entry is MessageEntry && entry.message is UserMessage
    }

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

    // Pi orders siblings with the active subtree first (stable, so the
    // timestamp order breaks ties); the last-ordered sibling renders └─.
    fun activeFirst(entries: List<SessionEntry>): List<SessionEntry> =
        entries.sortedBy { containsActive[it.id] != true }

    val orderedRoots = activeFirst(roots)
    val multipleRoots = orderedRoots.size > 1

    // Pi's flattenTree works one level deeper under a virtual branching root
    // and shifts rows one display level left when rendering; rows and
    // gutters are stored here at display levels directly.
    fun displayIndent(internalIndent: Int): Int =
        if (multipleRoots) internalIndent - 1 else internalIndent

    data class Frame(
        val entry: SessionEntry,
        val path: List<String>,
        val internalIndent: Int,
        /** True when this frame's parent branched (roots: the virtual root branched). */
        val justBranched: Boolean,
        val isRoot: Boolean,
        val connector: TreeConnector,
        val isLast: Boolean,
        val gutters: List<Int>,
    )

    val rows = ArrayList<TreeRow>(conversation.entries.size)
    val stack = ArrayDeque<Frame>()
    for (index in orderedRoots.indices.reversed()) {
        val root = orderedRoots[index]
        stack.addLast(
            Frame(
                entry = root,
                path = listOf(root.id),
                internalIndent = if (multipleRoots) 1 else 0,
                justBranched = multipleRoots,
                isRoot = true,
                // Roots render without a connector even under the virtual
                // branching root.
                connector = TreeConnector.NONE,
                isLast = index == orderedRoots.lastIndex,
                gutters = emptyList(),
            ),
        )
    }
    while (stack.isNotEmpty()) {
        val frame = stack.removeLast()
        val children = activeFirst(visibleChildren[frame.entry.id] ?: emptyList())
        val multipleChildren = children.size > 1
        rows += TreeRow(
            id = frame.entry.id,
            path = frame.path,
            indent = displayIndent(frame.internalIndent),
            connector = frame.connector,
            gutters = frame.gutters,
            isOnActivePath = frame.entry.id in activePathIds,
            isCurrentLeaf = frame.entry.id == leafId,
            // Pi's isFoldable: a segment start (root or child of a branch
            // point) with visible children; folding hides its descendants.
            isFoldable = children.isNotEmpty() && (frame.isRoot || frame.justBranched),
            preview = frame.entry.previewOf(),
        )
        val childIndent = when {
            multipleChildren -> frame.internalIndent + 1
            frame.justBranched && frame.internalIndent > 0 -> frame.internalIndent + 1
            else -> frame.internalIndent
        }
        val childGutters = if (frame.connector != TreeConnector.NONE && !frame.isLast) {
            frame.gutters + (displayIndent(frame.internalIndent) - 1)
        } else {
            frame.gutters
        }
        for (index in children.indices.reversed()) {
            val child = children[index]
            val isLast = index == children.lastIndex
            stack.addLast(
                Frame(
                    entry = child,
                    path = frame.path + child.id,
                    internalIndent = childIndent,
                    justBranched = multipleChildren,
                    isRoot = false,
                    connector = if (multipleChildren) {
                        if (isLast) TreeConnector.ELBOW else TreeConnector.TEE
                    } else {
                        TreeConnector.NONE
                    },
                    isLast = isLast,
                    gutters = childGutters,
                ),
            )
        }
    }
    return rows
}

private fun SessionEntry.previewOf(): String {
    if (this !is MessageEntry) return "(no content)"
    val prefix = when (message) {
        is UserMessage -> "You"
        is AssistantMessage -> "Assistant"
        is ToolResultMessage -> "Tool"
    }
    val body = when {
        message is AssistantMessage && message.errorMessage != null -> message.errorMessage
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
