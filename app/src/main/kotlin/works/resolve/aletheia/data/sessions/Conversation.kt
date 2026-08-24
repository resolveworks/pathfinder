package works.resolve.aletheia.data.sessions

import java.util.UUID
import works.resolve.aletheia.ai.core.Message

/** Node of the conversation tree; children are sorted oldest-first. */
data class SessionTreeNode(
    val entry: SessionEntry,
    val children: List<SessionTreeNode>,
)

/**
 * Immutable conversation tree porting pi's SessionManager semantics.
 * Operations return new instances (UDF-friendly). The invariant shared by all
 * append methods: a new entry's parentId is the current [leafId]; appending
 * advances the leaf to the new entry.
 */
class Conversation(
    val entries: List<SessionEntry>,
    val leafId: String?,
    idGenerator: () -> String = { UUID.randomUUID().toString() },
    clock: () -> Long = { System.currentTimeMillis() },
) {
    private val nextId: () -> String = idGenerator
    private val now: () -> Long = clock

    /** Appends [message] as a child of the current leaf (or as a root when the
     * leaf is null) and advances the leaf to the new entry. */
    fun append(message: Message): Conversation {
        val entry = MessageEntry(
            id = nextId(),
            parentId = leafId,
            timestamp = now(),
            message = message,
        )
        return Conversation(entries + entry, entry.id, nextId, now)
    }

    /** Moves the leaf to [entryId]; throws [IllegalArgumentException] when unknown. */
    fun branch(entryId: String): Conversation {
        require(entries.any { it.id == entryId }) { "Unknown entry id: $entryId" }
        return Conversation(entries, entryId, nextId, now)
    }

    /** Clears the leaf; the next append starts a new root. */
    fun resetLeaf(): Conversation = Conversation(entries, null, nextId, now)

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
     */
    fun tree(): List<SessionTreeNode> {
        val byId = entries.associateBy { it.id }
        val childrenOf = HashMap<String?, MutableList<SessionEntry>>()
        val roots = ArrayList<SessionEntry>()
        for (entry in entries) {
            val pid = entry.parentId
            when {
                pid == null || pid == entry.id || byId[pid] == null -> roots += entry
                else -> childrenOf.getOrPut(pid) { mutableListOf() } += entry
            }
        }
        fun node(entry: SessionEntry): SessionTreeNode =
            SessionTreeNode(
                entry = entry,
                children = (childrenOf[entry.id] ?: emptyList())
                    .sortedBy { it.timestamp }
                    .map(::node),
            )
        return roots.sortedBy { it.timestamp }.map(::node)
    }

    companion object {
        /** Builds a linearly chained conversation from a flat transcript
         * (each message parented to the previous, leaf = last); used by v1
         * migration and callers that still hold flat transcripts. */
        fun fromMessages(messages: List<Message>): Conversation {
            var conversation = Conversation(emptyList(), null)
            for (message in messages) {
                val id = UUID.randomUUID().toString()
                val entry = MessageEntry(
                    id = id,
                    parentId = conversation.leafId,
                    timestamp = message.timestamp,
                    message = message,
                )
                conversation = Conversation(conversation.entries + entry, entry.id)
            }
            return conversation
        }
    }
}
