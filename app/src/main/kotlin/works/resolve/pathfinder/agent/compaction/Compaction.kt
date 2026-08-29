package works.resolve.pathfinder.agent.compaction

import kotlin.time.Clock
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.Cost
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.core.toThinkingLevelOrNull
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.utils.ContextUsageEstimate
import works.resolve.pathfinder.ai.utils.Retry
import works.resolve.pathfinder.ai.utils.RetryCallbacks
import works.resolve.pathfinder.ai.utils.RetryPolicy
import works.resolve.pathfinder.ai.utils.calculateContextTokens
import works.resolve.pathfinder.ai.utils.estimateMessageTokens
import works.resolve.pathfinder.ai.utils.uuidv7
import works.resolve.pathfinder.data.sessions.CompactionEntry
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
 * `CompactResult`, `CompactionPreparation`, `combineUsage`) are ported
 * below in the same file, mirroring upstream's single-module layout.
 *
 * Adaptation boundaries (documented per symbol below):
 * - pi's `AgentMessage` union maps to pathfinder's `ai.core.Message`
 *   hierarchy; the AgentMessage roles `custom`, `bashExecution`,
 *   `branchSummary`, and `compactionSummary` have no pathfinder counterpart:
 *   compaction entries synthesize their summary as a wrapped user message
 *   instead (see `Messages.kt`).
 * - pi's harness `Entry` maps to pathfinder's `SessionEntry`, which has
 *   [MessageEntry] and [CompactionEntry]; other entry kinds (thinking/model
 *   changes, branch summaries) do not exist yet. The cut-point walk keeps
 *   pi's per-entry-type dispatch structure so those variants slot in
 *   without redesign.
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
        // A cut never lands immediately after a compaction entry (pi
        // compaction.ts findCutPoint).
        if (prevEntry is CompactionEntry) break
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
 * Adaptation: upstream reads the previous compaction entry's details via
 * `entries[prevCompactionIndex]` and tolerates unknown shapes; pathfinder
 * types the details, so the previous [CompactionDetails] are accepted
 * directly and the entry lookup happens in [prepareCompaction].
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

private fun getMessageFromEntry(entry: SessionEntry): Message? = when (entry) {
    is MessageEntry -> entry.message
    // pi synthesizes a dedicated compactionSummary agent message here; the
    // port projects it as its convertToLlm form — a wrapped user message
    // (Messages.kt createCompactionSummaryMessage).
    is CompactionEntry -> createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)
}

/**
 * Map an entry to the message it contributes to compaction input
 * (compaction.ts `getMessageFromEntryForCompaction`, private upstream).
 *
 * Compaction entries contribute nothing: their summary is handled via the
 * `previousSummary` update path instead (upstream returns undefined for
 * `compaction` entries).
 */
internal fun getMessageFromEntryForCompaction(entry: SessionEntry): Message? =
    if (entry is CompactionEntry) null else getMessageFromEntry(entry)

// ---- LLM-calling half (compaction.ts) ----

/** Error codes returned by compaction helpers (harness/types.ts `CompactionErrorCode`). */
enum class CompactionErrorCode { ABORTED, SUMMARIZATION_FAILED }

/** Error returned by compaction helpers (harness/types.ts `CompactionError`). */
class CompactionError(
    /** Backend-independent error code. */
    val code: CompactionErrorCode,
    message: String,
) : Exception(message)

/** pi's Result<T, E> shape for compaction helpers (harness/types.ts `ok`/`err`/`Result`). */
sealed interface CompactionResult<out T> {
    data class Ok<T>(val value: T) : CompactionResult<T>
    data class Err(val error: CompactionError) : CompactionResult<Nothing>
}

internal fun <T> ok(value: T): CompactionResult<T> = CompactionResult.Ok(value)

internal fun err(error: CompactionError): CompactionResult<Nothing> = CompactionResult.Err(error)

/** Generated summary plus its provider usage (compaction.ts `generateSummaryWithUsage` return value). */
data class GeneratedSummary(
    val text: String,
    val usage: Usage,
)

/** Generated compaction data ready to be persisted as a compaction entry (compaction.ts `CompactResult`). */
data class CompactResult(
    /** Summary text that replaces compacted history in future context. */
    val summary: String,
    /** Estimated context tokens before compaction. */
    val tokensBefore: Int,
    /** Usage from the LLM call(s) that generated this summary, if available. */
    val usage: Usage?,
    /** Retained recent messages stored directly on the compaction entry. */
    val retainedTail: List<Message>,
    /** Optional implementation-specific details stored with the compaction entry. */
    val details: CompactionDetails?,
)

