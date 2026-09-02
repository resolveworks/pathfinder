package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.agent.compaction.CompactionDetails
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Usage
import kotlinx.serialization.json.JsonElement

/** A node in a session's conversation tree; [parentId] is null for roots. */
sealed class SessionEntry {
    abstract val id: String

    /**
     * Shared, 1-based, storage-assigned sequence number, one per persisted
     * mutation. Divergence from pi: pi hands storage entries without
     * seq/parentId/timestamp and storage assigns all three on append, while
     * Pathfinder's [Conversation] mints id/parentId/timestamp itself and
     * entries circulate before they are persisted — so `seq = 0` marks a
     * not-yet-persisted entry (pi omits the field instead) and [SessionStore]
     * assigns the real consecutive seq when appending to the mutation log.
     * Replay rejects non-consecutive or non-positive seq.
     */
    abstract val seq: Long

    abstract val parentId: String?
    abstract val timestamp: Long

    abstract fun withSeq(seq: Long): SessionEntry
}

/** An entry carrying a chat [message]. */
data class MessageEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String? = null,
    override val timestamp: Long,
    val message: Message,
    /**
     * Marks a terminal-of-session message (pi's reducer sets it when a
     * tool-batch result requests early termination). Only ever persisted as
     * `true`; null encodes to an absent field.
     */
    val terminate: Boolean? = null,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/**
 * A compaction cut in the conversation tree: the summary replacing the
 * compacted history, the retained recent tail, and compaction metadata.
 * pi's `details?: unknown` is typed as [CompactionDetails].
 */
data class CompactionEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    val summary: String,
    val retainedTail: List<Message>,
    val tokensBefore: Int,
    /** File-operation details of the compacted history. */
    val details: CompactionDetails? = null,
    /** Usage from the LLM call(s) that generated the summary. */
    val usage: Usage? = null,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/**
 * A recorded model switch: the provider + model that become the branch's
 * effective configuration from this entry onward, folded root→leaf by
 * [Conversation.effectiveConfiguration].
 */
data class ModelChangeEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    val provider: String,
    val modelId: String,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/** A recorded thinking-level switch, folded like [ModelChangeEntry]. */
data class ThinkingLevelEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    /** pi's ThinkingLevel string, e.g. "off", "high". */
    val thinkingLevel: String,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/** A recorded active-tools set change, folded like [ModelChangeEntry]. */
data class ActiveToolsEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    /** The active set from this entry onward; empty = all tools off. */
    val activeToolNames: List<String>,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/** A branch summarization cut: summarizes the branch segment starting at [fromId]. */
data class BranchSummaryEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    val fromId: String,
    val summary: String,
    val details: JsonElement? = null,
    /** Usage from the LLM call(s) that generated the summary. */
    val usage: Usage? = null,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/** An extension-owned entry. */
data class CustomEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    val customType: String,
    val data: JsonElement? = null,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}
