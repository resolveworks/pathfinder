package works.resolve.pathfinder.agent.compaction

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.utils.calculateContextTokens
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.SessionEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ports of the pure-scope cases from pi's
 * packages/agent/test/harness/compaction.test.ts. Test data builders mirror
 * the upstream helpers adapted to pathfinder types; cases for LLM-calling
 * parts (generateSummary/prepareCompaction/compact) are out of scope for this
 * chunk.
 */
class CompactionTest {

    private var nextId = 0

    private fun createId(): String = "entry-${nextId++}"

    private fun createMockUsage(input: Int, output: Int, cacheRead: Int = 0, cacheWrite: Int = 0) = Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        totalTokens = input + output + cacheRead + cacheWrite,
    )

    private fun createUserMessage(text: String): UserMessage = UserMessage.ofText(text)

    private fun createAssistantMessage(
        text: String,
        usage: Usage = createMockUsage(100, 50),
    ): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "claude-sonnet-4-5",
        usage = usage,
        stopReason = StopReason.STOP,
    )

    private fun createMessageEntry(message: works.resolve.pathfinder.ai.core.Message, parentId: String? = null) =
        MessageEntry(id = createId(), parentId = parentId, timestamp = nextId.toLong(), message = message)

    @Test
    fun `calculates total context tokens from usage`() {
        // calculateContextTokens is reused from ai.utils.TokenEstimate; same
        // expectations as upstream.
        assertEquals(1800, calculateContextTokens(createMockUsage(1000, 500, 200, 100)))
        assertEquals(0, calculateContextTokens(createMockUsage(0, 0, 0, 0)))
    }

    @Test
    fun `checks compaction threshold`() {
        val settings = CompactionSettings(enabled = true, reserveTokens = 10000, keepRecentTokens = 20000)
        assertTrue(shouldCompact(95000, 100000, settings))
        assertFalse(shouldCompact(89000, 100000, settings))
        assertFalse(shouldCompact(95000, 100000, settings.copy(enabled = false)))
    }

    @Test
    fun `finds a cut point based on token differences`() {
        val entries = mutableListOf<SessionEntry>()
        var parentId: String? = null
        for (i in 0 until 10) {
            val user = createMessageEntry(createUserMessage("User $i"), parentId)
            entries.add(user)
            val assistant = createMessageEntry(
                createAssistantMessage("Assistant $i", createMockUsage(0, 100, (i + 1) * 1000, 0)),
                user.id,
            )
            entries.add(assistant)
            parentId = assistant.id
        }

        val result = findCutPoint(entries, 0, entries.size, 2500)
        assertTrue(entries[result.firstKeptEntryIndex] is MessageEntry)
    }

    @Test
    fun `covers cut-point and turn-start edge cases`() {
        // Upstream subcases using thinking_level/model_change/branch_summary/
        // compaction entries are omitted: those SessionEntry kinds do not
        // exist in pathfinder yet (see Compaction.kt adaptation notes).

        val toolResult = createMessageEntry(
            ToolResultMessage(
                toolCallId = "call-1",
                toolName = "read",
                content = listOf(TextContent("tool output")),
            ),
        )
        assertEquals(
            CutPointResult(firstKeptEntryIndex = 0, turnStartIndex = -1, isSplitTurn = false),
            findCutPoint(listOf(toolResult), 0, 1, 1),
        )

        // findTurnStartIndex returns -1 when no user message precedes.
        val assistantOnly = createMessageEntry(createAssistantMessage("assistant"))
        assertEquals(-1, findTurnStartIndex(listOf(assistantOnly), 0, 0))

        // A cut on an assistant entry splits the turn started by its user message.
        val user = createMessageEntry(createUserMessage("user"))
        val assistant = createMessageEntry(createAssistantMessage("assistant"), user.id)
        val result = findCutPoint(listOf(user, assistant), 0, 2, 1)
        assertEquals(0, result.turnStartIndex)
        assertTrue(result.isSplitTurn)
    }

    @Test
    fun `estimates tokens and context usage across supported message roles`() {
        val usage = createMockUsage(10, 5, 3, 2)
        val assistant = createAssistantMessage("assistant", usage)
        val assistantWithThinkingAndTool = assistant.copy(
            content = listOf(
                ThinkingContent("thinking"),
                ToolCall(id = "call-1", name = "read", arguments = """{"path":"file.ts"}"""),
            ),
        )
        val toolResultWithImage = ToolResultMessage(
            toolCallId = "call-1",
            toolName = "read",
            content = listOf(
                TextContent("tool text"),
                ImageContent(data = "abc", mimeType = "image/png"),
            ),
        )

        // Upstream also covers custom/bashExecution/branchSummary/
        // compactionSummary roles, which do not exist in pathfinder's Message.
        assertTrue(estimateTokens(UserMessage.ofText("plain user")) > 0)
        assertTrue(estimateTokens(assistantWithThinkingAndTool) > 0)
        assertTrue(estimateTokens(toolResultWithImage) > 1000)

        assertEquals(
            usage,
            getLastAssistantUsage(
                listOf(
                    createMessageEntry(createUserMessage("user")),
                    createMessageEntry(assistant),
                ),
            ),
        )
        assertNull(
            getLastAssistantUsage(
                listOf(
                    createMessageEntry(assistant.copy(stopReason = StopReason.ABORTED)),
                    createMessageEntry(assistant.copy(stopReason = StopReason.ERROR)),
                ),
            ),
        )
        assertEquals(
            usage,
            getLastAssistantUsage(
                listOf(
                    createMessageEntry(createUserMessage("user")),
                    createMessageEntry(assistant),
                    createMessageEntry(createAssistantMessage("partial", createMockUsage(0, 0))),
                ),
            ),
        )

        assertNull(estimateContextTokens(listOf(createUserMessage("no usage"))).lastUsageIndex)
        val withTail = estimateContextTokens(listOf(assistant, createUserMessage("tail")))
        assertEquals(20, withTail.usageTokens)
        assertEquals(0, withTail.lastUsageIndex)
        val estimate = estimateContextTokens(
            listOf(
                createUserMessage("Hello"),
                assistant,
                createUserMessage("continue"),
                createAssistantMessage("Partial thinking", createMockUsage(0, 0)),
            ),
        )
        assertEquals(20, estimate.usageTokens)
        assertEquals(1, estimate.lastUsageIndex)
        assertTrue(estimate.trailingTokens > 0)
        assertEquals(20 + estimate.trailingTokens, estimate.tokens)
    }

    @Test
    fun `serializes conversation with truncated tool results`() {
        val longContent = "x".repeat(5000)
        val messages = listOf(
            ToolResultMessage(
                toolCallId = "tc1",
                toolName = "read",
                content = listOf(TextContent(longContent)),
            ),
        )
        val result = serializeConversation(messages)
        assertTrue("[Tool result]:" in result)
        assertTrue("[... 3000 more characters truncated]" in result)
    }

    @Test
    fun `serializes conversation with user text, thinking, and tool calls`() {
        val assistant = AssistantMessage(
            content = listOf(
                ThinkingContent("let me think"),
                TextContent("answer"),
                ToolCall(id = "t1", name = "read", arguments = """{"path":"file.ts"}"""),
            ),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            stopReason = StopReason.STOP,
        )
        val result = serializeConversation(listOf(UserMessage.ofText("hi"), assistant))
        assertEquals(
            "[User]: hi\n\n" +
                "[Assistant thinking]: let me think\n\n" +
                "[Assistant]: answer\n\n" +
                """[Assistant tool calls]: read(path="file.ts")""",
            result,
        )
    }

    @Test
    fun `extracts file operations from tool calls`() {
        val assistantWithCalls = AssistantMessage(
            content = listOf(
                ToolCall(id = "1", name = "read", arguments = """{"path":"a.ts"}"""),
                ToolCall(id = "2", name = "write", arguments = """{"path":"b.ts"}"""),
                ToolCall(id = "3", name = "edit", arguments = """{"path":"c.ts"}"""),
                ToolCall(id = "4", name = "read", arguments = """{"other":"no path"}"""),
                ToolCall(id = "5", name = "read", arguments = "not json"),
            ),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            stopReason = StopReason.STOP,
        )

        val fileOps = extractFileOperations(listOf(assistantWithCalls))
        assertEquals(setOf("a.ts"), fileOps.read)
        assertEquals(setOf("b.ts"), fileOps.written)
        assertEquals(setOf("c.ts"), fileOps.edited)
    }

    @Test
    fun `extractFileOperations carries previous compaction details`() {
        val assistant = AssistantMessage(
            content = listOf(ToolCall(id = "1", name = "read", arguments = """{"path":"new.ts"}""")),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            stopReason = StopReason.STOP,
        )
        val fileOps = extractFileOperations(
            listOf(assistant),
            prevCompactionDetails = CompactionDetails(readFiles = listOf("old-read.ts"), modifiedFiles = listOf("old-edit.ts")),
        )
        assertEquals(setOf("old-read.ts", "new.ts"), fileOps.read)
        assertEquals(setOf("old-edit.ts"), fileOps.edited)
    }

    @Test
    fun `computes and formats file lists`() {
        val fileOps = createFileOps()
        fileOps.read.add("read-only.ts")
        fileOps.read.add("both.ts")
        fileOps.edited.add("both.ts")
        fileOps.written.add("written.ts")

        val (readFiles, modifiedFiles) = computeFileLists(fileOps)
        assertEquals(listOf("read-only.ts"), readFiles)
        assertEquals(listOf("both.ts", "written.ts"), modifiedFiles)

        assertEquals(
            "\n\n<read-files>\nread-only.ts\n</read-files>\n\n<modified-files>\nboth.ts\nwritten.ts\n</modified-files>",
            formatFileOperations(readFiles, modifiedFiles),
        )
        assertEquals("", formatFileOperations(emptyList(), emptyList()))
    }

    @Test
    fun `summarization system prompt matches upstream verbatim`() {
        assertEquals(
            "You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.\n" +
                "\n" +
                "Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary.",
            SUMMARIZATION_SYSTEM_PROMPT,
        )
        assertNotEquals("", SUMMARIZATION_SYSTEM_PROMPT)
    }

    @Test
    fun `default settings match upstream`() {
        assertEquals(
            CompactionSettings(enabled = true, reserveTokens = 16384, keepRecentTokens = 20000),
            DEFAULT_COMPACTION_SETTINGS,
        )
    }
}
