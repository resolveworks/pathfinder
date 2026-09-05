package works.resolve.pathfinder.codingagent.core.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.codingagent.core.compaction.buildSessionContext

/**
 * Port of pi's save-entry.test.ts. That file's single case exercises
 * appendCustomEntry; custom entries are deliberately not ported, so the
 * shape is preserved with a model-change entry standing in for the custom
 * entry: save a message, save a non-message entry, save another message,
 * then check entries, branch path, and context projection.
 */
class SaveEntryTest {

    private val clock = FakeClock()
    private var sessionCounter = 0
    private var entryCounter = 0

    private fun user(text: String) = UserMessage.ofText(text, clock.now().toEpochMilliseconds())

    private fun assistant(text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "claude-test",
        usage = Usage(1, 1, totalTokens = 2, cost = Cost()),
        stopReason = StopReason.STOP,
        timestamp = clock.now().toEpochMilliseconds()
    )

    private suspend fun manager(dir: File): SessionManager = SessionManager.create(
        dir = dir,
        clock = clock,
        idFactory = { "sess-${sessionCounter++}" },
        ioDispatcher = Dispatchers.Unconfined,
        entryIdFactory = { "e${entryCounter++}" }
    )

    @Test
    fun `saves entries and includes them in tree traversal`() = runTest {
        val dir = createTempDirectory()
        val m = manager(dir)

        m.appendMessage(user("hello"))
        val firstId = m.entries.last().id
        m.appendModelChange("anthropic", "claude-test")
        val modelChangeId = m.entries.last().id
        m.appendMessage(assistant("hi"))
        val msg2Id = m.entries.last().id

        assertEquals(3, m.entries.size)

        val modelChange = assertIs<ModelChangeEntry>(m.conversation.entry(modelChangeId))
        assertEquals("anthropic", modelChange.provider)
        assertEquals("claude-test", modelChange.modelId)
        assertEquals(firstId, modelChange.parentId)

        val path = m.conversation.activeEntries()
        assertEquals(listOf(firstId, modelChangeId, msg2Id), path.map { it.id })

        val context = buildSessionContext(path)
        assertEquals(2, context.size)
        assertTrue(context.all { it !is AssistantMessage || it.model == "claude-test" })
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("save-entry-test").toFile()
}
