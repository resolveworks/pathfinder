package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.strictInt
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.data.sessions.BranchSummaryEntry
import works.resolve.pathfinder.data.sessions.CompactionEntry
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.JsonlCodec
import works.resolve.pathfinder.data.sessions.LaneRecord
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.OperationIntent
import works.resolve.pathfinder.data.sessions.SessionEntry
import works.resolve.pathfinder.data.sessions.SessionMutation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Lane-state reducer, porting pi's
 * `packages/agent/src/harness/reducer.ts` (audit P1-5): [validateRecordLog]
 * validates a bounded lane recovery slice without reading or mutating
 * session state, and [reduceLaneState] purely reconstructs one lane's
 * orchestration state from it. This is what makes the record log a recovery
 * log rather than telemetry.
 *
 * Adaptation boundaries (per-record-kind, documented at each reduction):
 * - Pathfinder's typed record surface covers the operation lifecycle trio
 *   and usage records; the remaining upstream kinds (step_attempt,
 *   tool_started, queue_enqueued, queue_cancelled, write_deferred) decode
 *   as [LaneRecord.DeferredRecord]s, and this reducer reads their payload
 *   fields from the preserved [JsonObject] exactly where upstream reads the
 *   typed members — same fields, same rules.
 * - `invalid_deferred_handle` is never raised: pathfinder's
 *   `AssistantMessage` carries no deferred-handle member (no adapter
 *   produces `DEFERRED` responses; see `ai/core/Types.kt`), so upstream's
 *   handle check has no comparable input. The reason stays in
 *   [RecordLogCorruptionReason] for shape parity.
 * - Provisioned entries (run intents' initialMessages, queue targets,
 *   write_deferred targets) are preserved as JSON payloads; comparison with
 *   a persisted entry round-trips the typed entry through the JSONL codec
 *   (see [matchesProvisionedEntry]).
 * - `deriveEffectiveConfiguration` is not duplicated: the configuration
 *   fold is [Conversation.effectiveConfiguration], applied to the slice's
 *   configuration + own entries rooted at [LaneReductionInput.leafId]
 *   (upstream folds the same entries in seq order; for the bounded slices
 *   recovery passes, the active-path fold is the same entries in the same
 *   order).
 * - Not reduced (documented exclusions matching the ported scope):
 *   `toolBatch` (pathfinder registers no tools, so no assistant entry
 *   carries tool calls to batch — `tool_started` records are still
 *   validated), and the open operation's `deferred` handle (no deferred
 *   producers; see above).
 */

/**
 * Machine-readable category for a contradiction in a lane's durable recovery
 * slice (reducer.ts `RecordLogCorruptionReason`): states the single-writer
 * record protocol cannot produce, not ordinary operation failures or
 * incomplete-but-recoverable intent/result prefixes. Restore must reject
 * such states rather than repair or continue them; the accompanying error
 * message supplies human-readable detail.
 */
enum class RecordLogCorruptionReason {
    MULTIPLE_OPEN_OPERATIONS,
    UNKNOWN_OPERATION,
    RECORD_AFTER_FINISH,
    NON_CONSECUTIVE_ATTEMPT,
    INVALID_COMPACTION_REASON,
    QUEUE_AFTER_ABORT,
    INVALID_QUEUE_CANCELLATION,
    INCONSISTENT_STEP,
    TOOL_CALL_MISMATCH,
    DUPLICATE_TOOL_INVOCATION,
    PROVISIONED_ENTRY_MISMATCH,
    INVALID_DEFERRED_HANDLE,
}

/** Reducer.ts `RecordLogCorruption`. */
class RecordLogCorruption(
    val reason: RecordLogCorruptionReason,
    message: String,
) : Exception(message)

/** Reducer.ts `RecordLogSlice`. */
data class RecordLogSlice(
    val lane: String,
    val openOperations: List<LaneRecord.OperationStartedRecord>,
    val records: List<LaneRecord>,
    /** Operation-owned entries plus entries fetched directly by provisioned or referenced ids. */
    val entries: List<SessionEntry>,
)

