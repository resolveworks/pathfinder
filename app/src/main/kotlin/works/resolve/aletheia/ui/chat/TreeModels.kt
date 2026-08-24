package works.resolve.aletheia.ui.chat

/**
 * UI-safe projection of one session-tree row, consumed by the tree panel.
 *
 * Rows are produced by a pure projection function over the conversation tree
 * (see TreeProjection.kt); the panel only renders them. All tree semantics —
 * branching structure, active path, filter modes — live in the projection,
 * not in the composable.
 */

/** Filter mode for the tree view, mirroring pi's /tree filters (reduced). */
enum class TreeFilter {
    /** Committed transcript entries: user and assistant messages. */
    DEFAULT,

    /** User messages only. */
    USER_ONLY,
}

/**
 * One flattened tree row.
 */
data class TreeRow(
    /** Entry id; stable key for the row and for fold state. */
    val id: String,
    /**
     * Ancestor entry ids of this row, root first, ending with this row's own
     * id. Used by the panel to hide descendants of folded branch points: a
     * row is hidden when any ancestor (path element except the last) is
     * folded.
     */
    val path: List<String>,
    /** Visual indent level (0 for roots; branch points add levels). */
    val depth: Int,
    /** True when this entry lies on the root→current-leaf path. */
    val isOnActivePath: Boolean,
    /** True when this entry is the conversation's current leaf. */
    val isCurrentLeaf: Boolean,
    /** True for user-message entries (drives the role chip). */
    val isUser: Boolean,
    /** True when this entry has more than one visible child (foldable). */
    val isBranchPoint: Boolean,
    /** Single-line preview: role-prefixed, whitespace-normalized, bounded. */
    val preview: String,
)