/**
 * Add two provider usage blocks (compaction.ts `combineUsage`, private upstream).
 *
 * Divergence: upstream keeps `cacheWrite1h`/`reasoning` undefined when absent
 * on both sides; pathfinder's [Usage] models them as non-optional ints
 * (0 = unreported), so they are summed unconditionally.
 */
fun combineUsage(first: Usage, second: Usage): Usage = Usage(
    input = first.input + second.input,
    output = first.output + second.output,
    cacheRead = first.cacheRead + second.cacheRead,
    cacheWrite = first.cacheWrite + second.cacheWrite,
    cacheWrite1h = first.cacheWrite1h + second.cacheWrite1h,
    reasoning = first.reasoning + second.reasoning,
    totalTokens = first.totalTokens + second.totalTokens,
    cost = first.cost + second.cost,
)

private operator fun Cost.plus(other: Cost): Cost = Cost(
    input = input + other.input,
    output = output + other.output,
    cacheRead = cacheRead + other.cacheRead,
    cacheWrite = cacheWrite + other.cacheWrite,
    total = total + other.total,
)

private val DEFAULT_RETRY = Retry()

/**
 * Run a single summary completion with bounded retry (compaction.ts
 * `completeSimpleWithRetries`).
 *
 * Summaries are standalone requests, so routing is isolated and cache
 * writes that cannot be reused are avoided: every call sets
 * `cacheRetention = "none"` and a fresh `sessionId` (upstream
 * `requestOptions` tweaks, ported verbatim onto [SimpleStreamOptions]).
 *
 * Adaptation: upstream is a free function calling pi's module-level
 * `retryAssistantCall(produce, retry, signal, callbacks)`; pathfinder's
 * `retryAssistantCall` lives on the [Retry] class (injectable sleep), so the
 * runner is taken as a defaulted [retryRunner] parameter. Upstream's
 * `signal` is dropped — aborts are expressed as coroutine cancellation in
 * this codebase (see ai/utils/Retry.kt docs).
 */
suspend fun completeSimpleWithRetries(
    models: Models,
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
    retry: RetryPolicy? = null,
    callbacks: RetryCallbacks? = null,
    retryRunner: Retry = DEFAULT_RETRY,
): AssistantMessage {
    val requestOptions = options.copy(
        cacheRetention = CacheRetention.NONE,
        sessionId = uuidv7(),
    )
    return retryRunner.retryAssistantCall(
        { models.completeSimple(model, context, requestOptions) },
        retry,
        callbacks,
    )
}

private const val SUMMARIZATION_PROMPT = """The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or "(none)" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

private const val UPDATE_SUMMARIZATION_PROMPT = """The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

Update the existing structured summary with new information. RULES:
- PRESERVE all existing information from the previous summary
- ADD new progress, decisions, and context from the new messages
- UPDATE the Progress section: move items from "In Progress" to "Done" when completed
- UPDATE "Next Steps" based on what was accomplished
- PRESERVE exact file paths, function names, and error messages
- If something is no longer relevant, you may remove it

Use this EXACT format:

## Goal
[Preserve existing goals, add new ones if the task expanded]

## Constraints & Preferences
- [Preserve existing, add new ones discovered]

## Progress
### Done
- [x] [Include previously done items AND newly completed items]

### In Progress
- [ ] [Current work - update based on progress]

### Blocked
- [Current blockers - remove if resolved]

## Key Decisions
- **[Decision]**: [Brief rationale] (preserve all previous, add new)

## Next Steps
1. [Update based on current state]

## Critical Context
- [Preserve important context, add new if needed]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

private const val TURN_PREFIX_SUMMARIZATION_PROMPT = """This is the PREFIX of a turn that was too large to keep. The SUFFIX (recent work) is retained.

Summarize the prefix to provide context for the retained suffix:

## Original Request
[What did the user ask for in this turn?]

## Early Progress
- [Key decisions and work done in the prefix]

## Context for Suffix
- [Information needed to understand the retained recent work]

Be concise. Focus on what's needed to understand the kept suffix."""

private fun contentText(content: List<Content>): String =
    content.filter { it.type == ContentType.TEXT }.joinToString("\n") { (it as TextContent).text }

private fun summaryMaxTokens(reserveTokens: Int, model: Model, fraction: Double): Int {
    val fromReserve = (fraction * reserveTokens).toInt()
    return if (model.maxTokens > 0) minOf(fromReserve, model.maxTokens) else fromReserve
}

