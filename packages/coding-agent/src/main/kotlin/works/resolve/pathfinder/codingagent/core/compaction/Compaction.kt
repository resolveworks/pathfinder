package works.resolve.pathfinder.codingagent.core.compaction

import kotlin.time.Clock
import works.resolve.pathfinder.agent.CompactionDetails
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.MessageRole
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingLevel
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.toThinkingLevelOrNull
import works.resolve.pathfinder.ai.utils.ContextUsageEstimate
import works.resolve.pathfinder.ai.utils.Retry
import works.resolve.pathfinder.ai.utils.RetryCallbacks
import works.resolve.pathfinder.ai.utils.RetryPolicy
import works.resolve.pathfinder.ai.utils.calculateContextTokens
import works.resolve.pathfinder.ai.utils.contentText
import works.resolve.pathfinder.ai.utils.estimateMessageTokens
import works.resolve.pathfinder.ai.utils.uuidv7
import works.resolve.pathfinder.codingagent.core.session.ActiveToolsEntry
import works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.CustomEntry
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.session.SessionEntry
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry
import works.resolve.pathfinder.codingagent.core.utils.addUsage

data class CompactionSettings(
    val enabled: Boolean,
    /** Headroom held out of the context window for the summary request. */
    val reserveTokens: Int,
    val keepRecentTokens: Int
)

val DEFAULT_COMPACTION_SETTINGS = CompactionSettings(
    enabled = true,
    reserveTokens = 16384,
    keepRecentTokens = 20000
)

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
 * Upstream also estimates `custom`, `bashExecution`, `branchSummary`, and
 * `compactionSummary` message roles; the pathfinder [Message] hierarchy has
 * no such roles.
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
 * Compaction's own estimator over a bare message list: last valid assistant
 * usage wins, with per-message estimates for the trailing messages — unlike
 * the same-named `ai.utils.estimateContextTokens(Context)`, which also
 * accounts for the system prompt and tools.
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
            lastUsageIndex = null
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
        lastUsageIndex = index
    )
}

fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings): Boolean {
    if (!settings.enabled) return false
    return contextTokens > contextWindow - settings.reserveTokens
}

private fun findValidCutPoints(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int
): List<Int> {
    val cutPoints = mutableListOf<Int>()
    for (i in startIndex until endIndex) {
        val entry = entries[i]
        if (entry is MessageEntry) {
            when (entry.message.role) {
                MessageRole.USER, MessageRole.ASSISTANT -> cutPoints.add(i)
                MessageRole.TOOL_RESULT -> {}
            }
        } else if (entry is BranchSummaryEntry) {
            cutPoints.add(i)
        }
    }
    return cutPoints
}

fun findTurnStartIndex(entries: List<SessionEntry>, entryIndex: Int, startIndex: Int): Int {
    for (i in entryIndex downTo startIndex) {
        val entry = entries[i]
        if (entry is BranchSummaryEntry) return i
        if (entry is MessageEntry && entry.message.role == MessageRole.USER) {
            return i
        }
    }
    return -1
}

data class CutPointResult(
    val firstKeptEntryIndex: Int,
    /** Turn-start entry index when the cut splits a turn, otherwise -1. */
    val turnStartIndex: Int,
    val isSplitTurn: Boolean
)

fun findCutPoint(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
    keepRecentTokens: Int
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
        if (prevEntry is CompactionEntry) break
        if (prevEntry is MessageEntry) break
        cutIndex--
    }
    val cutEntry = entries[cutIndex]
    val isUserMessage = cutEntry is MessageEntry && cutEntry.message.role == MessageRole.USER
    val turnStartIndex = if (isUserMessage) {
        -1
    } else {
        findTurnStartIndex(
            entries,
            cutIndex,
            startIndex
        )
    }

    return CutPointResult(
        firstKeptEntryIndex = cutIndex,
        turnStartIndex = turnStartIndex,
        isSplitTurn = !isUserMessage && turnStartIndex != -1
    )
}

