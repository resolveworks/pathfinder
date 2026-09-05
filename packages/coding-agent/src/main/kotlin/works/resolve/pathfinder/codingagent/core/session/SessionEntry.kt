package works.resolve.pathfinder.codingagent.core.session

import kotlinx.serialization.json.JsonElement
import works.resolve.pathfinder.agent.CompactionDetails
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Usage

/** A node in a session's conversation tree; [parentId] is null for roots. */
sealed class SessionEntry {
    abstract val id: String
    abstract val parentId: String?

    /** Epoch millis. */
    abstract val timestamp: Long
}

/** An entry carrying a chat [message]. */
data class MessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val message: Message
) : SessionEntry()

/**
 * A compaction cut: the summary replacing the compacted history and the id
 * of the first entry kept after it (entries between the previous leaf path
 * root and that id are summarized away).
 */
data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Int,
    /** File-operation details of the compacted history. */
    val details: CompactionDetails? = null,
    /** Usage from the LLM call(s) that generated the summary. */
    val usage: Usage? = null
) : SessionEntry()

/**
 * A recorded model switch: the provider + model that become the branch's
 * effective configuration from this entry onward, folded root→leaf by
 * [Conversation.effectiveConfiguration].
 */
data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val provider: String,
    val modelId: String
) : SessionEntry()

/** A recorded thinking-level switch, folded like [ModelChangeEntry]. */
data class ThinkingLevelEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    /** pi's ThinkingLevel wire string, e.g. "off", "high". */
    val thinkingLevel: String
) : SessionEntry()

/** A branch summarization cut: summarizes the branch segment starting at [fromId]. */
data class BranchSummaryEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val fromId: String,
    val summary: String,
    val details: JsonElement? = null,
    /** Usage from the LLM call(s) that generated the summary. */
    val usage: Usage? = null
) : SessionEntry()