private fun summarizationContext(promptText: String, clock: Clock): Context = Context(
    systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
    messages = listOf(
        UserMessage(
            content = listOf(TextContent(promptText)),
            timestamp = clock.now().toEpochMilliseconds(),
        ),
    ),
)

private fun completionOptions(
    model: Model,
    maxTokens: Int,
    thinkingLevel: ModelThinkingLevel?,
): SimpleStreamOptions =
    // The OFF guard above makes the shared OFF→null mapper total here: OFF
    // never reaches toThinkingLevelOrNull, matching pi, which only sets
    // reasoning when the level is present and not "off".
    if (model.reasoning && thinkingLevel != null && thinkingLevel != ModelThinkingLevel.OFF) {
        SimpleStreamOptions(maxTokens = maxTokens, reasoning = thinkingLevel.toThinkingLevelOrNull())
    } else {
        SimpleStreamOptions(maxTokens = maxTokens)
    }

/**
 * Generate or update a conversation summary for compaction (compaction.ts
 * `generateSummary`).
 *
 * Divergences: pi's `signal` parameter is dropped (aborts are coroutine
 * cancellation here); the `thinkingLevel` parameter uses
 * [ModelThinkingLevel] because pi's `ThinkingLevel` includes "off", which
 * pathfinder's core `ThinkingLevel` enum models as `ModelThinkingLevel.OFF`;
 * the retry runner adaptation is documented on [completeSimpleWithRetries]; and
 * the wall [clock] used to timestamp the summarization prompt is received
 * from the caller (TS→Kotlin timing rule: pure functions get the clock from
 * their caller) rather than defaulting here.
 */
suspend fun generateSummary(
    currentMessages: List<Message>,
    models: Models,
    model: Model,
    reserveTokens: Int,
    customInstructions: String? = null,
    previousSummary: String? = null,
    thinkingLevel: ModelThinkingLevel? = null,
    retry: RetryPolicy? = null,
    callbacks: RetryCallbacks? = null,
    retryRunner: Retry = DEFAULT_RETRY,
    clock: Clock,
): CompactionResult<String> =
    when (val result = generateSummaryWithUsage(
        currentMessages, models, model, reserveTokens,
        customInstructions, previousSummary, thinkingLevel, retry, callbacks, retryRunner, clock,
    )) {
        is CompactionResult.Ok -> ok(result.value.text)
        is CompactionResult.Err -> err(result.error)
    }

/**
 * Generate or update a conversation summary and return its provider usage
 * (compaction.ts `generateSummaryWithUsage`).
 *
 * Divergences: upstream builds the prompt over `convertToLlm(messages)`;
 * pathfinder messages are already LLM messages (the compaction-summary
 * agent role is pre-projected at its convertToLlm form, see `Messages.kt`),
 * so the conversion step is the identity and is omitted.
 */
suspend fun generateSummaryWithUsage(
    currentMessages: List<Message>,
    models: Models,
    model: Model,
    reserveTokens: Int,
    customInstructions: String? = null,
    previousSummary: String? = null,
    thinkingLevel: ModelThinkingLevel? = null,
    retry: RetryPolicy? = null,
    callbacks: RetryCallbacks? = null,
    retryRunner: Retry = DEFAULT_RETRY,
    clock: Clock,
): CompactionResult<GeneratedSummary> {
    val maxTokens = summaryMaxTokens(reserveTokens, model, 0.8)
    var basePrompt = if (previousSummary != null) UPDATE_SUMMARIZATION_PROMPT else SUMMARIZATION_PROMPT
    if (customInstructions != null) {
        basePrompt = "$basePrompt\n\nAdditional focus: $customInstructions"
    }
    val conversationText = serializeConversation(currentMessages)
    var promptText = "<conversation>\n$conversationText\n</conversation>\n\n"
    if (previousSummary != null) {
        promptText += "<previous-summary>\n$previousSummary\n</previous-summary>\n\n"
    }
    promptText += basePrompt

    val response = completeSimpleWithRetries(
        models,
        model,
        summarizationContext(promptText, clock),
        completionOptions(model, maxTokens, thinkingLevel),
        retry,
        callbacks,
        retryRunner,
    )
    if (response.stopReason == StopReason.ABORTED) {
        return err(CompactionError(CompactionErrorCode.ABORTED, response.errorMessage ?: "Summarization aborted"))
    }
    if (response.stopReason == StopReason.ERROR) {
        return err(
            CompactionError(
                CompactionErrorCode.SUMMARIZATION_FAILED,
                "Summarization failed: ${response.errorMessage ?: "Unknown error"}",
            ),
        )
    }

    return ok(GeneratedSummary(text = contentText(response.content), usage = response.usage))
}

