package works.resolve.pathfinder.codingagent.core.compaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.resolve.pathfinder.agent.CompactionDetails
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.calculateContextTokens
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.SessionEntry

class CompactionTest {

    private var nextId = 0

    private fun createId(): String = "entry-${nextId++}"

    private fun createMockUsage(input: Int, output: Int, cacheRead: Int = 0, cacheWrite: Int = 0) =
        Usage(
            input = input,
            output = output,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            totalTokens = input + output + cacheRead + cacheWrite
        )

    private fun createUserMessage(text: String): UserMessage = UserMessage.ofText(text)

    private fun createAssistantMessage(
        text: String,
        usage: Usage = createMockUsage(100, 50)
    ): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "claude-sonnet-4-5",
        usage = usage,
        stopReason = StopReason.STOP
    )

    private fun createMessageEntry(
        message: works.resolve.pathfinder.ai.Message,
        parentId: String? = null
    ) = MessageEntry(
        id = createId(),
        parentId = parentId,
        timestamp = nextId.toLong(),
        message = message
    )

    @Test
    fun `calculates total context tokens from usage`() {
        assertEquals(1800, calculateContextTokens(createMockUsage(1000, 500, 200, 100)))
        assertEquals(0, calculateContextTokens(createMockUsage(0, 0, 0, 0)))
    }

    @Test
    fun `checks compaction threshold`() {
        val settings =
            CompactionSettings(enabled = true, reserveTokens = 10000, keepRecentTokens = 20000)
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
                user.id
            )
            entries.add(assistant)
            parentId = assistant.id
        }

        val result = findCutPoint(entries, 0, entries.size, 2500)
        assertTrue(entries[result.firstKeptEntryIndex] is MessageEntry)
    }

    @Test
    fun `covers cut-point and turn-start edge cases`() {
        val thinking = works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry(
            id = createId(),
            parentId = null,
            timestamp = nextId.toLong(),
            thinkingLevel = "high"
        )
        val modelChange = works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry(
            id = createId(),
            parentId = thinking.id,
            timestamp = nextId.toLong(),
            provider = "openai",
            modelId = "gpt-4"
        )
        assertEquals(
            CutPointResult(firstKeptEntryIndex = 0, turnStartIndex = -1, isSplitTurn = false),
            findCutPoint(listOf<SessionEntry>(thinking, modelChange), 0, 2, 1)
        )

        val branchSummary = works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry(
            id = createId(),
            parentId = modelChange.id,
            timestamp = nextId.toLong(),
            fromId = "branch",
            summary = "branch summary"
        )
        assertEquals(1, findTurnStartIndex(listOf(thinking, branchSummary), 1, 0))
        assertEquals(-1, findTurnStartIndex(listOf(thinking, modelChange), 1, 0))

        assertEquals(0, findCutPoint(listOf(thinking, branchSummary), 0, 2, 1).firstKeptEntryIndex)

        val toolResult = createMessageEntry(
            ToolResultMessage(
                toolCallId = "call-1",
                toolName = "read",
                content = listOf(TextContent("tool output"))
            )
        )
        assertEquals(
            CutPointResult(firstKeptEntryIndex = 0, turnStartIndex = -1, isSplitTurn = false),
            findCutPoint(listOf(toolResult), 0, 1, 1)
        )

        val assistantOnly = createMessageEntry(createAssistantMessage("assistant"))
        assertEquals(-1, findTurnStartIndex(listOf(assistantOnly), 0, 0))

        val user = createMessageEntry(createUserMessage("user"))
        val assistant = createMessageEntry(createAssistantMessage("assistant"), user.id)
        val result = findCutPoint(listOf(user, assistant), 0, 2, 1)
        assertEquals(0, result.turnStartIndex)
        assertTrue(result.isSplitTurn)
    }

    @Test
    fun `never cuts immediately after a compaction entry`() {
        val user = createMessageEntry(createUserMessage("user"))
        val compaction = works.resolve.pathfinder.codingagent.core.session.CompactionEntry(
            id = createId(),
            parentId = user.id,
            timestamp = nextId.toLong(),
            summary = "summary",
            retainedTail = emptyList(),
            tokensBefore = 1234
        )
        val assistant = createMessageEntry(createAssistantMessage("assistant"), compaction.id)
        assertEquals(
            2,
            findCutPoint(
                listOf<SessionEntry>(user, compaction, assistant),
                0,
                3,
                1
            ).firstKeptEntryIndex
        )
    }

    @Test
    fun `estimates tokens and context usage across supported message roles`() {
        val usage = createMockUsage(10, 5, 3, 2)
        val assistant = createAssistantMessage("assistant", usage)
        val assistantWithThinkingAndTool = assistant.copy(
            content = listOf(
                ThinkingContent("thinking"),
                ToolCall(id = "call-1", name = "read", arguments = """{"path":"file.ts"}""")
            )
        )
        val toolResultWithImage = ToolResultMessage(
            toolCallId = "call-1",
            toolName = "read",
            content = listOf(
                TextContent("tool text"),
                ImageContent(data = "abc", mimeType = "image/png")
            )
        )

        assertTrue(estimateTokens(UserMessage.ofText("plain user")) > 0)
        assertTrue(estimateTokens(assistantWithThinkingAndTool) > 0)
        assertTrue(estimateTokens(toolResultWithImage) > 1000)
        // Upstream also estimates `custom`, `bashExecution`, and unknown roles
        // (the last asserting 0); pathfinder's sealed Message hierarchy has no
        // such roles (see estimateTokens in Compaction.kt). `branchSummary`/
        // `compactionSummary` exist only pre-projected to wrapped user messages
        // (Messages.kt), so those assertions run over the projection.
        assertTrue(estimateTokens(createBranchSummaryMessage("branch", "x", timestamp = 1L)) > 0)
        assertTrue(
            estimateTokens(
                createCompactionSummaryMessage("compact", tokensBefore = 123, timestamp = 1L)
            ) >
                0
        )

        assertEquals(
            usage,
            getLastAssistantUsage(
                listOf(
                    createMessageEntry(createUserMessage("user")),
                    createMessageEntry(assistant)
                )
            )
        )
        assertNull(
            getLastAssistantUsage(
                listOf(
                    createMessageEntry(assistant.copy(stopReason = StopReason.ABORTED)),
                    createMessageEntry(assistant.copy(stopReason = StopReason.ERROR))
                )
            )
        )
        assertEquals(
            usage,
            getLastAssistantUsage(
                listOf(
                    createMessageEntry(createUserMessage("user")),
                    createMessageEntry(assistant),
                    createMessageEntry(createAssistantMessage("partial", createMockUsage(0, 0)))
                )
            )
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
                createAssistantMessage("Partial thinking", createMockUsage(0, 0))
            )
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
                content = listOf(TextContent(longContent))
            )
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
                ToolCall(id = "t1", name = "read", arguments = """{"path":"file.ts"}""")
            ),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            stopReason = StopReason.STOP
        )
        val result = serializeConversation(listOf(UserMessage.ofText("hi"), assistant))
        assertEquals(
            "[User]: hi\n\n" +
                "[Assistant thinking]: let me think\n\n" +
                "[Assistant]: answer\n\n" +
                """[Assistant tool calls]: read(path="file.ts")""",
            result
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
                ToolCall(id = "5", name = "read", arguments = "not json")
            ),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            stopReason = StopReason.STOP
        )

        val fileOps = extractFileOperations(listOf(assistantWithCalls))
        assertEquals(setOf("a.ts"), fileOps.read)
        assertEquals(setOf("b.ts"), fileOps.written)
        assertEquals(setOf("c.ts"), fileOps.edited)
    }

    @Test
    fun `extractFileOperations carries previous compaction details`() {
        val assistant = AssistantMessage(
            content = listOf(
                ToolCall(id = "1", name = "read", arguments = """{"path":"new.ts"}""")
            ),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            stopReason = StopReason.STOP
        )
        val fileOps = extractFileOperations(
            listOf(assistant),
            prevCompactionDetails = CompactionDetails(
                readFiles = listOf("old-read.ts"),
                modifiedFiles = listOf("old-edit.ts")
            )
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
            formatFileOperations(readFiles, modifiedFiles)
        )
        assertEquals("", formatFileOperations(emptyList(), emptyList()))
    }

    @Test
    fun `summarization system prompt matches upstream verbatim`() {
        assertEquals(
            "You are a context summarization assistant. Your task is to read a " +
                "conversation between a user and an AI assistant, then produce a " +
                "structured summary " +
                "following the exact format specified.\n" +
                "\n" +
                "Do NOT continue the conversation. Do NOT respond to any questions in the " +
                "conversation. ONLY output the structured summary.",
            SUMMARIZATION_SYSTEM_PROMPT
        )
        assertNotEquals("", SUMMARIZATION_SYSTEM_PROMPT)
    }

    @Test
    fun `default settings match upstream`() {
        assertEquals(
            CompactionSettings(enabled = true, reserveTokens = 16384, keepRecentTokens = 20000),
            DEFAULT_COMPACTION_SETTINGS
        )
    }
}
