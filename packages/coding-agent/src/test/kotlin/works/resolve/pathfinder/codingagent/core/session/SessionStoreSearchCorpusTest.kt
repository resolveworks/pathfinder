package works.resolve.pathfinder.codingagent.core.session

import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock

class SessionStoreSearchCorpusTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val clock = FakeClock(1_000)
    private var nextId = 0
    private lateinit var root: File

    private fun newStore(maxFileBytes: Long = SessionStore.MAX_FILE_BYTES): SessionStore {
        if (!::root.isInitialized) root = tmpFolder.newFolder("sessions")
        return SessionStore(
            root = root,
            clock = clock,
            idFactory = { "sess-${nextId++}" },
            maxFileBytes = maxFileBytes
        )
    }

    private fun transcript(): List<Message> = listOf(
        UserMessage.ofText("hello", 1L),
        works.resolve.pathfinder.ai.AssistantMessage(
            content = listOf(
                works.resolve.pathfinder.ai.ThinkingContent(
                    thinking = "secret",
                    thinkingSignature = "sig"
                ),
                TextContent("visible answer"),
                ToolCall(id = "call-1", name = "get_weather", arguments = """{"city":"Oslo"}""")
            ),
            api = "openai-completions",
            provider = "zai",
            model = "glm-4.6",
            timestamp = 2L
        ),
        ToolResultMessage(
            toolCallId = "call-1",
            toolName = "get_weather",
            content = listOf(TextContent("tool output text")),
            isError = false,
            timestamp = 3L
        )
    )

    @Test
    fun extractsUserAndAssistantTextOnly() = runTest {
        val store = newStore()
        val created = store.create("titled")
        store.save(created.withMessages(transcript()))

        val corpus = newStore().searchCorpus()
        val value = corpus.getValue(created.id)
        assertEquals("${created.id} titled hello visible answer", value)
        assertFalse(value.contains("secret"))
        assertFalse(value.contains("Oslo"))
        assertFalse(value.contains("tool output text"))
    }

    @Test
    fun includesEntriesOnNonActiveBranch() = runTest {
        val store = newStore()
        val created = store.create("branchy")
        val rootEntry = MessageEntry("m0", 1, null, 1L, UserMessage.ofText("a", 1L))
        val left = MessageEntry("m1", 2, "m0", 2L, UserMessage.ofText("b-left", 2L))
        val right = MessageEntry("m2", 3, "m0", 3L, UserMessage.ofText("c-right", 3L))
        store.save(created.copy(entries = listOf(rootEntry, left, right), leafId = "m2"))

        val corpus = newStore().searchCorpus()
        assertEquals("${created.id} branchy a b-left c-right", corpus.getValue(created.id))
    }

    @Test
    fun latestNameFactWins() = runTest {
        val store = newStore()
        val created = store.create("original")
        store.save(
            created.withMessages(listOf(UserMessage.ofText("hi", 1L))).copy(title = "renamed")
        )

        val corpus = newStore().searchCorpus()
        assertEquals("${created.id} renamed hi", corpus.getValue(created.id))
    }

    @Test
    fun malformedMidFileLineSkippedAndLaterLinesScanned() = runTest {
        val store = newStore()
        val created = store.create("corrupt")
        store.save(created.withMessages(listOf(UserMessage.ofText("before", 1L))))
        val file = File(root, "${created.id}.jsonl")
        // header + name fact + 1 entry = 3 lines; next seq is 4.
        file.appendText("""{"kind":"fact","seq":4,"broken""" + "\n")
        file.appendText(
            JsonlCodec.encodeMutation(
                SessionMutation.Entry(
                    lane = SessionState.LANE_MAIN,
                    entry = MessageEntry("after-1", 5, "x", 5L, UserMessage.ofText("after", 5L))
                )
            )
        )

        val corpus = newStore().searchCorpus()
        assertEquals("${created.id} corrupt before after", corpus.getValue(created.id))
    }

    @Test
    fun badHeaderSkipsFile() = runTest {
        val store = newStore()
        val created = store.create("good")
        store.save(created.withMessages(listOf(UserMessage.ofText("kept", 1L))))
        File(root, "broken.jsonl").writeText("not json\n")

        val corpus = newStore().searchCorpus()
        assertEquals(setOf(created.id), corpus.keys)
        assertEquals("${created.id} good kept", corpus.getValue(created.id))
    }

    @Test
    fun oversizedFileSkipped() = runTest {
        val store = newStore(maxFileBytes = 4L * 1024 * 1024)
        val created = store.create("big")
        val message = "x".repeat(1024)
        store.save(
            created.withMessages(
                (1..8_000).map {
                    UserMessage.ofText(message, it.toLong())
                }
            )
        )
        assertTrue(File(root, "${created.id}.jsonl").length() > 4L * 1024 * 1024)

        val corpus = newStore(maxFileBytes = 4L * 1024 * 1024).searchCorpus()
        assertFalse(corpus.containsKey(created.id))
    }

    @Test
    fun headerIdMismatchSkipsFile() = runTest {
        val store = newStore()
        val created = store.create("fine")
        store.save(created.withMessages(listOf(UserMessage.ofText("kept", 1L))))
        val other = File(root, "other.jsonl")
        other.writeText(
            JsonlCodec.encodeHeader(JsonlCodec.JsonlV4Header(id = "mismatched", createdAt = 1L)) +
                JsonlCodec.encodeMutation(
                    SessionMutation.Fact.Name(1, "ghost")
                )
        )

        val corpus = newStore().searchCorpus()
        assertEquals(setOf(created.id), corpus.keys)
    }

    @Test
    fun recordLaneAndLabelMutationsContributeNothing() = runTest {
        val store = newStore()
        val created = store.create("neutral")
        val file = File(root, "${created.id}.jsonl")
        // header + name fact = 2 lines; next seq is 3.
        file.appendText(
            JsonlCodec.encodeMutation(
                SessionMutation.Record(
                    LaneRecord.OperationStartedRecord(
                        id = "run-1",
                        lane = SessionState.LANE_MAIN,
                        seq = 3,
                        timestamp = 3L,
                        intent = OperationIntent.run()
                    )
                )
            )
        )
        file.appendText(
            JsonlCodec.encodeMutation(
                SessionMutation.Fact.Label(4, "nonexistent", "bookmark")
            )
        )
        file.appendText(
            JsonlCodec.encodeMutation(SessionMutation.Lane(5, SessionState.LANE_MAIN, null))
        )
        store.save(created.withMessages(listOf(UserMessage.ofText("only", 1L))))

        val corpus = newStore().searchCorpus()
        assertEquals("${created.id} neutral only", corpus.getValue(created.id))
    }

    @Test
    fun emptySessionAppearsWithIdAndTitle() = runTest {
        val store = newStore()
        val created = store.create("empty title")

        val corpus = newStore().searchCorpus()
        assertEquals(mapOf(created.id to "${created.id} empty title "), corpus)
    }
}
