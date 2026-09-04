package works.resolve.pathfinder.ui.chat

enum class TreeFilter {
    DEFAULT,
    USER_ONLY
}

enum class TreeConnector {
    /** Root row, or a continuation of a single-child chain: no connector. */
    NONE,

    /** Parent branches and later siblings follow below this row (├─). */
    TEE,

    /** Parent branches and this row is its last visible child (└─). */
    ELBOW
}

/**
 * The originating call of a tool-result row, resolved by call id: the
 * panel titles the row from it exactly like the chat's tool rows
 * ("Searched for …"/"Fetched …", else the bare tool name).
 */
data class TreeToolCall(
    val name: String,
    /** Parsed title argument; null when the tool has no title spec. */
    val input: String? = null
)

data class TreeRow(
    /** Entry id; stable key for the row and for fold state. */
    val id: String,
    /**
     * Ancestor entry ids of this row, root first, ending with this row's own
     * id; the panel hides a row when any element but the last is folded.
     */
    val path: List<String>,
    /** Display indent level; the panel renders one guide cell per level. */
    val indent: Int,
    val connector: TreeConnector,
    /**
     * Guide levels where an ancestor branch continues below this row: those
     * cells render │ while the ancestor's later siblings follow below.
     */
    val gutters: List<Int>,
    /** True when this entry lies on the root→current-leaf path. */
    val isOnActivePath: Boolean,
    val isCurrentLeaf: Boolean,
    /**
     * True when this row can be folded: it has visible children and starts a
     * segment (a root, or a child of a branch point).
     */
    val isFoldable: Boolean,
    /** Single-line preview: role-prefixed, whitespace-normalized, bounded. */
    val preview: String,
    /** Non-null only on tool-result rows; drives the rendered row title. */
    val toolCall: TreeToolCall? = null
)
