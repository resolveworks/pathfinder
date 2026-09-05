package works.resolve.pathfinder.codingagent.core.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.testing.FakeClock

/**
 * Port of pi's custom-session-id.test.ts for the companion create/open
 * API. Cases targeting the inMemory constructor, createBranchedSession,
 * and forkFrom are skipped: those entry points are not ported.
 */
class CustomSessionIdTest {

    private val clock = FakeClock()
    private var entryCounter = 0

    private val uuidV7Regex =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun assistant() = AssistantMessage(
        content = listOf(TextContent("hi")),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "claude-test",
        usage = Usage(1, 1, totalTokens = 2, cost = Cost()),
        stopReason = StopReason.STOP,
        timestamp = clock.now().toEpochMilliseconds()
    )

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("custom-session-id-test").toFile()

    @Test
    fun `uses the provided id instead of generating one`() = runTest {
        val m = SessionManager.create(
            createTempDirectory(),
            clock,
            idFactory = { "my-custom-id" },
            ioDispatcher = Dispatchers.Unconfined
        )
        assertEquals("my-custom-id", m.sessionId)
    }

    @Test
    fun `allows alphanumeric session ids with interior punctuation`() = runTest {
        val m = SessionManager.create(
            createTempDirectory(),
            clock,
            idFactory = { "abc-123_def.456" },
            ioDispatcher = Dispatchers.Unconfined
        )
        assertEquals("abc-123_def.456", m.sessionId)
    }

    @Test
    fun `rejects invalid custom session ids`() = runTest {
        val invalidIds = listOf(
            "", "-abc", "abc-", "_abc", "abc_", ".abc", "abc.",
            "abc/def", "abc\\def", "abc def"
        )
        val dir = createTempDirectory()

        for (id in invalidIds) {
            assertFailsWith<SessionError>("id: $id") {
                SessionManager.create(dir, clock, idFactory = {
                    id
                }, ioDispatcher = Dispatchers.Unconfined)
            }.let { assertEquals(SessionErrorCode.INVALID_ID, it.code) }
        }
    }

    @Test
    fun `generates a UUIDv7 id when no id is provided`() = runTest {
        val m = SessionManager.create(
            createTempDirectory(),
            clock,
            ioDispatcher = Dispatchers.Unconfined
        )
        assertTrue(uuidV7Regex.matches(m.sessionId))
    }

    @Test
    fun `includes the custom id in the session header`() = runTest {
        val dir = createTempDirectory()
        val m = SessionManager.create(
            dir,
            clock,
            idFactory = { "header-test-id" },
            ioDispatcher = Dispatchers.Unconfined,
            entryIdFactory = { "e${entryCounter++}" }
        )

        // The header becomes observable when the first assistant flushes it.
        m.appendMessage(works.resolve.pathfinder.ai.UserMessage.ofText("hello", 1L))
        clock.advanceMillis(10)
        m.appendMessage(assistant())

        val file = dir.listFiles { f: File -> f.name.endsWith(".jsonl") }!!.single()
        val headerLine = file.readText().trimEnd().split("\n").first()
        val header = assertIs<JsonlCodec.Line.Header>(JsonlCodec.parseLine(headerLine)).header
        assertEquals("header-test-id", header.id)
    }

    @Test
    fun `uses the provided id when creating a persisted session`() = runTest {
        val dir = createTempDirectory()
        val m = SessionManager.create(
            dir,
            clock,
            idFactory = { "created-session-id" },
            ioDispatcher = Dispatchers.Unconfined
        )

        assertEquals("created-session-id", m.sessionId)
        val file = m.sessionFile!!
        assertTrue("created-session-id" in file.name)
        assertTrue(
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-\\d{3}Z_created-session-id\\.jsonl$")
                .matches(file.name)
        )
        assertFalse(file.exists())
    }
}
