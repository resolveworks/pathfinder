package works.resolve.pathfinder.data.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
