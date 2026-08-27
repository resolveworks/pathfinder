package works.resolve.pathfinder.agent.compaction

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.utils.ContextUsageEstimate
import works.resolve.pathfinder.ai.utils.calculateContextTokens
import works.resolve.pathfinder.ai.utils.estimateMessageTokens
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.SessionEntry

/**
 * Pure core of pi's harness compaction module, ported from
 * `packages/agent/src/harness/compaction/compaction.ts`.
 *
 * This file covers only the declarative/pure parts: settings, token
 * estimation, compaction-threshold decision, cut-point selection, the
 * summarization system prompt, and file-operation extraction. The
 * LLM-calling parts (`generateSummary`, `generateSummaryWithUsage`,
 * `completeSimpleWithRetries`, `prepareCompaction`, `compact`,
 * `CompactResult`, `CompactionPreparation`, `combineUsage`) are ported in a
 * later chunk and are deliberately absent.
 *
 * Adaptation boundaries (documented per symbol below):
 * - pi's `AgentMessage` union maps to pathfinder's `ai.core.Message`
 *   hierarchy; the AgentMessage roles `custom`, `bashExecution`,
 *   `branchSummary`, and `compactionSummary` have no pathfinder counterpart
 *   yet.
 * - pi's harness `Entry` maps to pathfinder's `SessionEntry`, which currently
 *   has only [MessageEntry]; non-message entry kinds (thinking/model changes,
 *   branch summaries, compaction entries) do not exist yet. The cut-point
 *   walk keeps pi's per-entry-type dispatch structure so those variants slot
 *   in without redesign.
 *
 * Reuse decisions: `calculateContextTokens`, per-message token estimation,
 * and `ContextUsageEstimate` already exist in
 * `works.resolve.pathfinder.ai.utils.TokenEstimate` (itself ported from pi's
 * `packages/ai/src/utils/estimate.ts`) with identical semantics for the
 * message roles pathfinder supports, so they are reused rather than
 * duplicated.
 */

/** Compaction thresholds and retention settings (compaction.ts `CompactionSettings`). */
data class CompactionSettings(
    /** Enable automatic compaction decisions. */
    val enabled: Boolean,
    /** Tokens reserved for summary prompt and output. */
    val reserveTokens: Int,
    /** Approximate recent-context tokens to keep after compaction. */
    val keepRecentTokens: Int,
)

/** Default compaction settings used by the harness (compaction.ts `DEFAULT_COMPACTION_SETTINGS`). */
val DEFAULT_COMPACTION_SETTINGS = CompactionSettings(
    enabled = true,
    reserveTokens = 16384,
    keepRecentTokens = 20000,
)

// calculateContextTokens (compaction.ts) is reused from
// works.resolve.pathfinder.ai.utils.TokenEstimate: same "prefer totalTokens,
// else sum components" semantics.

private fun getAssistantUsage(msg: Message): Usage? {
    if (msg is AssistantMessage) {
        if (
            msg.stopReason != StopReason.ABORTED &&
            msg.stopReason != StopReason.ERROR &&
            calculateContextTokens(msg.usage) > 0
        ) {
            return msg.usage
        }
    }
    return null
}

/** Return usage from the last valid assistant message in session entries (compaction.ts `getLastAssistantUsage`). */
fun getLastAssistantUsage(entries: List<SessionEntry>): Usage? {
    for (i in entries.indices.reversed()) {
        val entry = entries[i]
        if (entry is MessageEntry) {
            val usage = getAssistantUsage(entry.message)
            if (usage != null) return usage
        }
    }
    return null
}

/**
 * Estimate token count for one message using a conservative character
 * heuristic (compaction.ts `estimateTokens`).
 *
 * Delegates to [estimateMessageTokens] in `ai.utils.TokenEstimate`, which
 * implements the same heuristic. Divergence: compaction.ts also estimates
 * `custom`, `bashExecution`, `branchSummary`, and `compactionSummary`
 * message roles, which do not exist in pathfinder's [Message] yet; when a
 * compaction-summary message type is added, extend the estimation there.
 */
