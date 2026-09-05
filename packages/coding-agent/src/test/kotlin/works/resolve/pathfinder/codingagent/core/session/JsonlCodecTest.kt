package works.resolve.pathfinder.codingagent.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage

class JsonlCodecTest {

    private fun assistant(text: String, ts: Long) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "openai-completions",
        provider = "zai",
        model = "glm-4.6",
        usage = Usage(
            input = 1,
            output = 1,
            reasoning = 0,
            totalTokens = 2,
            cost = Cost(0.0, 0.0, 0.0, 0.0, 0.0)
        ),
        stopReason = StopReason.STOP,
        timestamp = ts
    )

    private fun roundtripEntry(entry: SessionEntry): SessionEntry {
        val line = JsonlCodec.encodeEntryLine(entry).trimEnd()
        val parsed = JsonlCodec.parseLine(line)
        return assertIs<JsonlCodec.Line.Entry>(parsed).entry
    }

    @Test
    fun `header line shape and roundtrip`() {
        val line = JsonlCodec.encodeHeaderLine(JsonlCodec.SessionHeader("sess-1", 0))
        assertTrue(line.endsWith("}\n") && !line.dropLast(1).contains("\n"))
        val parsed = assertIs<JsonlCodec.Line.Header>(JsonlCodec.parseLine(line.trimEnd()))
        assertEquals("sess-1", parsed.header.id)
        assertEquals(0L, parsed.header.timestamp)
    }

    @Test
    fun `timestamps use three-digit iso millis`() {
        val line = JsonlCodec.encodeEntryLine(
            ModelChangeEntry("e1", null, 1759098220386L, "zai", "glm-4.7")
        )
        val parsed = Json.parseToJsonElement(line).jsonObject["timestamp"]!!.jsonPrimitive.content
        // Exactly 3 millisecond digits and a Z suffix.
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(parsed))
        assertEquals(1759098220386L, JsonlCodec.parseIso(parsed))
    }

    @Test
    fun `session filename replaces separators`() {
        assertEquals(
            "2026-09-05T19-03-40-386Z_abc.jsonl",
            JsonlCodec.sessionFileName(JsonlCodec.parseIso("2026-09-05T19:03:40.386Z")!!, "abc")
        )
    }

    @Test
    fun `message entries roundtrip with full transcript shapes`() {
        val user = MessageEntry("m0", null, 7L, UserMessage.ofText("hello", 1L))
        assertEquals(user, roundtripEntry(user))

        val assistant = MessageEntry(
            "m1",
            "m0",
            8L,
            assistant("hi", 2L).copy(
                rawStopReason = "stop",
                responseId = "r",
                responseModel = "glm-4.6-actual",
                endTurn = true
            )
        )
        assertEquals(assistant, roundtripEntry(assistant))

        val deferred = MessageEntry(
            "d0",
            "m1",
            9L,
            assistant("later", 1L).copy(stopReason = StopReason.DEFERRED)
        )
        // Deferred stop reason survives the wire: deferred assistant
        // messages are dropped from context, so the value must persist.
        assertEquals(deferred, roundtripEntry(deferred))

        val tool = MessageEntry(
            "m2",
            "m1",
            9L,
            ToolResultMessage(
                toolCallId = "c1",
                toolName = "read",
                content = listOf(TextContent("ok")),
                isError = false,
                addedToolNames = listOf("read"),
                timestamp = 3L
            )
        )
        assertEquals(tool, roundtripEntry(tool))
    }

    @Test
    fun `configuration compaction and branch summary entries roundtrip`() {
        val entries = listOf(
            ModelChangeEntry("e1", null, 1L, provider = "zai", modelId = "glm-4.7"),
            ThinkingLevelEntry("e2", "e1", 2L, thinkingLevel = "high"),
            BranchSummaryEntry("e3", "e2", 3L, fromId = "e1", summary = "s"),
            CompactionEntry(
                id = "e4",
                parentId = "e3",
                timestamp = 4L,
                summary = "sum",
                firstKeptEntryId = "e2",
                tokensBefore = 100
            )
        )
        entries.forEach { assertEquals(it, roundtripEntry(it)) }

        // Compaction details and usage roundtrip.
        val compaction = CompactionEntry(
            id = "e5",
            parentId = "e4",
            timestamp = 5L,
            summary = "sum2",
            firstKeptEntryId = "e4",
            tokensBefore = 200,
            details = works.resolve.pathfinder.agent.CompactionDetails(
                readFiles = listOf("a.kt"),
                modifiedFiles = listOf("b.kt")
            ),
            usage = Usage(1, 2, 0, 0, 0, 0, 3, Cost(0.0, 0.0, 0.0, 0.0, 0.0))
        )
        assertEquals(compaction, roundtripEntry(compaction))
    }

    @Test
    fun `assistant wire shape matches pi files`() {
        // pi serializes lowercase stop reasons and omits zero reasoning.
        val entry = MessageEntry(
            "m",
            null,
            0L,
            assistant("bye", 0L).copy(stopReason = StopReason.ABORTED)
        )
        val line = JsonlCodec.encodeEntryLine(entry).trimEnd()
        assertTrue("\"stopReason\":\"aborted\"" in line)
        assertTrue("reasoning" !in line)

        val piLine =
            """{"type":"message","id":"m","parentId":null,""" +
                """"timestamp":"2026-09-05T19:03:40.386Z",""" +
                """"message":{"role":"assistant","timestamp":1,"content":[],""" +
                """"api":"openai-completions","provider":"zai","model":"glm-4.6",""" +
                """"usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,""" +
                """"totalTokens":2,"cost":{"input":0,"output":0,"cacheRead":0,""" +
                """"cacheWrite":0,"total":0}},"stopReason":"aborted"}}"""
        val decoded = assertIs<JsonlCodec.Line.Entry>(JsonlCodec.parseLine(piLine)).entry
        val message = assertIs<AssistantMessage>((decoded as MessageEntry).message)
        assertEquals(StopReason.ABORTED, message.stopReason)
        assertEquals(0, message.usage.reasoning)
        // Old Pathfinder files with uppercase enum names still decode.
        val upper = piLine.replace("\"stopReason\":\"aborted\"", "\"stopReason\":\"ABORTED\"")
        val upperDecoded = assertIs<JsonlCodec.Line.Entry>(JsonlCodec.parseLine(upper)).entry
        assertEquals(
            StopReason.ABORTED,
            (assertIs<AssistantMessage>((upperDecoded as MessageEntry).message)).stopReason
        )
    }

    @Test
    fun `blank malformed and unknown lines are skipped`() {
        assertNull(JsonlCodec.parseLine(""))
        assertNull(JsonlCodec.parseLine("   "))
        assertNull(JsonlCodec.parseLine("{ torn"))
        assertNull(JsonlCodec.parseLine("""{"type":"nope","id":"x"}"""))
        assertNull(JsonlCodec.parseLine("""not json at all"""))
        // Entries before a header are dropped by callers; the codec itself
        // still parses them.
        assertIs<JsonlCodec.Line.Entry>(
            JsonlCodec.parseLine(
                """{"type":"message","id":"m","parentId":null,""" +
                    """"timestamp":"2026-09-05T19:03:40.386Z",""" +
                    """"message":{"role":"user","timestamp":1,"content":[]}}"""
            )
        )
    }

    @Test
    fun `old v4 mutation lines are skipped`() {
        // The previous Pathfinder format's header and mutation lines are
        // neither migrated nor parsed.
        assertNull(
            JsonlCodec.parseLine("""{"kind":"header","version":4,"id":"a","createdAt":0}""")
        )
        assertNull(
            JsonlCodec.parseLine(
                """{"kind":"entry","seq":1,"id":"a","type":"message","parentId":null,"timestamp":1,"message":{"role":"user","timestamp":1,"content":[]}}"""
            )
        )
    }

    @Test
    fun `header requires string id and iso timestamp`() {
        assertNull(
            JsonlCodec.parseLine(
                """{"type":"session","version":3,"timestamp":"2026-09-05T19:03:40.386Z","cwd":""}"""
            )
        )
        assertNull(JsonlCodec.parseLine("""{"type":"session","version":3,"id":"a","cwd":""}"""))
    }
}