/** Prepared inputs for a compaction run (compaction.ts `CompactionPreparation`). */
data class CompactionPreparation(
    /** Messages summarized into the history summary. */
    val messagesToSummarize: List<Message>,
    /** Prefix messages summarized separately when compaction splits a turn. */
    val turnPrefixMessages: List<Message>,
    /** Recent messages retained after compaction and stored on the compaction entry. */
    val retainedTail: List<Message>,
    /** Whether compaction splits a turn. */
    val isSplitTurn: Boolean,
    /** Estimated context tokens before compaction. */
    val tokensBefore: Int,
    /** Previous compaction summary used for iterative updates. */
    val previousSummary: String?,
    /** File operations extracted from summarized history. */
    val fileOps: FileOperations,
    /** Settings used to prepare compaction. */
    val settings: CompactionSettings,
)

/**
 * Prepare session entries for compaction, or return null when compaction is
 * not applicable (compaction.ts `prepareCompaction`).
 */
fun prepareCompaction(
    pathEntries: List<SessionEntry>,
    settings: CompactionSettings,
): CompactionResult<CompactionPreparation?> {
    if (pathEntries.isEmpty() || pathEntries.last() is CompactionEntry) {
        return ok(null)
    }

    var prevCompactionIndex = -1
    for (i in pathEntries.indices.reversed()) {
        if (pathEntries[i] is CompactionEntry) {
            prevCompactionIndex = i
            break
        }
    }

    var previousSummary: String? = null
    var compactableEntries = pathEntries
    if (prevCompactionIndex >= 0) {
        val prevCompaction = pathEntries[prevCompactionIndex] as CompactionEntry
        previousSummary = prevCompaction.summary
        val virtualRetainedEntries: List<SessionEntry> = prevCompaction.retainedTail.mapIndexed { index, message ->
            MessageEntry(
                id = "${prevCompaction.id}:retained:$index",
                parentId = if (index == 0) prevCompaction.id else "${prevCompaction.id}:retained:${index - 1}",
                timestamp = message.timestamp,
                message = message,
            )
        }
        compactableEntries = virtualRetainedEntries + pathEntries.subList(prevCompactionIndex + 1, pathEntries.size)
    }
    val boundaryEnd = compactableEntries.size

    val tokensBefore = estimateContextTokens(buildSessionContext(pathEntries)).tokens

    val cutPoint = findCutPoint(compactableEntries, 0, boundaryEnd, settings.keepRecentTokens)
    val historyEnd = if (cutPoint.isSplitTurn) cutPoint.turnStartIndex else cutPoint.firstKeptEntryIndex
    val messagesToSummarize = mutableListOf<Message>()
    for (i in 0 until historyEnd) {
        getMessageFromEntryForCompaction(compactableEntries[i])?.let { messagesToSummarize.add(it) }
    }
    val turnPrefixMessages = mutableListOf<Message>()
    if (cutPoint.isSplitTurn) {
        for (i in cutPoint.turnStartIndex until cutPoint.firstKeptEntryIndex) {
            getMessageFromEntryForCompaction(compactableEntries[i])?.let { turnPrefixMessages.add(it) }
        }
    }
    val retainedTail = mutableListOf<Message>()
    for (i in cutPoint.firstKeptEntryIndex until boundaryEnd) {
        getMessageFromEntryForCompaction(compactableEntries[i])?.let { retainedTail.add(it) }
    }
    val prevDetails = if (prevCompactionIndex >= 0) {
        (pathEntries[prevCompactionIndex] as CompactionEntry).details
    } else {
        null
    }
    val fileOps = extractFileOperations(messagesToSummarize, prevDetails)
    if (cutPoint.isSplitTurn) {
        for (msg in turnPrefixMessages) {
            extractFileOpsFromMessage(msg, fileOps)
        }
    }

    return ok(
        CompactionPreparation(
            messagesToSummarize = messagesToSummarize,
            turnPrefixMessages = turnPrefixMessages,
            retainedTail = retainedTail,
            isSplitTurn = cutPoint.isSplitTurn,
            tokensBefore = tokensBefore,
            previousSummary = previousSummary,
            fileOps = fileOps,
            settings = settings,
        ),
    )
}

/**
 * Generate compaction summary data from prepared session history
 * (compaction.ts `compact`).
 *
 * Divergences: pi's `signal` parameter is dropped (coroutine cancellation);
 * `details` is typed [CompactionDetails] rather than upstream's generic
 * `T = unknown`. The retry runner adaptation is documented on
 * [completeSimpleWithRetries]. The wall [clock] for the summarization
 * prompts is received from the caller (TS→Kotlin timing rule).
 */