const val SUMMARIZATION_SYSTEM_PROMPT =
    "You are a context summarization assistant. Your task is to read a conversation " +
        "between a user and an AI assistant, then produce a structured summary " +
        "following the exact format specified.\n" +
        "\n" +
        "Do NOT continue the conversation. Do NOT respond to any questions in the conversation. " +
        "ONLY output the structured summary."

/**
 * Upstream reads the previous compaction entry's details out of the entries
 * list and tolerates unknown shapes; pathfinder's details are typed, so the
 * previous [CompactionDetails] are passed in directly and the entry lookup
 * happens in [prepareCompaction].
 */
fun extractFileOperations(
    messages: List<Message>,
    prevCompactionDetails: CompactionDetails? = null
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

    // Upstream synthesizes dedicated branchSummary/compactionSummary agent
    // messages here; pathfinder has no such roles, so both entries project
    // to their LLM form — a wrapped user message (Messages.kt).
    is BranchSummaryEntry -> createBranchSummaryMessage(
        entry.summary,
        entry.fromId,
        entry.timestamp
    )

    is CompactionEntry -> createCompactionSummaryMessage(
        entry.summary,
        entry.tokensBefore,
        entry.timestamp
    )

    is ModelChangeEntry, is ThinkingLevelEntry, is ActiveToolsEntry, is CustomEntry -> null
}

/**
 * Compaction entries contribute no message: their stored summary re-enters
 * summarization via `previousSummary` instead.
 */
internal fun getMessageFromEntryForCompaction(entry: SessionEntry): Message? =
    if (entry is CompactionEntry) null else getMessageFromEntry(entry)

enum class CompactionErrorCode { ABORTED, SUMMARIZATION_FAILED }

class CompactionError(val code: CompactionErrorCode, message: String) : Exception(message)

sealed interface CompactionResult<out T> {
    data class Ok<T>(val value: T) : CompactionResult<T>
    data class Err(val error: CompactionError) : CompactionResult<Nothing>
}

internal fun <T> ok(value: T): CompactionResult<T> = CompactionResult.Ok(value)

internal fun err(error: CompactionError): CompactionResult<Nothing> = CompactionResult.Err(error)

data class GeneratedSummary(val text: String, val usage: Usage)

/** Compaction outcome to persist as a session compaction entry. */
data class CompactResult(
    /** Replaces the compacted history in future context. */
    val summary: String,
    val tokensBefore: Int,
    val usage: Usage?,
    val retainedTail: List<Message>,
    val details: CompactionDetails?
)

private val DEFAULT_RETRY = Retry()

/**
 * Summaries are standalone requests, so every call isolates routing and
 * avoids cache writes that cannot be reused: `cacheRetention = NONE` and a
 * fresh `sessionId` on each attempt. Upstream's `signal` parameter is
 * dropped — aborts are coroutine cancellation here — and the retry runner is
 * an injected [retryRunner] because retry sleeping lives on [Retry].
 */