/** Reducer.ts `TerminalFailureState`. */
data class TerminalFailureState(
    val entryId: String,
    val source: Source,
    val message: AssistantMessage,
) {
    enum class Source { STEP, DEFERRED_FETCH }
}

/** Reducer.ts `LaneState.operation.step`. */
data class LaneStepState(
    val kind: Kind,
    val attempts: Int,
    val resultEntryId: String,
    val compactionReason: String? = null,
) {
    enum class Kind { ASSISTANT, COMPACTION, BRANCH_SUMMARY }
}

/** Reducer.ts `LaneState.operation.newestOwn`. */
data class NewestOwnState(
    val entryId: String,
    /** The entry's wire `type` (reducer.ts carries pi's Entry["type"]). */
    val type: String,
    val role: MessageRole? = null,
    val stopReason: StopReason? = null,
)

/** Reducer.ts `LaneState.operation.targets`. */
data class LaneOperationTargets(
    val result: Boolean? = null,
    val summary: Boolean? = null,
)

/**
 * Reducer.ts `LaneState.operation` reduced to the computable fields (see
 * file KDoc: `toolBatch` and `deferred` are not reduced). Provisioned-entry
 * lists ([missingInitialMessages], [pendingSteer], [pendingFollowUp],
 * [pendingWrites], [LaneState.pendingNextRun]) keep their upstream shape as
 * the preserved provisioned JSON payloads.
 */
data class LaneOperationState(
    val id: String,
    val kind: OperationIntent.Kind,
    val aborting: Boolean,
    val step: LaneStepState? = null,
    val missingInitialMessages: List<JsonObject> = emptyList(),
    val pendingSteer: List<JsonObject> = emptyList(),
    val pendingFollowUp: List<JsonObject> = emptyList(),
    val pendingWrites: List<JsonObject> = emptyList(),
    val overflowRecoveryUsed: Boolean = false,
    val newestOwn: NewestOwnState? = null,
    val targets: LaneOperationTargets = LaneOperationTargets(),
)

/** Reducer.ts `LaneState` (see [LaneOperationState] for reductions). */
data class LaneState(
    val lane: String,
    val leafId: String?,
    val operation: LaneOperationState? = null,
    val pendingNextRun: List<JsonObject> = emptyList(),
)

/** Reducer.ts `LaneReductionInput`. */
data class LaneReductionInput(
    val lane: String,
    val openOperations: List<LaneRecord.OperationStartedRecord>,
    val records: List<LaneRecord>,
    val entries: List<SessionEntry>,
    val leafId: String?,
    /** Entries appended by the open operation, oldest first. Empty when idle. */
    val ownEntries: List<SessionEntry> = emptyList(),
    /**
     * Bounded effective-state lookups at the operation anchor or idle leaf,
     * oldest first. Folded by [Conversation.effectiveConfiguration] (see
     * file KDoc — reducer.ts `deriveEffectiveConfiguration`).
     */
    val configurationEntries: List<SessionEntry> = emptyList(),
    /** Harness option fallbacks used when no persisted value exists. */
    val defaults: Conversation.EffectiveConfiguration = Conversation.EffectiveConfiguration(),
)

/** Reducer.ts `LaneReductionResult`. */
data class LaneReductionResult(
    val laneState: LaneState,
    val effectiveConfiguration: Conversation.EffectiveConfiguration,
    val terminalFailure: TerminalFailureState? = null,
)

/**
 * Load-time lane classification over `findOpenOperations`' `limit: 2`
 * recovery contract (harness/session/types.ts:237): zero open operations
 * mean the lane is idle, one means it is suspended/interrupted, and two or
 * more mean at least two operations are open — corruption (the reducer's
 * `multiple_open_operations`). This is the minimal classification surface
 * when only the open-operation seed (not the record log) has been read.
 */
sealed interface LaneRecovery {
    data object Idle : LaneRecovery

