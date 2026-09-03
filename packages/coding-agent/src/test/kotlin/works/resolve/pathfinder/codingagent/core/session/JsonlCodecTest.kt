package works.resolve.pathfinder.codingagent.core.session

import works.resolve.pathfinder.agent.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.lenientJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The syntax/schema error distinction exists for torn-tail repair. */
class JsonlCodecTest {

    private fun assistant(text: String, ts: Long) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "openai-completions",
        provider = "zai",
        model = "glm-4.6",
        usage = Usage(input = 1, output = 1, reasoning = 0, totalTokens = 2, cost = Cost(0.0, 0.0, 0.0, 0.0, 0.0)),
        stopReason = StopReason.STOP,
        timestamp = ts,
    )

    @Test
    fun `header roundtrips with lineage and metadata`() {
        val header = JsonlCodec.JsonlV4Header(
            id = "sess-1",
            createdAt = 42L,
            parentSessionId = "sess-0",
            metadata = JsonObject(mapOf("title" to kotlinx.serialization.json.JsonPrimitive("t"))),
        )
        val decoded = JsonlCodec.decodeHeader(JsonlCodec.encodeHeader(header).trimEnd())
        assertEquals(header, decoded)
    }

    @Test
    fun `header rejects wrong kind version and malformed fields`() {
        assertDecodeHeaderSchemaError("""{"kind":"nope","version":4,"id":"a","createdAt":0}""")
        assertDecodeHeaderSchemaError("""{"kind":"header","version":3,"id":"a","createdAt":0}""")
        assertDecodeHeaderSchemaError("""{"kind":"header","version":4,"createdAt":0}""")
        assertDecodeHeaderSchemaError("""{"kind":"header","version":4,"id":"a","createdAt":-1}""")
        assertDecodeHeaderSchemaError("""{"kind":"header","version":4,"id":"a","createdAt":"0"}""")
        assertDecodeHeaderSchemaError(
            """{"kind":"header","version":4,"id":"a","createdAt":0,"parentSessionId":"p","legacyParentSessionPath":"/x"}""",
        )
        assertDecodeHeaderSchemaError("""{"kind":"header","version":4,"id":"a","createdAt":0,"metadata":[1]}""")
    }

    @Test
    fun `header explicitly rejects legacy v3 session files`() {
        // A genuine v3 header line (pi's legacy format: type "session",
        // version 3, no kind field) is rejected at the kind check — never
        // migrated (AGENTS.md: reject old formats).
        assertDecodeHeaderSchemaError(
            """{"type":"session","version":3,"id":"a","timestamp":"2024-01-01T00:00:00Z","cwd":"/x"}""",
        )
        // A v4-shaped header claiming version 3 is rejected by the version
        // check with pi's pin-era message.
        val error = assertFailsWith<JsonlCodec.JsonlDecodeError> {
            JsonlCodec.decodeHeader("""{"kind":"header","version":3,"id":"a","createdAt":0}""")
        }
        assertEquals(JsonlCodec.JsonlDecodeError.Kind.SCHEMA, error.kind)
        assertEquals("has unsupported session version", error.message)
    }

    @Test
    fun `header tolerates pi cwd and missing parentage`() {
        // A pi-written session carries a required cwd; Pathfinder ignores it.
        val decoded = JsonlCodec.decodeHeader(
            """{"kind":"header","version":4,"id":"a","createdAt":1,"cwd":"/home/x"}""",
        )
        assertEquals("a", decoded.id)
        assertNull(decoded.parentSessionId)
        assertNull(decoded.legacyParentSessionPath)
    }

    @Test
    fun `syntax errors are distinguishable from schema errors`() {
        val syntax = assertFailsWith<JsonlCodec.JsonlDecodeError> { JsonlCodec.decodeHeader("{ torn") }
        assertEquals(JsonlCodec.JsonlDecodeError.Kind.SYNTAX, syntax.kind)
        val schema = assertFailsWith<JsonlCodec.JsonlDecodeError> { JsonlCodec.decodeMutation("""{"kind":"x"}""") }
        assertEquals(JsonlCodec.JsonlDecodeError.Kind.SCHEMA, schema.kind)
    }

    private fun roundtripEntry(entry: SessionEntry): SessionEntry {
        val lane = SessionMutation.Entry(lane = "main", entry = entry)
        val line = JsonlCodec.encodeMutation(lane).trimEnd()
        val decoded = JsonlCodec.decodeMutation(line)
        val decodedEntry = assertIs<SessionMutation.Entry>(decoded).entry
        assertEquals("main", assertIs<SessionMutation.Entry>(decoded).lane)
        return decodedEntry
    }

    @Test
    fun `message entry roundtrip with full transcript shapes`() {
        val message = MessageEntry(
            id = "m0",
            seq = 1,
            parentId = null,
            timestamp = 7,
            message = UserMessage.ofText("hello", 1L),
        )
        assertEquals(message, roundtripEntry(message))

        val assistant = MessageEntry(
            id = "m1",
            seq = 2,
            parentId = "m0",
            timestamp = 8,
            message = assistant("hi", 2L).copy(
                rawStopReason = "stop",
                responseId = "r",
                responseModel = "glm-4.6-actual",
                endTurn = true,
            ),
        )
        assertEquals(assistant, roundtripEntry(assistant))

        val tool = MessageEntry(
            id = "m2",
            seq = 3,
            parentId = "m1",
            timestamp = 9,
            message = ToolResultMessage(
                toolCallId = "c1",
                toolName = "read",
                content = listOf(TextContent("ok")),
                isError = false,
                addedToolNames = listOf("read"),
                timestamp = 3L,
            ),
        )
        assertEquals(tool, roundtripEntry(tool))
    }

    @Test
    fun `message entry terminate flag and deferred stop reason roundtrip`() {
        // pi's MessageEntry.terminate is `true`-only.
        val terminated = MessageEntry(
            id = "t0",
            seq = 1,
            parentId = null,
            timestamp = 7,
            message = UserMessage.ofText("bye", 1L),
            terminate = true,
        )
        val line = JsonlCodec.encodeMutation(SessionMutation.Entry(lane = "main", entry = terminated)).trimEnd()
        assertTrue("\"terminate\":true" in line)
        assertEquals(terminated, roundtripEntry(terminated))

        // Absent terminate stays absent (null encodes to no field).
        val plain = MessageEntry(
            id = "t1",
            seq = 2,
            parentId = "t0",
            timestamp = 8,
            message = UserMessage.ofText("hi", 1L),
        )
        val plainLine = JsonlCodec.encodeMutation(SessionMutation.Entry(lane = "main", entry = plain)).trimEnd()
        assertTrue("terminate" !in plainLine)
        assertEquals(plain.copy(terminate = null), roundtripEntry(plain))

        // Deferred stop reason survives the wire: deferred assistant messages
        // are dropped from context, so the value must stay persistable.
        val deferred = MessageEntry(
            id = "d0",
            seq = 3,
            parentId = "t1",
            timestamp = 9,
            message = assistant("later", 1L).copy(stopReason = StopReason.DEFERRED),
        )
        assertEquals(deferred, roundtripEntry(deferred))
    }

    @Test
    fun `configuration and compaction entry kinds roundtrip`() {
        val entries = listOf(
            ModelChangeEntry("e1", 1, null, 1, provider = "zai", modelId = "glm-4.7"),
            ThinkingLevelEntry("e2", 2, "e1", 2, thinkingLevel = "high"),
            ActiveToolsEntry("e3", 3, "e2", 3, activeToolNames = listOf("read", "edit")),
            BranchSummaryEntry("e4", 4, "e3", 4, fromId = "e1", summary = "s"),
            CustomEntry("e5", 5, "e4", 5, customType = "ext.thing", data = lenientJson.parseToJsonElement("[true]")),
            CompactionEntry(
                id = "e6", seq = 6, parentId = "e5", timestamp = 6,
                summary = "sum", retainedTail = listOf(UserMessage.ofText("tail", 1L)), tokensBefore = 100,
            ),
        )
        entries.forEach { assertEquals(it, roundtripEntry(it)) }
    }

    @Test
    fun `entry mutations decode without lane`() {
        val line = """{"kind":"entry","seq":1,"id":"a","type":"message","parentId":null,"timestamp":1,"message":{"role":"user","timestamp":1,"content":[]}}"""
        val decoded = assertIs<SessionMutation.Entry>(JsonlCodec.decodeMutation(line))
        assertNull(decoded.lane)
        assertEquals(1L, decoded.entry.seq)
    }

    @Test
    fun `entry mutation rejects unknown entry type and bad seq`() {
        assertMutationSchemaError("""{"kind":"entry","seq":1,"id":"a","type":"nope","parentId":null,"timestamp":1}""")
        assertMutationSchemaError("""{"kind":"entry","seq":0,"id":"a","type":"message","parentId":null,"timestamp":1}""")
        assertMutationSchemaError("""{"kind":"entry","seq":"1","id":"a","type":"message","parentId":null,"timestamp":1}""")
        assertMutationSchemaError("""{"kind":"entry","seq":-2,"id":"a","type":"message","parentId":null,"timestamp":1}""")
        // Unknown mutation kind.
        assertMutationSchemaError("""{"kind":"zzz","seq":1}""")
    }

    @Test
    fun `record mutation decodes every record type`() {
        for (type in listOf("step_attempt", "tool_started", "queue_enqueued", "queue_cancelled", "write_deferred")) {
            val line = """{"kind":"record","seq":1,"id":"r1","lane":"main","type":"$type","timestamp":1,"step":"assistant"}"""
            val record = assertIs<SessionMutation.Record>(JsonlCodec.decodeMutation(line)).record
            val deferred = assertIs<LaneRecord.DeferredRecord>(record)
            assertEquals(type, deferred.type)
            assertEquals("main", deferred.lane)
            assertEquals(deferred, JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(SessionMutation.Record(deferred)).trimEnd()).let { (it as SessionMutation.Record).record })
        }
        val abort = assertIs<SessionMutation.Record>(
            JsonlCodec.decodeMutation("""{"kind":"record","seq":1,"id":"r1","lane":"main","type":"abort_requested","timestamp":1,"runId":"op1"}"""),
        ).record
        assertEquals("op1", assertIs<LaneRecord.AbortRequestedRecord>(abort).runId)
    }

    @Test
    fun `record mutation validates payload discriminants`() {
        assertMutationSchemaError("""{"kind":"record","seq":1,"id":"r","lane":"main","type":"zzz","timestamp":1}""")
        assertMutationSchemaError("""{"kind":"record","seq":1,"id":"r","lane":"main","type":"operation_started","timestamp":1}""")
        assertMutationSchemaError(
            """{"kind":"record","seq":1,"id":"r","lane":"main","type":"operation_started","timestamp":1,"intent":{"kind":"zzz"}}""",
        )
        assertMutationSchemaError(
            """{"kind":"record","seq":1,"id":"r","lane":"main","type":"operation_finished","timestamp":1,"outcome":"completed"}""",
        )
        assertMutationSchemaError(
            """{"kind":"record","seq":1,"id":"r","lane":"main","type":"operation_finished","timestamp":1,"runId":"op1","outcome":"zzz"}""",
        )
        assertMutationSchemaError("""{"kind":"record","seq":1,"id":"r","lane":"main","type":"usage","timestamp":1}""")
    }

    @Test
    fun `operation records roundtrip typed payloads`() {
        // Absent sourceLeafId decodes as null and re-encodes explicitly.
        val started = assertIs<LaneRecord.OperationStartedRecord>(
            assertIs<SessionMutation.Record>(
                JsonlCodec.decodeMutation(
                    """{"kind":"record","seq":1,"id":"op1","lane":"main","type":"operation_started","timestamp":1,"intent":{"kind":"run","originalPrompt":[]}}""",
                ),
            ).record,
        )
        assertNull(started.sourceLeafId)
        assertEquals(OperationIntent.Kind.RUN, started.intent.kind)
        assertEquals("run", started.intent.payload["kind"]!!.jsonPrimitive.content)
        assertEquals(started, JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(SessionMutation.Record(started)).trimEnd()).let { (it as SessionMutation.Record).record })

        val finished = LaneRecord.OperationFinishedRecord(
            id = "f1", lane = "main", seq = 2, timestamp = 2,
            runId = "op1", outcome = OperationOutcome.FAILED, error = RecordError("provider", "boom"),
        )
        assertEquals(
            finished,
            assertIs<SessionMutation.Record>(
                JsonlCodec.decodeMutation(
                    """{"kind":"record","seq":2,"id":"f1","lane":"main","type":"operation_finished","timestamp":2,"runId":"op1","outcome":"failed","error":{"code":"provider","message":"boom"}}""",
                ),
            ).record,
        )
        assertEquals(finished, JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(SessionMutation.Record(finished)).trimEnd()).let { (it as SessionMutation.Record).record })

        val compaction = LaneRecord.OperationStartedRecord(
            id = "op2", lane = "main", seq = 3, timestamp = 3,
            sourceLeafId = "e1", intent = OperationIntent.compaction("e9"),
        )
        assertEquals(
            compaction,
            assertIs<SessionMutation.Record>(
                JsonlCodec.decodeMutation(
                    """{"kind":"record","seq":3,"id":"op2","lane":"main","type":"operation_started","timestamp":3,"sourceLeafId":"e1","intent":{"kind":"compaction","resultEntryId":"e9"}}""",
                ),
            ).record,
        )
    }

    @Test
    fun `usage record roundtrips its usage payload and opaque cause fields`() {
        val usage = Usage(input = 10, output = 5, cacheRead = 100, cacheWrite = 20, reasoning = 1, totalTokens = 136, cost = Cost(0.1, 0.2, 0.0, 0.0, 0.3))
        val record = LaneRecord.UsageRecord(
            id = "u1", lane = "main", seq = 1, timestamp = 1, usage = usage,
            fields = kotlinx.serialization.json.JsonObject(
                mapOf("cause" to kotlinx.serialization.json.JsonPrimitive("assistant"), "runId" to kotlinx.serialization.json.JsonPrimitive("op1")),
            ),
        )
        val decoded = JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(SessionMutation.Record(record)).trimEnd())
        assertEquals(record, (decoded as SessionMutation.Record).record)
    }

    @Test
    fun `lane mutation roundtrips with null and string leaf`() {
        val lane = SessionMutation.Lane(seq = 5, lane = "main", leafId = null)
        assertEquals(lane, JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(lane).trimEnd()))
        val pointed = SessionMutation.Lane(seq = 6, lane = "main", leafId = "e1")
        assertEquals(pointed, JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(pointed).trimEnd()))
        // Absent leafId is invalid (pi's requireNullableId).
        assertMutationSchemaError("""{"kind":"lane","seq":1,"lane":"main"}""")
    }

    @Test
    fun `fact mutations roundtrip name and label`() {
        val name = SessionMutation.Fact.Name(seq = 1, name = "title")
        assertEquals(name, JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(name).trimEnd()))
        val clear = SessionMutation.Fact.Name(seq = 2, name = null)
        assertEquals(clear, JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(clear).trimEnd()))
        val label = SessionMutation.Fact.Label(seq = 3, targetId = "e1", label = "l")
        assertEquals(label, JsonlCodec.decodeMutation(JsonlCodec.encodeMutation(label).trimEnd()))
        assertMutationSchemaError("""{"kind":"fact","seq":1,"fact":"zzz"}""")
        assertMutationSchemaError("""{"kind":"fact","seq":1,"fact":"name","name":[1]}""")
    }

    @Test
    fun `encoded lines are newline terminated single lines`() {
        val header = JsonlCodec.encodeHeader(JsonlCodec.JsonlV4Header("a", 0))
        assertTrue(header.endsWith("}\n") && !header.dropLast(1).contains("\n"))
        val mutation = JsonlCodec.encodeMutation(SessionMutation.Lane(1, "main", null))
        assertTrue(mutation.endsWith("}\n") && !mutation.dropLast(1).contains("\n"))
    }

    private fun assertDecodeHeaderSchemaError(line: String) {
        val error = assertFailsWith<JsonlCodec.JsonlDecodeError> { JsonlCodec.decodeHeader(line) }
        assertEquals(JsonlCodec.JsonlDecodeError.Kind.SCHEMA, error.kind)
    }

    private fun assertMutationSchemaError(line: String) {
        val error = assertFailsWith<JsonlCodec.JsonlDecodeError> { JsonlCodec.decodeMutation(line) }
        assertEquals(JsonlCodec.JsonlDecodeError.Kind.SCHEMA, error.kind)
    }
}
