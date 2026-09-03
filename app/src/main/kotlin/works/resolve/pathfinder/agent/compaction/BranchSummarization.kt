package works.resolve.pathfinder.agent.compaction

import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.utils.Retry
import works.resolve.pathfinder.ai.utils.RetryCallbacks
import works.resolve.pathfinder.ai.utils.RetryPolicy
import works.resolve.pathfinder.ai.utils.contentText
import works.resolve.pathfinder.data.sessions.BranchSummaryEntry
import works.resolve.pathfinder.data.sessions.CompactionEntry
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.ModelChangeEntry
import works.resolve.pathfinder.data.sessions.SessionError
import works.resolve.pathfinder.data.sessions.SessionErrorCode
import works.resolve.pathfinder.data.sessions.SessionEntry
import works.resolve.pathfinder.data.sessions.ThinkingLevelEntry
import works.resolve.pathfinder.data.sessions.ActiveToolsEntry
import works.resolve.pathfinder.data.sessions.CustomEntry
import works.resolve.pathfinder.data.sessions.walkToRoot

data class BranchSummaryResult(
    val summary: String,
    val usage: Usage?,
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)

data class BranchPreparation(
    val messages: List<Message>,
    val fileOps: FileOperations,
    val totalTokens: Int,
)

data class CollectEntriesResult(
    val entries: List<SessionEntry>,
    val commonAncestorId: String?,
)

/**
 * The old branch's entries from (exclusive) the deepest common ancestor of
 * [oldLeafId] and [targetId] down to [oldLeafId], in chronological order;
 * empty when there is no old leaf or nothing unique to summarize.
 */
fun collectEntriesForBranchSummary(
    conversation: Conversation,
    oldLeafId: String?,
    targetId: String,
): CollectEntriesResult {
    if (oldLeafId == null) {
        return CollectEntriesResult(entries = emptyList(), commonAncestorId = null)
    }
    val oldPath = walkToRoot(conversation::entry, oldLeafId).map { it.id }.toHashSet()
    // The walk is leaf→root, so the first id shared with the old path is
    // the deepest common ancestor.
    val targetPath = walkToRoot(conversation::entry, targetId)
    var commonAncestorId: String? = null
    for (entry in targetPath) {
        if (entry.id in oldPath) {
            commonAncestorId = entry.id
            break
        }
    }
    val entries = mutableListOf<SessionEntry>()
    var current: String? = oldLeafId
    while (current != null && current != commonAncestorId) {
        val entry = conversation.entry(current)
            ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: $current")
        entries.add(entry)
        current = entry.parentId
    }
    entries.reverse()

    return CollectEntriesResult(entries = entries, commonAncestorId = commonAncestorId)
}

private fun getMessageFromEntry(entry: SessionEntry): Message? = when (entry) {
    is MessageEntry -> if (entry.message.role == MessageRole.TOOL_RESULT) null else entry.message
    // Upstream returns a dedicated branchSummary agent message; pathfinder
    // has no such role, so the entry projects to a wrapped user message.
    is BranchSummaryEntry -> createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp)
    is CompactionEntry -> createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)
    is ModelChangeEntry, is ThinkingLevelEntry, is ActiveToolsEntry, is CustomEntry -> null
}

/**
 * [BranchSummaryEntry.details] stays the raw persisted JSON, so the file
 * lists are read leniently and malformed fields are skipped, as upstream.
 */
private fun carryBranchSummaryDetails(details: JsonObject?, fileOps: FileOperations) {
    fun strings(name: String): List<String> =
        (details?.get(name) as? JsonArray)
            ?.filterIsInstance<JsonPrimitive>()
            ?.filter { it.isString }
            ?.map { it.content }
            ?: emptyList()
    for (f in strings("readFiles")) fileOps.read.add(f)
    for (f in strings("modifiedFiles")) fileOps.edited.add(f)
}

/**
 * Newest→oldest within the token budget; an over-budget compaction or
 * branch-summary entry is still kept while the running total is under 90%
 * of the budget — the summary of already-summarized history is cheap
 * context — then the walk stops.
 *
 * Nested branch-summary details contribute their file ops before the budget
 * walk, so they count even when the entry itself is cut.
 */
fun prepareBranchEntries(entries: List<SessionEntry>, tokenBudget: Int = 0): BranchPreparation {
    val messages = mutableListOf<Message>()
    val fileOps = createFileOps()
    var totalTokens = 0
    for (entry in entries) {
        if (entry is BranchSummaryEntry) {
            carryBranchSummaryDetails(entry.details as? JsonObject, fileOps)
        }
    }
    for (i in entries.indices.reversed()) {
        val entry = entries[i]
        val message = getMessageFromEntry(entry) ?: continue
        extractFileOpsFromMessage(message, fileOps)

        val tokens = estimateTokens(message)
        if (tokenBudget > 0 && totalTokens + tokens > tokenBudget) {
            if ((entry is CompactionEntry || entry is BranchSummaryEntry) && totalTokens < tokenBudget * 0.9) {
                messages.add(0, message)
                totalTokens += tokens
            }
            break
        }

        messages.add(0, message)
        totalTokens += tokens
    }

    return BranchPreparation(messages = messages, fileOps = fileOps, totalTokens = totalTokens)
}

