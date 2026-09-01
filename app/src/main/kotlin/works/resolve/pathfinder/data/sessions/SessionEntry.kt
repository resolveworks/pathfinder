package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.agent.compaction.CompactionDetails
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Usage
import kotlinx.serialization.json.JsonElement

/**
 * A node in a session's conversation tree, porting pi's harness EntryBase
 * (packages/agent/src/harness/session/types.ts): every entry carries an
 * [id], the storage-assigned shared [seq], a [parentId] (null for roots),
 * and a [timestamp].
 */
sealed class SessionEntry {
    abstract val id: String

    /**
     * Shared, 1-based, storage-assigned sequence number (pi's EntryBase.seq,
     * "shared sequence; read-side, storage-assigned"), one seq per persisted
     * mutation.
     *
     * Divergence from pi's ProvisionedEntry split: pi's callers hand storage
     * entries *without* seq/parentId/timestamp and storage assigns all three
     * on append. Pathfinder's [Conversation] is the live in-memory tree and
     * mints id/parentId/timestamp itself, so entries circulate before they
     * are persisted; `seq = 0` marks a not-yet-persisted entry (pi omits the
     * field instead), and [SessionStore] assigns the real consecutive seq
     * when appending the entry to the mutation log. Replay rejects
     * non-consecutive or non-positive seq.
     */
    abstract val seq: Long

    abstract val parentId: String?
    abstract val timestamp: Long

    /**
     * The same entry with the storage-assigned [SessionEntry.seq]
     * (pi's storage fills in the provisioned entry's seq on append).
     */
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
     * Marks a terminal-of-session message (pi's MessageEntry
     * `terminate?: true`, harness/session/types.ts:27; set by the reducer
     * when a tool-batch result requests early termination). Upstream only
     * ever persists `true`; null encodes to an absent field.
     */
    val terminate: Boolean? = null,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/**
 * A compaction cut in the conversation tree, pi's harness CompactionEntry
 * (packages/agent/src/harness/session/types.ts): the summary replacing the
 * compacted history, the retained recent tail, and compaction metadata.
 * Divergence: upstream's `details?: unknown` is typed as
 * [CompactionDetails] (the only producer).
 */
data class CompactionEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    /** Summary text that replaces the compacted history in future context. */
    val summary: String,
    /** Retained recent messages stored directly on the entry. */
    val retainedTail: List<Message>,
    /** Estimated context tokens before compaction. */
    val tokensBefore: Int,
    /** File-operation details of the compacted history. */
    val details: CompactionDetails? = null,
    /** Usage from the LLM call(s) that generated the summary. */
    val usage: Usage? = null,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/**
 * A recorded model switch, pi's harness ModelChangeEntry
 * (packages/agent/src/harness/session/types.ts): the provider + model that
 * become the branch's effective configuration from this entry onward.
 * Recorded when the user selects a model (pi's agent-session setModel /
 * sdk new-session seeding) and folded root→leaf by
 * [Conversation.effectiveConfiguration].
 */
data class ModelChangeEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    /** Provider id of the newly selected model. */
    val provider: String,
    /** Model id within [provider]. */
    val modelId: String,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/**
 * A recorded thinking-level switch, pi's harness ThinkingLevelEntry
 * (packages/agent/src/harness/session/types.ts). Pathfinder has no
 * thinking-level selection point yet, so the entry type and the
 * [Conversation.effectiveConfiguration] fold are ported but nothing
 * records it (no producer, no consumer).
 */
data class ThinkingLevelEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    /** Selected thinking level (pi's ThinkingLevel string, e.g. "off", "high"). */
    val thinkingLevel: String,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/**
 * A recorded active-tools set change, pi's harness ActiveToolsEntry
 * (packages/agent/src/harness/session/types.ts). Pathfinder has no
 * per-session tool activation yet, so the entry type and the
 * [Conversation.effectiveConfiguration] fold are ported but nothing
 * records it (no producer, no consumer).
 */
data class ActiveToolsEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    /** Tool names active from this entry onward (empty set = all defaults off). */
    val activeToolNames: List<String>,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/**
 * A branch summarization cut, pi's harness BranchSummaryEntry
 * (packages/agent/src/harness/session/types.ts). Pathfinder has no branch
 * summarization feature yet, so the entry type and codec support are ported
 * for shape parity only (no producer, no consumer).
 */
data class BranchSummaryEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    /** Entry id the summary starts from. */
    val fromId: String,
    /** Summary text of the summarized branch segment. */
    val summary: String,
    /** Upstream `details?: unknown`. */
    val details: JsonElement? = null,
    /** Usage from the LLM call(s) that generated the summary. */
    val usage: Usage? = null,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/**
 * An extension-owned entry, pi's harness CustomEntry
 * (packages/agent/src/harness/session/types.ts). Pathfinder has no extension
 * runner, so the entry type and codec support are ported for shape parity
 * only (no producer, no consumer).
 */
data class CustomEntry(
    override val id: String,
    override val seq: Long = 0L,
    override val parentId: String?,
    override val timestamp: Long,
    /** Discriminator for the custom entry kind. */
    val customType: String,
    /** Upstream `data?: unknown`. */
    val data: JsonElement? = null,
) : SessionEntry() {
    override fun withSeq(seq: Long) = copy(seq = seq)
}
