package works.resolve.pathfinder.codingagent.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.LaneRecord
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.session.OperationIntent
import works.resolve.pathfinder.codingagent.core.session.OperationOutcome
import works.resolve.pathfinder.codingagent.core.session.SessionEntry

class ReducerTest {

    private var nextSeq = 0L
    private fun nextSeq(): Long = ++nextSeq

    private fun messageEntry(
        message: works.resolve.pathfinder.ai.Message,
        id: String = "e${nextSeq()}",
        parentId: String? = null
    ) = MessageEntry(
        id = id,
        seq = nextSeq(),
        parentId = parentId,
        timestamp = id.hashCode().toLong(),
        message = message
    )

    private fun userEntry(text: String, id: String = "e${nextSeq()}", parentId: String? = null) =
        messageEntry(UserMessage.ofText(text), id, parentId)

    private fun assistantEntry(
        text: String,
        parentId: String? = null,
        stopReason: StopReason = StopReason.STOP
    ) = messageEntry(
        AssistantMessage(
            content = listOf(TextContent(text)),
            api = "openai-completions",
            provider = "zai",
            model = "glm-4.6",
            stopReason = stopReason
        ),
        parentId = parentId
    )

    private fun started(
        id: String = "op1",
        lane: String = "main",
        intent: OperationIntent = OperationIntent.run()
    ) = LaneRecord.OperationStartedRecord(
        id = id,
        lane = lane,
        seq = nextSeq(),
        timestamp = 1L,
        sourceLeafId = null,
        intent = intent
    )

    private fun finished(
        runId: String = "op1",
        outcome: OperationOutcome = OperationOutcome.COMPLETED
    ) = LaneRecord.OperationFinishedRecord(
        id = "f-${nextSeq()}",
        lane = "main",
        seq = nextSeq(),
        timestamp = 1L,
        runId = runId,
        outcome = outcome
    )

    private fun aborted(runId: String = "op1") = LaneRecord.AbortRequestedRecord(
        id = "a-${nextSeq()}",
        lane = "main",
        seq = nextSeq(),
        timestamp = 1L,
        runId = runId
    )

    private fun deferred(
        type: String,
        runId: String?,
        fields: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
        seq: Long = nextSeq()
    ): LaneRecord.DeferredRecord = LaneRecord.DeferredRecord(
        id = "d-$seq",
        lane = "main",
        seq = seq,
        timestamp = 1L,
        type = type,
        fields = buildJsonObject {
            runId?.let { put("runId", it) }
            fields.forEach { (k, v) -> put(k, v) }
        }
    )

