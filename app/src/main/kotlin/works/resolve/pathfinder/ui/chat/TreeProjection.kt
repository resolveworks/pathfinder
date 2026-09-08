package works.resolve.pathfinder.ui.chat

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.codingagent.core.MessageEntry
import works.resolve.pathfinder.codingagent.core.SessionEntry
import works.resolve.pathfinder.codingagent.core.SessionTreeNode

/**
 * Projects a session tree (pi's getTree() roots with its current
 * [leafId]) into flat, renderable [TreeRow]s, mirroring pi's tree
 * selector (reduced to two filters): a hidden
 * entry's visible descendants
 * re-parent to their nearest visible ancestor and indent, connectors, and
 * gutters are recomputed over the visible tree, like pi's
 * recalculateVisualStructure. Containment runs over the full tree, so a
 * hidden leaf (user-only filter) still prioritizes its ancestors.
 *
 * Display rules ported from pi's flattenTree: the active subtree sorts
 * first among siblings; branch points indent their children one level, as
 * does the first generation below a branch (visual grouping), while
 * single-child chains stay flat; multiple roots act as children of a
 * virtual branching root, rendering unshifted without connectors.
 */
internal fun buildTreeRows(
    roots: List<SessionTreeNode>,
    leafId: String?,
    filter: TreeFilter
): List<TreeRow> {
    if (roots.isEmpty()) return emptyList()

    // Flatten pre-order once: containment, lookups, and the active-path
    // walk all run over the same nodes (pi's selector flattens first).
    val allNodes = ArrayList<SessionTreeNode>(roots.size)
    val preOrder = ArrayDeque<SessionTreeNode>()
    for (root in roots.asReversed()) preOrder.addLast(root)
    while (preOrder.isNotEmpty()) {
        val node = preOrder.removeLast()
        allNodes += node
        for (child in node.children.asReversed()) preOrder.addLast(child)
    }

    // pi's toolCallMap: a tool-result row titles itself from its originating
    // call, which may survive only in history.
    val toolCalls = HashMap<String, ToolCall>()
    for (node in allNodes) {
        val message = (node.entry as? MessageEntry)?.message as? AssistantMessage ?: continue
        for (part in message.content) {
            if (part is ToolCall) toolCalls[part.id] = part
        }
    }

    val byId = HashMap<String, SessionEntry>(allNodes.size)
    for (node in allNodes) byId[node.entry.id] = node.entry

    // pi's buildActivePath: walk parent links from the leaf.
    val activePathIds = HashSet<String>()
    var pathCursor = leafId
    while (pathCursor != null) {
        activePathIds += pathCursor
        pathCursor = byId[pathCursor]?.parentId
    }

    fun isVisible(entry: SessionEntry): Boolean = when (filter) {
        // Bookkeeping entries (compaction cuts, model_change, ...) elide in
        // both filters, as in pi's default view.
        TreeFilter.DEFAULT -> entry is MessageEntry

        TreeFilter.USER_ONLY -> entry is MessageEntry && entry.message is UserMessage
    }

    // Containment over the full node tree, hidden nodes included.
    val containsActive = HashMap<String, Boolean>()
    for (node in allNodes.asReversed()) {
        var has = leafId != null && node.entry.id == leafId
        for (child in node.children) {
            if (containsActive[child.entry.id] == true) has = true
        }
        containsActive[node.entry.id] = has
    }

    // Stable sort: tree()'s timestamp order breaks ties.
    fun activeFirst(nodes: List<SessionTreeNode>): List<SessionTreeNode> =
        nodes.sortedBy { containsActive[it.entry.id] != true }

    // pi's recalculateVisualStructure: visible nodes re-parent in flatten
    // order, so the visible-children lists inherit the active-first order.
    val visibleChildren = HashMap<String?, MutableList<SessionEntry>>()
    val flatten = ArrayDeque<SessionTreeNode>()
    for (root in activeFirst(roots).asReversed()) flatten.addLast(root)
    while (flatten.isNotEmpty()) {
        val node = flatten.removeLast()
        if (isVisible(node.entry)) {
            var ancestorId = node.entry.parentId
            var attachedTo: String? = null
            while (ancestorId != null) {
                val ancestor = byId[ancestorId] ?: break // orphan: promoted to a root
                if (isVisible(ancestor)) {
                    attachedTo = ancestor.id
                    break
                }
                ancestorId = ancestor.parentId
            }
            visibleChildren.getOrPut(attachedTo) { mutableListOf() } += node.entry
        }
        for (child in activeFirst(node.children).asReversed()) flatten.addLast(child)
    }

    val visibleRoots = visibleChildren[null].orEmpty()
    val multipleRoots = visibleRoots.size > 1

    // pi computes one level deeper under the virtual root and shifts left
    // at render; rows and gutters are stored at display levels directly.
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
        val gutters: List<Int>
    )

    val rows = ArrayList<TreeRow>(allNodes.size)
    val stack = ArrayDeque<Frame>()
    for (index in visibleRoots.indices.reversed()) {
        val root = visibleRoots[index]
        stack.addLast(
            Frame(
                entry = root,
                path = listOf(root.id),
                internalIndent = if (multipleRoots) 1 else 0,
                justBranched = multipleRoots,
                isRoot = true,
                connector = TreeConnector.NONE,
                isLast = index == visibleRoots.lastIndex,
                gutters = emptyList()
            )
        )
    }
    while (stack.isNotEmpty()) {
        val frame = stack.removeLast()
        val children = visibleChildren[frame.entry.id].orEmpty()
        val multipleChildren = children.size > 1
        rows += TreeRow(
            id = frame.entry.id,
            path = frame.path,
            indent = displayIndent(frame.internalIndent),
            connector = frame.connector,
            gutters = frame.gutters,
            isOnActivePath = frame.entry.id in activePathIds,
            isCurrentLeaf = frame.entry.id == leafId,
            // pi's isFoldable: segment starts (roots, branch children) with
            // visible children.
            isFoldable = children.isNotEmpty() && (frame.isRoot || frame.justBranched),
            body = frame.entry.rowBody(toolCalls)
        )
        val childIndent = when {
            multipleChildren -> frame.internalIndent + 1
            frame.justBranched && frame.internalIndent > 0 -> frame.internalIndent + 1
            else -> frame.internalIndent
        }
        val childGutters = if (frame.connector != TreeConnector.NONE && !frame.isLast) {
            frame.gutters + maxOf(0, displayIndent(frame.internalIndent) - 1)
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
                    gutters = childGutters
                )
            )
        }
    }
    return rows
}

private fun SessionEntry.rowBody(toolCalls: Map<String, ToolCall>): TreeRowBody {
    if (this !is MessageEntry) return TreeRowBody.NoContent
    return when (val entryMessage = message) {
        is ToolResultMessage -> TreeRowBody.Tool(
            name = entryMessage.toolName,
            // pi's tree holds the originating call and formats the title at
            // render; a result whose call is not in history falls back to
            // the bare name.
            call = toolCalls[entryMessage.toolCallId]
        )

        is UserMessage -> preview("You", entryMessage.content.textContent())

        is AssistantMessage -> preview(
            "Assistant",
            entryMessage.errorMessage ?: entryMessage.content.textContent()
        )
    }
}

private fun preview(prefix: String, body: String): TreeRowBody {
    val normalized = body
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(PREVIEW_MAX_LENGTH)
    return if (normalized.isEmpty()) {
        TreeRowBody.NoContent
    } else {
        TreeRowBody.Text("$prefix: $normalized")
    }
}

private const val PREVIEW_MAX_LENGTH = 120
