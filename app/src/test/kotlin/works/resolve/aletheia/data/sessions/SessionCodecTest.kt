package works.resolve.aletheia.data.sessions

import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.Cost
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ThinkingContent
import works.resolve.aletheia.ai.core.ToolCall
import works.resolve.aletheia.ai.core.ToolResultMessage
import works.resolve.aletheia.ai.core.Usage
import works.resolve.aletheia.ai.core.UserMessage
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCodecTest {

    private fun assistant(text: String, ts: Long) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "openai-completions",
        provider = "zai",
        model = "glm-4.6",
        usage = Usage(1, 2, 0, 0, 0, 3, Cost(0.0, 0.0, 0.0, 0.0, 0.0)),
        stopReason = StopReason.STOP,
        timestamp = ts,
    )

    private fun branchedSession(): Session {
        val root = MessageEntry("m0", null, 1L, UserMessage.ofText("a", 1L))
        val left = MessageEntry("m1", "m0", 2L, assistant("left", 2L))
        val right = MessageEntry("m2", "m0", 3L, assistant("right", 3L))
        return Session("sess-1", "t", 1, 2, listOf(root, left, right), "m2")
    }

    @Test
    fun responsesReplayFieldsRoundTripAndStayOptional() {
        val message = assistant("hi", 2L).copy(
            responseId = "resp_1",
            endTurn = true,
            content = listOf(
                TextContent("a", """{"v":1,"id":"msg_1"}"""),
                ThinkingContent("t", """{"type":"reasoning"}"""),
                ToolCall(
                    id = "call|fc_1",
                    name = "edit",
                    arguments = "{}",
                    namespace = "ns-1",
                ),
            ),
        )
        val session = branchedSession().copy(
            entries = listOf(MessageEntry("m1", null, 2L, message)),
            leafId = "m1",
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(session))
        assertEquals(message, (decoded.entries.single() as MessageEntry).message)

        // Older payloads without the optional fields still decode.
        val legacy = SessionCodec.decode(
            SessionCodec.encode(session)
                .replace(",\"endTurn\":true", "")
                .replace(",\"namespace\":\"ns-1\"", "")
                .replace(",\"textSignature\":\"{\\\"v\\\":1,\\\"id\\\":\\\"msg_1\\\"}\"", ""),
        )
        val legacyMessage = (legacy.entries.single() as MessageEntry).message as AssistantMessage
        assertNull(legacyMessage.endTurn)
        assertNull((legacyMessage.content[0] as TextContent).textSignature)
        assertNull((legacyMessage.content[2] as works.resolve.aletheia.ai.core.ToolCall).namespace)
    }

    @Test
    fun toolResultAddedToolNamesRoundTripAndDefaultToEmpty() {
        val result = ToolResultMessage(
            toolCallId = "call|fc_1",
            toolName = "edit",
            content = listOf(TextContent("ok")),
            addedToolNames = listOf("search", "edit"),
        )
        val session = branchedSession().copy(
            entries = listOf(MessageEntry("m1", null, 2L, result)),
            leafId = "m1",
        )
        val encoded = SessionCodec.encode(session)
        val decoded = SessionCodec.decode(encoded)
        assertEquals(result, (decoded.entries.single() as MessageEntry).message)

        // The field is optional; without it the default is emptyList.
        val omitted = SessionCodec.decode(encoded.replace(",\"addedToolNames\":[\"search\",\"edit\"]", ""))
        val omittedResult = (omitted.entries.single() as MessageEntry).message as ToolResultMessage
        assertEquals(emptyList<String>(), omittedResult.addedToolNames)
    }

    @Test
    fun toolResultOmitsAddedToolNamesWhenEmpty() {
        val result = ToolResultMessage(
            toolCallId = "call|fc_1",
            toolName = "edit",
            content = listOf(TextContent("ok")),
        )
        val session = branchedSession().copy(
            entries = listOf(MessageEntry("m1", null, 2L, result)),
            leafId = "m1",
        )
        val encoded = SessionCodec.encode(session)
        assertEquals(false, encoded.contains("addedToolNames"))
    }

    @Test
    fun v2RoundTripPreservesEntriesAndLeafId() {
        val session = branchedSession()
        val decoded = SessionCodec.decode(SessionCodec.encode(session))
        assertEquals(session, decoded)
        assertEquals(listOf("a", "right"), decoded.activeTexts())
    }

    @Test
    fun v1MigratesToChainedEntries() {
        val text = """
            {"format":1,"id":"sess-1","title":"t","createdAt":10,"updatedAt":20,
             "messages":[
               {"role":"user","timestamp":1,"content":[{"type":"text","text":"a"}]},
               {"role":"assistant","timestamp":2,"content":[{"type":"text","text":"b"}],
                "api":"api","provider":"p","model":"m","stopReason":"STOP",
                "usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"reasoning":0,"totalTokens":2,
                         "cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}}}
             ]}
        """.trimIndent()

        val decoded = SessionCodec.decode(text)
        assertEquals("sess-1", decoded.id)
        assertEquals(2, decoded.entries.size)
        assertNull(decoded.entries.first().parentId)
        assertEquals(decoded.entries[0].id, decoded.entries[1].parentId)
        assertEquals(decoded.entries.last().id, decoded.leafId)
        assertEquals(listOf("a", "b"), decoded.activeTexts())
    }

    @Test
    fun v1EmptyMessagesMigrateToEmptyEntriesAndNullLeaf() {
        val text = """{"format":1,"id":"s","title":"t","createdAt":1,"updatedAt":1,"messages":[]}"""
        val decoded = SessionCodec.decode(text)
        assertEquals(0, decoded.entries.size)
        assertNull(decoded.leafId)
        assertEquals(0, decoded.messages.size)
    }

    @Test
    fun unknownVersionRejected() {
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"format":3,"id":"s","title":"t","createdAt":1,"updatedAt":1,"entries":[],"leafId":null}""")
        }
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"format":0,"id":"s","title":"t","createdAt":1,"updatedAt":1,"entries":[],"leafId":null}""")
        }
    }

    @Test
    fun malformedDataRejected() {
        // Not JSON.
        assertFailsWith<SessionDataException> { SessionCodec.decode("{ nope") }
        // Not an object.
        assertFailsWith<SessionDataException> { SessionCodec.decode("[1]") }
        // Missing entries.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1}""")
        }
        // Unknown entry type.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1,
               "entries":[{"type":"compaction","id":"c","timestamp":1}],"leafId":null}""")
        }
        // Entry missing id.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1,
               "entries":[{"type":"message","timestamp":1,"message":{"role":"user","timestamp":0,"content":[]}}],"leafId":null}""")
        }
        // Entry missing timestamp.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1,
               "entries":[{"type":"message","id":"m","message":{"role":"user","timestamp":0,"content":[]}}],"leafId":null}""")
        }
        // Entry missing message.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1,
               "entries":[{"type":"message","id":"m","timestamp":1}],"leafId":null}""")
        }
        // Missing header fields.
        assertFailsWith<SessionDataException> {
            SessionCodec.decode("""{"format":2,"id":"s","title":"t","createdAt":1,"entries":[],"leafId":null}""")
        }
    }

    @Test
    fun parentIdNullAndMissingBothDecodeAsNull() {
        val withNull = SessionCodec.decode(
            """{"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1,
               "entries":[{"type":"message","id":"m","parentId":null,"timestamp":1,
                          "message":{"role":"user","timestamp":0,"content":[]}}],"leafId":null}""",
        )
        assertNull(withNull.entries.single().parentId)

        val without = SessionCodec.decode(
            """{"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1,
               "entries":[{"type":"message","id":"m","timestamp":1,
                          "message":{"role":"user","timestamp":0,"content":[]}}],"leafId":null}""",
        )
        assertNull(without.entries.single().parentId)
    }

    @Test
    fun providerMetadataRoundTrips() {
        val message = AssistantMessage(
            content = listOf(
                TextContent("answer", textSignature = "ts-1"),
                ThinkingContent("th", thinkingSignature = "sig", redacted = true),
                ToolCall("tc-1", "tool", "{}", thoughtSignature = "t_sig"),
            ),
            api = "api",
            provider = "p",
            model = "m",
            usage = Usage(1, 2, 0, 0, 0, 3, Cost(0.0, 0.0, 0.0, 0.0, 0.0)),
            stopReason = StopReason.STOP,
            timestamp = 1L,
        )
        val session = Session("s", "t", 1, 1, listOf(MessageEntry("m0", null, 1L, message)), "m0")
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun providerMetadataAbsentDecodesToDefaults() {
        // v2 session written before replay-metadata fields existed.
        val text = """
            {"format":2,"id":"s","title":"t","createdAt":1,"updatedAt":1,
             "entries":[{"type":"message","id":"m0","timestamp":1,
               "message":{"role":"assistant","timestamp":1,
                "content":[
                  {"type":"text","text":"a"},
                  {"type":"thinking","thinking":"th"},
                  {"type":"toolCall","id":"tc","name":"tool","arguments":"{}"}],
                "api":"api","provider":"p","model":"m","stopReason":"STOP",
                "usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"reasoning":0,"totalTokens":2,
                         "cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}}}}],
             "leafId":"m0"}
        """.trimIndent()
        val content = (SessionCodec.decode(text).messages.single() as AssistantMessage).content
        val textContent = content[0] as TextContent
        val thinking = content[1] as ThinkingContent
        val toolCall = content[2] as ToolCall
        assertNull(textContent.textSignature)
        assertNull(thinking.thinkingSignature)
        assertEquals(false, thinking.redacted)
        assertNull(toolCall.thoughtSignature)
    }

    @Test
    fun absentMetadataFieldsOmittedFromEncoding() {
        val message = AssistantMessage(
            content = listOf(TextContent("a"), ThinkingContent("th"), ToolCall("tc", "tool", "{}")),
            api = "api",
            provider = "p",
            model = "m",
            usage = Usage(1, 2, 0, 0, 0, 3, Cost(0.0, 0.0, 0.0, 0.0, 0.0)),
            stopReason = StopReason.STOP,
            timestamp = 1L,
        )
        val session = Session("s", "t", 1, 1, listOf(MessageEntry("m0", null, 1L, message)), "m0")
        val encoded = SessionCodec.encode(session)
        assertEquals(0, Regex("\"textSignature\"").findAll(encoded).count())
        assertEquals(0, Regex("\"thinkingSignature\"").findAll(encoded).count())
        assertEquals(0, Regex("\"thoughtSignature\"").findAll(encoded).count())
        assertEquals(0, Regex("\"redacted\"").findAll(encoded).count())
    }

    private fun Session.activeTexts(): List<String> =
        Conversation(entries, leafId).activeMessages().map { msg ->
            when (msg) {
                is UserMessage -> msg.content
                is AssistantMessage -> msg.content
                is ToolResultMessage -> msg.content
            }.joinToString("") { c -> (c as TextContent).text }
        }
}