    data class Suspended(val kind: OperationIntent.Kind) : LaneRecovery

    data class Corrupt(val reason: RecordLogCorruptionReason) : LaneRecovery
}

/** Classifies [openOperations] (newest first) per the contract above. */
fun classifyLaneRecovery(openOperations: List<LaneRecord.OperationStartedRecord>): LaneRecovery = when {
    openOperations.isEmpty() -> LaneRecovery.Idle
    openOperations.size == 1 -> LaneRecovery.Suspended(openOperations[0].intent.kind)
    else -> LaneRecovery.Corrupt(RecordLogCorruptionReason.MULTIPLE_OPEN_OPERATIONS)
}

private fun corrupt(reason: RecordLogCorruptionReason, message: String): Nothing =
    throw RecordLogCorruption(reason, message)

// ---- deferred record payload access (see file KDoc) ----

private fun LaneRecord.runIdOrNull(): String? = when (this) {
    is LaneRecord.AbortRequestedRecord -> runId
    is LaneRecord.OperationFinishedRecord -> runId
    is LaneRecord.UsageRecord -> fields.string("runId")
    is LaneRecord.DeferredRecord -> fields.string("runId")
    is LaneRecord.OperationStartedRecord -> null
}

/**
 * Reducer.ts `matchesProvisionedEntry`: the entry's payload (everything but
 * parentId/seq/timestamp) deep-equals the provisioned target. Adaptation:
 * the typed entry is round-tripped through the JSONL codec's wire shape
 * (pi compares JSON objects directly; pathfinder holds typed entries, so the
 * entry is re-encoded and the storage-assigned keys dropped).
 */
private fun matchesProvisionedEntry(entry: SessionEntry, target: JsonObject): Boolean {
    val line = JsonlCodec.encodeMutation(SessionMutation.Entry(lane = null, entry = entry))
    val encoded = lenientJson.parseToJsonElement(line) as JsonObject
    val payload = JsonObject(encoded.filterKeys { it !in PROVISION_EXCLUDED_KEYS })
    return payload == target
}

private val PROVISION_EXCLUDED_KEYS = setOf("kind", "lane", "parentId", "seq", "timestamp")

/**
 * Reducer.ts `validateExactProvisionedEntry`: when an entry with the
 * target's id exists, it must carry exactly the provisioned content.
 */
private fun validateExactProvisionedEntry(entriesById: Map<String, SessionEntry>, target: JsonObject) {
    val id = target.string("id") ?: return
    val entry = entriesById[id]
    if (entry != null && !matchesProvisionedEntry(entry, target)) {
        corrupt(
            RecordLogCorruptionReason.PROVISIONED_ENTRY_MISMATCH,
            "Provisioned entry $id exists with content different from its intent",
        )
    }
}

/** Reducer.ts `validateResultEntry`. */
private fun validateResultEntry(
    entriesById: Map<String, SessionEntry>,
    resultEntryId: String,
    matches: (SessionEntry) -> Boolean,
    description: String,
) {
    val entry = entriesById[resultEntryId]
    if (entry != null && !matches(entry)) {
        corrupt(
            RecordLogCorruptionReason.PROVISIONED_ENTRY_MISMATCH,
            "Provisioned $description entry $resultEntryId exists with different content",
        )
    }
}

