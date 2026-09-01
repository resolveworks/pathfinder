package works.resolve.pathfinder.data.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SessionCodec v3: Koog-message round-trip plus fail-fast rejection of any
 * older/unknown format (no conversion, per repo policy).
 */
class SessionCodecTest {

    private fun branchySession(): Session {
        val root = MessageEntry("m0", null, 1L, userMessage("a", 1L))
        val left = MessageEntry(
            "m1",
            "m0",
            2L,
            assistantMessage(
                reasoningPart("hmm", "more"),
                textPart("hi there"),
                toolCallPart("call-1", "get_weather", """{"city":"Oslo"}"""),
                epochMs = 2L,
            ),
        )
        val right = MessageEntry("m2", "m0", 3L, userMessage("c", 3L))
        return Session("sess-1", "t", 1, 2, entries = listOf(root, left, right), leafId = "m2")
    }

    @Test
    fun roundTripsTreeAndKoogMessages() {
        val session = branchySession()

        val decoded = SessionCodec.decode(SessionCodec.encode(session))

        assertEquals(session, decoded)
        assertEquals(listOf("a", "c"), decoded.messages.map { (it as ai.koog.prompt.message.Message.User).textContent() })
        // Reasoning and tool-call parts survive verbatim.
        val assistant = (decoded.entries[1] as MessageEntry).message as ai.koog.prompt.message.Message.Assistant
        assertEquals(3, assistant.parts.size)
        assertTrue(assistant.parts[0] is ai.koog.prompt.message.MessagePart.Reasoning)
        assertTrue(assistant.parts[2] is ai.koog.prompt.message.MessagePart.Tool.Call)
    }

    @Test
    fun rejectsOldOrUnknownFormatsAndMalformedData() {
        // Old (pre-Koog) format.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode(
                """{"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1,"entries":[],"leafId":null}""",
            )
        }
        // Unknown future format.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode(
                """{"format":99,"id":"s","title":"t","createdAt":1,"updatedAt":1,"entries":[],"leafId":null}""",
            )
        }
        // Missing format.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"id":"s","title":"t","createdAt":1,"updatedAt":1,"entries":[]}""")
        }
        // Malformed message payload.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode(
                """{"format":3,"id":"s","title":"t","createdAt":1,"updatedAt":1,""" +
                    """"entries":[{"kind":"message","id":"m0","timestamp":0,"message":{"type":"nonsense"}}],"leafId":"m0"}""",
            )
        }
        // Not JSON at all.
        assertFailsWith<SessionDataException> { SessionCodec.decode("{ not json") }
    }

    @Test
    fun rejectsBrokenGraphsAtDecodeTime() {
        // Build a valid two-entry session, then corrupt its graph in ways the
        // codec must reject at decode time (never silently tolerate).
        val root = MessageEntry("m0", null, 1L, userMessage("a", 1L))
        val child = MessageEntry("m1", "m0", 2L, userMessage("b", 2L))
        fun json(entries: List<SessionEntry>, leafId: String?): String =
            SessionCodec.encode(Session("s", "t", 1, 1, entries, leafId))

        fun messageFailsWith(expected: String, entries: List<SessionEntry>, leafId: String?) {
            val e = assertFailsWith<SessionDataException> { SessionCodec.decode(json(entries, leafId)) }
            assertEquals(expected, e.message)
        }

        // Duplicate entry ids.
        messageFailsWith(
            "Malformed session data: duplicate entry id: m0",
            listOf(root, root.copy(timestamp = 2L)), "m0",
        )
        // ParentId referencing a nonexistent entry.
        messageFailsWith(
            "Malformed session data: entry m0 references unknown parentId: ghost",
            listOf(root.copy(parentId = "ghost")), "m0",
        )
        // Self-parent.
        messageFailsWith(
            "Malformed session data: entry m0 parents itself",
            listOf(root.copy(parentId = "m0")), "m0",
        )
        // Two-entry parent cycle.
        messageFailsWith(
            "Malformed session data: cycle in parent chain at entry m0",
            listOf(root.copy(parentId = "m1"), child), "m1",
        )
        // Dangling leafId — must not silently decode to an empty transcript.
        messageFailsWith(
            "Malformed session data: leafId not in entries: m9",
            listOf(root, child), "m9",
        )
    }

    @Test
    fun nullLeafIdWithEntriesStaysLegal() {
        // A null leafId with entries present is reachable for a brand-new
        // session before its first append; decode must keep accepting it.
        val root = MessageEntry("m0", null, 1L, userMessage("a", 1L))
        val decoded = SessionCodec.decode(SessionCodec.encode(Session("s", "t", 1, 1, listOf(root), null)))

        assertEquals("m0", decoded.entries.single().id)
        assertNull(decoded.leafId)
        assertTrue(decoded.messages.isEmpty())
    }
}
