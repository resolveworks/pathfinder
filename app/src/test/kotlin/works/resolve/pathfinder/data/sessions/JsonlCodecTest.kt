package works.resolve.pathfinder.data.sessions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Cost
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.utils.lenientJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Roundtrips and rejection cases for the JSONL v4 codec, porting pi's
 * session/jsonl/codec.ts decodeHeader/decodeMutation contract: header shape
 * (version 4, mutually exclusive parentage, free-form metadata), all four
 * mutation kinds, and the syntax/schema error distinction used for
 * torn-tail repair.
 */
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

    // ---- header ----

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
        // Wrong version: format 3 and older are rejected outright.
        assertDecodeHeaderSchemaError("""{"kind":"header","version":3,"id":"a","createdAt":0}""")
        assertDecodeHeaderSchemaError("""{"kind":"header","version":4,"createdAt":0}""")
        assertDecodeHeaderSchemaError("""{"kind":"header","version":4,"id":"a","createdAt":-1}""")
        assertDecodeHeaderSchemaError("""{"kind":"header","version":4,"id":"a","createdAt":"0"}""")
        // Mutually exclusive parentage.
        assertDecodeHeaderSchemaError(
            """{"kind":"header","version":4,"id":"a","createdAt":0,"parentSessionId":"p","legacyParentSessionPath":"/x"}""",
        )
        // Metadata must be an object.
        assertDecodeHeaderSchemaError("""{"kind":"header","version":4,"id":"a","createdAt":0,"metadata":[1]}""")
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
        // A non-object line is also a schema error; invalid seq is schema too.
        assertEquals(JsonlCodec.JsonlDecodeError.Kind.SCHEMA, schema.kind)
    }

    // ---- entry mutations: every entry kind roundtrips ----

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
        // pi's MessageEntry.terminate is `true`-only (harness/session/types.ts:27).
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

        // Deferred stop reason survives the wire (context.ts drops deferred
        // assistant messages from context; the value must stay persistable).
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

    // ---- record, lane, and fact mutations decode (no producers yet) ----

    @Test
    fun `record mutation decodes every record type`() {
        for (type in listOf("abort_requested", "step_attempt", "tool_started", "queue_enqueued", "queue_cancelled", "write_deferred", "usage")) {
            val line = """{"kind":"record","seq":1,"id":"r1","lane":"main","type":"$type","timestamp":1}"""
            val record = assertIs<SessionMutation.Record>(JsonlCodec.decodeMutation(line)).record
            assertEquals(type, record.type)
            assertEquals("main", record.lane)
        }
    }

    @Test
    fun `record mutation validates payload discriminants`() {
        // Unknown record type.
        assertMutationSchemaError("""{"kind":"record","seq":1,"id":"r","lane":"main","type":"zzz","timestamp":1}""")
        // operation_started requires an intent object with a known kind.
        assertMutationSchemaError("""{"kind":"record","seq":1,"id":"r","lane":"main","type":"operation_started","timestamp":1}""")
        assertMutationSchemaError(
            """{"kind":"record","seq":1,"id":"r","lane":"main","type":"operation_started","timestamp":1,"intent":{"kind":"zzz"}}""",
        )
        // operation_finished requires a runId.
        assertMutationSchemaError(
            """{"kind":"record","seq":1,"id":"r","lane":"main","type":"operation_finished","timestamp":1}""",
        )
        // A valid operation_started roundtrips its payload fields.
        val line = """{"kind":"record","seq":1,"id":"r","lane":"main","type":"operation_started","timestamp":1,"intent":{"kind":"run"}}"""
        val record = assertIs<SessionMutation.Record>(JsonlCodec.decodeMutation(line)).record
        assertEquals("run", record.fields["intent"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        val reencoded = JsonlCodec.encodeMutation(SessionMutation.Record(record)).trimEnd()
        assertEquals(JsonlCodec.decodeMutation(line), JsonlCodec.decodeMutation(reencoded))
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
