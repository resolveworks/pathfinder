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

/**
 * Branch summarization core, ported from pi's
 * `packages/agent/src/harness/compaction/branch-summarization.ts`.
 *
 * In pi, branch summaries are generated when the user navigates away from a
 * leaf: the navigation operation (`operation_started` with intent kind
 * `navigation`, harness/session/types.ts) collects the soon-to-be-abandoned
 * branch segment via [collectEntriesForBranchSummary], generates a summary
 * with [generateBranchSummary], and appends a [BranchSummaryEntry] on the
 * target branch so the exploration is preserved as context
 * (`createBranchSummaryMessage` projection in SessionContext.kt).
 *
 * Pathfinder's navigation trigger is [AgentSession.navigateTree] (ported
 * with the lane-state reducer / operation-record effort, audit P1-4/P1-5):
 * the navigation operation appends a [BranchSummaryEntry] on the target
 * branch via [collectEntriesForBranchSummary] + [generateBranchSummary], so
 * the exploration is preserved as context (`createBranchSummaryMessage`
 * projection in SessionContext.kt).
 *
 * Adaptation boundaries (documented per symbol below):
 * - pi's `AgentMessage` union maps to pathfinder's `ai.core.Message`
 *   hierarchy; the `branchSummary`/`compactionSummary` agent roles are
 *   pre-projected to their convertToLlm forms (see Messages.kt), so upstream's
 *   `convertToLlm` step is the identity and is omitted.
 * - pi's `Session` lookups (`findEntriesOnBranch`, `getEntry`) become direct
 *   walks over the in-memory [Conversation] (synchronous, same
 *   walk-to-root/cycle semantics as pi's state.ts `walkToRoot`).
 * - pi's `signal` parameter is dropped; aborts are coroutine cancellation
 *   (see [completeSimpleWithRetries] and ai/utils/Retry.kt docs).
 */

/**
 * Generated branch summary data ready to be persisted as a branch-summary
 * entry (branch-summarization.ts `BranchSummaryResult`).
 */
data class BranchSummaryResult(
    /** Summary text of the summarized branch segment. */
    val summary: String,
    /** Usage from the LLM call that generated the summary, if available. */
    val usage: Usage?,
    /** Files read while exploring the summarized branch. */
    val readFiles: List<String>,
    /** Files modified while exploring the summarized branch. */
    val modifiedFiles: List<String>,
)

/** Prepared branch content for summarization (branch-summarization.ts `BranchPreparation`). */
data class BranchPreparation(
    /** Messages selected for the branch summary. */
    val messages: List<Message>,
    /** File operations extracted from the branch. */
    val fileOps: FileOperations,
    /** Estimated token count for selected messages. */
    val totalTokens: Int,
)

/** Entries selected for branch summarization (branch-summarization.ts `CollectEntriesResult`). */
data class CollectEntriesResult(
    /** Entries to summarize in chronological order. */
    val entries: List<SessionEntry>,
    /** Deepest common ancestor between the previous leaf and target entry. */
    val commonAncestorId: String?,
)

/**
 * Walk an entry and its ancestors, leaf→root, with pi's `walkToRoot` cycle
 * guard (state.ts walkToRoot throws `invalid_entry` on a cycle).
 */
private fun walkToRoot(conversation: Conversation, start: String): List<SessionEntry> {
    val path = mutableListOf<SessionEntry>()
    val seen = HashSet<String>()
    var current: SessionEntry? = conversation.entry(start)
        ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: $start")
    while (current != null) {
        if (!seen.add(current.id)) {
            throw SessionError(SessionErrorCode.INVALID_ENTRY, "Session branch contains a cycle at ${current.id}")
        }
        path.add(current)
        val parentId = current.parentId ?: break
        current = conversation.entry(parentId)
            ?: throw SessionError(SessionErrorCode.INVALID_ENTRY, "Entry not found: $parentId")
    }
    return path
}

/**
 * Collect entries that should be summarized before navigating to a different
 * session tree entry (branch-summarization.ts `collectEntriesForBranchSummary`).
 *
 * Returns the old branch's entries from (exclusive) the deepest common
 * ancestor of [oldLeafId] and [targetId] down to [oldLeafId], in
 * chronological order; empty when there is no old leaf or nothing unique to
 * summarize.
 *
 * Divergence: pi looks the paths up asynchronously through its `Session`
 * (`findEntriesOnBranch`/`getEntry`, storage-backed); pathfinder walks the
 * in-memory [Conversation] instead — same conditions, same [SessionError]
 * codes and messages.
 */
fun collectEntriesForBranchSummary(
    conversation: Conversation,
    oldLeafId: String?,
    targetId: String,
): CollectEntriesResult {
    if (oldLeafId == null) {
        return CollectEntriesResult(entries = emptyList(), commonAncestorId = null)
    }
    val oldPath = walkToRoot(conversation, oldLeafId).map { it.id }.toHashSet()
    // findEntriesOnBranch default order is leaf→root (state.ts walkToRoot),
    // so the first id shared with the old path is the deepest common ancestor.
    val targetPath = walkToRoot(conversation, targetId)
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

/**
 * Map an entry to the message it contributes to a branch summary
 * (branch-summarization.ts `getMessageFromEntry`, private upstream).
 */
private fun getMessageFromEntry(entry: SessionEntry): Message? = when (entry) {
    is MessageEntry -> if (entry.message.role == MessageRole.TOOL_RESULT) null else entry.message
    // Projected as its convertToLlm form — a wrapped user message
    // (Messages.kt createBranchSummaryMessage).
    is BranchSummaryEntry -> createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp)
    is CompactionEntry -> createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)
    is ModelChangeEntry, is ThinkingLevelEntry, is ActiveToolsEntry, is CustomEntry -> null
}