suspend fun completeSimpleWithRetries(
    models: Models,
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
    retry: RetryPolicy? = null,
    callbacks: RetryCallbacks? = null,
    retryRunner: Retry = DEFAULT_RETRY
): AssistantMessage {
    val requestOptions = options.copy(
        cacheRetention = CacheRetention.NONE,
        sessionId = uuidv7()
    )
    return retryRunner.retryAssistantCall(
        { models.completeSimple(model, context, requestOptions) },
        retry,
        callbacks
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

private fun summaryMaxTokens(reserveTokens: Int, model: Model, fraction: Double): Int {
    val fromReserve = (fraction * reserveTokens).toInt()
    return if (model.maxTokens > 0) minOf(fromReserve, model.maxTokens) else fromReserve
}

private fun summarizationContext(promptText: String, clock: Clock): Context = Context(
    systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
    messages = listOf(
        UserMessage(
            content = listOf(TextContent(promptText)),
            timestamp = clock.now().toEpochMilliseconds()
        )
    )
)

private fun completionOptions(
    model: Model,
    maxTokens: Int,
    thinkingLevel: ModelThinkingLevel?
): SimpleStreamOptions = // The OFF guard above makes the shared OFF→null mapper total here: OFF
    // never reaches toThinkingLevelOrNull, matching pi, which only sets
    // reasoning when the level is present and not "off".
    if (model.reasoning && thinkingLevel != null && thinkingLevel != ModelThinkingLevel.OFF) {
        SimpleStreamOptions(
            maxTokens = maxTokens,
            reasoning = thinkingLevel.toThinkingLevelOrNull()
        )
    } else {
        SimpleStreamOptions(maxTokens = maxTokens)
    }

/**
 * Divergences from upstream: no `signal` parameter (aborts are coroutine
 * cancellation); `thinkingLevel` is [ModelThinkingLevel] because pathfinder
 * models upstream's "off" as [ModelThinkingLevel.OFF]; the wall [clock] is
 * received from the caller (TS→Kotlin timing rule).
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
    clock: Clock
): CompactionResult<String> = when (
    val result = generateSummaryWithUsage(
        currentMessages, models, model, reserveTokens,
        customInstructions, previousSummary, thinkingLevel, retry, callbacks, retryRunner, clock
    )
) {
    is CompactionResult.Ok -> ok(result.value.text)
    is CompactionResult.Err -> err(result.error)
}

/**
 * Upstream builds the prompt over `convertToLlm(messages)`; pathfinder
 * messages are already LLM messages (branch/compaction summaries are
 * pre-projected, see `Messages.kt`), so the conversion step is the identity
 * and is omitted.
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
    clock: Clock
): CompactionResult<GeneratedSummary> {
    val maxTokens = summaryMaxTokens(reserveTokens, model, 0.8)
    var basePrompt = if (previousSummary !=
        null
    ) {
        UPDATE_SUMMARIZATION_PROMPT
    } else {
        SUMMARIZATION_PROMPT
    }
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
        retryRunner
    )
    if (response.stopReason == StopReason.ABORTED) {
        return err(
            CompactionError(
                CompactionErrorCode.ABORTED,
                response.errorMessage ?: "Summarization aborted"
            )
        )
    }
    if (response.stopReason == StopReason.ERROR) {
        return err(
            CompactionError(
                CompactionErrorCode.SUMMARIZATION_FAILED,
                "Summarization failed: ${response.errorMessage ?: "Unknown error"}"
            )
        )
    }

    return ok(GeneratedSummary(text = contentText(response.content), usage = response.usage))
}

data class CompactionPreparation(
    val messagesToSummarize: List<Message>,
    val turnPrefixMessages: List<Message>,
    val retainedTail: List<Message>,
    val isSplitTurn: Boolean,
    val tokensBefore: Int,
    val previousSummary: String?,
    val fileOps: FileOperations,
    val settings: CompactionSettings
)

fun prepareCompaction(
    pathEntries: List<SessionEntry>,
    settings: CompactionSettings
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
        val virtualRetainedEntries: List<SessionEntry> = prevCompaction.retainedTail.mapIndexed {
                index,
                message
            ->
            MessageEntry(
                id = "${prevCompaction.id}:retained:$index",
                parentId = if (index ==
                    0
                ) {
                    prevCompaction.id
                } else {
                    "${prevCompaction.id}:retained:${index - 1}"
                },
                timestamp = message.timestamp,
                message = message
            )
        }
        compactableEntries =
            virtualRetainedEntries + pathEntries.subList(prevCompactionIndex + 1, pathEntries.size)
    }
    val boundaryEnd = compactableEntries.size

    val tokensBefore = estimateContextTokens(buildSessionContext(pathEntries)).tokens

    val cutPoint = findCutPoint(compactableEntries, 0, boundaryEnd, settings.keepRecentTokens)
    val historyEnd = if (cutPoint.isSplitTurn) {
        cutPoint.turnStartIndex
    } else {
        cutPoint.firstKeptEntryIndex
    }
    val messagesToSummarize = mutableListOf<Message>()
    for (i in 0 until historyEnd) {
        getMessageFromEntryForCompaction(compactableEntries[i])?.let { messagesToSummarize.add(it) }
    }
    val turnPrefixMessages = mutableListOf<Message>()
    if (cutPoint.isSplitTurn) {
        for (i in cutPoint.turnStartIndex until cutPoint.firstKeptEntryIndex) {
            getMessageFromEntryForCompaction(compactableEntries[i])?.let {
                turnPrefixMessages.add(it)
            }
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
            settings = settings
        )
    )
}

/**
 * Divergences from upstream: no `signal` parameter (aborts are coroutine
 * cancellation); `details` is typed [CompactionDetails] rather than
 * upstream's unknown-typed generic; the wall [clock] is received from the
 * caller (TS→Kotlin timing rule).
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
    clock: Clock
): CompactionResult<CompactResult> {
    val (
        messagesToSummarize, turnPrefixMessages, retainedTail, isSplitTurn, tokensBefore,
        previousSummary, fileOps, settings
    ) = preparation

    val summary: String
    val summaryUsage: Usage

    if (isSplitTurn && turnPrefixMessages.isNotEmpty()) {
        var historyText = "No prior history."
        var historyUsage: Usage? = null
        if (messagesToSummarize.isNotEmpty()) {
            val historyResult = generateSummaryWithUsage(
                messagesToSummarize, models, model, settings.reserveTokens,
                customInstructions, previousSummary, thinkingLevel, retry, callbacks,
                retryRunner, clock
            )
            if (historyResult is CompactionResult.Err) return err(historyResult.error)
            historyResult as CompactionResult.Ok
            historyText = historyResult.value.text
            historyUsage = historyResult.value.usage
        }
        val turnPrefixResult = generateTurnPrefixSummary(
            turnPrefixMessages, models, model, settings.reserveTokens,
            thinkingLevel, retry, callbacks, retryRunner, clock
        )
        if (turnPrefixResult is CompactionResult.Err) return err(turnPrefixResult.error)
        turnPrefixResult as CompactionResult.Ok
        summary =
            "$historyText\n\n---\n\n**Turn Context (split turn):**\n\n${turnPrefixResult.value.text}"
        summaryUsage = historyUsage?.let { addUsage(it, turnPrefixResult.value.usage) }
            ?: turnPrefixResult.value.usage
    } else {
        val summaryResult = generateSummaryWithUsage(
            messagesToSummarize, models, model, settings.reserveTokens,
            customInstructions, previousSummary, thinkingLevel, retry, callbacks, retryRunner, clock
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
            details = CompactionDetails(readFiles = readFiles, modifiedFiles = modifiedFiles)
        )
    )
}

private suspend fun generateTurnPrefixSummary(
    messages: List<Message>,
    models: Models,
    model: Model,
    reserveTokens: Int,
    thinkingLevel: ModelThinkingLevel?,
    retry: RetryPolicy?,
    callbacks: RetryCallbacks?,
    retryRunner: Retry,
    clock: Clock
): CompactionResult<GeneratedSummary> {
    val maxTokens = summaryMaxTokens(reserveTokens, model, 0.5)
    val conversationText = serializeConversation(messages)
    val promptText = "<conversation>\n$conversationText\n</conversation>\n\n" +
        TURN_PREFIX_SUMMARIZATION_PROMPT

    val response = completeSimpleWithRetries(
        models,
        model,
        summarizationContext(promptText, clock),
        completionOptions(model, maxTokens, thinkingLevel),
        retry,
        callbacks,
        retryRunner
    )
    if (response.stopReason == StopReason.ABORTED) {
        return err(
            CompactionError(
                CompactionErrorCode.ABORTED,
                response.errorMessage ?: "Turn prefix summarization aborted"
            )
        )
    }
    if (response.stopReason == StopReason.ERROR) {
        return err(
            CompactionError(
                CompactionErrorCode.SUMMARIZATION_FAILED,
                "Turn prefix summarization failed: ${response.errorMessage ?: "Unknown error"}"
            )
        )
    }

    return ok(GeneratedSummary(text = contentText(response.content), usage = response.usage))
}
