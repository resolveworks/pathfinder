package works.resolve.pathfinder.codingagent.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock

/**
 * pi has no test file for session-export.ts; these cases pin its contract
 * for the port: the export is a fresh single-branch session (new header,
 * active branch only, parentIds re-chained), trailing entries append
 * verbatim under the header's timestamp, and exportSessionToJsonl writes
 * under its default name into the output directory.
 */
class SessionExportTest {

    private val clock = FakeClock()
    private var sessionCounter = 0
    private var entryCounter = 0

    private fun user(text: String) = UserMessage.ofText(text, clock.now().toEpochMilliseconds())

    private suspend fun manager(dir: File): SessionManager = SessionManager.create(
        dir = dir,
        clock = clock,
        idFactory = { "sess-${sessionCounter++}" },
        ioDispatcher = Dispatchers.Unconfined,
        entryIdFactory = { "e${entryCounter++}" }
    )

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("session-export-test").toFile()

    private fun sessionFile(dir: File): File =
        dir.listFiles { f: File -> f.name.endsWith(".jsonl") }!!.single()

    private fun objects(export: String): List<JsonObject> =
        export.trim().split('\n').map { Json.parseToJsonElement(it).jsonObject }

    private fun lastLine(export: String): String = export.trimEnd('\n').split('\n').last()

    @Test
    fun `serializes the active branch under a fresh header`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)
        val first = m.appendMessage(user("first"))
        m.appendMessage(user("second"))
        m.branch(first)
        val fork = m.appendMessage(user("fork"))

        val lines = objects(serializeSessionBranch(m))
        assertEquals(3, lines.size)
        val header = lines[0]
        assertEquals("session", header["type"]!!.jsonPrimitive.content)
        assertEquals(JsonlCodec.SESSION_VERSION, header["version"]!!.jsonPrimitive.int)
        assertEquals("sess-0", header["id"]!!.jsonPrimitive.content)
        assertTrue(JsonlCodec.parseIso(header["timestamp"]!!.jsonPrimitive.content) != null)
        // The abandoned sibling never appears; the chain restarts at null.
        assertEquals(listOf(first, fork), lines.drop(1).map { it["id"]!!.jsonPrimitive.content })
        assertEquals(JsonNull, lines[1]["parentId"])
        assertEquals(first, lines[2]["parentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `writes the export under the default name in the output dir`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)
        val entry = m.appendMessage(user("only"))

        val nested = File(dir, "nested/deeper")
        val file = exportSessionToJsonl(m, nested)
        assertTrue(file.exists())
        assertEquals(nested, file.parentFile)
        assertTrue(
            file.name.matches(
                Regex("session-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-\\d{3}Z\\.jsonl")
            )
        )

        val named = exportSessionToJsonl(m, dir, fileName = "pick.jsonl")
        assertEquals("pick.jsonl", named.name)
        // The export is a loadable session containing exactly the branch.
        val reloaded = SessionManager.open(named, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(listOf(entry), reloaded.getBranch().map { it.id })
    }

    @Test
    fun `appends trailing entries verbatim after the branch`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)
        val entry = m.appendMessage(user("hi"))
        var seenParentId: String? = "sentinel"
        var seenTimestamp = 0L

        val export = serializeSessionBranch(m) { parentId, timestampMillis ->
            seenParentId = parentId
            seenTimestamp = timestampMillis
            listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", "custom")
                    put("customType", "pi.share")
                    put("id", "t1")
                    put("parentId", parentId)
                    put("timestamp", JsonlCodec.formatIso(timestampMillis))
                }
            )
        }

        val lines = objects(export)
        assertEquals(3, lines.size)
        assertEquals(entry, seenParentId)
        // The trailing entry shares the header's stamp and follows the branch.
        assertEquals(
            JsonlCodec.formatIso(seenTimestamp),
            lines[0]["timestamp"]!!.jsonPrimitive.content
        )
        assertEquals(
            JsonlCodec.formatIso(seenTimestamp),
            lines[2]["timestamp"]!!.jsonPrimitive.content
        )
        assertEquals(entry, lines[2]["parentId"]!!.jsonPrimitive.content)
        // Verbatim: the emitted line is the returned object, byte for byte.
        val emitted = lines[2].toString()
        assertEquals(emitted, lastLine(export))
    }

    @Test
    fun `re-chains a dangling root parent to null`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)
        m.appendMessage(user("orphan"))
        val file = sessionFile(dir)
        // Corrupt the committed entry's parent link to point at a missing
        // entry; the export's re-chaining normalizes it like pi's spread.
        file.writeText(file.readText().replace("\"parentId\":null", "\"parentId\":\"ghost\""))

        val reopened = SessionManager.open(file, ioDispatcher = Dispatchers.Unconfined)
        val lines = objects(serializeSessionBranch(reopened))
        assertEquals(2, lines.size)
        assertEquals(JsonNull, lines[1]["parentId"])
    }

    @Test
    fun `raw entries re-serialize verbatim on the chain`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)
        val entry = m.appendMessage(user("base"))
        val file = sessionFile(dir)
        val raw =
            """{"id":"r1","parentId":"$entry","timestamp":"2026-01-01T00:00:00.000Z",""" +
                """"type":"future_entry","payload":{"a":1}}"""
        file.appendText(raw + "\n")

        val reopened = SessionManager.open(file, ioDispatcher = Dispatchers.Unconfined)
        val export = serializeSessionBranch(reopened)
        val lines = objects(export)
        assertEquals(3, lines.size)
        assertEquals(listOf(entry, "r1"), lines.drop(1).map { it["id"]!!.jsonPrimitive.content })
        assertEquals(raw, lastLine(export))
    }
}
