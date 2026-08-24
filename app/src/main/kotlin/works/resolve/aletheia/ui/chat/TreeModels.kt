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
 * How a row joins its parent branch point, mirroring pi's tree connectors
 * (tree-selector.ts FlatNode.showConnector/isLast).
 */
enum class TreeConnector {
    /** Root row, or a continuation of a single-child chain: no connector. */
    NONE,

    /** Parent branches and later siblings follow below this row (├─). */
    TEE,

    /** Parent branches and this row is its last visible child (└─). */
    ELBOW,
}

/**
 * One flattened tree row, mirroring pi's tree-selector FlatNode.
 */
data class TreeRow(
    /** Entry id; stable key for the row and for fold state. */
    val id: String,
    /**
     * Ancestor entry ids of this row, root first, ending with this row's own
     * id. Used by the panel to hide descendants of folded rows: a row is
     * hidden when any ancestor (path element except the last) is folded.
     */
    val path: List<String>,
    /**
     * Display indent level; each level renders as one three-character guide
     * cell. Pi's rules: single-child chains stay at their parent's level,
     * branch points indent their children — and the first generation below a
     * branch indents once more for visual grouping.
     */
    val indent: Int,
    /** Connector joining this row to its parent branch point. */
    val connector: TreeConnector,
    /**
     * Guide levels where an ancestor branch continues below this row (pi's
     * GutterInfo positions): those cells render │ instead of blanks while
     * later siblings of that ancestor follow below.
     */
    val gutters: List<Int>,
    /** True when this entry lies on the root→current-leaf path. */
    val isOnActivePath: Boolean,
    /** True when this entry is the conversation's current leaf. */
    val isCurrentLeaf: Boolean,
    /**
     * True when this row can be folded: it has visible children and starts a
     * segment (a root, or a child of a branch point). Folding hides the
     * row's own descendants (pi's TreeList.isFoldable).
     */
    val isFoldable: Boolean,
    /** Single-line preview: role-prefixed, whitespace-normalized, bounded. */
    val preview: String,
)