/** The intent payload's run `initialMessages`, when present. */
private fun intentInitialMessages(record: LaneRecord.OperationStartedRecord): List<JsonObject> =
    (record.intent.payload["initialMessages"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?: emptyList()

private fun isAssistantMessage(entry: SessionEntry): Boolean =
    entry is MessageEntry && entry.message is AssistantMessage

/** Reducer.ts `validateOperationResult`. */
private fun validateOperationResult(entriesById: Map<String, SessionEntry>, record: LaneRecord.OperationStartedRecord) {
    when (record.intent.kind) {
        OperationIntent.Kind.RUN ->
            for (target in intentInitialMessages(record)) validateExactProvisionedEntry(entriesById, target)
        OperationIntent.Kind.COMPACTION -> {
            val resultEntryId = record.intent.payload.string("resultEntryId") ?: return
            validateResultEntry(
                entriesById,
                resultEntryId,
                { it is CompactionEntry },
                "manual compaction",
            )
        }
        OperationIntent.Kind.NAVIGATION -> {
            val summaryEntryId = record.intent.payload.string("summaryEntryId") ?: return
            validateResultEntry(
                entriesById,
                summaryEntryId,
                { it is BranchSummaryEntry },
                "navigation summary",
            )
        }
    }
}

private class StepAttemptSeries(
    val record: LaneRecord.DeferredRecord,
    val fields: JsonObject,
) {
    val step: String = fields.string("step") ?: ""
    val attempt: Int = fields.strictInt("attempt") ?: 0
    val resultEntryId: String = fields.string("resultEntryId") ?: ""
    val compactionReason: String? = fields.string("compactionReason")
    val seq: Long = record.seq
}

/** Reducer.ts `validateAttemptReason`. */
private fun validateAttemptReason(attempt: StepAttemptSeries) {
    val reason = attempt.compactionReason
    if (attempt.step == "compaction") {
        if (reason != "manual" && reason != "threshold" && reason != "overflow") {
            corrupt(
                RecordLogCorruptionReason.INVALID_COMPACTION_REASON,
                "Compaction attempt ${attempt.record.id} has no valid compaction reason",
            )
        }
    } else if (reason != null) {
        corrupt(
            RecordLogCorruptionReason.INVALID_COMPACTION_REASON,
            "${attempt.step} attempt ${attempt.record.id} has a compaction reason",
        )
    }
}

/** Reducer.ts `validateAttemptSequence`. */
private fun validateAttemptSequence(
    attempt: StepAttemptSeries,
    previous: StepAttemptSeries?,
    entriesById: Map<String, SessionEntry>,
) {
    val previousResult = previous?.let { entriesById[it.resultEntryId] }
    val continuesSeries =
        previous != null &&
            previous.step == attempt.step &&
            (previousResult == null || previousResult.seq >= attempt.seq)
    val expectedAttempt = if (continuesSeries) previous!!.attempt + 1 else 1
    if (attempt.attempt != expectedAttempt) {
        corrupt(
            RecordLogCorruptionReason.NON_CONSECUTIVE_ATTEMPT,
            "${attempt.step} attempt ${attempt.record.id} is ${attempt.attempt}; expected $expectedAttempt",
        )
    }
    if (!continuesSeries || attempt.step == "assistant" || previous == null) return
    if (attempt.resultEntryId != previous.resultEntryId) {
        corrupt(
            RecordLogCorruptionReason.INCONSISTENT_STEP,
            "${attempt.step} attempts disagree on their result entry id",
        )
    }
    if (attempt.compactionReason != previous.compactionReason) {
        corrupt(
            RecordLogCorruptionReason.INCONSISTENT_STEP,
            "${attempt.step} attempts disagree on their compaction reason",
        )
    }
}

/** Reducer.ts `validateAttemptResult`. */
private fun validateAttemptResult(entriesById: Map<String, SessionEntry>, attempt: StepAttemptSeries) {
    when (attempt.step) {
        "assistant" -> validateResultEntry(
            entriesById,
            attempt.resultEntryId,
            ::isAssistantMessage,
            "assistant result",
        )
        "compaction" -> validateResultEntry(
            entriesById,
            attempt.resultEntryId,
            { it is CompactionEntry },
            "compaction result",
        )
        "branch_summary" -> validateResultEntry(
            entriesById,
            attempt.resultEntryId,
            { it is BranchSummaryEntry },
            "branch-summary result",
        )
    }
}

/** Reducer.ts `validateToolStart`. */
private fun validateToolStart(
    record: LaneRecord.DeferredRecord,
    entriesById: Map<String, SessionEntry>,
    invocations: MutableSet<String>,
) {
    val fields = record.fields
    val assistantEntryId = fields.string("assistantEntryId") ?: ""
    val toolIndex = fields.strictInt("toolIndex") ?: -1
    val invocation = "$assistantEntryId\u0000$toolIndex"
    if (invocation in invocations) {
        corrupt(
            RecordLogCorruptionReason.DUPLICATE_TOOL_INVOCATION,
            "Tool invocation $assistantEntryId:$toolIndex is duplicated",
        )
    }
    invocations.add(invocation)

    val assistantEntry = entriesById[assistantEntryId]
    if (assistantEntry !is MessageEntry || assistantEntry.message !is AssistantMessage) {
        corrupt(
            RecordLogCorruptionReason.TOOL_CALL_MISMATCH,
            "Tool start ${record.id} does not reference an assistant entry",
        )
        return
    }
    val toolCalls = assistantEntry.message.content.filterIsInstance<ToolCall>()
    val toolCall = toolCalls.getOrNull(toolIndex)
    if (toolCall == null || toolCall.id != fields.string("toolCallId") || toolCall.name != fields.string("toolName")) {
        corrupt(
            RecordLogCorruptionReason.TOOL_CALL_MISMATCH,
            "Tool start ${record.id} does not match its assistant tool-call ordinal",
        )
        return
    }

    val resultEntryId = fields.string("resultEntryId") ?: return
    validateResultEntry(
        entriesById,
        resultEntryId,
        { entry ->
            entry is MessageEntry &&
                entry.message is ToolResultMessage &&
                (entry.message as ToolResultMessage).toolCallId == toolCall.id &&
                (entry.message as ToolResultMessage).toolName == toolCall.name
        },
        "tool result",
    )
}

/** Reducer.ts `validateDeferredHandles` — see file KDoc (not reducible; no deferred handles exist). */

/**
 * Validates a bounded lane recovery slice without reading or mutating
 * session state (reducer.ts `validateRecordLog`).
 */
fun validateRecordLog(input: RecordLogSlice) {
    if (input.openOperations.size > 1) {
        corrupt(
            RecordLogCorruptionReason.MULTIPLE_OPEN_OPERATIONS,
            "Lane ${input.lane} has at least two open operations",
        )
    }

    val entriesById = input.entries.associateBy { it.id }
    val starts = HashMap<String, LaneRecord.OperationStartedRecord>()
    val finishedAt = HashMap<String, Long>()
    val abortedAt = HashMap<String, Long>()
    val queueEnqueues = HashMap<String, LaneRecord.DeferredRecord>()
    val latestAttempt = HashMap<String, StepAttemptSeries>()
    val toolInvocations = HashSet<String>()
    val records = input.records.sortedBy { it.seq }

    for (record in records) {
        if (record is LaneRecord.OperationStartedRecord) {
            starts[record.id] = record
            validateOperationResult(entriesById, record)
            continue
        }

        val runId = record.runIdOrNull()
        if (runId != null) {
            if (runId !in starts) {
                corrupt(
                    RecordLogCorruptionReason.UNKNOWN_OPERATION,
                    "Record ${record.id} references unknown operation $runId",
                )
            }
            val finishSeq = finishedAt[runId]
            if (finishSeq != null && record.seq > finishSeq) {
                corrupt(
                    RecordLogCorruptionReason.RECORD_AFTER_FINISH,
                    "Record ${record.id} follows the finish of operation $runId",
                )
            }
        }

        when (record) {
            is LaneRecord.OperationStartedRecord -> Unit // handled above
            is LaneRecord.OperationFinishedRecord -> finishedAt[record.runId] = record.seq
            is LaneRecord.AbortRequestedRecord -> abortedAt[record.runId] = record.seq
            is LaneRecord.UsageRecord -> Unit
            is LaneRecord.DeferredRecord -> when (record.type) {
                "step_attempt" -> {
                    val attempt = StepAttemptSeries(record, record.fields)
                    validateAttemptReason(attempt)
                    validateAttemptSequence(attempt, latestAttempt[attempt.fields.string("runId") ?: ""], entriesById)
                    validateAttemptResult(entriesById, attempt)
                    latestAttempt[attempt.fields.string("runId") ?: ""] = attempt
                }
                "tool_started" -> validateToolStart(record, entriesById, toolInvocations)
                "queue_enqueued" -> {
                    val queue = record.fields.string("queue")
                    val target = record.fields["target"] as? JsonObject ?: JsonObject(emptyMap())
                    val enqueueRunId = record.fields.string("runId")
                    val abortedSeq = enqueueRunId?.let(abortedAt::get)
                    if (
                        queue != "nextRun" &&
                        abortedSeq != null &&
                        record.seq > abortedSeq
                    ) {
                        corrupt(
                            RecordLogCorruptionReason.QUEUE_AFTER_ABORT,
                            "$queue item ${target.string("id") ?: record.id} was enqueued after abort",
                        )
                    }
                    val targetId = target.string("id")
                    if (targetId != null) queueEnqueues[targetId] = record
                    validateExactProvisionedEntry(entriesById, target)
                }
                "queue_cancelled" -> {
                    val entryId = record.fields.string("entryId")
                    val enqueue = entryId?.let(queueEnqueues::get)
                    if (
                        enqueue == null ||
                        enqueue.seq >= record.seq ||
                        enqueue.fields.string("runId") != record.fields.string("runId") ||
                        entriesById.containsKey(entryId)
                    ) {
                        corrupt(
                            RecordLogCorruptionReason.INVALID_QUEUE_CANCELLATION,
                            "Queue cancellation ${record.id} has no pending matching enqueue",
                        )
                    }
                }
                "write_deferred" ->
                    validateExactProvisionedEntry(entriesById, record.fields["target"] as? JsonObject ?: JsonObject(emptyMap()))
                else -> Unit
            }
        }
    }
}

private fun <T : Any> bySequence(values: List<T>, seq: (T) -> Long): List<T> =
    values.sortedBy(seq)

private fun entryType(entry: SessionEntry): String = when (entry) {
    is MessageEntry -> "message"
    is CompactionEntry -> "compaction"
    is BranchSummaryEntry -> "branch_summary"
    else -> "custom"
}

/** Reducer.ts `deriveNewestOwn`. */
private fun deriveNewestOwn(entry: SessionEntry?): NewestOwnState? {
    if (entry == null) return null
    if (entry !is MessageEntry) return NewestOwnState(entryId = entry.id, type = entryType(entry))
    val message = entry.message
    if (message !is AssistantMessage) {
        return NewestOwnState(entryId = entry.id, type = "message", role = message.role)
    }
    return NewestOwnState(entryId = entry.id, type = "message", role = message.role, stopReason = message.stopReason)
}

/**
 * Purely reconstructs one lane's orchestration state from its bounded
 * recovery inputs (reducer.ts `reduceLaneState`); see the file KDoc for the
 * documented reductions.
 */
fun reduceLaneState(input: LaneReductionInput): LaneReductionResult {
    validateRecordLog(RecordLogSlice(input.lane, input.openOperations, input.records, input.entries))

    val records = bySequence(input.records) { it.seq }
    val ownEntries = bySequence(input.ownEntries) { it.seq }
    val entriesById = buildMap {
        for (entry in input.entries) put(entry.id, entry)
        for (entry in ownEntries) put(entry.id, entry)
    }
    val cancelledQueueIds = records
        .filterIsInstance<LaneRecord.DeferredRecord>()
        .filter { it.type == "queue_cancelled" }
        .mapNotNull { it.fields.string("entryId") }
        .toHashSet()
    val pendingQueueRecords = records
        .filterIsInstance<LaneRecord.DeferredRecord>()
        .filter { it.type == "queue_enqueued" }
        .mapNotNull { record ->
            val target = record.fields["target"] as? JsonObject ?: return@mapNotNull null
            val id = target.string("id") ?: return@mapNotNull null
            if (id !in entriesById && id !in cancelledQueueIds) record to target else null
        }
    val started = input.openOperations.firstOrNull()
    val capturedInitialMessageIds = started
        ?.takeIf { it.intent.kind == OperationIntent.Kind.RUN }
        ?.let(::intentInitialMessages)
        ?.mapNotNull { it.string("id") }
        ?.toHashSet()
        ?: emptySet()
    val pendingNextRun = pendingQueueRecords
        .filter { (record, _) -> record.fields.string("queue") == "nextRun" }
        .map { (_, target) -> target }
        .filterNot { it.string("id") in capturedInitialMessageIds }
    // Reducer.ts deriveEffectiveConfiguration — reuse the ported fold (see
    // file KDoc) instead of duplicating it here, seeding unset fields from
    // the harness defaults (upstream starts the fold from `input.defaults`).
    val folded = Conversation(
        entries = input.configurationEntries + ownEntries,
        leafId = input.leafId,
    ).effectiveConfiguration()
    val sawThinkingLevelEntry = (input.configurationEntries + ownEntries)
        .any { it is works.resolve.pathfinder.data.sessions.ThinkingLevelEntry }
    val effectiveConfiguration = Conversation.EffectiveConfiguration(
        model = folded.model ?: input.defaults.model,
        thinkingLevel = if (sawThinkingLevelEntry) folded.thinkingLevel else input.defaults.thinkingLevel,
        activeToolNames = folded.activeToolNames ?: input.defaults.activeToolNames,
    )

    if (started == null) {
        return LaneReductionResult(
            laneState = LaneState(lane = input.lane, leafId = input.leafId, operation = null, pendingNextRun = pendingNextRun),
            effectiveConfiguration = effectiveConfiguration,
            terminalFailure = null,
        )
    }

    val operationRecords = records.filter { record ->
        if (record is LaneRecord.OperationStartedRecord) record.id == started.id else record.runIdOrNull() == started.id
    }
    val aborting = operationRecords.any { it is LaneRecord.AbortRequestedRecord }
    fun pendingQueue(queue: String): List<JsonObject> =
        if (aborting) {
            emptyList()
        } else {
            pendingQueueRecords
                .filter { (record, _) -> record.fields.string("queue") == queue && record.fields.string("runId") == started.id }
                .map { (_, target) -> target }
        }
    val pendingSteer = pendingQueue("steer")
    val pendingFollowUp = pendingQueue("followUp")
    val pendingWrites = operationRecords
        .filterIsInstance<LaneRecord.DeferredRecord>()
        .filter { it.type == "write_deferred" }
        .mapNotNull { it.fields["target"] as? JsonObject }
        .filterNot { it.string("id") in entriesById }
    val missingInitialMessages = if (started.intent.kind == OperationIntent.Kind.RUN) {
        intentInitialMessages(started).filterNot { it.string("id") in entriesById }
    } else {
        emptyList()
    }

    val newestAttempt = operationRecords
        .filterIsInstance<LaneRecord.DeferredRecord>()
        .filter { it.type == "step_attempt" }
        .lastOrNull()
    val step = newestAttempt
        ?.takeIf { it.fields.string("resultEntryId") !in entriesById }
        ?.let { record ->
            val stepName = record.fields.string("step")
            LaneStepState(
                kind = when (stepName) {
                    "assistant" -> LaneStepState.Kind.ASSISTANT
                    "compaction" -> LaneStepState.Kind.COMPACTION
                    else -> LaneStepState.Kind.BRANCH_SUMMARY
                },
                attempts = record.fields.strictInt("attempt") ?: 1,
                resultEntryId = record.fields.string("resultEntryId") ?: "",
                compactionReason = record.fields.string("compactionReason").takeIf { stepName == "compaction" },
            )
        }

    val consumedInputIds = HashSet<String>()
    if (started.intent.kind == OperationIntent.Kind.RUN) {
        for (target in intentInitialMessages(started)) target.string("id")?.let(consumedInputIds::add)
    }
    for (record in operationRecords) {
        if (record is LaneRecord.DeferredRecord && record.type == "queue_enqueued" && record.fields.string("queue") != "nextRun") {
            (record.fields["target"] as? JsonObject)?.string("id")?.let(consumedInputIds::add)
        }
    }
    var newestConsumedInputSequence = Long.MIN_VALUE
    for (id in consumedInputIds) {
        val entry = entriesById[id]
        if (entry is MessageEntry) newestConsumedInputSequence = maxOf(newestConsumedInputSequence, entry.seq)
    }
    val overflowRecoveryUsed = operationRecords.any { record ->
        record is LaneRecord.DeferredRecord &&
            record.type == "step_attempt" &&
            record.fields.string("step") == "compaction" &&
            record.fields.string("compactionReason") == "overflow" &&
            record.seq > newestConsumedInputSequence
    }

    val newestOwnEntry = ownEntries.lastOrNull()
    val newestOwn = deriveNewestOwn(newestOwnEntry)
    val targets = LaneOperationTargets(
        result = if (started.intent.kind == OperationIntent.Kind.COMPACTION) {
            started.intent.payload.string("resultEntryId") in entriesById
        } else {
            null
        },
        summary = if (started.intent.kind == OperationIntent.Kind.NAVIGATION) {
            started.intent.payload.string("summaryEntryId")?.let { it in entriesById }
        } else {
            null
        },
    )

    val deferredWriteIds = operationRecords
        .filterIsInstance<LaneRecord.DeferredRecord>()
        .filter { it.type == "write_deferred" }
        .mapNotNullTo(HashSet()) { (it.fields["target"] as? JsonObject)?.string("id") }
    var terminalFailure: TerminalFailureState? = null
    if (
        newestOwnEntry is MessageEntry &&
        newestOwnEntry.message is AssistantMessage &&
        newestOwnEntry.message.stopReason == StopReason.ERROR &&
        newestOwnEntry.id !in deferredWriteIds
    ) {
        val newestMessage = newestOwnEntry.message as AssistantMessage
        val producedByStep = operationRecords.any { record ->
            record is LaneRecord.DeferredRecord &&
                record.type == "step_attempt" &&
                record.fields.string("resultEntryId") == newestOwnEntry.id
        }
        val previousOwnEntry = ownEntries.getOrNull(ownEntries.size - 2)
        val producedByDeferredFetch = operationRecords.any { record ->
            record is LaneRecord.UsageRecord &&
                record.fields.string("cause") == "deferred_fetch" &&
                record.fields.string("entryId") == newestOwnEntry.id
        } || (
            previousOwnEntry is MessageEntry &&
                previousOwnEntry.message is AssistantMessage &&
                previousOwnEntry.message.stopReason == StopReason.DEFERRED
            )
        if (producedByStep || producedByDeferredFetch) {
            terminalFailure = TerminalFailureState(
                entryId = newestOwnEntry.id,
                source = if (producedByStep) TerminalFailureState.Source.STEP else TerminalFailureState.Source.DEFERRED_FETCH,
                message = newestMessage,
            )
        }
    }

    return LaneReductionResult(
        laneState = LaneState(
            lane = input.lane,
            leafId = input.leafId,
            operation = LaneOperationState(
                id = started.id,
                kind = started.intent.kind,
                aborting = aborting,
                step = step,
                missingInitialMessages = missingInitialMessages,
                pendingSteer = pendingSteer,
                pendingFollowUp = pendingFollowUp,
                pendingWrites = pendingWrites,
                overflowRecoveryUsed = overflowRecoveryUsed,
                newestOwn = newestOwn,
                targets = targets,
            ),
            pendingNextRun = pendingNextRun,
        ),
        effectiveConfiguration = effectiveConfiguration,
        terminalFailure = terminalFailure,
    )
}