    private fun provisionedUser(id: String, text: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "message")
        putJsonObject("message") {
            put("role", "user")
            put("timestamp", 0L)
            put(
                "content",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    )
                )
            )
        }
    }

    private fun input(
        lane: String = "main",
        openOperations: List<LaneRecord.OperationStartedRecord> = emptyList(),
        records: List<LaneRecord> = emptyList(),
        entries: List<SessionEntry> = emptyList(),
        leafId: String? = null,
        ownEntries: List<SessionEntry> = emptyList(),
        configurationEntries: List<SessionEntry> = emptyList(),
        defaults: Conversation.EffectiveConfiguration = Conversation.EffectiveConfiguration()
    ) = LaneReductionInput(
        lane = lane,
        openOperations = openOperations,
        records = records,
        entries = entries,
        leafId = leafId,
        ownEntries = ownEntries,
        configurationEntries = configurationEntries,
        defaults = defaults
    )

    private fun reasonOf(block: () -> Unit): RecordLogCorruptionReason = try {
        block()
        throw AssertionError("expected RecordLogCorruption")
    } catch (e: RecordLogCorruption) {
        e.reason
    }

    @Test
    fun `classifyLaneRecovery distinguishes idle, suspended, and corrupt`() {
        assertEquals(LaneRecovery.Idle, classifyLaneRecovery(emptyList()))
        assertEquals(
            LaneRecovery.Suspended(OperationIntent.Kind.RUN),
            classifyLaneRecovery(listOf(started()))
        )
        assertEquals(
            LaneRecovery.Suspended(OperationIntent.Kind.COMPACTION),
            classifyLaneRecovery(listOf(started(intent = OperationIntent.compaction("r1"))))
        )
        assertEquals(
            LaneRecovery.Corrupt(RecordLogCorruptionReason.MULTIPLE_OPEN_OPERATIONS),
            classifyLaneRecovery(listOf(started(), started(id = "op2")))
        )
    }

    @Test
    fun `idle lane reduces with no operation and no failure`() {
        val result = reduceLaneState(input(entries = emptyList(), leafId = null))
        assertNull(result.laneState.operation)
        assertNull(result.terminalFailure)
        assertEquals("main", result.laneState.lane)
    }

    @Test
    fun `suspended run operation reduces its identity, aborting, step, and newest own`() {
        val own = listOf(userEntry("hi"), assistantEntry("there"))
        val op = started()
        val records = listOf<LaneRecord>(
            op,
            deferred(
                "step_attempt",
                runId = "op1",
                fields = mapOf(
                    "step" to kotlinx.serialization.json.JsonPrimitive("assistant"),
                    "attempt" to kotlinx.serialization.json.JsonPrimitive(1),
                    "resultEntryId" to kotlinx.serialization.json.JsonPrimitive("pending")
                )
            )
        )
        val result = reduceLaneState(
            input(
                openOperations = listOf(op),
                records = records,
                ownEntries = own,
                leafId = own.last().id
            )
        )
        val operation = result.laneState.operation!!
        assertEquals("op1", operation.id)
        assertEquals(OperationIntent.Kind.RUN, operation.kind)
        assertTrue(!operation.aborting)
        assertEquals(LaneStepState.Kind.ASSISTANT, operation.step!!.kind)
        assertEquals(1, operation.step!!.attempts)
        assertEquals("pending", operation.step!!.resultEntryId)
        assertEquals(own.last().id, operation.newestOwn!!.entryId)
        assertNull(result.terminalFailure)
    }

    @Test
    fun `aborting operation drops pending queues and marks aborting`() {
        val op = started()
        val result = reduceLaneState(
            input(
                openOperations = listOf(op),
                records = listOf(op, aborted())
            )
        )
        assertTrue(result.laneState.operation!!.aborting)
        assertEquals(emptyList<Any>(), result.laneState.operation!!.pendingSteer)
    }

    @Test
    fun `compaction and navigation targets reduce from their intent result entries`() {
        val summary = BranchSummaryEntry(
            id = "s1",
            seq = nextSeq(),
            parentId = "target",
            timestamp = 1L,
            fromId = "old",
            summary = "s"
        )
        val navIntent = OperationIntent(
            kind = OperationIntent.Kind.NAVIGATION,
            payload = buildJsonObject {
                put("kind", "navigation")
                put("targetId", "target")
                put("summarize", true)
                put("summaryEntryId", "s1")
            }
        )
        val nav = started(id = "nav", intent = navIntent)
        val result = reduceLaneState(
            input(
                openOperations = listOf(nav),
                records = listOf<LaneRecord>(nav),
                entries = listOf(summary)
            )
        )
        assertEquals(true, result.laneState.operation!!.targets.summary)

        val compaction = started(id = "c1", intent = OperationIntent.compaction("missing"))
        val result2 = reduceLaneState(
            input(openOperations = listOf(compaction), records = listOf<LaneRecord>(compaction))
        )
        assertEquals(false, result2.laneState.operation!!.targets.result)
    }

    @Test
    fun `terminal failure reduces from an error own entry produced by a step`() {
        val error = messageEntry(
            AssistantMessage(
                content = listOf(TextContent("boom")),
                api = "openai-completions",
                provider = "zai",
                model = "glm-4.6",
                stopReason = StopReason.ERROR,
                errorMessage = "boom"
            )
        )
        val op = started()
        val records = listOf<LaneRecord>(
            op,
            deferred(
                "step_attempt",
                runId = "op1",
                fields = mapOf(
                    "step" to kotlinx.serialization.json.JsonPrimitive("assistant"),
                    "attempt" to kotlinx.serialization.json.JsonPrimitive(1),
                    "resultEntryId" to kotlinx.serialization.json.JsonPrimitive(error.id)
                )
            )
        )
        val result =
            reduceLaneState(
                input(openOperations = listOf(op), records = records, ownEntries = listOf(error))
            )
        val failure = result.terminalFailure!!
        assertEquals(error.id, failure.entryId)
        assertEquals(TerminalFailureState.Source.STEP, failure.source)
    }

    @Test
    fun `effective configuration folds model changes on the path with defaults fallback`() {
        val change =
            ModelChangeEntry(
                id = "m1",
                seq = nextSeq(),
                parentId = null,
                timestamp = 1L,
                provider = "zai",
                modelId = "glm-4.6"
            )
        val result = reduceLaneState(
            input(
                entries = listOf(change),
                leafId = change.id,
                configurationEntries = listOf(change),
                defaults = Conversation.EffectiveConfiguration(
                    model = Conversation.SessionModelSelection(
                        "other",
                        "other-model"
                    ),
                    thinkingLevel = "high"
                )
            )
        )
        assertEquals("glm-4.6", result.effectiveConfiguration.model!!.modelId)
        assertEquals("high", result.effectiveConfiguration.thinkingLevel)
    }

    @Test
    fun `two open operations are multiple_open_operations`() {
        val a = started()
        val b = started(id = "op2")
        assertEquals(
            RecordLogCorruptionReason.MULTIPLE_OPEN_OPERATIONS,
            reasonOf { reduceLaneState(input(openOperations = listOf(a, b))) }
        )
    }

    @Test
    fun `record referencing an unknown operation is unknown_operation`() {
        val op = started()
        assertEquals(
            RecordLogCorruptionReason.UNKNOWN_OPERATION,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice("main", listOf(op), listOf(aborted(runId = "nope")), emptyList())
                )
            }
        )
    }

    @Test
    fun `record after finish is record_after_finish`() {
        val op = started()
        val fin = finished()
        assertEquals(
            RecordLogCorruptionReason.RECORD_AFTER_FINISH,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice("main", emptyList(), listOf(op, fin, aborted()), emptyList())
                )
            }
        )
    }

    @Test
    fun `wrong attempt number is non_consecutive_attempt`() {
        val op = started()
        assertEquals(
            RecordLogCorruptionReason.NON_CONSECUTIVE_ATTEMPT,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            deferred(
                                "step_attempt",
                                runId = "op1",
                                fields = mapOf(
                                    "step" to kotlinx.serialization.json.JsonPrimitive("assistant"),
                                    "attempt" to kotlinx.serialization.json.JsonPrimitive(2),
                                    "resultEntryId" to kotlinx.serialization.json.JsonPrimitive(
                                        "r"
                                    )
                                )
                            )
                        ),
                        emptyList()
                    )
                )
            }
        )
    }

    @Test
    fun `compaction attempt without a reason is invalid_compaction_reason`() {
        val op = started()
        assertEquals(
            RecordLogCorruptionReason.INVALID_COMPACTION_REASON,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            deferred(
                                "step_attempt",
                                runId = "op1",
                                fields = mapOf(
                                    "step" to kotlinx.serialization.json.JsonPrimitive(
                                        "compaction"
                                    ),
                                    "attempt" to kotlinx.serialization.json.JsonPrimitive(1),
                                    "resultEntryId" to kotlinx.serialization.json.JsonPrimitive(
                                        "r"
                                    )
                                )
                            )
                        ),
                        emptyList()
                    )
                )
            }
        )
    }

    @Test
    fun `queue item after abort is queue_after_abort`() {
        val op = started()
        assertEquals(
            RecordLogCorruptionReason.QUEUE_AFTER_ABORT,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            aborted(),
                            deferred(
                                "queue_enqueued",
                                runId = "op1",
                                fields = mapOf(
                                    "queue" to kotlinx.serialization.json.JsonPrimitive("steer")
                                ),
                                seq = 10L
                            )
                        ),
                        emptyList()
                    )
                )
            }
        )
    }

    @Test
    fun `cancellation without pending enqueue is invalid_queue_cancellation`() {
        val op = started()
        assertEquals(
            RecordLogCorruptionReason.INVALID_QUEUE_CANCELLATION,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            deferred(
                                "queue_cancelled",
                                runId = "op1",
                                fields = mapOf(
                                    "entryId" to kotlinx.serialization.json.JsonPrimitive("q1")
                                )
                            )
                        ),
                        emptyList()
                    )
                )
            }
        )
    }

    @Test
    fun `compaction attempts disagreeing on the result entry are inconsistent_step`() {
        val op = started()
        assertEquals(
            RecordLogCorruptionReason.INCONSISTENT_STEP,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            deferred(
                                "step_attempt",
                                runId = "op1",
                                fields = mapOf(
                                    "step" to kotlinx.serialization.json.JsonPrimitive(
                                        "compaction"
                                    ),
                                    "attempt" to kotlinx.serialization.json.JsonPrimitive(1),
                                    "resultEntryId" to
                                        kotlinx.serialization.json.JsonPrimitive("r1"),
                                    "compactionReason" to
                                        kotlinx.serialization.json.JsonPrimitive("threshold")
                                )
                            ),
                            deferred(
                                "step_attempt",
                                runId = "op1",
                                fields = mapOf(
                                    "step" to kotlinx.serialization.json.JsonPrimitive(
                                        "compaction"
                                    ),
                                    "attempt" to kotlinx.serialization.json.JsonPrimitive(2),
                                    "resultEntryId" to
                                        kotlinx.serialization.json.JsonPrimitive("r2"),
                                    "compactionReason" to
                                        kotlinx.serialization.json.JsonPrimitive("threshold")
                                )
                            )
                        ),
                        emptyList()
                    )
                )
            }
        )
    }

    @Test
    fun `tool start against a non-assistant entry is tool_call_mismatch`() {
        val op = started()
        val user = userEntry("hi")
        assertEquals(
            RecordLogCorruptionReason.TOOL_CALL_MISMATCH,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            deferred(
                                "tool_started",
                                runId = "op1",
                                fields = mapOf(
                                    "assistantEntryId" to
                                        kotlinx.serialization.json.JsonPrimitive(user.id),
                                    "toolIndex" to kotlinx.serialization.json.JsonPrimitive(0),
                                    "toolCallId" to kotlinx.serialization.json.JsonPrimitive("tc1"),
                                    "toolName" to kotlinx.serialization.json.JsonPrimitive("read"),
                                    "resultEntryId" to kotlinx.serialization.json.JsonPrimitive(
                                        "r"
                                    )
                                )
                            )
                        ),
                        listOf(user)
                    )
                )
            }
        )
    }

    @Test
    fun `duplicate tool invocation is duplicate_tool_invocation`() {
        val op = started()
        val assistant = messageEntry(
            AssistantMessage(
                content = listOf(ToolCall(id = "tc1", name = "read", arguments = "{}")),
                api = "openai-completions",
                provider = "zai",
                model = "glm-4.6"
            )
        )
        val fields = mapOf(
            "assistantEntryId" to kotlinx.serialization.json.JsonPrimitive(assistant.id),
            "toolIndex" to kotlinx.serialization.json.JsonPrimitive(0),
            "toolCallId" to kotlinx.serialization.json.JsonPrimitive("tc1"),
            "toolName" to kotlinx.serialization.json.JsonPrimitive("read"),
            "resultEntryId" to kotlinx.serialization.json.JsonPrimitive("r")
        )
        assertEquals(
            RecordLogCorruptionReason.DUPLICATE_TOOL_INVOCATION,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            deferred("tool_started", "op1", fields),
                            deferred("tool_started", "op1", fields)
                        ),
                        listOf(assistant)
                    )
                )
            }
        )
    }

    @Test
    fun `existing entry differing from its provisioned target is provisioned_entry_mismatch`() {
        val op = started()
        val persisted = userEntry("actual text", id = "u1")
        assertEquals(
            RecordLogCorruptionReason.PROVISIONED_ENTRY_MISMATCH,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            deferred(
                                "write_deferred",
                                runId = "op1",
                                fields = mapOf("target" to provisionedUser("u1", "different text"))
                            )
                        ),
                        listOf(persisted)
                    )
                )
            }
        )
    }

    @Test
    fun `matching provisioned target passes validation`() {
        val op = started()
        val persisted = userEntry("same text", id = "u1")
        validateRecordLog(
            RecordLogSlice(
                "main",
                listOf(op),
                listOf(
                    op,
                    deferred(
                        "write_deferred",
                        runId = "op1",
                        fields = mapOf("target" to provisionedUser("u1", "same text"))
                    )
                ),
                listOf(persisted)
            )
        )
    }

    @Test
    fun `compaction intent naming a non-compaction result entry is provisioned_entry_mismatch`() {
        val op = started(intent = OperationIntent.compaction("r1"))
        val wrong = userEntry("hi", id = "r1")
        assertEquals(
            RecordLogCorruptionReason.PROVISIONED_ENTRY_MISMATCH,
            reasonOf {
                validateRecordLog(RecordLogSlice("main", listOf(op), listOf(op), listOf(wrong)))
            }
        )
    }

    @Test
    fun `navigation intent naming a branch-summary result entry validates`() {
        val summary =
            BranchSummaryEntry(
                id = "s1",
                seq = nextSeq(),
                parentId = null,
                timestamp = 1L,
                fromId = "old",
                summary = "s"
            )
        val intent = OperationIntent(
            kind = OperationIntent.Kind.NAVIGATION,
            payload = buildJsonObject {
                put("kind", "navigation")
                put("targetId", "t")
                put("summarize", true)
                put("summaryEntryId", "s1")
            }
        )
        val op = started(intent = intent)
        validateRecordLog(RecordLogSlice("main", listOf(op), listOf(op), listOf(summary)))
    }

    @Test
    fun `tool result entry mismatch is provisioned_entry_mismatch`() {
        val op = started()
        val assistant = messageEntry(
            AssistantMessage(
                content = listOf(ToolCall(id = "tc1", name = "read", arguments = "{}")),
                api = "openai-completions",
                provider = "zai",
                model = "glm-4.6"
            )
        )
        val wrongResult =
            messageEntry(
                ToolResultMessage("tc1", "read", listOf(TextContent("x")), timestamp = 2L),
                id = "r"
            )
        assertEquals(
            RecordLogCorruptionReason.PROVISIONED_ENTRY_MISMATCH,
            reasonOf {
                validateRecordLog(
                    RecordLogSlice(
                        "main",
                        listOf(op),
                        listOf(
                            op,
                            deferred(
                                "tool_started",
                                runId = "op1",
                                fields = mapOf(
                                    "assistantEntryId" to
                                        kotlinx.serialization.json.JsonPrimitive(assistant.id),
                                    "toolIndex" to kotlinx.serialization.json.JsonPrimitive(0),
                                    "toolCallId" to kotlinx.serialization.json.JsonPrimitive("tc1"),
                                    "toolName" to kotlinx.serialization.json.JsonPrimitive("read"),
                                    "resultEntryId" to
                                        kotlinx.serialization.json.JsonPrimitive("wrong")
                                )
                            )
                        ),
                        listOf(assistant, userEntry("not a result", id = "wrong"))
                    )
                )
            }
        )
        validateRecordLog(
            RecordLogSlice(
                "main",
                listOf(op),
                listOf(
                    op,
                    deferred(
                        "tool_started",
                        runId = "op1",
                        fields = mapOf(
                            "assistantEntryId" to
                                kotlinx.serialization.json.JsonPrimitive(assistant.id),
                            "toolIndex" to kotlinx.serialization.json.JsonPrimitive(0),
                            "toolCallId" to kotlinx.serialization.json.JsonPrimitive("tc1"),
                            "toolName" to kotlinx.serialization.json.JsonPrimitive("read"),
                            "resultEntryId" to
                                kotlinx.serialization.json.JsonPrimitive(wrongResult.id)
                        )
                    )
                ),
                listOf(assistant, wrongResult)
            )
        )
    }

    @Test
    fun `overflow recovery flag reduces from an overflow compaction attempt after consumed inputs`() {
        val op = started()
        val inputEntry = userEntry("hi", id = "u1")
        val result = reduceLaneState(
            input(
                openOperations = listOf(op),
                records = listOf(
                    op,
                    deferred(
                        "step_attempt",
                        runId = "op1",
                        fields = mapOf(
                            "step" to kotlinx.serialization.json.JsonPrimitive("compaction"),
                            "attempt" to kotlinx.serialization.json.JsonPrimitive(1),
                            "resultEntryId" to kotlinx.serialization.json.JsonPrimitive("c"),
                            "compactionReason" to
                                kotlinx.serialization.json.JsonPrimitive("overflow")
                        ),
                        seq = inputEntry.seq + 10
                    )
                ),
                entries = listOf(inputEntry),
                ownEntries = listOf(inputEntry)
            )
        )
        assertTrue(result.laneState.operation!!.overflowRecoveryUsed)
    }

    @Test
    fun `run intent initialMessages reduce to missing entries`() {
        val intent = OperationIntent(
            kind = OperationIntent.Kind.RUN,
            payload = buildJsonObject {
                put("kind", "run")
                put(
                    "initialMessages",
                    kotlinx.serialization.json.JsonArray(listOf(provisionedUser("u1", "hi")))
                )
            }
        )
        val op = started(intent = intent)
        val result = reduceLaneState(input(openOperations = listOf(op), records = listOf(op)))
        assertEquals(
            listOf(provisionedUser("u1", "hi")),
            result.laneState.operation!!.missingInitialMessages
        )
    }
}