/**
 * Carry file operations recorded on nested branch-summary details into a
 * fresh accumulator (branch-summarization.ts `prepareBranchEntries` first
 * loop).
 *
 * Upstream reads `details.readFiles`/`details.modifiedFiles` from untyped
 * JSON with `Array.isArray` guards, so malformed fields are skipped. The
 * same lenient read is kept here: [BranchSummaryEntry.details] stays the raw
 * persisted `JsonElement`.
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
 * Prepare branch entries for summarization within an optional token budget
 * (branch-summarization.ts `prepareBranchEntries`).
 *
 * Walks newest→oldest keeping messages until the budget is exhausted; a
 * compaction or branch-summary entry that would exceed the budget is still
 * kept when the running total is under 90% of the budget (the summary of
 * already-summarized history is cheap context), then the walk stops.
 *
 * File-ops merge: upstream accumulates `read`/`write`/`edit` tool calls from
 * assistant messages ([extractFileOpsFromMessage]) plus the details of nested
 * branch summaries. Pathfinder registers no read/write/edit tools
 * (NativeAgentFactory ships `tools = emptyList()`), so the message-side
 * accumulation is currently structurally a no-op and only the nested-details
 * carry-over can contribute; the merge logic is still ported so the trigger
 * (navigation over operation records) lands against faithful behavior.
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

/** Preamble prepended to every generated branch summary (branch-summarization.ts `BRANCH_SUMMARY_PREAMBLE`), verbatim. */
const val BRANCH_SUMMARY_PREAMBLE =
    "The user explored a different conversation branch before returning here.\n" +
        "Summary of that exploration:\n" +
        "\n"

/** Fixed prompt for branch summaries (branch-summarization.ts `BRANCH_SUMMARY_PROMPT`), verbatim. */
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
 * Options for generating a branch summary (branch-summarization.ts
 * `GenerateBranchSummaryOptions`).
 *
 * Divergences: pi's `signal` is dropped (coroutine cancellation); the retry
 * runner is [Retry] (see [completeSimpleWithRetries]); the wall [clock] for
 * the summarization prompt's user-message timestamp is received from the
 * caller (TS→Kotlin timing rule) rather than read inside.
 */
data class GenerateBranchSummaryOptions(
    /** Provider collection the summarization request goes through; owns auth resolution. */
    val models: Models,
    /** Model used for summarization. */
    val model: Model,
    /** Optional instructions appended to or replacing the default prompt. */
    val customInstructions: String? = null,
    /** Replace the default prompt with custom instructions instead of appending them. */
    val replaceInstructions: Boolean = false,
    /** Tokens reserved for prompt and model output. Defaults to 16384. */
    val reserveTokens: Int = 16384,
    /** Optional retry policy for transient summarization errors. */
    val retry: RetryPolicy? = null,
    /** Optional callbacks for retry reporting. */
    val callbacks: RetryCallbacks? = null,
    /** Injectable retry runner (see [completeSimpleWithRetries]). */
    val retryRunner: Retry = Retry(),
    /** Wall clock timestamping the summarization prompt. */
    val clock: Clock = Clock.System,
)

/** Stable branch-summary error codes (harness/types.ts `BranchSummaryErrorCode`). */
enum class BranchSummaryErrorCode { ABORTED, SUMMARIZATION_FAILED }

/** Error returned by branch summarization (harness/types.ts `BranchSummaryError`). */
class BranchSummaryError(
    /** Backend-independent error code. */
    val code: BranchSummaryErrorCode,
    message: String,
) : Exception(message)

/**
 * Result shape for branch summarization helpers, mirroring pi's
 * `Result<BranchSummaryResult, BranchSummaryError>`.
 *
 * Divergence: the data payload keeps its upstream export name
 * ([BranchSummaryResult]), so the wrapper takes this name (compaction's port
 * split the same way: `CompactResult` data / `CompactionResult` wrapper).
 */
sealed interface BranchSummaryCallResult {
    data class Ok(val value: BranchSummaryResult) : BranchSummaryCallResult
    data class Err(val error: BranchSummaryError) : BranchSummaryCallResult
}

private val BRANCH_SUMMARY_MAX_TOKENS = 2048

/**
 * Generate a summary for abandoned branch entries
 * (branch-summarization.ts `generateBranchSummary`).
 *
 * The summary is [BRANCH_SUMMARY_PREAMBLE] + the model's structured output +
 * a `<read-files>`/`<modified-files>` appendix from the branch's file
 * operations. Returns `ok` with the placeholder "No content to summarize"
 * when preparation selects no messages.
 */
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
    // Upstream serializes `convertToLlm(messages)`; pathfinder messages are
    // already LLM-ready (see file docs), so the conversion is the identity.
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