suspend fun compact(
    preparation: CompactionPreparation,
    models: Models,
    model: Model,
    customInstructions: String? = null,
    thinkingLevel: ModelThinkingLevel? = null,
    retry: RetryPolicy? = null,
    callbacks: RetryCallbacks? = null,
    retryRunner: Retry = DEFAULT_RETRY,
    clock: Clock,
): CompactionResult<CompactResult> {
    val (messagesToSummarize, turnPrefixMessages, retainedTail, isSplitTurn, tokensBefore, previousSummary, fileOps, settings) =
        preparation

    val summary: String
    val summaryUsage: Usage

    if (isSplitTurn && turnPrefixMessages.isNotEmpty()) {
        var historyText = "No prior history."
        var historyUsage: Usage? = null
        if (messagesToSummarize.isNotEmpty()) {
            val historyResult = generateSummaryWithUsage(
                messagesToSummarize, models, model, settings.reserveTokens,
                customInstructions, previousSummary, thinkingLevel, retry, callbacks, retryRunner, clock,
            )
            if (historyResult is CompactionResult.Err) return err(historyResult.error)
            historyResult as CompactionResult.Ok
            historyText = historyResult.value.text
            historyUsage = historyResult.value.usage
        }
        val turnPrefixResult = generateTurnPrefixSummary(
            turnPrefixMessages, models, model, settings.reserveTokens,
            thinkingLevel, retry, callbacks, retryRunner, clock,
        )
        if (turnPrefixResult is CompactionResult.Err) return err(turnPrefixResult.error)
        turnPrefixResult as CompactionResult.Ok
        summary = "$historyText\n\n---\n\n**Turn Context (split turn):**\n\n${turnPrefixResult.value.text}"
        summaryUsage = historyUsage?.let { combineUsage(it, turnPrefixResult.value.usage) }
            ?: turnPrefixResult.value.usage
    } else {
        val summaryResult = generateSummaryWithUsage(
            messagesToSummarize, models, model, settings.reserveTokens,
            customInstructions, previousSummary, thinkingLevel, retry, callbacks, retryRunner, clock,
        )
        if (summaryResult is CompactionResult.Err) return err(summaryResult.error)
        summaryResult as CompactionResult.Ok
        summary = summaryResult.value.text
        summaryUsage = summaryResult.value.usage
    }

    val (readFiles, modifiedFiles) = computeFileLists(fileOps)
    val summaryWithFiles = summary + formatFileOperations(readFiles, modifiedFiles)

    return ok(
        CompactResult(
            summary = summaryWithFiles,
            tokensBefore = tokensBefore,
            usage = summaryUsage,
            retainedTail = retainedTail,
            details = CompactionDetails(readFiles = readFiles, modifiedFiles = modifiedFiles),
        ),
    )
}

/**
 * Summarize the prefix of an oversized split turn (compaction.ts
 * `generateTurnPrefixSummary`, private upstream).
 */
private suspend fun generateTurnPrefixSummary(
    messages: List<Message>,
    models: Models,
    model: Model,
    reserveTokens: Int,
    thinkingLevel: ModelThinkingLevel?,
    retry: RetryPolicy?,
    callbacks: RetryCallbacks?,
    retryRunner: Retry,
    clock: Clock,
): CompactionResult<GeneratedSummary> {
    val maxTokens = summaryMaxTokens(reserveTokens, model, 0.5)
    val conversationText = serializeConversation(messages)
    val promptText = "<conversation>\n$conversationText\n</conversation>\n\n$TURN_PREFIX_SUMMARIZATION_PROMPT"

    val response = completeSimpleWithRetries(
        models,
        model,
        summarizationContext(promptText, clock),
        completionOptions(model, maxTokens, thinkingLevel),
        retry,
        callbacks,
        retryRunner,
    )
    if (response.stopReason == StopReason.ABORTED) {
        return err(CompactionError(CompactionErrorCode.ABORTED, response.errorMessage ?: "Turn prefix summarization aborted"))
    }
    if (response.stopReason == StopReason.ERROR) {
        return err(
            CompactionError(
                CompactionErrorCode.SUMMARIZATION_FAILED,
                "Turn prefix summarization failed: ${response.errorMessage ?: "Unknown error"}",
            ),
        )
    }

    return ok(GeneratedSummary(text = contentText(response.content), usage = response.usage))
}
