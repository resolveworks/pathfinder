package works.resolve.pathfinder.data.sessions

import kotlin.time.Clock
import works.resolve.pathfinder.agent.compaction.CompactionDetails
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.utils.uuidv7

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
 * Seq-assignment seam (pi's storage split, packages/agent/src/harness/
 * session/jsonl/storage.ts appendEntry): pi's storage assigns an entry's
 * parentId (the appending lane's leaf), seq, and timestamp on append. Here
 * the [Conversation] is the live tree and mints id/parentId/timestamp at
 * append time (its leaf is the single "main" lane's leaf, so parentId
 * matches what pi's storage would assign); the storage-assigned seq stays
 * 0 until [SessionStore] persists the entry. The store validates the same
 * invariants pi's storage enforces (unused id, parent existence, chaining
 * to the lane leaf).
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

    /**
     * Appends a compaction cut as a child of the current leaf and advances
     * the leaf to it (pi's sessionManager.appendCompaction, session-manager.ts
     * ~1098). Divergences: upstream stores `firstKeptEntryId`/`fromHook` on
     * the entry — pathfinder's [CompactionEntry] keeps the retained tail
     * directly (harness entry shape) and has no extension producers, so both
     * parameters are absent.
     */
    fun appendCompaction(
        summary: String,
        retainedTail: List<Message>,
        tokensBefore: Int,
        details: CompactionDetails? = null,
        usage: Usage? = null,
        /** Pre-minted entry id, for producers that record it up front (pi's compaction operation intent names its resultEntryId before the work runs). */
        id: String? = null,
    ): Conversation {
        val entry = CompactionEntry(
            id = id ?: nextId(),
            parentId = leafId,
            timestamp = clock.now().toEpochMilliseconds(),
            summary = summary,
            retainedTail = retainedTail,
            tokensBefore = tokensBefore,
            details = details,
            usage = usage,
        )
        return Conversation(entries + entry, entry.id, nextId, clock)
    }

    /**
     * Appends a model change as child of the current leaf and advances the
     * leaf to it (pi's sessionManager.appendModelChange, session-manager.ts
     * ~1084).
     */
    fun appendModelChange(provider: String, modelId: String): Conversation {
        val entry = ModelChangeEntry(
            id = nextId(),
            parentId = leafId,
            timestamp = clock.now().toEpochMilliseconds(),
            provider = provider,
            modelId = modelId,
        )
        return Conversation(entries + entry, entry.id, nextId, clock)
    }

    /**
     * Appends a thinking-level change as child of the current leaf and
     * advances the leaf to it (pi's sessionManager.appendThinkingLevelChange,
     * session-manager.ts ~1071). [thinkingLevel] is pi's wire string
     * (see [works.resolve.pathfinder.ai.core.ModelThinkingLevel.wire]).
     */
    fun appendThinkingLevelChange(thinkingLevel: String): Conversation {
        val entry = ThinkingLevelEntry(
            id = nextId(),
            parentId = leafId,
            timestamp = clock.now().toEpochMilliseconds(),
            thinkingLevel = thinkingLevel,
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

    /**
     * The provider+model pair a configuration entry selects (pi's inline
     * `{ provider, modelId }` in harness session types).
     */
    data class SessionModelSelection(val provider: String, val modelId: String)

    /**
     * Branch-effective session configuration, pi's deriveSessionContextState
     * (harness/session/context.ts) — the same fold as the reducer's
     * deriveEffectiveConfiguration (harness/reducer.ts ~398): scanning the
     * root→leaf path in order, model_change / thinking_level_change /
     * active_tools_change entries overwrite the corresponding field, and
     * assistant message entries also update the model (their provider/model
     * is what actually ran). Defaults mirror pi's: thinkingLevel "off",
     * model and activeToolNames unset.
     */
    data class EffectiveConfiguration(
        val model: SessionModelSelection? = null,
        val thinkingLevel: String = "off",
        val activeToolNames: List<String>? = null,
    )

    /** Folds the active root→leaf path into the branch's effective configuration. */
    fun effectiveConfiguration(): EffectiveConfiguration {
        var configuration = EffectiveConfiguration()
        for (entry in activeEntries()) {
            configuration = when (entry) {
                is ModelChangeEntry -> configuration.copy(
                    model = SessionModelSelection(entry.provider, entry.modelId),
                )
                is ThinkingLevelEntry -> configuration.copy(thinkingLevel = entry.thinkingLevel)
                is ActiveToolsEntry -> configuration.copy(activeToolNames = entry.activeToolNames.toList())
                is MessageEntry -> {
                    val assistant = entry.message as? AssistantMessage ?: continue
                    configuration.copy(
                        model = SessionModelSelection(assistant.provider, assistant.model),
                    )
                }
                else -> configuration
            }
        }
        return configuration
    }


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

    companion object {
        /** Builds a linearly chained conversation from a flat transcript
         * (each message parented to the previous, leaf = last); used by
         * callers that still hold flat transcripts. */
        fun fromMessages(messages: List<Message>): Conversation {
            var conversation = Conversation(emptyList(), null)
            for (message in messages) {
                val entry = MessageEntry(
                    id = uuidv7(),
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
