package works.resolve.pathfinder.codingagent.core.session

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message

/** Node of the conversation tree; children are sorted oldest-first. */
data class SessionTreeNode(val entry: SessionEntry, val children: List<SessionTreeNode>)

/**
 * Immutable snapshot of a session's entry tree plus its current leaf —
 * what the app layer reads. All mutations (id minting, leaf moves,
 * persistence) live in [SessionManager]; this type only projects.
 */
class Conversation(val entries: List<SessionEntry>, val leafId: String?) {
    /** The active branch's root→leaf path. */
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

    fun activeMessages(): List<Message> =
        activeEntries().filterIsInstance<MessageEntry>().map { it.message }

    /** The provider+model pair a configuration entry selects. */
    data class SessionModelSelection(val provider: String, val modelId: String)

    /**
     * Branch-effective session configuration: scanning the root→leaf path
     * in order, model/thinking-level entries overwrite their field — and
     * assistant messages also update the model, since their provider/model
     * is what actually ran.
     */
    data class EffectiveConfiguration(
        val model: SessionModelSelection? = null,
        val thinkingLevel: String = "off"
    )

    /** Folds the active root→leaf path into the branch's effective configuration. */
    fun effectiveConfiguration(): EffectiveConfiguration {
        var configuration = EffectiveConfiguration()
        for (entry in activeEntries()) {
            configuration = when (entry) {
                is ModelChangeEntry -> configuration.copy(
                    model = SessionModelSelection(entry.provider, entry.modelId)
                )

                is ThinkingLevelEntry -> configuration.copy(thinkingLevel = entry.thinkingLevel)

                is MessageEntry -> {
                    val assistant = entry.message as? AssistantMessage ?: continue
                    configuration.copy(
                        model = SessionModelSelection(assistant.provider, assistant.model)
                    )
                }

                else -> configuration
            }
        }
        return configuration
    }

    fun entry(id: String): SessionEntry? = entries.firstOrNull { it.id == id }

    /**
     * Tree over all entries. Roots are entries with null or self parentId, or
     * whose parent is missing (orphans are promoted to roots, like pi).
     * Built iteratively rather than by recursion: a linear session makes the
     * tree as deep as the transcript, and pi's getTree() documents the stack
     * overflow recursion causes on deep trees.
     */
    fun tree(): List<SessionTreeNode> {
        val byId = entries.associateBy { it.id }

        fun isRoot(entry: SessionEntry): Boolean {
            val pid = entry.parentId
            return pid == null || pid == entry.id || byId[pid] == null
        }

        val childrenOf = HashMap<String?, MutableList<SessionEntry>>()
        for (entry in entries) {
            if (!isRoot(entry)) childrenOf.getOrPut(entry.parentId!!) { mutableListOf() } += entry
        }

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