fun estimateTokens(message: Message): Int = estimateMessageTokens(message)

private fun getLastAssistantUsageInfo(messages: List<Message>): Pair<Usage, Int>? {
    for (i in messages.indices.reversed()) {
        val usage = getAssistantUsage(messages[i])
        if (usage != null) return usage to i
    }
    return null
}

/**
 * Estimate context tokens for messages using provider usage when available
 * (compaction.ts `estimateContextTokens`).
 *
 * Note: this is pi's compaction-specific estimator over a bare message list;
 * it differs from `ai.utils.estimateContextTokens(Context)`, which mirrors
 * pi's `packages/ai/src/utils/estimate.ts` (system prompt/tools accounting,
 * timestamp-prefix usage validation). Compaction's simpler "last valid
 * assistant usage wins" rule is ported here faithfully.
 */
fun estimateContextTokens(messages: List<Message>): ContextUsageEstimate {
    val usageInfo = getLastAssistantUsageInfo(messages)

    if (usageInfo == null) {
        var estimated = 0
        for (message in messages) {
            estimated += estimateTokens(message)
        }
        return ContextUsageEstimate(
            tokens = estimated,
            usageTokens = 0,
            trailingTokens = estimated,
            lastUsageIndex = null,
        )
    }

    val (usage, index) = usageInfo
    val usageTokens = calculateContextTokens(usage)
    var trailingTokens = 0
    for (i in index + 1 until messages.size) {
        trailingTokens += estimateTokens(messages[i])
    }

    return ContextUsageEstimate(
        tokens = usageTokens + trailingTokens,
        usageTokens = usageTokens,
        trailingTokens = trailingTokens,
        lastUsageIndex = index,
    )
}

/** Return whether context usage exceeds the configured compaction threshold (compaction.ts `shouldCompact`). */
fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings): Boolean {
    if (!settings.enabled) return false
    return contextTokens > contextWindow - settings.reserveTokens
}

/**
 * Collect valid compaction cut points in `[startIndex, endIndex)` (compaction.ts
 * `findValidCutPoints`, private upstream).
 *
 * Adaptation: only [MessageEntry] exists in pathfinder's [SessionEntry]
 * today. As upstream, message entries are cut points for every role except
 * tool results (`bashExecution`/`custom`/`branchSummary`/`compactionSummary`
 * roles would also be cut points but do not exist here). Non-message entry
 * kinds (thinking/model/tools changes, compaction entries) contribute
 * nothing; `branch_summary` entries additionally push their own index
 * upstream — when that entry kind is added, mirror both branches here.
 */
private fun findValidCutPoints(entries: List<SessionEntry>, startIndex: Int, endIndex: Int): List<Int> {
    val cutPoints = mutableListOf<Int>()
    for (i in startIndex until endIndex) {
        val entry = entries[i]
        if (entry is MessageEntry) {
            when (entry.message.role) {
                MessageRole.USER, MessageRole.ASSISTANT -> cutPoints.add(i)
                MessageRole.TOOL_RESULT -> {}
            }
        }
    }
    return cutPoints
}

/**
 * Find the user-visible message that starts the turn containing an entry
 * (compaction.ts `findTurnStartIndex`).
 *
 * Adaptation: `branch_summary` entries do not exist yet; when added, they
 * must return their index like upstream. `bashExecution` role is likewise
 * not present.
 */
fun findTurnStartIndex(entries: List<SessionEntry>, entryIndex: Int, startIndex: Int): Int {
    for (i in entryIndex downTo startIndex) {
        val entry = entries[i]
        if (entry is MessageEntry && entry.message.role == MessageRole.USER) {
            return i
        }
    }
    return -1
}