const val BRANCH_SUMMARY_PREAMBLE =
    "The user explored a different conversation branch before returning here.\n" +
        "Summary of that exploration:\n" +
        "\n"

const val BRANCH_SUMMARY_PROMPT =
    """Create a structured summary of this conversation branch for context when returning later.

Use this EXACT format:

## Goal
[What was the user trying to accomplish in this branch?]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Work that was started but not finished]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [What should happen next to continue this work]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

/**
 * Divergences from upstream: no `signal` (coroutine cancellation instead),
 * the retry runner is injected as [retryRunner], and the wall [clock] for
 * the prompt timestamp comes from the caller.
 */
data class GenerateBranchSummaryOptions(
    /** Owns auth resolution for the summarization request. */
    val models: Models,
    val model: Model,
    val customInstructions: String? = null,
    /** Replace the default prompt instead of appending to it. */
    val replaceInstructions: Boolean = false,
    /** Headroom for the summarization prompt and model output. */
    val reserveTokens: Int = 16384,
    val retry: RetryPolicy? = null,
    val callbacks: RetryCallbacks? = null,
    val retryRunner: Retry = Retry(),
    val clock: Clock = Clock.System,
)

enum class BranchSummaryErrorCode { ABORTED, SUMMARIZATION_FAILED }

class BranchSummaryError(
    val code: BranchSummaryErrorCode,
    message: String,
) : Exception(message)

/**
 * Mirrors pi's `Result<BranchSummaryResult, BranchSummaryError>`; named to
 * avoid clashing with the [BranchSummaryResult] payload.
 */
sealed interface BranchSummaryCallResult {
    data class Ok(val value: BranchSummaryResult) : BranchSummaryCallResult
    data class Err(val error: BranchSummaryError) : BranchSummaryCallResult
}

private val BRANCH_SUMMARY_MAX_TOKENS = 2048

suspend fun generateBranchSummary(
    entries: List<SessionEntry>,
    options: GenerateBranchSummaryOptions,
): BranchSummaryCallResult {
    val contextWindow = if (options.model.contextWindow > 0) options.model.contextWindow else 128000
    val tokenBudget = contextWindow - options.reserveTokens

    val preparation = prepareBranchEntries(entries, tokenBudget)

    if (preparation.messages.isEmpty()) {
        return BranchSummaryCallResult.Ok(
            BranchSummaryResult(summary = "No content to summarize", usage = null, readFiles = emptyList(), modifiedFiles = emptyList()),
        )
    }
    // Upstream serializes convertToLlm(messages); pathfinder messages are
    // already LLM-ready, so the conversion is the identity.
    val conversationText = serializeConversation(preparation.messages)
    val instructions: String = if (options.replaceInstructions && options.customInstructions != null) {
        options.customInstructions!!
    } else if (options.customInstructions != null) {
        "$BRANCH_SUMMARY_PROMPT\n\nAdditional focus: ${options.customInstructions}"
    } else {
        BRANCH_SUMMARY_PROMPT
    }
    val promptText = "<conversation>\n$conversationText\n</conversation>\n\n$instructions"

    val response = completeSimpleWithRetries(
        options.models,
        options.model,
        Context(
            systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
            messages = listOf(
                UserMessage(
                    content = listOf(TextContent(promptText)),
                    timestamp = options.clock.now().toEpochMilliseconds(),
                ),
            ),
        ),
        SimpleStreamOptions(maxTokens = BRANCH_SUMMARY_MAX_TOKENS),
        options.retry,
        options.callbacks,
        options.retryRunner,
    )
    if (response.stopReason == StopReason.ABORTED) {
        return BranchSummaryCallResult.Err(
            BranchSummaryError(BranchSummaryErrorCode.ABORTED, response.errorMessage ?: "Branch summary aborted"),
        )
    }
    if (response.stopReason == StopReason.ERROR) {
        return BranchSummaryCallResult.Err(
            BranchSummaryError(
                BranchSummaryErrorCode.SUMMARIZATION_FAILED,
                "Branch summary failed: ${response.errorMessage ?: "Unknown error"}",
            ),
        )
    }

    var summary = BRANCH_SUMMARY_PREAMBLE + contentText(response.content)
    val (readFiles, modifiedFiles) = computeFileLists(preparation.fileOps)
    summary += formatFileOperations(readFiles, modifiedFiles)

    return BranchSummaryCallResult.Ok(
        BranchSummaryResult(
            summary = summary.ifEmpty { "No summary generated" },
            usage = response.usage,
            readFiles = readFiles,
            modifiedFiles = modifiedFiles,
        ),
    )
}
