package works.resolve.pathfinder.data.sessions

import ai.koog.prompt.message.Message
import kotlin.time.Clock

/** Node of the conversation tree; children are sorted oldest-first. */
data class SessionTreeNode(
    val entry: SessionEntry,
    val children: List<SessionTreeNode>,
)

/**
 * Immutable conversation tree porting pi's SessionManager semantics.
 * Operations return new instances (UDF-friendly). The invariant shared by all
 * append methods: a new entry's parentId is the current [leafId]; appending
 * advances the leaf to the new entry. Entry ids default to time-ordered
 * UUIDv7, pi's Session idGenerator default `{ next: () => uuidv7() }`
 * (agent/src/harness/session/session.ts).
 *
 * Messages are Koog `ai.koog.prompt.message.Message` values; this class is
 * the one boundary where the session tree encodes Koog history.
 */
class Conversation(
    val entries: List<SessionEntry>,
    val leafId: String?,
    idGenerator: () -> String = ::uuidv7,
    clock: Clock = Clock.System,
) {
    private val nextId: () -> String = idGenerator
    private val clock: Clock = clock

    /** Appends [message] as a child of the current leaf (or as a root when the
     * leaf is null) and advances the leaf to the new entry. */
    fun append(message: Message): Conversation {
        val entry = MessageEntry(
            id = nextId(),
            parentId = leafId,
            timestamp = clock.now().toEpochMilliseconds(),
            message = message,
        )
        return Conversation(entries + entry, entry.id, nextId, clock)
    }

    /** Moves the leaf to [entryId]; throws [IllegalArgumentException] when unknown. */
    fun branch(entryId: String): Conversation {
        require(entries.any { it.id == entryId }) { "Unknown entry id: $entryId" }
        return Conversation(entries, entryId, nextId, clock)
    }

    /** Clears the leaf; the next append starts a new root. */
    fun resetLeaf(): Conversation = Conversation(entries, null, nextId, clock)

    /** Root→leaf path (pi's getBranch), in conversation order. */
    fun activeEntries(): List<SessionEntry> {
        val byId = entries.associateBy { it.id }
        val path = ArrayDeque<SessionEntry>()
        var current = leafId?.let(byId::get)
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            path.addFirst(current)
            current = current.parentId?.let(byId::get)
        }
        return path.toList()
    }

    /** Messages along the active root→leaf path, in order. */
    fun activeMessages(): List<Message> =
        activeEntries().filterIsInstance<MessageEntry>().map { it.message }

    /** Entry lookup by id. */
    fun entry(id: String): SessionEntry? = entries.firstOrNull { it.id == id }

    /**
     * Tree over all entries. Roots are entries with null or self parentId, or
     * whose parent is missing (orphans get promoted to roots, like pi).
     * Children are sorted oldest-first by timestamp.
     *
     * Built iteratively (leaves-first) rather than with recursion: a linear
     * session makes the tree as deep as the transcript, and pi's getTree()
     * documents the stack overflow that recursion causes on deep trees.
     */
    fun tree(): List<SessionTreeNode> {
        val byId = entries.associateBy { it.id }

        fun isRoot(entry: SessionEntry): Boolean {
            val pid = entry.parentId
            return pid == null || pid == entry.id || byId[pid] == null
        }

        // Children grouped by (existing) parent id.
        val childrenOf = HashMap<String?, MutableList<SessionEntry>>()
        for (entry in entries) {
            if (!isRoot(entry)) childrenOf.getOrPut(entry.parentId!!) { mutableListOf() } += entry
        }

        // Leaves-first pass: create each node once all of its children's
        // subtrees exist, so no recursion is needed regardless of depth.
        val pending = childrenOf.mapValues { it.value.size }.toMutableMap()
        val subtrees = HashMap<String, SessionTreeNode>()
        val rootNodes = ArrayList<SessionTreeNode>()
        val stack = ArrayDeque(entries.filter { (pending[it.id] ?: 0) == 0 })
        while (stack.isNotEmpty()) {
            val entry = stack.removeLast()
            val children = (childrenOf[entry.id] ?: emptyList())
                .mapNotNull { subtrees[it.id] }
                .sortedBy { it.entry.timestamp }
            val node = SessionTreeNode(entry, children)
            if (isRoot(entry)) {
                rootNodes += node
            } else {
                val pid = entry.parentId!!
                subtrees[entry.id] = node
                val remaining = (pending[pid] ?: 0) - 1
                pending[pid] = remaining
                if (remaining == 0) stack.addLast(byId.getValue(pid))
            }
        }
        return rootNodes.sortedBy { it.entry.timestamp }
    }
}