/** Cut point selected for compaction (compaction.ts `CutPointResult`). */
data class CutPointResult(
    /** Index of the first entry retained after compaction. */
    val firstKeptEntryIndex: Int,
    /** Index of the turn-start entry when the cut splits a turn, otherwise -1. */
    val turnStartIndex: Int,
    /** Whether the selected cut point splits an in-progress turn. */
    val isSplitTurn: Boolean,
)

/**
 * Find the compaction cut point that keeps approximately the requested
 * recent-token budget (compaction.ts `findCutPoint`).
 */
fun findCutPoint(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
    keepRecentTokens: Int,
): CutPointResult {
    val cutPoints = findValidCutPoints(entries, startIndex, endIndex)

    if (cutPoints.isEmpty()) {
        return CutPointResult(startIndex, -1, false)
    }
    var accumulatedTokens = 0
    var cutIndex = cutPoints[0]

    for (i in endIndex - 1 downTo startIndex) {
        val entry = entries[i]
        if (entry !is MessageEntry) continue
        accumulatedTokens += estimateTokens(entry.message)
        if (accumulatedTokens >= keepRecentTokens) {
            for (c in cutPoints.indices) {
                if (cutPoints[c] >= i) {
                    cutIndex = cutPoints[c]
                    break
                }
            }
            break
        }
    }
    while (cutIndex > startIndex) {
        val prevEntry = entries[cutIndex - 1]
        // Upstream also breaks on "compaction" entries; pathfinder has no
        // compaction entry variant yet — when one is added it must break here
        // so a cut never lands immediately after a compaction entry (pi
        // compaction.ts findCutPoint).
        if (prevEntry is MessageEntry) break
        cutIndex--
    }
    val cutEntry = entries[cutIndex]
    val isUserMessage = cutEntry is MessageEntry && cutEntry.message.role == MessageRole.USER
    val turnStartIndex = if (isUserMessage) -1 else findTurnStartIndex(entries, cutIndex, startIndex)

    return CutPointResult(
        firstKeptEntryIndex = cutIndex,
        turnStartIndex = turnStartIndex,
        isSplitTurn = !isUserMessage && turnStartIndex != -1,
    )
}

/** System prompt for summarization (compaction.ts `SUMMARIZATION_SYSTEM_PROMPT`), ported verbatim. */
const val SUMMARIZATION_SYSTEM_PROMPT =
    "You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.\n" +
        "\n" +
        "Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary."

/** File-operation details stored on generated compaction entries (compaction.ts `CompactionDetails`). */
data class CompactionDetails(
    /** Files read in the compacted history. */
    val readFiles: List<String>,
    /** Files modified in the compacted history. */
    val modifiedFiles: List<String>,
)

/**
 * Accumulate file operations for a compaction summary (compaction.ts
 * `extractFileOperations`, private upstream).
 *
 * Divergence: upstream reads the previous compaction entry's details via
 * `entries[prevCompactionIndex]`; pathfinder has no compaction entry variant
 * yet, so the previous [CompactionDetails] are accepted directly and the
 * entry lookup will be reintroduced when the compaction entry kind lands.
 */
fun extractFileOperations(
    messages: List<Message>,
    prevCompactionDetails: CompactionDetails? = null,
): FileOperations {
    val fileOps = createFileOps()
    if (prevCompactionDetails != null) {
        for (f in prevCompactionDetails.readFiles) fileOps.read.add(f)
        for (f in prevCompactionDetails.modifiedFiles) fileOps.edited.add(f)
    }
    for (msg in messages) {
        extractFileOpsFromMessage(msg, fileOps)
    }
    return fileOps
}

private fun getMessageFromEntry(entry: SessionEntry): Message? = (entry as? MessageEntry)?.message

/**
 * Map an entry to the message it contributes to compaction input
 * (compaction.ts `getMessageFromEntry`, private upstream).
 *
 * Adaptation: `branch_summary` and `compaction` entries synthesize summary
 * messages upstream; those entry kinds do not exist in pathfinder yet.
 */
internal fun getMessageFromEntryForCompaction(entry: SessionEntry): Message? = getMessageFromEntry(entry)
